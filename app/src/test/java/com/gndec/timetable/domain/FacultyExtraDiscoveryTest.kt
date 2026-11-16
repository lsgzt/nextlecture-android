package com.gndec.timetable.domain

import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultySlot
import com.gndec.timetable.parse.ParsedFacultyDoc
import com.gndec.timetable.parse.TeacherTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery of the teachers the department timetables list but the bundled
 * directory (the college website's faculty list) does not carry. The reported
 * case: "Ms. MANPREET KAUR." publishes an Applied Science table, yet no
 * directory record of any department matches — search used to have no way to
 * surface her at all.
 */
class FacultyExtraDiscoveryTest {

    private val slot = FacultySlot(day = 0, startMinutes = 510, endMinutes = 570, subject = "PHYSICS", room = "101")

    private fun doc(vararg tables: TeacherTable, rootId: String = "appsc") = ParsedFacultyDoc(
        rootId = rootId,
        url = "https://example.org/$rootId",
        slotStarts = listOf(510),
        tables = tables.toList(),
        generatedAtMillis = null,
        fetchedAtMillis = 0L
    )

    private val directory = listOf(
        FacultyRecord(name = "HITIKA DHAWAN", department = "Applied Science", profileId = "18"),
        FacultyRecord(name = "PARDEEP JASWAL", department = "Applied Science", profileId = "19"),
        FacultyRecord(
            name = "DR. GAGANDEEP SINGH SODHI",
            department = "Electrical Engineering",
            profileId = "426"
        ),
        FacultyRecord(
            name = "DR. HARPREET KAUR GREWAL",
            designation = "Professor and Head",
            email = "hkgrewal@gndec.ac.in",
            profileId = "71",
            department = "Applied Science"
        )
    )

    // --- the reported bug -----------------------------------------------------

    @Test
    fun timetableOnlyTeacherIsDiscoveredWithSyntheticRecord() {
        val manpreet = TeacherTable(teacherName = "Ms. MANPREET KAUR.", slots = listOf(slot))
        val hitika = TeacherTable(teacherName = "Ms. HITIKA DHAWAN", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(
            doc(manpreet, hitika), RoomSourceRoot.APPSC, directory
        )
        assertEquals(1, extras.size)
        val extra = extras[0]
        assertEquals("MS. MANPREET KAUR", extra.record.name)
        assertEquals("Applied Science", extra.record.department)
        assertEquals("tt-appsc-manpreet-kaur", extra.record.profileId)
        assertEquals("appsc", extra.rootId)
        assertEquals(manpreet, extra.table)
        // Nothing to contact: the "information not available" note covers it.
        assertTrue(!extra.record.contactable)
    }

    @Test
    fun teacherClaimedByTheDirectoryProducesNoExtra() {
        val hitika = TeacherTable(teacherName = "Ms. HITIKA DHAWAN", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(hitika), RoomSourceRoot.APPSC, directory)
        assertTrue(extras.isEmpty())
    }

    // --- visiting teachers with a department annotation ------------------------

    @Test
    fun crossDepartmentTeacherKnownInTheDirectoryIsSkipped() {
        // Applied Science file lists an EE teacher; the EE directory knows them,
        // so their own department's page already serves them.
        val visiting = TeacherTable(teacherName = "DR. GAGANDEEP SINGH SODHI", code = "EE", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(visiting), RoomSourceRoot.APPSC, directory)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun crossDepartmentTeacherUnknownToTheDirectoryGetsTheAnnotatedDepartment() {
        val visiting = TeacherTable(teacherName = "ER. GURLEEN KAUR", code = "EC", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(visiting), RoomSourceRoot.APPSC, directory)
        assertEquals(1, extras.size)
        assertEquals("Electronics & Communication Engineering", extras[0].record.department)
        assertEquals("tt-appsc-gurleen-kaur", extras[0].record.profileId)
    }

    // --- spelling drift guard ---------------------------------------------------

    @Test
    fun plainLabelThatFuzzyMatchesADirectoryTeacherIsSkipped() {
        // The timetable writes PRADEEP, the directory PARDEEP — one edit apart.
        // The label belongs to the known teacher, not a new one.
        val drift = TeacherTable(teacherName = "PRADEEP JASWAL", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(drift), RoomSourceRoot.APPSC, directory)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun fuzzyNearMissOfADifferentTeacherStaysDiscoverable() {
        // "MANPREET" is one edit from "SUMANPREET", but SUMANPREET's own table
        // exists in the same file — the near-miss is a DIFFERENT person and
        // must be discovered, not silently attributed to SUMANPREET.
        val sumanpreet = TeacherTable(teacherName = "Ms. SUMANPREET KAUR", slots = listOf(slot))
        val manpreet = TeacherTable(teacherName = "Ms. MANPREET KAUR.", slots = listOf(slot))
        val withSuman = directory + FacultyRecord(name = "SUMANPREET KAUR", department = "Applied Science", profileId = "20")
        val extras = FacultyExtraDiscovery.discoverForDoc(
            doc(sumanpreet, manpreet), RoomSourceRoot.APPSC, withSuman
        )
        assertEquals(listOf("MS. MANPREET KAUR"), extras.map { it.record.name })
    }

    @Test
    fun uniqueNameDirectoryTeacherVisitingAnotherDepartmentIsSkipped() {
        // The IT file lists Applied Science's "Dr. Isha Dudeja" with no
        // annotation: her name is unique in the directory, so the plain label
        // belongs to her — her own department's page serves her.
        val visiting = TeacherTable(teacherName = "Dr. Isha Dudeja", slots = listOf(slot))
        val withIsha = directory + FacultyRecord(name = "DR. ISHA DUDEJA", department = "Applied Science", profileId = "21")
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(visiting, rootId = "it"), RoomSourceRoot.IT, withIsha)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun sameNameTeachersOfOtherDepartmentsDoNotHideATimetableOnlyTeacher() {
        // The real configuration behind the reported bug: the directory has
        // Manpreet Kaurs in OTHER departments only, so the Applied Science
        // "Ms. MANPREET KAUR." stays ambiguous — and discoverable.
        val manpreet = TeacherTable(teacherName = "Ms. MANPREET KAUR.", slots = listOf(slot))
        val sameNameElsewhere = listOf(
            FacultyRecord(name = "MANPREET KAUR", department = "Business Administration", profileId = "300"),
            FacultyRecord(name = "MANPREET KAUR", department = "Computer Applications", profileId = "301")
        )
        val extras = FacultyExtraDiscovery.discoverForDoc(
            doc(manpreet), RoomSourceRoot.APPSC, directory + sameNameElsewhere
        )
        assertEquals(listOf("MS. MANPREET KAUR"), extras.map { it.record.name })
        assertEquals("tt-appsc-manpreet-kaur", extras[0].record.profileId)
    }

    // --- pseudo-table junk ---------------------------------------------------------

    @Test
    fun departmentWordPseudoTablesAreNotTeachers() {
        // "ME2" inside the ECE file is Mechanical's table, not a teacher "ME".
        val pseudo = TeacherTable(teacherName = "ME2", slots = emptyList())
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(pseudo, rootId = "ece"), RoomSourceRoot.ECE, directory)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun acronymShapedLabelsAreNotTeachers() {
        val pseudo = TeacherTable(teacherName = "CH-1", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(pseudo, rootId = "me"), RoomSourceRoot.ME, directory)
        assertTrue(extras.isEmpty())
    }

    // --- unnameable tables -------------------------------------------------------

    @Test
    fun bareCodeTableWithoutANameCannotBecomeAnExtra() {
        val codeOnly = TeacherTable(code = "HVS", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(codeOnly), RoomSourceRoot.CE, directory)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun duplicateLabelsInsideOneDocumentCollapseToOneExtra() {
        val first = TeacherTable(teacherName = "Ms. KIRAN", slots = listOf(slot))
        val second = TeacherTable(teacherName = "KIRAN", slots = listOf(slot))
        val extras = FacultyExtraDiscovery.discoverForDoc(doc(first, second), RoomSourceRoot.APPSC, directory)
        assertEquals(1, extras.size)
        assertEquals("tt-appsc-kiran", extras[0].record.profileId)
    }

    // --- merge across roots --------------------------------------------------------

    @Test
    fun mergeKeepsTheTablePublishedByTheTeachersOwnDepartment() {
        val fromAppsc = ExtraFacultyTeacher(
            record = FacultyRecord(name = "ER. GURLEEN KAUR", department = "Electronics & Communication Engineering", profileId = "tt-appsc-gurleen-kaur"),
            rootId = "appsc",
            table = TeacherTable(teacherName = "ER. GURLEEN KAUR", code = "EC")
        )
        val fromHome = ExtraFacultyTeacher(
            record = FacultyRecord(name = "ER. GURLEEN KAUR", department = "Electronics & Communication Engineering", profileId = "tt-ece-gurleen-kaur"),
            rootId = "ece",
            table = TeacherTable(teacherName = "ER. GURLEEN KAUR", code = "GK")
        )
        val merged = FacultyExtraDiscovery.mergeExtras(listOf(fromAppsc, fromHome))
        assertEquals(1, merged.size)
        assertEquals("ece", merged[0].rootId)
        assertEquals("tt-ece-gurleen-kaur", merged[0].record.profileId)
    }

    @Test
    fun mergeKeepsFirstEntryWhenNeitherRootIsTheHomeDepartment() {
        val a = ExtraFacultyTeacher(
            record = FacultyRecord(name = "JASWANT SINGH TAUR", department = "Workshops", profileId = "tt-appsc-jaswant-singh-taur"),
            rootId = "appsc",
            table = TeacherTable(teacherName = "ER. JASWANT SINGH TAUR", code = "TA")
        )
        val b = ExtraFacultyTeacher(
            record = FacultyRecord(name = "JASWANT SINGH TAUR", department = "Workshops", profileId = "tt-cse-jaswant-singh-taur"),
            rootId = "cse",
            table = TeacherTable(teacherName = "ER. JASWANT SINGH TAUR", code = "TA")
        )
        val merged = FacultyExtraDiscovery.mergeExtras(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("appsc", merged[0].rootId)
    }

    @Test
    fun mergeSortsExtrasByName() {
        val a = ExtraFacultyTeacher(
            record = FacultyRecord(name = "MS. KIRAN", department = "Applied Science", profileId = "tt-appsc-kiran"),
            rootId = "appsc",
            table = TeacherTable(teacherName = "Ms. KIRAN")
        )
        val b = ExtraFacultyTeacher(
            record = FacultyRecord(name = "MS. MANPREET KAUR", department = "Applied Science", profileId = "tt-appsc-manpreet-kaur"),
            rootId = "appsc",
            table = TeacherTable(teacherName = "Ms. MANPREET KAUR.")
        )
        val merged = FacultyExtraDiscovery.mergeExtras(listOf(b, a))
        assertEquals(listOf("MS. KIRAN", "MS. MANPREET KAUR"), merged.map { it.record.name })
    }

    // --- display-name cleaning -------------------------------------------------------

    @Test
    fun directoryStyleNameCases() {
        assertEquals("MS. MANPREET KAUR", FacultyExtraDiscovery.directoryStyleName("Ms. MANPREET KAUR."))
        assertEquals("DR. MEETALI", FacultyExtraDiscovery.directoryStyleName("DR. MEETALI"))
        assertEquals("ER. BALIHAR SINGH", FacultyExtraDiscovery.directoryStyleName("ER. BALIHAR SINGH"))
        assertEquals("MEETALI", FacultyExtraDiscovery.directoryStyleName("MEETALI"))
        assertNull(FacultyExtraDiscovery.directoryStyleName("Ms."))
        assertNull(FacultyExtraDiscovery.directoryStyleName("---"))
    }

    @Test
    fun syntheticProfileIdsNeverCollideWithDirectoryIds() {
        val id = FacultyExtraDiscovery.syntheticProfileId("appsc", "MS. MANPREET KAUR")
        assertTrue(id.startsWith(ExtraTeacherIdPrefix))
        assertEquals("tt-appsc-manpreet-kaur", id)
        // Directory ids are numeric; root ids and name keys keep this one apart.
        assertTrue(directory.none { it.profileId == id })
    }
}
