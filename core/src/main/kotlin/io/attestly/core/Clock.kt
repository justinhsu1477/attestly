package io.attestly.core

import java.time.Instant

/**
 * Indirection over wall-clock time so tests can pin the value the audit fields capture.
 *
 * Kept as our own minimal [fun interface] rather than [java.time.Clock] to avoid leaking the
 * latter's `getZone()` / `withZone()` ceremony into the public API — Attestly only ever needs
 * "what is the current instant?" at write time.
 *
 * Production code passes [System]; tests pass `Clock { fixedInstant }`.
 */
fun interface Clock {
    fun now(): Instant

    companion object {
        /** Real wall clock, backed by [Instant.now]. */
        val System: Clock = Clock { Instant.now() }
    }
}
