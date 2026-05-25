package io.attestly.core.test

import io.attestly.core.Embedder
import io.attestly.core.EmbeddingModelTag
import io.attestly.core.EmbeddingResult
import kotlin.math.sqrt

/**
 * Deterministic [Embedder] for unit tests — no network, no LLM, no API key.
 *
 * Embedding scheme is a normalised bag-of-characters over a fixed-size vector:
 * each lowercased character contributes to position `(char.code mod dim)` and the resulting
 * vector is L2-normalised. Properties this gives us for tests:
 *
 * - **stable**: same text → same vector across runs.
 * - **case-insensitive**: `"Hello"` and `"hello"` embed identically.
 * - **monotonic-ish similarity**: texts that share more characters land closer in cosine
 *   space, so search ranking tests can assert "this match comes before that match" using
 *   real similarity arithmetic, not by stubbing the repository's return order.
 *
 * Default [dim] = 32: small enough to be readable in test failure dumps, large enough that
 * the modular collision rate is low for short test strings.
 */
class FakeEmbedder(
    val model: EmbeddingModelTag = EmbeddingModelTag("fake-embedder-v1"),
    val dim: Int = 32,
) : Embedder {

    override fun embed(text: String): EmbeddingResult {
        val raw = FloatArray(dim)
        text.lowercase().forEach { c ->
            val idx = (c.code and Int.MAX_VALUE) % dim
            raw[idx] += 1.0f
        }
        // L2-normalise so cosine similarity is the meaningful comparison.
        var sumSquares = 0.0
        for (v in raw) sumSquares += v.toDouble() * v.toDouble()
        val norm = sqrt(sumSquares).toFloat()
        if (norm > 0f) {
            for (i in raw.indices) raw[i] = raw[i] / norm
        } else {
            // All-zero text (e.g. ""): leave a deterministic seed so the vector isn't empty.
            raw[0] = 1.0f
        }
        return EmbeddingResult(raw, model)
    }
}
