package com.eraassistant.mobile.browser

/** Chrome automation via supported remote-debugging/DevTools bridge, not private Android DOM access. */
data class BrowserTarget(val id: String, val title: String, val url: String)
data class DomNode(val nodeId: String, val role: String?, val name: String?, val tag: String?, val selectorHint: String?, val enabled: Boolean)
data class BrowserSnapshot(val target: BrowserTarget, val nodes: List<DomNode>, val capturedAtMillis: Long)

interface ChromeDevToolsBridge {
    suspend fun listTargets(): List<BrowserTarget>
    suspend fun snapshot(targetId: String): BrowserSnapshot
    suspend fun proposeAction(targetId: String, nodeId: String, action: String, value: String? = null): String
}

/** No connection is opened automatically; bridge requires an explicitly configured user-owned debugging session. */
class ChromeBridgeStub : ChromeDevToolsBridge {
    override suspend fun listTargets() = emptyList<BrowserTarget>()
    override suspend fun snapshot(targetId: String): BrowserSnapshot = throw IllegalStateException("Chrome DevTools bridge is not configured")
    override suspend fun proposeAction(targetId: String, nodeId: String, action: String, value: String?): String = throw IllegalStateException("Bridge not configured")
}
