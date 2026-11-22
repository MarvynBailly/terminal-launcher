package com.terminallauncher.shell

import java.util.Calendar

/** [MARK] is inverted text, used for today in `cal`. */
enum class Style { NORMAL, DIM, ACCENT, APP, OK, ERR, MARK }

/**
 * A run of text. If [run] is set, tapping the span runs that command. If [fill] is set, tapping it
 * puts that text at the prompt without running it.
 */
data class Span(val text: String, val style: Style = Style.NORMAL, val run: String? = null, val fill: String? = null)

/** How a line of text arrives on screen: printed left to right, all at once, or with a shake. */
enum class Entrance { TYPE, NONE, SHAKE }

sealed interface Line

data class TextLine(val spans: List<Span>, val entrance: Entrance = Entrance.TYPE) : Line

/** App names laid out in columns, the way `ls` prints them. Each one launches on tap. */
data class AppGrid(val names: List<String>) : Line

/** The lock screen's clock, date and summary. The UI draws it live. */
object LockHeader : Line

/** Shown while the lock screen refuses input after repeated wrong passwords. */
data class Lockout(val until: Long) : Line

/** A labelled bar filled to [percent], with [note] after it. The login summary prints three. */
data class Gauge(val label: String, val percent: Int, val note: String) : Line

/** The `fetch` card. The UI draws a logo beside [rows]. */
data class Fetch(val rows: List<TextLine>) : Line

/** An app on its way to the front. The UI spins, then grows this line until it fills the screen. */
data class Launching(val name: String) : Line

/** A notification that arrived while the terminal was on screen. */
data class Note(val app: String, val text: String, val at: Long) : Line

/** The `np` card: [track] as it was when the command ran. The UI keeps it current while music plays. */
data class NowPlaying(val track: Track) : Line

/** With [REMOTE] what is typed goes to another computer, and the prompt is whatever that computer last wrote. */
enum class PromptKind { LOGIN, SHELL, ASK, REMOTE }

data class Prompt(val text: String, val kind: PromptKind, val mask: Boolean = false)

/** [name] is what the user types and sees. [id] is an opaque handle only the [Phone] understands. */
data class AppEntry(val name: String, val category: String, val id: String)

data class Contact(val name: String, val number: String)

data class Notif(val app: String, val title: String, val text: String, val ageMinutes: Long)

data class Battery(val percent: Int, val charging: Boolean)

/** One line of the `setup` checklist: a piece of phone access Android makes the user grant by hand. */
data class SetupItem(val key: String, val label: String, val ok: Boolean)

/** What `fetch` and the boot log print about the phone. Disk sizes are in decimal gigabytes. */
data class SystemInfo(
    val os: String,
    val device: String,
    val display: String,
    val uptimeMinutes: Long,
    val diskUsedGb: Long,
    val diskTotalGb: Long,
)

/** What the phone's media player is on. [durationMs] is 0 when the player does not say. */
data class Track(val title: String, val artist: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

enum class MediaKey { PREVIOUS, PLAY, PAUSE, NEXT }

enum class Panel { WIFI, BLUETOOTH }

/** A [KEY] chip is pressed, not typed: it goes to [Shell.press] and leaves the input alone. */
enum class ChipKind { CMD, APP, ARG, KEY }

data class Candidate(val text: String, val kind: ChipKind)

/** Completions for the word that starts at [start] in the input and currently reads [prefix]. */
data class Candidates(val start: Int, val prefix: String, val items: List<Candidate>)

/** The input after choosing a completion, and whether it should run straight away. */
data class Pick(val text: String, val submit: Boolean)

/**
 * A one-off effect for the whole screen. The shell raises it and the UI plays it once:
 * the screen opening from a line, collapsing to a dot, the lock screen dissolving after a right
 * password, or a screensaver starting now.
 */
enum class Cue { POWER_ON, POWER_OFF, GRANTED, SAVER }

/** How long an app launch waits for its transition before the app is started. */
const val LAUNCH_MS = 420L

object Fmt {
    private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private fun cal(t: Long): Calendar = Calendar.getInstance().apply { timeInMillis = t }
    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun hhmm(t: Long): String = cal(t).let { two(it.get(Calendar.HOUR_OF_DAY)) + ":" + two(it.get(Calendar.MINUTE)) }

    fun day(t: Long): String = cal(t).let {
        "${DAYS[it.get(Calendar.DAY_OF_WEEK) - 1]} ${it.get(Calendar.DAY_OF_MONTH)} ${MONTHS[it.get(Calendar.MONTH)]}"
    }

    fun weekday(t: Long): String = DAYS[cal(t).get(Calendar.DAY_OF_WEEK) - 1]

    fun full(t: Long): String = cal(t).let { "${day(t)} ${it.get(Calendar.YEAR)} ${hhmm(t)}:${two(it.get(Calendar.SECOND))}" }

    fun age(minutes: Long): String = when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }

    /** A length of time in its two largest units: 3d 4h, 4h 12m, 12m. */
    fun span(minutes: Long): String = when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (60 * 24)}d ${minutes / 60 % 24}h"
    }

    /** Track time: 2:11, or 1:02:11 past the hour. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}:${two((s / 60 % 60).toInt())}:${two((s % 60).toInt())}"
        else "${s / 60}:${two((s % 60).toInt())}"
    }

    fun bar(percent: Int): String {
        val filled = ((percent + 5) / 10).coerceIn(0, 10)
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]"
    }

    /** How much of the day has gone, 0 to 100. */
    fun dayPercent(t: Long): Int = cal(t).let { (it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)) * 100 / 1440 }

    /** How far through the week today is, counting today: the last day of the week is 100. */
    fun weekPercent(t: Long): Int = cal(t).let {
        ((it.get(Calendar.DAY_OF_WEEK) - it.firstDayOfWeek + 7) % 7 + 1) * 100 / 7
    }

    fun yearPercent(t: Long): Int = cal(t).let {
        (it.get(Calendar.DAY_OF_YEAR) * 100 / it.getActualMaximum(Calendar.DAY_OF_YEAR)).coerceIn(0, 100)
    }
}
