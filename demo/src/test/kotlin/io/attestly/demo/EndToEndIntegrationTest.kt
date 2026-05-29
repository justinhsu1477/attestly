package io.attestly.demo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.attestly.core.Confidence
import io.attestly.core.MemoryService
import io.attestly.core.UserId
import io.attestly.embedder.openai.OpenAiEmbedder
import io.attestly.store.sqlite.SqliteMemoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The slice's headline test: a real [MemoryService] wired from the real adapters —
 * [SqliteMemoryRepository] over an in-memory SQLite database and [OpenAiEmbedder] over a WireMock
 * stand-in for OpenAI — driven through the full add → search → explain → forget lifecycle.
 *
 * This is what proves the core ports actually compose: data flows through real JDBC serialization
 * and real HTTP request/response handling, not test doubles. The embedder stub returns one vector
 * for "sci-fi" inputs and another for everything else, so search ranking is exercised for real.
 */
class EndToEndIntegrationTest {

    private lateinit var server: WireMockServer
    private lateinit var repo: SqliteMemoryRepository
    private lateinit var service: MemoryService

    @BeforeEach
    fun setUp() {
        server = WireMockServer(options().dynamicPort())
        server.start()
        // Default vector for any input…
        server.stubFor(
            post(urlEqualTo("/embeddings")).atPriority(5)
                .willReturn(embeddingResponse(0.0, 1.0, 0.0)),
        )
        // …but a distinct vector when the input mentions "sci-fi", so ranking is meaningful.
        server.stubFor(
            post(urlEqualTo("/embeddings")).atPriority(1)
                .withRequestBody(matchingJsonPath("$.input", containing("sci-fi")))
                .willReturn(embeddingResponse(1.0, 0.0, 0.0)),
        )

        repo = SqliteMemoryRepository.open("jdbc:sqlite::memory:")
        val embedder = OpenAiEmbedder(
            apiKey = "test-key",
            baseUrl = server.baseUrl(),
            retryBackoff = Duration.ZERO,
        )
        service = MemoryService(repo, embedder)
    }

    @AfterEach
    fun tearDown() {
        repo.close()
        server.stop()
    }

    @Test
    fun `full lifecycle - add, search, explain, forget`() {
        val alice = UserId("alice")

        val sciFi = service.add(
            userId = alice,
            text = "I love sci-fi novels",
            source = "chat:session_1",
            originalQuote = "I just finished Project Hail Mary, loved it",
            confidence = Confidence(0.9),
        )
        service.add(alice, "I love mystery novels", source = "chat:session_1")

        // search: the sci-fi memory ranks first (its vector matches the query's)
        val hits = service.search(alice, "give me sci-fi recommendations", k = 5)
        assertEquals(2, hits.size)
        assertEquals(sciFi.id, hits[0].memory.id)
        assertTrue(hits[0].similarity >= hits[1].similarity)

        // explain: audit receipt for the owner, with the captured quote and confidence
        val explanation = service.explain(sciFi.id, alice)
        assertNotNull(explanation)
        assertEquals("chat:session_1", explanation.source)
        assertEquals("I just finished Project Hail Mary, loved it", explanation.originalQuote)
        assertEquals(0.9, explanation.confidence.value)

        // forget: removes it, and search no longer returns it
        assertTrue(service.forget(sciFi.id, alice))
        val afterForget = service.search(alice, "give me sci-fi recommendations", k = 5)
        assertEquals(1, afterForget.size)
        assertEquals("I love mystery novels", afterForget[0].memory.text)
    }

    @Test
    fun `memories stay isolated per user across the full stack`() {
        service.add(UserId("alice"), "alice's private note", source = "chat")
        service.add(UserId("bob"), "bob's private note", source = "chat")

        val aliceHits = service.search(UserId("alice"), "anything at all", k = 5)
        assertEquals(1, aliceHits.size)
        assertTrue(aliceHits.all { it.memory.userId == UserId("alice") })
    }

    private fun embeddingResponse(vararg values: Double) = aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(
            """{"data":[{"embedding":[${values.joinToString(",")}]}],"model":"text-embedding-3-small"}""",
        )
}
