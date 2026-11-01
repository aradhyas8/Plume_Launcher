package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadesTest {
    private val h = 1080

    @Test
    fun topVeilIsStrongestAtTheTopAndGoneByItsEnd() {
        val top = Fades.topAlpha(0, h)
        assertTrue("$top", top > 130 && top <= 141)
        assertEquals(0, Fades.topAlpha(h * 28 / 100 + 1, h))
    }

    @Test
    fun topVeilOnlyGetsLighterDownThePicture() {
        var last = Fades.topAlpha(0, h)
        for (y in 1 until h) {
            val a = Fades.topAlpha(y, h)
            assertTrue("y=$y: $a > $last", a <= last)
            last = a
        }
    }

    @Test
    fun bottomFadeLeavesTheTopAndEndsSolid() {
        assertEquals(0, Fades.bottomAlpha(0, h))
        assertEquals(0, Fades.bottomAlpha(h * 39 / 100, h))
        assertEquals(255, Fades.bottomAlpha(h * 87 / 100, h))
        assertEquals(255, Fades.bottomAlpha(h - 1, h))
    }

    @Test
    fun bottomFadeOnlyGetsDarkerDownThePicture() {
        var last = 0
        for (y in 0 until h) {
            val a = Fades.bottomAlpha(y, h)
            assertTrue("y=$y: $a < $last", a >= last)
            last = a
        }
    }

    @Test
    fun bothFadesAreClearInTheMiddle() {
        for (y in h * 30 / 100 until h * 40 / 100) {
            assertEquals(0, Fades.topAlpha(y, h))
            assertEquals(0, Fades.bottomAlpha(y, h))
        }
    }

    @Test
    fun smallThumbnailsStayInRange() {
        for (y in 0 until 68) {
            val top = Fades.topAlpha(y, 68)
            val bottom = Fades.bottomAlpha(y, 68)
            assertTrue("y=$y top=$top", top in 0..255)
            assertTrue("y=$y bottom=$bottom", bottom in 0..255)
        }
    }
}
