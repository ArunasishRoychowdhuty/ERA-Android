package com.eraassistant.mobile.remote

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** HTTPS-only v3 client: pairing and heartbeat, with no command execution endpoint. */
class RemoteGatewayApiClient {
    fun exchangePairingCode(
        endpoint: String,
        pairingCode: String,
        deviceLabel: String
    ): RemoteGatewayPairing {
        val normalized = requireNotNull(RemoteGatewayPolicy.normalizeEndpoint(endpoint)) {
            "Gateway must be a trusted HTTPS DNS hostname on port 443."
        }
        require(RemoteGatewayPolicy.isValidPairingCode(pairingCode)) {
            "Pairing code format is invalid."
        }
        val body = JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("pairing_code", pairingCode.trim().uppercase())
            .put("device_label", deviceLabel.take(48))
            .put("requested_scopes", JSONArray().put("presence:write"))
        val response = post("$normalized/api/v3/pairings/exchange", body, null)
        if (response.optInt("protocol_version", -1) != PROTOCOL_VERSION) {
            throw IOException("Gateway protocol version mismatch.")
        }
        val deviceId = response.optString("device_id", "")
        val token = response.optString("access_token", "")
        val heartbeat = response.optInt("heartbeat_interval_seconds", 0)
        val expires = response.optLong("expires_at_epoch_ms", 0L)
        val scopes = response.optJSONArray("scopes") ?: JSONArray()
        val hasOnlyPresenceScope = scopes.length() == 1 && scopes.optString(0) == "presence:write"
        if (!RemoteGatewayPolicy.isValidDeviceId(deviceId) ||
            !RemoteGatewayPolicy.isValidAccessToken(token) ||
            heartbeat !in MIN_HEARTBEAT_SECONDS..MAX_HEARTBEAT_SECONDS ||
            expires <= System.currentTimeMillis() ||
            expires > System.currentTimeMillis() + MAX_TOKEN_LIFETIME_MS ||
            !hasOnlyPresenceScope
        ) {
            throw IOException("Gateway pairing response failed protocol validation.")
        }
        return RemoteGatewayPairing(deviceId, token, heartbeat, expires)
    }

    fun heartbeat(session: RemoteGatewaySession): RemoteGatewayHeartbeat {
        val heartbeatUrl = requireNotNull(
            RemoteGatewayPolicy.heartbeatUrl(session.endpoint, session.deviceId)
        ) { "Remote gateway endpoint or device identifier is invalid." }
        require(RemoteGatewayPolicy.isValidAccessToken(session.accessToken))
        val body = JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("device_id", session.deviceId)
            .put("client_time_epoch_ms", System.currentTimeMillis())
            .put("client_nonce", UUID.randomUUID().toString())
            .put("uptime_ms", SystemClock.elapsedRealtime())
        val response = post(heartbeatUrl, body, session.accessToken)
        if (response.optInt("protocol_version", -1) != PROTOCOL_VERSION) {
            throw IOException("Gateway protocol version mismatch.")
        }
        val requested = response.optInt("next_heartbeat_seconds", session.heartbeatIntervalSeconds)
        if (requested !in MIN_HEARTBEAT_SECONDS..MAX_HEARTBEAT_SECONDS) {
            throw IOException("Gateway heartbeat interval is outside the supported range.")
        }
        return RemoteGatewayHeartbeat(requested)
    }

    private fun post(url: String, body: JSONObject, bearerToken: String?): JSONObject {
        val connection = (URI(url).toURL().openConnection() as? HttpsURLConnection)
            ?: throw IOException("HTTPS connection is unavailable.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-ERA-Protocol-Version", PROTOCOL_VERSION.toString())
            connection.setRequestProperty("Cache-Control", "no-store")
            bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RemoteGatewayHttpException(status)
            if (status == 204) return JSONObject()
            val bytes = connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }
            if (bytes.isEmpty()) return JSONObject()
            return try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                throw IOException("Gateway response is not valid JSON.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Gateway response exceeded the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val PROTOCOL_VERSION = 3
        const val MIN_HEARTBEAT_SECONDS = 15
        const val MAX_HEARTBEAT_SECONDS = 120
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_RESPONSE_BYTES = 16 * 1024
        private const val MAX_TOKEN_LIFETIME_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
