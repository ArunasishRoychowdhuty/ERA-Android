package com.eraassistant.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eraassistant.mobile.preferences.CustomCommandAlias

@Composable
fun ERAScreen(
    transcript: String,
    status: String,
    history: List<String>,
    aliases: List<CustomCommandAlias>,
    planPreview: String,
    speechOutputEnabled: Boolean,
    speechLanguage: String,
    autoObservationEnabled: Boolean,
    accessibilityConsentAccepted: Boolean,
    microphonePermissionGranted: Boolean,
    cameraPermissionGranted: Boolean,
    accessibilityServiceEnabled: Boolean,
    voiceSessionActive: Boolean,
    uiActionStatus: String,
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
    onClearUiSnapshot: () -> Unit
) {
    var aliasPhrase by remember { mutableStateOf("") }
    var aliasTarget by remember { mutableStateOf("") }
    var uiTargetText by remember { mutableStateOf("") }
    var uiTargetResourceId by remember { mutableStateOf("") }
    var uiTypeText by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ERA Assistant", style = MaterialTheme.typography.headlineMedium)
                    Text("Voice-first Android assistant · v0.7.1", style = MaterialTheme.typography.bodyMedium)
                    Text("Fast local commands + approved semantic UI actions + offline task planning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

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
                                androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = null)
                                Text("  Speak")
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

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Structured UI Inspector", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Screenshot-এর X/Y অনুমান নয়। Android Accessibility tree থেকে text, role, resource ID, clickable, enabled, input, actions এবং parent-child structure JSON হিসেবে capture হবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Data disclosure: while enabled, ERA can read accessible UI text, labels, resource IDs and element states from the current app screen. The local tree is stored on this phone. No cloud/provider upload is configured. Password fields are redacted, but other visible personal text is not redacted in this version. One-shot UI actions require your separate approval and an overlay tap.", style = MaterialTheme.typography.bodySmall)
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

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (planPreview.isNotBlank()) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Task plan preview", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(planPreview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Command history", style = MaterialTheme.typography.titleMedium)
                            if (history.isNotEmpty()) OutlinedButton(onClick = onClearHistory) { Text("Clear") }
                        }
                        if (history.isEmpty()) {
                            Text("এখনও কোনো command history নেই।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            history.take(15).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                Text(
                    "Privacy: microphone চালু হয় শুধু Speak বা foreground voice session শুরু করলে; app background-এ গেলে session বন্ধ হয়। Screen Inspector optional ও user-enabled; screen text local-এ থাকে। Password fields redact হয়, কিন্তু অন্য personal text এই version-এ redacted নয়—copy করার আগে নিজে review করো। Provider/API integration নেই। Send/Publish/Pay/Delete/Submit control ERA click করে না।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
