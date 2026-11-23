package com.eraassistant.mobile.productivity

import com.eraassistant.mobile.screen.SensitiveInputPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val CRM_ALLOWED_STATUSES = setOf("NEW", "REVIEWED", "CONTACTED", "PROPOSAL_DRAFT", "WAITING", "WON", "LOST")

data class ClientCrmDraft(
    val clientLabel: String,
    val projectTitle: String,
    val projectSummary: String,
    val sourceUrl: String,
    val status: String = "NEW",
    val followUpAtEpochMs: Long? = null
)
data class ClientCrmRecord(
    val id: String, val clientLabel: String, val projectTitle: String, val projectSummary: String,
    val sourceUrl: String, val status: String, val createdAtEpochMs: Long, val updatedAtEpochMs: Long,
    val followUpAtEpochMs: Long?
)

object ClientCrmRecordPolicy {
    fun validate(draft: ClientCrmDraft): String? {
        if (draft.clientLabel.isBlank() || draft.clientLabel.length > 140) return "Client label must be 1–140 characters."
        if (draft.projectTitle.isBlank() || draft.projectTitle.length > 180) return "Project title must be 1–180 characters."
        if (draft.projectSummary.length > 3_000 || draft.sourceUrl.length > 1_000) return "CRM field exceeds its size limit."
        if (draft.status !in CRM_ALLOWED_STATUSES) return "Unsupported CRM status."
        if (draft.sourceUrl.isNotBlank() && !draft.sourceUrl.startsWith("https://", ignoreCase = true)) return "Only HTTPS source URLs are accepted."
        if (listOf(draft.clientLabel, draft.projectTitle, draft.projectSummary).any(SensitiveInputPolicy::containsCredentialLikePayload)) {
            return "Credential-like material is blocked from CRM records."
        }
        return null
    }
}

/** Room stores AES-GCM ciphertext only. Phone/email credentials are not CRM schema fields. */
class ClientCrmRepository(
    private val dao: ClientCrmDao,
    private val cipher: SecurePayloadCipher = SecurePayloadCipher(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observeAll(): Flow<List<ClientCrmRecord>> = dao.observeAll().map { rows -> rows.mapNotNull(::decode) }

    suspend fun save(draft: ClientCrmDraft, explicitUserConsent: Boolean, id: String? = null): Result<ClientCrmRecord> = runCatching {
        require(explicitUserConsent) { "Explicit user consent is required before saving a client record." }
        ClientCrmRecordPolicy.validate(draft)?.let { throw IllegalArgumentException(it) }
        val now = clock()
        val existing = id?.let { dao.getById(it) }
        val recordId = existing?.id ?: UUID.randomUUID().toString()
        val created = existing?.createdAtEpochMs ?: now
        dao.upsert(ClientCrmEntity(
            id = recordId,
            encryptedClientLabel = cipher.encrypt(draft.clientLabel.trim()),
            encryptedProjectTitle = cipher.encrypt(draft.projectTitle.trim()),
            encryptedProjectSummary = cipher.encrypt(draft.projectSummary.trim()),
            encryptedSourceUrl = cipher.encrypt(draft.sourceUrl.trim()),
            status = draft.status,
            createdAtEpochMs = created,
            updatedAtEpochMs = now,
            followUpAtEpochMs = draft.followUpAtEpochMs
        ))
        ClientCrmRecord(recordId, draft.clientLabel.trim(), draft.projectTitle.trim(), draft.projectSummary.trim(),
            draft.sourceUrl.trim(), draft.status, created, now, draft.followUpAtEpochMs)
    }

    suspend fun updateStatus(id: String, status: String, explicitUserConsent: Boolean): Boolean {
        if (!explicitUserConsent || status !in CRM_ALLOWED_STATUSES || dao.getById(id) == null) return false
        return dao.updateStatus(id, status, clock()) > 0
    }
    suspend fun delete(id: String, explicitUserConsent: Boolean): Boolean = explicitUserConsent && dao.delete(id) > 0

    private fun decode(row: ClientCrmEntity): ClientCrmRecord? = runCatching {
        ClientCrmRecord(
            row.id, cipher.decrypt(row.encryptedClientLabel), cipher.decrypt(row.encryptedProjectTitle),
            cipher.decrypt(row.encryptedProjectSummary), cipher.decrypt(row.encryptedSourceUrl), row.status,
            row.createdAtEpochMs, row.updatedAtEpochMs, row.followUpAtEpochMs
        )
    }.getOrNull()
}
