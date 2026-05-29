package io.attestly.store.sqlite

import io.attestly.core.Confidence
import io.attestly.core.EmbeddingModelTag
import io.attestly.core.Memory
import io.attestly.core.MemoryId
import io.attestly.core.RetentionPolicy
import io.attestly.core.UserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SqliteMemoryRepository] against a real in-memory SQLite database.
 *
 * The repository must honour the same [io.attestly.core.MemoryRepository] contract the
 * production adapter is expected to: user-scoped isolation and embedding-model filtering inside
 * the query, dimension-mismatch rejection, cosine ranking on a `[0,1]` scale, and lossless
 * round-trip of every audit field (including the trickier ones — `FloatArray`, `RetentionPolicy`,
 * `Instant`, nullable `originalQuote`).
 */
class SqliteMemoryRepositoryTest {

    private lateinit var repo: SqliteMemoryRepository
    private var counter = 0

    @BeforeEach
    fun setUp() {
        repo = SqliteMemoryRepository.open("jdbc:sqlite::memory:")
    }

    @AfterEach
    fun tearDown() {
        repo.close()
    }

    private fun memory(
        id: String = "mem_${counter++}",
        userId: String = "alice",
        text: String = "some text",
        embedding: FloatArray = floatArrayOf(1f, 0f, 0f),
        model: String = "test-model",
        source: String = "chat",
        originalQuote: String? = null,
        confidence: Double = 1.0,
        retention: RetentionPolicy = RetentionPolicy.OneYear,
        createdAt: Instant = Instant.parse("2026-05-25T10:30:00Z"),
    ) = Memory(
        id = MemoryId(id),
        userId = UserId(userId),
        text = text,
        embedding = embedding,
        embeddingModel = EmbeddingModelTag(model),
        source = source,
        originalQuote = originalQuote,
        confidence = Confidence(confidence),
        retentionPolicy = retention,
        createdAt = createdAt,
    )

    // ── round-trip ──────────────────────────────────────────────────────────────

    @Test
    fun `save then findById round-trips every audit field`() {
        repo.save(
            memory(
                id = "mem_1",
                userId = "alice",
                text = "I prefer sci-fi",
                embedding = floatArrayOf(0.1f, 0.2f, 0.3f),
                model = "text-embedding-3-small",
                source = "chat:session_xyz",
                originalQuote = "I loved Project Hail Mary",
                confidence = 0.87,
                retention = RetentionPolicy.parse("365d"),
                createdAt = Instant.parse("2026-05-25T10:30:00Z"),
            ),
        )

        val found = repo.findById(MemoryId("mem_1"))
        assertNotNull(found)
        assertEquals(UserId("alice"), found.userId)
        assertEquals("I prefer sci-fi", found.text)
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), found.embedding)
        assertEquals(EmbeddingModelTag("text-embedding-3-small"), found.embeddingModel)
        assertEquals("chat:session_xyz", found.source)
        assertEquals("I loved Project Hail Mary", found.originalQuote)
        assertEquals(0.87, found.confidence.value)
        assertEquals(RetentionPolicy.parse("365d"), found.retentionPolicy)
        assertEquals(Instant.parse("2026-05-25T10:30:00Z"), found.createdAt)
    }

    @Test
    fun `round-trips a null originalQuote`() {
        repo.save(memory(id = "mem_nq", originalQuote = null))
        assertNull(repo.findById(MemoryId("mem_nq"))!!.originalQuote)
    }

    @Test
    fun `round-trips Forever retention`() {
        repo.save(memory(id = "mem_fv", retention = RetentionPolicy.Forever))
        assertEquals(RetentionPolicy.Forever, repo.findById(MemoryId("mem_fv"))!!.retentionPolicy)
    }

    @Test
    fun `round-trips sub-day retention durations`() {
        // For(Duration.ofHours(5)) proves we serialize the Duration itself, not just whole days.
        val policy = RetentionPolicy.For(Duration.ofHours(5))
        repo.save(memory(id = "mem_sd", retention = policy))
        assertEquals(policy, repo.findById(MemoryId("mem_sd"))!!.retentionPolicy)
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertNull(repo.findById(MemoryId("mem_nope")))
    }

    // ── search ──────────────────────────────────────────────────────────────────

    @Test
    fun `search is scoped to the requesting user`() {
        repo.save(memory(id = "m_alice", userId = "alice", embedding = floatArrayOf(1f, 0f, 0f)))
        repo.save(memory(id = "m_bob", userId = "bob", embedding = floatArrayOf(1f, 0f, 0f)))

        val hits = repo.searchByUserAndVector(
            UserId("alice"), floatArrayOf(1f, 0f, 0f), EmbeddingModelTag("test-model"), 5,
        )
        assertEquals(1, hits.size)
        assertEquals(UserId("alice"), hits[0].memory.userId)
    }

    @Test
    fun `search filters by embedding model`() {
        repo.save(memory(id = "m_old", model = "old-model", embedding = floatArrayOf(1f, 0f, 0f)))
        repo.save(memory(id = "m_new", model = "new-model", embedding = floatArrayOf(1f, 0f, 0f)))

        val hits = repo.searchByUserAndVector(
            UserId("alice"), floatArrayOf(1f, 0f, 0f), EmbeddingModelTag("new-model"), 5,
        )
        assertEquals(1, hits.size)
        assertEquals(MemoryId("m_new"), hits[0].memory.id)
    }

    @Test
    fun `search ranks by cosine similarity and respects k`() {
        repo.save(memory(id = "close", embedding = floatArrayOf(1f, 0f, 0f)))
        repo.save(memory(id = "mid", embedding = floatArrayOf(0.7f, 0.7f, 0f)))
        repo.save(memory(id = "far", embedding = floatArrayOf(0f, 0f, 1f)))

        val hits = repo.searchByUserAndVector(
            UserId("alice"), floatArrayOf(1f, 0f, 0f), EmbeddingModelTag("test-model"), 2,
        )
        assertEquals(2, hits.size)
        assertEquals(MemoryId("close"), hits[0].memory.id)
        assertTrue(hits[0].similarity >= hits[1].similarity)
        assertTrue(hits[0].similarity in 0.0..1.0)
    }

    @Test
    fun `search skips rows whose dimension does not match the query vector`() {
        repo.save(memory(id = "dim3", embedding = floatArrayOf(1f, 0f, 0f)))
        // A 2-dim query must not be compared against a 3-dim stored vector — and must not crash.
        val hits = repo.searchByUserAndVector(
            UserId("alice"), floatArrayOf(1f, 0f), EmbeddingModelTag("test-model"), 5,
        )
        assertTrue(hits.isEmpty())
    }

    // ── delete ──────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the row and reports success`() {
        repo.save(memory(id = "del"))
        assertTrue(repo.delete(MemoryId("del")))
        assertNull(repo.findById(MemoryId("del")))
    }

    @Test
    fun `delete returns false when nothing matched`() {
        assertFalse(repo.delete(MemoryId("ghost")))
    }
}
