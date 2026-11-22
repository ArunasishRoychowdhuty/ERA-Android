package com.eraassistant.mobile.provider

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIProviderConfigurationTest {
    @Test fun localProviderNeedsNoRemoteSettings() {
        assertTrue(AIProviderConfiguration().validate().isEmpty())
    }

    @Test fun ollamaProviderAcceptsOnlySameDeviceLoopbackEndpoints() {
        val valid = AIProviderConfiguration(
            provider = AIProviderKind.OLLAMA_LOCAL,
            modelId = "llama3.2:1b",
            endpoint = "http://localhost:11434"
        )
        assertTrue(valid.validate().isEmpty())
        assertTrue(valid.isLocalModelConfigured())
        assertFalse(valid.isRemoteConfigured(hasSecret = true))

        val remote = valid.copy(endpoint = "http://192.168.1.10:11434")
        assertTrue(remote.validate().isNotEmpty())
        val ipLoopback = valid.copy(endpoint = "http://127.0.0.1:11434")
        assertTrue(ipLoopback.validate().isNotEmpty())
        val credentials = valid.copy(endpoint = "http://user:pass@localhost:11434")
        assertTrue(credentials.validate().isNotEmpty())
        val query = valid.copy(endpoint = "http://localhost:11434?api_key=secret")
        assertTrue(query.validate().isNotEmpty())
        val fallback = valid.copy(remoteFallbackEnabled = true)
        assertTrue(fallback.validate().isNotEmpty())
    }

    @Test fun localOnDevicePlannerSourceIsReportedSeparately() = runBlocking {
        val router = LocalFirstPlannerRouter<String>(
            localPlanner = { LocalPlanningCandidate("offline plan", PlanningConfidence.NEEDS_REMOTE_REASONING) },
            remotePlanner = { "on-device model plan" },
            configuredPlannerSource = PlannerSource.ON_DEVICE
        )
        val result = router.plan("goal", remoteEnabled = true)
        assertEquals("on-device model plan", result.value)
        assertEquals(PlannerSource.ON_DEVICE, result.source)
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
