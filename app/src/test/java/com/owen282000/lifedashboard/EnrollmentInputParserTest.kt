package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentInputParserTest {
    @Test
    fun `parses HTTPS endpoint and fragment code`() {
        val parsed = EnrollmentInputParser.parse(
            "lifedashboard://enroll?endpoint=https%3A%2F%2Fdash.example%2Fapi%2Fv1%2Fenrollments%2Fexchange#code=once%2Bonly"
        )
        assertEquals("https://dash.example/api/v1/enrollments/exchange", parsed.endpoint)
        assertEquals("once+only", parsed.code)
    }

    @Test
    fun `rejects insecure endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentInputParser.parse(
                "lifedashboard://enroll?endpoint=http%3A%2F%2Fdash.example%2Fenroll#code=once"
            )
        }
    }

    @Test
    fun `requires code in fragment rather than query`() {
        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentInputParser.parse(
                "lifedashboard://enroll?endpoint=https%3A%2F%2Fdash.example%2Fenroll&code=leaky"
            )
        }
    }
}
