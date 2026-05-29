package io.attestly.embedder.openai

import io.attestly.core.Embedder
import io.attestly.core.EmbeddingException
import io.attestly.core.EmbeddingModelTag
import io.attestly.core.EmbeddingResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Embedder] backed by the OpenAI embeddings API.
 *
 * Uses the JDK's built-in [HttpClient] and kotlinx.serialization — no heavyweight SDK. Per the
 * core port's contract, transient failures are retried inside this class and only unrecoverable
 * ones surface as [EmbeddingException]:
 *
 * - **2xx** → parsed into an [EmbeddingResult].
 * - **429 / 5xx** and network [IOException]s → retried up to [maxRetries] times with exponential
 *   backoff, then surfaced as [EmbeddingException].
 * - **other 4xx** (e.g. 401 bad key, 400 malformed) → [EmbeddingException] immediately, no retry.
 *
 * The resulting vector is tagged with the model named in the response (falling back to the
 * configured [model]), so a later read can detect a model/dimension mismatch.
 *
 * @param baseUrl Override for testing or Azure/proxy deployments; no trailing slash.
 */
class OpenAiEmbedder(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val maxRetries: Int = 3,
    private val retryBackoff: Duration = Duration.ofMillis(500),
) : Embedder {

    private val configuredTag = EmbeddingModelTag(model)

    override fun embed(text: String): EmbeddingResult {
        val request = buildRequest(text)
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            if (attempt > 0) sleepBackoff(attempt - 1)

            val response: HttpResponse<String> = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                lastError = e // transient network failure → retry
                continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw EmbeddingException("Interrupted during OpenAI embeddings request", e)
            }

            val status = response.statusCode()
            when {
                status in 200..299 -> return parse(response.body())
                status == 429 || status >= 500 -> {
                    lastError = EmbeddingException(errorMessage(status, response.body()))
                    continue // transient server failure → retry
                }
                else -> throw EmbeddingException(errorMessage(status, response.body()))
            }
        }

        throw EmbeddingException(
            "OpenAI embeddings request failed after ${maxRetries + 1} attempt(s)",
            lastError,
        )
    }

    private fun buildRequest(text: String): HttpRequest {
        val body = json.encodeToString(EmbeddingRequest.serializer(), EmbeddingRequest(text, model))
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/embeddings"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun parse(body: String): EmbeddingResult {
        val parsed = try {
            json.decodeFromString(EmbeddingResponse.serializer(), body)
        } catch (e: Exception) {
            throw EmbeddingException("Failed to parse OpenAI embeddings response", e)
        }
        val data = parsed.data.firstOrNull()
            ?: throw EmbeddingException("OpenAI embeddings response contained no data")
        if (data.embedding.isEmpty()) {
            throw EmbeddingException("OpenAI embeddings response contained an empty vector")
        }
        val vector = FloatArray(data.embedding.size) { data.embedding[it].toFloat() }
        val tag = parsed.model?.let(::EmbeddingModelTag) ?: configuredTag
        return EmbeddingResult(vector, tag)
    }

    private fun sleepBackoff(retryIndex: Int) {
        val millis = retryBackoff.toMillis() * (1L shl retryIndex) // base, 2x, 4x, ...
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun errorMessage(status: Int, body: String): String =
        "OpenAI embeddings returned HTTP $status: ${body.take(200)}"

    @Serializable
    private data class EmbeddingRequest(val input: String, val model: String)

    @Serializable
    private data class EmbeddingResponse(
        val data: List<EmbeddingData> = emptyList(),
        val model: String? = null,
    )

    @Serializable
    private data class EmbeddingData(val embedding: List<Double> = emptyList())

    companion object {
        const val DEFAULT_MODEL = "text-embedding-3-small"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
