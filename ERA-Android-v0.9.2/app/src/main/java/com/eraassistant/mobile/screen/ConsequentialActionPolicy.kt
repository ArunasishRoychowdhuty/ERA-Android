package com.eraassistant.mobile.screen

enum class ConsequentialActionKind(val label: String) {
    SEND("send"),
    SUBMIT("submit"),
    PUBLISH("publish"),
    DELETE("delete"),
    PAYMENT("payment"),
    TRANSFER("transfer"),
    PURCHASE("purchase")
}

/**
 * Detects irreversible or externally visible UI controls. Detection is descriptive only;
 * the final dispatch still requires exact screen binding and a fresh one-time user approval.
 */
object ConsequentialActionPolicy {
    private val markers = linkedMapOf(
        ConsequentialActionKind.PAYMENT to listOf("pay", "payment", "pay now", "make payment", "পেমেন্ট", "পরিশোধ"),
        ConsequentialActionKind.TRANSFER to listOf("transfer", "send money", "ট্রান্সফার", "টাকা পাঠান"),
        ConsequentialActionKind.PURCHASE to listOf("purchase", "buy now", "checkout", "place order", "অর্ডার করুন", "কিনুন"),
        ConsequentialActionKind.DELETE to listOf("delete", "remove permanently", "ডিলিট", "মুছে ফেল"),
        ConsequentialActionKind.PUBLISH to listOf("publish", "post now", "publish now", "প্রকাশ করুন", "পোস্ট করুন"),
        ConsequentialActionKind.SEND to listOf("send", "send now", "পাঠান", "পাঠিয়ে দিন", "পাঠিয়ে দিন"),
        ConsequentialActionKind.SUBMIT to listOf("submit", "submit now", "confirm submission", "জমা দিন", "দাখিল করুন")
    )

    fun classify(element: UiElement): ConsequentialActionKind? {
        if (!element.clickable || !element.enabled || !element.visible) return null
        val label = listOfNotNull(element.text, element.contentDescription, element.hintText, element.resourceId)
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[_./:-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (label.isBlank()) return null
        return markers.entries.firstOrNull { (_, words) ->
            words.any { marker -> containsPhrase(label, marker) }
        }?.key
    }

    fun classifyLabel(label: String): ConsequentialActionKind? {
        val normalized = label.lowercase().replace(Regex("[_./:-]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null
        return markers.entries.firstOrNull { (_, words) ->
            words.any { marker -> containsPhrase(normalized, marker) }
        }?.key
    }

    /**
     * Payment voice approval is offered only when a currency amount is visible in the
     * non-editable accessibility tree. Editable values are intentionally not read.
     */
    fun buildSummary(
        kind: ConsequentialActionKind,
        snapshot: UiScreenSnapshot,
        target: UiElement
    ): String? {
        val labels = mutableListOf<String>()
        fun visit(node: UiElement) {
            if (node.visible && !node.editable && !node.password) {
                listOfNotNull(node.text, node.contentDescription)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .filterNot(SensitiveInputPolicy::containsCredentialLikePayload)
                    .forEach { labels += it.take(100) }
            }
            node.children.forEach(::visit)
        }
        visit(snapshot.root)
        val pageText = labels.joinToString(" ")
        val visibleAmounts = CURRENCY_AMOUNT.findAll(pageText)
            .map { it.value.replace(Regex("\\s+"), " ").trim().lowercase() }
            .distinct()
            .toList()
        // A screen with several different visible amounts is ambiguous (for example,
        // account balance vs. checkout total). Never guess which amount is being approved.
        if (visibleAmounts.size > 1) return null
        val amount = visibleAmounts.singleOrNull()
        if (kind == ConsequentialActionKind.PAYMENT || kind == ConsequentialActionKind.TRANSFER || kind == ConsequentialActionKind.PURCHASE) {
            if (amount == null) return null
        }
        val targetLabel = listOfNotNull(target.text, target.contentDescription)
            .firstOrNull(String::isNotBlank)
            ?.take(72)
            ?: kind.label
        val app = snapshot.packageName?.substringAfterLast('.')?.take(36).orEmpty().ifBlank { "the current app" }
        return buildString {
            append("Confirm ").append(kind.label).append(" in ").append(app)
            append(": ").append(targetLabel)
            if (amount != null) append(". Visible amount: ").append(amount)
        }.take(180)
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val escaped = Regex.escape(phrase.lowercase())
        return Regex("(^|[^\\p{L}\\p{N}])$escaped([^\\p{L}\\p{N}]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    private val CURRENCY_AMOUNT = Regex("(?i)(?:₹|INR|Rs\\.?\\s*)\\s?[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
}
