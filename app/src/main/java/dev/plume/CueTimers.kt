package dev.plume

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/*
 * The time math, kept apart from Android so the unit tests can run it. All times are wall-clock milliseconds.
 * A running countdown keeps its end time and a paused one keeps the time left, so a pause is "left = end - now"
 * and a resume is "end = now + left". A stopwatch keeps the wall time it started from (moved on by every pause)
 * while it runs, and the time it showed while it is paused.
 */

/** The time left before [end], never below zero. */
internal fun islLeft(end: Long, now: Long) = (end - now).coerceAtLeast(0L)

/** How much of [total] is gone when [left] is still to go, 0..1. An empty total counts as all gone. */
internal fun islFraction(left: Long, total: Long): Float =
    if (total <= 0L) 1f else (1f - left.toFloat() / total).coerceIn(0f, 1f)

/** A paused countdown starts again: its new end time. */
internal fun islResumeEnd(left: Long, now: Long) = now + left

/** The stopwatch's time: from [base] while it runs, the kept [shown] time while it is paused. */
internal fun islElapsed(base: Long, shown: Long, running: Boolean, now: Long) =
    if (running) (now - base).coerceAtLeast(0L) else shown

/** A paused stopwatch starts again: the base that makes it go on from [shown]. */
internal fun islResumeBase(shown: Long, now: Long) = now - shown

/**
 * Cue's timer and stopwatch: one of each at a time. The state lives in the "home" prefs with
 * wall-clock end times, so it outlives the process. While the process lives a main-thread Handler ends things on
 * time; an inexact AlarmManager alarm to [IslandAlarm] is the backup when the process is gone. Exact alarms need a
 * special permission on Android 12 and later, and a few seconds late is fine for a backup. Both paths go through
 * [check], which acts only on state that is still running and past its end, so nothing ends twice.
 *
 * Call [init] at start and every control on the main thread, because the Toasts and the Handler need it.
 */
internal object CueTimers {
    private const val T_STATE = "isl_t_state"
    private const val T_END = "isl_t_end"
    private const val T_LEFT = "isl_t_left"
    private const val T_TOTAL = "isl_t_total"
    private const val T_START = "isl_t_start"
    private const val W_STATE = "isl_w_state"
    private const val W_BASE = "isl_w_base"
    private const val W_SHOWN = "isl_w_shown"
    private const val W_START = "isl_w_start"

    private const val NONE = 0
    private const val RUNNING = 1
    private const val PAUSED = 2
    private const val DONE = 3 // the timer only

    private const val MINUTE = 60_000L
    private const val DONE_SHOWN = 2 * MINUTE
    private const val PEEK = 8_000L
    private const val ALARM_TIMER = 1

    // Lazy, because a Handler made when the object loads would also be made in unit tests that never need it.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val tick = Runnable { check() }
    @Volatile private var app: Context? = null
    // Held so the sound is not collected while it plays.
    private var ring: Ringtone? = null

    /** Loads the saved state: ends what ended while the process was gone, and sets the Handler and alarms again. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        check()
    }

    fun startTimer(ctx: Context, ms: Long) = edit(ctx) {
        val now = System.currentTimeMillis()
        putInt(T_STATE, RUNNING).putLong(T_END, now + ms).putLong(T_TOTAL, ms).putLong(T_START, now).remove(T_LEFT)
    }

    fun pauseTimer(ctx: Context) = edit(ctx) { p ->
        if (p.getInt(T_STATE, NONE) == RUNNING) {
            putInt(T_STATE, PAUSED).putLong(T_LEFT, islLeft(p.getLong(T_END, 0L), System.currentTimeMillis()))
        }
    }

    fun resumeTimer(ctx: Context) = edit(ctx) { p ->
        if (p.getInt(T_STATE, NONE) == PAUSED) {
            putInt(T_STATE, RUNNING).putLong(T_END, islResumeEnd(p.getLong(T_LEFT, 0L), System.currentTimeMillis()))
        }
    }

    /** More time on the timer. A timer that is done starts again with just [ms]. */
    fun addTimer(ctx: Context, ms: Long) {
        val p = prefs(ctx)
        when (p.getInt(T_STATE, NONE)) {
            DONE -> startTimer(ctx, ms)
            RUNNING -> edit(ctx) { putLong(T_END, p.getLong(T_END, 0L) + ms).putLong(T_TOTAL, p.getLong(T_TOTAL, 0L) + ms) }
            PAUSED -> edit(ctx) { putLong(T_LEFT, p.getLong(T_LEFT, 0L) + ms).putLong(T_TOTAL, p.getLong(T_TOTAL, 0L) + ms) }
        }
    }

    /** Stops the timer, or dismisses it when it is done. */
    fun cancelTimer(ctx: Context) = edit(ctx) { clearTimer() }

    fun startStopwatch(ctx: Context) = edit(ctx) {
        val now = System.currentTimeMillis()
        putInt(W_STATE, RUNNING).putLong(W_BASE, now).putLong(W_SHOWN, 0L).putLong(W_START, now)
    }

    fun pauseStopwatch(ctx: Context) = edit(ctx) { p ->
        if (p.getInt(W_STATE, NONE) == RUNNING) {
            putInt(W_STATE, PAUSED).putLong(W_SHOWN, islElapsed(p.getLong(W_BASE, 0L), 0L, true, System.currentTimeMillis()))
        }
    }

    fun resumeStopwatch(ctx: Context) = edit(ctx) { p ->
        if (p.getInt(W_STATE, NONE) == PAUSED) {
            putInt(W_STATE, RUNNING).putLong(W_BASE, islResumeBase(p.getLong(W_SHOWN, 0L), System.currentTimeMillis()))
        }
    }

    /** Back to 0:00, still running or still paused as it was. */
    fun zeroStopwatch(ctx: Context) = edit(ctx) { putLong(W_BASE, System.currentTimeMillis()).putLong(W_SHOWN, 0L) }

    /** Stops the stopwatch and clears it. */
    fun resetStopwatch(ctx: Context) = edit(ctx) { remove(W_STATE).remove(W_BASE).remove(W_SHOWN).remove(W_START) }

    fun items(now: Long): List<CueItem> {
        val c = app ?: return emptyList()
        val p = prefs(c)
        val out = ArrayList<CueItem>(2)
        val stop = CueAction(R.drawable.ic_isl_stop, description = c.getString(R.string.isl_timer_stop)) { cancelTimer(c) }

        when (p.getInt(T_STATE, NONE)) {
            RUNNING, PAUSED -> {
                val running = p.getInt(T_STATE, NONE) == RUNNING
                val total = p.getLong(T_TOTAL, 0L)
                val left = if (running) islLeft(p.getLong(T_END, 0L), now) else p.getLong(T_LEFT, 0L)
                val length = length(c, total)
                out += CueItem(
                    key = "timer", kind = CueKind.TIMER, title = cueClock(left),
                    sub = c.getString(if (running) R.string.isl_timer_sub else R.string.isl_timer_sub_paused, length),
                    icon = R.drawable.ms_timer, progress = islFraction(left, total), tick = if (running) 1000L else 0L,
                    since = p.getLong(T_START, 0L),
                    actions = listOf(
                        CueAction(label = c.getString(R.string.isl_timer_plus1), description = c.getString(R.string.isl_timer_add_minute)) {
                            addTimer(c, MINUTE)
                        },
                        pauseOrResume(c, running, { pauseTimer(c) }, { resumeTimer(c) }),
                        stop,
                    ),
                )
            }
            DONE -> {
                val end = p.getLong(T_END, 0L)
                out += CueItem(
                    key = "timer", kind = CueKind.TIMER_DONE, title = c.getString(R.string.isl_timer_done),
                    sub = c.getString(R.string.isl_timer_done_sub, length(c, p.getLong(T_TOTAL, 0L))),
                    icon = R.drawable.ms_timer, since = end, until = end + DONE_SHOWN, peekUntil = end + PEEK,
                    actions = listOf(
                        CueAction(R.drawable.ic_isl_stop, description = c.getString(R.string.isl_timer_dismiss)) { cancelTimer(c) },
                        CueAction(label = c.getString(R.string.isl_timer_plus1), description = c.getString(R.string.isl_timer_again)) {
                            addTimer(c, MINUTE)
                        },
                    ),
                )
            }
        }

        val watch = p.getInt(W_STATE, NONE)
        if (watch != NONE) {
            val running = watch == RUNNING
            out += CueItem(
                key = "stopwatch", kind = CueKind.STOPWATCH,
                title = cueClock(islElapsed(p.getLong(W_BASE, 0L), p.getLong(W_SHOWN, 0L), running, now), countdown = false),
                sub = c.getString(if (running) R.string.isl_timer_stopwatch else R.string.isl_timer_paused),
                icon = R.drawable.ic_isl_stopwatch, tick = if (running) 1000L else 0L, since = p.getLong(W_START, 0L),
                actions = listOf(
                    CueAction(R.drawable.ic_isl_replay, description = c.getString(R.string.isl_timer_reset)) { zeroStopwatch(c) },
                    pauseOrResume(c, running, { pauseStopwatch(c) }, { resumeStopwatch(c) }),
                    CueAction(R.drawable.ic_isl_stop, description = c.getString(R.string.isl_timer_stop)) { resetStopwatch(c) },
                ),
            )
        }

        return out
    }

    private fun pauseOrResume(c: Context, running: Boolean, pause: () -> Unit, resume: () -> Unit) =
        if (running) CueAction(R.drawable.ic_pause, description = c.getString(R.string.isl_timer_pause), run = pause)
        else CueAction(R.drawable.ic_play, description = c.getString(R.string.isl_timer_resume), run = resume)

    /** "10 min", or "1:30" for a length that is not whole minutes. */
    private fun length(c: Context, total: Long) =
        if (total > 0L && total % MINUTE == 0L) c.getString(R.string.isl_timer_minutes, total / MINUTE) else cueClock(total)

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Every control: change the state, then end, schedule and redraw from it. */
    private fun edit(ctx: Context, change: SharedPreferences.Editor.(SharedPreferences) -> Unit) {
        app = ctx.applicationContext
        val p = prefs(ctx)
        p.edit().also { it.change(p) }.apply()
        check()
        Cue.changed()
    }

    private fun SharedPreferences.Editor.clearTimer() =
        remove(T_STATE).remove(T_END).remove(T_LEFT).remove(T_TOTAL).remove(T_START)

    /**
     * Ends what is past its end, then sets the Handler for the next end and the backup alarms. The prefs change in
     * memory at once and this runs on the main thread only, so the Handler and the alarm cannot both end a timer.
     */
    private fun check() {
        val c = app ?: return
        val p = prefs(c)
        val now = System.currentTimeMillis()

        val state = p.getInt(T_STATE, NONE)
        if (state == RUNNING && p.getLong(T_END, 0L) <= now) {
            p.edit().putInt(T_STATE, DONE).apply()
            timerDone(c)
        } else if (state == DONE && p.getLong(T_END, 0L) + DONE_SHOWN <= now) {
            p.edit().clearTimer().apply()
            Cue.changed()
        }

        val timerEnd = if (p.getInt(T_STATE, NONE) == RUNNING) p.getLong(T_END, 0L) else 0L
        val doneGone = if (p.getInt(T_STATE, NONE) == DONE) p.getLong(T_END, 0L) + DONE_SHOWN else 0L
        main.removeCallbacks(tick)
        val next = listOf(timerEnd, doneGone).filter { it > 0L }.minOrNull()
        if (next != null) main.postDelayed(tick, (next - now).coerceAtLeast(0L))
        // The dismissal of a done timer needs no alarm: a new process clears it in init.
        alarm(c, ALARM_TIMER, timerEnd)
    }

    private fun timerDone(c: Context) {
        try {
            ring = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            ring?.play()
        } catch (_: RuntimeException) { // no sound set, or the player failed: Cue still shows it
        }
        // With home shown Cue tells it; otherwise only a Toast reaches the person over another app.
        if (Cue.listener == null) Toast.makeText(c, R.string.isl_timer_toast, Toast.LENGTH_LONG).show()
        Cue.changed()
    }

    /** Sets the backup alarm [code] for [at], or cancels it when [at] is 0. */
    private fun alarm(c: Context, code: Int, at: Long) {
        val send = PendingIntent.getBroadcast(
            c, code, Intent(c, IslandAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            val alarms = c.getSystemService(AlarmManager::class.java)
            if (at > 0L) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, send) else alarms.cancel(send)
        } catch (_: RuntimeException) { // too many alarms on some builds: the Handler still ends it while Plume runs
        }
    }
}

/** The backup alarm. The process can be new here, so [CueTimers.init] loads the state and ends what is due. */
// Keep the receiver name for pending alarms from earlier versions.
class IslandAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CueTimers.init(context)
        // Keep the process for a few seconds, so it is not killed while the timer's sound plays.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 5_000L)
    }
}
