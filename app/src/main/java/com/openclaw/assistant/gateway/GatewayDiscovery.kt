package com.openclaw.assistant.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.CancellationSignal
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.PTRRecord
import org.xbill.DNS.Record
import org.xbill.DNS.Rcode
import org.xbill.DNS.Resolver
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TextParseException
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

@Suppress("DEPRECATION")
class GatewayDiscovery(
  context: Context,
  private val scope: CoroutineScope,
) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val dns = DnsResolver.getInstance()
  private val serviceType = "_openclaw-gw._tcp."
  private val wideAreaDomain = System.getenv("OPENCLAW_WIDE_AREA_DOMAIN")
  private val logTag = "OpenClaw/GatewayDiscovery"

  private val localById = ConcurrentHashMap<String, GatewayEndpoint>()
  private val unicastById = ConcurrentHashMap<String, GatewayEndpoint>()
  private val _gateways = MutableStateFlow<List<GatewayEndpoint>>(emptyList())
  val gateways: StateFlow<List<GatewayEndpoint>> = _gateways.asStateFlow()

  private val _statusText = MutableStateFlow("Searching…")
  val statusText: StateFlow<String> = _statusText.asStateFlow()

  private var unicastJob: Job? = null
  private val dnsExecutor: Executor = Executors.newCachedThreadPool()

  @Volatile private var lastWideAreaRcode: Int? = null
  @Volatile private var lastWideAreaCount: Int = 0

  private val dnsFailureTracker = DnsFailureTracker()

  private val discoveryListener =
    object : NsdManager.DiscoveryListener {
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(logTag, "Local discovery start failed: errorCode=$errorCode")
      }

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(logTag, "Local discovery stop failed: errorCode=$errorCode")
      }

      override fun onDiscoveryStarted(serviceType: String) {
        Log.i(logTag, "Local discovery started for $serviceType")
      }

      override fun onDiscoveryStopped(serviceType: String) {
        Log.i(logTag, "Local discovery stopped for $serviceType")
      }

      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (serviceInfo.serviceType != this@GatewayDiscovery.serviceType) return
        resolve(serviceInfo)
      }

      override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val serviceName = BonjourEscapes.decode(serviceInfo.serviceName)
        val id = stableId(serviceName, "local.")
        localById.remove(id)
        publish()
      }
    }

  init {
    startLocalDiscovery()
    if (!wideAreaDomain.isNullOrBlank()) {
      startUnicastDiscovery(wideAreaDomain)
    }
  }

  private fun startLocalDiscovery() {
    try {
      nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    } catch (e: Throwable) {
      Log.w(logTag, "Failed to start local discovery", e)
    }
  }

  private fun stopLocalDiscovery() {
    try {
      nsd.stopServiceDiscovery(discoveryListener)
    } catch (e: Throwable) {
      Log.w(logTag, "Failed to stop local discovery", e)
    }
  }

  private fun startUnicastDiscovery(domain: String) {
    unicastJob =
      scope.launch(Dispatchers.IO) {
        while (true) {
          val delayMs = dnsFailureTracker.getBackoffDelayMs()
          if (delayMs > BASE_RETRY_DELAY_MS) {
            Log.d(logTag, "DNS backoff active: waiting ${delayMs}ms before next query")
          }
          delay(delayMs)

          val startTime = System.currentTimeMillis()
          try {
            refreshUnicast(domain)
            dnsFailureTracker.recordSuccess()
          } catch (e: Throwable) {
            dnsFailureTracker.recordFailure()
            Log.e(logTag, "Wide-area discovery failed (consecutiveFailures=${dnsFailureTracker.consecutiveFailures})", e)
          }
        }
      }
  }

  private fun resolve(serviceInfo: NsdServiceInfo) {
    nsd.resolveService(
      serviceInfo,
      object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          Log.w(logTag, "Service resolve failed: ${serviceInfo.serviceName}, errorCode=$errorCode")
        }

        override fun onServiceResolved(resolved: NsdServiceInfo) {
          val host = resolved.host?.hostAddress ?: return
          val port = resolved.port
          if (port <= 0) return

          val rawServiceName = resolved.serviceName
          val serviceName = BonjourEscapes.decode(rawServiceName)
          val displayName = BonjourEscapes.decode(txt(resolved, "displayName") ?: serviceName)
          val lanHost = txt(resolved, "lanHost")
          val tailnetDns = txt(resolved, "tailnetDns")
          val gatewayPort = txtInt(resolved, "gatewayPort")
          val canvasPort = txtInt(resolved, "canvasPort")
          val tlsEnabled = txtBool(resolved, "gatewayTls")
          val tlsFingerprint = txt(resolved, "gatewayTlsSha256")
          val id = stableId(serviceName, "local.")
          localById[id] =
            GatewayEndpoint(
              stableId = id,
              name = displayName,
              host = host,
              port = port,
              lanHost = lanHost,
              tailnetDns = tailnetDns,
              gatewayPort = gatewayPort,
              canvasPort = canvasPort,
              tlsEnabled = tlsEnabled,
              tlsFingerprintSha256 = tlsFingerprint,
            )
          publish()
        }
      },
    )
  }

  private fun publish() {
    _gateways.value = (localById.values + unicastById.values).sortedBy { it.name.lowercase() }
    _statusText.value = buildStatusText()
  }

  private fun buildStatusText(): String {
    val localCount = localById.size
    val wideRcode = lastWideAreaRcode
    val wideCount = lastWideAreaCount

    val wide =
      when (wideRcode) {
        null -> "Wide: ?"
        Rcode.NOERROR -> "Wide: $wideCount"
        Rcode.NXDOMAIN -> "Wide: NXDOMAIN"
        else -> "Wide: ${Rcode.string(wideRcode)}"
      }

    return when {
      localCount == 0 && wideRcode == null -> "Searching for gateways…"
      localCount == 0 -> "$wide"
      else -> "Local: $localCount • $wide"
    }
  }

  private fun stableId(serviceName: String, domain: String): String {
    return "${serviceType}|${domain}|${normalizeName(serviceName)}"
  }

  private fun normalizeName(raw: String): String {
    return raw.trim().split(Regex("\\s+")).joinToString(" ")
  }

  private fun txt(info: NsdServiceInfo, key: String): String? {
    val bytes = info.attributes[key] ?: return null
    return try {
      String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
    } catch (_: Throwable) {
      null
    }
  }

  private fun txtInt(info: NsdServiceInfo, key: String): Int? {
    return txt(info, key)?.toIntOrNull()
  }

  private fun txtBool(info: NsdServiceInfo, key: String): Boolean {
    val raw = txt(info, key)?.trim()?.lowercase() ?: return false
    return raw == "1" || raw == "true" || raw == "yes"
  }

  private suspend fun refreshUnicast(domain: String) {
    val ptrName = "${serviceType}${domain}"
    val ptrMsg = lookupUnicastMessage(ptrName, Type.PTR)

    if (ptrMsg == null) {
      Log.w(logTag, "DNS PTR lookup failed for $ptrName")
      clearUnicastEndpoints()
      return
    }

    val ptrRecords = records(ptrMsg, Section.ANSWER).mapNotNull { it as? PTRRecord }

    if (ptrRecords.isEmpty() && ptrMsg.header.rcode != Rcode.NOERROR) {
      Log.w(logTag, "DNS PTR lookup returned rcode=${Rcode.string(ptrMsg.header.rcode)} for $ptrName")
      lastWideAreaRcode = ptrMsg.header.rcode
      clearUnicastEndpoints()
      return
    }

    val next = LinkedHashMap<String, GatewayEndpoint>()
    for (ptr in ptrRecords) {
      val instanceFqdn = ptr.target.toString()
      val srv =
        recordByName(ptrMsg, instanceFqdn, Type.SRV) as? SRVRecord
          ?: run {
            val msg = lookupUnicastMessage(instanceFqdn, Type.SRV)
            if (msg == null) {
              Log.w(logTag, "DNS SRV lookup failed for $instanceFqdn")
            }
            recordByName(msg, instanceFqdn, Type.SRV) as? SRVRecord
          }
          ?: continue
      val port = srv.port
      if (port <= 0) continue

      val targetFqdn = srv.target.toString()
      val host =
        resolveHostFromMessage(ptrMsg, targetFqdn)
          ?: resolveHostFromMessage(lookupUnicastMessage(instanceFqdn, Type.SRV), targetFqdn)
          ?: resolveHostUnicast(targetFqdn)
          ?: run {
            Log.w(logTag, "Could not resolve host for $targetFqdn")
            continue
          }

      val txtFromPtr =
        recordsByName(ptrMsg, Section.ADDITIONAL)[keyName(instanceFqdn)]
          .orEmpty()
          .mapNotNull { it as? TXTRecord }
      val txt =
        if (txtFromPtr.isNotEmpty()) {
          txtFromPtr
        } else {
          val msg = lookupUnicastMessage(instanceFqdn, Type.TXT)
          if (msg == null) {
            Log.w(logTag, "DNS TXT lookup failed for $instanceFqdn")
          }
          records(msg, Section.ANSWER).mapNotNull { it as? TXTRecord }
        }
      val instanceName = BonjourEscapes.decode(decodeInstanceName(instanceFqdn, domain))
      val displayName = BonjourEscapes.decode(txtValue(txt, "displayName") ?: instanceName)
      val lanHost = txtValue(txt, "lanHost")
      val tailnetDns = txtValue(txt, "tailnetDns")
      val gatewayPort = txtIntValue(txt, "gatewayPort")
      val canvasPort = txtIntValue(txt, "canvasPort")
      val tlsEnabled = txtBoolValue(txt, "gatewayTls")
      val tlsFingerprint = txtValue(txt, "gatewayTlsSha256")
      val id = stableId(instanceName, domain)
      next[id] =
        GatewayEndpoint(
          stableId = id,
          name = displayName,
          host = host,
          port = port,
          lanHost = lanHost,
          tailnetDns = tailnetDns,
          gatewayPort = gatewayPort,
          canvasPort = canvasPort,
          tlsEnabled = tlsEnabled,
          tlsFingerprintSha256 = tlsFingerprint,
        )
    }

    unicastById.clear()
    unicastById.putAll(next)

    if (next.isNotEmpty()) {
      lastWideAreaRcode = Rcode.NOERROR
    } else {
      lastWideAreaRcode = ptrMsg.header.rcode
    }
    lastWideAreaCount = next.size
    publish()

    if (next.isEmpty()) {
      Log.d(
        logTag,
        "wide-area discovery: 0 results for $ptrName (rcode=${Rcode.string(ptrMsg.header.rcode)})",
      )
    } else {
      Log.d(logTag, "wide-area discovery: found ${next.size} gateway(s) for $domain")
    }
  }

  private fun clearUnicastEndpoints() {
    unicastById.clear()
    lastWideAreaCount = 0
    publish()
  }

  private fun decodeInstanceName(instanceFqdn: String, domain: String): String {
    val suffix = "${serviceType}${domain}"
    val withoutSuffix =
      if (instanceFqdn.endsWith(suffix)) {
        instanceFqdn.removeSuffix(suffix)
      } else {
        instanceFqdn.substringBefore(serviceType)
      }
    return normalizeName(stripTrailingDot(withoutSuffix))
  }

  private fun stripTrailingDot(raw: String): String {
    return raw.removeSuffix(".")
  }

  private suspend fun lookupUnicastMessage(name: String, type: Int): Message? {
    val queryStartTime = System.currentTimeMillis()
    val query =
      try {
        Message.newQuery(
          org.xbill.DNS.Record.newRecord(
            Name.fromString(name),
            type,
            DClass.IN,
          ),
        )
      } catch (e: TextParseException) {
        Log.w(logTag, "Failed to build DNS query for $name type=$type", e)
        return null
      }

    val systemStartTime = System.currentTimeMillis()
    val system = queryViaSystemDns(query)
    val systemDuration = System.currentTimeMillis() - systemStartTime

    if (records(system, Section.ANSWER).any { it.type == type }) {
      Log.v(logTag, "DNS query via system DNS succeeded: $name type=$type in ${systemDuration}ms")
      return system
    }

    val direct = createDirectResolver()
    if (direct == null) {
      Log.v(logTag, "DNS query using system DNS (no direct resolver): $name type=$type in ${systemDuration}ms")
      return system
    }

    return try {
      val directStartTime = System.currentTimeMillis()
      val msg = direct.send(query)
      val directDuration = System.currentTimeMillis() - directStartTime
      if (records(msg, Section.ANSWER).any { it.type == type }) {
        Log.v(logTag, "DNS query via direct resolver succeeded: $name type=$type in ${directDuration}ms")
        msg
      } else {
        Log.v(logTag, "DNS query using system DNS (direct had no answers): $name type=$type in ${systemDuration}ms")
        system
      }
    } catch (e: Throwable) {
      Log.w(logTag, "Direct DNS query failed for $name type=$type after ${System.currentTimeMillis() - systemStartTime}ms", e)
      system
    }
  }

  private suspend fun queryViaSystemDns(query: Message): Message? {
    val network = preferredDnsNetwork()
    val bytes =
      try {
        rawQuery(network, query.toWire())
      } catch (e: Throwable) {
        Log.w(logTag, "System DNS query failed", e)
        return null
      }

    return try {
      Message(bytes)
    } catch (e: IOException) {
      Log.w(logTag, "Failed to parse DNS response", e)
      null
    }
  }

  private fun records(msg: Message?, section: Int): List<Record> {
    return msg?.getSectionArray(section)?.toList() ?: emptyList()
  }

  private fun keyName(raw: String): String {
    return raw.trim().lowercase()
  }

  private fun recordsByName(msg: Message, section: Int): Map<String, List<Record>> {
    val next = LinkedHashMap<String, MutableList<Record>>()
    for (r in records(msg, section)) {
      val name = r.name?.toString() ?: continue
      next.getOrPut(keyName(name)) { mutableListOf() }.add(r)
    }
    return next
  }

  private fun recordByName(msg: Message, fqdn: String, type: Int): Record? {
    val key = keyName(fqdn)
    val byNameAnswer = recordsByName(msg, Section.ANSWER)
    val fromAnswer = byNameAnswer[key].orEmpty().firstOrNull { it.type == type }
    if (fromAnswer != null) return fromAnswer

    val byNameAdditional = recordsByName(msg, Section.ADDITIONAL)
    return byNameAdditional[key].orEmpty().firstOrNull { it.type == type }
  }

  private fun resolveHostFromMessage(msg: Message?, hostname: String): String? {
    val m = msg ?: return null
    val key = keyName(hostname)
    val additional = recordsByName(m, Section.ADDITIONAL)[key].orEmpty()
    val a = additional.mapNotNull { it as? ARecord }.mapNotNull { it.address?.hostAddress }
    val aaaa = additional.mapNotNull { it as? AAAARecord }.mapNotNull { it.address?.hostAddress }
    return a.firstOrNull() ?: aaaa.firstOrNull()
  }

  private fun preferredDnsNetwork(): android.net.Network? {
    val cm = connectivity ?: return null

    cm.allNetworks.firstOrNull { n ->
      val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }?.let { return it }

    return cm.activeNetwork
  }

  private fun createDirectResolver(): Resolver? {
    val cm = connectivity ?: return null

    val candidateNetworks =
      buildList {
        cm.allNetworks
          .firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
          }?.let(::add)
        cm.activeNetwork?.let(::add)
      }.distinct()

    val servers =
      candidateNetworks
        .asSequence()
        .flatMap { n ->
          cm.getLinkProperties(n)?.dnsServers?.asSequence() ?: emptySequence()
        }
        .distinctBy { it.hostAddress ?: it.toString() }
        .toList()
    if (servers.isEmpty()) return null

    return try {
      val resolvers =
        servers.mapNotNull { addr ->
          try {
            SimpleResolver().apply {
              setAddress(InetSocketAddress(addr, 53))
              setTimeout(3)
            }
          } catch (_: Throwable) {
            null
          }
        }
      if (resolvers.isEmpty()) return null
      ExtendedResolver(resolvers.toTypedArray()).apply { setTimeout(3) }
    } catch (e: Throwable) {
      Log.w(logTag, "Failed to create direct DNS resolver", e)
      null
    }
  }

  private suspend fun rawQuery(network: android.net.Network?, wireQuery: ByteArray): ByteArray =
    suspendCancellableCoroutine { cont ->
      val signal = CancellationSignal()
      cont.invokeOnCancellation { signal.cancel() }

      dns.rawQuery(
        network,
        wireQuery,
        DnsResolver.FLAG_EMPTY,
        dnsExecutor,
        signal,
        object : DnsResolver.Callback<ByteArray> {
          override fun onAnswer(answer: ByteArray, rcode: Int) {
            cont.resume(answer)
          }

          override fun onError(error: DnsResolver.DnsException) {
            cont.resumeWithException(error)
          }
        },
      )
    }

  private fun txtValue(records: List<TXTRecord>, key: String): String? {
    val prefix = "$key="
    for (r in records) {
      val strings: List<String> =
        try {
          r.strings.mapNotNull { it as? String }
        } catch (_: Throwable) {
          emptyList()
        }
      for (s in strings) {
        val trimmed = decodeDnsTxtString(s).trim()
        if (trimmed.startsWith(prefix)) {
          return trimmed.removePrefix(prefix).trim().ifEmpty { null }
        }
      }
    }
    return null
  }

  private fun txtIntValue(records: List<TXTRecord>, key: String): Int? {
    return txtValue(records, key)?.toIntOrNull()
  }

  private fun txtBoolValue(records: List<TXTRecord>, key: String): Boolean {
    val raw = txtValue(records, key)?.trim()?.lowercase() ?: return false
    return raw == "1" || raw == "true" || raw == "yes"
  }

  private fun decodeDnsTxtString(raw: String): String {
    val bytes = raw.toByteArray(Charsets.ISO_8859_1)
    val decoder =
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Throwable) {
      raw
    }
  }

  private suspend fun resolveHostUnicast(hostname: String): String? {
    val a =
      records(lookupUnicastMessage(hostname, Type.A), Section.ANSWER)
        .mapNotNull { it as? ARecord }
        .mapNotNull { it.address?.hostAddress }
    val aaaa =
      records(lookupUnicastMessage(hostname, Type.AAAA), Section.ANSWER)
        .mapNotNull { it as? AAAARecord }
        .mapNotNull { it.address?.hostAddress }

    return a.firstOrNull() ?: aaaa.firstOrNull()
  }

  companion object {
    private const val BASE_RETRY_DELAY_MS = 5000L
    private const val MAX_RETRY_DELAY_MS = 60000L
    private const val CIRCUIT_BREAKER_THRESHOLD = 5

    private class DnsFailureTracker {
      @Volatile var consecutiveFailures: Int = 0

      fun recordFailure() {
        consecutiveFailures++
      }

      fun recordSuccess() {
        consecutiveFailures = 0
      }

      fun getBackoffDelayMs(): Long {
        if (consecutiveFailures == 0) return BASE_RETRY_DELAY_MS
        if (consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD) return MAX_RETRY_DELAY_MS
        val backoffMultiplier = 1 shl (consecutiveFailures - 1)
        return min(BASE_RETRY_DELAY_MS * backoffMultiplier, MAX_RETRY_DELAY_MS)
      }
    }
  }
}
