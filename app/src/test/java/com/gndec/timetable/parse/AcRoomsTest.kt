package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AC registry: every published spelling of an air-conditioned room must be
 * recognised, and the near-miss rooms (same block, sister lab, the other
 * department's OS lab) must NOT be.
 */
class AcRoomsTest {

    @Test
    fun publishedVariantsOfAcRoomsAreRecognised() {
        // F-block rooms, hyphenated/spaced/lowercase as departments publish them
        listOf("F101", "F-108", "f108", "F 108", "F109", "F112", "F113", "F114").forEach {
            assertTrue("expected AC: $it", AcRooms.isAcRoomName(it))
        }
        // Labs and halls by their raw published names
        listOf(
            "CHEM LAB 1", "Chem lab 1", "TNP SEMINAR HALL", "TnP Seminar Hall",
            "Central Library", "CENTRAL LIBRARY", "BEE LAB 1", "bee lab 1",
            "OS1 LAB IT DEPT", "COMPUTATIONAL LAB", "OS LAB CSE DEPT", "HW LAB",
            "HW Lab", "HW_Lab", "PROJ/L", "PROJ LAB"
        ).forEach {
            assertTrue("expected AC: $it", AcRooms.isAcRoomName(it))
        }
    }

    @Test
    fun scopedDisplayNamesResolveToTheirOwnDepartment() {
        // "OS LAB" is published by both CSE and IT, so each merges scoped; only
        // the CSE one is air-conditioned (the IT one is "OS1 LAB IT DEPT").
        assertTrue(AcRooms.isAcRoomName("OS LAB · CSE"))
        assertFalse(AcRooms.isAcRoomName("OS_Lab · IT"))
        assertFalse(AcRooms.isAcRoomName("OS LAB · MCA"))
        // Other scoped labs stay non-AC.
        assertFalse(AcRooms.isAcRoomName("CAD LAB · ME"))
        assertFalse(AcRooms.isAcRoomName("SOM LAB · CE"))
    }

    @Test
    fun lookalikeRoomsAreNotAc() {
        listOf(
            "F107", "F110", "F115", "F119",          // same block, no AC
            "F108A", "F112A",                        // annex rooms beside AC ones
            "TNP SEMINAR HALL 1",                    // the hall next door
            "BEE LAB 2", "CHEM LAB 2",               // sister labs
            "OS LAB · IT",                           // the other department's OS lab
            "W/S SEMINAR HALL", "SEMINAR HALL BA", "DEPT LIB"
        ).forEach {
            assertFalse("expected non-AC: $it", AcRooms.isAcRoomName(it))
        }
        // …while the college-wide names of the AC labs stay recognised.
        assertTrue(AcRooms.isAcRoomName("OS1 LAB IT DEPT"))
    }

    @Test
    fun mergeKeysAreRecognisedDirectly() {
        AcRooms.AC_KEYS.forEach { key -> assertTrue(AcRooms.isAcRoom(key)) }
        assertFalse(AcRooms.isAcRoom("F107"))
        assertFalse(AcRooms.isAcRoom("it:OS LAB"))
        assertEquals(16, AcRooms.AC_KEYS.size)
    }

    @Test
    fun badgePrependsSnowflakeExactlyForAcRooms() {
        assertEquals("${AcRooms.SNOWFLAKE}F108", AcRooms.badge("F108", "F108"))
        assertEquals("${AcRooms.SNOWFLAKE}Central Library", AcRooms.badge("Central Library", "CENTRAL LIBRARY"))
        assertEquals("${AcRooms.SNOWFLAKE}OS LAB · CSE", AcRooms.badge("OS LAB · CSE", "cse:OS LAB"))
        assertEquals("F107", AcRooms.badge("F107", "F107"))
        assertEquals("OS_Lab · IT", AcRooms.badge("OS_Lab · IT", "it:OS LAB"))
        // Key and display name disagree: either one recognising the room is enough.
        assertEquals("${AcRooms.SNOWFLAKE}F108", AcRooms.badge("F108", mergeKey = null))
    }

    @Test
    fun snowflakeDetectionAcceptsBareGlyphAndEmojiForm() {
        assertTrue(AcRooms.hasSnowflake("❄"))
        assertTrue(AcRooms.hasSnowflake("❄️"))
        assertTrue(AcRooms.hasSnowflake("some ❄️ text"))
        assertFalse(AcRooms.hasSnowflake("F108"))
        assertFalse(AcRooms.hasSnowflake(""))
        assertEquals("F108", AcRooms.stripSnowflake("❄️F108").trim())
        assertEquals("f1", AcRooms.stripSnowflake("❄ f1").trim())
        assertTrue(AcRooms.isSnowflakeOnly("❄️"))
        assertTrue(AcRooms.isSnowflakeOnly("❄"))
        assertTrue(AcRooms.isSnowflakeOnly(" ❄️ ❄ "))
        assertFalse(AcRooms.isSnowflakeOnly("❄️F108"))
        assertFalse(AcRooms.isSnowflakeOnly("F108"))
    }
}
