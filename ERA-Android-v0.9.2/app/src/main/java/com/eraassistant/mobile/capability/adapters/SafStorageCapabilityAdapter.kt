package com.eraassistant.mobile.capability.adapters

import android.os.SystemClock
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.storage.SafStorageManager

/** Read-only Storage Access Framework capability, scoped to one user-selected tree grant. */
class SafStorageCapabilityAdapter(
    private val storage: SafStorageManager
) : CapabilityAdapter {
    override val descriptor = CapabilityDescriptor(
        id = CapabilityId.FILE_STORAGE,
        name = "Scoped Android File Access",
        description = "Lists a user-selected folder and previews bounded text files through Storage Access Framework.",
        category = CapabilityCategory.FILE_STORAGE,
        supportedActions = setOf(ACTION_LIST, ACTION_READ),
        requiredPermissions = setOf("User-selected SAF folder read grant"),
        executionConstraints = setOf(
            "No broad storage permission",
            "Read-only in v0.9.0",
            "Maximum 100 listed items",
            "Text preview limited to 64 KB input and 3500 output characters",
            "No raw filesystem paths"
        ),
        worksOffline = true,
        actionSchemas = CapabilitySchemas.fileStorage()
    )

    override suspend fun checkAvailability(): CapabilityAvailability =
        if (storage.hasReadAccess()) CapabilityAvailability.AVAILABLE
        else CapabilityAvailability.missing("Choose a folder using Android's system folder picker first.")

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        if (!storage.hasReadAccess()) {
            return ExecutionError(
                ExecutionErrorCategory.PERMISSION_DENIED,
                "A user-selected folder read grant is required."
            )
        }
        return when (request.actionName) {
            ACTION_LIST, ACTION_READ -> null
            else -> ExecutionError(
                ExecutionErrorCategory.UNSUPPORTED_CAPABILITY,
                "Unsupported SAF operation."
            )
        }
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            when (request.actionName) {
                ACTION_LIST -> {
                    val items = storage.listChildren(
                        parentDocumentId = request.stringParam("parentDocumentId").ifBlank { null },
                        limit = request.intParam("limit", 50).coerceIn(1, 100)
                    )
                    val listing = items.joinToString("\n") { item ->
                        val kind = if (item.isDirectory) "DIR" else "FILE"
                        "$kind | ${item.displayName} | ${item.mimeType} | id=${item.documentId}"
                    }.take(3_500)
                    val summary = "Listed ${items.size} item(s) in the user-selected folder."
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("message" to summary, "listing" to listing),
                        observation = NormalizedObservation.fromSystemState(
                            summary,
                            mapOf("itemCount" to items.size.toString())
                        ),
                        durationMs = SystemClock.elapsedRealtime() - started
                    )
                }
                ACTION_READ -> {
                    val preview = storage.readText(request.stringParam("documentId"))
                    val summary = "Read-only preview prepared for ${preview.displayName} (${preview.bytesRead} bytes)${if (preview.truncated) "; preview truncated" else ""}."
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("message" to summary, "content" to preview.text),
                        observation = NormalizedObservation.fromSystemState(
                            summary,
                            mapOf("mimeType" to preview.mimeType, "bytesRead" to preview.bytesRead.toString())
                        ),
                        durationMs = SystemClock.elapsedRealtime() - started
                    )
                }
                else -> StructuredActionResult.failure(
                    request.actionId,
                    ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Unsupported SAF operation."),
                    durationMs = SystemClock.elapsedRealtime() - started
                )
            }
        } catch (error: SecurityException) {
            StructuredActionResult.failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.PERMISSION_DENIED, "Folder access was revoked or denied."),
                durationMs = SystemClock.elapsedRealtime() - started
            )
        } catch (error: Exception) {
            StructuredActionResult.failure(
                request.actionId,
                ExecutionError(
                    ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE,
                    (error.message ?: "Storage operation failed.").take(180)
                ),
                durationMs = SystemClock.elapsedRealtime() - started
            )
        }
    }

    companion object {
        const val ACTION_LIST = "storage.tree.list"
        const val ACTION_READ = "storage.document.read"
    }
}
