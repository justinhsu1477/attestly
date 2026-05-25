package io.attestly.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class RetentionPolicyTest {

    @Test
    fun `parse forever and empty string yield Forever`() {
        assertEquals(RetentionPolicy.Forever, RetentionPolicy.parse("forever"))
        assertEquals(RetentionPolicy.Forever, RetentionPolicy.parse("FOREVER"))
        assertEquals(RetentionPolicy.Forever, RetentionPolicy.parse(""))
        assertEquals(RetentionPolicy.Forever, RetentionPolicy.parse("   "))
    }

    @Test
    fun `parse day specs in all supported shapes`() {
        val expected = RetentionPolicy.For(Duration.ofDays(365))
        assertEquals(expected, RetentionPolicy.parse("365d"))
        assertEquals(expected, RetentionPolicy.parse("365_days"))
        assertEquals(expected, RetentionPolicy.parse("365days"))
        assertEquals(expected, RetentionPolicy.parse("365-day"))
    }

    @Test
    fun `parse weeks months years convert correctly`() {
        assertEquals(RetentionPolicy.For(Duration.ofDays(28)), RetentionPolicy.parse("4w"))
        assertEquals(RetentionPolicy.For(Duration.ofDays(180)), RetentionPolicy.parse("6months"))
        assertEquals(RetentionPolicy.For(Duration.ofDays(365)), RetentionPolicy.parse("1y"))
    }

    @Test
    fun `parse rejects garbage with a helpful message`() {
        val ex = assertThrows<IllegalArgumentException> { RetentionPolicy.parse("3 fortnights") }
        assert(ex.message!!.contains("Invalid retention spec")) {
            "Expected helpful error message, got: ${ex.message}"
        }
    }

    @Test
    fun `For rejects zero or negative durations`() {
        assertThrows<IllegalArgumentException> { RetentionPolicy.For(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { RetentionPolicy.For(Duration.ofDays(-1)) }
    }

    @Test
    fun `presets match their stated durations`() {
        assertEquals(RetentionPolicy.For(Duration.ofDays(1)), RetentionPolicy.OneDay)
        assertEquals(RetentionPolicy.For(Duration.ofDays(7)), RetentionPolicy.OneWeek)
        assertEquals(RetentionPolicy.For(Duration.ofDays(30)), RetentionPolicy.OneMonth)
        assertEquals(RetentionPolicy.For(Duration.ofDays(365)), RetentionPolicy.OneYear)
    }
}
