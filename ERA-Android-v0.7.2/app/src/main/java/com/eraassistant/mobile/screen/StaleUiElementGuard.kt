package com.eraassistant.mobile.screen

/** A semantic reference copied from a previous capture. The ephemeral nodeKey is diagnostic only. */
data class UiElementReference(
    val capturedAtEpochMs: Long,
    val packageName: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val role: String,
    val className: String?
)

sealed interface FreshElementResolution {
    data class Fresh(val element: UiElement) : FreshElementResolution
    data object Expired : FreshElementResolution
    data object WrongPackage : FreshElementResolution
    data object Changed : FreshElementResolution
    data object Missing : FreshElementResolution
    data object Ambiguous : FreshElementResolution
}

/** Re-resolves a prior reference against a newly captured tree; never uses coordinates or nodeKey. */
object StaleUiElementGuard {
    fun resolve(
        reference: UiElementReference,
        current: UiScreenSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 60_000
    ): FreshElementResolution {
        if (nowEpochMs - reference.capturedAtEpochMs > maxAgeMs || nowEpochMs < reference.capturedAtEpochMs) {
            return FreshElementResolution.Expired
        }
        if (!reference.packageName.isNullOrBlank() &&
            !reference.packageName.equals(current.packageName, ignoreCase = true)
        ) return FreshElementResolution.WrongPackage

        val selector = SemanticUiSelector(
            resourceId = reference.resourceId,
            text = reference.text,
            contentDescription = reference.contentDescription,
            role = reference.role,
            packageName = reference.packageName
        )
        return when (val result = SemanticUiSelectorEngine.resolve(current.root, selector)) {
            SelectorResolution.NotFound -> FreshElementResolution.Missing
            is SelectorResolution.Ambiguous -> FreshElementResolution.Ambiguous
            is SelectorResolution.Unique -> {
                if (!reference.className.isNullOrBlank() &&
                    !reference.className.equals(result.element.className, ignoreCase = true)
                ) FreshElementResolution.Changed else FreshElementResolution.Fresh(result.element)
            }
        }
    }

    fun from(snapshot: UiScreenSnapshot, element: UiElement): UiElementReference = UiElementReference(
        capturedAtEpochMs = snapshot.capturedAtEpochMs,
        packageName = element.packageName,
        resourceId = element.resourceId,
        text = element.text,
        contentDescription = element.contentDescription,
        role = element.role,
        className = element.className
    )
}
