package com.eraassistant.mobile.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import android.provider.ContactsContract
import com.eraassistant.mobile.accessibility.EraAccessibilityService
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.storage.SafStorageManager

/** Reports capabilities that are actually available on this device at runtime. */
class AndroidCapabilityDiscovery(private val context: Context) {
    fun discoverForGoal(goal: String): Set<TaskCapability> {
        val lower = goal.lowercase()
        val targetPackages = buildSet {
            if (containsAny(lower, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ") &&
                !isNegatedTarget(lower, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ")
            ) {
                add("com.whatsapp")
                add("com.whatsapp.w4b")
            }
            if (containsAny(lower, "instagram", "ইনস্টাগ্রাম") &&
                !isNegatedTarget(lower, "instagram", "ইনস্টাগ্রাম")
            ) add("com.instagram.android")
            if (containsAny(lower, "gmail", "email", "mail", "ইমেইল") &&
                !isNegatedTarget(lower, "gmail", "email", "mail")
            ) add("com.google.android.gm")
            if (containsAny(lower, "youtube", "ইউটিউব") &&
                !isNegatedTarget(lower, "youtube")
            ) add("com.google.android.youtube")
            if (containsAny(lower, "chrome", "ক্রোম", "browser", "ব্রাউজার") &&
                !isNegatedTarget(lower, "chrome", "browser")
            ) add("com.android.chrome")
            if (containsAny(lower, "telegram") && !isNegatedTarget(lower, "telegram")) {
                add("org.telegram.messenger")
            }
        }
        return discover(targetPackages)
    }

    fun discover(targetAppPackage: String? = null): Set<TaskCapability> =
        discover(targetAppPackage?.let(::setOf).orEmpty())

    private fun discover(targetAppPackages: Set<String>): Set<TaskCapability> = buildSet {
        val pm = context.packageManager
        if (targetAppPackages.any(::isPackageLaunchable)) {
            add(TaskCapability.APP_LAUNCH)
        }

        val consented = context.getSharedPreferences("era_preferences", Context.MODE_PRIVATE)
            .getBoolean("accessibility_consent_accepted", false)
        if (consented && isEraAccessibilityServiceEnabled()) {
            add(TaskCapability.SCREEN_OBSERVATION)
            add(TaskCapability.STRUCTURED_UI_ACTION)
            add(TaskCapability.TEXT_ENTRY)
        }

        val picker = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        if (picker.resolveActivity(pm) != null) add(TaskCapability.CONTACT_PICKER)

        val mediaPicker = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        if (mediaPicker.resolveActivity(pm) != null) add(TaskCapability.MEDIA_PICKER)

        val whatsapp = listOf("com.whatsapp", "com.whatsapp.w4b").any(::isPackageLaunchable)
        if (whatsapp) add(TaskCapability.SHARE_DRAFT)
        if (SafStorageManager(context).hasReadAccess()) add(TaskCapability.FILE_STORAGE)

        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
        if (web.resolveActivity(pm) != null) add(TaskCapability.WEB_NAVIGATION)

        // These are available only when the user can review and approve actions in the app.
        add(TaskCapability.USER_CONFIRMATION)
    }

    private fun containsAny(text: String, vararg phrases: String): Boolean = phrases.any(text::contains)

    private fun isNegatedTarget(text: String, vararg targets: String): Boolean {
        val prefixes = listOf("don't use", "do not use", "never use", "don't open", "do not open", "without")
        if (targets.any { target -> prefixes.any { "$it $target" in text } }) return true
        return targets.any { target ->
            listOf("ব্যবহার করো না", "ব্যবহার করব না", "খুলবে না").any { "$target $it" in text }
        }
    }

    fun isPackageLaunchable(packageName: String): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }

    fun isEraAccessibilityServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!manager.isEnabled) return false
        val expected = ComponentName(context, EraAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) == expected } }
    }
}
