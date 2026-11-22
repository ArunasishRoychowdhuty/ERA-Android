package com.eraassistant.mobile.accessibility

/**
 * Policy for the separate Chrome UI target. This is Android accessibility-tree
 * automation only; it does not expose Chrome DOM, CDP or browser profile data.
 */
object ExternalChromeAgentPolicy {
    const val PREFS_NAME = "era_external_chrome"
    const val KEY_STATUS = "status"
    const val KEY_PACKAGE = "package_name"
    const val KEY_UPDATED_AT = "updated_at"

    val supportedPackages: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev"
    )

    private val urlPattern = Regex("""(?i)https?://[^\s<>"']+""")
    private val addressBarIds = listOf("url_bar", "location_bar", "omnibox", "urlbar")

    fun isSupportedPackage(packageName: String?): Boolean =
        packageName != null && packageName in supportedPackages

    fun isAddressBarResourceId(resourceId: String?): Boolean {
        val value = resourceId.orEmpty().lowercase()
        return value.isNotBlank() && addressBarIds.any(value::contains)
    }

    /** Never export a live editable value, password value, or Chrome address-bar value. */
    fun shouldOmitNodeText(
        packageName: String?,
        editable: Boolean,
        password: Boolean,
        resourceId: String?,
        text: String?
    ): Boolean {
        if (password) return true
        return isSupportedPackage(packageName) &&
            (editable || isAddressBarResourceId(resourceId) || looksLikeFullUrl(text))
    }

    /** Preserve the URL destination path, but discard query strings and fragments. */
    fun sanitizeVisibleText(packageName: String?, value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val safe = if (isSupportedPackage(packageName)) {
            urlPattern.replace(raw) { match ->
                match.value.substringBefore('?').substringBefore('#')
            }
        } else raw
        return safe.take(300).takeIf(String::isNotBlank)
    }

    private fun looksLikeFullUrl(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        return text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("www.", ignoreCase = true)
    }
}
