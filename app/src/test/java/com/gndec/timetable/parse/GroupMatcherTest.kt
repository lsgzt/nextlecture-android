package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data-driven mapping tests built from the REAL group names published in the
 * live 2026 departmental timetables (CSE "D2 CS A", IT "D2IT_A", EE "D2A",
 * CE "D2 CE A" + electives, ME streams, ECE practical subgroups). Group
 * naming is deliberately NOT assumed to be uniform across departments.
 */
class GroupMatcherTest {

    // Real TOC listings captured from the live documents (Aug 2026).
    private val cseGroups = listOf(
        "D2 CS A", "D2 CS B", "D2 CS C", "D2 CS D", "D2 CS E", "D2 CS F",
        "D3 CS A", "D3 CS B", "D3 CS C", "D3 CS D", "D3 CS E", "D3 CS F",
        "D4 CS A", "D4 CS B", "D4 CS C", "D4 CS D", "D4 CS E",
        "M1 Automatic Group", "M3 Automatic Group", "PHD Automatic Group",
        "D1 CS A1", "D1 CS A2", "D1 CS B1", "D1 CS B2"
    )
    private val itGroups = listOf(
        "D2IT_A", "D2IT_B", "D2IT_C", "D3IT_A", "D3IT_B", "D3IT_C", "D4IT",
        "M.Tech_1", "M.Tech_3", "D1IT_A", "D1IT_B"
    )
    private val eeGroups = listOf("D2A", "D2B", "D3A", "D3B", "D4A", "D4B", "M1PW", "M2PW", "D1EEA", "D1CSA")
    private val ceGroups = listOf(
        "D2 CE A", "D2 CE B", "D2CEMC1", "D2CEMC2", "D3 CE A", "D3 CE B",
        "D3CE(Geology)", "D3CE(DM)", "D4CEMC1", "D4CEMC2", "D4CE (STR)", "D4CE (Env)",
        "D4_EIA(OA)", "D4_EIA(OB)", "M.TECH STR", "First Year", "ARCH3", "D3CSEA", "D3EEA"
    )
    private val meGroups = listOf(
        "D2 RAI A1", "D2 RAI A2", "D2MEA", "D2MEB", "D3ME A", "D3ME B",
        "D4 ME MANUFACTURING", "D4 ME THERMAL", "D4 ME DESIGN", "D4 ME A1", "D4 ME B3",
        "M.TECH OE Automatic Group", "CSE A", "D3 ECE A", "D1 BCA A",
        // Published in the live ME document (Jul-Dec 2026) with no branch token.
        "D3 BCA A", "D3 BCA B"
    )
    private val eceGroups = listOf(
        "D4ECA1", "D4ECA2", "D4ECA3", "D4ECB1", "D4ECB2", "D4ECB3",
        "D3ECA", "D3ECB", "D2ECA", "D2ECB", "D1ECA1", "M1EC Automatic Group"
    )

    @Test
    fun cseSectionMapsToSpacedGroupName() {
        val groups = GroupMatcher.groupsForSection(cseGroups, "CS", 2, "A")
        assertEquals(listOf("D2 CS A"), groups)
        assertEquals(listOf("D4 CS E"), GroupMatcher.groupsForSection(cseGroups, "CS", 4, "E"))
    }

    @Test
    fun cseSectionsListAllPublishedSections() {
        val sections = GroupMatcher.sectionsForYear(cseGroups, "CS", 2)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), sections)
    }

    @Test
    fun itUnderscoredGroupsMapAndD4HasSingleGroup() {
        assertEquals(listOf("D3IT_B"), GroupMatcher.groupsForSection(itGroups, "IT", 3, "B"))
        // D4IT carries no section suffix — one option, labeled "All".
        assertEquals(listOf(""), GroupMatcher.sectionsForYear(itGroups, "IT", 4))
        assertEquals(listOf("D4IT"), GroupMatcher.groupsForSection(itGroups, "IT", 4, ""))
        assertEquals("All", GroupMatcher.sectionLabel(""))
    }

    @Test
    fun eeGroupsOmitTheBranchToken() {
        assertEquals(listOf("D2A"), GroupMatcher.groupsForSection(eeGroups, "EE", 2, "A"))
        assertEquals(listOf("A", "B"), GroupMatcher.sectionsForYear(eeGroups, "EE", 4))
        // "D1CSA" is CS, not EE — must not leak into EE year selection (wrong year anyway).
        assertTrue(GroupMatcher.groupsForYear(eeGroups, "EE", 2).all { it.raw in setOf("D2A", "D2B") })
    }

    @Test
    fun ceExposesRealSectionsIncludingElectiveGroups() {
        assertEquals(listOf("D2 CE A"), GroupMatcher.groupsForSection(ceGroups, "CE", 2, "A"))
        assertEquals(listOf("D3 CE B"), GroupMatcher.groupsForSection(ceGroups, "CE", 3, "B"))
        // D4 CE has no plain A/B — its real published groups are offered instead.
        val d4Sections = GroupMatcher.sectionsForYear(ceGroups, "CE", 4)
        assertTrue(d4Sections.containsAll(listOf("MC1", "MC2", "STR", "ENV")))
        assertTrue(GroupMatcher.groupsForSection(ceGroups, "CE", 4, "STR").contains("D4CE (STR)"))
        // Cross-department groups inside the CE file must never match CE students.
        assertFalse(GroupMatcher.groupsForYear(ceGroups, "CE", 3).any { it.raw in setOf("D3CSEA", "D3EEA") })
    }

    @Test
    fun meHandlesCompactNamesStreamsAndRai() {
        assertEquals(listOf("D2MEA"), GroupMatcher.groupsForSection(meGroups, "ME", 2, "A"))
        assertEquals(listOf("A", "B"), GroupMatcher.sectionsForYear(meGroups, "ME", 3))
        // 4th year ME: stream groups + practical groups, no plain A/B.
        val d4 = GroupMatcher.sectionsForYear(meGroups, "ME", 4)
        assertTrue(d4.containsAll(listOf("MANUFACTURING", "THERMAL", "DESIGN", "A1", "B3")))
        // RAI students live inside the ME file under their own branch token.
        assertEquals(listOf("D2 RAI A1"), GroupMatcher.groupsForSection(meGroups, "RAI", 2, "A1"))
        assertEquals(listOf("A1", "A2"), GroupMatcher.sectionsForYear(meGroups, "RAI", 2))
        // ME students must not receive RAI groups and vice versa.
        assertFalse(GroupMatcher.groupsForYear(meGroups, "ME", 2).any { it.raw.startsWith("D2 RAI") })
    }

    @Test
    fun ecePracticalSubgroupsAreDistinctSections() {
        assertEquals(listOf("A", "B"), GroupMatcher.sectionsForYear(eceGroups, "EC", 2))
        val d4Sections = GroupMatcher.sectionsForYear(eceGroups, "EC", 4)
        assertEquals(listOf("A1", "A2", "A3", "B1", "B2", "B3"), d4Sections)
        assertEquals(listOf("D4ECA2"), GroupMatcher.groupsForSection(eceGroups, "EC", 4, "A2"))
    }

    @Test
    fun firstYearMtechAndForeignGroupsAreExcluded() {
        for (groups in listOf(cseGroups, itGroups, eeGroups, ceGroups, meGroups, eceGroups)) {
            for (dept in listOf("CS", "IT", "EE", "CE", "ME", "EC", "RAI")) {
                val parsed = GroupMatcher.groupsForYear(groups, dept, 2)
                assertTrue(
                    "no M.Tech/PHD/Automatic/1st-year group may survive: $parsed",
                    parsed.none {
                        it.raw.contains("Automatic", ignoreCase = true) ||
                            it.raw.contains("M.Tech", ignoreCase = true) ||
                            it.raw.contains("PHD", ignoreCase = true) ||
                            GroupMatcher.parseGroup(it.raw).year != 2
                    }
                )
            }
        }
    }

    @Test
    fun hasGroupsForValidatesDocuments() {
        assertTrue(GroupMatcher.hasGroupsFor(cseGroups, "CS", 3))
        // The CSE document contains no IT groups for year 3 (only year-1 D1IT in other files).
        assertFalse(GroupMatcher.hasGroupsFor(cseGroups, "IT", 3))
        assertFalse(GroupMatcher.hasGroupsFor(listOf("M1PW", "ARCH3"), "ME", 2))
    }

    @Test
    fun normalizationUnifiesSeparatorsAndCase() {
        assertEquals("D2CSA", GroupMatcher.normalize("d2 cs-a"))
        assertEquals("D2CSA", GroupMatcher.normalize("D2_CS_A"))
        val pg = GroupMatcher.parseGroup("d2-it_b")
        // D2ITB: branch token IT is found even without the underscore.
        assertEquals(2, pg.year)
        assertEquals("IT", pg.branch)
        assertEquals("B", pg.section)
    }

    // ---- matchGroup: linking an onboarding catalog pick to the stored cache ----

    @Test
    fun matchGroupPrefersExactPublishedName() {
        assertEquals("D2 CS A", GroupMatcher.matchGroup(cseGroups, "D2 CS A"))
    }

    @Test
    fun matchGroupToleratesSeparatorAndCaseDrift() {
        // The picker/free-text path may produce differently spaced/cased names;
        // the stored Room groups keep the published spelling.
        assertEquals("D2 CS A", GroupMatcher.matchGroup(cseGroups, "D2CSA"))
        assertEquals("D2 CS A", GroupMatcher.matchGroup(cseGroups, "d2 cs-a"))
        assertEquals("D2IT_A", GroupMatcher.matchGroup(itGroups, "D2IT-A"))
        assertEquals("D4CE (STR)", GroupMatcher.matchGroup(ceGroups, "D4CESTR"))
        assertEquals("D2A", GroupMatcher.matchGroup(eeGroups, "d2 a"))
    }

    @Test
    fun matchGroupReturnsNullForUnknownOrBlank() {
        assertEquals(null, GroupMatcher.matchGroup(cseGroups, "D9 CS Z"))
        assertEquals(null, GroupMatcher.matchGroup(cseGroups, ""))
        assertEquals(null, GroupMatcher.matchGroup(cseGroups, "   "))
    }

    @Test
    fun raiCatalogRoundTripWithMeDocument() {
        // RAI students study under the ME department's document; their groups
        // carry the RAI token ("D2 RAI A1"). Previously GroupTimetableManager
        // rejected the 3-letter branch code outright.
        assertTrue(GroupMatcher.hasGroupsFor(meGroups, "RAI", 2))
        val sections = GroupMatcher.sectionsForYear(meGroups, "RAI", 2)
        assertTrue(sections.containsAll(listOf("A1", "A2")))
        val picked = GroupMatcher.groupsForSection(meGroups, "RAI", 2, "A1")
        assertEquals(listOf("D2 RAI A1"), picked)
        // And a free-text drift of the same group still links back to it.
        assertEquals("D2 RAI A1", GroupMatcher.matchGroup(meGroups, "D2RAIA1"))
    }

    @Test
    fun nonBtechProgrammeSectionsNeverLeakIntoPickers() {
        // The ME document publishes BCA sections ("D3 BCA A") with no branch
        // token; they must not be offered to ME/RAI seniors as sections.
        val me3 = GroupMatcher.sectionsForYear(meGroups, "ME", 3)
        assertTrue(me3.containsAll(listOf("A", "B")))
        assertFalse("BCA sections leaked into ME year 3: $me3", me3.any { it.startsWith("BCA") })
        // The live ME document really does carry them — the filter is what saves us.
        assertTrue(meGroups.any { GroupMatcher.normalize(it).startsWith("D3BCA") })
        // And a department with no year-3 RAI document sections honestly reports none.
        assertFalse(GroupMatcher.hasGroupsFor(meGroups, "RAI", 3))
    }

    // ---- relinkCandidate: self-heal for migrated / drifted senior profiles ----

    @Test
    fun relinkFindsTheStoredSeniorGroup() {
        // 2.4.26 senior whose subsection holds the picked group (exact or drifted).
        assertEquals("D2 CS A", GroupMatcher.relinkCandidate(cseGroups, "D2 CS A"))
        assertEquals("D2 CS A", GroupMatcher.relinkCandidate(cseGroups, "D2CSA"))
        assertEquals("D4ECA1", GroupMatcher.relinkCandidate(eceGroups, "d4 eca 1"))
        assertEquals("D2IT_A", GroupMatcher.relinkCandidate(itGroups, "D2IT_A"))
    }

    @Test
    fun relinkNeverGuessesFromFirstYearStyleNames() {
        // A migrated 1st-year profile stores "A1"-style subsections; nothing in
        // a departmental document may match them, and blank never matches.
        assertEquals(null, GroupMatcher.relinkCandidate(cseGroups, "A1"))
        assertEquals(null, GroupMatcher.relinkCandidate(eeGroups, "A1"))
        assertEquals(null, GroupMatcher.relinkCandidate(cseGroups, ""))
        assertEquals(null, GroupMatcher.relinkCandidate(cseGroups, null))
    }

    @Test
    fun relinkRejectsGroupsWithoutSeniorYearPrefix() {
        // "CSE A" exists in the ME document but carries no D2-D4 year — it must
        // never be auto-selected; only real departmental year groups qualify.
        assertEquals("CSE A", GroupMatcher.matchGroup(meGroups, "CSE A"))
        assertEquals(null, GroupMatcher.relinkCandidate(meGroups, "CSE A"))
    }
}
