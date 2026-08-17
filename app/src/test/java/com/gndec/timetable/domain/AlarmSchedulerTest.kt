package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSchedulerTest {

    @Test
    fun alarmIdsAreDeterministic() {
        val a = AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_15")
        val b = AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_15")
        assertEquals(a, b)
        assertTrue(a >= 0)
    }

    @Test
    fun differentReminderTypesNeverCollide() {
        val base = AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_15")
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_30"))
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_5"))
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "AT_START"))
    }

    @Test
    fun differentGroupsDatesAndTimesNeverCollide() {
        val base = AlarmScheduler.alarmRequestCode("ITB2", 20000L, 510, "BEFORE_15")
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB1", 20000L, 510, "BEFORE_15"))
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB2", 20001L, 510, "BEFORE_15"))
        assertNotEquals(base, AlarmScheduler.alarmRequestCode("ITB2", 20000L, 570, "BEFORE_15"))
    }

    @Test
    fun defaultReminderConfigMatchesSpec() {
        // spec defaults: 15 min before ON, 30 OFF, 5 OFF, at start ON
        val cfg = ReminderConfig(remind15 = true, remind30 = false, remind5 = false, remindAtStart = true)
        assertEquals(listOf("BEFORE_15" to 15, "AT_START" to 0), cfg.types())
    }

    @Test
    fun allRemindersEnabledProducesFourTypes() {
        val cfg = ReminderConfig(true, true, true, true)
        assertEquals(4, cfg.types().size)
    }

    @Test
    fun allRemindersDisabledSchedulesNothing() {
        assertTrue(ReminderConfig(false, false, false, false).types().isEmpty())
    }
}
