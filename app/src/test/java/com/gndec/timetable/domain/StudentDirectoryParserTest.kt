package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests use SYNTHETIC rows that mirror the official GNDEC permanent-section
 * PDF layout — never real student data.
 */
class StudentDirectoryParserTest {

    private val nameSplits = mapOf(
        "2614001" to StudentDirectoryParser.NameSplit("Test Student One", "Test Father One", "Test Mother One"),
        "2614002" to StudentDirectoryParser.NameSplit("Test Student Two", "Test Father Two", "Test Mother Two")
    )
    private val regFallback = mapOf("2614002" to "26099999")

    @Test
    fun `parses current layout with registration column`() {
        val lines = listOf(
            "S.No.", "College Roll", "No.", "Registration No. Student Name", "Branch Section",
            "1 2614001 26012345 Test Student One Test Father One Test Mother One CE CEA CEA1 CEAM1 Dr. Mentor A 9815830889 Geotech Lab"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback)
        assertEquals(1, records.size)
        val r = records.single()
        assertEquals("2614001", r.crn)
        assertEquals("26012345", r.registrationNumber)
        assertEquals("Test Student One", r.candidateName)
        assertEquals("Test Father One", r.fatherName)
        assertEquals("Test Mother One", r.motherName)
        assertEquals("CE", r.branch)
        assertEquals("CEA", r.section)
        assertEquals("CEA1", r.subsection)
        assertEquals("CEAM1", r.group)
        assertEquals("Dr. Mentor A", r.mentorName)
        assertEquals("9815830889", r.mentorMobile)
        assertEquals("Geotech Lab", r.venue)
    }

    @Test
    fun `legacy layout falls back to bundled registration map`() {
        val lines = listOf(
            "1 2614002 Test Student Two Test Father Two Test Mother Two CE CEB CEB2 CEBM2 Er. Mentor B 9876543210 TNP Seminar Hall 1"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback)
        val r = records.single()
        assertEquals("2614002", r.crn)
        assertEquals("26099999", r.registrationNumber)
        assertEquals("Test Student Two", r.candidateName)
    }

    @Test
    fun `unknown student keeps full pdf name text and blank parents`() {
        val lines = listOf(
            "9 2614999 26098877 Brandnew Student New Father New Mother CE CEA CEA2 CEAM2 Dr. Mentor C 9000000001 Room 101"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback)
        val r = records.single()
        assertEquals("Brandnew Student New Father New Mother", r.candidateName)
        assertEquals("", r.fatherName)
        assertEquals("", r.motherName)
        assertEquals("26098877", r.registrationNumber)
    }

    @Test
    fun `corrected pdf names never overwrite with mismatched bundled split`() {
        // PDF tokens differ from the bundled split for the same CRN -> keep PDF text whole.
        val lines = listOf(
            "1 2614001 26012345 Corrected Studentname Same Father One Test Mother One CE CEA CEA1 CEAM1 Dr. Mentor A 9815830889 Geotech Lab"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback)
        val r = records.single()
        assertEquals("Corrected Studentname Same Father One Test Mother One", r.candidateName)
        assertEquals("", r.fatherName)
    }

    @Test
    fun `headers page numbers and other branch rows are ignored`() {
        val lines = listOf(
            "S.No.",
            "Mentoring",
            "1 2699999 26011111 Other Branch Student Some Father Some Mother XX XXA XXA1 XXAM1 Dr. X 9000000009 Lab",
            "1 2614003 26012222 Test Student Three Test Father Three Test Mother Three CE CEA CEA1 CEAM1 Dr. Mentor A 9815830889 Geotech Lab"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, emptyMap())
        assertEquals(1, records.size)
        assertEquals("2614003", records.single().crn)
    }

    @Test
    fun `whitespace irregularities are normalized`() {
        val lines = listOf(
            "14 2614014 26013738  Double   Spaced   Name  Father  Name  Mother Name CE CEA CEA1 CEAM1 Dr. Mentor A 9815830889 MWR\\L "
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, emptyMap())
        val r = records.single()
        assertEquals("Double Spaced Name Father Name Mother Name", r.candidateName)
        assertEquals("MWR\\L", r.venue)
    }

    @Test
    fun `cross branch mentoring group is preserved verbatim`() {
        val lines = listOf(
            "1 2630001 26010447 Synthetic Student Father Name Mother Name ME MEA MEA1 ITCM3 Er. Mentor D 9872769887 TNP SEMINAR HALL 1"
        )
        val records = StudentDirectoryParser.parse(lines, "ME", emptyMap(), emptyMap())
        assertEquals("ITCM3", records.single().group)
    }

    @Test
    fun `multi token venue is preserved`() {
        val lines = listOf(
            "1 2614001 26012345 Test Student One Test Father One Test Mother One CE CEA CEA1 CEAM1 Dr. Mentor A 9815830889 HT LAB (ME)"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits, emptyMap())
        assertEquals("HT LAB (ME)", records.single().venue)
        assertTrue(records.single().candidateName.isNotBlank())
    }

    // ---- STRICT column-aware (pipe) rows ----

    @Test
    fun `pipe rows take names straight from the official columns`() {
        // Column-aware extraction of a real 2026 row — no bundled split needed.
        val lines = listOf(
            "1| 2614001| 26012345| Test Student One| Test Father One| Test Mother One| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits = emptyMap(), registrationFallback = emptyMap())
        assertEquals(1, records.size)
        val r = records.single()
        assertEquals("2614001", r.crn)
        assertEquals("26012345", r.registrationNumber)
        assertEquals("Test Student One", r.candidateName)
        assertEquals("Test Father One", r.fatherName)
        assertEquals("Test Mother One", r.motherName)
        assertEquals("CEA", r.section)
        assertEquals("CEAM1", r.group)
        assertEquals("Dr. Mentor A", r.mentorName)
        assertEquals("9815830889", r.mentorMobile)
        assertEquals("Geotech Lab", r.venue)
    }

    @Test
    fun `pipe rows never combine parent names into the student name`() {
        // The case the strict path exists for: bundled split missing, legacy
        // extraction would show "Student Father Mother" as one name.
        val lines = listOf(
            "7| 2614991| 26017111| Riya Kapoor| Rohita Gupta| Somraj Devi| CE| CEA| CEA2| CEAM2| Dr. Mentor C| 9000000001| Room 101"
        )
        val r = StudentDirectoryParser.parse(lines, "CE", nameSplits = emptyMap()).single()
        assertEquals("Riya Kapoor", r.candidateName)
        assertEquals("Rohita Gupta", r.fatherName)
        assertEquals("Somraj Devi", r.motherName)
    }

    @Test
    fun `pipe row without registration column uses fallback map`() {
        val lines = listOf(
            "2| 2614002| Test Student Two| Test Father Two| Test Mother Two| CE| CEB| CEB2| CEBM2| Er. Mentor B| 9876543210| TNP Seminar Hall 1"
        )
        val r = StudentDirectoryParser.parse(lines, "CE", nameSplits = emptyMap(), registrationFallback = regFallback).single()
        assertEquals("26099999", r.registrationNumber)
        assertEquals("Test Student Two", r.candidateName)
    }

    @Test
    fun `ambiguous pipe rows fall back to legacy handling`() {
        // A merged cell (registration and student fused by a missed gap) breaks
        // the exact structure -> strict path rejects, legacy path decides.
        val lines = listOf(
            "1| 2614001 26012345 Test Student One| Test Father One| Test Mother One| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab"
        )
        val r = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback).single()
        assertEquals("2614001", r.crn)
        assertEquals("Test Student One", r.candidateName)
        assertEquals("Test Father One", r.fatherName)
    }

    @Test
    fun `merged crn and registration cell still takes names from columns`() {
        // REAL 2026 production shape: the CRN and registration columns sit so
        // close together that the extractor merges them into ONE cell. This is
        // the bug that made 2.4.30's strict path reject every row and kept the
        // father/mother names glued to the student's name. No bundled split is
        // provided here — the columns alone must decide.
        val lines = listOf(
            "1| 2614001 26012345| Test Student One| Test Father One| Test Mother One| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab"
        )
        val r = StudentDirectoryParser.parse(lines, "CE", nameSplits = emptyMap()).single()
        assertEquals("2614001", r.crn)
        assertEquals("26012345", r.registrationNumber)
        assertEquals("Test Student One", r.candidateName)
        assertEquals("Test Father One", r.fatherName)
        assertEquals("Test Mother One", r.motherName)
    }

    @Test
    fun `merged serial crn and registration cell parses`() {
        val lines = listOf(
            "33| 2614009 26019999| Test Student Nine| Test Father Nine| Test Mother Nine| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab",
            "12 2614008 26019998| Test Student Eight| Test Father Eight| Test Mother Eight| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab"
        )
        val records = StudentDirectoryParser.parse(lines, "CE", nameSplits = emptyMap())
        assertEquals(2, records.size)
        assertEquals("2614009", records[0].crn)
        assertEquals("26019999", records[0].registrationNumber)
        assertEquals("Test Student Nine", records[0].candidateName)
        assertEquals("2614008", records[1].crn)
        assertEquals("Test Student Eight", records[1].candidateName)
    }

    @Test
    fun `swapped father mother columns are corrected from the bundled split`() {
        // The official PDF occasionally swaps the Father/Mother cells of a row;
        // the bundled directory carries the corrected order for that CRN.
        val swappedSplits = mapOf(
            "2621191" to StudentDirectoryParser.NameSplit("Riya Kapoor", "Somraj", "Rohita Gupta")
        )
        // PDF columns read: student / "Rohita Gupta" under Father / "Somraj" under Mother.
        val lines = listOf(
            "33| 2621191 26015016| Riya Kapoor| Rohita Gupta| Somraj| IT| ITB| ITB1| ITBM2| Er. Mentor D| 8968801937| HW LAB"
        )
        val r = StudentDirectoryParser.parse(lines, "IT", nameSplits = swappedSplits).single()
        assertEquals("Riya Kapoor", r.candidateName)
        assertEquals("Somraj", r.fatherName)
        assertEquals("Rohita Gupta", r.motherName)
    }

    @Test
    fun `swapped names are not applied without the bundled split`() {
        // No bundle entry -> never guess: the columns are used as-is.
        val lines = listOf(
            "33| 2621191 26015016| Riya Kapoor| Rohita Gupta| Somraj| IT| ITB| ITB1| ITBM2| Er. Mentor D| 8968801937| HW LAB"
        )
        val r = StudentDirectoryParser.parse(lines, "IT", nameSplits = emptyMap()).single()
        assertEquals("Riya Kapoor", r.candidateName)
        assertEquals("Rohita Gupta", r.fatherName)
        assertEquals("Somraj", r.motherName)
    }

    @Test
    fun `legacy path applies a swapped bundled split`() {
        // Legacy extraction has no pipes: parents are fused into namesPart in
        // the PDF's (swapped) order. The swapped-token match must still verify
        // and apply the bundle's corrected order.
        val swappedSplits = mapOf(
            "2621191" to StudentDirectoryParser.NameSplit("Riya Kapoor", "Somraj", "Rohita Gupta")
        )
        val lines = listOf(
            "33 2621191 26015016 Riya Kapoor Rohita Gupta Somraj IT ITB ITB1 ITBM2 Er. Mentor D 8968801937 HW LAB"
        )
        val r = StudentDirectoryParser.parse(lines, "IT", nameSplits = swappedSplits).single()
        assertEquals("Riya Kapoor", r.candidateName)
        assertEquals("Somraj", r.fatherName)
        assertEquals("Rohita Gupta", r.motherName)
    }

    @Test
    fun `crn cell fused with name text still falls back to legacy`() {
        // The gap between the registration number and the student name was
        // missed AND the cell carries name text — unambiguous rejection, the
        // legacy path decides via the bundled split.
        val lines = listOf(
            "1| 2614001 26012345 Test Student One| Test Father One| Test Mother One| CE| CEA| CEA1| CEAM1| Dr. Mentor A| 9815830889| Geotech Lab"
        )
        val r = StudentDirectoryParser.parse(lines, "CE", nameSplits, regFallback).single()
        assertEquals("2614001", r.crn)
        assertEquals("Test Student One", r.candidateName)
        assertEquals("Test Father One", r.fatherName)
    }

    @Test
    fun `other branch rows in pipe format are rejected`() {
        val lines = listOf(
            "1| 2699999| 26011111| Other Branch Student| Some Father| Some Mother| XX| XXA| XXA1| XXAM1| Dr. X| 9000000009| Lab"
        )
        assertTrue(StudentDirectoryParser.parse(lines, "CE").isEmpty())
    }
}
