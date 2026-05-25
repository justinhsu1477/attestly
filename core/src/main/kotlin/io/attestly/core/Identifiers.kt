package io.attestly.core

import java.util.UUID

/**
 * Opaque memory identifier.
 *
 * IDs are generated server-side via [random] and prefixed with `mem_` so they're recognisable
 * in logs and audit reports. The wrapped string is intentionally opaque — callers should never
 * parse it for structure.
 */
@JvmInline
value class MemoryId(val value: String) {
    init {
        require(value.isNotBlank()) { "MemoryId cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** Generate a fresh random id. */
        fun random(): MemoryId =
            MemoryId("mem_${UUID.randomUUID().toString().replace("-", "")}")
    }
}

/**
 * Owner of a memory.
 *
 * The service enforces user-scoped isolation: searches by one [UserId] never return entries
 * created under another. This is the single boundary that makes Attestly safe to use across
 * multi-tenant LLM applications.
 */
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Tag identifying which embedding model produced a vector.
 *
 * Stored alongside every memory so that a later read can detect a model mismatch — old vectors
 * from `text-embedding-3-small` (1536 dims) are not comparable to new vectors from
 * `text-embedding-3-large` (3072 dims). Without this tag, silent dimension mismatch would
 * surface as garbage similarity scores.
 */
@JvmInline
value class EmbeddingModelTag(val value: String) {
    init {
        require(value.isNotBlank()) { "EmbeddingModelTag cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Confidence score for a memory entry, on a closed `[0.0, 1.0]` range.
 *
 * Semantics:
 * - `1.0` — the entry is a direct quote of what the user said.
 * - `0.5–0.9` — LLM-extracted fact derived from user content.
 * - `< 0.5` — speculative or inferred; the explainer surfaces this prominently.
 *
 * Callers are not forced to pick a value; if omitted the service defaults to [FULL] on direct
 * ingest paths and prompts the embedding pipeline to attach a score on derived paths.
 */
@JvmInline
value class Confidence(val value: Double) {
    init {
        require(value in 0.0..1.0) { "Confidence must be in [0.0, 1.0], got $value" }
    }

    override fun toString(): String = "%.2f".format(value)

    companion object {
        val FULL = Confidence(1.0)
        val NONE = Confidence(0.0)
    }
}
