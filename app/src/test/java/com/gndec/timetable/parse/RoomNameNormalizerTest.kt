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

    @Test
    fun snowflakeQueryMatchesAcRoomsOnly() {
        // Emoji form, bare glyph, snowflake buried in spaces — all the same ask.
        assertTrue(RoomNameNormalizer.matches("F108", AcRooms.SNOWFLAKE))
        assertTrue(RoomNameNormalizer.matches("F108", "❄"))
        assertTrue(RoomNameNormalizer.matches("Central Library", AcRooms.SNOWFLAKE))
        assertTrue(RoomNameNormalizer.matches("PROJ/L", AcRooms.SNOWFLAKE))
        assertTrue(RoomNameNormalizer.matches("OS LAB · CSE", AcRooms.SNOWFLAKE))
        // Near misses that must stay hidden.
        assertFalse(RoomNameNormalizer.matches("F108A", AcRooms.SNOWFLAKE))
        assertFalse(RoomNameNormalizer.matches("F107", AcRooms.SNOWFLAKE))
        assertFalse(RoomNameNormalizer.matches("OS_Lab · IT", AcRooms.SNOWFLAKE))
        assertFalse(RoomNameNormalizer.matches("TNP SEMINAR HALL 1", AcRooms.SNOWFLAKE))
        assertFalse(RoomNameNormalizer.matches("BEE LAB 2", AcRooms.SNOWFLAKE))
    }

    @Test
    fun snowflakeQueryCombinesWithRoomText() {
        assertTrue(RoomNameNormalizer.matches("F108", "${AcRooms.SNOWFLAKE}F108"))
        assertTrue(RoomNameNormalizer.matches("F108", "${AcRooms.SNOWFLAKE} f108"))
        assertTrue(RoomNameNormalizer.matches("F108", "F108 ${AcRooms.SNOWFLAKE}"))
        assertTrue(RoomNameNormalizer.matches("F108", "${AcRooms.SNOWFLAKE}f1"))
        assertTrue(RoomNameNormalizer.matches("CHEM LAB 1", "❄ chem"))
        // The text still has to match — a snowflake does not widen the query.
        assertFalse(RoomNameNormalizer.matches("F107", "${AcRooms.SNOWFLAKE}F108"))
        assertFalse(RoomNameNormalizer.matches("F108", "${AcRooms.SNOWFLAKE}F107"))
    }

    @Test
    fun roomLikeChecksAreCaseInsensitive() {
        // Search pre-filters lowercase the query before this check.
        assertTrue(RoomNameNormalizer.isRoomLike("f108"))
        assertTrue(RoomNameNormalizer.isRoomLike("F108"))
        assertTrue(RoomNameNormalizer.isRoomLike("S220"))
        assertFalse(RoomNameNormalizer.isRoomLike("OS LAB"))
        assertFalse(RoomNameNormalizer.isRoomLike("library"))
    }
}
