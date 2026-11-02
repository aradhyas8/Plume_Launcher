package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class InkTest {
    private val dark = 0xFF111214.toInt()
    private val white = -1 // Color.WHITE

    @Test
    fun brightPhotoGetsDarkText() = assertEquals(dark, inkFor(listOf(FloatArray(10) { 0.8f })))

    @Test
    fun darkPhotoGetsWhiteText() = assertEquals(white, inkFor(listOf(FloatArray(10) { 0.05f })))

    @Test
    fun aBusyPatternGetsWhiteNotTheAverage() = // dark threads and pale straw: the average is mid-tone, dark text would sink
        assertEquals(white, inkFor(listOf(FloatArray(10) { if (it % 2 == 0) 0.03f else 0.6f })))

    @Test
    fun aVideoIsJudgedByItsWorstFrame() { // bright scenes want dark text, one night scene makes it white
        assertEquals(dark, inkFor(listOf(FloatArray(10) { 0.8f }, FloatArray(10) { 0.7f })))
        assertEquals(white, inkFor(listOf(FloatArray(10) { 0.8f }, FloatArray(10) { 0.02f })))
    }
}
