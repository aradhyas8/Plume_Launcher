package dev.plume

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets an app read other apps' media sessions (what the media island shows) while
 * the user has given it notification access, and that access is given to a listener like this one.
 */
class MediaListener : NotificationListenerService()

/** Which session the island shows: the first one playing, else the first with a title (a paused song). */
internal fun <T> islandPick(sessions: List<T>, playing: (T) -> Boolean, titled: (T) -> Boolean): T? =
    sessions.firstOrNull { playing(it) && titled(it) } ?: sessions.firstOrNull(titled)

/** Where the track is now: the session reports a [position] at [updatedAt]; playing, it has moved on since at [speed]. */
internal fun islandPosition(position: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean, length: Long): Long =
    (position + if (playing && updatedAt > 0) ((now - updatedAt) * speed).toLong() else 0L).coerceIn(0L, length)

/** "3:07" */
internal fun islandTime(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** The island steps aside when its track has been paused for [idleMs] ([pausedAt] 0: playing); never while it is selected. */
internal fun islandIdle(pausedAt: Long, now: Long, idleMs: Long, selected: Boolean) = !selected && pausedAt > 0 && now - pausedAt >= idleMs
