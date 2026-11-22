package com.eraassistant.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.app.ActivityManager
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eraassistant.mobile.approval.VoiceApprovalEngine
import com.eraassistant.mobile.approval.VoiceApprovalResult
import com.eraassistant.mobile.execution.ActionIntentCompiler
import com.eraassistant.mobile.storage.SafStorageManager
import com.eraassistant.mobile.storage.StorageDocument
import com.eraassistant.mobile.storage.StorageTextPreview
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.core.CommandRouter
import com.eraassistant.mobile.contacts.CallConfirmationPolicy
import com.eraassistant.mobile.contacts.ContactAliasStore
import com.eraassistant.mobile.contacts.ContactCandidate
import com.eraassistant.mobile.contacts.ContactMatchEngine
import com.eraassistant.mobile.contacts.ContactMatchResult
import com.eraassistant.mobile.contacts.PendingCallConfirmation
import com.eraassistant.mobile.browser.PhoneBrowserAgentClient
import com.eraassistant.mobile.browser.PhoneBrowserAgentUiState
import com.eraassistant.mobile.browser.PhoneBrowserAgentPolicy
import com.eraassistant.mobile.device.DeviceController
import com.eraassistant.mobile.device.AndroidCapabilityDiscovery
import com.eraassistant.mobile.history.CommandHistoryStore
import com.eraassistant.mobile.planner.PreviewPlanner
import com.eraassistant.mobile.tasks.TaskExecutionCoordinator
import com.eraassistant.mobile.tasks.TaskExecutionMonitor
import com.eraassistant.mobile.tasks.TaskStepRecord
import com.eraassistant.mobile.tasks.TransitionResult
import com.eraassistant.mobile.tasks.SharedPreferencesTaskLedgerPersistence
import com.eraassistant.mobile.orchestration.TaskPlanDAG
import com.eraassistant.mobile.orchestration.PlannerFeedbackLoop
import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.capability.adapters.AndroidDeviceCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.NativeWebViewBrowserCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.ExternalChromeAccessibilityCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.AccessibilityUiCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.VisualInspectionCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.SearchAndRecognitionCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.SafStorageCapabilityAdapter
import com.eraassistant.mobile.recovery.ExecutionJournal
import com.eraassistant.mobile.recovery.SharedPreferencesExecutionJournalPersistence
import com.eraassistant.mobile.verification.VerificationEngine
import com.eraassistant.mobile.skills.SkillEngine
import com.eraassistant.mobile.memory.PersistentAgentMemory
import com.eraassistant.mobile.memory.WorkflowLearningEngine
import com.eraassistant.mobile.offline.OfflineOperationManager
import com.eraassistant.mobile.statemachine.SharedPreferencesTaskStatePersistence
import com.eraassistant.mobile.provider.AIProviderConfiguration
import com.eraassistant.mobile.provider.AIProviderConfigurationStore
import com.eraassistant.mobile.provider.AIProviderConnectionTester
import com.eraassistant.mobile.provider.AIProviderKind
import com.eraassistant.mobile.provider.LocalFirstPlannerRouter
import com.eraassistant.mobile.provider.LocalPlanningCandidate
import com.eraassistant.mobile.provider.PlanningConfidence
import com.eraassistant.mobile.provider.PlannerSource
import com.eraassistant.mobile.provider.RemoteAIPlannerProvider
import com.eraassistant.mobile.provider.LocalOllamaPlannerProvider
import com.eraassistant.mobile.provider.PlannerResponseAdapter
import com.eraassistant.mobile.ai.PlannerRequest
import com.eraassistant.mobile.preferences.CustomCommandAlias
import com.eraassistant.mobile.preferences.CustomCommandStore
import com.eraassistant.mobile.preferences.AssistantPreferencesStore
import com.eraassistant.mobile.speech.VoiceOutput
import com.eraassistant.mobile.speech.ForegroundVoiceSession
import com.eraassistant.mobile.screen.UiAgentContextBuilder
import com.eraassistant.mobile.screen.UiTreeJson
import com.eraassistant.mobile.screen.UiScreenSnapshot
import com.eraassistant.mobile.screen.PendingUiActionStore
import com.eraassistant.mobile.screen.UiSnapshotFingerprint
import com.eraassistant.mobile.screen.VisualFrame
import com.eraassistant.mobile.screen.VisualFrameStore
import com.eraassistant.mobile.search.ProductSearchQuery
import com.eraassistant.mobile.search.VisualFrameContentProvider
import com.eraassistant.mobile.search.VisualProductRecognitionProvider
import com.eraassistant.mobile.social.InstagramPreparationPolicy
import com.eraassistant.mobile.accessibility.ExternalChromeAgentPolicy
import com.eraassistant.mobile.shizuku.ShizukuBridgeManager
import com.eraassistant.mobile.shizuku.ShizukuBridgeState
import com.eraassistant.mobile.remote.RemoteGatewayApiClient
import com.eraassistant.mobile.remote.RemoteGatewayPolicy
import com.eraassistant.mobile.remote.RemoteGatewayStore
import com.eraassistant.mobile.remote.RemoteSessionService
import com.eraassistant.mobile.ui.ERAScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private companion object {
        const val MAX_CONTACT_CANDIDATES = 3_000
        const val MAX_CONTACT_ALIAS_LENGTH = 60
        const val CONTACT_CACHE_TTL_MS = 30_000L
    }
    private val router = CommandRouter()
    private lateinit var deviceController: DeviceController
    private lateinit var historyStore: CommandHistoryStore
    private lateinit var customCommandStore: CustomCommandStore
    private lateinit var assistantPreferencesStore: AssistantPreferencesStore
    private lateinit var aiProviderConfigurationStore: AIProviderConfigurationStore
    private lateinit var remoteGatewayStore: RemoteGatewayStore
    private lateinit var phoneBrowserAgentClient: PhoneBrowserAgentClient
    private lateinit var voiceOutput: VoiceOutput
    private lateinit var pendingUiActionStore: PendingUiActionStore
    private lateinit var contactAliasStore: ContactAliasStore
    private lateinit var safStorageManager: SafStorageManager
    private var activeTaskCoordinator: TaskExecutionCoordinator? = null
    private val capabilityRegistry = CapabilityRegistry()
    private val voiceApprovalEngine = VoiceApprovalEngine()
    private lateinit var executionJournal: ExecutionJournal
    private lateinit var taskStatePersistence: SharedPreferencesTaskStatePersistence
    private lateinit var verificationEngine: VerificationEngine
    private lateinit var skillEngine: SkillEngine
    private lateinit var persistentAgentMemory: PersistentAgentMemory
    private lateinit var workflowLearningEngine: WorkflowLearningEngine
    private lateinit var offlineOperationManager: OfflineOperationManager
    private lateinit var foregroundVoiceSession: ForegroundVoiceSession
    private var pendingVoiceSessionStart = false
    private enum class MicrophonePermissionAction { VOICE_SESSION, SINGLE_COMMAND, SETUP }
    private var pendingMicrophonePermissionAction: MicrophonePermissionAction? = null
    private var voiceSessionActive by mutableStateOf(false)
    private var sequenceJob: Job? = null
    private var contactSearchJob: Job? = null
    private var contactSearchGeneration = 0L
    private var cachedContactCandidates: List<ContactCandidate>? = null
    private var contactCandidatesCachedAtEpochMs = 0L
    private var pendingCommandAfterCameraPermission: String? = null

    private var transcript by mutableStateOf("")
    private var status by mutableStateOf("Ready — ERA is waiting for a command.")
    private var history by mutableStateOf(listOf<String>())
    private var aliases by mutableStateOf(listOf<CustomCommandAlias>())
    private var planPreview by mutableStateOf("")
    private var taskMonitorText by mutableStateOf("")
    private var taskSteps by mutableStateOf(emptyList<TaskStepRecord>())
    private var contactSearchQuery by mutableStateOf("")
    private var contactMatches by mutableStateOf(emptyList<ContactCandidate>())
    private var selectedContactForAlias by mutableStateOf<ContactCandidate?>(null)
    private var instagramMediaUri by mutableStateOf<Uri?>(null)
    private var instagramMediaLabel by mutableStateOf("")
    private var instagramCaption by mutableStateOf("")
    private var instagramHashtags by mutableStateOf("")
    private var productSearchQuery by mutableStateOf("")
    private var productRecognitionStatus by mutableStateOf("")
    private var productRecognitionRunning by mutableStateOf(false)
    private var pendingContactQueryForCall by mutableStateOf(false)
    private var aiProviderKind by mutableStateOf(AIProviderKind.LOCAL_ONLY)
    private var aiProviderModel by mutableStateOf("")
    private var aiProviderEndpoint by mutableStateOf("")
    private var aiProviderKeyDraft by mutableStateOf("")
    private var aiProviderKeyConfigured by mutableStateOf(false)
    private var aiProviderFallbackEnabled by mutableStateOf(false)
    private var aiProviderConnectionStatus by mutableStateOf("Local-only planner is active.")
    private var aiProviderTestRunning by mutableStateOf(false)
    private var phoneBrowserAgent by mutableStateOf(PhoneBrowserAgentUiState())
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
    private var externalChromeTargetMode by mutableStateOf(false)
    private var externalChromeStatus by mutableStateOf("External Chrome target is inactive.")
    private lateinit var shizukuBridgeManager: ShizukuBridgeManager
    private var shizukuBridgeState by mutableStateOf(ShizukuBridgeState())
    private var storageFolderName by mutableStateOf("")
    private var storageStatus by mutableStateOf("Choose a folder to grant ERA read-only access.")
    private var storageEntries by mutableStateOf(emptyList<StorageDocument>())
    private var storagePreview by mutableStateOf<StorageTextPreview?>(null)
    private var storageCurrentParentId by mutableStateOf<String?>(null)
    private var remoteGatewayEndpoint by mutableStateOf("")
    private var remotePairingCode by mutableStateOf("")
    private var remoteGatewayStatus by mutableStateOf("Not paired with a remote gateway.")
    private var remoteGatewayPaired by mutableStateOf(false)
    private var remoteGatewaySessionActive by mutableStateOf(false)
    private var remoteGatewayPairingBusy by mutableStateOf(false)
    private var remoteStatusRefreshJob: Job? = null

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

    private val remoteNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRemoteSessionNow()
        else {
            remoteGatewayStatus = "Notification permission was denied. Remote foreground session was not started."
            remoteGatewayStore.setStatus(remoteGatewayStatus)
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

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) searchContactsNow()
        else {
            val callWasRequested = pendingContactQueryForCall
            pendingContactQueryForCall = false
            if (callWasRequested) {
                status = "Contacts permission দেওয়া হয়নি। Android-এর system contact picker খুলছি; ERA contacts নিজে পড়বে না।"
                openContactPicker()
            } else {
                status = "Contacts permission দেওয়া হয়নি। System contact picker এখনও ব্যবহার করতে পারো।"
            }
        }
    }

    private val instagramMediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            instagramMediaUri = uri
            instagramMediaLabel = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                }.orEmpty()
            }.getOrDefault("").ifBlank { uri.lastPathSegment ?: "Selected media" }
            status = "Instagram media selected. Add caption/hashtags, review, then prepare the draft."
        } else {
            status = "Media picker cancelled; the existing Instagram draft was kept."
        }
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

    private val storageTreePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            storageStatus = "Folder picker cancelled. Existing access was kept."
        } else {
            runCatching { safStorageManager.persistSelectedTree(uri) }
                .onSuccess {
                    storagePreview = null
                    storageCurrentParentId = null
                    storageStatus = "Folder access saved. ERA can list and preview supported text files only."
                    refreshStorageListing(null)
                }
                .onFailure { error ->
                    storageStatus = "Folder access could not be saved: ${error.message ?: "Unknown error"}"
                }
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
        shizukuBridgeManager = ShizukuBridgeManager(this) { nextState ->
            runOnUiThread { shizukuBridgeState = nextState }
        }
        historyStore = CommandHistoryStore(this)
        customCommandStore = CustomCommandStore(this)
        assistantPreferencesStore = AssistantPreferencesStore(this)
        aiProviderConfigurationStore = AIProviderConfigurationStore(this)
        remoteGatewayStore = RemoteGatewayStore(this)
        remoteGatewayEndpoint = remoteGatewayStore.endpointDraft()
        val providerConfig = aiProviderConfigurationStore.load()
        aiProviderKind = providerConfig.provider
        aiProviderModel = providerConfig.modelId
        aiProviderEndpoint = providerConfig.endpoint
        aiProviderFallbackEnabled = providerConfig.remoteFallbackEnabled
        aiProviderKeyConfigured = aiProviderConfigurationStore.hasApiKey()
        phoneBrowserAgentClient = PhoneBrowserAgentClient(this) { targetId, title, url, loading, message ->
            runOnUiThread {
                val sessions = runCatching { phoneBrowserAgentClient.sessionsNow() }
                    .getOrDefault(phoneBrowserAgent.sessions)
                val selectedTargetId = if (sessions.any { it.targetId == phoneBrowserAgent.selectedTargetId }) {
                    phoneBrowserAgent.selectedTargetId
                } else {
                    sessions.firstOrNull { it.active }?.targetId ?: targetId
                }
                val isSelected = targetId == selectedTargetId
                phoneBrowserAgent = phoneBrowserAgent.copy(
                    busy = if (isSelected) loading else phoneBrowserAgent.busy,
                    status = if (isSelected) message else phoneBrowserAgent.status,
                    sessions = sessions,
                    selectedTargetId = selectedTargetId,
                    addressDraft = if (isSelected && url.isNotBlank()) url else phoneBrowserAgent.addressDraft
                )
            }
        }
        voiceOutput = VoiceOutput(this)
        pendingUiActionStore = PendingUiActionStore(this)
        contactAliasStore = ContactAliasStore(this)
        safStorageManager = SafStorageManager(this)
        storageFolderName = safStorageManager.rootName().orEmpty()
        if (safStorageManager.hasReadAccess()) {
            storageStatus = "Existing user-selected folder grant is active."
        }
        executionJournal = ExecutionJournal(SharedPreferencesExecutionJournalPersistence(this))
        taskStatePersistence = SharedPreferencesTaskStatePersistence(this)
        val recoveredJournalEntries = executionJournal.reconcileAfterRestart()
        if (recoveredJournalEntries.isNotEmpty()) {
            status = "${recoveredJournalEntries.size} unfinished action(s) were moved to RECOVERY_REQUIRED. Inspect the target app before starting another attempt."
        } else if (!executionJournal.isAvailable()) {
            status = "Execution journal is unavailable. Automated capability dispatch is disabled until its stored state can be read safely."
        }
        verificationEngine = VerificationEngine()
        persistentAgentMemory = PersistentAgentMemory(this)
        workflowLearningEngine = WorkflowLearningEngine(persistentAgentMemory)
        offlineOperationManager = OfflineOperationManager(this, capabilityRegistry)

        capabilityRegistry.register(AndroidDeviceCapabilityAdapter(this, deviceController))
        capabilityRegistry.register(NativeWebViewBrowserCapabilityAdapter { phoneBrowserAgentClient })
        capabilityRegistry.register(ExternalChromeAccessibilityCapabilityAdapter(this, { externalChromeTargetMode }, { latestAccessibilitySnapshot() }, pendingUiActionStore))
        capabilityRegistry.register(AccessibilityUiCapabilityAdapter(this, { accessibilityServiceEnabled }, { latestAccessibilitySnapshot() }, pendingUiActionStore))
        capabilityRegistry.register(VisualInspectionCapabilityAdapter { VisualFrameStore.current() })
        capabilityRegistry.register(SearchAndRecognitionCapabilityAdapter(this))
        capabilityRegistry.register(SafStorageCapabilityAdapter(safStorageManager))

        skillEngine = SkillEngine(capabilityRegistry)
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
        refreshRemoteGatewayState()
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
                shizukuBridgeState = shizukuBridgeState,
                onConnectShizukuBridge = ::connectShizukuBridge,
                onRefreshShizukuBridge = { shizukuBridgeManager.refresh() },
                onReadShizukuDiagnostic = ::readShizukuDiagnostic,
                remoteGatewayEndpoint = remoteGatewayEndpoint,
                remotePairingCode = remotePairingCode,
                remoteGatewayStatus = remoteGatewayStatus,
                remoteGatewayPaired = remoteGatewayPaired,
                remoteGatewaySessionActive = remoteGatewaySessionActive,
                remoteGatewayPairingBusy = remoteGatewayPairingBusy,
                onRemoteGatewayEndpointChange = { remoteGatewayEndpoint = it },
                onRemotePairingCodeChange = { remotePairingCode = it },
                onPairRemoteGateway = ::pairRemoteGateway,
                onStartRemoteSession = ::requestRemoteSessionStart,
                onStopRemoteSession = ::stopRemoteSession,
                onUnpairRemoteGateway = ::unpairRemoteGateway,
                phoneBrowserAgent = phoneBrowserAgent,
                phoneBrowserWebView = phoneBrowserAgentClient.currentWebView(),
                onPhoneBrowserAddressChange = { phoneBrowserAgent = phoneBrowserAgent.copy(addressDraft = it) },
                onNavigatePhoneBrowser = ::navigatePhoneBrowser,
                onGoBackPhoneBrowser = ::goBackPhoneBrowser,
                onGoForwardPhoneBrowser = ::goForwardPhoneBrowser,
                onReloadPhoneBrowser = ::reloadPhoneBrowser,
                onNewPhoneBrowserTab = ::newPhoneBrowserTab,
                onClosePhoneBrowserTab = ::closePhoneBrowserTab,
                onTestPhoneBrowserAgent = ::testPhoneBrowserAgent,
                onRefreshPhoneBrowserSessions = ::refreshPhoneBrowserSessions,
                onSelectPhoneBrowserSession = ::selectPhoneBrowserSession,
                onCapturePhoneBrowserSnapshot = ::capturePhoneBrowserSnapshot,
                onPhoneBrowserRefChange = { phoneBrowserAgent = phoneBrowserAgent.copy(selectedRef = it) },
                onPhoneBrowserFillTextChange = { phoneBrowserAgent = phoneBrowserAgent.copy(fillText = it) },
                onPhoneBrowserKeyChange = { phoneBrowserAgent = phoneBrowserAgent.copy(keyName = it) },
                onRunPhoneBrowserAction = ::runPhoneBrowserAction,
                onRequestBrowserAutofill = ::requestBrowserAutofill,
                speechOutputEnabled = speechOutputEnabled,
                speechLanguage = speechLanguage,
                autoObservationEnabled = autoObservationEnabled,
                accessibilityConsentAccepted = accessibilityConsentAccepted,
                microphonePermissionGranted = microphonePermissionGranted,
                cameraPermissionGranted = cameraPermissionGranted,
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                externalChromeTargetMode = externalChromeTargetMode,
                externalChromeStatus = externalChromeStatus,
                onOpenExternalChrome = ::openExternalChromeTarget,
                onDisableExternalChrome = ::disableExternalChromeTarget,
                voiceSessionActive = voiceSessionActive,
                uiActionStatus = uiActionStatus,
                pendingCallConfirmation = pendingCallConfirmation,
                onConfirmCall = ::confirmPendingCall,
                onCancelCall = ::cancelPendingCall,
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
                        getSharedPreferences("era_preferences", MODE_PRIVATE).edit()
                            .putBoolean("external_chrome_target_mode", false)
                            .remove("external_chrome_target_package")
                            .apply()
                        getSharedPreferences(ExternalChromeAgentPolicy.PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                        externalChromeTargetMode = false
                        externalChromeStatus = "Screen-inspection consent revoked. External Chrome target disabled."
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
                taskSteps = taskSteps,
                onAdvanceTaskStep = ::advanceTaskStep,
                onVerifyTaskStep = ::verifyTaskStep,
                onCancelTaskMonitor = ::cancelActiveTask,
                contactSearchQuery = contactSearchQuery,
                contactMatches = contactMatches,
                selectedContactForAlias = selectedContactForAlias,
                onContactSearchQueryChange = { contactSearchQuery = it },
                onSearchContacts = ::searchContacts,
                onSelectContactMatch = ::selectContactMatch,
                onSaveContactAlias = { contactId, alias -> saveContactAlias(contactId, alias) },
                instagramMediaLabel = instagramMediaLabel,
                instagramCaption = instagramCaption,
                instagramHashtags = instagramHashtags,
                onPickInstagramMedia = ::pickInstagramMedia,
                onInstagramCaptionChange = { instagramCaption = it },
                onInstagramHashtagsChange = { instagramHashtags = it },
                onPrepareInstagramDraft = ::prepareInstagramDraft,
                productSearchQuery = productSearchQuery,
                onProductSearchQueryChange = { productSearchQuery = it },
                onShareVisualFrameForProductSearch = ::shareVisualFrameForProductSearch,
                onSearchShoppingPrices = ::searchShoppingPrices,
                productRecognitionStatus = productRecognitionStatus,
                productRecognitionRunning = productRecognitionRunning,
                onIdentifyVisualProduct = ::identifyVisualProduct,
                storageFolderName = storageFolderName,
                storageStatus = storageStatus,
                storageEntries = storageEntries,
                storagePreview = storagePreview,
                onPickStorageFolder = ::pickStorageFolder,
                onRefreshStorage = { refreshStorageListing(storageCurrentParentId) },
                onShowStorageRoot = { refreshStorageListing(null) },
                onOpenStorageDocument = ::openStorageDocument,
                onClearStorageFolder = ::clearStorageFolder,
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

    private fun connectShizukuBridge() {
        if (!::shizukuBridgeManager.isInitialized) return
        shizukuBridgeManager.connectOrRequestPermission()
    }

    private fun readShizukuDiagnostic(operationId: String) {
        val allowed = setOf("identity", "device", "battery", "apps", "display", "storage")
        if (operationId !in allowed) {
            status = "Unsupported Shizuku diagnostic operation."
            return
        }
        if (!::shizukuBridgeManager.isInitialized) {
            status = "Shizuku bridge is not initialized."
            return
        }
        status = "Running read-only Shizuku diagnostic: $operationId …"
        lifecycleScope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching { shizukuBridgeManager.executeReadOnly(operationId) }
                    .getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            }
            shizukuBridgeState = shizukuBridgeManager.snapshot().copy(lastOutput = output)
            status = if (output.startsWith("ERROR:")) {
                "Shizuku diagnostic failed: $operationId"
            } else {
                "Read-only Shizuku diagnostic completed: $operationId"
            }
        }
    }

    private fun pairRemoteGateway() {
        val endpoint = RemoteGatewayPolicy.normalizeEndpoint(remoteGatewayEndpoint)
        val code = remotePairingCode.trim()
        if (endpoint == null) {
            remoteGatewayStatus = "Use an HTTPS DNS hostname on port 443. Cleartext, IP literals and URL paths are rejected."
            return
        }
        if (!RemoteGatewayPolicy.isValidPairingCode(code)) {
            remoteGatewayStatus = "Pairing code format is invalid. Use the short-lived code issued by your gateway."
            return
        }
        if (remoteGatewayPairingBusy) return
        remoteGatewayPairingBusy = true
        remoteGatewayStatus = "Exchanging the one-time pairing code over HTTPS…"
        lifecycleScope.launch {
            try {
                val pairing = withContext(Dispatchers.IO) {
                    RemoteGatewayApiClient().exchangePairingCode(
                        endpoint = endpoint,
                        pairingCode = code,
                        deviceLabel = "ERA Android device"
                    )
                }
                remoteGatewayStore.save(endpoint, pairing)
                remoteGatewayEndpoint = endpoint
                remotePairingCode = ""
                remoteGatewayPaired = true
                remoteGatewaySessionActive = false
                remoteGatewayStatus = "Paired securely. Token stored with Android Keystore. Start the session when ready."
                remoteGatewayStore.setStatus(remoteGatewayStatus)
            } catch (error: Exception) {
                val reason = when (error) {
                    is com.eraassistant.mobile.remote.RemoteGatewayHttpException ->
                        "Gateway returned HTTP ${error.statusCode}."
                    else -> error.message?.take(120) ?: "Check the endpoint, TLS certificate and pairing code."
                }
                remoteGatewayStatus = "Pairing failed. $reason"
                remoteGatewayStore.setStatus(remoteGatewayStatus)
            } finally {
                remoteGatewayPairingBusy = false
            }
        }
    }

    private fun requestRemoteSessionStart() {
        if (remoteGatewayStore.loadSession() == null) {
            remoteGatewayStatus = "Pair this phone with the private gateway first."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            remoteNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startRemoteSessionNow()
    }

    private fun startRemoteSessionNow() {
        if (remoteGatewayStore.loadSession() == null) {
            remoteGatewayStatus = "Pairing credentials are missing or invalid. Pair again."
            return
        }
        try {
            remoteGatewayStore.setStatus("Foreground session requested; waiting for the first gateway heartbeat.")
            ContextCompat.startForegroundService(
                this,
                Intent(this, RemoteSessionService::class.java)
            )
            remoteGatewaySessionActive = true
            remoteGatewayStatus = remoteGatewayStore.status()
        } catch (error: Exception) {
            remoteGatewaySessionActive = false
            remoteGatewayStatus = "Remote session could not start: ${error.message?.take(100) ?: "Android rejected the service start."}"
            remoteGatewayStore.setStatus(remoteGatewayStatus)
        }
    }

    private fun stopRemoteSession() {
        stopService(Intent(this, RemoteSessionService::class.java))
        remoteGatewaySessionActive = false
        remoteGatewayStore.setSessionActive(false)
        remoteGatewayStatus = "Remote session stopped by user."
        remoteGatewayStore.setStatus(remoteGatewayStatus)
    }

    private fun unpairRemoteGateway() {
        stopService(Intent(this, RemoteSessionService::class.java))
        remoteGatewayStore.clearPairing()
        remoteGatewayStore.setSessionActive(false)
        remoteGatewayStore.setStatus("Pairing revoked on this phone. The gateway should also revoke this device token server-side.")
        remoteGatewayEndpoint = ""
        remotePairingCode = ""
        remoteGatewayPaired = false
        remoteGatewaySessionActive = false
        remoteGatewayStatus = remoteGatewayStore.status()
    }

    @Suppress("DEPRECATION")
    private fun refreshRemoteGatewayState() {
        remoteGatewayPaired = remoteGatewayStore.loadSession() != null
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val serviceRunning = activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == RemoteSessionService::class.java.name }
        remoteGatewaySessionActive = serviceRunning && remoteGatewayStore.isSessionMarkedActive()
        if (!serviceRunning && remoteGatewayStore.isSessionMarkedActive()) {
            remoteGatewayStore.setSessionActive(false)
            remoteGatewayStore.setStatus("Previous remote process stopped. Start the session manually to reconnect.")
        }
        remoteGatewayStatus = remoteGatewayStore.status()
    }

    override fun onResume() {
        super.onResume()
        if (::remoteGatewayStore.isInitialized) {
            refreshRemoteGatewayState()
            remoteStatusRefreshJob?.cancel()
            remoteStatusRefreshJob = lifecycleScope.launch {
                while (isActive) {
                    refreshRemoteGatewayState()
                    delay(2_000L)
                }
            }
        }
        if (::shizukuBridgeManager.isInitialized) shizukuBridgeManager.refresh()
        if (::phoneBrowserAgentClient.isInitialized) phoneBrowserAgentClient.onResume()
        // A user may have edited contacts in another app while ERA was paused.
        cachedContactCandidates = null
        contactCandidatesCachedAtEpochMs = 0L
        contactSearchGeneration += 1
        contactSearchJob?.cancel()
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
        remoteStatusRefreshJob?.cancel()
        remoteStatusRefreshJob = null
        if (::phoneBrowserAgentClient.isInitialized) phoneBrowserAgentClient.onPause()
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
                aiProviderConnectionStatus = "Local-only deterministic planner selected. No API key or network call is used."
            }
            AIProviderKind.OLLAMA_LOCAL -> {
                if (aiProviderModel.isBlank()) aiProviderModel = "llama3.2:1b"
                if (aiProviderEndpoint.isBlank()) aiProviderEndpoint = "http://localhost:11434"
                aiProviderFallbackEnabled = false
                aiProviderConnectionStatus = "Same-device Ollama selected. Install/start Ollama and ensure the selected model is downloaded."
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
        aiProviderConnectionStatus = when (aiProviderKind) {
            AIProviderKind.LOCAL_ONLY -> "Saved. Deterministic local-only planning is active."
            AIProviderKind.OLLAMA_LOCAL -> "Saved. Local Ollama endpoint and model are configured; no API key is stored."
            AIProviderKind.GEMINI, AIProviderKind.OPENAI_COMPATIBLE ->
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
            if (result.connected &&
                aiProviderKind in setOf(AIProviderKind.GEMINI, AIProviderKind.OPENAI_COMPATIBLE) &&
                aiProviderKeyDraft.isNotBlank()
            ) {
                aiProviderConnectionStatus = "Connection successful. Save settings to store this key encrypted on the device."
            }
        }
    }

    private fun requestBrowserAutofill() {
        val targetId = phoneBrowserAgent.selectedTargetId
        if (targetId.isBlank()) {
            status = "Open a page in the ERA browser and focus the intended sign-in field first."
            return
        }
        lifecycleScope.launch {
            status = runCatching {
                phoneBrowserAgentClient.requestSystemAutofill(targetId)
            }.getOrElse { error ->
                "Android Autofill could not be requested: ${error.message ?: "Unknown error"}"
            }
        }
    }

    private fun navigatePhoneBrowser() {
        val address = phoneBrowserAgent.addressDraft
        lifecycleScope.launch {
            phoneBrowserAgent = phoneBrowserAgent.copy(busy = true, status = "Opening page in ERA browser…")
            runCatching { phoneBrowserAgentClient.navigate(address) }
                .onSuccess { url ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = true,
                        addressDraft = url,
                        status = "Opening " + url
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        status = error.message ?: "Could not open the page."
                    )
                }
        }
    }

    private fun newPhoneBrowserTab() {
        runCatching {
            val targetId = phoneBrowserAgentClient.newTab()
            phoneBrowserAgent = phoneBrowserAgent.copy(
                selectedTargetId = targetId,
                sessions = phoneBrowserAgentClient.sessionsNow(),
                snapshot = null,
                selectedRef = "",
                addressDraft = "",
                fillText = "",
                status = "New ERA browser tab opened."
            )
        }.onFailure { error ->
            phoneBrowserAgent = phoneBrowserAgent.copy(status = error.message ?: "Could not open a new browser tab.")
        }
    }

    private fun closePhoneBrowserTab() {
        val targetId = phoneBrowserAgent.selectedTargetId
        runCatching {
            phoneBrowserAgentClient.closeSession(targetId)
            phoneBrowserAgent = phoneBrowserAgent.copy(
                selectedTargetId = phoneBrowserAgentClient.activeTargetId(),
                sessions = phoneBrowserAgentClient.sessionsNow(),
                snapshot = null,
                selectedRef = "",
                fillText = "",
                addressDraft = phoneBrowserAgentClient.sessionsNow()
                    .firstOrNull { it.active }?.url.orEmpty(),
                status = "Browser tab closed."
            )
        }.onFailure { error ->
            phoneBrowserAgent = phoneBrowserAgent.copy(status = error.message ?: "Could not close browser tab.")
        }
    }

    private fun goForwardPhoneBrowser() {
        val targetId = phoneBrowserAgent.selectedTargetId
        lifecycleScope.launch {
            val moved = runCatching { phoneBrowserAgentClient.goForward(targetId) }.getOrDefault(false)
            phoneBrowserAgent = phoneBrowserAgent.copy(
                snapshot = null,
                selectedRef = "",
                status = if (moved) "Moving forward in browser history…" else "No forward page in this tab."
            )
        }
    }

    private fun reloadPhoneBrowser() {
        val targetId = phoneBrowserAgent.selectedTargetId
        lifecycleScope.launch {
            runCatching { phoneBrowserAgentClient.reload(targetId) }
                .onSuccess {
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = true,
                        snapshot = null,
                        selectedRef = "",
                        status = "Refreshing current browser tab…"
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(status = error.message ?: "Could not refresh the page.")
                }
        }
    }

    private fun testPhoneBrowserAgent() {
        lifecycleScope.launch {
            phoneBrowserAgent = phoneBrowserAgent.copy(busy = true, status = "Checking ERA in-app browser…")
            runCatching { phoneBrowserAgentClient.testConnection() }
                .onSuccess { result ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        status = result
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        status = error.message ?: "In-app browser is unavailable."
                    )
                }
        }
    }

    private fun refreshPhoneBrowserSessions(includePrivateDetails: Boolean = false) {
        lifecycleScope.launch {
            phoneBrowserAgent = phoneBrowserAgent.copy(busy = true, status = "Reading ERA browser session…")
            runCatching {
                phoneBrowserAgentClient.listSessions(
                    includePrivateDetails = includePrivateDetails,
                    confirmed = includePrivateDetails
                )
            }
                .onSuccess { sessions ->
                    val selectedStillExists = sessions.any { it.targetId == phoneBrowserAgent.selectedTargetId }
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        sessions = sessions,
                        selectedTargetId = if (selectedStillExists) phoneBrowserAgent.selectedTargetId else phoneBrowserAgentClient.activeTargetId(),
                        snapshot = if (selectedStillExists) phoneBrowserAgent.snapshot else null,
                        selectedRef = if (selectedStillExists) phoneBrowserAgent.selectedRef else "",
                        status = if (sessions.isEmpty()) "ERA browser has no active page yet. Enter a URL or search phrase."
                        else "ERA in-app browser ready · " + sessions.size + " session(s)" +
                            if (includePrivateDetails) " · complete URL shared once" else " · query details excluded"
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        status = error.message ?: "Could not read the ERA browser session."
                    )
                }
        }
    }

    private fun selectPhoneBrowserSession(session: com.eraassistant.mobile.browser.PhoneBrowserSession) {
        runCatching { phoneBrowserAgentClient.selectSession(session.targetId) }
            .onFailure { error ->
                phoneBrowserAgent = phoneBrowserAgent.copy(status = error.message ?: "Could not select browser tab.")
                return
            }
        phoneBrowserAgent = phoneBrowserAgent.copy(
            selectedTargetId = session.targetId,
            sessions = phoneBrowserAgentClient.sessionsNow(),
            snapshot = null,
            selectedRef = "",
            addressDraft = session.url,
            status = "Selected ERA browser page: " + (session.title.ifBlank { session.url })
        )
    }

    private fun capturePhoneBrowserSnapshot(includePrivateDetails: Boolean = false) {
        val targetId = phoneBrowserAgent.selectedTargetId
        if (targetId.isBlank()) {
            phoneBrowserAgent = phoneBrowserAgent.copy(status = "Open a page in the ERA browser first.")
            return
        }
        lifecycleScope.launch {
            phoneBrowserAgent = phoneBrowserAgent.copy(busy = true, status = "Capturing semantic DOM and accessibility-style snapshot…")
            runCatching {
                phoneBrowserAgentClient.captureSnapshot(
                    targetId = targetId,
                    includePrivateDetails = includePrivateDetails,
                    confirmed = includePrivateDetails
                )
            }
                .onSuccess { snapshot ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        snapshot = snapshot,
                        selectedRef = "",
                        status = "Snapshot captured: " + snapshot.nodes.size + " semantic nodes" +
                            (if (snapshot.truncated || !snapshot.complete) " · limited or incomplete" else " · complete") +
                            (if (includePrivateDetails) " · private values/attributes shared once" else " · private details excluded")
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        snapshot = null,
                        selectedRef = "",
                        status = error.message ?: "Browser snapshot failed."
                    )
                }
        }
    }

    private fun runPhoneBrowserAction(action: String, confirmed: Boolean = false) {
        val snapshot = phoneBrowserAgent.snapshot
        val targetId = phoneBrowserAgent.selectedTargetId
        val ref = phoneBrowserAgent.selectedRef
        val node = snapshot?.nodes?.firstOrNull { it.ref == ref }
        if (snapshot == null || targetId.isBlank() || node == null) {
            phoneBrowserAgent = phoneBrowserAgent.copy(actionStatus = "Capture a fresh snapshot and select one semantic ref first.")
            return
        }
        val permitted = when (action.lowercase()) {
            "read" -> PhoneBrowserAgentPolicy.canRead(node)
            "click" -> PhoneBrowserAgentPolicy.canClick(node)
            "fill", "type" -> PhoneBrowserAgentPolicy.canFill(node)
            "press" -> PhoneBrowserAgentPolicy.canPress(node)
            "check", "uncheck" -> PhoneBrowserAgentPolicy.canCheck(node)
            "select" -> PhoneBrowserAgentPolicy.canSelect(node)
            "scroll" -> node.visible
            else -> false
        }
        if (!permitted) {
            phoneBrowserAgent = phoneBrowserAgent.copy(
                actionStatus = "Target is not currently visible/enabled or does not support this action."
            )
            return
        }
        if (PhoneBrowserAgentPolicy.requiresConfirmation(node, action) && !confirmed) {
            phoneBrowserAgent = phoneBrowserAgent.copy(
                actionStatus = "This action needs a one-time confirmation. Review the permission prompt first."
            )
            return
        }
        val includePrivateDetails = action.lowercase() == "read" && confirmed
        val textToFill = when (action.lowercase()) {
            "press" -> phoneBrowserAgent.keyName.trim().uppercase().ifBlank { "ENTER" }
            else -> phoneBrowserAgent.fillText
        }
        if (action.lowercase() in setOf("fill", "type", "select") && textToFill.isBlank()) {
            phoneBrowserAgent = phoneBrowserAgent.copy(actionStatus = "Enter the text or option value first.")
            return
        }
        lifecycleScope.launch {
            phoneBrowserAgent = phoneBrowserAgent.copy(busy = true, actionStatus = "Running the approved in-app browser action…")
            runCatching {
                phoneBrowserAgentClient.performAction(
                    targetId = targetId,
                    ref = ref,
                    action = action,
                    text = textToFill,
                    confirmed = confirmed,
                    includePrivateDetails = includePrivateDetails
                )
            }
                .onSuccess { result ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        snapshot = result.followUpSnapshot ?: phoneBrowserAgent.snapshot,
                        selectedRef = if (result.followUpSnapshot != null) "" else ref,
                        fillText = if (action.lowercase() in setOf("fill", "type", "select")) "" else phoneBrowserAgent.fillText,
                        actionStatus = result.status + ": " + result.note +
                            if (result.privateDetails.isNotBlank()) "\n" + result.privateDetails else ""
                    )
                }
                .onFailure { error ->
                    phoneBrowserAgent = phoneBrowserAgent.copy(
                        busy = false,
                        actionStatus = error.message ?: "Browser action failed."
                    )
                }
        }
    }

    private fun goBackPhoneBrowser() {
        phoneBrowserAgentClient.goBack()
        phoneBrowserAgent = phoneBrowserAgent.copy(snapshot = null, selectedRef = "", status = "Returning to previous page…")
    }

    private fun openExternalChromeTarget() {
        if (!accessibilityConsentAccepted) {
            status = "Accept the screen-inspection disclosure before using External Chrome."
            return
        }
        refreshPermissionStatuses()
        if (!accessibilityServiceEnabled) {
            status = "প্রথমে ERA Screen Inspector Android Accessibility Settings থেকে enable করতে হবে।"
            openAccessibilitySettings()
            return
        }
        val launch = ExternalChromeAgentPolicy.supportedPackages.firstNotNullOfOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let { packageName to it }
        }
        if (launch == null) {
            externalChromeStatus = "Chrome Stable, Beta or Dev is not installed or launchable on this device."
            status = externalChromeStatus
            return
        }
        val (packageName, intent) = launch
        if (::pendingUiActionStore.isInitialized) {
            pendingUiActionStore.clearPending("External Chrome target changed; previous UI approval cancelled.")
        }
        getSharedPreferences("era_ui_snapshot", MODE_PRIVATE).edit().clear().apply()
        uiSnapshot = ""
        uiSnapshotError = ""
        uiDiff = ""
        getSharedPreferences("era_preferences", MODE_PRIVATE).edit()
            .putBoolean("external_chrome_target_mode", true)
            .putString("external_chrome_target_package", packageName)
            .apply()
        externalChromeTargetMode = true
        externalChromeStatus = "External Chrome target active ($packageName). In Chrome, tap the ERA overlay once to capture its visible accessibility tree."
        status = externalChromeStatus
        runCatching { startActivity(intent) }.onFailure { error ->
            getSharedPreferences("era_preferences", MODE_PRIVATE).edit()
                .putBoolean("external_chrome_target_mode", false)
                .remove("external_chrome_target_package")
                .apply()
            externalChromeTargetMode = false
            externalChromeStatus = "Could not open Chrome: " + (error.message ?: "Android launch error")
            status = externalChromeStatus
        }
    }

    private fun disableExternalChromeTarget() {
        getSharedPreferences("era_preferences", MODE_PRIVATE).edit()
            .putBoolean("external_chrome_target_mode", false)
            .remove("external_chrome_target_package")
            .apply()
        if (pendingUiActionStore.isPending()) {
            pendingUiActionStore.clearPending("External Chrome target disabled; pending UI action cancelled.")
        }
        externalChromeTargetMode = false
        getSharedPreferences("era_ui_snapshot", MODE_PRIVATE).edit().clear().apply()
        uiSnapshot = ""
        uiSnapshotError = ""
        uiDiff = ""
        externalChromeStatus = "External Chrome target disabled. General screen inspector remains available."
        status = externalChromeStatus
    }

    private fun openAccessibilitySettings() {
        if (!accessibilityConsentAccepted) {
            status = "Screen-inspection consent is required before enabling the Accessibility service."
            return
        }
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { status = "Accessibility settings খুলতে পারিনি: " + (it.message ?: "Unknown error") }
    }

    private fun startCallContactFlow(rawCommand: String) {
        val query = extractContactSearchTerm(rawCommand)
        if (query.isBlank()) {
            pendingContactQueryForCall = false
            openContactPicker()
            return
        }
        pendingContactQueryForCall = true
        pendingCallConfirmation = null
        selectedContactForAlias = null
        contactSearchQuery = query
        contactMatches = emptyList()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            searchContactsNow()
        } else {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun extractContactSearchTerm(rawCommand: String): String {
        var value = rawCommand.trim()
        val markers = listOf(
            "please call ", "call ", "phone ", "dial ",
            "কল করে দাও", "কল করে", "কল কর", "কল দাও", "ফোন কর", "ফোন দাও",
            "কে কল", "কে ফোন"
        )
        markers.forEach { marker -> value = value.replace(marker, " ", ignoreCase = true) }
        value = value.replace(Regex("[,.!?।]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val prefixes = listOf("আমার ", "to ", "the ")
        prefixes.forEach { prefix ->
            if (value.startsWith(prefix, ignoreCase = true)) value = value.substring(prefix.length).trim()
        }
        if (value.endsWith("কে") && value.length > 2) value = value.dropLast(2).trim()
        return value.take(80)
    }

    private fun searchContacts() {
        if (contactSearchQuery.isBlank()) {
            status = "Search-এর জন্য contact name, alias বা phone-এর কয়েকটি digit লিখো."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            searchContactsNow()
        } else {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun searchContactsNow() {
        val query = contactSearchQuery.trim()
        if (query.isBlank()) return

        val generation = ++contactSearchGeneration
        contactSearchJob?.cancel()
        contactSearchJob = lifecycleScope.launch {
            val cached = cachedContactCandidates?.takeIf {
                System.currentTimeMillis() - contactCandidatesCachedAtEpochMs in 0..CONTACT_CACHE_TTL_MS
            }
            val candidateResult = if (cached != null) {
                Result.success(cached)
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { readContactCandidates() }
                }
            }

            // Ignore stale completions when the query changed or a newer search was requested.
            if (!isActive || generation != contactSearchGeneration || query != contactSearchQuery.trim()) {
                return@launch
            }
            if (candidateResult.isFailure) {
                val callWasRequested = pendingContactQueryForCall
                pendingContactQueryForCall = false
                status = "Contact search failed: " + (candidateResult.exceptionOrNull()?.message ?: "Unknown error")
                if (callWasRequested) openContactPicker()
                return@launch
            }

            val candidates = candidateResult.getOrDefault(emptyList())
            if (cached == null) {
                cachedContactCandidates = candidates
                contactCandidatesCachedAtEpochMs = System.currentTimeMillis()
            }
            when (val result = ContactMatchEngine.resolve(query, candidates)) {
                ContactMatchResult.NoMatch -> {
                    contactMatches = emptyList()
                    selectedContactForAlias = null
                    val callWasRequested = pendingContactQueryForCall
                    pendingContactQueryForCall = false
                    status = "No contact matched that name, alias or phone digits."
                    if (callWasRequested) openContactPicker()
                }
                is ContactMatchResult.Unique -> {
                    pendingContactQueryForCall = false
                    contactMatches = emptyList()
                    selectedContactForAlias = result.contact
                    createCallConfirmation(result.contact)
                    status = "Unique contact match (" + result.matchedBy + "). Review the selected number before opening the dialer."
                }
                is ContactMatchResult.Multiple -> {
                    pendingContactQueryForCall = false
                    contactMatches = result.contacts
                    selectedContactForAlias = null
                    status = "Multiple contacts matched (" + result.matchedBy + "). Choose the correct person; ERA will not guess."
                }
            }
        }
    }

    /** Must be called on Dispatchers.IO; the query result is bounded and grouped by contact ID. */
    private fun readContactCandidates(): List<ContactCandidate> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val grouped = linkedMapOf<String, Pair<String, MutableList<String>>>()
        val sortOrder = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && grouped.size < MAX_CONTACT_CANDIDATES) {
                val id = cursor.getString(idColumn).orEmpty()
                val name = cursor.getString(nameColumn).orEmpty()
                val number = cursor.getString(numberColumn).orEmpty()
                if (id.isBlank() || number.isBlank()) continue
                val row = grouped.getOrPut(id) { name to mutableListOf() }
                if (number !in row.second) row.second += number
            }
        }
        return grouped.map { entry ->
            ContactCandidate(
                entry.key,
                entry.value.first,
                entry.value.second,
                contactAliasStore.aliasesFor(entry.key)
            )
        }
    }

    private fun selectContactMatch(contact: ContactCandidate) {
        pendingContactQueryForCall = false
        selectedContactForAlias = contact
        contactMatches = emptyList()
        createCallConfirmation(contact)
        status = "Contact selected. Confirm opens only the Android dialer; no call is placed."
    }

    private fun createCallConfirmation(contact: ContactCandidate) {
        voiceApprovalEngine.clear()
        val pending = CallConfirmationPolicy.create(
            name = contact.displayName,
            number = contact.phoneNumbers.firstOrNull(),
            nowEpochMs = System.currentTimeMillis()
        )
        pendingCallConfirmation = pending
        if (pending == null) {
            status = "Selected contact has no valid phone number. No call was placed."
            return
        }
        voiceApprovalEngine.request(
            actionId = "phone.dialer.open",
            actionSummary = "Open the Android dialer",
            targetSummary = pending.contactName,
            binding = callApprovalBinding(pending),
            ttlMs = CallConfirmationPolicy.MAX_AGE_MS
        )
        status = "Dialer খোলার জন্য voice approval অপেক্ষা করছে। 'Approved' বা 'Cancel' বলো।"
        if (speechOutputEnabled) {
            voiceOutput.speak(
                "${pending.contactName}-এর জন্য Android dialer খুলব? অনুমোদন করতে Approved বলো, বাতিল করতে Cancel বলো।"
            )
        }
    }

    private fun saveContactAlias(contactId: String, alias: String) {
        val contact = selectedContactForAlias
        if (contact == null || contact.id != contactId) {
            status = "আগে একটি contact select করো, তারপর alias save করো।"
            return
        }
        if (contactAliasStore.addAlias(contact.id, alias)) {
            val updatedAliases = (contact.aliases + alias.trim().take(MAX_CONTACT_ALIAS_LENGTH))
                .distinctBy { it.lowercase() }
                .toSet()
            selectedContactForAlias = contact.copy(aliases = updatedAliases)
            cachedContactCandidates = cachedContactCandidates?.map { cached ->
                if (cached.id == contact.id) cached.copy(aliases = updatedAliases) else cached
            }
            status = "Local contact alias saved. It stays on this device."
        } else {
            status = "Alias save করা যায়নি। একটি ছোট, non-empty alias দাও।"
        }
    }

    private fun pickInstagramMedia() {
        instagramMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    private fun prepareInstagramDraft() {
        val uri = instagramMediaUri
        val validation = InstagramPreparationPolicy.validate(instagramCaption, instagramHashtags, uri?.toString())
        if (!validation.valid) {
            status = validation.message
            return
        }
        val mimeType = uri?.let { contentResolver.getType(it) } ?: "image/*"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            setPackage("com.instagram.android")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, validation.shareText)
            clipData = ClipData.newUri(contentResolver, "ERA Instagram draft media", uri!!)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (shareIntent.resolveActivity(packageManager) == null) {
            status = "Instagram share target is unavailable. The draft stays in ERA; install/open Instagram and select the same media manually."
            return
        }
        runCatching { startActivity(shareIntent) }
            .onSuccess {
                status = validation.message
                recordCommand("Instagram preparation", "Draft handed to Instagram for final review. No post was published.")
            }
            .onFailure { status = "Instagram draft handoff failed: " + (it.message ?: "Unknown error") }
    }

    private fun shareVisualFrameForProductSearch() {
        if (!accessibilityConsentAccepted || visualFrame == null || VisualFrameStore.current() == null) {
            status = "Product image search needs an explicit screen-inspection consent and a fresh manual visual capture."
            return
        }
        val uri = VisualFrameContentProvider.currentFrameUri(packageName)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "ERA user-captured product image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(shareIntent, "Choose visual search app (for example, Google Lens)")) }
            .onSuccess {
                status = "One captured frame was shared only after you choose a receiving app. ERA does not upload or save the image."
                recordCommand("Visual product search", "User-selected external visual-search handoff opened.")
            }
            .onFailure { status = "Could not open visual search chooser: " + (it.message ?: "Unknown error") }
    }

    private fun identifyVisualProduct() {
        if (!accessibilityConsentAccepted || visualFrame == null) {
            productRecognitionStatus = "Capture one frame manually and keep screen-inspection consent enabled first."
            return
        }
        val configuration = aiProviderConfigurationStore.load()
        if (configuration.provider == AIProviderKind.LOCAL_ONLY) {
            productRecognitionStatus = "Select Gemini or an OpenAI-compatible vision model, save its key, then try again."
            return
        }
        val key = aiProviderConfigurationStore.apiKey()
        if (!configuration.isRemoteConfigured(!key.isNullOrBlank()) || key.isNullOrBlank()) {
            productRecognitionStatus = "The selected AI provider needs a valid HTTPS endpoint, model ID and saved API key."
            return
        }
        val frameCopy = VisualFrameStore.copyCurrentBitmap()
        if (frameCopy == null) {
            productRecognitionStatus = "The captured visual frame is no longer available. Capture it again."
            return
        }
        lifecycleScope.launch {
            productRecognitionRunning = true
            productRecognitionStatus = "Sending the selected frame to the configured AI provider for one-time product recognition…"
            try {
                val candidate = VisualProductRecognitionProvider.identify(frameCopy, configuration, key)
                productSearchQuery = candidate.shoppingQuery
                val confidenceText = (candidate.confidence * 100).toInt()
                productRecognitionStatus = "Candidate: " + candidate.shoppingQuery + " · model confidence " +
                    confidenceText + "% · verify brand/model before buying. " + candidate.visualNotes
                recordCommand("Visual product recognition", "One-time AI candidate prepared for manual Shopping search.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                productRecognitionStatus = error.message ?: "Visual product recognition failed."
            } finally {
                runCatching { frameCopy.recycle() }
                productRecognitionRunning = false
            }
        }
    }

    private fun pickStorageFolder() {
        storageTreePicker.launch(safStorageManager.selectedTreeUri())
    }

    private fun refreshStorageListing(parentDocumentId: String?) {
        if (!safStorageManager.hasReadAccess()) {
            storageStatus = "Choose a folder first. No broad storage permission is requested."
            return
        }
        lifecycleScope.launch {
            storageStatus = "Reading the selected folder…"
            val result = withContext(Dispatchers.IO) {
                runCatching { safStorageManager.listChildren(parentDocumentId, 100) }
            }
            if (result.isSuccess) {
                storageEntries = result.getOrDefault(emptyList())
                storageCurrentParentId = parentDocumentId
                storageFolderName = withContext(Dispatchers.IO) {
                    runCatching { safStorageManager.rootName() }.getOrNull()
                }.orEmpty().ifBlank { "Selected folder" }
                storagePreview = null
                storageStatus = "Showing ${storageEntries.size} item(s). Only names and supported metadata are listed."
            } else {
                storageStatus = result.exceptionOrNull()?.message ?: "Folder listing failed."
                if (!safStorageManager.hasReadAccess()) storageEntries = emptyList()
            }
        }
    }

    private fun openStorageDocument(document: StorageDocument) {
        if (document.isDirectory) {
            refreshStorageListing(document.documentId)
            return
        }
        lifecycleScope.launch {
            storageStatus = "Preparing a bounded, local text preview…"
            val result = withContext(Dispatchers.IO) {
                runCatching { safStorageManager.readText(document.documentId) }
            }
            result.onSuccess { preview ->
                storagePreview = preview
                storageStatus = if (preview.truncated) {
                    "Preview truncated to ${preview.text.length} characters. Source file was not changed."
                } else {
                    "Read-only preview ready. Source file was not changed."
                }
            }.onFailure { error ->
                storagePreview = null
                storageStatus = error.message ?: "The selected file could not be previewed."
            }
        }
    }

    private fun clearStorageFolder() {
        runCatching { safStorageManager.clearSelectedTree() }
        storageFolderName = ""
        storageEntries = emptyList()
        storagePreview = null
        storageCurrentParentId = null
        storageStatus = "Folder access removed. ERA can no longer read that folder."
    }

    private fun searchShoppingPrices() {
        val url = ProductSearchQuery.shoppingUrl(productSearchQuery)
        if (url == null) {
            status = "Product name লিখে তারপর price search করো।"
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onSuccess {
                status = "Shopping price search opened externally. Compare seller, delivery and product variant before choosing."
                recordCommand("Shopping price search", "Opened a user-requested external shopping search.")
            }
            .onFailure { status = "Shopping search could not be opened: " + (it.message ?: "Unknown error") }
    }

    private fun refreshTaskMonitor(coordinator: TaskExecutionCoordinator) {
        taskSteps = coordinator.ledger.all()
        val base = TaskExecutionMonitor.snapshot(
            plan = coordinator.plan,
            records = taskSteps,
            stateMachine = coordinator.stateMachine,
            activeCapability = coordinator.activeCapabilityId?.value
        ).toDisplayText()
        val proposal = coordinator.suggestedReplanPlan
        taskMonitorText = if (proposal == null) {
            base
        } else {
            buildString {
                append(base)
                appendLine()
                appendLine()
                appendLine("REPLAN PROPOSAL — MANUAL REVIEW ONLY")
                appendLine("Goal: ${proposal.goal.take(240)}")
                appendLine("Risk: ${proposal.overallRisk}")
                proposal.steps.take(8).forEachIndexed { index, step ->
                    appendLine("${index + 1}. ${step.title.take(120)} — ${step.description.take(220)}")
                }
                appendLine("This proposal is not applied or executed automatically. Review the plan and target app before any new approval.")
            }
        }
    }

    private fun advanceTaskStep(stepId: String, action: String) {
        val coordinator = activeTaskCoordinator ?: run {
            status = "No active task plan. Preview a task first."
            return
        }
        val result = when (action) {
            "start" -> coordinator.start(stepId)
            "review" -> coordinator.readyForReview(stepId, "User reviewed the step instructions and target.")
            "approve" -> coordinator.approve(stepId)
            "reported" -> coordinator.reportUserPerformed(stepId)
            "replan" -> coordinator.ledger.transition(
                stepId,
                com.eraassistant.mobile.tasks.TaskStepState.NEEDS_REPLAN,
                "User requested a fresh review of this step."
            )
            else -> null
        }
        when (result) {
            is TransitionResult.Updated -> status = if (action == "approve") {
                "Task step $stepId marked approved for plan review only. No adapter action was dispatched; a typed action request and exact fresh-observation approval are still required."
            } else {
                "Task step $stepId updated: " + result.record.state + ". No action was run by the monitor."
            }
            is TransitionResult.Rejected -> status = "Task step " + stepId + " was not updated: " + result.reason
            null -> status = "Unknown task monitor action."
        }
        refreshTaskMonitor(coordinator)
    }

    private fun verifyTaskStep(stepId: String, evidence: String) {
        val cleanEvidence = evidence.trim().take(500)
        if (cleanEvidence.isBlank()) {
            status = "নিজে পরীক্ষা করা ফলাফল লিখে outcome confirm করো।"
            return
        }
        val coordinator = activeTaskCoordinator ?: return
        val current = coordinator.ledger.get(stepId)
        val result = if (current?.state == com.eraassistant.mobile.tasks.TaskStepState.RECOVERY_REQUIRED) {
            coordinator.confirmRecoveryInspection(stepId, cleanEvidence)
        } else {
            coordinator.confirmUserOutcome(stepId, cleanEvidence)
        }
        when (result) {
            is TransitionResult.Updated -> status = if (current?.state == com.eraassistant.mobile.tasks.TaskStepState.RECOVERY_REQUIRED) {
                "Recovery inspection recorded. The step is back in review; take a fresh observation before approving another attempt."
            } else {
                "Task step " + stepId + " marked user-confirmed. ERA did not independently verify the target app."
            }
            is TransitionResult.Rejected -> status = "Task outcome confirmation rejected: " + result.reason
        }
        refreshTaskMonitor(coordinator)
    }

    private fun cancelActiveTask() {
        val coordinator = activeTaskCoordinator ?: return
        coordinator.cancel()
        refreshTaskMonitor(coordinator)
        status = "Pending task steps cancelled. User-confirmed records were retained."
    }


    private fun latestAccessibilitySnapshot(): UiScreenSnapshot? {
        if (!accessibilityConsentAccepted) return null
        val raw = getSharedPreferences("era_ui_snapshot", MODE_PRIVATE)
            .getString("latest_json", null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching { UiTreeJson.decode(raw) }.getOrNull()
    }

    private fun reloadUiSnapshot() {
        val targetPrefs = getSharedPreferences("era_preferences", MODE_PRIVATE)
        externalChromeTargetMode = targetPrefs.getBoolean("external_chrome_target_mode", false)
        val chromePrefs = getSharedPreferences(ExternalChromeAgentPolicy.PREFS_NAME, MODE_PRIVATE)
        externalChromeStatus = chromePrefs.getString(
            ExternalChromeAgentPolicy.KEY_STATUS,
            if (externalChromeTargetMode) "External Chrome target active. Switch to Chrome and tap the ERA overlay."
            else "External Chrome target is inactive."
        ).orEmpty()
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
        if (handleVoiceApprovalCommand(text)) return
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
            AssistantCommand.PickContactForDial -> startCallContactFlow(text)
            AssistantCommand.CancelCurrentTask -> {
                sequenceJob?.cancel()
                sequenceJob = null
                activeTaskCoordinator?.cancel()
                voiceApprovalEngine.clear()
                stopVoiceSession()
                pendingUiActionStore.clearPending("Pending UI action cancelled by user.")
                uiActionStatus = pendingUiActionStore.result()
                status = "চলমান command sequence, task execution এবং pending UI approval বন্ধ করা হয়েছে।"
                recordCommand(text, status)
            }
            else -> {
                lifecycleScope.launch {
                    status = executeCommandViaCoordinator(text, command)
                    recordCommand(text, status)
                }
            }
        }
    }

    /**
     * Every supported instant command is compiled to a typed capability request and
     * dispatched through the durable coordinator. Unsupported commands fail closed,
     * except for the two read-only summaries that retain their existing local handlers.
     */
    private suspend fun executeCommandViaCoordinator(
        originalText: String,
        command: AssistantCommand
    ): String {
        val compiled = ActionIntentCompiler.compile(command) ?: return when (command) {
            AssistantCommand.ReadBattery, AssistantCommand.ReadDateTime, AssistantCommand.Help -> runCatching {
                deviceController.execute(command)
            }.getOrElse { "Read-only command failed: ${it.message ?: "Unknown error"}" }
            else -> "এই command-এর জন্য verified typed capability mapping নেই। নিরাপত্তার জন্য কোনো action execute করা হয়নি।"
        }

        if (!executionJournal.isAvailable()) {
            return "Execution journal unavailable. No device action was dispatched."
        }

        return try {
            val coordinator = TaskExecutionCoordinator.from(
                plan = compiled.plan,
                persistence = SharedPreferencesTaskLedgerPersistence(this),
                capabilityRegistry = capabilityRegistry,
                verificationEngine = verificationEngine,
                executionJournal = executionJournal,
                statePersistence = taskStatePersistence
            )
            activeTaskCoordinator = coordinator

            val started = coordinator.start(compiled.stepId)
            if (started !is TransitionResult.Updated) {
                return "Action was not started: ${(started as? TransitionResult.Rejected)?.reason ?: "invalid task state"}"
            }
            val reviewed = coordinator.readyForReview(
                compiled.stepId,
                "Direct user command locally compiled as ${compiled.request.capabilityId.value}/${compiled.request.actionName}."
            )
            if (reviewed !is TransitionResult.Updated) {
                return "Action did not reach review state: ${(reviewed as? TransitionResult.Rejected)?.reason ?: "invalid task state"}"
            }
            val approved = coordinator.approve(compiled.stepId)
            if (approved !is TransitionResult.Updated) {
                return "Action was not approved for dispatch: ${(approved as? TransitionResult.Rejected)?.reason ?: "invalid task state"}"
            }

            refreshTaskMonitor(coordinator)
            val result = coordinator.executeStep(
                stepId = compiled.stepId,
                request = compiled.request
            )
            refreshTaskMonitor(coordinator)
            val adapterMessage = result.outputData["message"]?.toString()
                ?: result.error?.message
                ?: result.status.name
            val state = coordinator.ledger.get(compiled.stepId)?.state
            when {
                result.isFailure -> "Action failed safely: $adapterMessage"
                state == com.eraassistant.mobile.tasks.TaskStepState.WAITING_USER ->
                    "$adapterMessage Independent outcome verification is unavailable; inspect the target and confirm it in Task Monitor."
                state == com.eraassistant.mobile.tasks.TaskStepState.MACHINE_VERIFIED ->
                    "$adapterMessage ERA independently verified the configured acceptance criteria."
                else -> "$adapterMessage Task state: ${state ?: "unknown"}. Check Task Monitor before relying on the outcome."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            "Permission denied; no further action was attempted: ${error.message ?: "Android denied the request."}"
        } catch (error: Exception) {
            "Structured action was not completed: ${error.message ?: error::class.simpleName ?: "Unknown error"}"
        }
    }

    private fun confirmPendingCall() {
        val pending = pendingCallConfirmation ?: return
        voiceApprovalEngine.clear()
        if (!CallConfirmationPolicy.canConfirm(pending, System.currentTimeMillis())) {
            pendingCallConfirmation = null
            status = "Call confirmation expired. Select the contact again."
            return
        }
        pendingCallConfirmation = null
        val commandLabel = "Call confirmation: ${pending.contactName} (${pending.displayNumber})"
        lifecycleScope.launch {
            status = executeCommandViaCoordinator(
                commandLabel,
                AssistantCommand.DialNumber(pending.dialNumber)
            ) + " ERA did not place the call."
            recordCommand(commandLabel, status)
        }
    }

    private fun cancelPendingCall() {
        voiceApprovalEngine.clear()
        pendingCallConfirmation = null
        status = "Call request cancelled. No call was placed."
        recordCommand("Call request cancelled", status)
    }

    private fun handleVoiceApprovalCommand(text: String): Boolean {
        val prompt = voiceApprovalEngine.activePrompt()
        if (prompt == null) {
            if (VoiceApprovalEngine.isApprovalOrCancellationPhrase(text)) {
                status = "কোনো action voice approval-এর জন্য অপেক্ষা করছে না। কিছু execute করা হয়নি।"
                recordCommand(text, status)
                return true
            }
            return false
        }

        val pending = pendingCallConfirmation
        val binding = pending?.let(::callApprovalBinding)
        return when (val decision = voiceApprovalEngine.consumeSpokenCommand(text, binding)) {
            is VoiceApprovalResult.Approved -> {
                val confirmed = pendingCallConfirmation
                if (confirmed == null || !CallConfirmationPolicy.canConfirm(confirmed, System.currentTimeMillis())) {
                    pendingCallConfirmation = null
                    status = "Voice approval পাওয়া গেছে, কিন্তু call request আর valid নেই। কোনো dialer খোলা হয়নি।"
                    recordCommand("Expired voice approval", status)
                } else {
                    pendingCallConfirmation = null
                    val commandLabel = "Voice approval: open dialer for ${confirmed.contactName}"
                    lifecycleScope.launch {
                        status = executeCommandViaCoordinator(
                            commandLabel,
                            AssistantCommand.DialNumber(confirmed.dialNumber)
                        ) + " Voice approval received; ERA did not place the call."
                        recordCommand(commandLabel, status)
                    }
                }
                true
            }
            is VoiceApprovalResult.Denied -> {
                pendingCallConfirmation = null
                status = "Voice approval বাতিল হয়েছে। Dialer খোলা হয়নি।"
                recordCommand("Voice approval denied: ${decision.requestId}", status)
                true
            }
            is VoiceApprovalResult.Expired -> {
                pendingCallConfirmation = null
                status = "Voice approval-এর সময় শেষ। নতুন করে request তৈরি করো।"
                recordCommand("Voice approval expired: ${decision.requestId}", status)
                true
            }
            is VoiceApprovalResult.BindingChanged -> {
                pendingCallConfirmation = null
                status = "Approved action-এর target বদলে গেছে। নিরাপত্তার জন্য action বাতিল করা হয়েছে।"
                recordCommand("Voice approval binding changed: ${decision.requestId}", status)
                true
            }
            is VoiceApprovalResult.Unrecognized -> {
                pendingCallConfirmation = null
                status = "অন্য command শোনা হয়েছে; আগের voice approval বাতিল। নতুন command process করা হচ্ছে।"
                history = historyStore.add("Pending voice approval cancelled", status)
                false
            }
            VoiceApprovalResult.NoPending -> false
        }
    }

    private fun callApprovalBinding(pending: PendingCallConfirmation): String =
        listOf(
            pending.contactName,
            pending.dialNumber,
            pending.createdAtEpochMs.toString()
        ).joinToString("\u001f")

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
                val result = runCatching { executeCommandViaCoordinator(original, command) }
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
            "ব্যর্থ", "ইনস্টল করা নেই", "একাধিক app", "not installed", "not found", "permission denied", "failed", "unknown error",
            "no action was dispatched", "no action was executed", "verification is unavailable", "unsupported", "fail closed"
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
            val plannerEndpointReady = when (providerConfig.provider) {
                AIProviderKind.LOCAL_ONLY -> false
                AIProviderKind.OLLAMA_LOCAL -> providerConfig.isLocalModelConfigured()
                AIProviderKind.GEMINI, AIProviderKind.OPENAI_COMPATIBLE ->
                    providerConfig.remoteFallbackEnabled &&
                        providerConfig.isRemoteConfigured(aiProviderConfigurationStore.hasApiKey())
            }
            val plannerSource = if (providerConfig.provider == AIProviderKind.OLLAMA_LOCAL) {
                PlannerSource.ON_DEVICE
            } else {
                PlannerSource.REMOTE
            }
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
                    val plannerRequest = PlannerRequest(
                        goal = goal,
                        capabilities = availableCapabilities.map { it.name },
                        uiContext = null
                    )
                    val response = when (providerConfig.provider) {
                        AIProviderKind.OLLAMA_LOCAL ->
                            LocalOllamaPlannerProvider(providerConfig).plan(plannerRequest)
                        AIProviderKind.GEMINI, AIProviderKind.OPENAI_COMPATIBLE -> {
                            val key = aiProviderConfigurationStore.apiKey()
                                ?: error("Remote provider key is not configured.")
                            RemoteAIPlannerProvider(providerConfig, key).plan(plannerRequest)
                        }
                        AIProviderKind.LOCAL_ONLY -> error("No model endpoint is configured.")
                    }
                    PlannerResponseAdapter.toTaskPlan(goal, response)
                },
                configuredPlannerSource = plannerSource
            )
            val routingResult = runCatching {
                routedPlanner.plan(request, remoteEnabled = plannerEndpointReady)
            }
            val routed = routingResult.getOrNull()
            val plan = routed?.value ?: offlineOperationManager.planWithFallback(request)
            routingResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val routingNote = routed?.note.orEmpty().ifBlank {
                routingResult.exceptionOrNull()?.let {
                    "Configured planner endpoint unavailable or rejected the request; the validated local plan is retained: ${it.message.orEmpty().take(160)}"
                }.orEmpty()
            }
            val dag = runCatching { TaskPlanDAG(plan) }.getOrNull()
            val dagErrors = dag?.validateCapabilitySupport(capabilityRegistry)
                ?: listOf("TaskPlan DAG could not be constructed.")
            val availabilityErrors = if (dag != null && dagErrors.isEmpty()) {
                dag.validateCapabilityAvailability(capabilityRegistry)
            } else emptyList()
            val matchedSkill = skillEngine.findSkillForGoal(request)
            val learnedWorkflow = workflowLearningEngine.findVerifiedWorkflow(request)
            val feedbackLoop = PlannerFeedbackLoop(localPlanner, capabilityRegistry, maxReplans = 3)
            val offlineDescriptors = offlineOperationManager.offlineCapableDescriptors()
            val displayPlan = if (routingNote.isBlank()) plan else plan.copy(note = "${plan.note}\n$routingNote")
            val taskCoordinator = runCatching {
                TaskExecutionCoordinator.from(
                    plan = plan,
                    persistence = SharedPreferencesTaskLedgerPersistence(this@MainActivity),
                    capabilityRegistry = capabilityRegistry,
                    verificationEngine = verificationEngine,
                    executionJournal = executionJournal,
                    statePersistence = taskStatePersistence,
                    feedbackLoop = feedbackLoop,
                    onTaskVerified = { verifiedMachine ->
                        workflowLearningEngine.learnFromTask(verifiedMachine)
                    }
                )
            }.getOrNull()
            activeTaskCoordinator = taskCoordinator
            if (taskCoordinator != null) refreshTaskMonitor(taskCoordinator)
            else {
                taskSteps = emptyList()
                taskMonitorText = "Status: FAILED — task ledger could not be initialized."
            }
            planPreview = buildString {
                appendLine("Goal: ${plan.goal}")
                appendLine("Planner source: ${routed?.source ?: PlannerSource.LOCAL_FALLBACK}")
                appendLine("Plan version: ${plan.planVersion} | Risk: ${plan.overallRisk} | Estimated actions: ${plan.estimatedActions}")
                val validationErrors = plan.validate() + dagErrors + availabilityErrors
                appendLine("Plan structural/DAG validation: ${if (plan.validate().isEmpty() && dag != null) "PASS" else "FAILED"}")
                appendLine("Registered capability/action validation: ${if (dagErrors.isEmpty()) "PASS" else "BLOCKED"}")
                dagErrors.take(8).forEach { appendLine("  BLOCKED: $it") }
                appendLine("Live capability availability: ${if (availabilityErrors.isEmpty()) "PASS" else "BLOCKED"}")
                availabilityErrors.take(8).forEach { appendLine("  UNAVAILABLE: $it") }
                appendLine("Execution readiness: ${if (validationErrors.isEmpty() && taskCoordinator != null) "READY FOR REVIEW" else "MANUAL REVIEW REQUIRED"}")
                appendLine("Initial ready steps: ${taskCoordinator?.readySteps()?.joinToString { it.step.id } ?: "none"}")
                appendLine("Discovered task capabilities: ${availableCapabilities.joinToString().ifBlank { "none" }}")
                appendLine("Offline-capable adapters: ${offlineDescriptors.joinToString { it.name }.ifBlank { "none" }}")
                appendLine("Network status: ${if (offlineOperationManager.isNetworkAvailable()) "validated online" else "offline or unvalidated"}")
                matchedSkill?.let { appendLine("Skill template match: ${it.name} — review/template only; never auto-executed.") }
                learnedWorkflow?.let { appendLine("Previously verified workflow hint: ${it.workflowId} (${it.verifiedCount} verified run(s)); suggestion only, not replayed.") }
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
        remoteStatusRefreshJob?.cancel()
        sequenceJob?.cancel()
        if (::foregroundVoiceSession.isInitialized) foregroundVoiceSession.stop()
        if (::voiceOutput.isInitialized) voiceOutput.release()
        if (::phoneBrowserAgentClient.isInitialized) phoneBrowserAgentClient.destroy()
        if (::shizukuBridgeManager.isInitialized) shizukuBridgeManager.close()
        super.onDestroy()
    }
}
