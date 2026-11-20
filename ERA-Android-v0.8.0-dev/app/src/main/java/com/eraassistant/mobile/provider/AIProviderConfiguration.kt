package com.eraassistant.mobile.provider

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException

enum class AIProviderKind { LOCAL_ONLY, GEMINI, OPENAI_COMPATIBLE }

data class AIProviderConfiguration(
    val provider: AIProviderKind = AIProviderKind.LOCAL_ONLY,
    val modelId: String = "",
    val endpoint: String = "",
    val remoteFallbackEnabled: Boolean = false
) {
    fun validate(): List<String> {
        if (provider == AIProviderKind.LOCAL_ONLY) return emptyList()
        val errors = mutableListOf<String>()
        if (modelId.isBlank() || modelId.length > 120 || !MODEL_ID.matches(modelId)) {
            errors += "Model ID must contain only letters, digits, dot, underscore, slash or hyphen."
        }
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null
        ) errors += "Provider endpoint must be a valid HTTPS URL without credentials, query or fragment."
        return errors
    }

    fun isRemoteConfigured(hasSecret: Boolean): Boolean =
        provider != AIProviderKind.LOCAL_ONLY && validate().isEmpty() && hasSecret
    companion object {
        private val MODEL_ID = Regex("[A-Za-z0-9._/-]{1,120}")
    }
}

enum class PlanningConfidence { SUFFICIENT, NEEDS_REMOTE_REASONING }
enum class PlannerSource { LOCAL, REMOTE, LOCAL_FALLBACK }

data class LocalPlanningCandidate<T>(val value: T, val confidence: PlanningConfidence)
data class PlannerRoutingResult<T>(val value: T, val source: PlannerSource, val note: String = "")

class ProviderHttpException(val statusCode: Int, message: String) : IOException(message)

object ProviderFailurePolicy {
    fun isTransient(error: Throwable): Boolean {
        val cause = generateSequence(error) { it.cause }.toList()
        if (cause.any { it is IOException && it !is ProviderHttpException }) return true
        val status = cause.filterIsInstance<ProviderHttpException>().firstOrNull()?.statusCode ?: return false
        return status == 408 || status == 425 || status == 429 || status in 500..599
    }
}

/**
 * Run the local planner first. Only low-confidence local outcomes are sent to a
 * configured remote provider. A transient remote failure returns the already-made
 * local plan; authentication/configuration errors are surfaced instead of hidden.
 */
class LocalFirstPlannerRouter<T>(
    private val localPlanner: suspend (String) -> LocalPlanningCandidate<T>,
    private val remotePlanner: suspend (String) -> T
) {
    suspend fun plan(goal: String, remoteEnabled: Boolean): PlannerRoutingResult<T> {
        val local = localPlanner(goal)
        if (!remoteEnabled || local.confidence == PlanningConfidence.SUFFICIENT) {
            return PlannerRoutingResult(local.value, PlannerSource.LOCAL)
        }
        return try {
            PlannerRoutingResult(remotePlanner(goal), PlannerSource.REMOTE)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!ProviderFailurePolicy.isTransient(error)) throw error
            PlannerRoutingResult(
                value = local.value,
                source = PlannerSource.LOCAL_FALLBACK,
                note = "Remote provider temporarily unavailable; local plan retained: ${error.message.orEmpty().take(120)}"
            )
        }
    }
}
