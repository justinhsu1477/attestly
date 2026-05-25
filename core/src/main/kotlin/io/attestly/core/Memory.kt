package io.attestly.core

import java.time.Instant

/**
 * A single memory entry — the unit of storage Attestly is built around.
 *
 * Every field beyond [id], [userId], and [text] is part of the audit contract that distinguishes
 * Attestly from a bare embedding-and-text table. The [source], [originalQuote], [confidence],
 * and [retentionPolicy] together answer the four questions a compliance reviewer will ask:
 *
 * - *Where did this come from?* → [source]
 * - *What were the exact words?* → [originalQuote]
 * - *How sure are we?* → [confidence]
 * - *How long do we keep it?* → [retentionPolicy]
 *
 * `Memory` is **not** a `data class` because the [embedding] field is a [FloatArray], whose
 * generated `equals` would use referential identity and break sensible comparisons. Identity is
 * instead defined on [id] alone — two snapshots of the same entry compare equal even if a later
 * read returned a fresher embedding or an updated confidence.
 *
 * @property id              Server-generated, opaque.
 * @property userId          Owner; the service rejects cross-user reads.
 * @property text            Canonical text for this memory — usually the same as [originalQuote]
 *                           on direct-ingest paths, or an LLM-derived fact on extraction paths.
 * @property embedding       Vector produced by [embeddingModel]; dimension is model-specific.
 * @property embeddingModel  Tag identifying which model produced [embedding] so callers can
 *                           detect dimension mismatches across model upgrades.
 * @property source          Free-form provenance tag (e.g. `"chat:session_xyz"`,
 *                           `"import:notes_2026_05"`, `"mcp:cursor"`). Surfaced verbatim in
 *                           explanations.
 * @property originalQuote   The exact text the user / source produced, if available. `null`
 *                           when [text] is the original quote itself, or when the memory was
 *                           synthesised without a single attributable utterance.
 * @property confidence      Reliability score; see [Confidence] for semantics.
 * @property retentionPolicy When this entry expires.
 * @property createdAt       Wall-clock time of ingest, supplied by a [Clock] so tests can pin it.
 */
class Memory(
    val id: MemoryId,
    val userId: UserId,
    val text: String,
    val embedding: FloatArray,
    val embeddingModel: EmbeddingModelTag,
    val source: String,
    val originalQuote: String?,
    val confidence: Confidence,
    val retentionPolicy: RetentionPolicy,
    val createdAt: Instant,
) {
    init {
        require(text.isNotBlank()) { "Memory.text cannot be blank" }
        require(source.isNotBlank()) { "Memory.source cannot be blank" }
        require(embedding.isNotEmpty()) { "Memory.embedding cannot be empty" }
        originalQuote?.let {
            require(it.isNotBlank()) { "Memory.originalQuote, when present, cannot be blank" }
        }
    }

    /** Identity is the id; two snapshots of the same entry are equal even if fields differ. */
    override fun equals(other: Any?): Boolean = other is Memory && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Memory(id=$id, userId=$userId, source='$source', confidence=$confidence)"

    /**
     * Absolute expiry instant, derived from [createdAt] + [retentionPolicy].
     *
     * Returns `null` for [RetentionPolicy.Forever] — the purge job interprets `null` as "skip".
     */
    fun expiresAt(): Instant? = when (val p = retentionPolicy) {
        RetentionPolicy.Forever -> null
        is RetentionPolicy.For -> createdAt.plus(p.duration)
    }

    /** Project to the public-facing audit receipt returned by [MemoryService.explain]. */
    fun toExplanation(): MemoryExplanation = MemoryExplanation(
        id = id,
        learnedAt = createdAt,
        source = source,
        originalQuote = originalQuote ?: text,
        confidence = confidence,
        retentionExpiresAt = expiresAt(),
        embeddingModel = embeddingModel,
    )
}
