package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

// A row with 100 px tiles, 20 px left padding and room for 4 tiles (400 px).
class SnapScrollTest {
    private fun delta(tile: Int, at: Int) = SnapScrollView.snapDelta(20 + tile * 100, 20 + tile * 100 + 100, at, 20, 400)

    @Test
    fun visibleTileStays() = assertEquals(0, delta(tile = 3, at = 0))

    @Test
    fun nextTileLandsOnRightEdge() = assertEquals(100, delta(tile = 4, at = 0))

    @Test
    fun earlierTileLandsOnLeftEdge() = assertEquals(-100, delta(tile = 1, at = 200))

    // A held key: the glide is still at 30 px on its way to 100. Measured from the glide's end, the
    // next step is one tile more, not two.
    @Test
    fun heldKeyStepsFromGlideEnd() {
        assertEquals(100, delta(tile = 5, at = 100))
        assertEquals(170, delta(tile = 5, at = 30))
    }
}
