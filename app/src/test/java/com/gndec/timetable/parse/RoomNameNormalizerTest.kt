package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNameNormalizerTest {

    @Test
    fun hyphenatedAndSpacedVariantsMerge() {
        assertEquals("F119", RoomNameNormalizer.canonical("F119"))
        assertEquals("F119", RoomNameNormalizer.canonical("F-119"))
        assertEquals("F119", RoomNameNormalizer.canonical("f-119"))
        assertEquals("S220", RoomNameNormalizer.canonical("S-220"))
        assertEquals("G1", RoomNameNormalizer.canonical("G-1"))
        assertEquals("G1", RoomNameNormalizer.canonical("G1"))
        assertEquals("G3A", RoomNameNormalizer.canonical("G 3A "))
        assertEquals("G3A", RoomNameNormalizer.canonical("G-3A (NR)"))
        assertEquals("F102", RoomNameNormalizer.canonical("F102(AUTOMOBILE BLOCK)"))
        assertEquals("G10", RoomNameNormalizer.canonical("G10 (MPE Dept.)"))
        assertEquals("F104", RoomNameNormalizer.canonical("f104"))
    }

    @Test
    fun labShorthandExpands() {
        assertEquals("COMP LAB EC", RoomNameNormalizer.canonical("COMP/L(EC)"))
        assertEquals("COMP LAB EE", RoomNameNormalizer.canonical("COMP/L(EE)"))
        assertEquals("MWR LAB", RoomNameNormalizer.canonical("MWR/L"))
        assertEquals("TI LAB", RoomNameNormalizer.canonical("TI/L"))
    }

    @Test
    fun observedAliasesUnifySpellingVariants() {
        assertEquals(
            RoomNameNormalizer.canonical("W/S SEMINAR HALL"),
            RoomNameNormalizer.canonical("WS SEMINAR HALL")
        )
        assertEquals(
            RoomNameNormalizer.canonical("W/S SEM HALL"),
            RoomNameNormalizer.canonical("WS SEMINAR HALL")
        )
        assertEquals(
            RoomNameNormalizer.canonical("W/SHOP SEM HALL"),
            RoomNameNormalizer.canonical("W/S SEMINAR HALL")
        )
        assertEquals(
            RoomNameNormalizer.canonical("MEAS. LAB"),
            RoomNameNormalizer.canonical("MEASUREMENT LAB")
        )
        assertEquals(
            RoomNameNormalizer.canonical("ADV. MEAS. LAB"),
            RoomNameNormalizer.canonical("ADVANCE MEASUREMENT LAB")
        )
        assertEquals(
            RoomNameNormalizer.canonical("PE LAB (BEE LAB 2)"),
            RoomNameNormalizer.canonical("PE LAB/ BEE LAB 2")
        )
        assertEquals(
            RoomNameNormalizer.canonical("MBA COMP LAB"),
            RoomNameNormalizer.canonical("COMP LAB MBA")
        )
        assertEquals(
            RoomNameNormalizer.canonical("TnP Seminar Hall"),
            RoomNameNormalizer.canonical("TNP SEMINAR HALL")
        )
        assertEquals(
            RoomNameNormalizer.canonical("DBMS_Lab"),
            RoomNameNormalizer.canonical("DBMS LAB")
        )
        // genuinely different rooms stay distinct
        assertEquals("TNP SEMINAR HALL", RoomNameNormalizer.canonical("TNP SEMINAR HALL"))
        assertEquals(
            "TNP SEMINAR HALL 1",
            RoomNameNormalizer.canonical("TNP SEMINAR HALL 1")
        )
    }

    @Test
    fun placeholdersAreDetected() {
        assertTrue(RoomNameNormalizer.isPlaceholder("GHOST ROOM"))
        assertTrue(RoomNameNormalizer.isPlaceholder("TEACH OFFICE"))
        assertTrue(RoomNameNormalizer.isPlaceholder("A"))
        assertTrue(RoomNameNormalizer.isPlaceholder("B"))
        assertFalse(RoomNameNormalizer.isPlaceholder("A6"))
        assertFalse(RoomNameNormalizer.isPlaceholder("F119"))
    }

    @Test
    fun blankNamesReturnNull() {
        assertNull(RoomNameNormalizer.canonical("   "))
        assertNull(RoomNameNormalizer.canonical(""))
        assertNull(RoomNameNormalizer.canonical("---"))
    }

    @Test
    fun searchMatchesPartialAndSeparatorVariants() {
        // The F19 report: searching "F19" must find F119.
        assertTrue(RoomNameNormalizer.matches("F119", "F19"))
        assertTrue(RoomNameNormalizer.matches("F-119", "f19"))
        assertTrue(RoomNameNormalizer.matches("F119", "F-19"))
        assertTrue(RoomNameNormalizer.matches("S205", "s2"))
        assertTrue(RoomNameNormalizer.matches("SEMINAR HALL BA", "sem hall"))
        assertTrue(RoomNameNormalizer.matches("F119", ""))
        assertFalse(RoomNameNormalizer.matches("G14", "F119"))
    }
}
