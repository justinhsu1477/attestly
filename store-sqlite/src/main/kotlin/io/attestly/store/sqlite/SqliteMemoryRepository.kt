package io.attestly.store.sqlite

import io.attestly.core.Confidence
import io.attestly.core.EmbeddingModelTag
import io.attestly.core.Memory
import io.attestly.core.MemoryId
import io.attestly.core.MemoryRepository
import io.attestly.core.MemorySearchResult
import io.attestly.core.RetentionPolicy
import io.attestly.core.UserId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/**
 * SQLite-backed [MemoryRepository].
 *
 * Embeddings are stored as little-endian `float32` BLOBs and compared with application-level
 * cosine similarity — no native vector extension. This keeps the adapter pure-JVM and trivially
 * testable, at the cost of a full table scan per query. That trade-off is fine for the v0.1
 * slice (the goal is to validate the core ports against a real database); swapping in a
 * `sqlite-vec` / pgvector backed query later is a localised change behind the same interface.
 *
 * The repository owns a single JDBC [Connection] and is **not** thread-safe — matching the
 * core's stance that concurrency is the caller's concern. Close it when done (it is
 * [AutoCloseable]); for an in-memory database that also drops the data.
 *
 * Contract specifics honoured here:
 * - [searchByUserAndVector] filters by [UserId] and [EmbeddingModelTag] in SQL, never in memory.
 * - rows whose stored dimension differs from the query vector are skipped, not compared — a
 *   mismatched dimension yields a nonsense score, so we refuse rather than mislead.
 * - similarity is clamped to `[0,1]` to satisfy [MemorySearchResult]; see the note in
 *   [cosineSimilarity] about negative cosines from real-world embeddings.
 */
class SqliteMemoryRepository private constructor(
    private val connection: Connection,
) : MemoryRepository, AutoCloseable {

    init {
        connection.createStatement().use { st ->
            st.executeUpdate(SCHEMA)
            st.executeUpdate(INDEX)
        }
    }

    override fun save(memory: Memory): Memory {
        connection.prepareStatement(
            "INSERT INTO memories " +
                "(id, user_id, text, embedding, embedding_model, source, original_quote, " +
                "confidence, retention_policy, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, memory.id.value)
            ps.setString(2, memory.userId.value)
            ps.setString(3, memory.text)
            ps.setBytes(4, encodeEmbedding(memory.embedding))
            ps.setString(5, memory.embeddingModel.value)
            ps.setString(6, memory.source)
            ps.setString(7, memory.originalQuote) // nullable column; null is preserved
            ps.setDouble(8, memory.confidence.value)
            ps.setString(9, encodeRetention(memory.retentionPolicy))
            ps.setString(10, memory.createdAt.toString())
            ps.executeUpdate()
        }
        return memory
    }

    override fun findById(id: MemoryId): Memory? {
        connection.prepareStatement("SELECT * FROM memories WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rowToMemory(rs) else null
            }
        }
    }

    override fun searchByUserAndVector(
        userId: UserId,
        queryVector: FloatArray,
        embeddingModel: EmbeddingModelTag,
        k: Int,
    ): List<MemorySearchResult> {
        val results = mutableListOf<MemorySearchResult>()
        connection.prepareStatement(
            "SELECT * FROM memories WHERE user_id = ? AND embedding_model = ?",
        ).use { ps ->
            ps.setString(1, userId.value)
            ps.setString(2, embeddingModel.value)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val memory = rowToMemory(rs)
                    if (memory.embedding.size != queryVector.size) continue // reject dim mismatch
                    results += MemorySearchResult(memory, cosineSimilarity(memory.embedding, queryVector))
                }
            }
        }
        return results.sortedByDescending { it.similarity }.take(k)
    }

    override fun delete(id: MemoryId): Boolean {
        connection.prepareStatement("DELETE FROM memories WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            return ps.executeUpdate() > 0
        }
    }

    override fun close() {
        connection.close()
    }

    private fun rowToMemory(rs: ResultSet): Memory = Memory(
        id = MemoryId(rs.getString("id")),
        userId = UserId(rs.getString("user_id")),
        text = rs.getString("text"),
        embedding = decodeEmbedding(rs.getBytes("embedding")),
        embeddingModel = EmbeddingModelTag(rs.getString("embedding_model")),
        source = rs.getString("source"),
        originalQuote = rs.getString("original_quote"), // null when the column is NULL
        confidence = Confidence(rs.getDouble("confidence")),
        retentionPolicy = decodeRetention(rs.getString("retention_policy")),
        createdAt = Instant.parse(rs.getString("created_at")),
    )

    companion object {
        private val SCHEMA = """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding BLOB NOT NULL,
                embedding_model TEXT NOT NULL,
                source TEXT NOT NULL,
                original_quote TEXT,
                confidence REAL NOT NULL,
                retention_policy TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
        """.trimIndent()

        private const val INDEX =
            "CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id)"

        /** Open a repository over [jdbcUrl] (e.g. `jdbc:sqlite:memories.db` or `jdbc:sqlite::memory:`). */
        fun open(jdbcUrl: String): SqliteMemoryRepository =
            SqliteMemoryRepository(DriverManager.getConnection(jdbcUrl))

        /** Wrap an externally-managed [Connection] — caller owns its lifecycle. */
        fun usingConnection(connection: Connection): SqliteMemoryRepository =
            SqliteMemoryRepository(connection)

        // ── serialization ────────────────────────────────────────────────────────

        internal fun encodeEmbedding(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (value in vector) buffer.putFloat(value)
            return buffer.array()
        }

        internal fun decodeEmbedding(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
        }

        // RetentionPolicy has parse() but no symmetric serializer, so we encode here. We store the
        // Duration's ISO-8601 form (e.g. "PT8760H") rather than a day count, so non-whole-day
        // policies round-trip losslessly. Forever is a sentinel string.
        internal fun encodeRetention(policy: RetentionPolicy): String = when (policy) {
            RetentionPolicy.Forever -> "forever"
            is RetentionPolicy.For -> policy.duration.toString()
        }

        internal fun decodeRetention(spec: String): RetentionPolicy =
            if (spec == "forever") RetentionPolicy.Forever else RetentionPolicy.For(Duration.parse(spec))

        /**
         * Cosine similarity clamped to `[0,1]`.
         *
         * [MemorySearchResult] requires the score to land in `[0,1]`. Real embeddings can point in
         * opposing directions (negative cosine); we clamp those to `0` rather than remap, matching
         * the in-memory test repository. This loses ordering information among negatively-correlated
         * results — acceptable for the slice, but a sign the core's similarity scale assumption is
         * worth revisiting.
         */
        internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "dim mismatch: ${a.size} vs ${b.size}" }
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i].toDouble()
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }
            if (normA == 0.0 || normB == 0.0) return 0.0
            return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0.0, 1.0)
        }
    }
}
