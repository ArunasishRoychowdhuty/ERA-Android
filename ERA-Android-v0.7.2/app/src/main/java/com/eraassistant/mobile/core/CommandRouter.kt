package com.eraassistant.mobile.core

/** Deterministic, offline router. External model/provider integration is intentionally out of scope. */
class CommandRouter {

    fun route(rawCommand: String): AssistantCommand {
        val normalized = normalize(rawCommand)
        if (normalized.isBlank()) return AssistantCommand.Help
        val single = routeSingle(rawCommand)

        val segments = splitCompound(normalized, rawCommand)
        if (segments.size > 1) {
            val routed = segments.map { routeSingle(it) }
            if (routed.all {
                    it !is AssistantCommand.Help &&
                        it !is AssistantCommand.UnsupportedComplexTask &&
                        it !is AssistantCommand.RunSequence &&
                        it != AssistantCommand.PickContactForDial &&
                        it != AssistantCommand.CancelCurrentTask
                }) {
                return AssistantCommand.RunSequence(routed)
            }
            // Preserve a complete search/draft/complex intent instead of splitting its payload.
            if (single !is AssistantCommand.Help) return single
            // Never partially execute a request when any segment is unclear.
            return AssistantCommand.UnsupportedComplexTask(rawCommand.trim())
        }
        return single
    }

    private fun routeSingle(rawCommand: String): AssistantCommand {
        val text = normalize(rawCommand)
        if (text.isBlank()) return AssistantCommand.Help

        if (containsAny(text, "era stop", "stop task", "cancel task", "কাজ থামাও", "বন্ধ করো", "থামো", "বাতিল কর")) {
            return AssistantCommand.CancelCurrentTask
        }

        if (containsAny(text, "টর্চ অন", "ফ্ল্যাশ অন", "লাইট অন", "torch on", "flashlight on", "flash on")) {
            return AssistantCommand.SetTorch(true)
        }
        if (containsAny(text, "টর্চ অফ", "টর্চ বন্ধ", "ফ্ল্যাশ অফ", "লাইট বন্ধ", "torch off", "flashlight off", "flash off")) {
            return AssistantCommand.SetTorch(false)
        }

        channelVolumeCommand(text)?.let { return it }
        if (containsAny(text, "ভলিউম বাড়াও", "ভলিউম বাড়াও", "সাউন্ড বাড়াও", "volume up", "increase volume", "louder")) {
            return AssistantCommand.VolumeUp
        }
        if (containsAny(text, "ভলিউম কমাও", "সাউন্ড কমাও", "volume down", "decrease volume", "quieter")) {
            return AssistantCommand.VolumeDown
        }
        if (containsAny(text, "unmute", "সাউন্ড চালু কর", "ভলিউম ফুল")) {
            return AssistantCommand.SetMediaVolumePercent(100)
        }
        if (containsAny(text, "mute", "সাইলেন্ট কর", "ভলিউম শূন্য")) {
            return AssistantCommand.SetMediaVolumePercent(0)
        }
        volumePercentCommand(text)?.let { return it }

        if (containsAny(text, "ব্যাটারি কত", "ব্যাটারি দেখাও", "battery level", "battery status", "battery কত", "how much battery")) {
            return AssistantCommand.ReadBattery
        }
        if (containsAny(text, "সময় বল", "সময় বল", "এখন কয়টা", "এখন কয়টা", "what time", "current time", "আজ কত তারিখ", "date today")) {
            return AssistantCommand.ReadDateTime
        }

        // Draft shortcuts first; recipient-directed or multi-step requests remain complex tasks.
        whatsappDraftCommand(text, rawCommand)?.let { return it }
        smsDraftCommand(text, rawCommand)?.let { return it }
        if (looksLikeComplexTask(text)) return AssistantCommand.UnsupportedComplexTask(rawCommand.trim())
        mapSearchCommand(text, rawCommand)?.let { return it }
        webSearchCommand(text, rawCommand)?.let { return it }
        websiteCommand(text, rawCommand)?.let { return it }

        if (containsAny(text, "ক্যামেরা খোলো", "ক্যামেরা চালু", "camera খোলো", "open camera", "launch camera")) {
            return AssistantCommand.OpenCamera
        }

        appCommand(text)?.let { return it }
        settingsCommand(text)?.let { return it }
        dynamicAppCommand(text, rawCommand)?.let { return it }
        dialCommand(text)?.let { return it }
        if (containsAny(text, "কল কর", "কল দাও", "call ", "phone ", "মাকে কল", "বাবাকে কল", "বন্ধুকে কল", "মাকে ফোন", "বাবাকে ফোন", "mom call", "call mom", "call mother", "call dad", "call father")) {
            return AssistantCommand.PickContactForDial
        }
        return AssistantCommand.Help
    }

    private fun splitCompound(normalized: String, original: String): List<String> {
        val separator = Regex("\\s+(?:তারপর|এরপর|এবং|আর|করে|and\\s+then|then|and)\\s+|\\s*,\\s*|\\s*।\\s*")
        if (!separator.containsMatchIn(normalized)) return listOf(original)
        val parts = separator.split(normalized).map(String::trim).filter(String::isNotBlank)
        return if (parts.size in 2..5) parts else listOf(original)
    }

    private fun channelVolumeCommand(text: String): AssistantCommand? {
        val stream = when {
            containsAny(text, "রিংটোন", "ringtone", "রিং ভলিউম", "ring volume") -> VolumeStream.RINGTONE
            containsAny(text, "নোটিফিকেশন", "notification") -> VolumeStream.NOTIFICATION
            containsAny(text, "অ্যালার্ম", "alarm") -> VolumeStream.ALARM
            containsAny(text, "মিডিয়া", "মিডিয়া", "media volume") -> VolumeStream.MEDIA
            else -> return null
        }
        val direction = when {
            containsAny(text, "বাড়াও", "বাড়াও", "বৃদ্ধি", "up", "increase", "বেশি কর") -> VolumeDirection.UP
            containsAny(text, "কমাও", "কম কর", "down", "decrease", "lower") -> VolumeDirection.DOWN
            else -> null
        }
        if (direction != null) return AssistantCommand.AdjustVolume(stream, direction)
        val percent = Regex("(\\d{1,3})\\s*(%|percent|শতাংশ)?").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return percent?.let { AssistantCommand.SetVolumePercent(stream, it.coerceIn(0, 100)) }
    }

    private fun appCommand(text: String): AssistantCommand.LaunchApp? {
        val app = when {
            containsAny(text, "ইউটিউব", "youtube") -> KnownApp.YOUTUBE
            containsAny(text, "ক্রোম", "chrome", "browser", "ব্রাউজার") -> KnownApp.CHROME
            containsAny(text, "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "whatsapp") -> KnownApp.WHATSAPP
            containsAny(text, "ইনস্টাগ্রাম", "instagram") -> KnownApp.INSTAGRAM
            containsAny(text, "গুগল ম্যাপ", "গুগল ম্যাপস", "google maps") -> KnownApp.MAPS
            containsAny(text, "জিমেইল", "gmail", "ইমেইল") -> KnownApp.GMAIL
            containsAny(text, "মেসেজ", "messages", "sms অ্যাপ") -> KnownApp.MESSAGES
            containsAny(text, "ফোন অ্যাপ", "ফোন খোলো", "phone app", "open phone", "phone খোলো", "ডায়ালার খোলো", "ডায়ালার খোলো") -> KnownApp.PHONE
            containsAny(text, "ঘড়ি", "ঘড়ি", "clock", "alarm") -> KnownApp.CLOCK
            containsAny(text, "সেটিংস খোলো", "settings open", "open settings") -> KnownApp.SETTINGS
            containsAny(text, "ক্যালেন্ডার", "calendar") -> KnownApp.CALENDAR
            containsAny(text, "স্পটিফাই", "spotify") -> KnownApp.SPOTIFY
            containsAny(text, "ফাইল ম্যানেজার", "ফাইলস খোলো", "files app", "file manager") -> KnownApp.FILES
            containsAny(text, "গুগল ফটোস", "google photos", "photos app") -> KnownApp.PHOTOS
            containsAny(text, "কন্টাক্ট", "contacts", "contact list") -> KnownApp.CONTACTS
            else -> null
        } ?: return null
        return AssistantCommand.LaunchApp(app)
    }

    private fun dynamicAppCommand(text: String, original: String): AssistantCommand.LaunchAppByName? {
        val explicitMarkers = listOf("open app ", "launch app ", "অ্যাপ খোলো ", "অ্যাপ ওপেন কর ")
        val marker = explicitMarkers.firstOrNull { text.contains(it) }
        val candidate = if (marker != null) {
            val start = text.indexOf(marker) + marker.length
            original.substring(start.coerceAtMost(original.length)).trim()
        } else if (text.endsWith(" খোলো")) {
            original.dropLast(" খোলো".length).trim()
        } else return null
        return candidate.takeIf { it.isNotBlank() && it.length <= 60 }?.let(AssistantCommand::LaunchAppByName)
    }

    private fun settingsCommand(text: String): AssistantCommand.OpenSettings? = when {
        containsAny(text, "ওয়াইফাই সেটিং", "ওয়াই ফাই সেটিং", "wifi settings", "wi-fi settings") -> AssistantCommand.OpenSettings(SettingsAction.WIFI)
        containsAny(text, "ব্লুটুথ সেটিং", "bluetooth settings") -> AssistantCommand.OpenSettings(SettingsAction.BLUETOOTH)
        containsAny(text, "ডিসপ্লে সেটিং", "display settings", "brightness settings") -> AssistantCommand.OpenSettings(SettingsAction.DISPLAY)
        containsAny(text, "সাউন্ড সেটিং", "sound settings") -> AssistantCommand.OpenSettings(SettingsAction.SOUND)
        containsAny(text, "লোকেশন সেটিং", "location settings") -> AssistantCommand.OpenSettings(SettingsAction.LOCATION)
        containsAny(text, "অ্যাপ সেটিং", "apps settings", "app settings") -> AssistantCommand.OpenSettings(SettingsAction.APPS)
        else -> null
    }

    private fun dialCommand(text: String): AssistantCommand.DialNumber? {
        if (!containsAny(text, "ডায়াল", "ডায়াল", "কল কর", "নম্বর কল", "call number", "call ", "dial ")) return null
        val digits = text.filter { it.isDigit() || it == '+' }
        return if (digits.count { it.isDigit() } >= 6) AssistantCommand.DialNumber(digits) else null
    }

    private fun whatsappDraftCommand(text: String, original: String): AssistantCommand.ShareTextToWhatsApp? {
        if (!containsAny(text, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ")) return null
        if (containsAny(text, "কে ", " to ", "send to ")) return null
        val candidate = extractDraftText(text, original) ?: return null
        return AssistantCommand.ShareTextToWhatsApp(normalizeGreeting(candidate))
    }

    private fun smsDraftCommand(text: String, original: String): AssistantCommand.CreateSmsDraft? {
        if (!containsAny(text, "sms", "এসএমএস", "মেসেজ লিখে", "text message")) return null
        val candidate = extractDraftText(text, original) ?: return null
        return AssistantCommand.CreateSmsDraft(normalizeGreeting(candidate))
    }

    private fun extractDraftText(text: String, original: String): String? {
        val prefixMarker = listOf("type ", "write ", "টাইপ কর ", "লিখে দাও ").firstOrNull { text.contains(it) }
        if (prefixMarker != null) {
            val start = text.indexOf(prefixMarker) + prefixMarker.length
            return original.substring(start.coerceAtMost(original.length)).trim(' ', '.', ',', '।', '"', '\'').takeIf(String::isNotBlank)
        }
        val suffixMarker = listOf(" লিখে দাও", " টাইপ কর", " লিখে রাখ").firstOrNull { text.contains(it) } ?: return null
        val suffixIndex = text.indexOf(suffixMarker)
        var before = original.substring(0, suffixIndex.coerceAtMost(original.length)).trim()
        val normalizedBefore = text.substring(0, suffixIndex).trim()
        val recipientBoundary = listOf("কে ", "to ").lastOrNull { normalizedBefore.contains(it) }
        if (recipientBoundary != null) {
            val cutAt = normalizedBefore.lastIndexOf(recipientBoundary) + recipientBoundary.length
            before = before.substring(cutAt.coerceAtMost(before.length)).trim()
        } else {
            val appName = listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "sms", "এসএমএস").firstOrNull { normalizedBefore.contains(it) }
            if (appName != null) before = before.substring((normalizedBefore.indexOf(appName) + appName.length).coerceAtMost(before.length))
            before = before.trimStart('-', '–', '—', ' ', 'এ', 'ে', ':')
            before = before.replace(Regex("^(খুলে|খোলো|open|and)\\s+"), "").trim()
        }
        return before.takeIf(String::isNotBlank)
    }

    private fun volumePercentCommand(text: String): AssistantCommand.SetMediaVolumePercent? {
        if (!containsAny(text, "ভলিউম", "volume", "সাউন্ড")) return null
        val match = Regex("(\\d{1,3})\\s*(%|percent|শতাংশ)?").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)?.let(AssistantCommand::SetMediaVolumePercent)
    }

    private fun webSearchCommand(text: String, original: String): AssistantCommand.OpenWebSearch? {
        val markers = listOf("গুগলে সার্চ কর", "গুগলে খোঁজ", "search for ", "google search ", "সার্চ কর ")
        val marker = markers.firstOrNull { text.contains(it) } ?: return null
        val start = text.indexOf(marker) + marker.length
        return original.substring(start.coerceAtMost(original.length)).trim().takeIf(String::isNotBlank)?.let(AssistantCommand::OpenWebSearch)
    }

    private fun mapSearchCommand(text: String, original: String): AssistantCommand.OpenMapSearch? {
        val markers = listOf("ম্যাপে খোঁজ ", "ম্যাপে দেখাও ", "map search ", "show on map ", "গুগল ম্যাপে ")
        val marker = markers.firstOrNull { text.contains(it) } ?: return null
        val start = text.indexOf(marker) + marker.length
        return original.substring(start.coerceAtMost(original.length)).trim().takeIf(String::isNotBlank)?.let(AssistantCommand::OpenMapSearch)
    }

    private fun websiteCommand(text: String, original: String): AssistantCommand.OpenWebsite? {
        val marker = listOf("ওয়েবসাইট খোলো ", "ওয়েবসাইট খোলো ", "open website ", "open site ").firstOrNull { text.contains(it) } ?: return null
        var candidate = original.substring((text.indexOf(marker) + marker.length).coerceAtMost(original.length)).trim()
        if (candidate.isBlank()) return null
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) candidate = "https://$candidate"
        return AssistantCommand.OpenWebsite(candidate)
    }

    private fun looksLikeComplexTask(text: String): Boolean = containsAny(
        text,
        "পোস্ট", "আপলোড", "publish", "post ", "upload", "send ", "message ",
        "টাইপ কর", "লিখে দাও", "type ", "write ", "ফর্ম পূরণ", "fill form", "book ", "booking", "অর্ডার কর",
        "হোয়াটসঅ্যাপ খুলে", "হোয়াটসঅ্যাপ খুলে", "whatsapp খুলে", "whatsapp open"
    )

    private fun normalizeGreeting(value: String): String = when (value.trim().lowercase()) {
        "হাই", "hi" -> "Hi"
        "হ্যালো", "hello" -> "Hello"
        else -> value.trim()
    }

    private fun normalize(value: String): String = value.lowercase().map { char ->
        val index = BANGLA_DIGITS.indexOf(char)
        if (index >= 0) ('0' + index) else char
    }.joinToString("").replace(Regex("\\s+"), " ").trim()

    private fun containsAny(text: String, vararg values: String): Boolean = values.any(text::contains)

    private companion object { const val BANGLA_DIGITS = "০১২৩৪৫৬৭৮৯" }
}
