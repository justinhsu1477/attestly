package io.attestly.core

import java.time.Instant

/**
 * The audit receipt returned by [MemoryService.explain].
 *
 * This is the surface a user-facing app shows when someone asks "how does the AI know this?",
 * and the surface a compliance reviewer reads when auditing what an LLM-driven product remembers.
 * Deliberately separate from [Memory] so the public projection can evolve (or be redacted)
 * without changing storage representation.
 *
 * Notable choices:
 * - [originalQuote] is required here even though it's nullable on [Memory]; the service falls
 *   back to the memory's [Memory.text] when no quote was captured, so a missing quote never
 *   leaves the explanation with a blank field.
 * - [retentionExpiresAt] is `null` for [RetentionPolicy.Forever] — UI layers should render this
 *   as "kept indefinitely" rather than "expires at ?".
 * - [embeddingModel] is included so a downstream tool can detect that this memory was indexed
 *   under a since-retired model and may yield stale similarity scores.
 */
data class MemoryExplanation(
    val id: MemoryId,
    val learnedAt: Instant,
    val source: String,
    val originalQuote: String,
    val confidence: Confidence,
    val retentionExpiresAt: Instant?,
    val embeddingModel: EmbeddingModelTag,
) {
    /**
     * Human-readable rendering for chat UIs and CLI output.
     *
     * Example:
     * > I learned this on 2026-05-25T10:30:00Z from chat:session_xyz when you said:
     * > "I just finished Project Hail Mary, loved it" (confidence 0.87, kept until 2027-05-25).
     */
    fun toHumanReadable(): String {
        val expiry = retentionExpiresAt
            ?.let { ", kept until ${it.toString().substringBefore('T')}" }
            ?: ", kept indefinitely"
        return "I learned this on $learnedAt from $source when you said: " +
            "\"$originalQuote\" (confidence $confidence$expiry)."
    }
}
