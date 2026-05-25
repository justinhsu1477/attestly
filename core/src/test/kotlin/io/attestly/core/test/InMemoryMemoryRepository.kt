package io.attestly.core.test

import io.attestly.core.EmbeddingModelTag
import io.attestly.core.Memory
import io.attestly.core.MemoryId
import io.attestly.core.MemoryRepository
import io.attestly.core.MemorySearchResult
import io.attestly.core.UserId
import kotlin.math.sqrt

/**
 * Hash-map-backed [MemoryRepository] for unit tests.
 *
 * Honours every contract the production adapter (pgvector) must:
 * - filters by [UserId] inside the lookup (multi-tenant isolation),
 * - filters by [EmbeddingModelTag] so cross-dimension vectors never get compared,
 * - normalises similarity into `[0.0, 1.0]` (cosine) before returning.
 *
 * Not thread-safe. Tests are single-threaded; if you reach for this from a multi-threaded
 * test, wrap mutation in a mutex of your own.
 */
class InMemoryMemoryRepository : MemoryRepository {

    private val store = linkedMapOf<MemoryId, Memory>()

    override fun save(memory: Memory): Memory {
        store[memory.id] = memory
        return memory
    }

    override fun findById(id: MemoryId): Memory? = store[id]

    override fun searchByUserAndVector(
        userId: UserId,
        queryVector: FloatArray,
        embeddingModel: EmbeddingModelTag,
        k: Int,
    ): List<MemorySearchResult> =
        store.values
            .asSequence()
            .filter { it.userId == userId && it.embeddingModel == embeddingModel }
            .filter { it.embedding.size == queryVector.size }
            .map { MemorySearchResult(it, cosineSimilarity(it.embedding, queryVector)) }
            .sortedByDescending { it.similarity }
            .take(k)
            .toList()

    override fun delete(id: MemoryId): Boolean = store.remove(id) != null

    /** Test-only accessor: every entry currently in the store. */
    fun all(): List<Memory> = store.values.toList()

    /** Test-only: wipe the store between tests. */
    fun clear() {
        store.clear()
    }

    companion object {
        internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "dim mismatch: ${a.size} vs ${b.size}" }
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }
            if (normA == 0.0 || normB == 0.0) return 0.0
            // Our FakeEmbedder produces non-negative components so cosine is in [0, 1].
            return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0.0, 1.0)
        }
    }
}
