package io.attestly.core

/**
 * Storage port for [Memory] entries.
 *
 * Implementations decide the physical representation — pgvector, SQLite + sqlite-vec, in-memory
 * for tests, or a future remote-call adapter. The core service treats this purely as a
 * key-value-with-similarity store; all audit and authorisation logic lives one layer up in
 * [MemoryService].
 *
 * Contract notes for implementers:
 * - [save] returns the persisted entry, which may differ from the input only in that storage
 *   may have rewritten timestamps to its own precision. The id is **never** rewritten.
 * - [searchByUserAndVector] is required to filter by [UserId] inside the storage query, not in
 *   application memory. Returning entries from a different user is a security bug.
 * - [searchByUserAndVector] is also required to reject vectors whose dimension does not match
 *   the stored vectors for the given [EmbeddingModelTag]. Mixing dimensions silently produces
 *   nonsense similarity scores; explicit rejection lets the caller switch models cleanly.
 */
interface MemoryRepository {

    /** Persist (insert) a new entry. Returns the persisted form. */
    fun save(memory: Memory): Memory

    /** Look up by id; returns `null` if absent. Does not filter by user — that's [MemoryService]'s job. */
    fun findById(id: MemoryId): Memory?

    /**
     * Top-[k] entries by similarity to [queryVector], scoped to [userId] and restricted to
     * entries indexed under [embeddingModel].
     *
     * Results are ordered from most-similar to least-similar.
     */
    fun searchByUserAndVector(
        userId: UserId,
        queryVector: FloatArray,
        embeddingModel: EmbeddingModelTag,
        k: Int,
    ): List<MemorySearchResult>

    /** Delete by id; returns `true` if a row was removed, `false` if nothing matched. */
    fun delete(id: MemoryId): Boolean
}

/**
 * A search hit: the matched [Memory] plus its similarity to the query vector.
 *
 * [similarity] is on a `[0.0, 1.0]` scale where `1.0` means the query and the stored vector
 * point in the same direction (cosine similarity convention). Implementations that natively
 * return an L2 distance should normalise before populating this field, so callers get a
 * consistent scale regardless of backing store.
 */
data class MemorySearchResult(
    val memory: Memory,
    val similarity: Double,
) {
    init {
        require(similarity in 0.0..1.0) { "Similarity must be in [0.0, 1.0], got $similarity" }
    }
}
