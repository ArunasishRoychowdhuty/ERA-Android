package com.eraassistant.mobile.productivity
import org.junit.Assert.*
import org.junit.Test
class ClientCrmRecordPolicyTest {
    private fun draft(url: String = "https://example.com", summary: String = "Landing page redesign") =
        ClientCrmDraft("Acme Studio", "Website redesign", summary, url)
    @Test fun acceptsBoundedHttpsBusinessRecord() { assertNull(ClientCrmRecordPolicy.validate(draft())) }
    @Test fun rejectsCredentialsInCrmAndNonHttpsSources() {
        assertNotNull(ClientCrmRecordPolicy.validate(draft(summary = "Bearer abcdefghijklmnopqrstuvwxyz")))
        assertNotNull(ClientCrmRecordPolicy.validate(draft(url = "http://example.com")))
        assertNotNull(ClientCrmRecordPolicy.validate(draft().copy(status = "UNKNOWN")))
    }
}
