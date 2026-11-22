package com.eraassistant.mobile.search

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.eraassistant.mobile.screen.VisualFrameStore
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors

/** Streams a user-captured frame to a user-selected share target without writing it to disk. */
class VisualFrameContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = if (uri.lastPathSegment == FRAME_NAME) "image/jpeg" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.lastPathSegment != FRAME_NAME) throw FileNotFoundException("Unsupported visual-frame request.")
        val frameCopy: Bitmap = VisualFrameStore.copyCurrentBitmap()
            ?: throw FileNotFoundException("No user-captured visual frame is available.")
        val pipe = ParcelFileDescriptor.createPipe()
        streamExecutor.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    if (!frameCopy.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) throw IOException("Could not encode the visual frame.")
                }
            } catch (_: Exception) {
                runCatching { pipe[1].close() }
            } finally {
                runCatching { frameCopy.recycle() }
            }
        }
        return pipe[0]
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider.")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider.")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider.")

    companion object {
        const val FRAME_NAME = "current.jpg"
        private const val JPEG_QUALITY = 88
        private val streamExecutor = Executors.newCachedThreadPool()
        fun currentFrameUri(applicationId: String): Uri = Uri.parse("content://$applicationId.visualframe/$FRAME_NAME")
    }
}
