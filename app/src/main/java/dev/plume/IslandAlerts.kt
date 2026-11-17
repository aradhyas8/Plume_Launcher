package dev.plume

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Other apps' notifications as island alerts. [MediaListener] hands every posted and removed notification here; this
 * keeps one item per notification key. A TV is shared and most of what it posts is system noise, so the filter is
 * strict, and the privacy setting decides how much of a notification the room gets to read.
 */
internal object IslandAlerts {
    private const val PEEK_MS = 6_000L
    private const val LAST_MS = 120_000L

    internal class CachedAlert(val pkg: String, val privacy: String, val item: IslandItem) {
        fun visible(now: Long, currentPrivacy: String?, muted: Set<String>) =
            now < item.until && privacy == currentPrivacy && pkg !in muted
    }

    private val shown = LinkedHashMap<String, CachedAlert>()
    private val lastPeek = HashMap<String, Long>()

    @Synchronized fun items(now: Long, prefs: SharedPreferences): List<IslandItem> {
        val privacy = prefs.getString("isl_alert_privacy", "app")
        val muted = prefs.getStringSet("isl_alert_off", null).orEmpty()
        if (!prefs.getBoolean("isl_alerts", true)) shown.clear()
        shown.values.removeAll { !it.visible(now, privacy, muted) }
        return shown.values.map { it.item }
    }

    /** Everything already posted when the listener connects, so a call that rings before Plume starts still shows. */
    fun connected(ctx: Context, service: NotificationListenerService) {
        val all = try { service.activeNotifications } catch (_: Exception) { null } ?: return
        val ranking = try { service.currentRanking } catch (_: Exception) { null }
        for (sbn in all) posted(ctx, sbn, ranking, service.currentInterruptionFilter)
    }

    /** Access was taken away or the listener stopped: nothing it showed can be trusted to go away by itself. */
    @Synchronized fun disconnected() {
        if (shown.isEmpty()) return
        shown.clear()
        Island.changed()
    }

    fun posted(ctx: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?, interruption: Int) {
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val importance = ranking?.let { map ->
            NotificationListenerService.Ranking().takeIf { map.getRanking(sbn.key, it) }?.importance
        }
        val pass = prefs.getBoolean("isl_alerts", true) && islandAlertPasses(
            pkg = pkg,
            summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = ex?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true,
            ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            category = n.category,
            hasText = title.isNotEmpty() || text.isNotEmpty(),
            importance = importance,
            // UNKNOWN (0) is what a listener reads before it is fully connected; treat it as no Do Not Disturb.
            dndAll = interruption == NotificationListenerService.INTERRUPTION_FILTER_ALL ||
                interruption == NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN,
        )
        if (!pass) return removed(sbn.key)

        // Every app that got this far is one the settings screen can list, so it can be switched off there.
        val seen = prefs.getStringSet("isl_alert_seen", null).orEmpty()
        if (pkg !in seen) prefs.edit().putStringSet("isl_alert_seen", seen + pkg).apply()
        if (pkg in prefs.getStringSet("isl_alert_off", null).orEmpty()) return removed(sbn.key)

        val call = n.category == Notification.CATEGORY_CALL
        val privacy = prefs.getString("isl_alert_privacy", "app") ?: "app"
        // Android 11+ hides most apps from Plume, but each notification carries its app's info: name and icon come
        // from there first, so an alert never shows a bare package name.
        val info = noteApp(ex)
        val label = info?.let { ctx.packageManager.getApplicationLabel(it).toString() } ?: appLabel(ctx, pkg) ?: pkg
        val (shownTitle, sub) = islandAlertText(privacy, call, label, title, text,
            ctx.getString(R.string.isl_alert_call), ctx.getString(R.string.isl_alert_new)) ?: return removed(sbn.key)

        val now = System.currentTimeMillis()
        val post = sbn.postTime.takeIf { it > 0 } ?: now
        val full = privacy == "full"
        val picture = if (full) bigPicture(ex) ?: largeIcon(ctx, n) else null
        val image = picture ?: appIcon(ctx, info, pkg)
        // Reply actions need a keyboard the island does not have, so an alert keeps only the plain buttons.
        val actions = (n.actions?.takeIf { full } ?: emptyArray())
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() && (call || it.remoteInputs.isNullOrEmpty()) }
            .take(2)
            .map { a -> IslandAction(label = a.title.toString()) { try { a.actionIntent.send() } catch (_: PendingIntent.CanceledException) {} } }

        synchronized(this) {
            val peekUntil = when {
                call -> Long.MAX_VALUE // a ringing call stays open until it is answered or missed
                islandAlertMayPeek(lastPeek[pkg], now) -> { lastPeek[pkg] = now; post + PEEK_MS }
                else -> shown[sbn.key]?.item?.peekUntil ?: 0L // too soon after the last peek: update quietly
            }
            shown[sbn.key] = CachedAlert(pkg, privacy, IslandItem(
                key = sbn.key,
                kind = if (call) IslandKind.CALL else IslandKind.ALERT,
                title = shownTitle,
                sub = sub,
                mono = image == null,
                image = image,
                since = post,
                until = if (call) Long.MAX_VALUE else post + LAST_MS,
                peekUntil = peekUntil,
                actions = actions,
                description = "$label: ${if (shownTitle == label) sub else shownTitle}",
            ))
        }
        Island.changed()
    }

    @Synchronized fun removed(key: String) {
        if (shown.remove(key) != null) Island.changed()
    }

    @Suppress("DEPRECATION")
    private fun bigPicture(ex: android.os.Bundle?): Bitmap? = try { ex?.getParcelable<android.os.Parcelable>(Notification.EXTRA_PICTURE) as? Bitmap } catch (_: Exception) { null }

    private fun largeIcon(ctx: Context, n: Notification): Bitmap? = try { n.getLargeIcon()?.loadDrawable(ctx)?.let(::toBitmap) } catch (_: Exception) { null }

    private fun appIcon(ctx: Context, info: android.content.pm.ApplicationInfo?, pkg: String): Bitmap? = try {
        toBitmap(if (info != null) ctx.packageManager.getApplicationIcon(info) else ctx.packageManager.getApplicationIcon(pkg))
    } catch (_: Exception) { null }

    /** The posting app's info, which Android puts in every notification's extras ("android.appInfo"). */
    @Suppress("DEPRECATION")
    private fun noteApp(ex: android.os.Bundle?): android.content.pm.ApplicationInfo? =
        try { ex?.getParcelable<android.os.Parcelable>("android.appInfo") as? android.content.pm.ApplicationInfo } catch (_: Exception) { null }

    /** Adaptive and vector icons have no bitmap of their own, so they are drawn once at a size the island can use. */
    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 192
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(b))
        }
    }
}

/** Packages whose notifications on a TV are system chatter, never something the room wants to see. */
private val ALERT_NOISE = setOf(
    "android", "dev.plume", "com.android.systemui", "com.google.android.gms", "com.android.vending",
    "com.google.android.tv.remote.service", "com.android.tv.settings",
)

/**
 * Whether a notification may become an island alert. Plain values only, so the rules can be tested without Android.
 * [importance] is null when the ranking is unknown; [dndAll] is false while Do Not Disturb holds back interruptions.
 */
internal fun islandAlertPasses(
    pkg: String, summary: Boolean, media: Boolean, ongoing: Boolean, category: String?,
    hasText: Boolean, importance: Int?, dndAll: Boolean,
): Boolean {
    val call = category == "call"
    return when {
        pkg in ALERT_NOISE || pkg.startsWith("com.mediatek.") || "dolby" in pkg.lowercase() -> false
        summary || media || category == "transport" -> false
        ongoing && !call -> false
        !hasText -> false
        // 3 is IMPORTANCE_DEFAULT: anything quieter is not meant to interrupt, and the island is an interruption.
        !call && importance != null && importance < 3 -> false
        !dndAll && !call && category != "alarm" -> false
        else -> true
    }
}

/**
 * The pill's title and sub for a notification under a [privacy] setting ("full", "app" or "hidden"), or null when the
 * setting hides it. Only "full" shows what the notification says; calls always show, but name the caller only in full.
 */
internal fun islandAlertText(
    privacy: String, call: Boolean, app: String, title: String, text: String, incomingCall: String, newNotification: String,
): Pair<String, String?>? = when {
    privacy == "full" -> if (title.isNotEmpty()) title to text.ifEmpty { null } else text to null
    call -> app to incomingCall
    privacy == "hidden" -> null
    else -> app to newNotification
}

/** One peek per app in ten seconds, so a chatty app cannot keep the island open. */
internal fun islandAlertMayPeek(lastPeek: Long?, now: Long) = lastPeek == null || now - lastPeek >= 10_000L
