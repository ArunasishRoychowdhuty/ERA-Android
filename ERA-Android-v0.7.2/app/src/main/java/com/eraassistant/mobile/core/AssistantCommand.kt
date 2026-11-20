package com.eraassistant.mobile.core

sealed interface AssistantCommand {
    data class SetTorch(val enabled: Boolean) : AssistantCommand
    data object VolumeUp : AssistantCommand
    data object VolumeDown : AssistantCommand
    data class SetMediaVolumePercent(val percent: Int) : AssistantCommand
    data class AdjustVolume(val stream: VolumeStream, val direction: VolumeDirection) : AssistantCommand
    data class SetVolumePercent(val stream: VolumeStream, val percent: Int) : AssistantCommand
    data class LaunchApp(val app: KnownApp) : AssistantCommand
    data class LaunchAppByName(val appName: String) : AssistantCommand
    data class OpenSettings(val action: SettingsAction) : AssistantCommand
    data object OpenCamera : AssistantCommand
    data class DialNumber(val number: String) : AssistantCommand
    data object PickContactForDial : AssistantCommand
    data class OpenWebSearch(val query: String) : AssistantCommand
    data class OpenMapSearch(val query: String) : AssistantCommand
    data class OpenWebsite(val url: String) : AssistantCommand
    data class CreateSmsDraft(val text: String) : AssistantCommand
    data class ShareTextToWhatsApp(val text: String) : AssistantCommand
    data object ReadBattery : AssistantCommand
    data object ReadDateTime : AssistantCommand
    data class RunSequence(val commands: List<AssistantCommand>) : AssistantCommand
    data object CancelCurrentTask : AssistantCommand
    data class UnsupportedComplexTask(val originalText: String) : AssistantCommand
    data object Help : AssistantCommand
}

enum class VolumeStream(val streamId: Int, val label: String) {
    MEDIA(3, "media"),
    RINGTONE(2, "ringtone"),
    NOTIFICATION(5, "notification"),
    ALARM(4, "alarm")
}

enum class VolumeDirection { UP, DOWN }

enum class KnownApp(val packageNames: List<String>, val displayName: String) {
    YOUTUBE(listOf("com.google.android.youtube"), "YouTube"),
    CHROME(listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev"), "Chrome"),
    WHATSAPP(listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp"),
    INSTAGRAM(listOf("com.instagram.android"), "Instagram"),
    MAPS(listOf("com.google.android.apps.maps"), "Google Maps"),
    GMAIL(listOf("com.google.android.gm"), "Gmail"),
    MESSAGES(listOf("com.google.android.apps.messaging", "com.android.messaging"), "Messages"),
    PHONE(listOf("com.google.android.dialer", "com.android.dialer"), "Phone"),
    CLOCK(listOf("com.google.android.deskclock", "com.android.deskclock"), "Clock"),
    SETTINGS(listOf("com.android.settings"), "Settings"),
    CALENDAR(listOf("com.google.android.calendar", "com.android.calendar"), "Calendar"),
    SPOTIFY(listOf("com.spotify.music"), "Spotify"),
    FILES(listOf("com.google.android.documentsui", "com.google.android.apps.nbu.files"), "Files"),
    PHOTOS(listOf("com.google.android.apps.photos"), "Google Photos"),
    CONTACTS(listOf("com.google.android.contacts", "com.android.contacts"), "Contacts")
}

enum class SettingsAction(val intentAction: String, val label: String) {
    WIFI("android.settings.WIFI_SETTINGS", "Wi-Fi settings"),
    BLUETOOTH("android.settings.BLUETOOTH_SETTINGS", "Bluetooth settings"),
    DISPLAY("android.settings.DISPLAY_SETTINGS", "Display settings"),
    SOUND("android.settings.SOUND_SETTINGS", "Sound settings"),
    LOCATION("android.settings.LOCATION_SOURCE_SETTINGS", "Location settings"),
    APPS("android.settings.APPLICATION_SETTINGS", "App settings"),
    GENERAL("android.settings.SETTINGS", "Settings")
}
