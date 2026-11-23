package com.eraassistant.mobile.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.core.VolumeDirection
import com.eraassistant.mobile.core.VolumeStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DeviceController(private val context: Context) {
    private val torchStates = ConcurrentHashMap<String, Boolean>()
    private val cameraManager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchStates[cameraId] = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            torchStates.remove(cameraId)
        }
    }

    init {
        runCatching {
            cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    /**
     * Typed adapter entry point. Unsupported/missing targets are represented as statuses,
     * never as ordinary success-shaped strings.
     */
    fun executeTyped(actionName: String, parameters: Map<String, Any?>): DeviceActionResult {
        return try {
        when (actionName) {
            "device.torch.set" -> {
                val enabled = parameters["enabled"] as? Boolean
                    ?: return DeviceActionResult.failure("Torch enabled flag is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return DeviceActionResult.unsupported(
                    "এই ফোনে flash/torch hardware পাওয়া যায়নি।",
                    DeviceActionErrorCode.HARDWARE_UNAVAILABLE
                )
                cameraManager.setTorchMode(cameraId, enabled)
                DeviceActionResult.success(
                    if (enabled) "টর্চ চালু করার অনুরোধ পাঠানো হয়েছে।" else "টর্চ বন্ধ করার অনুরোধ পাঠানো হয়েছে।",
                    mapOf("device.action" to actionName, "torch.cameraId" to cameraId, "torch.expected" to enabled.toString())
                )
            }
            "device.volume.adjust", "device.volume.set_percent" -> {
                val streamName = parameters["stream"]?.toString() ?: "MEDIA"
                val stream = runCatching { VolumeStream.valueOf(streamName) }.getOrNull()
                    ?: return DeviceActionResult.failure("Unknown volume stream: $streamName", DeviceActionErrorCode.INVALID_ARGUMENT)
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val before = audio.getStreamVolume(stream.streamId)
                val maximum = audio.getStreamMaxVolume(stream.streamId)
                if (maximum <= 0) return DeviceActionResult.unavailable(
                    "Android did not expose a usable volume range for ${stream.label}.",
                    DeviceActionErrorCode.HARDWARE_UNAVAILABLE
                )
                val direction = parameters["direction"]?.toString()
                var requestedPercent: Int? = null
                if (actionName == "device.volume.set_percent") {
                    requestedPercent = (parameters["percent"] as? Number)?.toInt()
                        ?: parameters["percent"]?.toString()?.toIntOrNull()
                        ?: return DeviceActionResult.failure("Volume percent is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                    if (requestedPercent !in 0..100) return DeviceActionResult.failure(
                        "Volume percent must be between 0 and 100.", DeviceActionErrorCode.INVALID_ARGUMENT
                    )
                    val target = (maximum * requestedPercent / 100f).toInt().coerceIn(0, maximum)
                    audio.setStreamVolume(stream.streamId, target, AudioManager.FLAG_SHOW_UI)
                } else {
                    val adjustment = when {
                        direction.equals("UP", true) -> AudioManager.ADJUST_RAISE
                        direction.equals("DOWN", true) -> AudioManager.ADJUST_LOWER
                        else -> return DeviceActionResult.failure("Volume direction must be UP or DOWN.", DeviceActionErrorCode.INVALID_ARGUMENT)
                    }
                    audio.adjustStreamVolume(stream.streamId, adjustment, AudioManager.FLAG_SHOW_UI)
                }
                val after = audio.getStreamVolume(stream.streamId)
                val details = mutableMapOf(
                    "device.action" to actionName,
                    "volume.stream" to stream.name,
                    "volume.before" to before.toString(),
                    "volume.after" to after.toString(),
                    "volume.max" to maximum.toString()
                )
                if (requestedPercent != null) details["volume.requestedPercent"] = requestedPercent.toString()
                if (direction != null) details["volume.direction"] = direction.uppercase()
                DeviceActionResult.success(
                    if (requestedPercent != null) "${stream.label} volume ${requestedPercent}% করার অনুরোধ পাঠানো হয়েছে।"
                    else "${stream.label} volume adjustment request পাঠানো হয়েছে।",
                    details
                )
            }
            "device.app.launch" -> {
                val appName = parameters["appName"]?.toString()?.trim().orEmpty()
                val packageName = parameters["packageName"]?.toString()?.trim().orEmpty()
                val pm = context.packageManager
                val candidates = if (packageName.isNotBlank()) {
                    listOfNotNull(pm.getLaunchIntentForPackage(packageName)?.let { intent ->
                        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
                        Triple(packageName, intent, label)
                    })
                } else {
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(launcher, 0).mapNotNull { info ->
                        val activity = info.activityInfo ?: return@mapNotNull null
                        val label = runCatching { activity.applicationInfo.loadLabel(pm).toString() }.getOrDefault(activity.packageName)
                        Triple(activity.packageName, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(activity.packageName, activity.name), label)
                    }.distinctBy { it.first }
                        .let { apps ->
                            val exact = apps.filter { it.third.equals(appName, ignoreCase = true) }
                            if (exact.isNotEmpty()) exact else apps.filter { it.third.contains(appName, ignoreCase = true) }
                        }
                }
                if (candidates.isEmpty()) return DeviceActionResult.unavailable(
                    if (packageName.isNotBlank()) "Target app is not installed or is not launchable in this profile."
                    else "'$appName' নামে কোনো launchable app পাওয়া যায়নি.",
                    DeviceActionErrorCode.APP_NOT_INSTALLED
                )
                if (candidates.size > 1) return DeviceActionResult.unavailable(
                    "'$appName' নামে একাধিক app মিলেছে। একটি নির্দিষ্ট app নির্বাচন করো.",
                    DeviceActionErrorCode.APP_AMBIGUOUS
                )
                val candidate = candidates.single()
                candidate.second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(candidate.second)
                DeviceActionResult.success(
                    "${candidate.third} খোলার intent পাঠানো হয়েছে।",
                    mapOf("device.action" to actionName, "app.package" to candidate.first)
                )
            }
            "device.settings.open" -> {
                val action = parameters["settingsAction"]?.toString().orEmpty()
                val label = parameters["label"]?.toString()?.ifBlank { "Settings" } ?: "Settings"
                if (action.isBlank()) return DeviceActionResult.failure("Settings action is missing.", DeviceActionErrorCode.INVALID_ARGUMENT)
                startExternalIntent(Intent(action), actionName, "$label খুলতে intent পাঠানো হয়েছে।")
            }
            "device.camera.open" ->
                startExternalIntent(Intent("android.media.action.IMAGE_CAPTURE"), actionName, "ক্যামেরা খোলার intent পাঠানো হয়েছে।")
            "device.dialer.open" -> {
                val number = parameters["phoneNumber"]?.toString().orEmpty()
                if (number.isBlank()) return DeviceActionResult.failure("Phone number is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                startExternalIntent(intent, actionName, "ডায়ালার খোলার intent পাঠানো হয়েছে; ERA কল করেনি।")
            }
            "device.sms.create_draft" -> {
                val message = parameters["message"]?.toString().orEmpty()
                if (message.isBlank()) return DeviceActionResult.failure("SMS draft text is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:")
                    putExtra("sms_body", message)
                }
                startExternalIntent(intent, actionName, "SMS draft handoff শুরু হয়েছে; ERA পাঠায়নি।")
            }
            "device.whatsapp.share_draft" -> {
                val message = parameters["message"]?.toString().orEmpty()
                if (message.isBlank()) return DeviceActionResult.failure("WhatsApp draft text is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                var result: DeviceActionResult? = null
                for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage(pkg)
                        putExtra(Intent.EXTRA_TEXT, message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        result = DeviceActionResult.success(
                            "WhatsApp draft handoff শুরু হয়েছে; final Send user নিজে করবে।",
                            mapOf("device.action" to actionName, "app.package" to pkg)
                        )
                        break
                    }
                }
                result ?: DeviceActionResult.unavailable("WhatsApp বা WhatsApp Business launchable নয়।", DeviceActionErrorCode.APP_NOT_INSTALLED)
            }
            "device.web.search" -> {
                val query = parameters["query"]?.toString().orEmpty()
                if (query.isBlank()) return DeviceActionResult.failure("Search query is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                val uri = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", query).build()
                startExternalIntent(Intent(Intent.ACTION_VIEW, uri), actionName, "ব্রাউজার সার্চ খুলেছে।")
            }
            "device.maps.search" -> {
                val query = parameters["query"]?.toString().orEmpty()
                if (query.isBlank()) return DeviceActionResult.failure("Map query is required.", DeviceActionErrorCode.INVALID_ARGUMENT)
                val uri = Uri.parse("geo:0,0").buildUpon().appendQueryParameter("q", query).build()
                startExternalIntent(Intent(Intent.ACTION_VIEW, uri), actionName, "ম্যাপ সার্চ খুলেছে।")
            }
            "device.website.open" -> {
                val url = parameters["url"]?.toString().orEmpty()
                if (!url.startsWith("https://", ignoreCase = true)) return DeviceActionResult.failure("Only HTTPS navigation is supported.", DeviceActionErrorCode.INVALID_ARGUMENT)
                startExternalIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), actionName, "HTTPS navigation intent পাঠানো হয়েছে।")
            }
            else -> DeviceActionResult.unsupported(
                "Unsupported Android device action: $actionName",
                DeviceActionErrorCode.UNSUPPORTED_ACTION
            )
        }
    } catch (error: SecurityException) {
        DeviceActionResult.failure(
            "Android permission/security policy blocked the action: ${error.message ?: "permission denied"}",
            DeviceActionErrorCode.PERMISSION_DENIED
        )
    } catch (error: android.content.ActivityNotFoundException) {
        DeviceActionResult.unavailable(
            "No installed activity can handle this action: ${error.message ?: "activity not found"}",
            DeviceActionErrorCode.ACTIVITY_NOT_FOUND
        )
    } catch (error: IllegalArgumentException) {
        DeviceActionResult.failure(
            "Invalid device action argument: ${error.message ?: "invalid argument"}",
            DeviceActionErrorCode.INVALID_ARGUMENT
        )
    } catch (error: Exception) {
        DeviceActionResult.failure(
            "Android device action failed: ${error.message ?: error::class.java.simpleName}",
            DeviceActionErrorCode.PLATFORM_ERROR
        )
        }
    }

    private fun startExternalIntent(intent: Intent, actionName: String, message: String): DeviceActionResult {
        if (intent.resolveActivity(context.packageManager) == null) {
            return DeviceActionResult.unavailable("No installed app can handle $actionName.", DeviceActionErrorCode.ACTIVITY_NOT_FOUND)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return DeviceActionResult.success(message, mapOf("device.action" to actionName))
    }

    fun close() {
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        torchStates.clear()
    }

    /** Captures current system values after dispatch; it does not trust the operation's message. */
    fun verificationMetadata(actionName: String, previous: Map<String, String>): Map<String, String> {
        if (actionName != "device.torch.set") return previous
        val cameraId = previous["torch.cameraId"] ?: return previous
        val actual = torchStates[cameraId] ?: return previous
        return previous + ("torch.enabled" to actual.toString())
    }

    fun setTorch(enabled: Boolean): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "এই ফোনে flash/torch পাওয়া যায়নি।"

        cameraManager.setTorchMode(cameraId, enabled)
        return if (enabled) "টর্চ চালু হয়েছে।" else "টর্চ বন্ধ হয়েছে।"
    }

    fun adjustVolume(increase: Boolean): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        return if (increase) "ভলিউম বাড়ানো হয়েছে।" else "ভলিউম কমানো হয়েছে।"
    }

    fun setMediaVolumePercent(percent: Int): String = setVolumePercent(VolumeStream.MEDIA, percent)

    fun adjustVolume(stream: VolumeStream, direction: VolumeDirection): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val adjust = if (direction == VolumeDirection.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(stream.streamId, adjust, AudioManager.FLAG_SHOW_UI)
        val verb = if (direction == VolumeDirection.UP) "বাড়ানো" else "কমানো"
        return "${stream.label} ভলিউম $verb হয়েছে।"
    }

    fun setVolumePercent(stream: VolumeStream, percent: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val safePercent = percent.coerceIn(0, 100)
        val max = audioManager.getStreamMaxVolume(stream.streamId)
        val target = (max * safePercent / 100f).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(stream.streamId, target, AudioManager.FLAG_SHOW_UI)
        return "${stream.label} ভলিউম ${safePercent}% করা হয়েছে।"
    }

    fun launchApp(packageNames: List<String>, displayName: String): String {
        packageNames.forEach { packageName ->
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return "$displayName খোলা হচ্ছে।"
            }
        }
        return "$displayName ইনস্টল করা নেই অথবা এই Android profile থেকে খোলা যাচ্ছে না।"
    }


    fun launchAppByName(appName: String): String {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.let { activity ->
                val label = activity.applicationInfo.loadLabel(context.packageManager).toString()
                Triple(activity.packageName, activity.name, label)
            } }
            .distinctBy { it.first }
        val exact = candidates.filter { it.third.equals(appName, ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else candidates.filter {
            it.third.contains(appName, ignoreCase = true)
        }
        if (matches.isEmpty()) return "'$appName' নামে কোনো launchable app পাওয়া যায়নি।"
        if (matches.size > 1) return "'$appName' নামে একাধিক app মিলেছে। App-এর পুরো নাম দিয়ে আবার বলো।"
        val match = matches.single()
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(match.first, match.second)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "${match.third} খোলা হচ্ছে।"
        } catch (_: Exception) {
            "${match.third} খোলা যায়নি।"
        }
    }

    fun openSettings(action: String, label: String): String = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "$label খোলা হচ্ছে।"
    } catch (_: Exception) {
        "$label খোলা যায়নি।"
    }

    fun openCamera(): String = try {
        context.startActivity(Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ক্যামেরা খোলা হচ্ছে।"
    } catch (_: Exception) {
        "ক্যামেরা অ্যাপ খোলা যায়নি।"
    }

    fun openDialer(number: String): String {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.fromParts("tel", number, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "ডায়ালার খোলা হয়েছে। কল করার আগে নম্বরটি যাচাই করো।"
    }

    fun shareWhatsAppDraft(message: String): String {
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (packageName in packages) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(packageName)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "WhatsApp-এ মেসেজের draft খোলা হয়েছে। সঠিক contact বেছে নিয়ে নিজে Send করবে।"
            } catch (_: Exception) {
                // Try the WhatsApp Business package if the regular app is absent.
            }
        }
        return "WhatsApp খোলা যায়নি। অ্যাপটি ইনস্টল আছে কি না দেখো।"
    }

    fun createSmsDraft(message: String): String = try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "SMS draft খোলা হয়েছে। recipient যাচাই করে নিজে Send করবে।"
    } catch (_: Exception) {
        "SMS অ্যাপ খোলা যায়নি।"
    }

    fun openWebSearch(query: String): String = try {
        val uri = Uri.parse("https://www.google.com/search").buildUpon()
            .appendQueryParameter("q", query)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ব্রাউজারে সার্চ খোলা হচ্ছে: $query"
    } catch (_: Exception) {
        "ওয়েব সার্চ খোলা যায়নি।"
    }

    fun openMapSearch(query: String): String = try {
        val uri = Uri.parse("geo:0,0").buildUpon().appendQueryParameter("q", query).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ম্যাপে খোঁজা হচ্ছে: $query"
    } catch (_: Exception) {
        "ম্যাপ অ্যাপ খোলা যায়নি।"
    }

    fun openWebsite(url: String): String = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ওয়েবসাইট খোলা হচ্ছে: $url"
    } catch (_: Exception) {
        "ওয়েবসাইট খোলা যায়নি।"
    }

    fun readBattery(): String {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) "ব্যাটারি ${level}% আছে।" else "ব্যাটারির তথ্য পাওয়া যায়নি।"
    }

    fun readDateTime(): String {
        val now = LocalDateTime.now()
        val date = now.format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a"))
        return "আজ $date, এখন $time।"
    }

    fun execute(command: AssistantCommand): String = when (command) {
        is AssistantCommand.SetTorch -> setTorch(command.enabled)
        AssistantCommand.VolumeUp -> adjustVolume(true)
        AssistantCommand.VolumeDown -> adjustVolume(false)
        is AssistantCommand.SetMediaVolumePercent -> setMediaVolumePercent(command.percent)
        is AssistantCommand.AdjustVolume -> adjustVolume(command.stream, command.direction)
        is AssistantCommand.SetVolumePercent -> setVolumePercent(command.stream, command.percent)
        is AssistantCommand.LaunchApp -> launchApp(command.app.packageNames, command.app.displayName)
        is AssistantCommand.LaunchAppByName -> launchAppByName(command.appName)
        is AssistantCommand.OpenSettings -> openSettings(command.action.intentAction, command.action.label)
        AssistantCommand.OpenCamera -> openCamera()
        is AssistantCommand.DialNumber -> openDialer(command.number)
        is AssistantCommand.OpenWebSearch -> openWebSearch(command.query)
        is AssistantCommand.OpenMapSearch -> openMapSearch(command.query)
        is AssistantCommand.OpenWebsite -> openWebsite(command.url)
        is AssistantCommand.CreateSmsDraft -> createSmsDraft(command.text)
        is AssistantCommand.ShareTextToWhatsApp -> shareWhatsAppDraft(command.text)
        AssistantCommand.ReadBattery -> readBattery()
        AssistantCommand.ReadDateTime -> readDateTime()
        AssistantCommand.PickContactForDial -> "Contacts picker খুলে contact বেছে নাও; ERA শুধু dial screen খুলবে, নিজে কল করবে না।"
        is AssistantCommand.RunSequence -> "একাধিক command sequence activity থেকে চালাতে হবে।"
        AssistantCommand.CancelCurrentTask -> "চলমান task cancel করার অনুরোধ পাওয়া গেছে।"
        is AssistantCommand.UnsupportedComplexTask ->
            "এই কাজটি AI task planner-এ পাঠাতে হবে। আগে পরিকল্পনা preview করো; এখনো কোনো app action চালানো হয়নি।"
        AssistantCommand.Help -> "বলতে পারো: টর্চ অন, ভলিউম ৫০ শতাংশ, YouTube খোলো, Telegram খোলো, Camera খোলো, ব্যাটারি কত, Chrome খোলো বা Wi-Fi settings খোলো।"
    }
}
