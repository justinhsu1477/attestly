package io.attestly.core

import java.time.Duration

/**
 * Declares how long a memory entry should persist before automatic deletion.
 *
 * RetentionPolicy is a write-time decision: the audit layer records it on the entry itself,
 * and a separate purge job (out of scope for v0.1) honours it. This separation means the
 * core domain never deletes anything implicitly — deletion is always either explicit
 * ([MemoryService.forget]) or scheduled by an external operator.
 */
sealed interface RetentionPolicy {

    /** Memory is kept indefinitely. */
    data object Forever : RetentionPolicy

    /** Memory expires after [duration]. */
    data class For(val duration: Duration) : RetentionPolicy {
        init {
            require(!duration.isNegative && !duration.isZero) {
                "Retention duration must be positive, got $duration"
            }
        }
    }

    companion object {
        // Common presets so callers don't reach for Duration arithmetic.
        val OneDay: RetentionPolicy = For(Duration.ofDays(1))
        val OneWeek: RetentionPolicy = For(Duration.ofDays(7))
        val OneMonth: RetentionPolicy = For(Duration.ofDays(30))
        val OneYear: RetentionPolicy = For(Duration.ofDays(365))

        private val SPEC_PATTERN =
            Regex("""^(\d+)[_-]?(d|days?|w|weeks?|m|months?|y|years?)$""")

        /**
         * Parse a policy from a string used in configuration files (`application.yml`,
         * environment variables, MCP arguments).
         *
         * Accepted forms:
         * - `forever` (or empty) → [Forever]
         * - `365_days`, `365days`, `365d` → 365-day retention
         * - `4w` / `4weeks` → 4-week retention
         * - `6m` / `6months` → 6×30-day retention (months treated as 30d for simplicity)
         * - `1y` / `1year` → 365-day retention
         *
         * @throws IllegalArgumentException for unrecognised input.
         */
        fun parse(spec: String): RetentionPolicy {
            val normalized = spec.trim().lowercase()
            if (normalized == "forever" || normalized.isEmpty()) return Forever

            val match = SPEC_PATTERN.matchEntire(normalized)
                ?: throw IllegalArgumentException(
                    "Invalid retention spec: '$spec'. " +
                        "Use 'forever' or a duration like '365_days' / '30d' / '4w' / '6m' / '1y'."
                )

            val amount = match.groupValues[1].toLong()
            val unit = match.groupValues[2]
            val days = when {
                unit.startsWith("d") -> amount
                unit.startsWith("w") -> amount * 7
                unit.startsWith("m") -> amount * 30
                unit.startsWith("y") -> amount * 365
                else -> error("Unreachable: regex matched but unit '$unit' is not d/w/m/y")
            }
            return For(Duration.ofDays(days))
        }
    }
}
