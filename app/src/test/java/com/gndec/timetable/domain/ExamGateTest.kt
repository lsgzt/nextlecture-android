package com.gndec.timetable.domain

import com.gndec.timetable.data.prefs.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The exam-mode gate: what `exam_mode` has to say to switch the app over, and
 * how the date sheet is read for one student (group choice, section lists).
 */
class ExamGateTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val dateSheet = """
        **Chemistry Group:**

        23 Nov — Mathematics-I — 9:15–10:45 AM
        28 Nov — BEEE — 9:15–10:45 AM

        **Physics Group:**

        23 Nov — Mathematics-I — 9:15–10:45 AM
        28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B, CE A, CE B, RAI)
        28 Nov — EDG — 1:00–2:30 PM (EE A, EE B, EC A, EC B)
    """.trimIndent()

    private fun sheet(
        id: String = "mse-ii",
        examMode: String = "on",
        publishedAt: String = "2026-11-12T21:52:08+05:30",
        message: String = dateSheet
    ) = Announcement(
        id = id, title = "MSE-II Date Sheet", message = message,
        publishedAt = publishedAt, examMode = examMode
    )

    private fun profile(
        branch: String = "EE",
        section: String = "EE A",
        subsection: String = "EEA1"
    ) = AppSettings(
        branch = branch, studentSection = section, studentSubsection = subsection,
        studentGroup = subsection, group = "EEAM1"
    )

    private val today = LocalDate.of(2026, 11, 23)

    private fun cached(vararg announcements: Announcement): AppSettings =
        AppSettings(announcementsJson = json.encodeToString(announcements.toList()))

    @Test
    fun examModeIsOnOnlyWhenTheAttributeSaysSo() {
        listOf("on", "ON", " on ", "true", "True", "1", "yes", "enabled", "active").forEach {
            assertTrue(it, ExamGate.isOn(it))
        }
        listOf<String?>(null, "", "off", "OFF", "0", "no", "false", "exam", "ongoing").forEach {
            assertFalse("'$it'", ExamGate.isOn(it))
        }
    }

    @Test
    fun aSheetReadOnlyInPartSaysSo() {
        // One exam line is broken: the other exams still show, and Home keeps the
        // notice card because the broken one is in no card.
        val part = ExamGate.resolve(
            sheet(
                message = """
                    **Chemistry Group:**

                    23 Nov — Mathematics-I — 9:15–10:45 AM
                    25 Nov — PPS — 9:65–10:45 AM
                """.trimIndent()
            ),
            profile(branch = "CE", section = "CE A", subsection = "CEA1"),
            today
        )!!
        assertTrue(part.readable)
        assertTrue(part.partial)
        assertEquals(listOf("Mathematics-I"), part.exams.map { it.subject })

        val whole = ExamGate.resolve(sheet(), profile(), today)!!
        assertTrue(whole.readable)
        assertFalse(whole.partial)
    }

    @Test
    fun theAttributeDecodesFromTheFeedAndDefaultsToOff() {
        val on = json.decodeFromString<Announcement>("""{"id":"1","title":"t","message":"m","exam_mode":"on"}""")
        assertEquals("on", on.examMode)

        val absent = json.decodeFromString<Announcement>("""{"id":"1","title":"t","message":"m"}""")
        assertEquals("off", absent.examMode)

        val boolean = json.decodeFromString<Announcement>("""{"id":"1","title":"t","message":"m","exam_mode":true}""")
        assertTrue(ExamGate.isOn(boolean.examMode))
    }

    @Test
    fun theCacheKeepsTheAttribute() {
        val stored = json.encodeToString(listOf(sheet(), Announcement(id = "2", title = "t", message = "m")))
        val restored = decodeAnnouncements(stored)
        assertEquals("on", restored[0].examMode)
        assertEquals("off", restored[1].examMode)
    }

    @Test
    fun newestSwitchedOnAnnouncementIsTheActiveSheet() {
        val older = sheet(id = "older", publishedAt = "2026-10-01T10:00:00+05:30")
        val newer = sheet(id = "newer", publishedAt = "2026-11-20T10:00:00+05:30")
        val plain = Announcement(id = "plain", title = "t", message = "m", publishedAt = "2026-11-25T10:00:00+05:30")
        assertEquals("newer", ExamGate.activeAnnouncement(listOf(plain, older, newer))!!.id)
        assertNull(ExamGate.activeAnnouncement(listOf(plain)))
        assertNull(ExamGate.activeAnnouncement(emptyList()))
    }

    @Test
    fun theGateReadsTheCachedFeed() {
        assertTrue(ExamGate.isActive(cached(sheet())))
        assertEquals("mse-ii", ExamGate.active(cached(sheet()))!!.id)
        assertFalse(ExamGate.isActive(cached(sheet(examMode = "off"))))
        assertFalse(ExamGate.isActive(AppSettings()))
        assertFalse(ExamGate.isActive(AppSettings(announcementsJson = "not json {")))
        assertFalse(ExamGate.isActive(AppSettings(announcementsJson = " ")))
    }

    @Test
    fun anAnnouncementWithoutExamModeResolvesToNothing() {
        assertNull(ExamGate.resolve(sheet(examMode = "off"), profile(), today))
        assertNull(ExamGate.resolve(Announcement(id = "1", title = "t", message = dateSheet), profile(), today))
    }

    @Test
    fun aSplitSheetAsksForTheGroupUntilItIsAnswered() {
        val asked = ExamGate.resolve(sheet(), profile(), today)!!
        assertTrue(asked.needsGroupPick)
        assertNull(asked.group)
        assertTrue(asked.exams.isEmpty())
        assertTrue(asked.readable)
        assertEquals(listOf("Chemistry Group", "Physics Group"), asked.schedule.groups)
    }

    @Test
    fun theAnswerSelectsThatGroupsExams() {
        val chemistry = ExamGate.resolve(sheet(), profile().copy(examGroupAnnouncementId = "mse-ii", examGroupName = "Chemistry Group"), today)!!
        assertFalse(chemistry.needsGroupPick)
        assertEquals("Chemistry Group", chemistry.group)
        assertEquals(listOf("Mathematics-I", "BEEE"), chemistry.exams.map { it.subject })

        // The heading is matched loosely: case and stray spaces are not a reason to ask again.
        val physics = ExamGate.resolve(sheet(), profile().copy(examGroupAnnouncementId = "mse-ii", examGroupName = " physics group "), today)!!
        assertEquals("Physics Group", physics.group)
        // An EE A student sits only the EDG session that names EE sections, at 1:00 PM.
        assertEquals(listOf("Mathematics-I", "EDG"), physics.exams.map { it.subject })
        assertEquals(13 * 60, physics.exams.last().startMinutes)
    }

    @Test
    fun everyNewlyPublishedSheetAsksAgain() {
        val settings = profile().copy(examGroupAnnouncementId = "mse-i-september", examGroupName = "Chemistry Group")
        val state = ExamGate.resolve(sheet(id = "mse-ii"), settings, today)!!
        assertTrue(state.needsGroupPick)
        assertNull(state.group)

        val unknownName = profile().copy(examGroupAnnouncementId = "mse-ii", examGroupName = "Biology Group")
        assertTrue(ExamGate.resolve(sheet(), unknownName, today)!!.needsGroupPick)
    }

    @Test
    fun aSheetWithOneGroupOrNoHeadingsNeedsNoAnswer() {
        val single = ExamGate.resolve(
            sheet(message = "**Chemistry Group:**\n23 Nov — Mathematics-I — 9:15–10:45 AM"),
            profile(), today
        )!!
        assertFalse(single.needsGroupPick)
        assertEquals("Chemistry Group", single.group)
        assertEquals(1, single.exams.size)

        val flat = ExamGate.resolve(sheet(message = "23 Nov — Mathematics-I — 9:15–10:45 AM"), profile(), today)!!
        assertFalse(flat.needsGroupPick)
        assertNull(flat.group)
        assertEquals(listOf("Mathematics-I"), flat.exams.map { it.subject })
    }

    @Test
    fun sectionListsKeepOnlyThisStudentsLines() {
        fun physicsStudent(branch: String, section: String, subsection: String, group: String) = AppSettings(
            branch = branch, studentSection = section, studentSubsection = subsection, studentGroup = subsection,
            group = group, examGroupAnnouncementId = "mse-ii", examGroupName = "Physics Group"
        )

        val eeA = ExamGate.resolve(sheet(), physicsStudent("EE", "EE A", "EEA1", "EEAM1"), today)!!
        assertEquals(1, eeA.exams.count { it.subject == "EDG" })
        assertEquals(13 * 60, eeA.exams.first { it.subject == "EDG" }.startMinutes)

        // "ME A" covers every ME A subgroup, however the profile spells it.
        val meA1 = ExamGate.resolve(sheet(), physicsStudent("ME", "ME-A", "MEA1", "MEAM1"), today)!!
        assertEquals(1, meA1.exams.count { it.subject == "EDG" })
        assertEquals(11 * 60, meA1.exams.first { it.subject == "EDG" }.startMinutes)

        val rai = ExamGate.resolve(sheet(), physicsStudent("RAI", "RAI A", "RAIA1", "RAIAM1"), today)!!
        assertEquals(1, rai.exams.count { it.subject == "EDG" })
        assertEquals(11 * 60, rai.exams.first { it.subject == "EDG" }.startMinutes)

        // Lines without a section list are for the whole group, whatever the student's own section is.
        assertEquals(1, eeA.exams.count { it.subject == "Mathematics-I" })
        assertEquals(2, eeA.exams.size)
    }

    @Test
    fun aStudentNoLineNamesStillSeesTheDay() {
        val stranger = ExamGate.resolve(
            sheet(),
            AppSettings(
                branch = "CV", studentSection = "CV A", studentSubsection = "CVA1", studentGroup = "CVA1", group = "CVAM1",
                examGroupAnnouncementId = "mse-ii", examGroupName = "Physics Group"
            ),
            today
        )!!
        assertEquals(2, stranger.exams.count { it.subject == "EDG" })

        val anonymous = ExamGate.resolve(
            sheet(),
            AppSettings(examGroupAnnouncementId = "mse-ii", examGroupName = "Physics Group"),
            today
        )!!
        assertEquals(2, anonymous.exams.count { it.subject == "EDG" })
        assertEquals(3, anonymous.exams.size)
    }

    @Test
    fun datesWithoutAYearFollowThePublishDay() {
        val newYear = sheet(id = "end-sem", publishedAt = "2026-12-28T20:00:00+05:30", message = "2 Jan — Maths — 9–11 AM\n5 Jan — PPS — 9–11 AM")
        val state = ExamGate.resolve(newYear, profile(), today)!!
        assertEquals(LocalDate.of(2027, 1, 2), state.exams[0].date)
        assertEquals(LocalDate.of(2027, 1, 5), state.exams[1].date)

        val undated = sheet(message = "23 Nov — Maths — 9–11 AM", publishedAt = "")
        assertEquals(LocalDate.of(2026, 11, 23), ExamGate.resolve(undated, profile(), LocalDate.of(2026, 11, 20))!!.exams[0].date)
    }

    @Test
    fun anUnreadableSheetStaysInExamModeButHasNothingToShow() {
        val state = ExamGate.resolve(sheet(message = "Exams begin soon. Dates will follow."), profile(), today)
        assertNotNull(state)
        assertFalse(state!!.readable)
        assertFalse(state.needsGroupPick)
        assertTrue(state.exams.isEmpty())
    }
}
