package com.eraassistant.mobile.storage

/** Conservative policy for content exposed through a user-granted Storage Access Framework tree. */
object SafStoragePolicy {
    const val MAX_LIST_ITEMS = 100
    const val MAX_READ_BYTES = 64 * 1024
    const val MAX_PREVIEW_CHARS = 3_500
    const val MAX_NAME_LENGTH = 180

    private val readableMimeTypes = setOf(
        "text/plain",
        "text/markdown",
        "text/csv",
        "application/json",
        "application/xml",
        "text/xml",
        "application/yaml",
        "text/yaml"
    )

    fun isReadableTextMime(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.lowercase() in readableMimeTypes

    fun safeDisplayName(value: String?): String {
        val clean = value.orEmpty()
            .filterNot { it.isISOControl() || it == '/' || it == '\\' }
            .trim()
            .take(MAX_NAME_LENGTH)
        return clean.ifBlank { "Unnamed document" }
    }

    fun preview(content: String): Pair<String, Boolean> =
        content.take(MAX_PREVIEW_CHARS) to (content.length > MAX_PREVIEW_CHARS)

    fun validateDocumentId(documentId: String): Boolean =
        documentId.isNotBlank() && documentId.length <= 1_024 &&
            documentId.none { it.isISOControl() }
}

data class StorageDocument(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    val isDirectory: Boolean
)

data class StorageTextPreview(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
    val bytesRead: Int
)
