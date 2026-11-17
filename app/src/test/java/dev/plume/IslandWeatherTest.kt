package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandWeatherTest {
    private val t0 = 1_794_768_300_000L
    private val min = 60_000L

    // Quarter-hour slots from t0 with the given millimetres; snow in every slot when [snow].
    private fun precip(now: Long, vararg mm: Double, snow: Boolean = false) =
        wxPrecip(List(mm.size) { t0 + it * 15 * min }, mm.toList(), List(mm.size) { if (snow) 1.0 else 0.0 }, now)

    @Test fun rainStartsSoon() {
        val e = precip(t0 + 5 * min, 0.0, 0.0, 0.8, 0.3, 0.0)!!
        assertEquals(false, e.stops)
        assertEquals(t0 + 30 * min, e.at)
        assertEquals(25, wxMinutes(e.at, t0 + 5 * min))
        assertEquals(1, e.level)
        assertEquals(t0 + 60 * min, e.endsAt)
        assertEquals(false, e.snow)
    }

    @Test fun rainPastTheHourIsNotSoon() = assertNull(precip(t0 + 5 * min, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0))
    @Test fun rainExactlyAnHourAwayCounts() = assertEquals(t0 + 75 * min, precip(t0 + 15 * min, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)!!.at)
    @Test fun rainWithoutEndHasNoEnd() = assertNull(precip(t0, 0.0, 3.0, 3.0)!!.endsAt)
    @Test fun tracesAreDry() = assertNull(precip(t0, 0.0, 0.05, 0.02))
    @Test fun snowStartsSoon() = assertEquals(true, precip(t0, 0.0, 0.2, snow = true)!!.snow)

    @Test fun rainStopsSoon() {
        val e = precip(t0 + 1 * min, 2.5, 1.0, 0.0)!!
        assertEquals(true, e.stops)
        assertEquals(t0 + 30 * min, e.at)
        assertEquals(2, e.level)
    }

    @Test fun rainGoingOnIsNothing() = assertNull(precip(t0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0))
    @Test fun dryStaysDry() = assertNull(precip(t0, 0.0, 0.0, 0.0))
    @Test fun oldForecastSaysNothing() = assertNull(precip(t0 + 45 * min, 0.0, 0.0, 1.0))
    @Test fun beforeTheForecastSaysNothing() = assertNull(precip(t0 - 1, 0.0, 1.0))

    @Test fun levels() = assertEquals(listOf(0, 1, 1, 2), listOf(0.3, 0.5, 1.9, 2.0).map { wxLevel(it) })
    @Test fun minutesRoundUp() = assertEquals(listOf(1, 1, 2), listOf(1L, min, min + 1).map { wxMinutes(t0 + it, t0) })
}
