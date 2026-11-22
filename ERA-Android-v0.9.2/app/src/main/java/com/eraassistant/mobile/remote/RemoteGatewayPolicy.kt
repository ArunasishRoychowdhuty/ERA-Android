package com.eraassistant.mobile.remote

import java.net.URI

object RemoteGatewayPolicy {
    fun normalizeEndpoint(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.length !in 12..253) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (uri.path.orEmpty().isNotEmpty() && uri.path != "/") return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || ".." in host) return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
        if (!HOST_PATTERN.matches(host)) return null
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return null
        return "https://$host"
    }

    fun heartbeatUrl(endpoint: String, deviceId: String): String? {
        val normalized = normalizeEndpoint(endpoint) ?: return null
        if (!isValidDeviceId(deviceId)) return null
        return "$normalized/api/v3/devices/$deviceId/heartbeat"
    }

    fun isValidPairingCode(value: String): Boolean {
        val code = value.trim().uppercase()
        return code.length in 8..64 && PAIRING_CODE_PATTERN.matches(code)
    }

    fun isValidDeviceId(value: String): Boolean =
        value.length in 8..128 && DEVICE_ID_PATTERN.matches(value)

    fun isValidAccessToken(value: String): Boolean =
        value.length in 32..512 && ACCESS_TOKEN_PATTERN.matches(value)

    fun retryDelaySeconds(failureCount: Int, jitterSeconds: Int = 0): Int {
        val exponent = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(5)
        val base = (2L shl exponent).coerceAtMost(60L)
        return (base + jitterSeconds.coerceIn(0, 3)).coerceAtMost(60L).toInt()
    }

    private val HOST_PATTERN = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val PAIRING_CODE_PATTERN = Regex("[A-Z0-9-]{8,64}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
    private val ACCESS_TOKEN_PATTERN = Regex("[A-Za-z0-9._~+/-]{32,512}={0,}")
}
