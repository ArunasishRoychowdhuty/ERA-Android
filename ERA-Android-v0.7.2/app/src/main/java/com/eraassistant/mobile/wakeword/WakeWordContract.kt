package com.eraassistant.mobile.wakeword

/** Wake-word engine abstraction. Requires a foreground, visible microphone session. */
interface WakeWordEngine {
    fun start(onDetected: () -> Unit)
    fun stop()
    val running: Boolean
}

/** Platform-neutral placeholder; no always-on microphone implementation is shipped in this scaffold. */
class WakeWordEngineStub : WakeWordEngine {
    override var running: Boolean = false
        private set
    override fun start(onDetected: () -> Unit) { throw NotImplementedError("Wake-word model/runtime not wired") }
    override fun stop() { running = false }
}
