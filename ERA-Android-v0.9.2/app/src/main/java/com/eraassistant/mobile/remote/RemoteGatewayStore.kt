package com.eraassistant.mobile.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores gateway identity in private preferences and encrypts its bearer token with Android Keystore. */
class RemoteGatewayStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun save(endpoint: String, pairing: RemoteGatewayPairing) {
        val normalized = requireNotNull(RemoteGatewayPolicy.normalizeEndpoint(endpoint))
        require(RemoteGatewayPolicy.isValidDeviceId(pairing.deviceId))
        require(RemoteGatewayPolicy.isValidAccessToken(pairing.accessToken))
        require(pairing.heartbeatIntervalSeconds in MIN_HEARTBEAT_SECONDS..MAX_HEARTBEAT_SECONDS)
        require(pairing.expiresAtEpochMillis > System.currentTimeMillis())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(pairing.accessToken.toByteArray(Charsets.UTF_8))
        val committed = preferences.edit()
            .putString(KEY_ENDPOINT, normalized)
            .putString(KEY_DEVICE_ID, pairing.deviceId)
            .putInt(KEY_HEARTBEAT, pairing.heartbeatIntervalSeconds)
            .putLong(KEY_EXPIRES, pairing.expiresAtEpochMillis)
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(committed) { "Could not persist the remote pairing." }
    }

    @Synchronized
    fun loadSession(): RemoteGatewaySession? {
        val endpoint = preferences.getString(KEY_ENDPOINT, null) ?: return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val ivText = preferences.getString(KEY_IV, null) ?: return null
        val tokenText = preferences.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val normalized = requireNotNull(RemoteGatewayPolicy.normalizeEndpoint(endpoint))
            require(RemoteGatewayPolicy.isValidDeviceId(deviceId))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val token = String(
                cipher.doFinal(Base64.decode(tokenText, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
            require(RemoteGatewayPolicy.isValidAccessToken(token))
            val heartbeat = preferences.getInt(KEY_HEARTBEAT, DEFAULT_HEARTBEAT_SECONDS)
            require(heartbeat in MIN_HEARTBEAT_SECONDS..MAX_HEARTBEAT_SECONDS)
            val expiresAt = preferences.getLong(KEY_EXPIRES, 0L)
            require(expiresAt > System.currentTimeMillis()) { "Remote gateway pairing expired." }
            RemoteGatewaySession(
                endpoint = normalized,
                deviceId = deviceId,
                accessToken = token,
                heartbeatIntervalSeconds = heartbeat,
                expiresAtEpochMillis = expiresAt
            )
        }.getOrElse {
            clearPairing()
            null
        }
    }

    @Synchronized
    fun clearPairing() {
        preferences.edit()
            .remove(KEY_ENDPOINT)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_HEARTBEAT)
            .remove(KEY_EXPIRES)
            .remove(KEY_IV)
            .remove(KEY_TOKEN)
            .commit()
    }

    fun isPaired(): Boolean = loadSession() != null
    fun endpointDraft(): String = preferences.getString(KEY_ENDPOINT, "").orEmpty()
    fun status(): String = preferences.getString(KEY_STATUS, "Not paired with a remote gateway.").orEmpty()
    fun setStatus(value: String) { preferences.edit().putString(KEY_STATUS, value.take(180)).apply() }
    fun isSessionMarkedActive(): Boolean = preferences.getBoolean(KEY_ACTIVE, false)
    fun setSessionActive(active: Boolean) { preferences.edit().putBoolean(KEY_ACTIVE, active).apply() }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "era_remote_gateway_v3"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "era.remote.v3.access-token.aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HEARTBEAT = "heartbeat_seconds"
        const val KEY_EXPIRES = "expires_at_ms"
        const val KEY_IV = "token_iv"
        const val KEY_TOKEN = "token_ciphertext"
        const val KEY_STATUS = "status"
        const val KEY_ACTIVE = "session_active"
        const val MIN_HEARTBEAT_SECONDS = 15
        const val MAX_HEARTBEAT_SECONDS = 120
        const val DEFAULT_HEARTBEAT_SECONDS = 30
    }
}
