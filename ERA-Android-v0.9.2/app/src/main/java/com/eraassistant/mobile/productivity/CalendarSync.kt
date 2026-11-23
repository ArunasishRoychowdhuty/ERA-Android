package com.eraassistant.mobile.productivity

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

sealed interface CalendarSyncResult {
    data class Synced(val eventId: Long) : CalendarSyncResult
    data object PermissionRequired : CalendarSyncResult
    data object NoWritableCalendar : CalendarSyncResult
    data class Failed(val reason: String) : CalendarSyncResult
}

class CalendarSync(private val context: Context) {
    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun insert(title: String, startAtEpochMs: Long): CalendarSyncResult {
        if (!hasPermissions()) return CalendarSyncResult.PermissionRequired
        return runCatching {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
            )
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                CalendarContract.Calendars._ID + " ASC"
            ) ?: return CalendarSyncResult.NoWritableCalendar
            val calendarId = cursor.use {
                var chosen: Long? = null
                while (it.moveToNext()) {
                    val access = it.getInt(1)
                    val visible = it.getInt(2) == 1
                    if (visible && access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        chosen = it.getLong(0)
                        break
                    }
                }
                chosen
            } ?: return CalendarSyncResult.NoWritableCalendar

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startAtEpochMs)
                put(CalendarContract.Events.DTEND, startAtEpochMs + 15L * 60L * 1000L)
                put(CalendarContract.Events.TITLE, title.take(120))
                put(CalendarContract.Events.DESCRIPTION, "Created from an explicitly selected ERA reminder.")
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return CalendarSyncResult.Failed("Android Calendar did not create an event.")
            CalendarSyncResult.Synced(uri.lastPathSegment?.toLongOrNull()
                ?: return CalendarSyncResult.Failed("Calendar returned an invalid event ID."))
        }.getOrElse { error ->
            CalendarSyncResult.Failed(error.message ?: "Calendar sync failed.")
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
