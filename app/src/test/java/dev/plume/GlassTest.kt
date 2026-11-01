package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun aFlatPictureStaysFlat() {
        val flat = IntArray(12 * 8) { px(40, 120, 200) }
        assertTrue(boxBlur(flat, 12, 8, 2).all { it == px(40, 120, 200) })
    }

    @Test fun aBrightPixelSpreadsToItsNeighboursAndDims() {
        val w = 15
        val h = 15
        val pic = IntArray(w * h) { px(0, 0, 0) }
        pic[7 * w + 7] = px(255, 255, 255)
        val out = boxBlur(pic, w, h, 2)
        val centre = out[7 * w + 7] and 0xFF
        assertTrue("the centre dims", centre in 1..254)
        assertTrue("a neighbour lights up", (out[7 * w + 8] and 0xFF) > 0)
        assertTrue("the blur is about the same both ways", Math.abs((out[7 * w + 8] and 0xFF) - (out[8 * w + 7] and 0xFF)) <= 3)
        assertTrue("far away stays black", (out[0] and 0xFF) == 0)
    }

    @Test fun theInputIsLeftAlone() {
        val pic = IntArray(9 * 9) { px(it, 0, 0) }
        val copy = pic.copyOf()
        boxBlur(pic, 9, 9, 1)
        assertTrue(pic.contentEquals(copy))
    }

    @Test fun darkModeKeepsTheColoursAndLightModeLightensThem() {
        val pic = intArrayOf(px(200, 100, 50))
        assertEquals(pic[0], glassTone(pic, dark = true)[0])
        assertEquals(px(211, 131, 91), glassTone(pic, dark = false)[0])
    }

    @Test fun aSurfaceFindsItsPlaceInTheBlur() {
        // The blur is 16:9 (320x180), so on a 1920x1080 screen it scales by 6 both ways; a view at (300, 200) shifts it back.
        val m = glassMapping(1920, 1080, 300, 200)
        assertEquals(6f, m[0], 0.001f)
        assertEquals(6f, m[1], 0.001f)
        assertEquals(-300f, m[2], 0.001f)
        assertEquals(-200f, m[3], 0.001f)
    }

    @Test fun aSixteenByNinePhotoFillsTheScreen() {
        val r = glassCrop(1920, 1080)
        assertTrue(r[0] <= 0.5f && r[1] <= 0.5f && r[2] >= GLASS_W - 0.5f && r[3] >= GLASS_H - 0.5f)
    }

    @Test fun aFourByThreePhotoIsScaledByTheWidthAndCentredAsTheWallpaperIs() {
        val r = glassCrop(1600, 1200)
        assertEquals(0f, r[0], 0.01f)
        assertEquals(GLASS_W.toFloat(), r[2], 0.01f)
        assertEquals(GLASS_H / 2f, (r[1] + r[3]) / 2, 0.01f) // centred, not pinned to the top
        assertTrue(r[1] < 0f && r[3] > GLASS_H)
    }

    @Test fun aPhonePhotoAndAPanoramaAreCentredToo() {
        for ((w, h) in listOf(1080 to 1920, 4000 to 1000)) {
            val r = glassCrop(w, h)
            assertEquals(GLASS_W / 2f, (r[0] + r[2]) / 2, 0.01f)
            assertEquals(GLASS_H / 2f, (r[1] + r[3]) / 2, 0.01f)
            assertTrue(r[0] <= 0.01f && r[1] <= 0.01f && r[2] >= GLASS_W - 0.01f && r[3] >= GLASS_H - 0.01f) // always covers
        }
    }

    @Test fun clearGlassLetsMoreOfTheWallpaperThrough() {
        val frosted = GlassStyle.FROSTED
        val clear = GlassStyle.CLEAR
        assertTrue("dark tint is lighter", (clear.washDark ushr 24) < (frosted.washDark ushr 24))
        assertTrue("light tint is lighter", (clear.washLight ushr 24) < (frosted.washLight ushr 24))
        assertTrue("tiles let more through", (clear.tileRest[0] ushr 24) < (frosted.tileRest[0] ushr 24))
        assertTrue("a lit tile lets more through", (clear.tileLit[0] ushr 24) < (frosted.tileLit[0] ushr 24))
        assertTrue("dark tiles let more through", clear.darkTileRest < frosted.darkTileRest)
        assertTrue("a lit dark tile lets more through", clear.darkTileLit < frosted.darkTileLit)
        assertTrue("less blur", clear.radius < frosted.radius)
        assertTrue("a lens and a rim make it look like glass", clear.lens > 1f && clear.rim > 0)
        assertTrue("frosted has neither", frosted.lens == 1f && frosted.rim == 0)
    }

    @Test fun onlyAPictureGetsALens() {
        assertEquals(GlassStyle.CLEAR.lens, GlassLook(GlassStyle.CLEAR, dark = true, Backdrop.PICTURE).lens, 0f)
        assertEquals(1f, GlassLook(GlassStyle.CLEAR, dark = true, Backdrop.VIDEO).lens, 0f)
        assertEquals(0, GlassLook(GlassStyle.CLEAR, dark = true, Backdrop.PLAIN).rim)
    }

    @Test fun theOldGlassSwitchStillMeansSomething() {
        assertEquals(GlassStyle.FROSTED, GlassStyle.of(null, oldOn = true))
        assertTrue(GlassStyle.of(null, oldOn = false) == null)
        assertEquals(GlassStyle.CLEAR, GlassStyle.of("clear", oldOn = true))
        assertTrue(GlassStyle.of("off", oldOn = true) == null)
    }

    @Test fun theWallpaperChoiceNamesItsBackdrop() {
        assertEquals(Backdrop.PLAIN, Backdrop.of("plain:Pure black"))
        assertEquals(Backdrop.VIDEO, Backdrop.of("video:apple_yosemite.mp4"))
        assertEquals(Backdrop.PICTURE, Backdrop.of("res:Rockies (day)"))
        assertEquals(Backdrop.PICTURE, Backdrop.of("folder:12:Photos"))
    }

    /** Every style, theme and backdrop: frost only over a picture, a tint only over a video, nothing over a plain colour. */
    @Test fun eachLookPaintsOneThing() {
        for (style in listOf(null, GlassStyle.FROSTED, GlassStyle.CLEAR)) for (dark in listOf(false, true)) for (b in Backdrop.entries) {
            val look = GlassLook(style, dark, b)
            val on = style != null && b != Backdrop.PLAIN
            assertEquals("$look active", if (on) style else null, look.active)
            assertEquals("$look blurs", on && b == Backdrop.PICTURE, look.blurs)
            assertEquals("$look tints", on && b == Backdrop.VIDEO, look.tint != 0)
        }
    }

    @Test fun aVideoTintIsHalfTheWashAndClearIsLighter() {
        val frosted = GlassLook(GlassStyle.FROSTED, dark = true, Backdrop.VIDEO).tint
        val clear = GlassLook(GlassStyle.CLEAR, dark = true, Backdrop.VIDEO).tint
        assertEquals(GlassStyle.FROSTED.washDark and 0xFFFFFF, frosted and 0xFFFFFF)
        assertEquals((GlassStyle.FROSTED.washDark ushr 24) / 2, frosted ushr 24)
        assertTrue(clear ushr 24 < frosted ushr 24)
    }
}
