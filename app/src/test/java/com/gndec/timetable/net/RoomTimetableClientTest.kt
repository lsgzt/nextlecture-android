package com.gndec.timetable.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTimetableClientTest {

    private val client = RoomTimetableClient()

    // ---- URL filtering ----

    @Test
    fun onlySameHostHtmlDocumentsAreCandidates() {
        val root = RoomSourceRoot.CSE
        assertTrue(
            RoomTimetableClient.isCandidateUrl(
                "https://cse.gndec.ac.in/sites/default/files/TT_rooms_days_horizontal.html",
                root
            )
        )
        assertFalse(
            RoomTimetableClient.isCandidateUrl(
                "https://cse.gndec.ac.in/sites/default/files/rooms.pdf",
                root
            )
        )
        assertFalse(
            RoomTimetableClient.isCandidateUrl(
                "https://evil.example.com/rooms_days_horizontal.html",
                root
            )
        )
        assertFalse(RoomTimetableClient.isCandidateUrl("not a url", root))
    }

    // ---- anchor classification (pure) ----

    @Test
    fun roomAnchorsAreClassifiedAcrossAllDepartmentLabelStyles() {
        val cse = RoomSourceRoot.CSE
        assertEquals(
            RoomTimetableClient.AnchorKind.ROOMS,
            client.classifyAnchor(
                "Class Rooms",
                "https://cse.gndec.ac.in/sites/default/files/TT%20rooms_days_horizontal%20%281%29.html",
                cse
            )
        )
        val appsc = RoomSourceRoot.APPSC
        assertEquals(
            RoomTimetableClient.AnchorKind.ROOMS,
            client.classifyAnchor(
                "📍Room Time Table",
                "https://appsc.gndec.ac.in/sites/default/files/2026-08/30_08_2026%20FINAL_FILE_rooms_days_horizontal.html",
                appsc
            )
        )
        val ee = RoomSourceRoot.EE
        assertEquals(
            RoomTimetableClient.AnchorKind.ROOMS,
            client.classifyAnchor(
                "Room Time Table for Aug 2026-Dec 2026",
                "https://ee.gndec.ac.in/sites/default/files/TT%20aug2026%20%281%29_rooms_days_horizontal_0.html",
                ee
            )
        )
        val ce = RoomSourceRoot.CE
        assertEquals(
            RoomTimetableClient.AnchorKind.ROOMS,
            client.classifyAnchor(
                "Rooms [w.e.f 10.08.2026]",
                "https://ce.gndec.ac.in/sites/default/files/TT_10_8_26_rooms_days_horizontal.html",
                ce
            )
        )
        val mba = RoomSourceRoot.MBA
        assertEquals(
            RoomTimetableClient.AnchorKind.ROOMS,
            client.classifyAnchor(
                "Room Wise TimeTable .",
                "https://mba.gndec.ac.in/sites/default/files/T2_DAT_1.FET_rooms_days_horizontal.html",
                mba
            )
        )
        // cross-host links must not be candidates
        assertEquals(
            RoomTimetableClient.AnchorKind.NONE,
            client.classifyAnchor(
                "Room Wise Time Table for Session Jan-Jun",
                "https://ca.gndec.ac.in/sites/default/files/ca_JAN26_rooms_u.html",
                RoomSourceRoot.MCA
            )
        )
    }

    @Test
    fun groupAnchorsBecomeFallbackCandidates() {
        val me = RoomSourceRoot.ME
        assertEquals(
            RoomTimetableClient.AnchorKind.GROUPS,
            client.classifyAnchor(
                "Revised Time table (Class) (w.e.f. 12/08/2026)",
                "https://me.gndec.ac.in/sites/default/files/july%20to%20dec%202026_groups_days_horizontal_0.html",
                me
            )
        )
        assertEquals(
            RoomTimetableClient.AnchorKind.GROUPS,
            client.classifyAnchor(
                "UG, PG & Ph.D. Students",
                "https://cse.gndec.ac.in/sites/default/files/groups_days_horizontal.html",
                RoomSourceRoot.CSE
            )
        )
        // faculty timetables are neither
        assertEquals(
            RoomTimetableClient.AnchorKind.NONE,
            client.classifyAnchor(
                "Faculty",
                "https://cse.gndec.ac.in/sites/default/files/teachers_days_horizontal.html",
                RoomSourceRoot.CSE
            )
        )
    }

    // ---- session window ----

    private fun millis(y: Int, m: Int, d: Int, h: Int = 12): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }.timeInMillis

    @Test
    fun staleSessionsAreRejected() {
        val now = millis(2026, 8, 31) // Aug 2026 → Jul-Dec window
        // MBA's March 2026 file: previous semester → rejected.
        assertFalse(RoomTimetableClient.isCurrentSession(millis(2026, 3, 3), now))
        // Last year's fall file: rejected.
        assertFalse(RoomTimetableClient.isCurrentSession(millis(2025, 12, 20), now))
    }

    @Test
    fun currentSessionsAreAccepted() {
        val now = millis(2026, 8, 31)
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 8, 30), now))
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 7, 26), now))
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 6, 1), now))
        // December of the same fall window is still current.
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 12, 15), now))
    }

    @Test
    fun januaryMayWindowFlipsWithTheCalendar() {
        val now = millis(2026, 2, 15) // Feb 2026 → Jan-May window
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2026, 3, 3), now))
        // A fall-window file from LAST year (generated Aug 2025) is stale in Feb 2026.
        assertFalse(RoomTimetableClient.isCurrentSession(millis(2025, 8, 30), now))
    }

    @Test
    fun missingTimestampIsAcceptedAndFutureTimestampIsAccepted() {
        assertTrue(RoomTimetableClient.isCurrentSession(null, millis(2026, 8, 31)))
        assertTrue(RoomTimetableClient.isCurrentSession(millis(2027, 1, 1), millis(2026, 8, 31)))
    }

    @Test
    fun rootsCoverEveryDepartmentalDiscoveryPage() {
        val hosts = RoomSourceRoot.entries.map { it.host }
        assertEquals(9, hosts.size)
        assertEquals(9, hosts.toSet().size)
        assertTrue(hosts.all { it.endsWith("gndec.ac.in") })
        assertEquals(
            listOf("appsc", "cse", "ece", "ee", "me", "ce", "it", "mca", "mba"),
            RoomSourceRoot.entries.map { it.id }
        )
    }
}
