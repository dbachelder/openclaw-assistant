package com.openclaw.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.api.OpenClawClient

import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.NodeForegroundService
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.components.CollapsibleSection
import com.openclaw.assistant.ui.components.ConnectionState
import com.openclaw.assistant.ui.components.PairingRequiredCard
import com.openclaw.assistant.ui.components.StatusIndicator
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openclaw.assistant.utils.SystemInfoProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            OpenClawAssistantTheme {
                SettingsScreen(
                    settings = settings,
                    onSave = { 
                        Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var httpUrl by remember { mutableStateOf(settings.httpUrl) }
    var authToken by remember { mutableStateOf(settings.authToken) }
    var defaultAgentId by remember { mutableStateOf(settings.defaultAgentId) }
    var ttsEnabled by remember { mutableStateOf(settings.ttsEnabled) }
    var ttsSpeed by remember { mutableStateOf(settings.ttsSpeed) }
    var continuousMode by remember { mutableStateOf(settings.continuousMode) }
    var resumeLatestSession by remember { mutableStateOf(settings.resumeLatestSession) }
    var wakeWordPreset by remember { mutableStateOf(settings.wakeWordPreset) }
    var customWakeWord by remember { mutableStateOf(settings.customWakeWord) }
    var speechSilenceTimeout by remember { mutableStateOf(settings.speechSilenceTimeout.toFloat().coerceIn(5000f, 30000f)) }
    var speechLanguage by remember { mutableStateOf(settings.speechLanguage) }
    var thinkingSoundEnabled by remember { mutableStateOf(settings.thinkingSoundEnabled) }


    var showWakeWordMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }
    val apiClient = remember { OpenClawClient() }
    
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    // Agent list from NodeRuntime
    val isPairingRequired by runtime.isPairingRequired.collectAsState()
    val deviceId = runtime.deviceId

    val agentListState by runtime.agentList.collectAsState()
    val availableAgents = remember(agentListState) {
        agentListState?.agents?.distinctBy { it.id } ?: emptyList()
    }
    var isFetchingAgents by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }

    // TTS Engines
    var ttsEngine by remember { mutableStateOf(settings.ttsEngine) }
    var availableEngines by remember { mutableStateOf<List<com.openclaw.assistant.speech.TTSEngineUtils.EngineInfo>>(emptyList()) }
    var showEngineMenu by remember { mutableStateOf(false) }
    var showNodeToken by remember { mutableStateOf(false) }

    val nodeConnected by runtime.isConnected.collectAsState()
    val nodeStatus by runtime.statusText.collectAsState()
    val nodeForeground by runtime.isForeground.collectAsState()

    val manualEnabledState by runtime.manualEnabled.collectAsState()
    val manualHostState by runtime.manualHost.collectAsState()
    val manualPortState by runtime.manualPort.collectAsState()
    val manualTlsState by runtime.manualTls.collectAsState()
    val gatewayTokenState by runtime.gatewayToken.collectAsState()
    
    // Connection Type
    var connectionType by remember { mutableStateOf(settings.connectionType) }
    
    // Gateway inputs
    var gatewayHost by remember { mutableStateOf(manualHostState) }
    var gatewayPort by remember { mutableStateOf(manualPortState.toString()) }
    var gatewayTls by remember { mutableStateOf(manualTlsState) }
    var gatewayToken by remember { mutableStateOf(gatewayTokenState) }
    
    // HTTP inputs
    var httpInputUrl by remember { mutableStateOf(httpUrl) }
    var httpToken by remember { mutableStateOf(authToken) }
    
    // Update local state if runtime state changes behind the scenes
    LaunchedEffect(manualHostState, manualPortState, manualTlsState, gatewayTokenState) {
        gatewayHost = manualHostState
        gatewayPort = manualPortState.toString()
        gatewayTls = manualTlsState
        gatewayToken = gatewayTokenState
    }
    LaunchedEffect(httpUrl, authToken) {
        httpInputUrl = httpUrl
        httpToken = authToken
    }

    LaunchedEffect(Unit) {
        availableEngines = com.openclaw.assistant.speech.TTSEngineUtils.getAvailableEngines(context)
    }

    // Fetch agent list on screen open if already connected
    LaunchedEffect(Unit) {
        if (runtime.isConnected.value) {
            runtime.refreshAgentList()
        }
    }

    // Speech recognition language options - loaded dynamically from device
    var speechLanguageOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingLanguages by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoadingLanguages = true
        val deviceLanguages = com.openclaw.assistant.speech.SpeechLanguageUtils
            .getAvailableLanguages(context)

        speechLanguageOptions = buildList {
            add("" to context.getString(R.string.speech_language_system_default))
            if (deviceLanguages != null) {
                addAll(deviceLanguages.map { it.tag to it.displayName })
            } else {
                addAll(FALLBACK_SPEECH_LANGUAGES)
            }
        }
        isLoadingLanguages = false
    }

    // Wake word options
    val wakeWordOptions = listOf(
        SettingsRepository.WAKE_WORD_OPEN_CLAW to stringResource(R.string.wake_word_openclaw),
        SettingsRepository.WAKE_WORD_HEY_ASSISTANT to stringResource(R.string.wake_word_hey_assistant),
        SettingsRepository.WAKE_WORD_JARVIS to stringResource(R.string.wake_word_jarvis),
        SettingsRepository.WAKE_WORD_COMPUTER to stringResource(R.string.wake_word_computer),
        SettingsRepository.WAKE_WORD_CUSTOM to stringResource(R.string.wake_word_custom)
    )

    var selectedTabIndex by remember {
        mutableStateOf(if (connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) 0 else 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            settings.connectionType = if (selectedTabIndex == 0) {
                                SettingsRepository.CONNECTION_TYPE_GATEWAY
                            } else {
                                SettingsRepository.CONNECTION_TYPE_HTTP
                            }

                            // Save Gateway Settings
                            runtime.setManualEnabled(true)
                            runtime.setManualHost(gatewayHost.trim())
                            runtime.setManualPort(gatewayPort.toIntOrNull() ?: 18789)
                            runtime.setManualTls(gatewayTls)
                            runtime.setGatewayToken(gatewayToken.trim())

                            // Save HTTP Settings
                            settings.httpUrl = httpInputUrl.trim()
                            settings.authToken = httpToken.trim()

                            settings.defaultAgentId = defaultAgentId
                            settings.ttsEnabled = ttsEnabled
                            settings.ttsSpeed = ttsSpeed
                            settings.ttsEngine = ttsEngine
                            settings.continuousMode = continuousMode
                            settings.resumeLatestSession = resumeLatestSession
                            settings.wakeWordPreset = wakeWordPreset
                            settings.customWakeWord = customWakeWord
                            settings.speechSilenceTimeout = speechSilenceTimeout.toLong()
                            settings.speechLanguage = speechLanguage
                            settings.thinkingSoundEnabled = thinkingSoundEnabled

                            // Stop/Restart services
                            HotwordService.stop(context)

                            if (settings.connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                                runtime.connectManual()
                            }

                            if (settings.hotwordEnabled) {
                                HotwordService.start(context)
                            }
                            onSave()
                        },
                        enabled = gatewayHost.isNotBlank() || httpInputUrl.isNotBlank()
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
                // Show pairing required banner if needed
                if (isPairingRequired && deviceId != null) {
                    PairingRequiredCard(deviceId = deviceId)
                    Spacer(modifier = Modifier.height(24.dp))
                }

            // === UNIFIED CONNECTION SECTION ===
            CollapsibleSection(
                title = stringResource(R.string.connection),
                subtitle = if (connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY && nodeConnected) nodeStatus.ifBlank { stringResource(R.string.connected) } else "",
                initiallyExpanded = true
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // Connection Configuration Tabs
                        Text("Connection Settings", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))

                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = Color.Transparent,
                        ) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text("Gateway") }
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = { Text("HTTP") }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (selectedTabIndex == 0) {
                            Text("Gateway Configuration", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = gatewayHost,
                                    onValueChange = { gatewayHost = it; testResult = null },
                                    label = { Text("Host") },
                                    modifier = Modifier.weight(2f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                )
                                OutlinedTextField(
                                    value = gatewayPort,
                                    onValueChange = { gatewayPort = it.filter { char -> char.isDigit() }; testResult = null },
                                    label = { Text("Port") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = gatewayToken,
                                onValueChange = { gatewayToken = it; testResult = null },
                                label = { Text("Gateway Token") },
                                trailingIcon = {
                                    IconButton(onClick = { showNodeToken = !showNodeToken }) {
                                        Icon(
                                            if (showNodeToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (showNodeToken) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gateway_use_tls), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.gateway_use_tls_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = gatewayTls,
                                    onCheckedChange = { gatewayTls = it; testResult = null }
                                )
                            }
                        } else if (selectedTabIndex == 1) {
                            Text("HTTP API Configuration", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = httpInputUrl,
                                onValueChange = { httpInputUrl = it; testResult = null },
                                label = { Text(stringResource(R.string.webhook_url_label)) },
                                placeholder = { Text(stringResource(R.string.webhook_url_hint)) },
                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = httpToken,
                                onValueChange = { httpToken = it.trim(); testResult = null },
                                label = { Text(stringResource(R.string.auth_token_label)) },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = { showNodeToken = !showNodeToken }) {
                                        Icon(
                                            if (showNodeToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (showNodeToken) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        
                        // Gateway Specific Settings
                        if (selectedTabIndex == 0) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Foreground Service Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gateway_foreground_service), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.gateway_foreground_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = nodeForeground,
                                    onCheckedChange = { enabled ->
                                        runtime.setForeground(enabled)
                                        if (enabled) NodeForegroundService.start(context) else NodeForegroundService.stop(context)
                                    }
                                )
                            }
                        }

                        // HTTP Specific Settings
                        if (selectedTabIndex == 1) {
                            Text(
                                text = stringResource(R.string.settings_legacy_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp, top = 12.dp)
                            )
                            
                            // Default Agent
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (availableAgents.isNotEmpty()) {
                            // Dropdown when agents are loaded
                            ExposedDropdownMenuBox(
                                expanded = showAgentMenu,
                                onExpandedChange = { showAgentMenu = it }
                            ) {
                                val agentLabel = availableAgents.find { it.id == defaultAgentId }?.name ?: defaultAgentId
                                OutlinedTextField(
                                    value = agentLabel,
                                    onValueChange = { defaultAgentId = it },
                                    label = { Text(stringResource(R.string.default_agent_label)) },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isFetchingAgents) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                IconButton(onClick = {
                                                    scope.launch {
                                                        isFetchingAgents = true
                                                        if (runtime.isConnected.value) {
                                                            runtime.refreshAgentList()
                                                        } else {
                                                            Toast.makeText(context, context.getString(R.string.gateway_not_connected), Toast.LENGTH_SHORT).show()
                                                        }
                                                        isFetchingAgents = false
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Agents")
                                                }
                                            }
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showAgentMenu)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = showAgentMenu,
                                    onDismissRequest = { showAgentMenu = false }
                                ) {
                                    availableAgents.forEach { agent ->
                                        DropdownMenuItem(
                                            text = { Text(agent.name) },
                                            onClick = {
                                                defaultAgentId = agent.id
                                                showAgentMenu = false
                                            },
                                            leadingIcon = {
                                                if (defaultAgentId == agent.id) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            // Text field fallback before connection or if no agents found
                            OutlinedTextField(
                                value = defaultAgentId,
                                onValueChange = { defaultAgentId = it },
                                label = { Text(stringResource(R.string.default_agent_label)) },
                                placeholder = { Text(stringResource(R.string.default_agent_hint)) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                trailingIcon = {
                                    if (isFetchingAgents) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        IconButton(onClick = {
                                            scope.launch {
                                                isFetchingAgents = true
                                                if (runtime.isConnected.value) {
                                                    runtime.refreshAgentList()
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.gateway_not_connected), Toast.LENGTH_SHORT).show()
                                                }
                                                isFetchingAgents = false
                                            }
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Agents")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Test Connection Button
                            Button(
                                onClick = {
                                    if (httpUrl.isBlank()) return@Button
                                    scope.launch {
                                        try {
                                            isTesting = true
                                            testResult = null
                                            val testUrl = httpInputUrl.trimEnd('/').let { url ->
                                                if (url.contains("/v1/")) url else "$url/v1/chat/completions"
                                            }
                                            val result = apiClient.testConnection(testUrl, httpToken.trim())
                                            result.fold(
                                                onSuccess = {
                                                    testResult = TestResult(success = true, message = context.getString(R.string.connected))
                                                    settings.httpUrl = httpInputUrl.trim()
                                                    settings.authToken = httpToken.trim()
                                                    settings.isVerified = true
                                                },
                                                onFailure = {
                                                    testResult = TestResult(success = false, message = context.getString(R.string.failed, it.message ?: ""))
                                                }
                                            )
                                        } catch (e: Exception) {
                                            testResult = TestResult(success = false, message = context.getString(R.string.error, e.message ?: ""))
                                        } finally {
                                            isTesting = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        testResult?.success == true -> Color(0xFF4CAF50)
                                        testResult?.success == false -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                ),
                                enabled = httpInputUrl.isNotBlank() && !isTesting
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.testing))
                                } else {
                                    Icon(
                                        when {
                                            testResult?.success == true -> Icons.Default.Check
                                            testResult?.success == false -> Icons.Default.Error
                                            else -> Icons.Default.NetworkCheck
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(testResult?.message ?: stringResource(R.string.test_connection_button))
                                }
                            }
                }
                        Spacer(modifier = Modifier.height(24.dp))
                    } // end Column
                } // end Card
            } // end CollapsibleSection for Unified Connection


            Spacer(modifier = Modifier.height(24.dp))

            // === VOICE SECTION ===
            CollapsibleSection(title = stringResource(R.string.voice)) {

            // --- Speech Language card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.speech_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingLanguages) {
                        OutlinedTextField(
                            value = stringResource(R.string.speech_language_loading),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.speech_language_label)) },
                            trailingIcon = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = showLanguageMenu,
                            onExpandedChange = { showLanguageMenu = it }
                        ) {
                            val currentLabel = if (speechLanguage.isEmpty()) {
                                stringResource(R.string.speech_language_system_default)
                            } else {
                                speechLanguageOptions.find { it.first == speechLanguage }?.second
                                    ?: speechLanguage
                            }

                            OutlinedTextField(
                                value = currentLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.speech_language_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLanguageMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showLanguageMenu,
                                onDismissRequest = { showLanguageMenu = false }
                            ) {
                                speechLanguageOptions.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            speechLanguage = tag
                                            showLanguageMenu = false
                                        },
                                        leadingIcon = {
                                            if (speechLanguage == tag) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Voice Output card ---
            Text(
                text = stringResource(R.string.voice_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.read_ai_responses), style = MaterialTheme.typography.bodyLarge)
                        }
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }

                    if (ttsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // TTS Engine Selection
                        ExposedDropdownMenuBox(
                            expanded = showEngineMenu,
                            onExpandedChange = { showEngineMenu = it }
                        ) {
                            val currentLabel = if (ttsEngine.isEmpty()) {
                                stringResource(R.string.tts_engine_auto)
                            } else {
                                availableEngines.find { it.name == ttsEngine }?.label ?: ttsEngine
                            }

                            OutlinedTextField(
                                value = currentLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.tts_engine_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEngineMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showEngineMenu,
                                onDismissRequest = { showEngineMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tts_engine_auto)) },
                                    onClick = {
                                        ttsEngine = ""
                                        showEngineMenu = false
                                    },
                                    leadingIcon = {
                                        if (ttsEngine.isEmpty()) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )

                                availableEngines.forEach { engine ->
                                    DropdownMenuItem(
                                        text = { Text(engine.label) },
                                        onClick = {
                                            ttsEngine = engine.name
                                            showEngineMenu = false
                                        },
                                        leadingIcon = {
                                            if (ttsEngine == engine.name) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Voice Speed (only if Google TTS)
                        val effectiveEngine = if (ttsEngine.isEmpty()) {
                            com.openclaw.assistant.speech.TTSEngineUtils.getDefaultEngine(context)
                        } else {
                            ttsEngine
                        }
                        val isGoogleTTS = effectiveEngine == SettingsRepository.GOOGLE_TTS_PACKAGE

                        if (isGoogleTTS) {
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "%.1fx".format(ttsSpeed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Slider(
                                value = ttsSpeed,
                                onValueChange = { ttsSpeed = it },
                                valueRange = 0.5f..3.0f,
                                steps = 24,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Thinking sound
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.thinking_sound), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.thinking_sound_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = thinkingSoundEnabled, onCheckedChange = { thinkingSoundEnabled = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Conversation card ---
            Text(
                text = stringResource(R.string.conversation_section),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.continuous_conversation), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_start_mic), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = continuousMode, onCheckedChange = { continuousMode = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Speech silence timeout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.speech_silence_timeout), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.speech_silence_timeout_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text(
                            text = "%.1fs".format(speechSilenceTimeout / 1000f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = speechSilenceTimeout,
                        onValueChange = { speechSilenceTimeout = it },
                        valueRange = 5000f..30000f,
                        steps = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            } // end CollapsibleSection for Voice

            Spacer(modifier = Modifier.height(24.dp))

            // === WAKE WORD SECTION ===
            CollapsibleSection(title = stringResource(R.string.wake_word)) {



                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.wake_word_classic_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(
                                expanded = showWakeWordMenu,
                                onExpandedChange = { showWakeWordMenu = it }
                            ) {
                                OutlinedTextField(
                                    value = wakeWordOptions.find { it.first == wakeWordPreset }?.second ?: stringResource(R.string.wake_word_openclaw),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.activation_phrase)) },
                                    leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWakeWordMenu) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                
                                ExposedDropdownMenu(
                                    expanded = showWakeWordMenu,
                                    onDismissRequest = { showWakeWordMenu = false }
                                ) {
                                    wakeWordOptions.forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                wakeWordPreset = value
                                                showWakeWordMenu = false
                                            },
                                            leadingIcon = {
                                                if (wakeWordPreset == value) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            
                            if (wakeWordPreset == SettingsRepository.WAKE_WORD_CUSTOM) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = customWakeWord,
                                    onValueChange = { customWakeWord = it.lowercase() },
                                    label = { Text(stringResource(R.string.custom_wake_word)) },
                                    placeholder = { Text(stringResource(R.string.custom_wake_word_hint)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    supportingText = {
                                        Text(stringResource(R.string.custom_wake_word_help), color = Color.Gray, fontSize = 12.sp)
                                    }
                                )
                            }



                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.resume_latest_session), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.resume_latest_session_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Switch(checked = resumeLatestSession, onCheckedChange = { resumeLatestSession = it })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === SUPPORT SECTION ===
            CollapsibleSection(title = stringResource(R.string.support_section), collapsible = false) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.report_issue),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.report_issue_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val systemInfo = SystemInfoProvider.getSystemInfoReport(context, settings)
                            val body = "\n\n$systemInfo"
                            val uri = Uri.parse("https://github.com/yuga-hashimoto/openclaw-assistant/issues/new")
                                .buildUpon()
                                .appendQueryParameter("body", body)
                                .build()
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.report_issue))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    val versionName = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    Text(
                        text = stringResource(R.string.app_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            } // end CollapsibleSection for Support

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)

// Fallback language list used when device query fails
private val FALLBACK_SPEECH_LANGUAGES = listOf(
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "ja-JP" to "日本語",
    "it-IT" to "Italiano",
    "fr-FR" to "Français",
    "de-DE" to "Deutsch",
    "es-ES" to "Español",
    "pt-BR" to "Português (Brasil)",
    "ko-KR" to "한국어",
    "zh-CN" to "中文 (简体)",
    "zh-TW" to "中文 (繁體)",
    "ar-SA" to "العربية",
    "hi-IN" to "हिन्दी",
    "ru-RU" to "Русский",
    "th-TH" to "ไทย",
    "vi-VN" to "Tiếng Việt"
)
