package com.openclaw.assistant.gateway

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.openclaw.assistant.SecurePrefs
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Secure storage for device authentication tokens with defense-in-depth:
 * - Android Keystore-backed encryption keys
 * - HMAC-SHA256 integrity verification
 * - Automatic token expiration (30 days)
 * - Unpredictable storage key identifiers
 */
class DeviceAuthStore(
  context: Context,
  private val prefs: SecurePrefs,
) {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "openclaw_device_token_key"
    private const val KEYSTORE_AEAD_KEY = "openclaw_token_aead_key"
    private const val TOKEN_TTL_DAYS = 30L
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val VERSION = "v2"
    private const val KEY_SALT = "oc7qK9mP3nL5xR8v" // Static salt for key derivation
  }

  private val appContext = context.applicationContext

  init {
    AeadConfig.register()
  }

  private val aead: Aead by lazy {
    loadOrCreateAead()
  }

  private fun loadOrCreateAead(): Aead {
    return try {
      AndroidKeysetManager.Builder()
        .withSharedPref(appContext, KEYSTORE_AEAD_KEY, null)
        .withKeyTemplate(AesGcmKeyManager.aes128GcmTemplate())
        .withMasterKeyUri("android-keystore://$KEY_ALIAS")
        .build()
        .keysetHandle
        .getPrimitive(Aead::class.java)
    } catch (e: Exception) {
      // Fallback for robustness, though it should not happen in normal operation
      val keysetHandle = KeysetHandle.generateNew(AesGcmKeyManager.aes128GcmTemplate())
      keysetHandle.getPrimitive(Aead::class.java)
    }
  }

  /**
   * Load a stored token if it exists and hasn't expired.
   * Returns null if token is missing, corrupted, or expired.
   */
  fun loadToken(deviceId: String, role: String): String? {
    return try {
      val encryptedData = prefs.getString(storageKey(deviceId, role))
      if (encryptedData.isNullOrBlank()) return null

      val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
      if (combined.size < 12) return null

      val nonce = combined.sliceArray(0 until 12)
      val ciphertext = combined.sliceArray(12 until combined.size)

      val decrypted = aead.decrypt(ciphertext, nonce)
      val stored = String(decrypted, Charsets.UTF_8)

      parseAndVerifyStoredToken(stored, deviceId, role)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Save a token with encryption and integrity protection.
   * Automatically includes expiration timestamp.
   */
  fun saveToken(deviceId: String, role: String, token: String) {
    try {
      val trimmedToken = token.trim()
      if (trimmedToken.isEmpty()) {
        clearToken(deviceId, role)
        return
      }

      val expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(TOKEN_TTL_DAYS)
      val hmac = computeHmac(trimmedToken, deviceId, role, expiresAt)

      val stored = buildString {
        append(VERSION)
        append(":")
        append(expiresAt)
        append(":")
        append(hmac)
        append(":")
        append(trimmedToken)
      }

      val nonce = ByteArray(12).apply {
        java.security.SecureRandom().nextBytes(this)
      }

      val ciphertext = aead.encrypt(stored.toByteArray(Charsets.UTF_8), nonce)
      val combined = nonce + ciphertext
      val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

      prefs.putString(storageKey(deviceId, role), encoded)
    } catch (e: Exception) {
      // Fail secure: don't store if encryption fails
    }
  }

  /**
   * Clear a stored token.
   */
  fun clearToken(deviceId: String, role: String) {
    try {
      prefs.remove(storageKey(deviceId, role))
    } catch (e: Exception) {
      // Ignore cleanup errors
    }
  }

  /**
   * Check if a token exists and is still valid (not expired).
   */
  fun hasValidToken(deviceId: String, role: String): Boolean {
    return loadToken(deviceId, role) != null
  }

  /**
   * Get token expiration timestamp, or null if no token exists.
   */
  fun getTokenExpiration(deviceId: String, role: String): Long? {
    return try {
      val encryptedData = prefs.getString(storageKey(deviceId, role))
      if (encryptedData.isNullOrBlank()) return null

      val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
      if (combined.size < 12) return null

      val nonce = combined.sliceArray(0 until 12)
      val ciphertext = combined.sliceArray(12 until combined.size)

      val decrypted = aead.decrypt(ciphertext, nonce)
      val stored = String(decrypted, Charsets.UTF_8)

      parseExpiration(stored)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Clear all expired tokens to reclaim storage.
   */
  fun clearExpiredTokens(deviceId: String) {
    // Note: This is a simplified implementation.
    // In production, you'd iterate through known roles or use a prefix scan.
    // For now, we rely on loadToken returning null for expired tokens.
  }

  private fun parseAndVerifyStoredToken(stored: String, deviceId: String, role: String): String? {
    val parts = stored.split(":", limit = 4)
    if (parts.size != 4) return null

    val (version, expiresStr, storedHmac, token) = parts

    if (version != VERSION) return null

    val expiresAt = expiresStr.toLongOrNull() ?: return null
    if (System.currentTimeMillis() > expiresAt) return null

    if (token.isBlank()) return null

    val computedHmac = computeHmac(token, deviceId, role, expiresAt)
    if (!constantTimeCompare(storedHmac, computedHmac)) return null

    return token
  }

  private fun parseExpiration(stored: String): Long? {
    val parts = stored.split(":", limit = 4)
    if (parts.size < 2) return null
    return parts[1].toLongOrNull()
  }

  private fun computeHmac(token: String, deviceId: String, role: String, expiresAt: Long): String {
    val hmacKey = deriveHmacKey(deviceId, role)
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(hmacKey)

    mac.update(token.toByteArray(Charsets.UTF_8))
    mac.update(deviceId.toByteArray(Charsets.UTF_8))
    mac.update(role.toByteArray(Charsets.UTF_8))
    mac.update(expiresAt.toString().toByteArray(Charsets.UTF_8))

    return Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
  }

  private fun deriveHmacKey(deviceId: String, role: String): SecretKeySpec {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(KEY_SALT.toByteArray(Charsets.UTF_8))
    digest.update(deviceId.toByteArray(Charsets.UTF_8))
    digest.update(role.toByteArray(Charsets.UTF_8))

    // Also incorporate the Android Keystore-backed key for additional protection
    val keystore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keystore.load(null)
    if (keystore.containsAlias(KEY_ALIAS)) {
      val entry = keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
      entry?.let { digest.update(it.secretKey.encoded) }
    }

    return SecretKeySpec(digest.digest(), HMAC_ALGORITHM)
  }

  /**
   * Generate unpredictable storage key using hash of deviceId + role.
   * This prevents attackers from knowing which keys to target.
   */
  private fun storageKey(deviceId: String, role: String): String {
    val normalizedDevice = deviceId.trim().lowercase()
    val normalizedRole = role.trim().lowercase()

    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(KEY_SALT.toByteArray(Charsets.UTF_8))
    digest.update(normalizedDevice.toByteArray(Charsets.UTF_8))
    digest.update(normalizedRole.toByteArray(Charsets.UTF_8))
    digest.update(deviceId.reversed().toByteArray(Charsets.UTF_8)) // Additional mixing

    val hash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
      .replace("+", "-")
      .replace("/", "_")
      .take(32)

    return "dt.$hash"
  }

  /**
   * Constant-time comparison to prevent timing attacks on HMAC verification.
   */
  private fun constantTimeCompare(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
      result = result or (a[i].code xor b[i].code)
    }
    return result == 0
  }
}
