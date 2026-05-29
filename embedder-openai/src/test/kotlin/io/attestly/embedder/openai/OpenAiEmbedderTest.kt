package io.attestly.embedder.openai

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.attestly.core.EmbeddingException
import io.attestly.core.EmbeddingModelTag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Exercises [OpenAiEmbedder] against a WireMock stand-in for the OpenAI API — no network, no key.
 *
 * Covers the contract the core [io.attestly.core.Embedder] port expects: a successful call yields
 * a vector tagged with the model, transient failures (5xx) are retried, and unrecoverable ones
 * (4xx) surface as [EmbeddingException] without retrying.
 */
class OpenAiEmbedderTest {

    private lateinit var server: WireMockServer

    @BeforeEach
    fun setUp() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun embedder(maxRetries: Int = 3) = OpenAiEmbedder(
        apiKey = "test-key",
        baseUrl = server.baseUrl(),
        maxRetries = maxRetries,
        retryBackoff = Duration.ZERO,
    )

    @Test
    fun `embed returns the vector and model from a successful response`() {
        server.stubFor(
            post(urlEqualTo("/embeddings")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SUCCESS_BODY),
            ),
        )

        val result = embedder().embed("hello")

        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), result.vector)
        assertEquals(EmbeddingModelTag("text-embedding-3-small"), result.model)
    }

    @Test
    fun `embed sends the api key and input in the request`() {
        server.stubFor(
            post(urlEqualTo("/embeddings")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SUCCESS_BODY),
            ),
        )

        embedder().embed("hello")

        server.verify(
            postRequestedFor(urlEqualTo("/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(equalToJson("""{"input":"hello","model":"text-embedding-3-small"}""")),
        )
    }

    @Test
    fun `embed throws EmbeddingException on a 401 without retrying`() {
        server.stubFor(
            post(urlEqualTo("/embeddings")).willReturn(
                aResponse().withStatus(401).withBody("""{"error":{"message":"bad key"}}"""),
            ),
        )

        assertThrows<EmbeddingException> { embedder().embed("hello") }
        server.verify(1, postRequestedFor(urlEqualTo("/embeddings")))
    }

    @Test
    fun `embed retries on a 503 then succeeds`() {
        server.stubFor(
            post(urlEqualTo("/embeddings")).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"),
        )
        server.stubFor(
            post(urlEqualTo("/embeddings")).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY),
                ),
        )

        val result = embedder().embed("hello")

        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), result.vector)
        server.verify(2, postRequestedFor(urlEqualTo("/embeddings")))
    }

    @Test
    fun `embed gives up after exhausting retries on persistent 500`() {
        server.stubFor(
            post(urlEqualTo("/embeddings")).willReturn(aResponse().withStatus(500)),
        )

        assertThrows<EmbeddingException> { embedder(maxRetries = 2).embed("hello") }
        server.verify(3, postRequestedFor(urlEqualTo("/embeddings"))) // 1 initial + 2 retries
    }

    private companion object {
        const val SUCCESS_BODY =
            """{"object":"list","data":[{"object":"embedding","index":0,""" +
                """"embedding":[0.1,0.2,0.3]}],"model":"text-embedding-3-small",""" +
                """"usage":{"prompt_tokens":1,"total_tokens":1}}"""
    }
}
