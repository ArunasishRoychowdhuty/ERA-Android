package com.eraassistant.mobile.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGatewayPolicyTest {
    @Test fun acceptsHttpsGatewayDnsAndTailnetHosts() {
        assertEquals("https://gateway.example.com", RemoteGatewayPolicy.normalizeEndpoint("https://gateway.example.com/"))
        assertEquals("https://era-host.example-tailnet.ts.net", RemoteGatewayPolicy.normalizeEndpoint("https://era-host.example-tailnet.ts.net"))
    }

    @Test fun rejectsCleartextLocalAndLiteralIpEndpoints() {
        listOf(
            "http://gateway.example.com",
            "ws://gateway.example.com",
            "https://localhost",
            "https://host.local",
            "https://192.168.1.2",
            "https://100.64.0.10",
            "https://gateway.example.com:8443"
        ).forEach { assertNull(it, RemoteGatewayPolicy.normalizeEndpoint(it)) }
    }

    @Test fun rejectsMalformedDnsLabels() {
        assertNull(RemoteGatewayPolicy.normalizeEndpoint("https://host.-bad.example.com"))
        assertNull(RemoteGatewayPolicy.normalizeEndpoint("https://host..example.com"))
        assertNull(RemoteGatewayPolicy.normalizeEndpoint("https://-host.example.com"))
    }

    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        listOf(
            "https://user:pass@gateway.example.com",
            "https://gateway.example.com/api",
            "https://gateway.example.com?token=secret",
            "https://gateway.example.com#fragment"
        ).forEach { assertNull(it, RemoteGatewayPolicy.normalizeEndpoint(it)) }
    }

    @Test fun heartbeatUrlUsesTheExactNormalizedEndpointAndDeviceId() {
        assertEquals(
            "https://gateway.example.com/api/v3/devices/device_1234/heartbeat",
            RemoteGatewayPolicy.heartbeatUrl("https://gateway.example.com", "device_1234")
        )
        assertNull(RemoteGatewayPolicy.heartbeatUrl("http://gateway.example.com", "device_1234"))
        assertNull(RemoteGatewayPolicy.heartbeatUrl("https://gateway.example.com", "../bad"))
    }

    @Test fun validatesPairingCodesAndIds() {
        assertTrue(RemoteGatewayPolicy.isValidPairingCode("ABCD-1234-EFGH"))
        assertFalse(RemoteGatewayPolicy.isValidPairingCode("short"))
        assertFalse(RemoteGatewayPolicy.isValidPairingCode("ABCD 1234"))
        assertTrue(RemoteGatewayPolicy.isValidDeviceId("device_1234"))
        assertFalse(RemoteGatewayPolicy.isValidDeviceId("../etc/passwd"))
    }

    @Test fun accessTokensRejectWhitespaceAndNonHeaderCharacters() {
        assertTrue(RemoteGatewayPolicy.isValidAccessToken("Abc123._-~+/Abc123._-~+/Abc123._-~+/"))
        assertFalse(RemoteGatewayPolicy.isValidAccessToken("short"))
        assertFalse(RemoteGatewayPolicy.isValidAccessToken("Abc123._-~+/Abc123._-~+/Abc123._-~+/ "))
        assertFalse(RemoteGatewayPolicy.isValidAccessToken("A".repeat(513)))
    }

    @Test fun exponentialReconnectBackoffIsBounded() {
        assertEquals(2, RemoteGatewayPolicy.retryDelaySeconds(1))
        assertEquals(5, RemoteGatewayPolicy.retryDelaySeconds(1, 3))
        assertEquals(4, RemoteGatewayPolicy.retryDelaySeconds(2))
        assertEquals(60, RemoteGatewayPolicy.retryDelaySeconds(10, 3))
    }
}
