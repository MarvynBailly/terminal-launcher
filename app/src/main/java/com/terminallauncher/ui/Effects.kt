package com.terminallauncher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.terminallauncher.shell.AppGrid
import com.terminallauncher.shell.Cue
import com.terminallauncher.shell.Entrance
import com.terminallauncher.shell.Launching
import com.terminallauncher.shell.Line
import com.terminallauncher.shell.LockHeader
import com.terminallauncher.shell.Lockout
import com.terminallauncher.shell.Note
import com.terminallauncher.shell.NowPlaying
import com.terminallauncher.shell.TextLine
import com.terminallauncher.ui.looks.Look
import com.terminallauncher.ui.looks.Motion
import kotlinx.coroutines.delay

/* ---------------- printing lines one after another ---------------- */

/** The gap between two printed lines. A long output shrinks it to finish within [REVEAL_BUDGET_MS]. */
internal const val LINE_MS = 55
internal const val REVEAL_BUDGET_MS = 350

/** How long one line takes to print from left to right, and how many steps it takes. */
internal const val TYPE_MS = 180
internal const val TYPE_STEPS = 14
internal const val SHAKE_MS = 360

/** No entrance at all: on screen the moment the shell adds it. */
internal fun isInstant(line: Line): Boolean =
    (line is TextLine && line.entrance == Entrance.NONE) || line === LockHeader || line is Lockout || line is NowPlaying

/** An entrance of its own that holds nothing else up. */
private fun isQuick(line: Line): Boolean = line is AppGrid || line is Note || line is Launching

/** Printed in turn. The prompt waits until these are out. */
internal fun isPaced(line: Line): Boolean = !isInstant(line) && !isQuick(line)

/**
 * Puts new shell lines on screen one at a time, the way a slow terminal prints. It remembers how far
 * it has got as a line id, and when each line went up, so a line's own entrance can pick up from the
 * right moment even if its row is composed late or scrolled away and back.
 */
internal class Typist(start: Long) {
    /** Lines with an id below this are on screen. */
    var shown by mutableLongStateOf(start)

    /** When each line this typist revealed went on screen, in uptime milliseconds. */
    private val born = HashMap<Long, Long>()

    /** null for a line that was already on screen when the terminal was drawn: it has no entrance to play. */
    fun bornAt(id: Long): Long? = born[id]

    /** The id just past the run of instant lines at the front of what is still waiting. */
    fun frontier(lines: List<Line>, ids: List<Long>): Long {
        var at = shown
        var i = ids.indexOfFirst { it >= at }
        while (i in ids.indices && isInstant(lines[i])) {
            at = ids[i] + 1
            i++
        }
        return at
    }

    suspend fun print(lines: List<Line>, ids: List<Long>) {
        born.keys.retainAll(ids.toSet())
        var i = ids.indexOfFirst { it >= shown }
        if (i < 0) return
        val paced = (i until ids.size).count { isPaced(lines[it]) }
        val step = if (paced == 0) 0 else (REVEAL_BUDGET_MS / paced).coerceIn(3, LINE_MS)
        while (i < ids.size) {
            val line = lines[i]
            born[ids[i]] = SystemClock.uptimeMillis()
            shown = ids[i] + 1
            val pause = when {
                line is TextLine && line.entrance == Entrance.SHAKE -> SHAKE_MS + 80     // the fix comes after the shake
                isPaced(line) -> step
                else -> 0
            }
            if (pause > 0) delay(pause.toLong())
            i++
        }
    }

    fun skipTo(ids: List<Long>) {
        shown = (ids.lastOrNull() ?: (shown - 1)) + 1
    }
}

/**
 * Milliseconds since [bornAt], running up to [totalMs] and stopping there. A row with no birth time
 * starts at the end, so nothing replays for lines that were already on screen.
 */
@Composable
internal fun elapsedSince(bornAt: Long?, totalMs: Int, fixed: Float? = null): Animatable<Float, AnimationVector1D> {
    // A picture taken off the phone holds every entrance at one moment: `fixed` of the way through.
    if (fixed != null) return remember(fixed, totalMs) { Animatable(fixed.coerceIn(0f, 1f) * totalMs) }
    val anim = remember {
        val start = if (bornAt == null) totalMs.toFloat() else (SystemClock.uptimeMillis() - bornAt).toFloat()
        Animatable(start.coerceIn(0f, totalMs.toFloat()))
    }
    LaunchedEffect(Unit) {
        val left = totalMs - anim.value
        if (left > 0f) anim.animateTo(totalMs.toFloat(), tween(left.toInt(), easing = LinearEasing))
    }
    return anim
}

/** The sideways offset, in dp, of a line [p] of the way through its shake. */
internal fun shakeAt(p: Float): Float {
    val stops = floatArrayOf(0f, 0.15f, 0.35f, 0.55f, 0.75f, 1f)
    val offsets = floatArrayOf(0f, -7f, 6f, -4f, 3f, 0f)
    if (p <= 0f || p >= 1f) return 0f
    var i = 1
    while (stops[i] < p) i++
    val span = (p - stops[i - 1]) / (stops[i] - stops[i - 1])
    return offsets[i - 1] + (offsets[i] - offsets[i - 1]) * span
}

/* ---------------- whole-screen scenes ---------------- */

/** The screen stays dark for this long between going off and coming back on. */
private const val POWER_GAP_MS = 120

internal const val GRANTED_TEXT = "access granted"

/**
 * One playing [Cue]. [before] is the page as it was just before the cue: the home screen a lock
 * collapses, or the lock screen a right password takes away. How it moves is the [look]'s business.
 * With [fixedMs] it stands still at that moment, for a picture.
 */
internal class Scene(val cue: Cue, val before: List<Row>, private val look: Look, fixedMs: Float? = null) {
    private val on = look.powerOn
    private val off = look.powerOff

    val totalMs: Int
        get() = when (cue) {
            Cue.POWER_ON -> on.ms
            Cue.POWER_OFF -> off.ms + POWER_GAP_MS + on.ms
            else -> look.granted.ms
        }

    private val releaseMs: Int
        get() = when (cue) {
            Cue.POWER_ON -> on.readableMs
            Cue.POWER_OFF -> off.ms + POWER_GAP_MS + on.readableMs
            else -> look.granted.ms
        }

    /** Milliseconds since the scene started. */
    val time = Animatable(fixedMs ?: 0f)

    /** The page that follows may start printing. Until then the page from before is what shows. */
    var released by mutableStateOf(fixedMs != null && fixedMs >= releaseMs)
    var done by mutableStateOf(false)

    val bendsScreen: Boolean get() = cue == Cue.POWER_ON || cue == Cue.POWER_OFF

    /** Whether the page from before the cue is drawn in place of the live one. */
    val showsBefore: Boolean get() = !released && (cue == Cue.POWER_OFF || cue == Cue.GRANTED)

    /** For a scene that moves the whole screen: which movement it is in right now. */
    fun motion(): Motion = if (cue == Cue.POWER_OFF && time.value < off.ms + POWER_GAP_MS) off else on

    /** And how far into that movement. Between going off and coming on, the screen stays as dark as it got. */
    fun motionTime(): Float = when {
        cue != Cue.POWER_OFF -> time.value
        time.value < off.ms -> time.value
        time.value < off.ms + POWER_GAP_MS -> off.ms.toFloat()
        else -> time.value - off.ms - POWER_GAP_MS
    }

    fun finish() {
        released = true
        done = true
    }

    /** Runs the scene's clock to the end. Everything about the scene is read off that one clock. */
    suspend fun play() {
        try {
            time.animateTo(totalMs.toFloat(), tween(totalMs, easing = LinearEasing)) {
                if (value >= releaseMs) released = true
            }
        } finally {
            // However it ends, cut short included, the live page must come back.
            released = true
            done = true
        }
    }
}

/** "access granted" resolving out of noise, [ms] into the scene. */
internal fun scramble(ms: Float, seed: Int): String {
    val glyphs = "!<>-_\\/[]{}=+*^?#"
    val frame = (ms / 22f).toInt()
    if (frame >= GRANTED_TEXT.length + 5) return GRANTED_TEXT
    val random = java.util.Random((seed * 31 + frame).toLong())
    return buildString {
        GRANTED_TEXT.forEachIndexed { i, ch ->
            append(
                when {
                    i < frame - 3 || ch == ' ' -> ch
                    i < frame + 3 -> glyphs[random.nextInt(glyphs.length)]
                    else -> ' '
                },
            )
        }
    }
}

/* ---------------- launching an app ---------------- */

/** A launch in flight: the line it grows from, in root pixels, and when it started in uptime ms. */
internal class LaunchFx(val name: String, val top: Float, val height: Float, val bornAt: Long)


/** If the app has not covered the terminal by now, it is not coming: put the terminal back. */
internal const val LAUNCH_GIVE_UP_MS = 2500
