package com.terminallauncher.shell

/**
 * A change the phone's own log records: the screen turning on or off, Android's lock screen going
 * up or coming down, and the phone having shut down.
 */
enum class ScreenChange { ON, OFF, LOCKED, UNLOCKED, SHUTDOWN }

/**
 * One such change. [seen] is when the log last recorded anything at all, about the screen or not,
 * a second or more before it: the phone was certainly running then. After the phone died without
 * warning, that is the best there is for when. Entries closer than a second do not count, because
 * a restart writes a burst of them with one time.
 */
data class ScreenEvent(val at: Long, val change: ScreenChange, val seen: Long = at)

/**
 * What the phone's log holds from some moment up to now. [since] is how far back it reaches, which
 * can be later than was asked for: the phone keeps about a week. [on] and [locked] are how things
 * stand right now, and [seen] is the time of the last entry of any kind.
 */
data class ScreenLog(val since: Long, val events: List<ScreenEvent>, val on: Boolean, val locked: Boolean, val seen: Long = Long.MIN_VALUE)

/** Makes a [ScreenLog] out of the entries of the phone's log, taken in the order the log has them. */
class ScreenLogReader {
    private val events = ArrayList<ScreenEvent>()
    private var first = Long.MIN_VALUE
    private var latest = Long.MIN_VALUE
    private val recent = ArrayDeque<Long>()         // the times of the entries of the last second
    private var settled = Long.MIN_VALUE            // the latest entry a second or more older than the one in hand

    /**
     * One entry: [change] is what it says about the screen, or null if it is about something else.
     * A [marker] is an entry the phone writes about itself when it starts. It says nothing about
     * when the phone was last running.
     */
    fun take(at: Long, change: ScreenChange?, marker: Boolean = false) {
        if (first == Long.MIN_VALUE) first = at
        while (recent.isNotEmpty() && recent.first() <= at - RESTART_MS) settled = recent.removeFirst()
        if (change != null) events += ScreenEvent(at, change, if (settled == Long.MIN_VALUE) at else settled)
        if (marker) return
        if (recent.isEmpty() || recent.last() < at) recent.addLast(at)
        latest = maxOf(latest, at)
    }

    fun log(now: Long, on: Boolean, locked: Boolean) = ScreenLog(if (first == Long.MIN_VALUE) now else first, events, on, locked, latest)
}

/** One day's use of the phone: how long it was in use, and how many times it was picked up and unlocked. */
data class DayUse(val start: Long, val screenMs: Long, val unlocks: Int)

/** A shutdown is believed for this long after the last thing the log recorded before it. */
private const val SHUTDOWN_SLACK_MS = 60_000L

/** How close in time a restart's entries are to the note of the shutdown before it. */
private const val RESTART_MS = 1000L

/**
 * Adds a [log] up into days. [days] holds the moment each day starts, oldest first, and the last
 * day runs until [now].
 *
 * The phone is in use while its screen is on and Android's lock screen is down, so a glance at the
 * lock screen counts for nothing. An unlock is the start of such a stretch. On a phone with no lock
 * screen that is every time the screen turns on.
 *
 * The log is the phone's and it has its ways, all of them seen on real phones:
 * - It is written from memory every twenty minutes or so. A phone that dies takes the last of it
 *   along, and the next start notes a shutdown at the time the log was last written.
 * - That note can carry the time of the start that wrote it. It is then far too late, and it sorts
 *   in among the start's own entries.
 * - A phone that is switched off while in use never records its screen going dark.
 * - After a restart the screen is on a moment before the lock screen is, and everything up to the
 *   first unlock is stamped with the time of that unlock.
 *
 * Where it is unclear, this counts less rather than more. A stretch whose end was never recorded
 * ends at the last thing recorded in it, not hours later.
 */
fun tallyScreen(log: ScreenLog, days: List<Long>, now: Long): List<DayUse> {
    if (days.isEmpty()) return emptyList()
    val time = LongArray(days.size)
    val unlocks = IntArray(days.size)

    // Entries of one moment keep the order the phone wrote them in.
    val events = log.events.sortedBy { it.at }.toMutableList()
    // A shutdown happened before the start that noted it, whatever time the note carries.
    for (i in events.indices) {
        val note = events[i]
        if (note.change != ScreenChange.SHUTDOWN) continue
        var to = i
        while (to > 0 && events[to - 1].change != ScreenChange.SHUTDOWN && note.at - events[to - 1].at < RESTART_MS) {
            events[to] = events[to - 1]
            to--
        }
        events[to] = note
    }

    fun dayOf(at: Long): Int = days.indexOfLast { it <= at }

    fun spend(from: Long, to: Long) {
        for (i in days.indices) {
            val end = days.getOrNull(i + 1) ?: Long.MAX_VALUE
            val part = minOf(to, end) - maxOf(from, days[i])
            if (part > 0) time[i] += part
        }
    }

    // How things stood before the first entry is the opposite of what the first entry of each kind
    // did. With no entry of a kind, nothing changed, so it was as it is now.
    var on = when (events.firstOrNull { it.change == ScreenChange.ON || it.change == ScreenChange.OFF || it.change == ScreenChange.SHUTDOWN }?.change) {
        ScreenChange.OFF -> true
        null -> log.on
        else -> false
    }
    var locked = when (events.firstOrNull { it.change == ScreenChange.LOCKED || it.change == ScreenChange.UNLOCKED || it.change == ScreenChange.SHUTDOWN }?.change) {
        ScreenChange.UNLOCKED -> true
        null -> log.locked
        else -> false
    }

    val none = -1L
    val before = Long.MIN_VALUE                 // a stretch that was under way when the log starts
    var began = if (on && !locked) before else none
    var last = days[0]
    var starting = false                        // restarted, and not yet known whether there is a lock screen

    /** A stretch of use is over. One that began before the log was not unlocked in these days. */
    fun close(to: Long, evenIfEmpty: Boolean = false) {
        val from = began
        began = none
        if (from == before) {
            spend(days[0], to)
            return
        }
        // The log can open and shut a stretch in the same instant. That is no unlock.
        if (to <= from && !evenIfEmpty) return
        spend(from, to)
        val day = dayOf(from)
        if (day >= 0) unlocks[day]++
    }

    for (event in events) {
        var at = event.at.coerceAtMost(now)
        when (event.change) {
            ScreenChange.ON -> {
                // On twice with no off between: the phone went down and nothing recorded it. What
                // was under way ended by the last thing the log did record.
                if (on && began != none) close(minOf(maxOf(last, event.seen), at))
                on = true
            }
            ScreenChange.OFF -> {
                on = false
                starting = false
            }
            ScreenChange.SHUTDOWN -> {
                at = minOf(at, maxOf(last, event.seen + SHUTDOWN_SLACK_MS))
                on = false
                // The lock screen, if this phone has one, goes up again as it starts.
                locked = false
                starting = true
            }
            ScreenChange.LOCKED -> {
                // The moment between the screen and the lock screen coming up at a start is not use.
                if (starting && began != none && began != before && at - began < SHUTDOWN_SLACK_MS) began = none
                locked = true
                starting = false
            }
            ScreenChange.UNLOCKED -> {
                locked = false
                starting = false
            }
        }
        val using = on && !locked
        if (began != none && !using) close(at)
        if (began == none && using) began = at
        last = maxOf(last, at)
    }

    val usingNow = log.on && !log.locked
    when {
        began != none && usingNow -> close(now, evenIfEmpty = true)
        // The log says in use and the phone says not: whatever ended it was not recorded.
        began != none -> close(minOf(maxOf(last, log.seen), now))
        // The phone is in use and the log has not caught up with the unlock yet.
        usingNow -> unlocks[days.lastIndex]++
    }
    return days.indices.map { DayUse(days[it], time[it], unlocks[it]) }
}
