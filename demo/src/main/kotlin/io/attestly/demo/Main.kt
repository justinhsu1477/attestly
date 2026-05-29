package io.attestly.demo

import io.attestly.core.Confidence
import io.attestly.core.MemoryService
import io.attestly.core.RetentionPolicy
import io.attestly.core.UserId
import io.attestly.embedder.openai.OpenAiEmbedder
import io.attestly.store.sqlite.SqliteMemoryRepository

/**
 * Runnable end-to-end demo against the **real** OpenAI API and a persistent SQLite file.
 *
 * Unlike [EndToEndIntegrationTest] (which stubs OpenAI and runs on every build), this makes a live
 * embeddings call, so it needs a key:
 *
 * ```
 * OPENAI_API_KEY=sk-... ./gradlew :demo:run
 * ```
 *
 * Optionally set `ATTESTLY_DB` to point at a different SQLite file (defaults to `attestly-demo.db`).
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Set OPENAI_API_KEY to run this demo — it makes a real OpenAI embeddings call.")
        return
    }

    val dbPath = System.getenv("ATTESTLY_DB")?.takeIf { it.isNotBlank() } ?: "attestly-demo.db"
    val user = UserId("demo-user")

    SqliteMemoryRepository.open("jdbc:sqlite:$dbPath").use { repo ->
        val service = MemoryService(repo, OpenAiEmbedder(apiKey = apiKey))

        println("→ add")
        val memory = service.add(
            userId = user,
            text = "User prefers sci-fi novels",
            source = "chat:demo",
            originalQuote = "I just finished Project Hail Mary, loved it",
            confidence = Confidence(0.87),
            retentionPolicy = RetentionPolicy.parse("365d"),
        )
        println("  stored ${memory.id}")

        println("→ search \"book recommendations\"")
        service.search(user, "book recommendations", k = 5).forEach {
            println("  %.3f  %s".format(it.similarity, it.memory.text))
        }

        println("→ explain ${memory.id}")
        println("  ${service.explain(memory.id, user)?.toHumanReadable()}")

        println("→ forget ${memory.id}")
        println("  forgotten = ${service.forget(memory.id, user)}")
    }
}
