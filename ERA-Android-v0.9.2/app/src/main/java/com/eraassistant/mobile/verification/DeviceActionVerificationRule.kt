package com.eraassistant.mobile.verification

import com.eraassistant.mobile.capability.StructuredActionResult
import com.eraassistant.mobile.observation.NormalizedObservation

/**
 * Independent post-action verification for Android system settings.
 * Intents that merely hand off to another app remain user-verifiable, not machine-verified.
 */
class DeviceActionVerificationRule : VerificationRule {
    override val name: String = "AndroidDeviceStateRule"

    override fun supports(criterion: String): Boolean = criterion.startsWith("era.device.")

    override fun evaluate(
        expectedCriteria: List<String>,
        preObservation: NormalizedObservation?,
        postObservation: NormalizedObservation?,
        actionResult: StructuredActionResult
    ): VerificationEvidence {
        val criterion = expectedCriteria.singleOrNull().orEmpty()
        val metadata = postObservation?.rawMetadata.orEmpty()

        fun unavailable(reason: String) = VerificationEvidence(
            ruleName = name,
            status = VerificationStatus.VERIFICATION_UNAVAILABLE,
            expectedOutcome = criterion,
            actualObservation = metadata.entries.joinToString { "${it.key}=${it.value}" }.take(500).ifBlank { "No device-state metadata." },
            rationale = reason
        )

        fun measured(expected: String, actual: String?, field: String): VerificationEvidence {
            if (actual == null) return unavailable("Android did not expose a post-action value for $field.")
            val verified = actual.equals(expected, ignoreCase = true)
            return VerificationEvidence(
                ruleName = name,
                status = if (verified) VerificationStatus.VERIFIED else VerificationStatus.NOT_VERIFIED,
                expectedOutcome = "$field=$expected",
                actualObservation = "$field=$actual",
                rationale = if (verified) "A fresh Android system query matches the requested state."
                    else "A fresh Android system query does not match the requested state."
            )
        }

        return when {
            criterion.startsWith("era.device.torch.enabled=") -> {
                val expected = criterion.substringAfter('=')
                measured(expected, metadata["torch.enabled"], "torch.enabled")
            }
            criterion.startsWith("era.device.volume.percent|") -> {
                val parts = criterion.split('|').drop(1).mapNotNull {
                    val split = it.split('=', limit = 2)
                    if (split.size == 2) split[0] to split[1] else null
                }.toMap()
                val expectedPercent = parts["percent"]?.toIntOrNull()
                    ?: return unavailable("Volume verification criterion is malformed.")
                val current = metadata["volume.after"]?.toIntOrNull()
                    ?: return unavailable("Android did not expose the resulting volume index.")
                val maximum = metadata["volume.max"]?.toIntOrNull()
                    ?: return unavailable("Android did not expose the stream maximum volume.")
                if (maximum <= 0) return unavailable("Android exposed an invalid stream maximum.")
                val expectedIndex = (maximum * expectedPercent / 100f).toInt().coerceIn(0, maximum)
                val verified = current == expectedIndex
                VerificationEvidence(
                    ruleName = name,
                    status = if (verified) VerificationStatus.VERIFIED else VerificationStatus.NOT_VERIFIED,
                    expectedOutcome = "volume.index=$expectedIndex",
                    actualObservation = "volume.index=$current; max=$maximum",
                    rationale = if (verified) "AudioManager returned the requested resulting stream level."
                        else "AudioManager returned a different resulting stream level."
                )
            }
            criterion.startsWith("era.device.volume.adjust|") -> {
                val parts = criterion.split('|').drop(1).mapNotNull {
                    val split = it.split('=', limit = 2)
                    if (split.size == 2) split[0] to split[1] else null
                }.toMap()
                val direction = parts["direction"]?.uppercase()
                    ?: return unavailable("Volume adjustment direction is missing.")
                val before = metadata["volume.before"]?.toIntOrNull()
                    ?: return unavailable("Pre-adjustment volume was not captured.")
                val after = metadata["volume.after"]?.toIntOrNull()
                    ?: return unavailable("Post-adjustment volume was not captured.")
                val verified = when (direction) {
                    "UP" -> after > before
                    "DOWN" -> after < before
                    else -> return unavailable("Volume direction is unsupported.")
                }
                VerificationEvidence(
                    ruleName = name,
                    status = if (verified) VerificationStatus.VERIFIED else VerificationStatus.NOT_VERIFIED,
                    expectedOutcome = "volume $direction",
                    actualObservation = "before=$before; after=$after",
                    rationale = if (verified) "AudioManager confirmed the stream level changed in the requested direction."
                        else "The resulting volume did not change in the requested direction (it may be at a limit)."
                )
            }
            criterion.startsWith("era.device.app.launch|") ->
                unavailable("Android does not reliably expose the foreground target app here without additional usage-access permission.")
            criterion.startsWith("era.device.intent|") ->
                unavailable("The Android intent handoff is not independent proof that the target screen or draft became usable.")
            else -> unavailable("No independent verifier is implemented for this device action.")
        }
    }
}
