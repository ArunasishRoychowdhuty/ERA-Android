package com.eraassistant.mobile.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** User-started foreground service for authenticated gateway presence and heartbeat only. */
class RemoteSessionService : Service() {
    private lateinit var store: RemoteGatewayStore
    private val stopping = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        store = RemoteGatewayStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (store.loadSession() == null) {
            store.setSessionActive(false)
            store.setStatus("Not paired. Enter a gateway and one-time pairing code first.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopping.set(false)
        val notification = buildNotification("Starting secure session…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (worker?.isAlive != true) {
            worker = Thread({ runSessionLoop() }, "ERA-Remote-V3").apply {
                isDaemon = true
                start()
            }
        }
        return START_NOT_STICKY
    }

    private fun runSessionLoop() {
        var failures = 0
        store.setSessionActive(true)
        store.setStatus("Connecting to the paired private gateway…")
        updateNotification("Connecting to private gateway…")
        while (!stopping.get() && !Thread.currentThread().isInterrupted) {
            val session = store.loadSession()
            if (session == null) {
                store.setStatus("Pairing credentials unavailable. Pair again.")
                break
            }
            if (session.expiresAtEpochMillis <= System.currentTimeMillis()) {
                store.setStatus("Gateway pairing expired. Pair again to reconnect.")
                break
            }
            try {
                val result = RemoteGatewayApiClient().heartbeat(session)
                failures = 0
                store.setStatus("Connected · heartbeat received · ${System.currentTimeMillis()}")
                updateNotification("Connected · private gateway heartbeat active")
                sleepSafely(result.nextHeartbeatSeconds * 1_000L)
            } catch (error: RemoteGatewayHttpException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    store.setStatus("Gateway rejected this pairing. Re-pair to continue.")
                    break
                }
                failures += 1
                val delaySeconds = RemoteGatewayPolicy.retryDelaySeconds(failures, Random.nextInt(0, 4))
                store.setStatus("Gateway unavailable · retrying in ${delaySeconds}s (HTTP ${error.statusCode}).")
                updateNotification("Gateway offline · retrying in ${delaySeconds}s")
                sleepSafely(delaySeconds * 1_000L)
            } catch (error: Exception) {
                if (error is InterruptedException) break
                failures += 1
                val delaySeconds = RemoteGatewayPolicy.retryDelaySeconds(failures, Random.nextInt(0, 4))
                // Exception messages are deliberately not persisted because they can contain endpoint details.
                store.setStatus("Network unavailable · reconnecting in ${delaySeconds}s.")
                updateNotification("Network unavailable · retrying in ${delaySeconds}s")
                sleepSafely(delaySeconds * 1_000L)
            }
        }
        store.setSessionActive(false)
        if (!stopping.get()) {
            val current = store.status()
            if (current.startsWith("Connected") || current.startsWith("Connecting")) {
                store.setStatus("Remote session stopped.")
            }
        }
        stopSelf()
    }

    private fun sleepSafely(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds.coerceIn(1_000L, 120_000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun stopSession() {
        stopping.set(true)
        worker?.interrupt()
        worker = null
        store.setSessionActive(false)
        store.setStatus("Remote session stopped by user.")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopping.set(true)
        worker?.interrupt()
        worker = null
        if (::store.isInitialized) store.setSessionActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, com.eraassistant.mobile.MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 11, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ERA · Remote session")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ERA remote session",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Visible status for the user-started private remote gateway session."
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.eraassistant.mobile.remote.STOP"
        private const val CHANNEL_ID = "era_remote_session_v3"
        private const val NOTIFICATION_ID = 9303
    }
}
