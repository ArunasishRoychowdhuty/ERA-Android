package com.eraassistant.mobile.provider

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIProviderConfigurationTest {
    @Test fun localProviderNeedsNoRemoteSettings() {
        assertTrue(AIProviderConfiguration().validate().isEmpty())
    }

    @Test fun remoteProviderRequiresHttpsEndpointAndValidModel() {
        val invalid = AIProviderConfiguration(
            provider = AIProviderKind.GEMINI,
            modelId = "gemini-2.0-flash",
            endpoint = "http://example.com/v1"
        )
        assertTrue(invalid.validate().any { it.contains("HTTPS") })
        val valid = invalid.copy(endpoint = "https://generativelanguage.googleapis.com/v1beta")
        assertTrue(valid.validate().isEmpty())
    }

    @Test fun endpointRejectsEmbeddedCredentialsAndQueryStrings() {
        val config = AIProviderConfiguration(
            AIProviderKind.OPENAI_COMPATIBLE,
            "gpt-model",
            "https://user:pass@example.com/v1?key=secret",
            true
        )
        assertTrue(config.validate().isNotEmpty())
    }

    @Test fun sufficientLocalPlanDoesNotCallRemote() = runBlocking {
        var remoteCalled = false
        val router = LocalFirstPlannerRouter<String>(
            localPlanner = { LocalPlanningCandidate("local", PlanningConfidence.SUFFICIENT) },
            remotePlanner = { remoteCalled = true; "remote" }
        )
        val result = router.plan("goal", remoteEnabled = true)
        assertEquals("local", result.value)
        assertEquals(PlannerSource.LOCAL, result.source)
        assertFalse(remoteCalled)
    }

    @Test fun transientRemoteFailureFallsBackToLocalPlan() = runBlocking {
        val router = LocalFirstPlannerRouter<String>(
            localPlanner = { LocalPlanningCandidate("local plan", PlanningConfidence.NEEDS_REMOTE_REASONING) },
            remotePlanner = { throw IOException("offline") }
        )
        val result = router.plan("goal", remoteEnabled = true)
        assertEquals("local plan", result.value)
        assertEquals(PlannerSource.LOCAL_FALLBACK, result.source)
        assertTrue(result.note.contains("temporarily unavailable"))
    }

    @Test fun authenticationErrorsAreNotSilentlyRetriedAsTransient() = runBlocking {
        val router = LocalFirstPlannerRouter<String>(
            localPlanner = { LocalPlanningCandidate("local", PlanningConfidence.NEEDS_REMOTE_REASONING) },
            remotePlanner = { throw ProviderHttpException(401, "Unauthorized") }
        )
        try {
            router.plan("goal", remoteEnabled = true)
            fail("Expected provider authentication error to surface.")
        } catch (error: ProviderHttpException) {
            assertEquals(401, error.statusCode)
        }
    }

    @Test fun onlyRetryableProviderStatusesAreTransient() {
        assertTrue(ProviderFailurePolicy.isTransient(ProviderHttpException(429, "rate limit")))
        assertTrue(ProviderFailurePolicy.isTransient(ProviderHttpException(503, "unavailable")))
        assertFalse(ProviderFailurePolicy.isTransient(ProviderHttpException(401, "unauthorized")))
        assertFalse(ProviderFailurePolicy.isTransient(ProviderHttpException(400, "bad request")))
    }
}
