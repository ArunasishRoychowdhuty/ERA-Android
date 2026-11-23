package com.eraassistant.mobile.productivity

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val work = WorkManager.getInstance(context)

    fun schedule(reminder: ReminderEntity) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_DELIVER)
            .putExtra(EXTRA_REMINDER_ID, reminder.id)
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val delay = (reminder.triggerAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            work.enqueueUniqueWork(
                workName(reminder.id),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(androidx.work.workDataOf(EXTRA_REMINDER_ID to reminder.id))
                    .build()
            )
            return
        }

        work.cancelUniqueWork(workName(reminder.id))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtEpochMs, pending)
        } else {
            alarms.setExact(AlarmManager.RTC_WAKEUP, reminder.triggerAtEpochMs, pending)
        }
    }

    fun cancel(id: String) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_DELIVER)
            .putExtra(EXTRA_REMINDER_ID, id)
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) alarms.cancel(pending)
        work.cancelUniqueWork(workName(id))
    }

    suspend fun reschedulePending() {
        val rows = EraDatabase.get(context).reminders().getPending()
        rows.forEach(::schedule)
    }

    private fun workName(id: String) = "era-reminder-$id"

    companion object {
        const val ACTION_DELIVER = "com.eraassistant.mobile.action.DELIVER_REMINDER"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val CHANNEL_ID = "era_reminders"
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ReminderScheduler.EXTRA_REMINDER_ID) ?: return Result.failure()
        return runCatching {
            ReminderDelivery.deliver(applicationContext, id)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_DELIVER) return
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderDelivery.deliver(context.applicationContext, id)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler(context.applicationContext).reschedulePending()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private object ReminderDelivery {
    suspend fun deliver(context: Context, id: String) = withContext(Dispatchers.IO) {
        val canNotify = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return@withContext

        val db = EraDatabase.get(context)
        val dao = db.reminders()
        val row = dao.getById(id) ?: return@withContext
        val now = System.currentTimeMillis()
        if (!row.enabled || row.triggeredAtEpochMs != null || row.triggerAtEpochMs > now) return@withContext
        if (dao.claimIfDue(id, now) != 1) return@withContext

        val title = runCatching { SecurePayloadCipher().decrypt(row.encryptedTitle) }.getOrDefault("ERA reminder")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ReminderScheduler.CHANNEL_ID, "ERA reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openIntent = Intent(context, com.eraassistant.mobile.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            context, id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ERA reminder")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
