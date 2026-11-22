package com.eraassistant.mobile.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Read-only access to a folder explicitly selected by the user through ACTION_OPEN_DOCUMENT_TREE.
 * The app does not request broad storage permissions and never walks arbitrary filesystem paths.
 */
class SafStorageManager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun persistSelectedTree(uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "A Storage Access Framework tree URI is required." }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        val saved = preferences.edit().putString(KEY_TREE_URI, uri.toString()).commit()
        check(saved) { "The folder grant could not be saved." }
    }

    @Synchronized
    fun selectedTreeUri(): Uri? {
        val raw = preferences.getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!granted) {
            preferences.edit().remove(KEY_TREE_URI).commit()
            return null
        }
        return uri
    }

    fun hasReadAccess(): Boolean = selectedTreeUri() != null

    @Synchronized
    fun clearSelectedTree() {
        val uri = selectedTreeUri()
        if (uri != null) {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        preferences.edit().remove(KEY_TREE_URI).commit()
    }

    fun rootName(): String? {
        val tree = selectedTreeUri() ?: return null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        return queryDisplayName(documentUri)
    }

    fun listChildren(
        parentDocumentId: String? = null,
        limit: Int = SafStoragePolicy.MAX_LIST_ITEMS
    ): List<StorageDocument> {
        val tree = selectedTreeUri() ?: throw SecurityException("Choose a folder in ERA before browsing files.")
        val safeLimit = limit.coerceIn(1, SafStoragePolicy.MAX_LIST_ITEMS)
        val parentId = parentDocumentId?.takeIf(SafStoragePolicy::validateDocumentId)
            ?: DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<StorageDocument>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext() && result.size < safeLimit) {
                val documentId = cursor.getString(idIndex).orEmpty()
                if (!SafStoragePolicy.validateDocumentId(documentId)) continue
                val mime = cursor.getString(mimeIndex).orEmpty().take(120)
                val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex).coerceAtLeast(0L)
                val modified = if (cursor.isNull(modifiedIndex)) null else cursor.getLong(modifiedIndex).coerceAtLeast(0L)
                result += StorageDocument(
                    documentId = documentId,
                    displayName = SafStoragePolicy.safeDisplayName(cursor.getString(nameIndex)),
                    mimeType = mime,
                    sizeBytes = size,
                    lastModifiedEpochMs = modified,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        } ?: throw IOException("The selected document provider did not return a directory listing.")
        return result.sortedWith(compareByDescending<StorageDocument> { it.isDirectory }.thenBy { it.displayName.lowercase() })
    }

    fun readText(documentId: String): StorageTextPreview {
        require(SafStoragePolicy.validateDocumentId(documentId)) { "The selected document reference is invalid." }
        val tree = selectedTreeUri() ?: throw SecurityException("The folder grant is no longer available.")
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val mime = resolver.getType(documentUri).orEmpty().take(120)
        require(SafStoragePolicy.isReadableTextMime(mime)) {
            "Only plain text, Markdown, CSV, JSON, XML and YAML documents can be previewed."
        }
        val name = queryDisplayName(documentUri).orEmpty()
        val bytes = resolver.openInputStream(documentUri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= SafStoragePolicy.MAX_READ_BYTES) {
                    "The selected document exceeds the 64 KB preview limit."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("The selected document could not be opened.")
        val decoded = bytes.toString(Charsets.UTF_8)
        val (preview, truncated) = SafStoragePolicy.preview(decoded)
        return StorageTextPreview(
            documentId = documentId,
            displayName = SafStoragePolicy.safeDisplayName(name),
            mimeType = mime,
            text = preview,
            truncated = truncated,
            bytesRead = bytes.size
        )
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        private const val PREFS_NAME = "era_saf_storage"
        private const val KEY_TREE_URI = "selected_tree_uri"
    }
}
