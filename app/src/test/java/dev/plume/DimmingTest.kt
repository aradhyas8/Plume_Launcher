package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimmingTest {
    @Test fun meanBrightnessOfBlackWhiteAndAMix() {
        assertEquals(0f, Dimming.meanLuma(IntArray(4) { 0xFF000000.toInt() }), 0.001f)
        assertEquals(1f, Dimming.meanLuma(IntArray(4) { 0xFFFFFFFF.toInt() }), 0.001f)
        assertEquals(0.5f, Dimming.meanLuma(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())), 0.001f)
        assertEquals(0f, Dimming.meanLuma(IntArray(0)), 0f)
    }

    @Test fun greenWeighsMoreThanBlue() {
        assertTrue(Dimming.meanLuma(intArrayOf(0xFF00FF00.toInt())) > Dimming.meanLuma(intArrayOf(0xFF0000FF.toInt())))
    }

    @Test fun aBrightPictureGetsBlackAndADarkOneGetsNone() {
        assertEquals(0, Dimming.autoDim(0.3f)) // already at the target
        assertEquals(0, Dimming.autoDim(0.1f))
        assertEquals(0, Dimming.autoDim(0f)) // black stays black
        assertEquals(153, Dimming.autoDim(1f)) // never more than 60 %
        assertTrue(Dimming.autoDim(0.6f) > Dimming.autoDim(0.5f)) // brighter, more black
    }

    @Test fun aVeryDarkPictureIsLiftedAndOthersAreNot() {
        assertEquals(0, Dimming.autoLift(0.22f))
        assertEquals(0, Dimming.autoLift(0.4f))
        assertEquals(51, Dimming.autoLift(0.0f)) // the most: 20 % grey
        assertTrue(Dimming.autoLift(0.1f) in 1..50)
        assertTrue(Dimming.autoLift(0.05f) > Dimming.autoLift(0.1f)) // darker, more lift
        for (m in listOf(0f, 0.05f, 0.1f, 0.3f, 0.6f, 1f)) assertTrue(Dimming.autoDim(m) == 0 || Dimming.autoLift(m) == 0) // never both
    }
}
