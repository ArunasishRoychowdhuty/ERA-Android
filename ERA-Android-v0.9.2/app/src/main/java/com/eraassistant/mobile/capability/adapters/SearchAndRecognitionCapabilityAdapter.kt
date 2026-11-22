package com.eraassistant.mobile.capability.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource
import com.eraassistant.mobile.search.ProductSearchQuery

/**
 * Adapter integrating product search and external search queries.
 */
class SearchAndRecognitionCapabilityAdapter(
    private val context: Context
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.SEARCH_AND_RECOGNITION,
        name = "Product Search & External Information",
        description = "Formats, validates, and launches external product and information searches via system browser intents.",
        category = CapabilityCategory.INFORMATION_SEARCH,
        supportedActions = setOf(
            ACTION_BUILD_SHOPPING_URL,
            ACTION_LAUNCH_SHOPPING_SEARCH
        ),
        requiredPermissions = emptySet(),
        executionConstraints = setOf("Opens user's default browser via ACTION_VIEW intent"),
        worksOffline = false,
        actionSchemas = CapabilitySchemas.searchAndRecognition()
    )

    override suspend fun checkAvailability(): CapabilityAvailability =
        CapabilityAvailability.AVAILABLE

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        val query = request.param<String>("query")
        if (query.isNullOrBlank()) {
            return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'query' is required.")
        }
        return null
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            val query = request.stringParam("query")
            val normalized = ProductSearchQuery.normalize(query)
            val url = ProductSearchQuery.shoppingUrl(normalized)
                ?: return StructuredActionResult.failure(
                    request.actionId,
                    ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Could not generate shopping search URL for query: '$query'"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )

            when (request.actionName) {
                ACTION_BUILD_SHOPPING_URL -> {
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("query" to normalized, "url" to url),
                        observation = NormalizedObservation.fromSystemState("Generated search URL: $url"),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_LAUNCH_SHOPPING_SEARCH -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("query" to normalized, "url" to url, "launched" to true),
                        observation = NormalizedObservation.fromSystemState("Launched shopping search for '$normalized'"),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                else -> StructuredActionResult.failure(
                    actionId = request.actionId,
                    error = ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by Search adapter"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )
            }
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Search error: ${t.message}", cause = t),
                durationMs = SystemClock.elapsedRealtime() - start
            )
        }
    }

    companion object {
        const val ACTION_BUILD_SHOPPING_URL = "search.shopping.build_url"
        const val ACTION_LAUNCH_SHOPPING_SEARCH = "search.shopping.launch"
    }
}
