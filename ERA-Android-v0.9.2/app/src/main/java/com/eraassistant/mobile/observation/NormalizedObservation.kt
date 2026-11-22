package com.eraassistant.mobile.observation

/**
 * Origin of an observation.
 */
enum class ObservationSource {
    ANDROID_ACCESSIBILITY,
    WEBVIEW_DOM,
    CHROME_ACCESSIBILITY,
    VISUAL_CAMERA,
    VISUAL_SCREENSHOT,
    DEVICE_SYSTEM_STATE,
    API_RESPONSE,
    SYNTHETIC_TEST
}

/**
 * Normalized UI or system element representation.
 */
data class NormalizedElement(
    val elementId: String,
    val role: String,
    val label: String? = null,
    val text: String? = null,
    val resourceId: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocused: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val isPassword: Boolean = false,
    val depth: Int = 0,
    val parentId: String? = null,
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(elementId.isNotBlank() && elementId.length <= MAX_ID_LENGTH)
        require(role.length <= MAX_ROLE_LENGTH)
        require(label == null || label.length <= MAX_TEXT_LENGTH)
        require(text == null || text.length <= MAX_TEXT_LENGTH)
        require(resourceId == null || resourceId.length <= MAX_RESOURCE_ID_LENGTH)
        require(parentId == null || parentId.length <= MAX_ID_LENGTH)
        require(depth in 0..MAX_DEPTH)
        require(attributes.size <= MAX_ATTRIBUTES)
        require(attributes.all { (key, value) ->
            key.isNotBlank() && key.length <= MAX_ATTRIBUTE_KEY_LENGTH && value.length <= MAX_ATTRIBUTE_VALUE_LENGTH
        })
    }

    companion object {
        const val MAX_ID_LENGTH = 160
        const val MAX_ROLE_LENGTH = 100
        const val MAX_TEXT_LENGTH = 500
        const val MAX_RESOURCE_ID_LENGTH = 300
        const val MAX_DEPTH = 100
        const val MAX_ATTRIBUTES = 16
        const val MAX_ATTRIBUTE_KEY_LENGTH = 80
        const val MAX_ATTRIBUTE_VALUE_LENGTH = 500
    }
}

/**
 * Common, bounded, normalized observation model.
 */
data class NormalizedObservation(
    val observationId: String,
    val source: ObservationSource,
    val capturedAtEpochMs: Long,
    val targetPackageOrUrl: String? = null,
    val titleOrHeader: String? = null,
    val summary: String = "",
    val elements: List<NormalizedElement> = emptyList(),
    val totalElementsCount: Int = elements.size,
    val isTruncated: Boolean = false,
    val rawMetadata: Map<String, String> = emptyMap()
) {
    init {
        require(observationId.isNotBlank() && observationId.length <= 160) {
            "Observation ID must be between 1 and 160 characters."
        }
        require(elements.size <= MAX_ELEMENTS) {
            "Elements size must not exceed $MAX_ELEMENTS."
        }
        require(capturedAtEpochMs > 0L) { "Observation timestamp must be a positive epoch value." }
        require(targetPackageOrUrl == null || targetPackageOrUrl.length <= MAX_TARGET_LENGTH)
        require(titleOrHeader == null || titleOrHeader.length <= MAX_TITLE_LENGTH)
        require(summary.length <= MAX_SUMMARY_LENGTH)
        require(rawMetadata.size <= MAX_METADATA_ENTRIES)
        require(rawMetadata.all { (key, value) ->
            key.isNotBlank() && key.length <= MAX_METADATA_KEY_LENGTH && value.length <= MAX_METADATA_VALUE_LENGTH
        })
        val payloadChars = summary.length + (targetPackageOrUrl?.length ?: 0) +
            (titleOrHeader?.length ?: 0) + rawMetadata.entries.sumOf { it.key.length + it.value.length } +
            elements.sumOf { element ->
                element.elementId.length + element.role.length +
                    (element.label?.length ?: 0) + (element.text?.length ?: 0) +
                    (element.resourceId?.length ?: 0) + (element.parentId?.length ?: 0) +
                    element.attributes.entries.sumOf { it.key.length + it.value.length }
            }
        require(payloadChars <= MAX_PAYLOAD_CHARS) {
            "Observation payload exceeds the $MAX_PAYLOAD_CHARS character budget."
        }
    }

    fun isStale(maxAgeMs: Long = 15_000L, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        maxAgeMs < 0L || capturedAtEpochMs <= 0L ||
            capturedAtEpochMs > nowEpochMs ||
            (nowEpochMs - capturedAtEpochMs) > maxAgeMs

    fun findElementById(id: String): NormalizedElement? =
        elements.firstOrNull { it.elementId == id }

    fun findElementsByLabel(query: String, exact: Boolean = false): List<NormalizedElement> =
        elements.filter { element ->
            val candidate = element.label ?: element.text ?: ""
            if (exact) candidate.equals(query, ignoreCase = true)
            else candidate.contains(query, ignoreCase = true)
        }

    fun toPlannerContext(maxNodes: Int = 50): String = buildString {
        val boundedNodes = maxNodes.coerceIn(0, MAX_ELEMENTS)
        appendLine("Observation [$source] at $capturedAtEpochMs (id: ${observationId.take(12)}...)")
        targetPackageOrUrl?.take(500)?.let { appendLine("Target: $it") }
        titleOrHeader?.take(300)?.let { appendLine("Title: $it") }
        if (summary.isNotBlank()) appendLine("Summary: ${summary.take(500)}")
        appendLine("Interactive Elements (${elements.size.coerceAtMost(boundedNodes)}/${elements.size}):")
        elements.take(boundedNodes).forEach { el ->
            if (el.isClickable || el.isEditable || el.isScrollable || el.isFocused) {
                val flags = buildList {
                    if (el.isClickable) add("click")
                    if (el.isEditable) add("edit")
                    if (el.isScrollable) add("scroll")
                    if (el.isFocused) add("focused")
                    if (el.isPassword) add("password")
                }.joinToString(",")
                val desc = (el.label ?: el.text ?: el.resourceId ?: "unnamed").take(220)
                appendLine(" - [${el.elementId.take(80)}] <${el.role.take(60)}> \"$desc\" ($flags)")
            }
        }
    }.trim()

    companion object {
        const val MAX_ELEMENTS = 500
        const val MAX_TARGET_LENGTH = 2_048
        const val MAX_TITLE_LENGTH = 512
        const val MAX_SUMMARY_LENGTH = 2_000
        const val MAX_METADATA_ENTRIES = 32
        const val MAX_METADATA_KEY_LENGTH = 80
        const val MAX_METADATA_VALUE_LENGTH = 500
        const val MAX_PAYLOAD_CHARS = 64_000

        /**
         * Keeps source order while fitting nodes into the aggregate character budget.
         * The first node that cannot fit ends the list; callers mark the observation truncated.
         */
        fun boundElements(
            candidates: List<NormalizedElement>,
            summary: String,
            target: String? = null,
            title: String? = null,
            metadata: Map<String, String> = emptyMap()
        ): List<NormalizedElement> {
            var used = summary.length + (target?.length ?: 0) + (title?.length ?: 0) +
                metadata.entries.sumOf { it.key.length + it.value.length }
            if (used >= MAX_PAYLOAD_CHARS) return emptyList()
            val bounded = ArrayList<NormalizedElement>(candidates.size.coerceAtMost(MAX_ELEMENTS))
            for (element in candidates.take(MAX_ELEMENTS)) {
                val cost = element.elementId.length + element.role.length +
                    (element.label?.length ?: 0) + (element.text?.length ?: 0) +
                    (element.resourceId?.length ?: 0) + (element.parentId?.length ?: 0) +
                    element.attributes.entries.sumOf { it.key.length + it.value.length }
                if (used + cost > MAX_PAYLOAD_CHARS) break
                bounded += element
                used += cost
            }
            return bounded
        }

        fun fromSystemState(
            summary: String,
            details: Map<String, String> = emptyMap()
        ): NormalizedObservation = NormalizedObservation(
            observationId = "obs_sys_${System.currentTimeMillis()}_${(1000..9999).random()}",
            source = ObservationSource.DEVICE_SYSTEM_STATE,
            capturedAtEpochMs = System.currentTimeMillis(),
            summary = summary.take(MAX_SUMMARY_LENGTH),
            rawMetadata = details.entries.take(MAX_METADATA_ENTRIES).associate { (key, value) ->
                key.take(MAX_METADATA_KEY_LENGTH) to value.take(MAX_METADATA_VALUE_LENGTH)
            }
        )
    }
}
