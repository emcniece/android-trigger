package com.emcniece.trigger

import com.emcniece.trigger.model.AppJson
import com.emcniece.trigger.model.Constraint
import com.emcniece.trigger.model.ConstraintMatch
import com.emcniece.trigger.model.DeviceState
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.NetworkRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private data class FakeState(
    override val now: LocalDateTime = LocalDateTime.of(2026, 11, 9, 12, 0), // a Monday
    override val batteryLevel: Int = 60,
    override val isCharging: Boolean = false,
    override val networkType: String = "wifi",
    override val screenOn: Boolean = true,
) : DeviceState

private fun at(hour: Int, minute: Int = 0) = FakeState(now = LocalDateTime.of(2026, 11, 9, hour, minute))

class ConstraintTest {
    @Test fun timeWindowSameDay() {
        val c = Constraint.TimeWindow(9, 0, 17, 0)
        assertTrue(c.isMet(at(9)))
        assertTrue(c.isMet(at(16, 59)))
        assertFalse(c.isMet(at(17))) // end is exclusive
        assertFalse(c.isMet(at(8, 59)))
    }

    @Test fun timeWindowOvernight() {
        val c = Constraint.TimeWindow(22, 0, 6, 0)
        assertTrue(c.isMet(at(23)))
        assertTrue(c.isMet(at(0, 30)))
        assertTrue(c.isMet(at(5, 59)))
        assertFalse(c.isMet(at(6)))
        assertFalse(c.isMet(at(12)))
    }

    @Test fun timeWindowEqualEndsMeansAllDay() {
        assertTrue(Constraint.TimeWindow(8, 0, 8, 0).isMet(at(3)))
    }

    @Test fun daysOfWeek() {
        val monday = FakeState()
        val sunday = FakeState(now = LocalDateTime.of(2026, 11, 15, 12, 0))
        assertTrue(Constraint.DaysOfWeek().isMet(monday)) // default is weekdays
        assertFalse(Constraint.DaysOfWeek().isMet(sunday))
        assertTrue(Constraint.DaysOfWeek(setOf(6, 7)).isMet(sunday))
        assertEquals("on weekends", Constraint.DaysOfWeek(setOf(6, 7)).describe())
        assertEquals("on Mon, Wed", Constraint.DaysOfWeek(setOf(3, 1)).describe())
    }

    @Test fun deviceConditions() {
        val s = FakeState(batteryLevel = 30, isCharging = true, networkType = "cellular", screenOn = false)
        assertTrue(Constraint.Charging(true).isMet(s))
        assertFalse(Constraint.Charging(false).isMet(s))
        assertTrue(Constraint.Battery(30, atLeast = true).isMet(s))
        assertFalse(Constraint.Battery(31, atLeast = true).isMet(s))
        assertTrue(Constraint.Battery(30, atLeast = false).isMet(s))
        assertTrue(Constraint.NetworkIs(NetworkRequirement.CELLULAR).isMet(s))
        assertTrue(Constraint.NetworkIs(NetworkRequirement.ONLINE).isMet(s))
        assertFalse(Constraint.NetworkIs(NetworkRequirement.WIFI).isMet(s))
        assertTrue(Constraint.NetworkIs(NetworkRequirement.OFFLINE).isMet(s.copy(networkType = "none")))
        assertTrue(Constraint.Screen(on = false).isMet(s))
    }

    @Test fun allVersusAny() {
        val met = Constraint.Charging(false)
        val unmet = Constraint.Screen(on = false)
        val s = FakeState()
        assertEquals(emptyList<Constraint>(), Macro().blockingConstraints(s))
        assertEquals(listOf(unmet), Macro(constraints = listOf(met, unmet)).blockingConstraints(s))
        assertEquals(emptyList<Constraint>(), Macro(constraints = listOf(met, unmet), constraintMatch = ConstraintMatch.ANY).blockingConstraints(s))
        assertEquals(
            listOf(unmet, unmet),
            Macro(constraints = listOf(unmet, unmet), constraintMatch = ConstraintMatch.ANY).blockingConstraints(s),
        )
    }

    @Test fun jsonRoundTripAndOldMacros() {
        val m = Macro(
            constraints = listOf(Constraint.TimeWindow(22, 0, 6, 0), Constraint.DaysOfWeek(setOf(6, 7)), Constraint.NetworkIs(NetworkRequirement.OFFLINE)),
            constraintMatch = ConstraintMatch.ANY,
        )
        assertEquals(m, AppJson.decodeFromString(Macro.serializer(), AppJson.encodeToString(Macro.serializer(), m)))
        // Macros saved before constraints existed still load, with none.
        val old = AppJson.decodeFromString(Macro.serializer(), """{"id":"x","name":"Old","trigger":{"kind":"boot"},"actions":[]}""")
        assertEquals(emptyList<Constraint>(), old.constraints)
        assertEquals(ConstraintMatch.ALL, old.constraintMatch)
    }
}
