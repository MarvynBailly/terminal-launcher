package com.terminallauncher.shell

import java.util.Calendar

enum class Style { NORMAL, DIM, ACCENT, APP, OK, ERR }

/** A run of text. If [run] is set, tapping the span runs that command. */
data class Span(val text: String, val style: Style = Style.NORMAL, val run: String? = null)

sealed interface Line

data class TextLine(val spans: List<Span>) : Line

/** App names laid out in columns, the way `ls` prints them. Each one launches on tap. */
data class AppGrid(val names: List<String>) : Line

/** The lock screen's clock, date and summary. The UI draws it live. */
object LockHeader : Line

/** Shown while the lock screen refuses input after repeated wrong passwords. */
data class Lockout(val until: Long) : Line

enum class PromptKind { LOGIN, SHELL, ASK }

data class Prompt(val text: String, val kind: PromptKind, val mask: Boolean = false)

/** [name] is what the user types and sees. [id] is an opaque handle only the [Phone] understands. */
data class AppEntry(val name: String, val category: String, val id: String)

data class Contact(val name: String, val number: String)

data class Notif(val app: String, val title: String, val text: String, val ageMinutes: Long)

data class Battery(val percent: Int, val charging: Boolean)

/** One line of the `setup` checklist: a piece of phone access Android makes the user grant by hand. */
data class SetupItem(val key: String, val label: String, val ok: Boolean)

enum class Panel { WIFI, BLUETOOTH }

enum class ChipKind { CMD, APP, ARG }

data class Candidate(val text: String, val kind: ChipKind)

/** Completions for the word that starts at [start] in the input and currently reads [prefix]. */
data class Candidates(val start: Int, val prefix: String, val items: List<Candidate>)

/** The input after choosing a completion, and whether it should run straight away. */
data class Pick(val text: String, val submit: Boolean)

object Fmt {
    private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun cal(t: Long): Calendar = Calendar.getInstance().apply { timeInMillis = t }
    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun hhmm(t: Long): String = cal(t).let { two(it.get(Calendar.HOUR_OF_DAY)) + ":" + two(it.get(Calendar.MINUTE)) }

    fun day(t: Long): String = cal(t).let {
        "${DAYS[it.get(Calendar.DAY_OF_WEEK) - 1]} ${it.get(Calendar.DAY_OF_MONTH)} ${MONTHS[it.get(Calendar.MONTH)]}"
    }

    fun full(t: Long): String = cal(t).let { "${day(t)} ${it.get(Calendar.YEAR)} ${hhmm(t)}:${two(it.get(Calendar.SECOND))}" }

    fun age(minutes: Long): String = when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }

    fun bar(percent: Int): String {
        val filled = ((percent + 5) / 10).coerceIn(0, 10)
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]"
    }
}
