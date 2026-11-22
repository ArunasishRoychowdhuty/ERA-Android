package com.eraassistant.mobile.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.eraassistant.mobile.capability.CapabilityAvailability
import com.eraassistant.mobile.capability.CapabilityDescriptor
import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.planner.AIPlanner
import com.eraassistant.mobile.planner.PreviewPlanner
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.orchestration.TaskPlanDAG
import kotlinx.coroutines.CancellationException

/**
 * Manages offline assessment and graceful degradation.
 * Ensures offline-capable local capabilities continue functioning without network,
 * falls back to local deterministic planning when cloud AI is unreachable,
 * and never falsely claims successful execution for unavailable network capabilities.
 */
class OfflineOperationManager(
    private val context: Context? = null,
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val localPlanner: PreviewPlanner = PreviewPlanner()
) {
    /**
     * Checks if active network connectivity is available.
     */
    fun isNetworkAvailable(): Boolean {
        if (context == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Filters descriptors into those that operate strictly offline.
     */
    fun offlineCapableDescriptors(): List<CapabilityDescriptor> {
        val registry = capabilityRegistry ?: return emptyList()
        return registry.allDescriptors().filter { it.worksOffline }
    }

    /**
     * Evaluates whether an action requires network connectivity.
     */
    fun requiresNetwork(actionName: String): Boolean {
        val adapter = capabilityRegistry?.findCapabilityForAction(actionName) ?: return true
        return !adapter.descriptor.worksOffline
    }

    /**
     * Provides graceful fallback planning when a remote provider is down.
     */
    suspend fun planWithFallback(
        userRequest: String,
        remotePlanner: AIPlanner? = null
    ): TaskPlan {
        require(userRequest.isNotBlank() && userRequest.length <= 2_000) {
            "Task request must contain 1 to 2000 characters."
        }
        if (remotePlanner != null && isNetworkAvailable()) {
            val remotePlan = try {
                remotePlanner.createPlan(userRequest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (remotePlan != null) {
                val structuralErrors = remotePlan.validate()
                val capabilityErrors = if (capabilityRegistry != null && structuralErrors.isEmpty()) {
                    TaskPlanDAG(remotePlan).validateCapabilitySupport(capabilityRegistry)
                } else emptyList()
                if (structuralErrors.isEmpty() && capabilityErrors.isEmpty()) return remotePlan
            }
        }
        // Graceful offline degradation to deterministic local preview planning.
        return localPlanner.createPlan(userRequest).also { fallback ->
            val errors = fallback.validate()
            require(errors.isEmpty()) { "Local fallback planner produced an invalid plan: ${errors.joinToString()}" }
        }
    }

    /**
     * Checks availability of a capability taking network status into account.
     */
    fun assessCapability(descriptor: CapabilityDescriptor): CapabilityAvailability {
        if (!descriptor.worksOffline && !isNetworkAvailable()) {
            return CapabilityAvailability.missing(
                "Capability '${descriptor.name}' requires network connectivity, but device is offline."
            )
        }
        return CapabilityAvailability.AVAILABLE
    }
}
