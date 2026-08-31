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
}
