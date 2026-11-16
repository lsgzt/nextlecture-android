package com.gndec.timetable.util

/**
 * CRN (Class Roll Number) and URN (University Roll Number) input rules.
 *
 * The official GNDEC student directory stores CRNs as 7 digits and URNs as 8
 * digits — both are purely numeric. Manual entry used to accept alphabets and
 * special characters, which then flowed into the saved profile, the friends
 * timetable and the attendance screens. These rules are shared by onboarding
 * (senior details step, first-year manual fallback) and the Profile editor so
 * invalid roll numbers can never be typed or saved anywhere.
 */
object RollNumbers {

    /** Longest plausible roll number; also the hard cap while typing. */
    const val MAX_DIGITS = 12

    /** Shortest plausible roll number — blocks junk like "1" or "12". */
    const val MIN_DIGITS = 4

    /**
     * Live input filter: keeps digits only (dropping letters, spaces, symbols
     * and anything else) and caps the length, so invalid characters can never
     * even enter the field.
     */
    fun sanitize(input: String): String = input.filter { it.isDigit() }.take(MAX_DIGITS)

    /**
     * Blank is allowed — the fields are optional. A filled value must be all
     * digits and of a plausible length.
     */
    fun isValid(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || (v.length in MIN_DIGITS..MAX_DIGITS && v.all { it.isDigit() })
    }

    /** Shown under a filled-but-invalid field. */
    fun errorMessage(label: String): String =
        "$label must be $MIN_DIGITS–$MAX_DIGITS digits — letters and symbols are not allowed."
}
