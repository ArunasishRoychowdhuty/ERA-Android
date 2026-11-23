package com.eraassistant.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.eraassistant.mobile.provider.AIProviderKind
import com.eraassistant.mobile.browser.PhoneBrowserAgentUiState
import com.eraassistant.mobile.browser.PhoneBrowserAgentPolicy
import com.eraassistant.mobile.search.VisualScreenAnalysis
import com.eraassistant.mobile.search.VisualControlCandidate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eraassistant.mobile.preferences.CustomCommandAlias
import com.eraassistant.mobile.contacts.PendingCallConfirmation
import com.eraassistant.mobile.contacts.ContactCandidate
import com.eraassistant.mobile.tasks.TaskStepRecord
import com.eraassistant.mobile.tasks.TaskStepState
import com.eraassistant.mobile.screen.VisualFrame
import com.eraassistant.mobile.shizuku.ShizukuBridgeState
import com.eraassistant.mobile.storage.StorageDocument
import com.eraassistant.mobile.storage.StorageTextPreview


private val EraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

private val EraDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB9A5FF),
    onPrimary = Color(0xFF21163F),
    primaryContainer = Color(0xFF30244F),
    onPrimaryContainer = Color(0xFFEDE6FF),
    secondary = Color(0xFF78E0C8),
    onSecondary = Color(0xFF082F29),
    background = Color(0xFF0A1020),
    onBackground = Color(0xFFEAF0FC),
    surface = Color(0xFF141D30),
    onSurface = Color(0xFFEAF0FC),
    surfaceVariant = Color(0xFF202C43),
    onSurfaceVariant = Color(0xFFB8C5DA),
    outline = Color(0xFF465571),
    error = Color(0xFFFF8B9A)
)

@Composable
private fun EraTopBar() {
    Surface(
        color = Color.Transparent,
        shadowElevation = 5.dp,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF18243C), Color(0xFF202B49), Color(0xFF18243C))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Text(
                    "E",
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text("ERA", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("Your local-first assistant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill("v0.9.4", true)
        }
    }
}

@Composable
private fun EraPageHeading(selectedTab: Int) {
    val heading = when (selectedTab) {
        1 -> Triple("TOOLS & WORKFLOWS", "Agents", "Browser, product search, files and reusable voice workflows.")
        2 -> Triple("SCREEN INTELLIGENCE", "Screen inspector", "Inspect visible UI and review every proposed interaction.")
        3 -> Triple("DEVICE CONFIGURATION", "Settings", "Permissions, connectivity, AI provider and voice preferences.")
        4 -> Triple("PERSONAL PRODUCTIVITY", "Personal", "Reminders, Calendar and encrypted memory controls.")
        else -> Triple("COMMAND CENTER", "What would you like ERA to do?", "Fast local commands with user-approved actions.")
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                heading.first,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                heading.second,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                heading.third,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EraBottomNavigation(selectedTab: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("⌂", "Home", "Home"),
        Triple("✧", "Agents", "Agents"),
        Triple("▣", "Screen", "Screen"),
        Triple("⚙", "Settings", "Settings"),
        Triple("◷", "Personal", "Personal")
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 14.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = selectedTab == index
                val tileColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "nav-tile-color-$index"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(220),
                    label = "nav-icon-color-$index"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelect(index) }
                        .background(tileColor)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        item.first,
                        style = MaterialTheme.typography.titleLarge,
                        color = iconColor,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        item.second,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun ERAScreen(
    transcript: String,
    status: String,
    history: List<String>,
    aliases: List<CustomCommandAlias>,
    planPreview: String,
    taskMonitorText: String,
    aiProviderKind: AIProviderKind,
    aiProviderModel: String,
    aiProviderEndpoint: String,
    aiProviderKeyDraft: String,
    aiProviderKeyConfigured: Boolean,
    aiProviderFallbackEnabled: Boolean,
    aiProviderConnectionStatus: String,
    aiProviderTestRunning: Boolean,
    onAIProviderKindChange: (AIProviderKind) -> Unit,
    onAIProviderModelChange: (String) -> Unit,
    onAIProviderEndpointChange: (String) -> Unit,
    onAIProviderKeyChange: (String) -> Unit,
    onAIProviderFallbackChange: (Boolean) -> Unit,
    onSaveAIProviderSettings: () -> Unit,
    onTestAIProviderConnection: () -> Unit,
    shizukuBridgeState: ShizukuBridgeState,
    onConnectShizukuBridge: () -> Unit,
    onRefreshShizukuBridge: () -> Unit,
    onReadShizukuDiagnostic: (String) -> Unit,
    remoteGatewayEndpoint: String,
    remotePairingCode: String,
    remoteGatewayStatus: String,
    remoteGatewayPaired: Boolean,
    remoteGatewaySessionActive: Boolean,
    remoteGatewayPairingBusy: Boolean,
    onRemoteGatewayEndpointChange: (String) -> Unit,
    onRemotePairingCodeChange: (String) -> Unit,
    onPairRemoteGateway: () -> Unit,
    onStartRemoteSession: () -> Unit,
    onStopRemoteSession: () -> Unit,
    onUnpairRemoteGateway: () -> Unit,
    phoneBrowserAgent: PhoneBrowserAgentUiState,
    phoneBrowserWebView: android.webkit.WebView,
    onPhoneBrowserAddressChange: (String) -> Unit,
    onNavigatePhoneBrowser: () -> Unit,
    onGoBackPhoneBrowser: () -> Unit,
    onGoForwardPhoneBrowser: () -> Unit,
    onReloadPhoneBrowser: () -> Unit,
    onNewPhoneBrowserTab: () -> Unit,
    onClosePhoneBrowserTab: () -> Unit,
    onTestPhoneBrowserAgent: () -> Unit,
    onRefreshPhoneBrowserSessions: (Boolean) -> Unit,
    onSelectPhoneBrowserSession: (com.eraassistant.mobile.browser.PhoneBrowserSession) -> Unit,
    onCapturePhoneBrowserSnapshot: (Boolean) -> Unit,
    onPhoneBrowserRefChange: (String) -> Unit,
    onPhoneBrowserFillTextChange: (String) -> Unit,
    onPhoneBrowserKeyChange: (String) -> Unit,
    onRunPhoneBrowserAction: (String, Boolean) -> Unit,
    onRequestBrowserAutofill: () -> Unit,
    speechOutputEnabled: Boolean,
    speechLanguage: String,
    autoObservationEnabled: Boolean,
    accessibilityConsentAccepted: Boolean,
    microphonePermissionGranted: Boolean,
    cameraPermissionGranted: Boolean,
    accessibilityServiceEnabled: Boolean,
    externalChromeTargetMode: Boolean,
    externalChromeStatus: String,
    onOpenExternalChrome: () -> Unit,
    onDisableExternalChrome: () -> Unit,
    voiceSessionActive: Boolean,
    uiActionStatus: String,
    pendingCallConfirmation: PendingCallConfirmation?,
    onConfirmCall: () -> Unit,
    onCancelCall: () -> Unit,
    visualFrame: VisualFrame?,
    visualCaptureStatus: String,
    onClearVisualFrame: () -> Unit,
    visualScreenAnalysis: VisualScreenAnalysis?,
    visualScreenAnalysisStatus: String,
    visualScreenAnalysisRunning: Boolean,
    onAnalyzeVisualScreen: () -> Unit,
    onDispatchVisualTarget: (VisualControlCandidate) -> Unit,
    onVoiceSessionChange: (Boolean) -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRefreshPermissionStatuses: () -> Unit,
    onAutoObservationChange: (Boolean) -> Unit,
    onAccessibilityConsentChange: (Boolean) -> Unit,
    onApproveUiClick: (String, String) -> Unit,
    onApproveUiType: (String, String, String) -> Unit,
    onApproveUiScroll: (String, String, Boolean) -> Unit,
    onCancelUiAction: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onRunTextCommand: () -> Unit,
    onPreviewPlan: () -> Unit,
    taskSteps: List<TaskStepRecord>,
    onAdvanceTaskStep: (String, String) -> Unit,
    onVerifyTaskStep: (String, String) -> Unit,
    onCancelTaskMonitor: () -> Unit,
    contactSearchQuery: String,
    contactMatches: List<ContactCandidate>,
    selectedContactForAlias: ContactCandidate?,
    onContactSearchQueryChange: (String) -> Unit,
    onSearchContacts: () -> Unit,
    onSelectContactMatch: (ContactCandidate) -> Unit,
    onSaveContactAlias: (String, String) -> Unit,
    instagramMediaLabel: String,
    instagramCaption: String,
    instagramHashtags: String,
    onPickInstagramMedia: () -> Unit,
    onInstagramCaptionChange: (String) -> Unit,
    onInstagramHashtagsChange: (String) -> Unit,
    onPrepareInstagramDraft: () -> Unit,
    productSearchQuery: String,
    onProductSearchQueryChange: (String) -> Unit,
    onShareVisualFrameForProductSearch: () -> Unit,
    onSearchShoppingPrices: () -> Unit,
    productRecognitionStatus: String,
    productRecognitionRunning: Boolean,
    onIdentifyVisualProduct: () -> Unit,
    storageFolderName: String,
    storageStatus: String,
    storageEntries: List<StorageDocument>,
    storagePreview: StorageTextPreview?,
    onPickStorageFolder: () -> Unit,
    onRefreshStorage: () -> Unit,
    onShowStorageRoot: () -> Unit,
    onOpenStorageDocument: (StorageDocument) -> Unit,
    onClearStorageFolder: () -> Unit,
    onQuickCommand: (String) -> Unit,
    onSpeechOutputChange: (Boolean) -> Unit,
    onSpeechLanguageChange: (String) -> Unit,
    onAddAlias: (String, String) -> Unit,
    onDeleteAlias: (String) -> Unit,
    onClearHistory: () -> Unit,
    uiSnapshot: String,
    uiSnapshotError: String,
    uiDiff: String,
    onOpenAccessibilitySettings: () -> Unit,
    onReloadUiSnapshot: () -> Unit,
    onCopyUiSnapshot: () -> Unit,
    onCopyAiContext: () -> Unit,
    onClearUiSnapshot: () -> Unit,
    reminders: List<com.eraassistant.mobile.productivity.ReminderUiItem>,
    managedMemories: List<com.eraassistant.mobile.productivity.ManagedMemoryUiItem>,
    agentMemoryRecords: List<com.eraassistant.mobile.memory.MemoryRecord>,
    notificationPermissionGranted: Boolean,
    exactAlarmAllowed: Boolean,
    calendarPermissionsGranted: Boolean,
    productivityStatus: String,
    onCreateReminder: (String, Long) -> Unit,
    onDeleteReminder: (String) -> Unit,
    onSyncReminderToCalendar: (String) -> Unit,
    onCreateMemory: (String, String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearManagedMemory: () -> Unit,
    onDeleteAgentMemory: (com.eraassistant.mobile.memory.MemoryCategory, String) -> Unit,
    onClearAgentMemory: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onRequestCalendarPermissions: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var aliasPhrase by remember { mutableStateOf("") }
    var pendingBrowserPermissionPrompt by remember { mutableStateOf<String?>(null) }
    var aliasTarget by remember { mutableStateOf("") }
    var uiTargetText by remember { mutableStateOf("") }
    var uiTargetResourceId by remember { mutableStateOf("") }
    var uiTypeText by remember { mutableStateOf("") }
    var contactAliasDraft by remember { mutableStateOf("") }
    var taskVerificationEvidence by remember { mutableStateOf("") }
    var historyQuery by remember { mutableStateOf("") }
    var showVisualUploadConfirmation by remember { mutableStateOf(false) }
    var showVisualAnalysisUploadConfirmation by remember { mutableStateOf(false) }
    var pendingVisualControlTap by remember { mutableStateOf<VisualControlCandidate?>(null) }

    MaterialTheme(colorScheme = EraDarkColorScheme, shapes = EraShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                EraTopBar()
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(tween(240)) { height -> height / 18 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(150)) { height -> -height / 28 })
                    },
                    label = "era-page-transition"
                ) { activeTab ->
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                pendingBrowserPermissionPrompt?.let { prompt ->
                    val isActionPrompt = prompt.startsWith("action:")
                    val actionName = if (isActionPrompt) prompt.substringAfter(':') else ""
                    AlertDialog(
                        onDismissRequest = { pendingBrowserPermissionPrompt = null },
                        title = {
                            Text(
                                when (prompt) {
                                    "sessions" -> "Share full page URL for this refresh?"
                                    "snapshot" -> "Share private browser details?"
                                    else -> "Confirm one browser action"
                                }
                            )
                        },
                        text = {
                            Text(
                                when (prompt) {
                                    "sessions" -> "ERA can receive the current page URL including query parameters for this refresh. Query parameters may contain session or account information. Choose safe details to exclude them."
                                    "snapshot" -> "For this snapshot only, ERA can receive current editable field values (including sensitive fields), DOM attributes and full page URL including query strings. These details may contain credentials or personal information."
                                    "action:click" -> "This control appears to send, submit, publish, pay, delete, confirm or otherwise change something. ERA will click only this selected control once after your approval."
                                    "action:fill" , "action:type" -> "This is a password, OTP, PIN, API token or payment-related field. ERA will enter only the text you supplied into this selected field once."
                                    "action:read" -> "This will share the current value and private DOM details for the selected editable/sensitive element with ERA once."
                                    "action:press" -> "ERA will send one native ${phoneBrowserAgent.keyName.trim().ifBlank { "ENTER" }} key event to the selected focused element. This may submit a form or activate a page control."
                                    "action:select" -> "ERA will choose the option matching the supplied value or label in this dropdown."
                                    "action:check", "action:uncheck" -> "ERA will change the selected checkbox, radio or switch state once."
                                    else -> "Approve this one browser action only."
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingBrowserPermissionPrompt = null
                                when (prompt) {
                                    "sessions" -> onRefreshPhoneBrowserSessions(true)
                                    "snapshot" -> onCapturePhoneBrowserSnapshot(true)
                                    else -> onRunPhoneBrowserAction(actionName, true)
                                }
                            }) { Text(if (isActionPrompt) "Allow once" else "Share once") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingBrowserPermissionPrompt = null
                                when (prompt) {
                                    "sessions" -> onRefreshPhoneBrowserSessions(false)
                                    "snapshot" -> onCapturePhoneBrowserSnapshot(false)
                                    else -> Unit
                                }
                            }) { Text(if (isActionPrompt) "Cancel" else "Use safe details") }
                        }
                    )
                }
                pendingCallConfirmation?.let { pending ->
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Confirm call", style = MaterialTheme.typography.titleMedium)
                            Text(pending.contactName, style = MaterialTheme.typography.titleLarge)
                            Text(pending.displayNumber, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Confirm opens the Android dialer with this number. ERA will not place the call.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = onConfirmCall, modifier = Modifier.weight(1f)) { Text("Confirm & open dialer") }
                                OutlinedButton(onClick = onCancelCall, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            }
                        }
                    }
                }

                EraPageHeading(activeTab)
                if (activeTab == 4) {
                    ProductivityScreen(
                        reminders = reminders,
                        memories = managedMemories,
                        agentMemoryRecords = agentMemoryRecords,
                        notificationPermissionGranted = notificationPermissionGranted,
                        exactAlarmAllowed = exactAlarmAllowed,
                        calendarPermissionsGranted = calendarPermissionsGranted,
                        status = productivityStatus,
                        onCreateReminder = onCreateReminder,
                        onDeleteReminder = onDeleteReminder,
                        onSyncReminderToCalendar = onSyncReminderToCalendar,
                        onCreateMemory = onCreateMemory,
                        onDeleteMemory = onDeleteMemory,
                        onClearMemories = onClearManagedMemory,
                        onDeleteAgentMemory = onDeleteAgentMemory,
                        onClearAgentMemory = onClearAgentMemory,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onRequestExactAlarmPermission = onRequestExactAlarmPermission,
                        onRequestCalendarPermissions = onRequestCalendarPermissions
                    )
                }
                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Contact search · name, alias or number", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ERA reads contact display names and phone numbers only after you tap Search and grant Android Contacts permission. Matching stays on-device; ambiguous matches are never auto-selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = contactSearchQuery,
                            onValueChange = onContactSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Name, nickname or phone digits") },
                            placeholder = { Text("যেমন: মা, Aru, ৯৮৭৬") }
                        )
                        Button(onClick = onSearchContacts, modifier = Modifier.fillMaxWidth()) { Text("Search contacts") }
                        contactMatches.forEach { contact ->
                            OutlinedButton(
                                onClick = { onSelectContactMatch(contact) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(contact.displayName + " · " + contact.phoneNumbers.firstOrNull().orEmpty())
                            }
                        }
                        selectedContactForAlias?.let { selected ->
                            Text("Selected for local nickname: " + selected.displayName, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = contactAliasDraft,
                                onValueChange = { contactAliasDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Add a nickname / alias") }
                            )
                            OutlinedButton(
                                onClick = {
                                    onSaveContactAlias(selected.id, contactAliasDraft)
                                    contactAliasDraft = ""
                                },
                                enabled = contactAliasDraft.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Save contact alias on this device") }
                        }
                    }
                }
                }
                if (activeTab == 0) {
                Card(
                    modifier = Modifier.fillMaxWidth().animateContentSize(tween(240)),
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, Color(0xFFB9A5FF).copy(alpha = 0.26f)),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF342957), Color(0xFF263B5A), Color(0xFF1D4850))
                                )
                            )
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ERA", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("Your local-first assistant", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "v0.9.4",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        Text(
                            "Fast Android commands · user-approved screen actions · task previews",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (microphonePermissionGranted) "MIC READY" else "MIC SETUP", microphonePermissionGranted)
                            StatusPill(if (voiceSessionActive) "LISTENING" else "VOICE IDLE", voiceSessionActive)
                            StatusPill(if (accessibilityServiceEnabled) "SCREEN ON" else "SCREEN OPTIONAL", accessibilityServiceEnabled)
                        }
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Shizuku · Android system bridge", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ERA can connect to the Android ADB shell through Shizuku. This bridge exposes only fixed read-only diagnostics; it does not accept arbitrary shell commands and does not uninstall apps, change permissions, or alter system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(if (shizukuBridgeState.binderConnected) "SERVER ON" else "SERVER OFF", shizukuBridgeState.binderConnected)
                            StatusPill(if (shizukuBridgeState.permissionGranted) "ERA ALLOWED" else "NOT ALLOWED", shizukuBridgeState.permissionGranted)
                            StatusPill(if (shizukuBridgeState.userServiceConnected) "BRIDGE READY" else "BRIDGE IDLE", shizukuBridgeState.userServiceConnected)
                        }
                        Text(shizukuBridgeState.status, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onConnectShizukuBridge, modifier = Modifier.fillMaxWidth()) {
                            Text(if (shizukuBridgeState.permissionGranted) "Connect ERA to Shizuku" else "Request Shizuku access")
                        }
                        OutlinedButton(onClick = onRefreshShizukuBridge, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh Shizuku status")
                        }
                        Text("Read-only diagnostics", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("device") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Device") }
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("battery") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Battery") }
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("display") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Display") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("apps") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Installed apps") }
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("storage") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Storage / RAM") }
                            OutlinedButton(onClick = { onReadShizukuDiagnostic("identity") }, modifier = Modifier.weight(1f), enabled = shizukuBridgeState.userServiceConnected) { Text("Shell ID") }
                        }
                        if (shizukuBridgeState.shellUid != null) {
                            Text("Shizuku server UID: ${shizukuBridgeState.shellUid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (shizukuBridgeState.lastOutput.isNotBlank()) {
                            Text("Latest diagnostic output", style = MaterialTheme.typography.labelLarge)
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                                Text(
                                    shizukuBridgeState.lastOutput.take(3000),
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Remote Connectivity V3 · private gateway", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Use an HTTPS gateway on a private VPN/overlay network. Pair with a short-lived code, then start the session yourself. ERA stores the gateway token encrypted with Android Keystore. This release sends heartbeat/presence only; it does not accept remote shell or auto-run remote commands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = remoteGatewayEndpoint,
                            onValueChange = onRemoteGatewayEndpointChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !remoteGatewayPaired,
                            label = { Text("HTTPS gateway endpoint") },
                            placeholder = { Text("https://era-gateway.example.ts.net") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (remoteGatewayPaired) "PAIRED" else "NOT PAIRED", remoteGatewayPaired)
                            StatusPill(if (remoteGatewaySessionActive) "SESSION ON" else "SESSION OFF", remoteGatewaySessionActive)
                        }
                        if (!remoteGatewayPaired) {
                            OutlinedTextField(
                                value = remotePairingCode,
                                onValueChange = onRemotePairingCodeChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("One-time pairing code") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Button(
                                onClick = onPairRemoteGateway,
                                enabled = !remoteGatewayPairingBusy && remotePairingCode.isNotBlank() && remoteGatewayEndpoint.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (remoteGatewayPairingBusy) "Pairing…" else "Pair this phone")
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = onStartRemoteSession,
                                    enabled = !remoteGatewaySessionActive,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Start session") }
                                OutlinedButton(
                                    onClick = onStopRemoteSession,
                                    enabled = remoteGatewaySessionActive,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Stop") }
                            }
                            OutlinedButton(onClick = onUnpairRemoteGateway, modifier = Modifier.fillMaxWidth()) {
                                Text("Revoke pairing")
                            }
                        }
                        Text(remoteGatewayStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("One-time permission setup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "প্রথমবার প্রয়োজনীয় permission একবার দাও। Android permission grant বহাল থাকলে ERA আর prompt দেখাবে না। Microphone-এর dialog এলে স্থায়ী grant-এর জন্য ‘Allow’ বেছে নাও, ‘Only this time’ নয়।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PermissionSetupRow(
                            title = "Microphone · voice commands",
                            granted = microphonePermissionGranted,
                            grantedText = "Granted — আবার চাইবে না",
                            actionText = "Allow microphone",
                            onAction = onRequestMicrophonePermission
                        )
                        PermissionSetupRow(
                            title = "Camera · flashlight control",
                            granted = cameraPermissionGranted,
                            grantedText = "Granted — আবার চাইবে না",
                            actionText = "Allow camera",
                            onAction = onRequestCameraPermission
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Accessibility · screen inspection", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    accessibilityServiceEnabled -> "Enabled — Android settings-এ একবার চালু করা আছে"
                                    accessibilityConsentAccepted -> "Not enabled — screen inspection ব্যবহার করতে Settings-এ একবার চালু করো"
                                    else -> "Optional — আগে screen-inspection disclosure accept করতে হবে"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accessibilityServiceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onRefreshPermissionStatuses, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh permission status")
                        }
                    }
                }
                }

                if (activeTab == 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Command", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = transcript,
                            onValueChange = onTranscriptChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("বাংলা বা English command") },
                            placeholder = { Text("যেমন: টর্চ অন কর") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onVoiceClick, modifier = Modifier.weight(1f)) {
                                Text("🎙  Speak")
                            }
                            OutlinedButton(onClick = onRunTextCommand, modifier = Modifier.weight(1f)) {
                                Text("Run command")
                            }
                        }
                        OutlinedButton(onClick = onPreviewPlan, modifier = Modifier.fillMaxWidth()) {
                            Text("Preview complex task plan")
                        }
                    }
                }
                }

                if (activeTab == 0) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Quick commands", style = MaterialTheme.typography.titleMedium)
                        QuickRow("টর্চ অন", "টর্চ অফ", onQuickCommand)
                        QuickRow("ভলিউম বাড়াও", "ভলিউম কমাও", onQuickCommand)
                        QuickRow("ভলিউম ৫০ শতাংশ", "ব্যাটারি কত", onQuickCommand)
                        QuickRow("রিংটোন ভলিউম বাড়াও", "অ্যালার্ম ভলিউম ৩০%", onQuickCommand)
                        QuickRow("টর্চ অন করে YouTube খোলো", "মিডিয়া ভলিউম কমাও", onQuickCommand)
                        QuickRow("YouTube খোলো", "Chrome খোলো", onQuickCommand)
                        QuickRow("WhatsApp খোলো", "Instagram খোলো", onQuickCommand)
                        QuickRow("Camera খোলো", "Gmail খোলো", onQuickCommand)
                        QuickRow("Google Maps খোলো", "Clock খোলো", onQuickCommand)
                        QuickRow("মাকে কল কর", "Contacts খোলো", onQuickCommand)
                        QuickRow("Wi-Fi settings খোলো", "Bluetooth settings খোলো", onQuickCommand)
                    }
                }
                }

                if (activeTab == 2) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Structured UI Inspector", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Screenshot-এর X/Y অনুমান নয়। Android Accessibility tree থেকে text, role, resource ID, clickable, enabled, input, actions এবং parent-child structure JSON হিসেবে capture হবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Data disclosure: while enabled, ERA can read visible accessibility labels, page text, resource IDs and element states from the current app. The tree is stored locally; no automatic cloud upload is configured. Password values and editable field values are omitted. In External Chrome mode, address-bar content is omitted and URL query strings/fragments are stripped. Other visible page text may still contain personal information, so review before copying or sharing a snapshot. One-shot UI actions require separate approval and an overlay tap.", style = MaterialTheme.typography.bodySmall)
                        Text("Visual capture is manual only: while viewing a target app, long-press the ERA overlay. One frame is kept in process memory for preview; it is not saved as an image file or uploaded. Clear it here when finished. Requires Android 11+.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = accessibilityConsentAccepted,
                                onCheckedChange = onAccessibilityConsentChange
                            )
                            Text("I understand and consent to ERA's screen-inspection and one-shot action features.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            enabled = accessibilityConsentAccepted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (accessibilityServiceEnabled) "ERA Screen Inspector enabled" else "Enable ERA Screen Inspector")
                        }
                        Text(
                            "Enable করার পর অন্য app-এ ERA overlay ব্যবহার করো। Auto-observe শুধু local tree আপডেট করে। Click/type/scroll চালাতে আগে ERA-তে one-shot approval দিতে হবে, তারপর target app-এ overlay চাপতে হবে।",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-observe screen changes")
                                Text("শুধু local structured snapshot আপডেট হবে; কোনো action হবে না।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = autoObservationEnabled,
                                onCheckedChange = onAutoObservationChange,
                                enabled = accessibilityConsentAccepted
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("External Chrome · separate browser target", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "এটি ERA-র ভেতরের WebView নয়। আলাদা Chrome app-এর visible Accessibility tree ব্যবহার করে। Chrome খুলে যে page inspect করতে চাও সেখানে যাও, তারপর Chrome screen-এর ERA overlay একবার tap করো। ERA-তে ফিরে Reload tree চাপলে snapshot ও action review করতে পারবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onOpenExternalChrome,
                                enabled = accessibilityConsentAccepted && accessibilityServiceEnabled,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (externalChromeTargetMode) "Open Chrome" else "Start Chrome target") }
                            OutlinedButton(
                                onClick = onDisableExternalChrome,
                                enabled = externalChromeTargetMode,
                                modifier = Modifier.weight(1f)
                            ) { Text("Disable target") }
                        }
                        Text(
                            externalChromeStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (externalChromeTargetMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Limitations: this reads Chrome's exposed visible accessibility nodes only. It does not get Chrome DOM, CDP, browser cookies, passwords, hidden page state or cross-origin frame internals. Chrome UI labels can vary by version. Send/publish/pay/delete/submit-type controls remain blocked from one-shot click approval.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("One-shot semantic action review", style = MaterialTheme.typography.titleSmall)
                        Text("Exact visible text লিখে একটি control বেছে নাও। Approval current full UI snapshot-এর সঙ্গে bind থাকে; screen বদলালে action বাতিল হবে।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = uiTargetText,
                            onValueChange = { uiTargetText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Exact element text") },
                            placeholder = { Text("যেমন: Search বা Compose") }
                        )
                        OutlinedTextField(
                            value = uiTargetResourceId,
                            onValueChange = { uiTargetResourceId = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Resource ID (optional alternative)") },
                            placeholder = { Text("যেমন: com.example:id/search") }
                        )
                        OutlinedTextField(
                            value = uiTypeText,
                            onValueChange = { uiTypeText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Text to type (for input fields)") },
                            placeholder = { Text("যে text বসাতে চাও") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onApproveUiClick(uiTargetText, uiTargetResourceId) },
                                enabled = accessibilityConsentAccepted && (uiTargetText.isNotBlank() || uiTargetResourceId.isNotBlank()) && uiSnapshot.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Approve click") }
                            OutlinedButton(
                                onClick = { onApproveUiType(uiTargetText, uiTypeText, uiTargetResourceId) },
                                enabled = accessibilityConsentAccepted && (uiTargetText.isNotBlank() || uiTargetResourceId.isNotBlank()) && uiTypeText.isNotBlank() && uiSnapshot.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Approve typing") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onApproveUiScroll(uiTargetText, uiTargetResourceId, true) },
                                enabled = accessibilityConsentAccepted && (uiTargetText.isNotBlank() || uiTargetResourceId.isNotBlank()) && uiSnapshot.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Approve scroll down") }
                            OutlinedButton(
                                onClick = { onApproveUiScroll(uiTargetText, uiTargetResourceId, false) },
                                enabled = accessibilityConsentAccepted && (uiTargetText.isNotBlank() || uiTargetResourceId.isNotBlank()) && uiSnapshot.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Approve scroll up") }
                        }
                        if (uiActionStatus.isNotBlank()) {
                            Text(uiActionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(onClick = onCancelUiAction, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel pending UI action")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onReloadUiSnapshot, modifier = Modifier.weight(1f)) { Text("Reload tree") }
                            OutlinedButton(onClick = onCopyUiSnapshot, enabled = uiSnapshot.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Copy JSON") }
                        }
                        OutlinedButton(onClick = onCopyAiContext, enabled = uiSnapshot.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Copy AI context (manual review)")
                        }
                        if (uiSnapshotError.isNotBlank()) {
                            Text(uiSnapshotError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (uiDiff.isNotBlank()) {
                            Text("Changes since previous capture", style = MaterialTheme.typography.labelLarge)
                            Text(uiDiff.take(3000), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        if (uiSnapshot.isNotBlank()) {
                            Text("Latest structured snapshot", style = MaterialTheme.typography.labelLarge)
                            Text(
                                uiSnapshot.take(10000) + if (uiSnapshot.length > 10000) "\n… preview truncated; Copy JSON exports the full snapshot." else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            OutlinedButton(onClick = onClearUiSnapshot) { Text("Clear stored UI tree") }
                        } else if (uiSnapshotError.isBlank()) {
                            Text("এখনো কোনো screen tree capture করা হয়নি।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                }

                if (activeTab == 2) {
                if (visualFrame != null || visualCaptureStatus.isNotBlank()) {
                if (showVisualAnalysisUploadConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showVisualAnalysisUploadConfirmation = false },
                        title = { Text("Analyze this screenshot with AI?") },
                        text = {
                            Text(
                                "The full manually captured screen image will be sent over HTTPS to your configured vision provider. It can contain private text or account information. ERA filters sensitive target labels locally, but cannot guarantee every pixel is free of personal information. This is a separate one-time upload consent.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showVisualAnalysisUploadConfirmation = false
                                onAnalyzeVisualScreen()
                            }) { Text("Send once and analyze UI") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showVisualAnalysisUploadConfirmation = false }) { Text("Cancel") }
                        }
                    )
                }
                if (pendingVisualControlTap != null) {
                    val candidate = pendingVisualControlTap!!
                    AlertDialog(
                        onDismissRequest = { pendingVisualControlTap = null },
                        title = { Text("Tap this exact visible control?") },
                        text = {
                            Text(
                                candidate.label + " · " + candidate.role + " · " +
                                    (candidate.confidence * 100).toInt() + "% confidence. " +
                                    candidate.actionHint + " Only this one tap will be attempted. Send, publish, pay, delete and submit controls are excluded.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingVisualControlTap = null
                                onDispatchVisualTarget(candidate)
                            }) { Text("Approve one tap") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingVisualControlTap = null }) { Text("Cancel") }
                        }
                    )
                }
                if (showVisualUploadConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showVisualUploadConfirmation = false },
                        title = { Text("Send this captured image to AI?") },
                        text = {
                            Text(
                                "The current manually captured screen image will be sent over HTTPS to the Gemini or OpenAI-compatible provider you configured. It may contain unrelated personal information visible on the screen. ERA does not upload images automatically. Continue only after checking the preview and accepting this one-time disclosure.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showVisualUploadConfirmation = false
                                onIdentifyVisualProduct()
                            }) { Text("Send once and identify") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showVisualUploadConfirmation = false }) { Text("Cancel") }
                        }
                    )
                }
                }

                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Visual frame · memory only", style = MaterialTheme.typography.titleMedium)
                            if (visualFrame != null) {
                                Image(
                                    bitmap = visualFrame.bitmap.asImageBitmap(),
                                    contentDescription = "User-requested screen capture preview",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Text(
                                    "Source: ${visualFrame.sourcePackage ?: "active app"} · captured at ${visualFrame.capturedAtEpochMs}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onClearVisualFrame, modifier = Modifier.fillMaxWidth()) {
                                    Text("Clear visual frame from memory")
                                }
                                OutlinedButton(onClick = onShareVisualFrameForProductSearch, modifier = Modifier.fillMaxWidth()) {
                                    Text("Use this frame for visual product search")
                                }
                                OutlinedButton(
                                    onClick = { showVisualUploadConfirmation = true },
                                    enabled = !productRecognitionRunning,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (productRecognitionRunning) "Identifying product…" else "Identify product with configured AI")
                                }
                                OutlinedButton(
                                    onClick = { showVisualAnalysisUploadConfirmation = true },
                                    enabled = !visualScreenAnalysisRunning,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (visualScreenAnalysisRunning) "Analyzing controls…" else "Understand visible screen controls")
                                }
                                visualScreenAnalysis?.let { analysis ->
                                    Text(analysis.summary, style = MaterialTheme.typography.bodyMedium)
                                    analysis.controls.forEach { candidate ->
                                        Card {
                                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(candidate.label + " · " + candidate.role, style = MaterialTheme.typography.titleSmall)
                                                Text(candidate.actionHint + " · " + (candidate.confidence * 100).toInt() + "% confidence",
                                                    style = MaterialTheme.typography.bodySmall)
                                                OutlinedButton(
                                                    onClick = { pendingVisualControlTap = candidate },
                                                    enabled = candidate.confidence >= 0.90 && !visualScreenAnalysisRunning,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text("Review and approve one tap") }
                                            }
                                        }
                                    }
                                }
                                if (visualScreenAnalysisStatus.isNotBlank()) {
                                    Text(visualScreenAnalysisStatus, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "The frame remains in RAM. Screen analysis and product identification each require separate one-time upload consent. Visual taps require a fresh matching screen and a second exact approval.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (visualCaptureStatus.isNotBlank()) {
                                Text(visualCaptureStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Product price search", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "After identifying an item with a visual-search app, enter the product name here to open a shopping-results search. Prices are fetched by the external search page, not guaranteed by ERA.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = productSearchQuery,
                            onValueChange = onProductSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Product name / model") }
                        )
                        Button(onClick = onSearchShoppingPrices, enabled = productSearchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Search shopping prices")
                        }
                        if (productRecognitionStatus.isNotBlank()) {
                            Text(
                                productRecognitionStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (productRecognitionRunning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                }

                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scoped File Access", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Select one folder through Android's system picker. ERA can list it and preview supported text files only; no broad storage permission, file deletion or file modification is enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (storageFolderName.isBlank()) "No folder selected" else "Folder: $storageFolderName",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = onPickStorageFolder, modifier = Modifier.weight(1f)) { Text("Choose folder") }
                            OutlinedButton(onClick = onRefreshStorage, enabled = storageFolderName.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Refresh") }
                            OutlinedButton(onClick = onShowStorageRoot, enabled = storageFolderName.isNotBlank()) { Text("Root") }
                        }
                        Text(storageStatus, style = MaterialTheme.typography.bodySmall)
                        if (storageEntries.isNotEmpty()) {
                            storageEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            (if (entry.isDirectory) "Folder" else entry.mimeType) +
                                                (entry.sizeBytes?.let { " · $it bytes" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(onClick = { onOpenStorageDocument(entry) }) {
                                        Text(if (entry.isDirectory) "Open" else "Preview")
                                    }
                                }
                            }
                        }
                        storagePreview?.let { preview ->
                            Text("Preview: ${preview.displayName}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                preview.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (storageFolderName.isNotBlank()) {
                            OutlinedButton(onClick = onClearStorageFolder, modifier = Modifier.fillMaxWidth()) {
                                Text("Remove folder access")
                            }
                        }
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI provider settings", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Default stays local-only. Remote keys are encrypted with Android Keystore; the connection test runs only when you tap Test.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            if (aiProviderKind == AIProviderKind.LOCAL_ONLY) Button(onClick = { onAIProviderKindChange(AIProviderKind.LOCAL_ONLY) }, modifier = Modifier.weight(1f)) { Text("Local") }
                            else OutlinedButton(onClick = { onAIProviderKindChange(AIProviderKind.LOCAL_ONLY) }, modifier = Modifier.weight(1f)) { Text("Local") }
                            if (aiProviderKind == AIProviderKind.OLLAMA_LOCAL) Button(onClick = { onAIProviderKindChange(AIProviderKind.OLLAMA_LOCAL) }, modifier = Modifier.weight(1f)) { Text("Ollama") }
                            else OutlinedButton(onClick = { onAIProviderKindChange(AIProviderKind.OLLAMA_LOCAL) }, modifier = Modifier.weight(1f)) { Text("Ollama") }
                            if (aiProviderKind == AIProviderKind.GEMINI) Button(onClick = { onAIProviderKindChange(AIProviderKind.GEMINI) }, modifier = Modifier.weight(1f)) { Text("Gemini") }
                            else OutlinedButton(onClick = { onAIProviderKindChange(AIProviderKind.GEMINI) }, modifier = Modifier.weight(1f)) { Text("Gemini") }
                            if (aiProviderKind == AIProviderKind.OPENAI_COMPATIBLE) Button(onClick = { onAIProviderKindChange(AIProviderKind.OPENAI_COMPATIBLE) }, modifier = Modifier.weight(1f)) { Text("OpenAI") }
                            else OutlinedButton(onClick = { onAIProviderKindChange(AIProviderKind.OPENAI_COMPATIBLE) }, modifier = Modifier.weight(1f)) { Text("OpenAI") }
                        }
                        if (aiProviderKind != AIProviderKind.LOCAL_ONLY) {
                            OutlinedTextField(
                                value = aiProviderModel,
                                onValueChange = onAIProviderModelChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Model ID") },
                                placeholder = { Text(if (aiProviderKind == AIProviderKind.OLLAMA_LOCAL) "llama3.2:1b" else "gemini-2.0-flash or gpt-4o-mini") }
                            )
                            OutlinedTextField(
                                value = aiProviderEndpoint,
                                onValueChange = onAIProviderEndpointChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (aiProviderKind == AIProviderKind.OLLAMA_LOCAL) "Local loopback endpoint" else "HTTPS endpoint") },
                                placeholder = { Text(if (aiProviderKind == AIProviderKind.OLLAMA_LOCAL) "http://localhost:11434" else "https://provider.example/v1") }
                            )
                            if (aiProviderKind == AIProviderKind.OLLAMA_LOCAL) {
                                Text(
                                    "Ollama must be running on this same device and the model must already be downloaded. Only loopback HTTP is accepted; no cloud fallback or API key is used.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                OutlinedTextField(
                                    value = aiProviderKeyDraft,
                                    onValueChange = onAIProviderKeyChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(if (aiProviderKeyConfigured) "Replace API key (optional)" else "API key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                                )
                                Text(
                                    if (aiProviderKeyConfigured) "Encrypted provider key is stored on this device."
                                    else "No API key saved yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Allow remote fallback")
                                        Text("Use the saved local plan when the provider has a transient network failure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = aiProviderFallbackEnabled, onCheckedChange = onAIProviderFallbackChange)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = onSaveAIProviderSettings, modifier = Modifier.weight(1f)) { Text("Save settings") }
                                Button(onClick = onTestAIProviderConnection, enabled = !aiProviderTestRunning, modifier = Modifier.weight(1f)) {
                                    Text(if (aiProviderTestRunning) "Testing…" else "Test connection")
                                }
                            }
                        } else {
                            Text("Local deterministic planner active. No model server, API key or network call is needed.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onSaveAIProviderSettings, modifier = Modifier.fillMaxWidth()) { Text("Save local-only mode") }
                        }
                        Text(aiProviderConnectionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }

                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Instagram preparation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Select the exact media, prepare a caption and hashtags, then hand the draft to Instagram. ERA never taps Share or publishes a post.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onPickInstagramMedia, modifier = Modifier.fillMaxWidth()) {
                            Text(if (instagramMediaLabel.isBlank()) "Choose photo or video" else "Change media: " + instagramMediaLabel.take(70))
                        }
                        OutlinedTextField(
                            value = instagramCaption,
                            onValueChange = onInstagramCaptionChange,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("Caption draft") }
                        )
                        OutlinedTextField(
                            value = instagramHashtags,
                            onValueChange = onInstagramHashtagsChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            label = { Text("Hashtags (spaces or commas)") },
                            placeholder = { Text("#era #tech #reels") }
                        )
                        Button(
                            onClick = onPrepareInstagramDraft,
                            enabled = instagramMediaLabel.isNotBlank() && (instagramCaption.isNotBlank() || instagramHashtags.isNotBlank()),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Prepare draft in Instagram") }
                        if (instagramMediaLabel.isBlank()) {
                            Text("No media selected. Android Photo Picker will provide access only to the item you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                }

                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("ERA Browser Agent · Built-in browser", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ERA-র ভিতরে নিজস্ব WebView browser চলে। Termux, ADB, localhost bridge বা আলাদা Chrome app লাগবে না। সর্বোচ্চ ৫টি in-app tab এবং semantic DOM actions সমর্থিত।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = phoneBrowserAgent.addressDraft,
                            onValueChange = onPhoneBrowserAddressChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Website address or search") },
                            placeholder = { Text("https://example.com বা search phrase") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onNavigatePhoneBrowser,
                                enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.addressDraft.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Open page") }
                            OutlinedButton(
                                onClick = onGoBackPhoneBrowser,
                                enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.sessions.any { it.active },
                                modifier = Modifier.weight(1f)
                            ) { Text("Back") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onGoForwardPhoneBrowser, enabled = !phoneBrowserAgent.busy, modifier = Modifier.weight(1f)) {
                                Text("Forward")
                            }
                            OutlinedButton(onClick = onReloadPhoneBrowser, enabled = !phoneBrowserAgent.busy, modifier = Modifier.weight(1f)) {
                                Text("Reload")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = onNewPhoneBrowserTab, enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.sessions.size < 5, modifier = Modifier.weight(1f)) {
                                Text("New tab")
                            }
                            OutlinedButton(onClick = onClosePhoneBrowserTab, enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.sessions.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Text("Close tab")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onTestPhoneBrowserAgent, enabled = !phoneBrowserAgent.busy, modifier = Modifier.weight(1f)) {
                                Text("Test browser")
                            }
                            OutlinedButton(onClick = { onRefreshPhoneBrowserSessions(false) }, enabled = !phoneBrowserAgent.busy, modifier = Modifier.weight(1f)) {
                                Text("Refresh tabs")
                            }
                        }
                        if (phoneBrowserAgent.sessions.isNotEmpty()) {
                            Text("Open tabs", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                phoneBrowserAgent.sessions.forEach { session ->
                                    val label = "Tab ${session.tab + 1}: " +
                                        session.title.ifBlank { session.url.ifBlank { "New page" } }.take(28)
                                    if (session.active) {
                                        Button(onClick = { onSelectPhoneBrowserSession(session) }) { Text(label) }
                                    } else {
                                        OutlinedButton(onClick = { onSelectPhoneBrowserSession(session) }) { Text(label) }
                                    }
                                }
                            }
                        }
                        key(phoneBrowserAgent.selectedTargetId) {
                            AndroidView(
                                factory = {
                                    (phoneBrowserWebView.parent as? ViewGroup)?.removeView(phoneBrowserWebView)
                                    phoneBrowserWebView
                                },
                                modifier = Modifier.fillMaxWidth().height(360.dp)
                            )
                        }
                        phoneBrowserAgent.sessions.firstOrNull { it.active }?.let { session ->
                            Text(
                                session.title.ifBlank { session.url.ifBlank { "New ERA browser page" } },
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (session.url.isNotBlank()) {
                                Text(session.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            "For saved passwords, use Android's configured Autofill/password-manager service. ERA only requests the system UI and never reads the credential value.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onRequestBrowserAutofill,
                            enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.sessions.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Use Android Autofill / Password Manager") }
                        OutlinedButton(
                            onClick = { pendingBrowserPermissionPrompt = "snapshot" },
                            enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.sessions.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Capture semantic snapshot") }
                        phoneBrowserAgent.snapshot?.let { snapshot ->
                            Spacer(Modifier.fillMaxWidth().height(1.dp))
                            Text(
                                snapshot.title.ifBlank { snapshot.url.ifBlank { "ERA browser page snapshot" } },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${snapshot.nodes.size} semantic nodes · ${if (snapshot.complete && !snapshot.truncated) "complete" else "limited/incomplete"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (snapshot.includesPrivateDetails) {
                                Text(
                                    "One-time private details shared · full page URL: ${snapshot.url}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (snapshot.accessibilitySummary.isNotBlank()) {
                                Text("Semantic tree: ${snapshot.accessibilitySummary}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Select an element from the latest snapshot:", style = MaterialTheme.typography.labelLarge)
                            snapshot.nodes.take(24).forEach { node ->
                                val isSelected = node.ref == phoneBrowserAgent.selectedRef
                                val summary = "${node.role.ifBlank { node.tag }} · ${node.name.ifBlank { "(unnamed)" }}".take(105)
                                val caption = if (node.sensitive) "$summary · SENSITIVE" else summary
                                if (isSelected) {
                                    Button(
                                        onClick = { onPhoneBrowserRefChange(node.ref) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("${node.ref} · $caption") }
                                } else {
                                    OutlinedButton(
                                        onClick = { onPhoneBrowserRefChange(node.ref) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("${node.ref} · $caption") }
                                }
                            }
                            if (snapshot.nodes.size > 24) {
                                Text("Showing first 24 of ${snapshot.nodes.size} nodes. Take a fresh filtered snapshot for more context.", style = MaterialTheme.typography.bodySmall)
                            }
                            val selectedNode = snapshot.nodes.firstOrNull { it.ref == phoneBrowserAgent.selectedRef }
                            selectedNode?.let { node ->
                                Text(
                                    "Selected: ${node.role} · ${node.name.ifBlank { node.tag }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = {
                                            if (PhoneBrowserAgentPolicy.requiresConfirmation(node, "read")) {
                                                pendingBrowserPermissionPrompt = "action:read"
                                            } else onRunPhoneBrowserAction("read", false)
                                        },
                                        enabled = !phoneBrowserAgent.busy && PhoneBrowserAgentPolicy.canRead(node),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Read") }
                                    Button(
                                        onClick = {
                                            if (PhoneBrowserAgentPolicy.requiresConfirmation(node, "click")) {
                                                pendingBrowserPermissionPrompt = "action:click"
                                            } else onRunPhoneBrowserAction("click", false)
                                        },
                                        enabled = !phoneBrowserAgent.busy && PhoneBrowserAgentPolicy.canClick(node),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Click once") }
                                }
                                OutlinedButton(
                                    onClick = { onRunPhoneBrowserAction("scroll", false) },
                                    enabled = !phoneBrowserAgent.busy && node.visible,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Scroll into view") }
                                if (PhoneBrowserAgentPolicy.canPress(node)) {
                                    OutlinedTextField(
                                        value = phoneBrowserAgent.keyName,
                                        onValueChange = onPhoneBrowserKeyChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Key: ENTER, TAB, ESCAPE, BACKSPACE, DELETE, SPACE, ARROWUP/DOWN/LEFT/RIGHT") },
                                        singleLine = true
                                    )
                                    OutlinedButton(
                                        onClick = { pendingBrowserPermissionPrompt = "action:press" },
                                        enabled = !phoneBrowserAgent.busy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Press ${phoneBrowserAgent.keyName.trim().ifBlank { "ENTER" }}") }
                                }
                                if (PhoneBrowserAgentPolicy.canCheck(node)) {
                                    OutlinedButton(
                                        onClick = {
                                            val action = if (node.checked == true) "uncheck" else "check"
                                            if (PhoneBrowserAgentPolicy.requiresConfirmation(node, action)) {
                                                pendingBrowserPermissionPrompt = "action:$action"
                                            } else onRunPhoneBrowserAction(action, false)
                                        },
                                        enabled = !phoneBrowserAgent.busy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (node.checked == true) "Uncheck selected control" else "Check selected control") }
                                }
                                if (node.sensitive || PhoneBrowserAgentPolicy.isSensitive(node)) {
                                    Text(
                                        "Sensitive credential, verification or payment field. ERA text entry is blocked; use the website's sign-in flow or Android Autofill.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (node.editable && PhoneBrowserAgentPolicy.canFill(node)) {
                                    OutlinedButton(
                                        onClick = { onRunPhoneBrowserAction("focus_and_show_keyboard", false) },
                                        enabled = !phoneBrowserAgent.busy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Open Android keyboard for this field") }
                                    Text(
                                        "Type directly with your selected Android keyboard. ERA does not read or store those keystrokes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = phoneBrowserAgent.fillText,
                                        onValueChange = onPhoneBrowserFillTextChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Text to fill into selected field") },
                                        visualTransformation = if (PhoneBrowserAgentPolicy.isSensitive(node))
                                            PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = {
                                                if (PhoneBrowserAgentPolicy.requiresConfirmation(node, "fill")) {
                                                    pendingBrowserPermissionPrompt = "action:fill"
                                                } else onRunPhoneBrowserAction("fill", false)
                                            },
                                            enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.fillText.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Fill") }
                                        OutlinedButton(
                                            onClick = {
                                                if (PhoneBrowserAgentPolicy.requiresConfirmation(node, "type")) {
                                                    pendingBrowserPermissionPrompt = "action:type"
                                                } else onRunPhoneBrowserAction("type", false)
                                            },
                                            enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.fillText.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Type / append") }
                                    }
                                }
                                if (PhoneBrowserAgentPolicy.canSelect(node)) {
                                    OutlinedTextField(
                                        value = phoneBrowserAgent.fillText,
                                        onValueChange = onPhoneBrowserFillTextChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Option value or label") },
                                        singleLine = true
                                    )
                                    Button(
                                        onClick = {
                                            if (PhoneBrowserAgentPolicy.requiresConfirmation(node, "select")) {
                                                pendingBrowserPermissionPrompt = "action:select"
                                            } else onRunPhoneBrowserAction("select", false)
                                        },
                                        enabled = !phoneBrowserAgent.busy && phoneBrowserAgent.fillText.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Select option") }
                                }
                                if (node.description.isNotBlank()) {
                                    Text("Accessible description: ${node.description}", style = MaterialTheme.typography.bodySmall)
                                }
                                val stateSummary = buildList {
                                    node.checked?.let { add(if (it) "checked" else "not checked") }
                                    node.selected?.let { add(if (it) "selected" else "not selected") }
                                    node.expanded?.let { add(if (it) "expanded" else "collapsed") }
                                    if (node.required) add("required")
                                    if (node.readOnly) add("read-only")
                                    if (node.invalid) add("invalid")
                                    if (node.focused) add("focused")
                                }.joinToString(" · ")
                                if (stateSummary.isNotBlank()) {
                                    Text("State: $stateSummary", style = MaterialTheme.typography.bodySmall)
                                }
                                if (snapshot.includesPrivateDetails) {
                                    node.value?.let { value ->
                                        Text("Current value (shared once): $value", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (node.locator.isNotEmpty()) {
                                        Text("DOM/Shadow selectors: ${node.locator.joinToString(" → ")}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (node.attributes.isNotEmpty()) {
                                        Text("Attributes: ${node.attributes.entries.joinToString(" · ") { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (node.frameUrl.isNotBlank()) {
                                        Text("Frame URL: ${node.frameUrl}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (phoneBrowserAgent.status.isNotBlank()) {
                            Text(phoneBrowserAgent.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (phoneBrowserAgent.actionStatus.isNotBlank()) {
                            Text(phoneBrowserAgent.actionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "Privacy and limits: private field values, DOM attributes and URL query strings are excluded by default. ERA asks before sharing them once. Sensitive field fills and consequential clicks require a separate one-time confirmation. Only HTTPS pages are opened; HTTP addresses and page links are upgraded to HTTPS. The semantic tree is derived from DOM (including open Shadow DOM and accessible same-origin frames); cross-origin iframe content and Chrome's separate app tabs are not exposed by this in-app browser.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice preferences", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Speak responses")
                                Text("ERA result voice-এ বলবে", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = speechOutputEnabled, onCheckedChange = onSpeechOutputChange)
                        }
                        Text("Recognition language", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (speechLanguage == "bn-IN") {
                                Button(onClick = { onSpeechLanguageChange("bn-IN") }, modifier = Modifier.weight(1f)) { Text("বাংলা") }
                            } else {
                                OutlinedButton(onClick = { onSpeechLanguageChange("bn-IN") }, modifier = Modifier.weight(1f)) { Text("বাংলা") }
                            }
                            if (speechLanguage == "en-US") {
                                Button(onClick = { onSpeechLanguageChange("en-US") }, modifier = Modifier.weight(1f)) { Text("English") }
                            } else {
                                OutlinedButton(onClick = { onSpeechLanguageChange("en-US") }, modifier = Modifier.weight(1f)) { Text("English") }
                            }
                        }
                    }
                }
                }

                if (activeTab == 3) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Foreground voice session", style = MaterialTheme.typography.titleMedium)
                        Text("একবার session চালু করলে ERA foreground-এ থাকা পর্যন্ত একাধিক command শুনবে। অন্য app-এ গেলে microphone session বন্ধ হবে। এটি always-on wake word নয়।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onVoiceSessionChange(!voiceSessionActive) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (voiceSessionActive) "Stop voice session" else "Start voice session")
                        }
                        Text(if (voiceSessionActive) "Listening session active" else "Session stopped", style = MaterialTheme.typography.labelMedium)
                    }
                }
                }

                if (activeTab == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Custom voice phrases", style = MaterialTheme.typography.titleMedium)
                        Text("নিজের ছোট phrase-কে আগে থেকে অনুমোদিত instant command-এর সঙ্গে যুক্ত করো।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = aliasPhrase,
                            onValueChange = { aliasPhrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("তুমি কী বলবে?") },
                            placeholder = { Text("যেমন: আমার ভিডিও অ্যাপ") }
                        )
                        OutlinedTextField(
                            value = aliasTarget,
                            onValueChange = { aliasTarget = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("কোন command চালাবে?") },
                            placeholder = { Text("যেমন: YouTube খোলো") }
                        )
                        Button(
                            onClick = {
                                onAddAlias(aliasPhrase, aliasTarget)
                                aliasPhrase = ""
                                aliasTarget = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save custom phrase") }
                        aliases.forEach { alias ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(alias.phrase, style = MaterialTheme.typography.bodyMedium)
                                    Text("→ ${alias.mappedCommand}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = { onDeleteAlias(alias.phrase) }) { Text("Remove") }
                            }
                        }
                    }
                }
                }

                if (activeTab == 0) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                }

                if (activeTab == 0) {
                if (taskMonitorText.isNotBlank()) {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Task execution monitor", style = MaterialTheme.typography.titleMedium)
                            Text(taskMonitorText, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "This is a manual, user-attested tracker. ERA does not dispatch app actions or independently verify the target app. Report a step only after you performed it yourself, then describe the outcome you personally checked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            taskSteps.forEach { record ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Step " + record.step.id + " · " + record.state.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                                    Text(record.step.instruction, style = MaterialTheme.typography.bodySmall)
                                    if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    when (record.state) {
                                        TaskStepState.PENDING, TaskStepState.NEEDS_REPLAN -> OutlinedButton(
                                            onClick = { onAdvanceTaskStep(record.step.id, "start") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Start step review") }
                                        TaskStepState.OBSERVING -> OutlinedButton(
                                            onClick = { onAdvanceTaskStep(record.step.id, "review") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Mark ready for review") }
                                        TaskStepState.READY_FOR_REVIEW -> Button(
                                            onClick = { onAdvanceTaskStep(record.step.id, "approve") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Approve plan step (review only)") }
                                        TaskStepState.APPROVED, TaskStepState.WAITING_USER -> OutlinedButton(
                                            onClick = { onAdvanceTaskStep(record.step.id, "reported") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("I performed / completed this step manually") }
                                        TaskStepState.USER_REPORTED -> {
                                            OutlinedTextField(
                                                value = taskVerificationEvidence,
                                                onValueChange = { taskVerificationEvidence = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("What outcome did you personally check?") },
                                                minLines = 2
                                            )
                                            Button(
                                                onClick = {
                                                    onVerifyTaskStep(record.step.id, taskVerificationEvidence)
                                                    taskVerificationEvidence = ""
                                                },
                                                enabled = taskVerificationEvidence.isNotBlank(),
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Confirm user-checked outcome") }
                                        }
                                        TaskStepState.RECOVERY_REQUIRED -> {
                                            OutlinedTextField(
                                                value = taskVerificationEvidence,
                                                onValueChange = { taskVerificationEvidence = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("What did you inspect in the target app?") },
                                                minLines = 2
                                            )
                                            Button(
                                                onClick = {
                                                    onVerifyTaskStep(record.step.id, taskVerificationEvidence)
                                                    taskVerificationEvidence = ""
                                                },
                                                enabled = taskVerificationEvidence.isNotBlank(),
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Confirm inspection & return to review") }
                                        }
                                        TaskStepState.FAILED, TaskStepState.REJECTED -> OutlinedButton(
                                            onClick = { onAdvanceTaskStep(record.step.id, "replan") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Return to review") }
                                        TaskStepState.MACHINE_VERIFIED -> Text(
                                            "Independently verified by the execution verifier.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        else -> Unit
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            if (taskSteps.any { it.state !in setOf(TaskStepState.USER_CONFIRMED, TaskStepState.MACHINE_VERIFIED, TaskStepState.CANCELLED) }) {
                                OutlinedButton(onClick = onCancelTaskMonitor, modifier = Modifier.fillMaxWidth()) {
                                    Text("Cancel pending task steps")
                                }
                            }
                        }
                    }
                }
                }

                if (activeTab == 0) {
                if (planPreview.isNotBlank()) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Task plan preview", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(planPreview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                }

                if (activeTab == 0) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Command history", style = MaterialTheme.typography.titleMedium)
                            if (history.isNotEmpty()) OutlinedButton(onClick = onClearHistory) { Text("Clear") }
                        }
                        if (history.isEmpty()) {
                            Text("এখনও কোনো command history নেই।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            OutlinedTextField(
                                value = historyQuery,
                                onValueChange = { historyQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Search command history") }
                            )
                            val filteredHistory = history.filter { it.contains(historyQuery, ignoreCase = true) }
                            Text("Showing " + filteredHistory.size + " of " + history.size + " entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            filteredHistory.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (filteredHistory.isEmpty()) Text("No history entry matches this search.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }

                if (activeTab == 3) {
                Text(
                    "Privacy: microphone চালু হয় শুধু user action-এ; background-এ গেলে foreground voice session বন্ধ হয়। Screen Inspector optional; visual frame শুধু overlay long-press করলে capture হয়, memory-তে থাকে, 1440px-এর মধ্যে downscale হয় এবং কোনো image file বা automatic upload হয় না। Password fields redact হয়, কিন্তু অন্য screen text personal হতে পারে—copy করার আগে review করো। Remote AI optional এবং শুধু enabled provider setting-এ task goal পাঠায়; local mode default। Send/Publish/Pay/Delete/Submit ERA নিজে final tap করে না।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                    }
                }
                }
                EraBottomNavigation(selectedTab = selectedTab, onSelect = { selectedTab = it })
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PermissionSetupRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (granted) grantedText else "Not granted yet — only requested when you choose",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun QuickRow(first: String, second: String, onQuickCommand: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onQuickCommand(first) }, modifier = Modifier.weight(1f)) {
            Text(first)
        }
        OutlinedButton(onClick = { onQuickCommand(second) }, modifier = Modifier.weight(1f)) {
            Text(second)
        }
    }
}
