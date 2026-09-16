package com.gndec.timetable.domain

import com.gndec.timetable.data.prefs.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementAudienceTest {

    private fun profile(
        branch: String = "IT",
        section: String = "ITB",
        subsection: String = "ITB2",
        group: String? = "ITB2"
    ) = AppSettings(
        branch = branch,
        studentSection = section,
        studentSubsection = subsection,
        studentGroup = subsection,
        group = group
    )

    @Test
    fun everyoneWhenNoTargeting() {
        val a = Announcement(id = "1", title = "t", message = "m")
        assertTrue(a.matchesAudience(profile()))
        assertTrue(a.matchesAudience(AppSettings()))
    }

    @Test
    fun branchAllMatchesAnyone() {
        val a = Announcement(id = "1", title = "t", message = "m", branch = "all")
        assertTrue(a.matchesAudience(profile(branch = "CS")))
    }

    @Test
    fun branchOnlyFiltersToThatBranch() {
        val a = Announcement(id = "1", title = "t", message = "m", branch = "IT")
        assertTrue(a.matchesAudience(profile(branch = "IT")))
        assertFalse(a.matchesAudience(profile(branch = "CS")))
    }

    @Test
    fun sectionRequiresBranchMatchAndSection() {
        val a = Announcement(id = "1", title = "t", message = "m", branch = "IT", section = "ITB")
        assertTrue(a.matchesAudience(profile()))
        assertFalse(a.matchesAudience(profile(section = "ITA")))
        assertFalse(a.matchesAudience(profile(branch = "CS", section = "ITB")))
    }

    @Test
    fun subsectionMatchesGroupAliasesCaseInsensitive() {
        val a = Announcement(
            id = "1", title = "t", message = "m",
            branch = "it", section = "itb", subsection = "itb-2"
        )
        assertTrue(a.matchesAudience(profile(subsection = "ITB2", group = "ITB2")))
        assertFalse(a.matchesAudience(profile(subsection = "ITB1", group = "ITB1")))
    }

    @Test
    fun missingProfileIdentityOnlySeesBroadcasts() {
        val targeted = Announcement(id = "1", title = "t", message = "m", branch = "IT")
        val broadcast = Announcement(id = "2", title = "t", message = "m", branch = "all")
        assertFalse(targeted.matchesAudience(AppSettings()))
        assertTrue(broadcast.matchesAudience(AppSettings()))
    }

    @Test
    fun multiValueBranchMatchesAnyListed() {
        val a = Announcement(id = "1", title = "t", message = "m", branch = "CS, IT")
        assertTrue(a.matchesAudience(profile(branch = "IT")))
        assertTrue(a.matchesAudience(profile(branch = "CS")))
        assertFalse(a.matchesAudience(profile(branch = "ME")))
    }

    @Test
    fun multiValueSectionAndSubsection() {
        val a = Announcement(
            id = "1", title = "t", message = "m",
            branch = "IT",
            section = "ITA, ITB",
            subsection = "ITB1; ITB2"
        )
        assertTrue(a.matchesAudience(profile(section = "ITB", subsection = "ITB2", group = "ITB2")))
        assertTrue(a.matchesAudience(profile(section = "ITA", subsection = "ITB1", group = "ITB1")))
        assertFalse(a.matchesAudience(profile(section = "ITC", subsection = "ITC1", group = "ITC1")))
        assertFalse(a.matchesAudience(profile(section = "ITB", subsection = "ITB3", group = "ITB3")))
    }

    @Test
    fun multiValueWithAllInListStillMeansEveryoneAtThatLevel() {
        val a = Announcement(id = "1", title = "t", message = "m", branch = "all, *")
        assertTrue(a.matchesAudience(profile(branch = "ECE")))
    }
}
