package com.eraassistant.mobile.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.core.VolumeDirection
import com.eraassistant.mobile.core.VolumeStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DeviceController(private val context: Context) {

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
