package dev.plume

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/**
 * Android only lets an app read other apps' media sessions (what Cue shows) while the user has given it
 * notification access, and that access is given to a listener like this one. It hears every notification
 * anyway, so it hands them to [CueAlerts], which decides which few Cue shows.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() = CueAlerts.connected(this, this)

    override fun onListenerDisconnected() = CueAlerts.disconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) =
        CueAlerts.posted(this, sbn, rankingMap, currentInterruptionFilter)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = CueAlerts.removed(sbn.key)
}

/** Which session Cue shows: the first one playing, else the first with a title (a paused song). */
internal fun <T> cuePick(sessions: List<T>, playing: (T) -> Boolean, titled: (T) -> Boolean): T? =
    sessions.firstOrNull { playing(it) && titled(it) } ?: sessions.firstOrNull(titled)

/** Where the track is now: the session reports a [position] at [updatedAt]; playing, it has moved on since at [speed]. */
internal fun cuePosition(position: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean, length: Long): Long =
    (position + if (playing && updatedAt > 0) ((now - updatedAt) * speed).toLong() else 0L).coerceIn(0L, length)

/** "3:07" */
internal fun cueTime(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Cue steps aside when its track has been paused for [idleMs] ([pausedAt] 0: playing); never while it is selected. */
internal fun cueIdle(pausedAt: Long, now: Long, idleMs: Long, selected: Boolean) = !selected && pausedAt > 0 && now - pausedAt >= idleMs
