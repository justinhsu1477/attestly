package io.attestly.core

import io.attestly.core.test.FakeEmbedder
import io.attestly.core.test.InMemoryMemoryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryServiceTest {

    private lateinit var repo: InMemoryMemoryRepository
    private lateinit var embedder: FakeEmbedder
    private lateinit var clock: Clock
    private lateinit var service: MemoryService

    private val fixedNow: Instant = Instant.parse("2026-05-25T10:30:00Z")
    private val alice = UserId("alice")
    private val bob = UserId("bob")

    @BeforeEach
    fun setUp() {
        repo = InMemoryMemoryRepository()
        embedder = FakeEmbedder()
        clock = Clock { fixedNow }
        service = MemoryService(
            repository = repo,
            embedder = embedder,
            clock = clock,
            defaultRetention = RetentionPolicy.OneYear,
        )
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    fun `add populates every audit field`() {
        val memory = service.add(
            userId = alice,
            text = "I prefer sci-fi novels",
            source = "chat:session_xyz",
            originalQuote = "I just finished Project Hail Mary, loved it",
            confidence = Confidence(0.87),
            retentionPolicy = RetentionPolicy.parse("365_days"),
        )

        assertEquals(alice, memory.userId)
        assertEquals("I prefer sci-fi novels", memory.text)
        assertEquals("chat:session_xyz", memory.source)
        assertEquals("I just finished Project Hail Mary, loved it", memory.originalQuote)
        assertEquals(0.87, memory.confidence.value)
        assertEquals(fixedNow, memory.createdAt)
        assertEquals(EmbeddingModelTag("fake-embedder-v1"), memory.embeddingModel)
        assertEquals(fixedNow.plus(Duration.ofDays(365)), memory.expiresAt())
        assertTrue(memory.id.value.startsWith("mem_"))
    }

    @Test
    fun `add falls back to service defaults when caller omits optional fields`() {
        val memory = service.add(
            userId = alice,
            text = "User's coffee order is oat-milk flat white",
            source = "chat",
        )

        assertEquals(Confidence.FULL, memory.confidence)
        assertNull(memory.originalQuote)
        assertEquals(fixedNow.plus(Duration.ofDays(365)), memory.expiresAt())
    }

    @Test
    fun `add persists the memory to the repository`() {
        val saved = service.add(alice, "hello world", "chat")
        assertEquals(saved, repo.findById(saved.id))
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    fun `search returns memories scoped to the requesting user only`() {
        service.add(alice, "alice likes sci-fi", "chat")
        service.add(bob, "bob likes mystery", "chat")

        val aliceHits = service.search(alice, "sci-fi", k = 5)
        assertTrue(aliceHits.all { it.memory.userId == alice }, "leaked bob's memory to alice")
        assertEquals(1, aliceHits.size)
    }

    @Test
    fun `search ranks the closer text higher than the looser match`() {
        service.add(alice, "I love sci-fi novels", "chat")
        service.add(alice, "I love mystery novels", "chat")

        val hits = service.search(alice, "sci-fi novels", k = 2)
        assertEquals(2, hits.size)
        assertTrue(
            hits[0].memory.text.contains("sci-fi"),
            "expected sci-fi memory ranked first, got ${hits.map { it.memory.text }}",
        )
        assertTrue(
            hits[0].similarity > hits[1].similarity,
            "expected strict ranking, got ${hits[0].similarity} vs ${hits[1].similarity}",
        )
    }

    @Test
    fun `search rejects k outside 1 to MAX_K rather than silently clamping`() {
        assertThrows<IllegalArgumentException> { service.search(alice, "q", k = 0) }
        assertThrows<IllegalArgumentException> { service.search(alice, "q", k = -1) }
        assertThrows<IllegalArgumentException> { service.search(alice, "q", k = MemoryService.MAX_K + 1) }
    }

    @Test
    fun `search rejects blank query`() {
        assertThrows<IllegalArgumentException> { service.search(alice, "", k = 5) }
        assertThrows<IllegalArgumentException> { service.search(alice, "   ", k = 5) }
    }

    // ── explain ───────────────────────────────────────────────────────────────

    @Test
    fun `explain returns an audit receipt for the owner`() {
        val saved = service.add(
            userId = alice,
            text = "fact",
            source = "chat",
            originalQuote = "the exact thing alice said",
            confidence = Confidence(0.8),
        )
        val explanation = service.explain(saved.id, alice)

        assertNotNull(explanation)
        assertEquals(saved.id, explanation.id)
        assertEquals("chat", explanation.source)
        assertEquals("the exact thing alice said", explanation.originalQuote)
        assertEquals(0.8, explanation.confidence.value)
        assertEquals(fixedNow, explanation.learnedAt)
    }

    @Test
    fun `explain returns null when a different user asks (anti-enumeration)`() {
        val saved = service.add(alice, "alice's secret", "chat")
        assertNull(service.explain(saved.id, bob))
    }

    @Test
    fun `explain returns null for an unknown id`() {
        assertNull(service.explain(MemoryId("mem_does_not_exist"), alice))
    }

    @Test
    fun `explain falls back to text when originalQuote was not captured`() {
        val saved = service.add(alice, "derived fact, no quote", "extraction")
        val explanation = service.explain(saved.id, alice)
        assertNotNull(explanation)
        assertEquals("derived fact, no quote", explanation.originalQuote)
    }

    // ── forget ────────────────────────────────────────────────────────────────

    @Test
    fun `forget deletes for the owner`() {
        val saved = service.add(alice, "delete me", "chat")
        assertTrue(service.forget(saved.id, alice))
        assertNull(repo.findById(saved.id))
    }

    @Test
    fun `forget refuses cross-tenant deletion and leaves the entry intact`() {
        val saved = service.add(alice, "alice's data", "chat")
        assertFalse(service.forget(saved.id, bob))
        assertNotNull(repo.findById(saved.id), "entry should still be there after rejected forget")
    }

    @Test
    fun `forget returns false for an unknown id`() {
        assertFalse(service.forget(MemoryId("mem_nope"), alice))
    }

    // ── explanation rendering ─────────────────────────────────────────────────

    @Test
    fun `human-readable explanation includes source quote confidence and expiry`() {
        val saved = service.add(
            userId = alice,
            text = "fact",
            source = "chat:s1",
            originalQuote = "the original",
            confidence = Confidence(0.87),
            retentionPolicy = RetentionPolicy.parse("30d"),
        )
        val rendered = service.explain(saved.id, alice)!!.toHumanReadable()

        assertTrue("chat:s1" in rendered, rendered)
        assertTrue("the original" in rendered, rendered)
        assertTrue("0.87" in rendered, rendered)
        assertTrue("kept until 2026-06-24" in rendered, rendered)
    }

    @Test
    fun `human-readable explanation says kept indefinitely for Forever retention`() {
        val saved = service.add(
            userId = alice,
            text = "fact",
            source = "chat",
            retentionPolicy = RetentionPolicy.Forever,
        )
        val rendered = service.explain(saved.id, alice)!!.toHumanReadable()
        assertTrue("kept indefinitely" in rendered, rendered)
    }
}
