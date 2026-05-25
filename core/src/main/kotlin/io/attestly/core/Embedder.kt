package io.attestly.core

/**
 * Port for converting text into a numeric vector representation.
 *
 * The interface is intentionally minimal — implementations (OpenAI, Azure, Ollama, Voyage…)
 * own retry, rate-limiting, and caching. The core only cares about getting back a vector and
 * the tag describing which model produced it, so a later read can detect model mismatches.
 *
 * Declared `fun interface` so callers can pass a lambda in tests:
 * ```
 * val fake = Embedder { text -> EmbeddingResult(floatArrayOf(0.1f, 0.2f), MODEL) }
 * ```
 *
 * Implementations are expected to be **blocking**; bridging to async (virtual threads, reactive)
 * is the caller's choice. This keeps the v0.1 Java/Spring integration story trivial.
 */
fun interface Embedder {
    /**
     * @throws EmbeddingException if the underlying provider returns an unrecoverable error.
     */
    fun embed(text: String): EmbeddingResult
}

/**
 * The result of an embedding call.
 *
 * Not a data class — the [vector] field is a [FloatArray], whose generated equals uses
 * referential identity. Explicit `contentEquals`-based implementation below preserves
 * value-equality semantics that callers expect.
 */
class EmbeddingResult(
    val vector: FloatArray,
    val model: EmbeddingModelTag,
) {
    init {
        require(vector.isNotEmpty()) { "EmbeddingResult.vector cannot be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is EmbeddingResult &&
            model == other.model &&
            vector.contentEquals(other.vector)

    override fun hashCode(): Int = 31 * vector.contentHashCode() + model.hashCode()

    override fun toString(): String =
        "EmbeddingResult(model=$model, dim=${vector.size})"
}

/**
 * Thrown by [Embedder] implementations for unrecoverable upstream failures (auth, quota,
 * malformed response). Transient errors (timeouts, 429s) should be retried inside the
 * implementation, not surfaced here.
 */
class EmbeddingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
