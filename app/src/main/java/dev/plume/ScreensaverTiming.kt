package dev.plume

internal val SCREENSAVER_DELAYS = listOf(0, 5, 10, 15, 30, 60)

/** Save the system timeout, plus Fire OS's timeout, without leaving a partial change. Zero means Never. */
internal fun saveScreensaverDelay(minutes: Int, fireTv: Boolean,
    read: (String) -> String?, write: (String, String?) -> Boolean) {
    require(minutes in SCREENSAVER_DELAYS)
    val changes = linkedMapOf<String, String>()
    if (minutes > 0) {
        changes["screen_off_timeout"] = (minutes * 60_000).toString()
        if (fireTv) changes["contextual_screen_off_timeout"] = (minutes * 60_000).toString()
    }
    changes["screensaver_enabled"] = if (minutes == 0) "0" else "1"
    val previous = changes.keys.associateWith(read)
    try {
        changes.forEach { (key, value) -> check(write(key, value)) { "Could not write $key" } }
        check(changes.all { (key, value) -> read(key) == value }) { "TV did not save screensaver timing" }
    } catch (e: Exception) {
        previous.entries.reversed().forEach { (key, value) ->
            try {
                check(write(key, value) && read(key) == value) { "Could not restore $key" }
            } catch (restore: Exception) { e.addSuppressed(restore) }
        }
        throw e
    }
}
