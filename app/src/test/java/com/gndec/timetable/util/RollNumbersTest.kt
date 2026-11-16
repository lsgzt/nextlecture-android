package com.gndec.timetable.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRN/URN input rules: the official directory stores 7-digit CRNs and 8-digit
 * URNs, so manual entry must never accept alphabets or special characters.
 */
class RollNumbersTest {

    @Test
    fun sanitizeStripsLettersSymbolsAndSpaces() {
        assertEquals("2614001", RollNumbers.sanitize("2614001"))
        assertEquals("2614001", RollNumbers.sanitize("26ab14-001!"))
        assertEquals("26012961", RollNumbers.sanitize("26 012 961"))
        assertEquals("", RollNumbers.sanitize("abc-#@!"))
    }

    @Test
    fun sanitizeCapsAbsurdlyLongInput() {
        assertEquals(12, RollNumbers.sanitize("1234567890123456789").length)
        assertEquals("123456789012", RollNumbers.sanitize("1234567890123456789"))
    }

    @Test
    fun blankIsAllowedBecauseFieldsAreOptional() {
        assertTrue(RollNumbers.isValid(""))
        assertTrue(RollNumbers.isValid("   "))
    }

    @Test
    fun officialFormatsAreValid() {
        assertTrue(RollNumbers.isValid("2614001"))  // 7-digit CRN
        assertTrue(RollNumbers.isValid("26012961")) // 8-digit URN
    }

    @Test
    fun alphabetsAndSymbolsAreInvalid() {
        assertFalse(RollNumbers.isValid("26A14001"))
        assertFalse(RollNumbers.isValid("26-140-01"))
        assertFalse(RollNumbers.isValid("26 1401"))
        assertFalse(RollNumbers.isValid("###"))
    }

    @Test
    fun implausiblyShortNumbersAreInvalid() {
        assertFalse(RollNumbers.isValid("1"))
        assertFalse(RollNumbers.isValid("123"))
    }
}
