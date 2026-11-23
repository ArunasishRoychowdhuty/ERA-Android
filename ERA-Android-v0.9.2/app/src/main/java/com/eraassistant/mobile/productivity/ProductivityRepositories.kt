package com.eraassistant.mobile.productivity

import com.eraassistant.mobile.screen.SensitiveInputPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class ReminderUiItem(
    val id: String,
    val title: String,
    val triggerAtEpochMs: Long,
    val isTriggered: Boolean,
    val calendarSynced: Boolean
)

data class ManagedMemoryUiItem(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val updatedAtEpochMs: Long
)

class ReminderRepository(
    private val dao: ReminderDao,
    private val cipher: SecurePayloadCipher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observeUi(): Flow<List<ReminderUiItem>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            runCatching {
                ReminderUiItem(
                    id = row.id,
                    title = cipher.decrypt(row.encryptedTitle),
                    triggerAtEpochMs = row.triggerAtEpochMs,
                    isTriggered = row.triggeredAtEpochMs != null,
                    calendarSynced = row.calendarEventId != null
                )
            }.getOrNull()
        }
    }

    suspend fun create(title: String, delayMinutes: Long): ReminderEntity {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank() && cleanTitle.length <= MAX_TITLE_LENGTH) { "Reminder title is empty or too long." }
        require(delayMinutes in 1L..MAX_DELAY_MINUTES) { "Reminder delay must be between 1 minute and 365 days." }
        require(!SensitiveInputPolicy.containsCredentialLikePayload(cleanTitle)) {
            "Credential-like values cannot be saved as reminder text."
        }
        val now = clock()
        val entity = ReminderEntity(
            id = UUID.randomUUID().toString(),
            encryptedTitle = cipher.encrypt(cleanTitle),
            encryptedNote = cipher.encrypt(""),
            triggerAtEpochMs = now + delayMinutes * MILLIS_PER_MINUTE,
            createdAtEpochMs = now
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun get(id: String): ReminderEntity? = dao.getById(id)

    suspend fun titleFor(row: ReminderEntity): String = cipher.decrypt(row.encryptedTitle)

    suspend fun markCalendarSynced(id: String, eventId: Long) =
        dao.markCalendarSynced(id, eventId, clock())

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun pending(): List<ReminderEntity> = dao.getPending()

    suspend fun pruneOld() = dao.pruneTriggered(clock() - RETAIN_TRIGGERED_MS)

    companion object {
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_DELAY_MINUTES = 525_600L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val RETAIN_TRIGGERED_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

class ManagedMemoryRepository(
    private val dao: ManagedMemoryDao,
    private val cipher: SecurePayloadCipher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observeUi(): Flow<List<ManagedMemoryUiItem>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            runCatching {
                ManagedMemoryUiItem(
                    id = row.id,
                    category = row.category,
                    title = cipher.decrypt(row.encryptedTitle),
                    content = cipher.decrypt(row.encryptedContent),
                    updatedAtEpochMs = row.updatedAtEpochMs
                )
            }.getOrNull()
        }
    }

    /** The caller must only invoke this after the user explicitly presses Save. */
    suspend fun add(category: String, title: String, content: String): ManagedMemoryEntity {
        val cleanCategory = category.trim().uppercase()
        val cleanTitle = title.trim()
        val cleanContent = content.trim()
        require(cleanCategory in ALLOWED_CATEGORIES) { "Choose a supported memory category." }
        require(cleanTitle.isNotBlank() && cleanTitle.length <= 80) { "Memory title is empty or too long." }
        require(cleanContent.isNotBlank() && cleanContent.length <= MAX_CONTENT_LENGTH) { "Memory content is empty or too long." }
        require(!SensitiveInputPolicy.isSensitiveField(cleanTitle, hint = cleanCategory) &&
            !SensitiveInputPolicy.containsCredentialLikePayload(cleanContent)) {
            "Passwords, OTPs, PINs, payment details and credential-like values cannot be stored in memory."
        }
        val now = clock()
        val row = ManagedMemoryEntity(
            id = UUID.randomUUID().toString(),
            category = cleanCategory,
            encryptedTitle = cipher.encrypt(cleanTitle),
            encryptedContent = cipher.encrypt(cleanContent),
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.upsert(row)
        return row
    }

    suspend fun delete(id: String): Int = dao.delete(id)
    suspend fun clearAll(): Int = dao.deleteAll()

    companion object {
        private const val MAX_CONTENT_LENGTH = 1_500
        val ALLOWED_CATEGORIES = setOf("USER_PREFERENCE", "HOUSEHOLD", "ROUTINE", "PROJECT_NOTE")
    }
}
