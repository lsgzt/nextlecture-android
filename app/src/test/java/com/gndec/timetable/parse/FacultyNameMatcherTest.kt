package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacultyNameMatcherTest {

    // --- extractFullName -----------------------------------------------------

    @Test
    fun extractsFullNameFromMeStyleCodeFirstLabels() {
        assertEquals("Dr. Sehijpal Singh" to "SSK", FacultyNameMatcher.extractFullName("SSK (Dr. Sehijpal Singh)"))
        assertEquals("Dr. Harmeet Singh" to "HMS", FacultyNameMatcher.extractFullName("HMS  (Dr. Harmeet Singh )"))
    }

    @Test
    fun extractsFullNameFromEeEceStyleNameFirstLabels() {
        assertEquals("Dr. Kanwardeep Singh" to "KDS", FacultyNameMatcher.extractFullName("Dr. Kanwardeep Singh (KDS)"))
        assertEquals("Pf. Mandeep Kaur" to "AS", FacultyNameMatcher.extractFullName("Pf. Mandeep Kaur (AS)"))
        assertEquals("Dr. Jagdeep Singh" to "JDP", FacultyNameMatcher.extractFullName("Dr. Jagdeep Singh (JDP MPE)"))
    }

    @Test
    fun extractsFullNameFromDashCodeSuffix() {
        assertEquals("Dr. Akshay Girdhar" to "AG", FacultyNameMatcher.extractFullName("Dr. Akshay Girdhar-AG"))
    }

    @Test
    fun bareCodeLabelsCarryNoName() {
        assertEquals(null to "HVS", FacultyNameMatcher.extractFullName("HVS"))
        // "GDKL(math)": the parens side is a dept note, not a name.
        assertEquals(null to "GDKL", FacultyNameMatcher.extractFullName("GDKL(math)"))
    }

    @Test
    fun plainLabelsStayIntact() {
        assertEquals("DR D S PATHANIA" to null, FacultyNameMatcher.extractFullName("DR D S PATHANIA"))
    }

    // --- nameScore -----------------------------------------------------------

    @Test
    fun exactNamesMatchFully() {
        assertEquals(100, FacultyNameMatcher.nameScore("DR RAJBIR KAUR", "DR. RAJBIR KAUR"))
    }

    @Test
    fun spaceInsensitiveNamesMatch() {
        // The directory writes "SATWINDER JIT KAUR", the file "DR SATWINDERJIT KAUR".
        assertEquals(100, FacultyNameMatcher.nameScore("DR SATWINDERJIT KAUR", "SATWINDER JIT KAUR"))
    }

    @Test
    fun fuzzySpellingVariantsMatch() {
        assertEquals(70, FacultyNameMatcher.nameScore("Dr. Kulwinder Singh Mann", "DR. KULVINDER SINGH MANN"))
        assertEquals(70, FacultyNameMatcher.nameScore("Dr. Pardeep Jaiswal", "DR. PRADEEP JASWAL"))
    }

    @Test
    fun initialPerTokenMatches() {
        assertEquals(85, FacultyNameMatcher.nameScore("D S PATHANIA", "DR. DALJEEP SINGH PATHANIA"))
    }

    @Test
    fun leadingTokenSubsetMatches() {
        assertEquals(78, FacultyNameMatcher.nameScore("DR AMARJOT KAUR", "DR. AMARJOT KAUR RANDHAWA"))
    }

    @Test
    fun directoryNameLeadingPrefixOfThePublishedCaptionMatches() {
        // The other way round: the directory writes "SAMREET KAUR", the file
        // "Pf. Samreet Kaur Gosal" — the same teacher, named in full.
        assertEquals(78, FacultyNameMatcher.nameScore("Pf. Samreet Kaur Gosal", "SAMREET KAUR"))
        assertEquals(78, FacultyNameMatcher.nameScore("Pf. Baljeet Singh Ahluwalia", "BALJEET SINGH"))
    }

    @Test
    fun givenNamePrefixMatches() {
        // 'PRAGYA' is a leading token-subset of 'PRAGYA GOYAL' (78); the looser
        // single given-name rule (75) fires for e.g. 'Aman' vs 'AMANDEEP SINGH'.
        assertEquals(78, FacultyNameMatcher.nameScore("Ms. PRAGYA", "PRAGYA GOYAL"))
        assertEquals(75, FacultyNameMatcher.nameScore("Aman", "AMANDEEP SINGH"))
    }

    @Test
    fun unrelatedNamesDoNotMatch() {
        assertEquals(0, FacultyNameMatcher.nameScore("Dr. Raman Kumar", "DR. HARBIR SINGH"))
    }

    // --- damerau ---------------------------------------------------------------

    @Test
    fun damerauCountsTranspositionsAsOneEdit() {
        assertEquals(1, FacultyNameMatcher.damerau("kulvindra", "kulvnidra"))
        assertEquals(1, FacultyNameMatcher.damerau("jaswal", "jaiswal"))
        assertEquals(0, FacultyNameMatcher.damerau("same", "same"))
    }

    // --- assignment -------------------------------------------------------------

    /** Runs the assignment and maps every directory name to its teacher label. */
    private fun assign(
        directory: List<Pair<String, String>>, // name to department
        teachers: List<Triple<String?, String?, String>> // name, code, raw label
    ): Map<String, String?> {
        val result = FacultyNameMatcher.assignTables(
            directoryNames = directory.map { it.first },
            directoryDepartments = directory.map { it.second },
            teacherNames = teachers.map { it.first },
            teacherCodes = teachers.map { it.second },
            teacherLabels = teachers.map { it.third },
            tableAt = { i -> teachers[i] }
        )
        return directory.associate { (name, _) ->
            FacultyNameMatcher.normName(name) to result[FacultyNameMatcher.normName(name)]?.third
        }
    }

    @Test
    fun assignsFullNamesDirectly() {
        val assigned = assign(
            directory = listOf("DR. KULVINDER SINGH MANN" to "Information Technology"),
            teachers = listOf(Triple("Dr. Kulwinder Singh Mann", "KSM", "Dr. Kulwinder Singh Mann-KSM"))
        )
        assertEquals("Dr. Kulwinder Singh Mann-KSM", assigned["kulvinder singh mann"])
    }

    @Test
    fun assignsCodesThroughUniqueSubsequence() {
        // HVS: h-v-s appears in order only inside HARVINDER SINGH of this pool.
        val assigned = assign(
            directory = listOf("HARVINDER SINGH" to "Civil Engineering", "GURDEEPAK SINGH" to "Civil Engineering"),
            teachers = listOf(
                Triple(null, "HVS", "HVS"),
                Triple(null, "GSK", "GSK")
            )
        )
        assertEquals("HVS", assigned["harvinder singh"])
        // GSK is genuinely ambiguous (g-k-s vs g-s-k order inside GURDEEPAK SINGH) —
        // Civil publishes no names, so it stays unassigned.
        assertNull(assigned["gurdeepak singh"])
    }

    @Test
    fun fixpointResolvesCodesAmbiguousUntilAStrongerClaimLands() {
        // 'PTG' subseq-matches both PRASHANT GARG and PRABHJOT SINGH; 'PJS'
        // matches only PRABHJOT SINGH. Once PJS claims its teacher, PTG must
        // resolve to PRASHANT GARG on the next iteration.
        val assigned = assign(
            directory = listOf(
                "PRASHANT GARG" to "Civil Engineering",
                "PRABHJOT SINGH" to "Civil Engineering"
            ),
            teachers = listOf(
                Triple(null, "PTG", "PTG"),
                Triple(null, "PJS", "PJS")
            )
        )
        assertEquals("PJS", assigned["prabhjot singh"])
        assertEquals("PTG", assigned["prashant garg"])
    }

    @Test
    fun ambiguousCodesStayUnassigned() {
        val assigned = assign(
            directory = listOf("SUKHWINDER SINGH" to "Civil Engineering", "SUKHWINDERPAL SINGH" to "Civil Engineering"),
            teachers = listOf(Triple(null, "SKS", "SKS"))
        )
        assertNull(assigned["sukhwinder singh"])
        assertNull(assigned["sukhwinderpal singh"])
    }

    @Test
    fun departmentAnnotationPreventsCrossDepartmentClaims() {
        // The college-wide file lists "DR. MANDEEP KAUR (EE)" — an Electrical
        // teacher — which must not claim the Applied Science MANDEEP KAUR when
        // a same-named local caption exists.
        val assigned = assign(
            directory = listOf("MANDEEP KAUR" to "Applied Science"),
            teachers = listOf(
                Triple("Ms. MANDEEP KAUR", null, "Ms. MANDEEP KAUR"),
                Triple("DR. MANDEEP KAUR", "EE", "DR. MANDEEP KAUR (EE)")
            )
        )
        assertEquals("Ms. MANDEEP KAUR", assigned["mandeep kaur"])
    }

    @Test
    fun honorificBreaksSameNameTies() {
        val assigned = assign(
            directory = listOf("DR. RAJBIR KAUR" to "Applied Science"),
            teachers = listOf(
                Triple("DR RAJBIR KAUR", null, "DR RAJBIR KAUR"),
                Triple("ER. RAJBIR KAUR", null, "ER. RAJBIR KAUR")
            )
        )
        assertEquals("DR RAJBIR KAUR", assigned["rajbir kaur"])
    }

    @Test
    fun deptCodesAreNeverTreatedAsTeacherCodes() {
        // "ME" is a department marker, not a teacher code.
        val assigned = assign(
            directory = listOf("AMANDEEP SINGH" to "Civil Engineering"),
            teachers = listOf(Triple(null, "ME", "ME(faculty)"))
        )
        assertNull(assigned["amandeep singh"])
    }

    @Test
    fun aCodeNeverStealsATableTheNamePassAlreadyClaimed() {
        // "Dr. Amit Kamra-AK" belongs to AMIT KAMRA. KAMALJIT KAUR DHILLON
        // also contains A and K in order, so the bare-code pass used to hand
        // Kamra's table to her — a teacher who never appears in that file.
        val assigned = assign(
            directory = listOf(
                "DR. AMIT KAMRA" to "Information Technology",
                "DR. KAMALJIT KAUR DHILLON" to "Information Technology"
            ),
            teachers = listOf(
                Triple("Dr. Amit Kamra", "AK", "Dr. Amit Kamra-AK")
            )
        )
        assertEquals("Dr. Amit Kamra-AK", assigned["amit kamra"])
        assertNull(assigned["kamaljit kaur dhillon"])
    }

    @Test
    fun aBareCodeOnlyClaimsATeacherWithTheSameFirstInitial() {
        // Civil publishes bare codes. "HVS" is HARVINDER SINGH's — but
        // SARAH VENKATESH contains H, V and S in order too. A code may only
        // claim a teacher whose own first initial it starts with.
        val assigned = assign(
            directory = listOf(
                "HARVINDER SINGH" to "Civil Engineering",
                "SARAH VENKATESH" to "Civil Engineering"
            ),
            teachers = listOf(
                Triple(null, "HVS", "HVS")
            )
        )
        assertEquals("HVS", assigned["harvinder singh"])
        assertNull(assigned["sarah venkatesh"])
    }

    @Test
    fun aCodeIsNotHandedToADifferentTeacherWithTheSameLetters() {
        // "Pf. Preetinder Singh (PTS)" is PREETINDER SINGH's table; SANPREET
        // SINGH contains P, T and S in order as well.
        val assigned = assign(
            directory = listOf(
                "DR. PREETINDER SINGH" to "Electrical Engineering",
                "SANPREET SINGH" to "Electrical Engineering"
            ),
            teachers = listOf(
                Triple("Pf. Preetinder Singh", "PTS", "Pf. Preetinder Singh (PTS)")
            )
        )
        assertEquals("Pf. Preetinder Singh (PTS)", assigned["preetinder singh"])
        assertNull(assigned["sanpreet singh"])
    }

    // --- published spellings of one teacher -------------------------------------

    @Test
    fun aCuratedPublishedSpellingIsRecognised() {
        // The college's own list calls him "DR AMANPREET KAUR SODHI", the
        // Applied Science timetable writes "DR APK SODHI".
        assertTrue(
            FacultyNameMatcher.matchesAlias(
                "DR APK SODHI",
                listOf("DR APK SODHI")
            )
        )
        // compared on the normalised name, so the honorific and the spacing
        // do not matter
        assertTrue(
            FacultyNameMatcher.matchesAlias(
                "Er. Jaswant Singh Taur",
                listOf("ER. JASWANT SINGH TAUR")
            )
        )
        assertFalse(
            FacultyNameMatcher.matchesAlias(
                "ER. JASWANT SINGH",
                listOf("ER. JASWANT SINGH TAUR")
            )
        )
        assertFalse(FacultyNameMatcher.matchesAlias("ER. JASWANT SINGH", emptyList()))
    }

    @Test
    fun aPublishedSpellingClaimsTheTableOfItsOwnTeacher() {
        // "DR APK SODHI" names the Applied Science professor, and the
        // directory record carries that spelling as an alias.
        val result = FacultyNameMatcher.assignTables(
            directoryNames = listOf("DR AMANPREET KAUR SODHI"),
            directoryDepartments = listOf("Applied Science"),
            teacherNames = listOf("DR APK SODHI"),
            teacherCodes = listOf(null),
            teacherLabels = listOf("DR APK SODHI"),
            tableAt = { 0 },
            directoryAliases = listOf(listOf("DR APK SODHI"))
        )
        assertEquals(0, result["amanpreet kaur sodhi"])
    }

    @Test
    fun aPublishedSpellingWinsOverAnotherTeacherOfTheSameName() {
        // Three Harminder Kaurs are on the college's list; the Applied Science
        // file publishes one of them under the same spelling, and the alias
        // says which one.
        val result = FacultyNameMatcher.assignTables(
            directoryNames = listOf(
                "HARMINDER KAUR",
                "HARMINDER KAUR",
                "HARMINDER KAUR"
            ),
            directoryDepartments = listOf(
                "Computer Science & Engg.",
                "Electronics & Communication Engineering",
                "Information Technology"
            ),
            teacherNames = listOf("ER. HARMINDER KAUR"),
            teacherCodes = listOf(null),
            teacherLabels = listOf("ER. HARMINDER KAUR"),
            tableAt = { 0 },
            directoryAliases = listOf(
                listOf("ER. HARMINDER KAUR"),
                emptyList(),
                emptyList()
            )
        )
        // every record is keyed by its normalised name, so the alias owner wins
        assertEquals(0, result["harminder kaur"])
    }

    @Test
    fun aTeachingAssistantCodeIsNotADepartment() {
        // "ER. JASHANDEEP KAUR (TA)" labels a teaching assistant, not a
        // Workshops teacher.
        assertNull(FacultyNameMatcher.deptOfCode("TA"))
        assertNull(FacultyNameMatcher.deptOfCode("TA1"))
        assertNull(FacultyNameMatcher.deptOfCode("TA2"))
        assertEquals("Workshops", FacultyNameMatcher.deptOfCode("WS"))
    }
}
