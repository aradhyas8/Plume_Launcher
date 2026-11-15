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
