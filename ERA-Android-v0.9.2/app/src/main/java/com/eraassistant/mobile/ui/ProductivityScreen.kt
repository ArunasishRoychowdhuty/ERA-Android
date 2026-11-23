package com.eraassistant.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eraassistant.mobile.productivity.ReminderUiItem
import com.eraassistant.mobile.productivity.ManagedMemoryUiItem
import com.eraassistant.mobile.memory.MemoryRecord
import com.eraassistant.mobile.memory.MemoryCategory
import java.text.DateFormat
import java.util.Date

@Composable
fun ProductivityScreen(
    reminders: List<ReminderUiItem>,
    memories: List<ManagedMemoryUiItem>,
    agentMemoryRecords: List<MemoryRecord>,
    notificationPermissionGranted: Boolean,
    exactAlarmAllowed: Boolean,
    calendarPermissionsGranted: Boolean,
    status: String,
    onCreateReminder: (String, Long) -> Unit,
    onDeleteReminder: (String) -> Unit,
    onSyncReminderToCalendar: (String) -> Unit,
    onCreateMemory: (String, String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearMemories: () -> Unit,
    onDeleteAgentMemory: (MemoryCategory, String) -> Unit,
    onClearAgentMemory: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onRequestCalendarPermissions: () -> Unit
) {
    var reminderTitle by rememberSaveable { mutableStateOf("") }
    var reminderDelayMinutes by rememberSaveable { mutableStateOf("10") }
    var memoryTitle by rememberSaveable { mutableStateOf("") }
    var memoryContent by rememberSaveable { mutableStateOf("") }
    var memoryCategory by rememberSaveable { mutableStateOf("USER_PREFERENCE") }
    var memoryConsent by rememberSaveable { mutableStateOf(false) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showClearMemoryDialog by remember { mutableStateOf(false) }
    var showClearAgentMemoryDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("PERSONAL PRODUCTIVITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text("Reminders & private memory", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Reminders are saved locally. Memory entries are encrypted with an Android Keystore key. Each save, delete, calendar export and permission grant is user initiated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Reminder scheduler", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusMarker("Notifications", notificationPermissionGranted)
                    StatusMarker("Exact alarms", exactAlarmAllowed)
                    StatusMarker("Calendar", calendarPermissionsGranted)
                }
                OutlinedTextField(
                    value = reminderTitle,
                    onValueChange = { reminderTitle = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Reminder title") },
                    placeholder = { Text("e.g. Review assignment") }
                )
                OutlinedTextField(
                    value = reminderDelayMinutes,
                    onValueChange = { value -> reminderDelayMinutes = value.filter(Char::isDigit).take(7) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Remind me after (minutes)") }
                )
                Button(
                    onClick = {
                        val delay = reminderDelayMinutes.toLongOrNull()
                        if (delay != null && delay in 1L..525_600L && reminderTitle.isNotBlank()) {
                            onCreateReminder(reminderTitle.trim(), delay)
                            reminderTitle = ""
                        }
                    },
                    enabled = reminderTitle.isNotBlank() && (reminderDelayMinutes.toLongOrNull() ?: 0L) in 1L..525_600L,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create reminder") }
                if (!notificationPermissionGranted) {
                    OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow reminder notifications")
                    }
                }
                if (!exactAlarmAllowed) {
                    OutlinedButton(onClick = onRequestExactAlarmPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable exact alarm access (optional)")
                    }
                }
                Text(
                    "When exact-alarm access is unavailable, ERA schedules a WorkManager fallback. Android may delay that reminder under battery restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reminders.isEmpty()) {
                    Text("No reminders saved.", style = MaterialTheme.typography.bodySmall)
                } else {
                    reminders.forEach { reminder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(reminder.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reminder.triggerAtEpochMs)) +
                                        if (reminder.isTriggered) " · Notified" else " · Pending",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (reminder.calendarSynced) Text("Added to calendar", style = MaterialTheme.typography.labelSmall)
                            }
                            if (!reminder.calendarSynced && !reminder.isTriggered) {
                                TextButton(onClick = { onSyncReminderToCalendar(reminder.id) }) { Text("Calendar") }
                            }
                            TextButton(onClick = { onDeleteReminder(reminder.id) }) { Text("Delete") }
                        }
                    }
                }
                if (!calendarPermissionsGranted) {
                    OutlinedButton(onClick = onRequestCalendarPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect Android Calendar")
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Encrypted personal memory", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Only information you deliberately add here is saved. Passwords, OTPs, PINs, card data, bank credentials, access tokens and similar secrets are rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { showCategoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Category: " + memoryCategory.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                }
                DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                    listOf("USER_PREFERENCE", "HOUSEHOLD", "ROUTINE", "PROJECT_NOTE").forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = { memoryCategory = item; showCategoryMenu = false }
                        )
                    }
                }
                OutlinedTextField(
                    value = memoryTitle,
                    onValueChange = { memoryTitle = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Memory title") }
                )
                OutlinedTextField(
                    value = memoryContent,
                    onValueChange = { memoryContent = it.take(1500) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("What should ERA remember?") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = memoryConsent, onCheckedChange = { memoryConsent = it })
                    Text("I approve saving this entry encrypted on this device.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        onCreateMemory(memoryCategory, memoryTitle.trim(), memoryContent.trim())
                        memoryTitle = ""
                        memoryContent = ""
                        memoryConsent = false
                    },
                    enabled = memoryConsent && memoryTitle.isNotBlank() && memoryContent.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save encrypted memory") }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved entries (${memories.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (memories.isNotEmpty()) TextButton(onClick = { showClearMemoryDialog = true }) { Text("Clear all") }
                }
                if (memories.isEmpty()) {
                    Text("No managed memory entries saved.", style = MaterialTheme.typography.bodySmall)
                } else {
                    memories.forEach { entry ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(entry.category.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    Text(entry.content, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onDeleteMemory(entry.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Existing ERA agent memory", style = MaterialTheme.typography.titleMedium)
                        Text("Encrypted at rest with Android Keystore. These records belong to ERA's internal learning and workflow memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (agentMemoryRecords.isNotEmpty()) TextButton(onClick = { showClearAgentMemoryDialog = true }) { Text("Clear all") }
                }
                Text("Showing ${agentMemoryRecords.size} recent records", style = MaterialTheme.typography.labelSmall)
                if (agentMemoryRecords.isEmpty()) {
                    Text("No legacy agent memory records saved.", style = MaterialTheme.typography.bodySmall)
                } else {
                    agentMemoryRecords.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.category.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Text(entry.key, style = MaterialTheme.typography.bodyMedium)
                                Text(entry.value.take(500), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDeleteAgentMemory(entry.category, entry.key) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryDialog = false },
            title = { Text("Delete all saved memory?") },
            text = { Text("This permanently removes every entry in the encrypted ERA personal-memory section. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showClearMemoryDialog = false; onClearMemories() }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showClearAgentMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearAgentMemoryDialog = false },
            title = { Text("Clear all ERA agent memory?") },
            text = { Text("This deletes all legacy profile, workflow, correction, capability and agent-configuration memory records. Reminders and Room-managed memory are not affected.") },
            confirmButton = {
                TextButton(onClick = { showClearAgentMemoryDialog = false; onClearAgentMemory() }) { Text("Clear all agent memory") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAgentMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatusMarker(label: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            "$label: " + if (active) "On" else "Off",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
