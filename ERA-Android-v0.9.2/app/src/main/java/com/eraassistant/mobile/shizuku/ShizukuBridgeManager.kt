package com.eraassistant.mobile.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.eraassistant.mobile.BuildConfig
import rikka.shizuku.Shizuku

data class ShizukuBridgeState(
    val binderConnected: Boolean = false,
    val permissionGranted: Boolean = false,
    val userServiceConnected: Boolean = false,
    val shellUid: Int? = null,
    val status: String = "Shizuku is not connected yet.",
    val lastOutput: String = ""
)

/**
 * Lifecycle-aware Shizuku connection manager. Permission is requested only after
 * a user presses the Connect button. All exposed UserService operations are read-only.
 */
class ShizukuBridgeManager(
    context: Context,
    private val onStateChanged: (ShizukuBridgeState) -> Unit
) {
    private val appContext = context.applicationContext

    @Volatile
    private var remoteService: IEraShizukuUserService? = null

    @Volatile
    private var state = ShizukuBridgeState()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remoteService = null
        publish(
            state.copy(
                binderConnected = false,
                permissionGranted = false,
                userServiceConnected = false,
                shellUid = null,
                status = "Shizuku server stopped. Start it again in the Shizuku app."
            )
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                refresh()
                bindUserService()
            } else {
                publish(state.copy(permissionGranted = false, status = "Shizuku permission was denied. No privileged action was run."))
            }
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, EraShizukuUserService::class.java)
    )
        .daemon(false)
        .processNameSuffix("era-shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = runCatching {
                binder.pingBinder() && IEraShizukuUserService.Stub.asInterface(binder) != null
            }.getOrDefault(false)
            remoteService = if (connected) IEraShizukuUserService.Stub.asInterface(binder) else null
            publish(
                state.copy(
                    userServiceConnected = connected,
                    status = if (connected) {
                        "ERA system bridge connected. Only the listed read-only diagnostics are exposed."
                    } else {
                        "Shizuku returned an invalid UserService binder."
                    }
                )
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            publish(state.copy(userServiceConnected = false, status = "ERA Shizuku UserService disconnected."))
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun snapshot(): ShizukuBridgeState = state

    fun refresh(): ShizukuBridgeState {
        val binderConnected = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderConnected && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (binderConnected && permissionGranted) {
            runCatching { Shizuku.getUid() }.getOrNull()
        } else {
            null
        }
        val serviceConnected = remoteService?.asBinder()?.isBinderAlive == true
        val message = when {
            !binderConnected -> "Shizuku server is unavailable. Start it from the Shizuku app."
            !permissionGranted -> "Shizuku server detected. Tap Connect and approve ERA in Shizuku."
            !serviceConnected -> "Shizuku permission is granted. Tap Connect to start ERA's read-only service."
            else -> "ERA system bridge is connected (shell UID $uid)."
        }
        return state.copy(
            binderConnected = binderConnected,
            permissionGranted = permissionGranted,
            userServiceConnected = serviceConnected,
            shellUid = uid,
            status = message
        ).also(::publish)
    }

    fun connectOrRequestPermission() {
        val current = refresh()
        if (!current.binderConnected) {
            publish(current.copy(status = "Open Shizuku and start the Wireless Debugging service first."))
            return
        }
        if (Shizuku.isPreV11()) {
            publish(current.copy(status = "This Shizuku server version is too old. Update Shizuku to v11 or later."))
            return
        }
        if (!current.permissionGranted) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                .onFailure { publish(current.copy(status = "Permission request failed: ${it.message.orEmpty()}")) }
                .onSuccess { publish(current.copy(status = "Waiting for you to approve ERA in the Shizuku prompt.")) }
            return
        }
        bindUserService()
    }

    private fun bindUserService() {
        val current = refresh()
        if (!current.binderConnected || !current.permissionGranted) return
        if (current.userServiceConnected) {
            publish(current.copy(status = "ERA system bridge is already connected."))
            return
        }
        runCatching {
            if (Shizuku.getVersion() < 12) {
                publish(current.copy(status = "Shizuku UserService requires server API v12 or later."))
            } else {
                publish(current.copy(status = "Starting ERA's read-only Shizuku UserService…"))
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            }
        }.onFailure {
            publish(current.copy(status = "Could not start Shizuku UserService: ${it.message.orEmpty()}"))
        }
    }

    @Throws(RemoteException::class)
    fun executeReadOnly(operationId: String, argument: String = ""): String {
        val service = remoteService
            ?: return "ERROR: ERA Shizuku bridge is not connected. Tap Connect first."
        if (!service.asBinder().isBinderAlive) {
            remoteService = null
            return "ERROR: Shizuku UserService binder is dead."
        }
        return service.executeReadOnly(operationId, argument)
    }

    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        runCatching {
            if (remoteService != null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            }
        }
        remoteService = null
    }

    private fun publish(next: ShizukuBridgeState) {
        state = next
        onStateChanged(next)
    }

    companion object {
        private const val REQUEST_CODE = 4107
    }
}
