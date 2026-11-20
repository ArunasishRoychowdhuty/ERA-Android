package com.eraassistant.mobile.screen

import java.security.MessageDigest

/**
 * Stable identity for the actionable accessibility tree.
 * Capture timestamps are intentionally excluded: an unchanged screen keeps the same ID.
 * The complete serialized tree, including element states and hierarchy, is included so
 * any observable UI change invalidates an outstanding one-shot approval.
 */
object UiSnapshotFingerprint {
    fun of(
        packageName: String?,
        canonicalRootJson: String,
        nodeCount: Int,
        truncated: Boolean
    ): String {
        require(canonicalRootJson.isNotBlank()) { "A serialized UI root is required." }
        require(nodeCount >= 0) { "nodeCount cannot be negative." }
        val canonical = buildString {
            appendField(packageName)
            appendField(canonicalRootJson)
            appendField(nodeCount.toString())
            appendField(truncated.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: String?) {
        if (value == null) append("-1:")
        else append(value.length).append(':').append(value)
    }
}
