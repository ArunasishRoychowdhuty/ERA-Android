package com.eraassistant.mobile.remote

data class RemoteGatewayPairing(
    val deviceId: String,
    val accessToken: String,
    val heartbeatIntervalSeconds: Int,
    val expiresAtEpochMillis: Long
)

data class RemoteGatewaySession(
    val endpoint: String,
    val deviceId: String,
    val accessToken: String,
    val heartbeatIntervalSeconds: Int,
    val expiresAtEpochMillis: Long
)

data class RemoteGatewayHeartbeat(val nextHeartbeatSeconds: Int)

class RemoteGatewayHttpException(val statusCode: Int) :
    java.io.IOException("Remote gateway returned HTTP $statusCode.")
