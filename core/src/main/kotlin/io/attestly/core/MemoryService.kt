package io.attestly.core

/**
 * The single entry point for all memory operations.
 *
 * Composes a [MemoryRepository] and an [Embedder] into the four operations Attestly exposes:
 * [add], [search], [explain], [forget]. This is also where multi-tenant isolation is enforced —
 * the repository is intentionally user-agnostic on lookups by id; the service refuses to return
 * (or delete, or explain) a memory whose [Memory.userId] differs from the requesting user.
 *
 * Threading: all operations are blocking. Concurrency is the caller's concern. For Spring
 * users, this is exactly what `@Service` beans expect; for reactive code, wrap in your own
 * scheduler. v0.1 deliberately avoids picking a coroutine flavour.
 *
 * Defaults: when callers omit [add]'s optional fields, the service uses [Confidence.FULL] (the
 * caller is asserting a direct quote) and [defaultRetention] (a year, unless configured
 * otherwise). Both are write-time decisions; nothing implicit happens after.
 */
class MemoryService(
    private val repository: MemoryRepository,
    private val embedder: Embedder,
    private val clock: Clock = Clock.System,
    private val defaultRetention: RetentionPolicy = RetentionPolicy.OneYear,
) {

    /**
     * Ingest a new memory.
     *
     * @param userId           Owner of the entry. Required for isolation; the service tags every
     *                         row with this and never lets another user retrieve it.
     * @param text             Canonical text — the thing the system will surface back later.
     * @param source           Free-form provenance tag; surfaced verbatim in [explain].
     * @param originalQuote    The user's exact words, if [text] is a derived fact rather than
     *                         the raw quote. When omitted, [explain] falls back to [text].
     * @param confidence       Reliability of this entry; defaults to [Confidence.FULL] for
     *                         direct ingest. LLM-extraction paths should supply a lower value.
     * @param retentionPolicy  Per-entry override of [defaultRetention].
     */
    fun add(
        userId: UserId,
        text: String,
        source: String,
        originalQuote: String? = null,
        confidence: Confidence = Confidence.FULL,
        retentionPolicy: RetentionPolicy = defaultRetention,
    ): Memory {
        val embedding = embedder.embed(text)
        val memory = Memory(
            id = MemoryId.random(),
            userId = userId,
            text = text,
            embedding = embedding.vector,
            embeddingModel = embedding.model,
            source = source,
            originalQuote = originalQuote,
            confidence = confidence,
            retentionPolicy = retentionPolicy,
            createdAt = clock.now(),
        )
        return repository.save(memory)
    }

    /**
     * Top-[k] memories most similar to [query], scoped to [userId].
     *
     * The bound on [k] is enforced rather than silently clamped — silent clamping prevents an
     * LLM caller from realising it passed an out-of-range value and adjusting. This is the same
     * choice made in the upstream OpenHuman MCP server after a real bug report.
     *
     * @throws IllegalArgumentException if [k] is outside `1..MAX_K`.
     */
    fun search(userId: UserId, query: String, k: Int = DEFAULT_K): List<MemorySearchResult> {
        require(k in 1..MAX_K) { "k must be in 1..$MAX_K, got $k" }
        require(query.isNotBlank()) { "Search query cannot be blank" }

        val embedded = embedder.embed(query)
        return repository.searchByUserAndVector(
            userId = userId,
            queryVector = embedded.vector,
            embeddingModel = embedded.model,
            k = k,
        )
    }

    /**
     * Audit receipt for a single memory.
     *
     * Returns `null` either when no such memory exists **or** when it exists but belongs to a
     * different user. Conflating not-found with not-yours is deliberate — distinguishing them
     * would let a caller enumerate memory ids across tenants.
     */
    fun explain(id: MemoryId, requestingUserId: UserId): MemoryExplanation? {
        val memory = repository.findById(id) ?: return null
        if (memory.userId != requestingUserId) return null
        return memory.toExplanation()
    }

    /**
     * Delete a single memory.
     *
     * Returns `true` only when the entry exists, belongs to [requestingUserId], and the
     * underlying repository confirms removal. Cross-tenant attempts return `false` rather than
     * throwing, for the same enumeration-prevention reason as [explain].
     */
    fun forget(id: MemoryId, requestingUserId: UserId): Boolean {
        val memory = repository.findById(id) ?: return false
        if (memory.userId != requestingUserId) return false
        return repository.delete(id)
    }

    companion object {
        const val DEFAULT_K = 5

        /** Largest [search] page size the service will honour. */
        const val MAX_K = 50
    }
}
