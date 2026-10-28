package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowRowsTest {
    private fun src(key: String, kind: ShowRows.Kind) = ShowRows.Source(key, key, kind)

    @Test fun mixedIsTheDefaultAndAnUnknownModeFallsBackToIt() {
        assertEquals(ShowRows.MIXED, ShowRows.mode(null))
        assertEquals(ShowRows.MIXED, ShowRows.mode("one"))
        assertEquals(ShowRows.BY_TITLE, ShowRows.mode("title"))
        assertEquals(ShowRows.PER_CHANNEL, ShowRows.mode("stack"))
    }

    @Test fun watchNextComesFirstThenContinueWatchingThenTheRestInTheirOrder() {
        val given = listOf(
            src("plex", ShowRows.Kind.OTHER), src("stremio-cw", ShowRows.Kind.CONTINUE),
            src("watchnext", ShowRows.Kind.WATCH_NEXT), src("catalog", ShowRows.Kind.OTHER), src("other-cw", ShowRows.Kind.CONTINUE),
        )
        assertEquals(listOf("watchnext", "stremio-cw", "other-cw", "plex", "catalog"), ShowRows.ordered(given).map { it.key })
    }

    @Test fun theSavedSourceIsKeptWhileItExistsElseTheFirstOne() {
        assertEquals("b", ShowRows.current(listOf("a", "b", "c"), "b"))
        assertEquals("a", ShowRows.current(listOf("a", "b", "c"), "gone"))
        assertEquals("a", ShowRows.current(listOf("a", "b"), null))
        assertNull(ShowRows.current(emptyList(), "a"))
    }

    @Test fun byTitleGoesRoundTheList() {
        val keys = listOf("a", "b", "c")
        assertEquals("b", ShowRows.around(keys, "a", 1))
        assertEquals("a", ShowRows.around(keys, "c", 1))
        assertEquals("c", ShowRows.around(keys, "a", -1))
        assertEquals("b", ShowRows.around(keys, "c", -1))
    }

    @Test fun byTitleNeedsTwoSourcesAndAKnownOne() {
        assertNull(ShowRows.around(listOf("a"), "a", 1))
        assertNull(ShowRows.around(emptyList(), null, 1))
        assertNull(ShowRows.around(listOf("a", "b"), "gone", 1))
    }

    @Test fun perChannelStopsAtBothEnds() {
        val keys = listOf("a", "b", "c")
        assertEquals("b", ShowRows.beside(keys, "a", 1))
        assertEquals("a", ShowRows.beside(keys, "b", -1))
        assertNull(ShowRows.beside(keys, "c", 1))
        assertNull(ShowRows.beside(keys, "a", -1))
        assertNull(ShowRows.beside(keys, "gone", 1))
    }
}
