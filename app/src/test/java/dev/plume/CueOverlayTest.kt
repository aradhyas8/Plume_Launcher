package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueOverlayTest {
    private fun item(key: String, kind: CueKind, since: Long, peekUntil: Long) =
        CueItem(key, kind, key, since = since, peekUntil = peekUntil)

    @Test fun showsOnlyAlertsCallsAndFinishedTimers() {
        val now = 1_000L
        val items = listOf(
            item("song", CueKind.MEDIA, 900, 2_000),
            item("score", CueKind.SPORTS_LIVE, 950, 2_000),
            item("door", CueKind.ALERT, 800, 2_000),
        )
        assertEquals("door", cueOverlayPick(items, now)?.key)
        assertNull(cueOverlayPick(items.take(2), now))
    }

    @Test fun newestPeekWinsAndAnEndedPeekIsGone() {
        val items = listOf(
            item("call", CueKind.CALL, 500, Long.MAX_VALUE),
            item("timer", CueKind.TIMER_DONE, 900, 1_500),
            item("old", CueKind.ALERT, 950, 999),
        )
        assertEquals("timer", cueOverlayPick(items, 1_000)?.key)
        assertEquals("call", cueOverlayPick(items, 2_000)?.key)
    }

    @Test fun anOpenAlertStaysPastItsTimeThenGoes() {
        val door = CueAlerts.CachedAlert("ring", "app", item("door", CueKind.ALERT, 1_000, 3_000).let {
            CueItem(it.key, it.kind, it.title, until = 3_000, peekUntil = 3_000)
        })
        assertEquals(false, door.visible(4_000, "app", emptySet()))
        CueAlerts.held = "door"
        assertEquals(true, door.visible(4_000, "app", emptySet()))
        CueAlerts.held = null
        assertEquals(false, door.visible(4_000, "app", emptySet()))
    }

    @Test fun oneShellLineGivesBothAccesses() {
        assertEquals(
            "cmd notification allow_listener dev.plume/dev.plume.MediaListener; appops set dev.plume SYSTEM_ALERT_WINDOW allow",
            cueGrant("dev.plume"),
        )
    }
}
