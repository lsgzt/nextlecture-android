package com.gndec.timetable.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation of the departmental student-timetable discovery primitives
 * against the REAL labels published on the official index pages
 * (?q=node/5 + appsc/time_tables, snapshot August 2026).
 */
class DeptGroupSourceResolverTest {

    private val resolver = DeptGroupSourceResolver()
    private val classifier = RoomTimetableClient()

    @Test
    fun branchesMapToTheirOfficialDepartmentRoots() {
        assertEquals(RoomSourceRoot.CSE, resolver.rootFor("CS"))
        assertEquals(RoomSourceRoot.ECE, resolver.rootFor("EC"))
        assertEquals(RoomSourceRoot.EE, resolver.rootFor("EE"))
        assertEquals(RoomSourceRoot.CE, resolver.rootFor("CE"))
        assertEquals(RoomSourceRoot.IT, resolver.rootFor("IT"))
        assertEquals(RoomSourceRoot.ME, resolver.rootFor("ME"))
        // Robotics & AI publishes inside the Mechanical & Production file.
        assertEquals(RoomSourceRoot.ME, resolver.rootFor("RAI"))
        assertNull(resolver.rootFor("XYZ"))
    }

    private fun kind(label: String, href: String, root: RoomSourceRoot) =
        classifier.classifyAnchor(label, href, root)

    @Test
    fun realStudentTimetableLabelsAreClassifiedAsGroups() {
        // (label, href, root) triples taken from the live index pages.
        val candidates = listOf(
            Triple("UG, PG & Ph.D. Students", "https://cse.gndec.ac.in/sites/default/files/TT%20July%20December%202026_groups_days_horizontal%20%281%29.html", RoomSourceRoot.CSE),
            Triple("Classes individual Time Table", "https://ece.gndec.ac.in/sites/default/files/classes%20individual%20tt_0.html", RoomSourceRoot.ECE),
            Triple("Classes combined Time Table", "https://ece.gndec.ac.in/sites/default/files/classes%20combined%20tt_5.html", RoomSourceRoot.ECE),
            Triple("Time-Table (Groups) Session Aug 2026-Dec 2026", "https://ee.gndec.ac.in/sites/default/files/TT%20aug2026%20%281%29_years_days_horizontal_0.html", RoomSourceRoot.EE),
            Triple("UG & PG Students [w.e.f 10.08.2026]", "https://ce.gndec.ac.in/sites/default/files/TT_10_8_26_groups_days_horizontal.html", RoomSourceRoot.CE),
            Triple("Class Time Table", "https://it.gndec.ac.in/sites/default/files/july-dec%202026-27final26-27-8_years_days_horizontal.html", RoomSourceRoot.IT),
            Triple("Revised Time table (Class) (w.e.f. 12/08/2026)", "https://me.gndec.ac.in/sites/default/files/july%20to%20dec%202026_groups_days_horizontal_0.html", RoomSourceRoot.ME),
            Triple("Group Wise Time Table for Session Jul-Dec 2026", "https://mca.gndec.ac.in/sites/default/files/ca_July26_groups_days_horizontal.html", RoomSourceRoot.MCA),
            Triple("Student Time Table .", "https://mba.gndec.ac.in/sites/default/files/T2_DAT~1.FET_groups_days_horizontal%20%281%29.html", RoomSourceRoot.MBA)
        )
        for ((label, href, root) in candidates) {
            assertEquals("label must classify as GROUPS: $label", RoomTimetableClient.AnchorKind.GROUPS, kind(label, href, root))
        }
    }

    @Test
    fun nonStudentDocumentsAreRejected() {
        val rejections = listOf(
            Triple("Class Rooms", "https://cse.gndec.ac.in/sites/default/files/TT%20July%20December%202026_rooms_days_horizontal%20%281%29.html", RoomSourceRoot.CSE),
            Triple("Faculty", "https://cse.gndec.ac.in/sites/default/files/TT%20July%20December%202026_teachers_days_horizontal%20%281%29.htm", RoomSourceRoot.CSE),
            Triple("Room Time Table for Aug 2026-Dec 2026", "https://ee.gndec.ac.in/sites/default/files/TT%20aug2026%20%281%29_rooms_days_horizontal_0.html", RoomSourceRoot.EE),
            Triple("Detainee Time Table for Jan 2026- May 2026", "https://ee.gndec.ac.in/sites/default/files/Detainee%20tt.pdf", RoomSourceRoot.EE),
            Triple("Faculty's Free Period [w.e.f 10.08.2026]", "https://ce.gndec.ac.in/sites/default/files/TT_10_8_26_teachers_free_periods_days_horizontal.html", RoomSourceRoot.CE),
            Triple("Teacher Time Table", "https://it.gndec.ac.in/sites/default/files/july-dec%202026-27final26-27-8_teachers_days_horizontal.html", RoomSourceRoot.IT),
            Triple("Rooms [w.e.f 10.08.2026]", "https://ce.gndec.ac.in/sites/default/files/TT_10_8_26_rooms_days_horizontal.html", RoomSourceRoot.CE)
        )
        for ((label, href, root) in rejections) {
            assertTrue(
                "must NOT be a group candidate: $label",
                kind(label, href, root) != RoomTimetableClient.AnchorKind.GROUPS
            )
        }
    }

    @Test
    fun foreignHostsAndNonHtmlAreNotCandidates() {
        // MCA's old-session files live on ca.gndec.ac.in — a different host.
        val href = "https://ca.gndec.ac.in/sites/default/files/ca_JAN26_groups_u.html"
        assertTrue(!RoomTimetableClient.isCandidateUrl(href, RoomSourceRoot.MCA))
        assertTrue(!RoomTimetableClient.isCandidateUrl("https://cse.gndec.ac.in/x/tt.pdf", RoomSourceRoot.CSE))
        assertTrue(RoomTimetableClient.isCandidateUrl("http://cse.gndec.ac.in/x_groups_days_horizontal.html", RoomSourceRoot.CSE))
    }

    @Test
    fun sessionWindowRejectsForeignSemesterFiles() {
        // August 2026 (Jun–Dec window). A March 2026 file is stale; an Aug 2026 file is current.
        val aug2026 = 1755000000000L // 2026-08-12
        val mar2026 = 1772400000000L // 2026-03-02 — hmm, this is AFTER Jun window start? verify via local dates
        // Use explicit epoch values computed from known UTC dates instead of magic numbers.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        fun millis(year: Int, month: Int, day: Int): Long {
            cal.clear(); cal.set(year, month - 1, day, 12, 0, 0); return cal.timeInMillis
        }
        val now = millis(2026, 8, 31)
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 8, 24), now))
        assertTrue(!RoomTimetableClient.isCurrentSession(millis(2026, 3, 3), now))
        assertTrue(RoomTimetableClient.isCurrentSession(null, now))
    }
}
