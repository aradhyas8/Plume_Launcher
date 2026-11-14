package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAlarmTest {
    @Test fun readsAValueOutOfTheList() {
        assertEquals("10000", HomeAlarm.value("min_interval=60000,min_futurity=10000", "min_futurity"))
    }

    @Test fun anAbsentSettingHasNoValue() {
        assertNull(HomeAlarm.value(null, "min_futurity"))
        assertNull(HomeAlarm.value("", "min_futurity"))
        assertNull(HomeAlarm.value("min_interval=60000", "min_futurity"))
    }

    @Test fun aKeyThatOnlyStartsTheSameIsNotTheKey() {
        assertNull(HomeAlarm.value("min_futurity_extra=1", "min_futurity"))
    }

    @Test fun setsTheKeyAndKeepsTheOthers() {
        assertEquals("min_interval=60000,min_futurity=0", HomeAlarm.with("min_futurity=5000,min_interval=60000", "min_futurity", "0"))
    }

    @Test fun setsTheKeyInAnEmptySetting() {
        assertEquals("min_futurity=0", HomeAlarm.with(null, "min_futurity", "0"))
        assertEquals("min_futurity=0", HomeAlarm.with("", "min_futurity", "0"))
    }

    @Test fun removesTheKeyAndKeepsTheOthers() {
        assertEquals("min_interval=60000", HomeAlarm.with("min_interval=60000,min_futurity=0", "min_futurity", null))
    }

    @Test fun removingTheOnlyKeyLeavesNothing() {
        assertNull(HomeAlarm.with("min_futurity=0", "min_futurity", null))
    }

    @Test fun putsTheOldValueBack() {
        val start = "min_interval=60000,min_futurity=3000"
        val on = HomeAlarm.with(start, "min_futurity", "0")
        assertEquals("0", HomeAlarm.value(on, "min_futurity"))
        assertEquals(start.split(',').sorted(), HomeAlarm.with(on, "min_futurity", "3000")!!.split(',').sorted())
    }
}
