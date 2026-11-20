package com.eraassistant.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.core.CommandRouter
import com.eraassistant.mobile.contacts.CallConfirmationPolicy
import com.eraassistant.mobile.contacts.PendingCallConfirmation
import com.eraassistant.mobile.device.DeviceController
import com.eraassistant.mobile.device.AndroidCapabilityDiscovery
import com.eraassistant.mobile.history.CommandHistoryStore
import com.eraassistant.mobile.planner.PreviewPlanner
import com.eraassistant.mobile.tasks.TaskExecutionCoordinator
import com.eraassistant.mobile.tasks.TaskExecutionMonitor
import com.eraassistant.mobile.tasks.SharedPreferencesTaskLedgerPersistence
import com.eraassistant.mobile.provider.AIProviderConfiguration
import com.eraassistant.mobile.provider.AIProviderConfigurationStore
import com.eraassistant.mobile.provider.AIProviderConnectionTester
import com.eraassistant.mobile.provider.AIProviderKind
import com.eraassistant.mobile.provider.LocalFirstPlannerRouter
import com.eraassistant.mobile.provider.LocalPlanningCandidate
import com.eraassistant.mobile.provider.PlanningConfidence
import com.eraassistant.mobile.provider.PlannerSource
import com.eraassistant.mobile.provider.RemoteAIPlannerProvider
import com.eraassistant.mobile.provider.PlannerResponseAdapter
import com.eraassistant.mobile.ai.PlannerRequest
import com.eraassistant.mobile.preferences.CustomCommandAlias
import com.eraassistant.mobile.preferences.CustomCommandStore
import com.eraassistant.mobile.preferences.AssistantPreferencesStore
import com.eraassistant.mobile.speech.VoiceOutput
import com.eraassistant.mobile.speech.ForegroundVoiceSession
import com.eraassistant.mobile.screen.UiAgentContextBuilder
import com.eraassistant.mobile.screen.UiTreeJson
import com.eraassistant.mobile.screen.PendingUiActionStore
import com.eraassistant.mobile.screen.UiSnapshotFingerprint
import com.eraassistant.mobile.screen.VisualFrame
import com.eraassistant.mobile.screen.VisualFrameStore
import com.eraassistant.mobile.ui.ERAScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val router = CommandRouter()
    private lateinit var deviceController: DeviceController
    private lateinit var historyStore: CommandHistoryStore
    private lateinit var customCommandStore: CustomCommandStore
    private lateinit var assistantPreferencesStore: AssistantPreferencesStore
    private lateinit var aiProviderConfigurationStore: AIProviderConfigurationStore
    private lateinit var voiceOutput: VoiceOutput
    private lateinit var pendingUiActionStore: PendingUiActionStore
    private lateinit var foregroundVoiceSession: ForegroundVoiceSession
    private var pendingVoiceSessionStart = false
    private enum class MicrophonePermissionAction { VOICE_SESSION, SINGLE_COMMAND, SETUP }
    private var pendingMicrophonePermissionAction: MicrophonePermissionAction? = null
    private var voiceSessionActive by mutableStateOf(false)
    private var sequenceJob: Job? = null
    private var pendingCommandAfterCameraPermission: String? = null

    private var transcript by mutableStateOf("")
    private var status by mutableStateOf("Ready — ERA is waiting for a command.")
    private var history by mutableStateOf(listOf<String>())
    private var aliases by mutableStateOf(listOf<CustomCommandAlias>())
    private var planPreview by mutableStateOf("")
    private var taskMonitorText by mutableStateOf("")
    private var aiProviderKind by mutableStateOf(AIProviderKind.LOCAL_ONLY)
    private var aiProviderModel by mutableStateOf("")
    private var aiProviderEndpoint by mutableStateOf("")
    private var aiProviderKeyDraft by mutableStateOf("")
    private var aiProviderKeyConfigured by mutableStateOf(false)
    private var aiProviderFallbackEnabled by mutableStateOf(false)
    private var aiProviderConnectionStatus by mutableStateOf("Local-only planner is active.")
    private var aiProviderTestRunning by mutableStateOf(false)
    private var speechOutputEnabled by mutableStateOf(true)
    private var speechLanguage by mutableStateOf("bn-IN")
    private var autoObservationEnabled by mutableStateOf(false)
    private var accessibilityConsentAccepted by mutableStateOf(false)
    private var uiActionStatus by mutableStateOf("")
    private var pendingCallConfirmation by mutableStateOf<PendingCallConfirmation?>(null)
    private var uiSnapshot by mutableStateOf("")
    private var uiSnapshotError by mutableStateOf("")
    private var uiDiff by mutableStateOf("")
    private var visualFrame by mutableStateOf<VisualFrame?>(null)
    private var visualCaptureStatus by mutableStateOf("")
    private var microphonePermissionGranted by mutableStateOf(false)
    private var cameraPermissionGranted by mutableStateOf(false)
    private var accessibilityServiceEnabled by mutableStateOf(false)

    private val speechPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphonePermissionGranted = granted ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val requestedAction = pendingMicrophonePermissionAction
        pendingMicrophonePermissionAction = null
        pendingVoiceSessionStart = false
        if (!microphonePermissionGranted) {
            status = "Microphone permission দেওয়া হয়নি। Android Settings থেকে পরে চালু করতে পারবে।"
            return@registerForActivityResult
        }
        when (requestedAction) {
            MicrophonePermissionAction.VOICE_SESSION -> startForegroundVoiceSession()
            MicrophonePermissionAction.SINGLE_COMMAND -> startSpeechRecognition()
            MicrophonePermissionAction.SETUP, null -> status = "Microphone permission granted. ERA আর permission চাইবে না, যতক্ষণ Android grant বহাল রাখে।"
        }
    }

    private val torchPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val command = pendingCommandAfterCameraPermission
        pendingCommandAfterCameraPermission = null
        if (cameraPermissionGranted && !command.isNullOrBlank()) handleCommand(command)
        else if (cameraPermissionGranted) status = "Camera permission granted. Flashlight control is ready."
        else status = "Camera permission দেওয়া হয়নি। Flashlight control-এর জন্য এটি প্রয়োজন।"
    }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val contactUri = result.data?.data
        if (result.resultCode != RESULT_OK || contactUri == null) {
            pendingCallConfirmation = null
            status = "Contact নির্বাচন করা হয়নি; কোনো কল করা হয়নি।"
        } else {
            val selected = runCatching {
                contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                }
            }.getOrNull()
            pendingCallConfirmation = CallConfirmationPolicy.create(
                name = selected?.first,
                number = selected?.second,
                nowEpochMs = System.currentTimeMillis()
            )
            status = if (pendingCallConfirmation == null) {
                "নির্বাচিত contact-এর valid phone number পাওয়া যায়নি। কোনো কল করা হয়নি।"
            } else {
                "নির্বাচিত contact-এর নাম ও নম্বর review করো; Confirm করলে শুধু dialer খুলবে।"
            }
            recordCommand("Contact selected", status)
        }
    }

    private val speechResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        if (!spoken.isNullOrBlank()) {
            transcript = spoken
            handleCommand(spoken)
        } else {
            status = "কোনো voice command পাওয়া যায়নি। আবার চেষ্টা করো।"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceController = DeviceController(this)
        historyStore = CommandHistoryStore(this)
        customCommandStore = CustomCommandStore(this)
        assistantPreferencesStore = AssistantPreferencesStore(this)
        aiProviderConfigurationStore = AIProviderConfigurationStore(this)
        val providerConfig = aiProviderConfigurationStore.load()
        aiProviderKind = providerConfig.provider
        aiProviderModel = providerConfig.modelId
        aiProviderEndpoint = providerConfig.endpoint
        aiProviderFallbackEnabled = providerConfig.remoteFallbackEnabled
        aiProviderKeyConfigured = aiProviderConfigurationStore.hasApiKey()
        voiceOutput = VoiceOutput(this)
        pendingUiActionStore = PendingUiActionStore(this)
        foregroundVoiceSession = ForegroundVoiceSession(
            context = this,
            onCommand = { spoken ->
                transcript = spoken
                handleCommand(spoken)
            },
            onStatus = { message -> status = message }
        )
        history = historyStore.load()
        aliases = customCommandStore.load()
        speechOutputEnabled = assistantPreferencesStore.speechOutputEnabled()
        speechLanguage = assistantPreferencesStore.speechLanguage()
        autoObservationEnabled = assistantPreferencesStore.autoObservationEnabled()
        accessibilityConsentAccepted = assistantPreferencesStore.accessibilityConsentAccepted()
        reloadUiSnapshot()
        voiceOutput.setLanguage(speechLanguage)
        refreshPermissionStatuses()
        setContent {
            ERAScreen(
                transcript = transcript,
                status = status,
                history = history,
                aliases = aliases,
                planPreview = planPreview,
                taskMonitorText = taskMonitorText,
                aiProviderKind = aiProviderKind,
                aiProviderModel = aiProviderModel,
                aiProviderEndpoint = aiProviderEndpoint,
                aiProviderKeyDraft = aiProviderKeyDraft,
                aiProviderKeyConfigured = aiProviderKeyConfigured,
                aiProviderFallbackEnabled = aiProviderFallbackEnabled,
                aiProviderConnectionStatus = aiProviderConnectionStatus,
                aiProviderTestRunning = aiProviderTestRunning,
                onAIProviderKindChange = ::selectAIProvider,
                onAIProviderModelChange = { aiProviderModel = it },
                onAIProviderEndpointChange = { aiProviderEndpoint = it },
                onAIProviderKeyChange = { aiProviderKeyDraft = it },
                onAIProviderFallbackChange = { aiProviderFallbackEnabled = it },
                onSaveAIProviderSettings = ::saveAIProviderSettings,
                onTestAIProviderConnection = ::testAIProviderConnection,
                speechOutputEnabled = speechOutputEnabled,
                speechLanguage = speechLanguage,
                autoObservationEnabled = autoObservationEnabled,
                accessibilityConsentAccepted = accessibilityConsentAccepted,
                microphonePermissionGranted = microphonePermissionGranted,
                cameraPermissionGranted = cameraPermissionGranted,
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                voiceSessionActive = voiceSessionActive,
                uiActionStatus = uiActionStatus,
                pendingCallConfirmation = pendingCallConfirmation,
                onConfirmCall = ::confirmPendingCall,
                onCancelCall = {
                    pendingCallConfirmation = null
                    status = "Call request cancelled. No call was placed."
                },
                visualFrame = visualFrame,
                visualCaptureStatus = visualCaptureStatus,
                onClearVisualFrame = ::clearVisualFrame,
                onVoiceSessionChange = { enabled ->
                    if (enabled) requestVoiceSessionStart() else stopVoiceSession()
                },
                onRequestMicrophonePermission = ::requestMicrophonePermissionSetup,
                onRequestCameraPermission = ::requestCameraPermissionSetup,
                onRefreshPermissionStatuses = ::refreshPermissionStatuses,
                onAutoObservationChange = {
                    if (!accessibilityConsentAccepted && it) {
                        status = "Screen inspection consent is required first."
                    } else {
                        autoObservationEnabled = it
                        assistantPreferencesStore.setAutoObservationEnabled(it)
                        status = if (it) "Auto-observation enabled: local structured snapshots update on screen events; no actions run automatically."
                        else "Auto-observation disabled."
                    }
                },
                onAccessibilityConsentChange = { accepted ->
                    accessibilityConsentAccepted = accepted
                    assistantPreferencesStore.setAccessibilityConsentAccepted(accepted)
                    if (!accepted) {
                        autoObservationEnabled = false
                        assistantPreferencesStore.setAutoObservationEnabled(false)
                        pendingUiActionStore.clearPending("Screen-inspection consent revoked; pending UI action cancelled.")
                        VisualFrameStore.clear()
                        visualFrame = null
                        visualCaptureStatus = "Visual frame cleared because screen-inspection consent was revoked."
                        getSharedPreferences("era_ui_snapshot", MODE_PRIVATE).edit().clear().apply()
                        uiSnapshot = ""
                        uiDiff = ""
                        uiSnapshotError = "Screen-inspection consent is not enabled."
                        uiActionStatus = pendingUiActionStore.result()
                        status = "Screen-inspection consent revoked. Auto-observation and pending UI actions are disabled."
                    } else {
                        status = "Screen-inspection consent saved. You can now enable the Accessibility service."
                    }
                },
                onApproveUiClick = { selector, resourceId -> approveUiAction(selector, PendingUiActionStore.ACTION_CLICK, "", resourceId) },
                onApproveUiType = { selector, value, resourceId -> approveUiAction(selector, PendingUiActionStore.ACTION_TYPE_TEXT, value, resourceId) },
                onApproveUiScroll = { selector, resourceId, forward ->
                    approveUiAction(selector, if (forward) PendingUiActionStore.ACTION_SCROLL_FORWARD else PendingUiActionStore.ACTION_SCROLL_BACKWARD, "", resourceId)
                },
                onCancelUiAction = {
                    pendingUiActionStore.clearPending("Pending UI action cancelled by user.")
                    uiActionStatus = pendingUiActionStore.result()
                    status = uiActionStatus
                },
                onTranscriptChange = { transcript = it },
                onVoiceClick = ::requestVoiceInput,
                onRunTextCommand = { handleCommand(transcript) },
                onPreviewPlan = { previewComplexTask() },
                onQuickCommand = { command ->
                    transcript = command
                    handleCommand(command)
                },
                onSpeechOutputChange = {
                    speechOutputEnabled = it
                    assistantPreferencesStore.setSpeechOutputEnabled(it)
                },
                onSpeechLanguageChange = {
                    speechLanguage = it
                    assistantPreferencesStore.setSpeechLanguage(it)
                    voiceOutput.setLanguage(it)
                },
                onAddAlias = ::addAlias,
                onDeleteAlias = ::deleteAlias,
                onClearHistory = {
                    historyStore.clear()
                    history = emptyList()
                    status = "Command history মুছে ফেলা হয়েছে।"
                },
                uiSnapshot = uiSnapshot,
                uiSnapshotError = uiSnapshotError,
                uiDiff = uiDiff,
                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                onReloadUiSnapshot = ::reloadUiSnapshot,
                onCopyUiSnapshot = ::copyUiSnapshot,
                onCopyAiContext = ::copyAiContext,
                onClearUiSnapshot = ::clearUiSnapshot
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::historyStore.isInitialized) {
            refreshPermissionStatuses()
            reloadUiSnapshot()
        }
    }

    /** Android itself is the source of truth; granted permissions are never cached as permanent. */
    private fun refreshPermissionStatuses() {
        microphonePermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        accessibilityServiceEnabled = AndroidCapabilityDiscovery(this).isEraAccessibilityServiceEnabled()
    }

    private fun requestMicrophonePermissionSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            microphonePermissionGranted = true
            status = "Microphone permission আগেই দেওয়া আছে। আবার permission চাওয়া হবে না।"
            return
        }
        pendingMicrophonePermissionAction = MicrophonePermissionAction.SETUP
        speechPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestCameraPermissionSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraPermissionGranted = true
            status = "Camera permission আগেই দেওয়া আছে। Flashlight control ready."
            return
        }
        pendingCommandAfterCameraPermission = null
        torchPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        // Microphone capture is never continued in the background by this session.
        if (::foregroundVoiceSession.isInitialized && foregroundVoiceSession.isActive()) {
            stopVoiceSession()
        }
        super.onPause()
    }

    private fun selectAIProvider(provider: AIProviderKind) {
        if (provider != aiProviderKind) {
            // Do not silently reuse another provider's endpoint, model or credential.
            aiProviderConfigurationStore.clearApiKey()
            aiProviderKeyConfigured = false
            aiProviderKeyDraft = ""
            aiProviderModel = ""
            aiProviderEndpoint = ""
            aiProviderFallbackEnabled = false
        }
        aiProviderKind = provider
        when (provider) {
            AIProviderKind.LOCAL_ONLY -> {
                aiProviderModel = ""
                aiProviderEndpoint = ""
                aiProviderFallbackEnabled = false
                aiProviderConnectionStatus = "Local-only planner selected. No API key or network call is used."
            }
            AIProviderKind.GEMINI -> {
                if (aiProviderModel.isBlank()) aiProviderModel = "gemini-2.0-flash"
                if (aiProviderEndpoint.isBlank()) aiProviderEndpoint = "https://generativelanguage.googleapis.com/v1beta"
                aiProviderConnectionStatus = "Gemini selected. Save an API key before testing."
            }
            AIProviderKind.OPENAI_COMPATIBLE -> {
                if (aiProviderModel.isBlank()) aiProviderModel = "gpt-4o-mini"
                if (aiProviderEndpoint.isBlank()) aiProviderEndpoint = "https://api.openai.com/v1"
                aiProviderConnectionStatus = "OpenAI-compatible provider selected. Save an API key before testing."
            }
        }
    }

    private fun saveAIProviderSettings() {
        val configuration = AIProviderConfiguration(
            provider = aiProviderKind,
            modelId = aiProviderModel.trim(),
            endpoint = aiProviderEndpoint.trim(),
            remoteFallbackEnabled = aiProviderFallbackEnabled
        )
        val errors = aiProviderConfigurationStore.save(configuration, aiProviderKeyDraft.takeIf(String::isNotBlank))
        if (errors.isNotEmpty()) {
            aiProviderConnectionStatus = errors.joinToString("\n")
            status = "AI provider settings were not saved: ${errors.first()}"
            return
        }
        aiProviderKeyDraft = ""
        aiProviderKeyConfigured = aiProviderConfigurationStore.hasApiKey()
        aiProviderConnectionStatus = if (aiProviderKind == AIProviderKind.LOCAL_ONLY) {
            "Saved. Local-only planning is active."
        } else {
            "Provider settings saved. API key is stored encrypted by Android Keystore. Test the connection manually."
        }
        status = "AI provider settings saved."
    }

    private fun testAIProviderConnection() {
        val configuration = AIProviderConfiguration(
            provider = aiProviderKind,
            modelId = aiProviderModel.trim(),
            endpoint = aiProviderEndpoint.trim(),
            remoteFallbackEnabled = aiProviderFallbackEnabled
        )
        aiProviderTestRunning = true
        aiProviderConnectionStatus = "Testing provider connection…"
        lifecycleScope.launch {
            val result = AIProviderConnectionTester.test(
                configuration,
                aiProviderKeyDraft.takeIf(String::isNotBlank) ?: aiProviderConfigurationStore.apiKey()
            )
            aiProviderTestRunning = false
            aiProviderConnectionStatus = result.message
            status = result.message
            if (result.connected && aiProviderKind != AIProviderKind.LOCAL_ONLY && aiProviderKeyDraft.isNotBlank()) {
                aiProviderConnectionStatus = "Connection successful. Save settings to store this key encrypted on the device."
            }
        }
    }

    private fun openAccessibilitySettings() {
        if (!accessibilityConsentAccepted) {
            status = "Screen-inspection consent is required before enabling the Accessibility service."
            return
        }
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { status = "Accessibility settings খুলতে পারিনি: ${it.message ?: "Unknown error"}" }
    }

    private fun reloadUiSnapshot() {
        if (!accessibilityConsentAccepted) {
            VisualFrameStore.clear()
            visualFrame = null
            visualCaptureStatus = ""
            uiSnapshot = ""
            uiSnapshotError = "Accept the screen-inspection disclosure before reading screen content."
            uiDiff = ""
            uiActionStatus = pendingUiActionStore.result()
            return
        }
        val prefs = getSharedPreferences("era_ui_snapshot", MODE_PRIVATE)
        uiSnapshot = prefs.getString("latest_json", "").orEmpty()
        uiSnapshotError = prefs.getString("capture_error", "").orEmpty()
        uiDiff = prefs.getString("latest_diff", "").orEmpty()
        visualCaptureStatus = prefs.getString("visual_capture_status", "").orEmpty()
        visualFrame = VisualFrameStore.current()
        uiActionStatus = pendingUiActionStore.result().let { result ->
            if (pendingUiActionStore.isPending()) "A one-shot action is approved. Switch to the reviewed app and tap ERA overlay."
            else result
        }
    }

    private fun copyUiSnapshot() {
        if (uiSnapshot.isBlank()) {
            status = "Copy করার মতো UI snapshot নেই। আগে অন্য app-এ গিয়ে ERA · Capture UI চাপো।"
            return
        }
        val compactSnapshot = runCatching { UiTreeJson.compactFromEncoded(uiSnapshot) }
            .getOrElse {
                status = "Compact JSON তৈরি করা যায়নি: ${it.message ?: "Invalid snapshot"}"
                return
            }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ERA compact UI JSON", compactSnapshot))
        status = "Compact UI JSON কপি হয়েছে। Editable field-এর বর্তমান value ও password field বাদ রাখা হয়েছে। শেয়ার করার আগে review করো।"
    }

    private fun copyAiContext() {
        if (uiSnapshot.isBlank()) {
            status = "AI context তৈরির আগে অন্য app থেকে structured UI tree capture করো।"
            return
        }
        val contextText = UiAgentContextBuilder.build(transcript, uiSnapshot)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ERA UI agent context", contextText))
        status = "AI context clipboard-এ কপি হয়েছে। Trusted AI-তে পাঠানোর আগে পুরো text review করো।"
    }

    private fun clearVisualFrame() {
        VisualFrameStore.clear()
        visualFrame = null
        getSharedPreferences("era_ui_snapshot", MODE_PRIVATE).edit()
            .remove("visual_capture_status")
            .apply()
        visualCaptureStatus = "Visual frame cleared from process memory."
        status = visualCaptureStatus
    }

    private fun clearUiSnapshot() {
        getSharedPreferences("era_ui_snapshot", MODE_PRIVATE).edit().clear().apply()
        uiSnapshot = ""
        uiSnapshotError = ""
        uiDiff = ""
        status = "Stored UI snapshot মুছে ফেলা হয়েছে।"
    }

    private fun requestVoiceSessionStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundVoiceSession()
        } else {
            pendingVoiceSessionStart = true
            pendingMicrophonePermissionAction = MicrophonePermissionAction.VOICE_SESSION
            speechPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startForegroundVoiceSession() {
        pendingVoiceSessionStart = false
        foregroundVoiceSession.start(speechLanguage)
        voiceSessionActive = foregroundVoiceSession.isActive()
    }

    private fun stopVoiceSession() {
        if (::foregroundVoiceSession.isInitialized) foregroundVoiceSession.stop()
        voiceSessionActive = false
    }

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpeechRecognition()
        } else {
            pendingMicrophonePermissionAction = MicrophonePermissionAction.SINGLE_COMMAND
            speechPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "ERA-কে command বলো")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            speechResult.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status = "এই ফোনে speech recognition service পাওয়া যায়নি।"
        }
    }

    private fun handleCommand(text: String) {
        planPreview = ""
        taskMonitorText = ""
        val normalizedInput = text.trim().trimEnd('.', ',', '।', '!').lowercase()
        val alias = aliases.firstOrNull {
            it.phrase.trim().trimEnd('.', ',', '।', '!').lowercase() == normalizedInput
        }
        val command = router.route(alias?.mappedCommand ?: text)
        if (command is AssistantCommand.UnsupportedComplexTask) {
            previewComplexTask(command.originalText)
            return
        }
        val needsCameraPermission = when (command) {
            is AssistantCommand.SetTorch -> true
            is AssistantCommand.RunSequence -> command.commands.any { it is AssistantCommand.SetTorch }
            else -> false
        }
        if (needsCameraPermission &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCommandAfterCameraPermission = text
            torchPermission.launch(Manifest.permission.CAMERA)
            return
        }
        when (command) {
            is AssistantCommand.RunSequence -> runSequence(command.commands, text)
            AssistantCommand.PickContactForDial -> openContactPicker()
            AssistantCommand.CancelCurrentTask -> {
                sequenceJob?.cancel()
                sequenceJob = null
                stopVoiceSession()
                pendingUiActionStore.clearPending("Pending UI action cancelled by user.")
                uiActionStatus = pendingUiActionStore.result()
                status = "চলমান command sequence এবং pending UI action বন্ধ করা হয়েছে।"
                recordCommand(text, status)
            }
            else -> {
                status = try {
                    deviceController.execute(command)
                } catch (error: SecurityException) {
                    "Permission denied: ${error.message ?: "এই কাজের অনুমতি নেই।"}"
                } catch (error: Exception) {
                    "কাজটি সম্পন্ন হয়নি: ${error.message ?: "Unknown error"}"
                }
                recordCommand(text, status)
            }
        }
    }

    private fun confirmPendingCall() {
        val pending = pendingCallConfirmation ?: return
        if (!CallConfirmationPolicy.canConfirm(pending, System.currentTimeMillis())) {
            pendingCallConfirmation = null
            status = "Call confirmation expired. Select the contact again."
            return
        }
        pendingCallConfirmation = null
        status = deviceController.openDialer(pending.dialNumber)
        recordCommand(
            "Call confirmation: ${pending.contactName} (${pending.displayNumber})",
            "User confirmed. Android dialer opened; no call was placed by ERA."
        )
    }

    private fun openContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        runCatching { contactPicker.launch(intent) }
            .onFailure { status = "Contact picker খোলা যায়নি: ${it.message ?: "Unknown error"}" }
    }

    private fun runSequence(commands: List<AssistantCommand>, original: String) {
        sequenceJob?.cancel()
        sequenceJob = lifecycleScope.launch {
            status = "Command sequence শুরু হচ্ছে (${commands.size} steps)…"
            for ((index, command) in commands.withIndex()) {
                if (!isActive) break
                status = "Step ${index + 1}/${commands.size}: ${command::class.simpleName}"
                val result = runCatching { deviceController.execute(command) }
                    .getOrElse { "কাজটি ব্যর্থ হয়েছে: ${it.message ?: "Unknown error"}" }
                status = result
                recordCommand("${index + 1}. ${command::class.simpleName}", result)
                if (isExecutionFailure(result)) {
                    status = "Sequence stopped at step ${index + 1}: $result"
                    recordCommand(original, status)
                    return@launch
                }
                delay(350)
            }
            if (isActive) {
                status = "Command sequence সম্পন্ন হয়েছে।"
                recordCommand(original, status)
                if (speechOutputEnabled) voiceOutput.speak(status)
            } else {
                status = "Command sequence বাতিল করা হয়েছে।"
            }
        }
    }

    private fun isExecutionFailure(message: String): Boolean {
        val value = message.lowercase()
        return listOf(
            "পাওয়া যায়নি", "পাওয়া যায়নি", "খোলা যায়নি", "খোলা যায়নি", "করা যায়নি", "করা যায়নি",
            "ব্যর্থ", "ইনস্টল করা নেই", "একাধিক app", "not installed", "not found", "permission denied", "failed", "unknown error"
        ).any(value::contains)
    }

    private fun approveUiAction(selectorText: String, action: String, payload: String, resourceId: String = "") {
        if (!accessibilityConsentAccepted) {
            uiActionStatus = "Screen-inspection consent is required before approving a UI action."
            return
        }
        if (uiSnapshot.isBlank()) {
            uiActionStatus = "প্রথমে target app থেকে UI capture করো, তারপর action approve করো।"
            return
        }
        val reviewedSnapshot = runCatching { JSONObject(uiSnapshot) }.getOrNull()
        val expectedPackage = reviewedSnapshot?.optString("packageName")?.takeIf { it.isNotBlank() && it != "null" }
        val rootJson = runCatching { reviewedSnapshot?.getJSONObject("root")?.toString() }.getOrNull()
        val observationId = if (rootJson != null && reviewedSnapshot != null) {
            runCatching {
                UiSnapshotFingerprint.of(
                    packageName = expectedPackage,
                    canonicalRootJson = rootJson,
                    nodeCount = reviewedSnapshot.optInt("nodeCount", 0),
                    truncated = reviewedSnapshot.optBoolean("truncated", false)
                )
            }.getOrNull()
        } else null
        if (observationId == null) {
            uiActionStatus = "Current UI snapshot invalid. Capture the screen again before approving."
            return
        }
        val approved = pendingUiActionStore.approve(
            selectorText = selectorText,
            action = action,
            payload = payload,
            expectedPackage = expectedPackage,
            resourceId = resourceId,
            reviewedObservationId = observationId
        )
        uiActionStatus = if (approved) {
            "One-shot action approved for the exact screen state in ${expectedPackage ?: "target app"}. Switch back and tap ERA overlay within 2 minutes. Any screen change cancels approval."
        } else "Action approve করা যায়নি। Exact target এবং প্রয়োজনীয় text দাও।"
        status = uiActionStatus
        if (speechOutputEnabled) voiceOutput.speak(
            if (approved) "Action approved for this exact screen. Switch to the target app and tap the ERA overlay."
            else "Action approval failed. No action is pending."
        )
    }

    private fun recordCommand(command: String, result: String) {
        history = historyStore.add(command, result)
        if (speechOutputEnabled) voiceOutput.speak(result)
    }

    private fun previewComplexTask() = previewComplexTask(transcript.trim())

    private fun previewComplexTask(request: String) {
        if (request.isBlank()) {
            status = "আগে একটি complex task লিখে বা বলে দাও।"
            return
        }
        lifecycleScope.launch {
            val availableCapabilities = AndroidCapabilityDiscovery(this@MainActivity).discover()
            val localPlanner = PreviewPlanner(availableCapabilities)
            val providerConfig = aiProviderConfigurationStore.load()
            val remoteReady = providerConfig.remoteFallbackEnabled &&
                providerConfig.isRemoteConfigured(aiProviderConfigurationStore.hasApiKey())
            val routedPlanner = LocalFirstPlannerRouter(
                localPlanner = { goal ->
                    val localPlan = localPlanner.createPlan(goal)
                    val needsRemote = localPlan.steps.firstOrNull()?.title == "Extract goal and constraints"
                    LocalPlanningCandidate(
                        localPlan,
                        if (needsRemote) PlanningConfidence.NEEDS_REMOTE_REASONING else PlanningConfidence.SUFFICIENT
                    )
                },
                remotePlanner = { goal ->
                    val key = aiProviderConfigurationStore.apiKey()
                        ?: error("Remote provider key is not configured.")
                    val provider = RemoteAIPlannerProvider(providerConfig, key)
                    val response = provider.plan(
                        PlannerRequest(
                            goal = goal,
                            capabilities = availableCapabilities.map { it.name },
                            uiContext = null
                        )
                    )
                    PlannerResponseAdapter.toTaskPlan(goal, response)
                }
            )
            val routingResult = runCatching {
                routedPlanner.plan(request, remoteEnabled = remoteReady)
            }
            val routed = routingResult.getOrNull()
            val plan = routed?.value ?: localPlanner.createPlan(request)
            routingResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val routingNote = routed?.note.orEmpty().ifBlank {
                routingResult.exceptionOrNull()?.let {
                    "Remote planner unavailable or rejected the request; the validated local plan is retained: ${it.message.orEmpty().take(160)}"
                }.orEmpty()
            }
            val displayPlan = if (routingNote.isBlank()) plan else plan.copy(note = "${plan.note}\n$routingNote")
            val taskCoordinator = runCatching {
                TaskExecutionCoordinator.from(plan, SharedPreferencesTaskLedgerPersistence(this@MainActivity))
            }.getOrNull()
            taskMonitorText = taskCoordinator?.let { coordinator ->
                TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all()).toDisplayText()
            } ?: "Status: FAILED — task ledger could not be initialized."
            planPreview = buildString {
                appendLine("Goal: ${plan.goal}")
                appendLine("Planner source: ${routed?.source ?: PlannerSource.LOCAL_FALLBACK}")
                appendLine("Plan version: ${plan.planVersion} | Risk: ${plan.overallRisk} | Estimated actions: ${plan.estimatedActions}")
                appendLine("Plan validation: ${if (plan.validate().isEmpty() && taskCoordinator != null) "PASS" else "FAILED"}")
                appendLine("Initial ready steps: ${taskCoordinator?.readySteps()?.joinToString { it.step.id } ?: "none"}")
                appendLine("Discovered capabilities: ${availableCapabilities.joinToString().ifBlank { "none" }}")
                plan.steps.forEach { step ->
                    val approval = if (step.requiresUserApproval) " [USER APPROVAL REQUIRED]" else ""
                    val deps = if (step.dependsOn.isEmpty()) "none" else step.dependsOn.joinToString()
                    appendLine("${step.id}. ${step.title} — ${step.description}")
                    appendLine("   Depends on: $deps | Risk: ${step.risk} | Retries: ${step.retryLimit}$approval")
                    if (step.requiredCapabilities.isNotEmpty()) appendLine("   Capabilities: ${step.requiredCapabilities.joinToString()}")
                    step.acceptanceCriteria.forEach { appendLine("   ✓ $it") }
                }
                appendLine("Overall acceptance criteria:")
                plan.acceptanceCriteria.forEach { appendLine("• $it") }
                append(displayPlan.note)
            }
            status = "Task plan preview তৈরি হয়েছে। এখনো কোনো app action চালানো হয়নি।"
            history = historyStore.add(request, status)
            if (speechOutputEnabled) voiceOutput.speak(status)
        }
    }

    private fun addAlias(phrase: String, mappedCommand: String) {
        if (phrase.isBlank() || mappedCommand.isBlank()) {
            status = "Alias phrase এবং mapped command দুটোই দিতে হবে।"
            return
        }
        val mapped = router.route(mappedCommand)
        if (mapped is AssistantCommand.Help || mapped is AssistantCommand.UnsupportedComplexTask) {
            status = "এই alias-এর target command instant allow-list-এর মধ্যে নেই। আগে একটি supported simple command দাও।"
            return
        }
        aliases = customCommandStore.save(CustomCommandAlias(phrase, mappedCommand))
        status = "Custom voice phrase সংরক্ষণ করা হয়েছে: $phrase"
        if (speechOutputEnabled) voiceOutput.speak(status)
    }

    private fun deleteAlias(phrase: String) {
        aliases = customCommandStore.delete(phrase)
        status = "Custom phrase মুছে ফেলা হয়েছে।"
    }

    override fun onDestroy() {
        sequenceJob?.cancel()
        if (::foregroundVoiceSession.isInitialized) foregroundVoiceSession.stop()
        if (::voiceOutput.isInitialized) voiceOutput.release()
        super.onDestroy()
    }
}
