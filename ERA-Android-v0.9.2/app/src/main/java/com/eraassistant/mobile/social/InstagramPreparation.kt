package com.eraassistant.mobile.social

data class InstagramDraft(
    val caption: String,
    val hashtags: String,
    val mediaUri: String
)

data class InstagramDraftValidation(
    val valid: Boolean,
    val message: String,
    val shareText: String
)

/** Builds a user-reviewed Instagram draft. It never publishes or taps Share. */
object InstagramPreparationPolicy {
    fun validate(caption: String, hashtags: String, mediaUri: String?): InstagramDraftValidation {
        val cleanCaption = caption.trim()
        val cleanHashtags = normalizeHashtags(hashtags)
        if (mediaUri.isNullOrBlank()) return InstagramDraftValidation(false, "Choose the exact photo or video first.", "")
        if (cleanCaption.isBlank() && cleanHashtags.isBlank()) return InstagramDraftValidation(false, "Add a caption or at least one hashtag.", "")
        if (cleanCaption.length > MAX_CAPTION_CHARS) return InstagramDraftValidation(false, "Caption is too long for this draft.", "")
        val shareText = listOf(cleanCaption, cleanHashtags).filter(String::isNotBlank).joinToString("\\n\\n")
        return InstagramDraftValidation(true, "Draft is ready for Instagram review. The final Share action stays manual.", shareText)
    }

    fun normalizeHashtags(input: String): String = input
        .split(Regex("[,\\s]+"))
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '_' } }
        .distinctBy(String::lowercase)
        .joinToString(" ") { "#$it" }

    private const val MAX_CAPTION_CHARS = 5_000
}
