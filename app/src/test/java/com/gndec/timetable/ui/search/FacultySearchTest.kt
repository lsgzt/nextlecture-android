package com.gndec.timetable.ui.search

import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.FacultyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FacultySearchTest {

    private val faculty = listOf(
        FacultyRecord(
            name = "DR. HARPREET KAUR GREWAL",
            designation = "Professor and Head",
            email = "hkgrewal@gndec.ac.in",
            profileId = "71",
            department = "Applied Science"
        ),
        FacultyRecord(
            name = "DR. KULVINDER SINGH MANN",
            designation = "Associate Professor",
            email = "ksm@gndec.ac.in",
            profileId = "123",
            department = "Information Technology"
        ),
        FacultyRecord(
            name = "MANDEEP KAUR",
            designation = "Assistant Professor",
            email = "ce_mandeep@gndec.ac.in",
            profileId = "249",
            department = "Civil Engineering"
        ),
        FacultyRecord(
            name = "MANDEEP KAUR",
            designation = "Assistant Professor",
            email = "ee_mandeep@gndec.ac.in",
            profileId = "426",
            department = "Electrical Engineering"
        )
    )

    private fun snapshot(queryFaculty: List<FacultyRecord> = faculty) = SearchSnapshot(
        settings = AppSettings(),
        lectures = emptyList(),
        notices = emptyList(),
        holidays = emptyList(),
        attendance = emptyList(),
        rooms = null,
        faculty = queryFaculty,
        today = LocalDate.of(2026, 10, 7),
        nowMinutes = 600
    )

    @Test
    fun findsTeachersByPartialName() {
        val results = search("harpreet", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        assertEquals("DR. HARPREET KAUR GREWAL", facultyResults[0].title)
        assertEquals("Professor and Head · Applied Science", facultyResults[0].subtitle)
        assertEquals(SearchAction.OpenFaculty("71"), facultyResults[0].action)
    }

    @Test
    fun sameNameTeachersAreDisambiguatedByDepartment() {
        val results = search("mandeep kaur", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(2, facultyResults.size)
        val subtitles = facultyResults.map { it.subtitle }.toSet()
        assertTrue("Assistant Professor · Civil Engineering" in subtitles)
        assertTrue("Assistant Professor · Electrical Engineering" in subtitles)
        val ids = facultyResults.map { (it.action as SearchAction.OpenFaculty).profileId }.toSet()
        assertEquals(setOf("249", "426"), ids)
    }

    @Test
    fun subsequenceToleranceOnNames() {
        // "kulwinder" is a loose subsequence of "DR. KULVINDER SINGH MANN"'s key
        // only through the letter ladder; the word-boundary rules dominate.
        val results = search("kulwinder", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        assertEquals("DR. KULVINDER SINGH MANN", facultyResults[0].title)
    }

    @Test
    fun facultyResultsAppearForNameQueries() {
        val results = search("mann", snapshot())
        val groups = results.map { it.group }.distinct()
        assertTrue(SearchGroup.FACULTY in groups)
    }

    @Test
    fun emptyDirectoryYieldsNoFacultyResults() {
        val results = search("harpreet", snapshot(queryFaculty = emptyList()))
        assertTrue(results.none { it.group == SearchGroup.FACULTY })
    }

    // --- department picker ---------------------------------------------------

    @Test
    fun departmentFilterNarrowsFacultyResults() {
        // "mandeep kaur" matches both the Civil and the Electrical teacher;
        // the picker's Electrical chip must keep only the EE one.
        val results = search("mandeep kaur", snapshot(), departmentFilter = "Electrical Engineering")
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        val profileId = (facultyResults[0].action as SearchAction.OpenFaculty).profileId
        assertEquals("426", profileId)
    }

    @Test
    fun departmentFilterCanBeReleasedWithNull() {
        val all = search("mandeep kaur", snapshot(), departmentFilter = null)
        assertEquals(2, all.filter { it.group == SearchGroup.FACULTY }.size)
    }

    @Test
    fun facultyDepartmentsListsDepartmentsWithHits() {
        // Canonical order: Electrical (4) before Civil (7).
        assertEquals(
            listOf("Electrical Engineering", "Civil Engineering"),
            facultyDepartments("mandeep kaur", snapshot())
        )
    }

    @Test
    fun facultyDepartmentsListsWholeDirectoryForEmptyQuery() {
        assertEquals(
            listOf(
                "Applied Science",
                "Information Technology",
                "Electrical Engineering",
                "Civil Engineering"
            ),
            facultyDepartments("", snapshot())
        )
    }

    @Test
    fun emptyQueryWithPickedDepartmentBrowsesThatDepartment() {
        // Picking a department with nothing typed lists its teachers
        // instead of the generic quick actions.
        val results = search("", snapshot(), departmentFilter = "Applied Science")
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        assertEquals("DR. HARPREET KAUR GREWAL", facultyResults[0].title)
        assertEquals(SearchAction.OpenFaculty("71"), facultyResults[0].action)
    }

    @Test
    fun emptyQueryWithoutPickedDepartmentStillShowsSuggestions() {
        val results = search("", snapshot(), departmentFilter = null)
        assertTrue(results.any { it.group == SearchGroup.ACTION })
        assertTrue(results.none { it.group == SearchGroup.FACULTY })
    }

    @Test
    fun browseModeListsTeachersAlphabetically() {
        val itSnapshot = SearchSnapshot(
            settings = AppSettings(),
            lectures = emptyList(),
            notices = emptyList(),
            holidays = emptyList(),
            attendance = emptyList(),
            rooms = null,
            faculty = faculty.filter { it.department == "Information Technology" },
            today = LocalDate.of(2026, 10, 7),
            nowMinutes = 600
        )
        val results = search("", itSnapshot, departmentFilter = "Information Technology")
        val titles = results.filter { it.group == SearchGroup.FACULTY }.map { it.title }
        assertEquals(titles, titles.sorted())
    }

    // --- timetable-only teachers -----------------------------------------------

    @Test
    fun timetableOnlyTeacherIsSearchable() {
        // "Ms. MANPREET KAUR." appears in the Applied Science timetable but has
        // no directory record — the synthetic record must still be findable.
        val withExtra = faculty + FacultyRecord(
            name = "MS. MANPREET KAUR",
            department = "Applied Science",
            profileId = "tt-appsc-manpreet-kaur"
        )
        val results = search("manpreet kaur", snapshot(queryFaculty = withExtra))
        val titles = results.filter { it.group == SearchGroup.FACULTY }.map { it.title }
        assertTrue("MS. MANPREET KAUR" in titles)
        val result = results.first { it.title == "MS. MANPREET KAUR" }
        assertEquals("Applied Science", result.subtitle)
        assertEquals(SearchAction.OpenFaculty("tt-appsc-manpreet-kaur"), result.action)
    }

    @Test
    fun timetableOnlyTeacherJoinsTheDepartmentBrowse() {
        val withExtra = faculty + FacultyRecord(
            name = "MS. MANPREET KAUR",
            department = "Applied Science",
            profileId = "tt-appsc-manpreet-kaur"
        )
        val results = search("", snapshot(queryFaculty = withExtra), departmentFilter = "Applied Science")
        val titles = results.filter { it.group == SearchGroup.FACULTY }.map { it.title }
        assertEquals(listOf("DR. HARPREET KAUR GREWAL", "MS. MANPREET KAUR"), titles)
    }

    @Test
    fun timetableOnlyTeacherCountsInTheDepartmentPicker() {
        val withExtra = faculty + FacultyRecord(
            name = "MS. MANPREET KAUR",
            department = "Applied Science",
            profileId = "tt-appsc-manpreet-kaur"
        )
        // "manpreet" only matches the synthetic record — its department is the
        // picker's single option.
        assertEquals(
            listOf("Applied Science"),
            facultyDepartments("manpreet", snapshot(queryFaculty = withExtra))
        )
    }
}
