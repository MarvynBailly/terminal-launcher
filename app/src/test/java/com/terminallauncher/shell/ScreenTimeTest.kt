package com.terminallauncher.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The entries here are in the shapes a real phone wrote them: Android 16's log was read while it
 * was locked, unlocked, glanced at, restarted and switched off without warning.
 */
class ScreenTimeTest {
    private val midnight = 1_794_960_000_000L       // Wednesday 18 November 2026, 00:00 UTC
    private val minute = 60_000L

    private fun at(hour: Int, min: Int = 0, sec: Int = 0, day: Int = 0): Long =
        midnight + day * 24 * 60 * minute + ((hour * 60L + min) * 60 + sec) * 1000

    /** [seen] is when the log last recorded anything a second or more earlier. Left out, it had only just. */
    private fun on(t: Long, seen: Long = t) = ScreenEvent(t, ScreenChange.ON, seen)
    private fun off(t: Long) = ScreenEvent(t, ScreenChange.OFF)
    private fun locked(t: Long) = ScreenEvent(t, ScreenChange.LOCKED)
    private fun unlocked(t: Long) = ScreenEvent(t, ScreenChange.UNLOCKED)
    private fun shutdown(t: Long, seen: Long = t) = ScreenEvent(t, ScreenChange.SHUTDOWN, seen)

    /** The phone lying dark and locked at [now], unless said otherwise, after [events]. */
    private fun tally(
        events: List<ScreenEvent>,
        now: Long,
        on: Boolean = false,
        locked: Boolean = true,
        days: List<Long> = listOf(midnight),
    ) = tallyScreen(ScreenLog(events.firstOrNull()?.at ?: now, events, on, locked), days, now)

    private fun List<DayUse>.minutes() = map { it.screenMs / minute.toDouble() }
    private fun List<DayUse>.unlocks() = map { it.unlocks }

    /** Picked up, unlocked, used and put down. Android's lock goes up a second after the screen goes dark. */
    private fun session(from: Long, minutes: Int): List<ScreenEvent> =
        listOf(on(from - 5000), unlocked(from), off(from + minutes * minute), locked(from + minutes * minute + 1000))

    @Test fun timeUnlockedIsScreenTimeAndEachUnlockCounts() {
        val use = tally(session(at(8), 10) + session(at(9, 30), 25), now = at(12))
        assertEquals(listOf(35.0), use.minutes())
        assertEquals(listOf(2), use.unlocks())
        assertEquals(midnight, use[0].start)
    }

    @Test fun aGlanceAtTheLockScreenIsNeither() {
        val glance = listOf(on(at(9)), off(at(9, 0, 6)))
        val use = tally(session(at(8), 10) + glance + glance.map { it.copy(at = it.at + minute) }, now = at(12))
        assertEquals(listOf(10.0), use.minutes())
        assertEquals(listOf(1), use.unlocks())
    }

    @Test fun aDayOfOnlyGlancesIsNothing() {
        // No entry about the lock at all: it is up now, so it was up all along.
        val use = tally(listOf(on(at(9)), off(at(9, 0, 6)), on(at(10)), off(at(10, 0, 9))), now = at(12), locked = true)
        assertEquals(listOf(0.0), use.minutes())
        assertEquals(listOf(0), use.unlocks())
    }

    @Test fun withNoLockScreenEveryTimeTheScreenTurnsOnIsAnUnlock() {
        val events = listOf(on(at(9)), off(at(9, 4)), on(at(10)), off(at(10, 6)))
        val use = tally(events, now = at(12), locked = false)
        assertEquals(listOf(10.0), use.minutes())
        assertEquals(listOf(2), use.unlocks())
    }

    @Test fun useThatIsStillGoingOnCountsUpToNow() {
        val use = tally(listOf(on(at(11, 49, 55)), unlocked(at(11, 50))), now = at(12), on = true, locked = false)
        assertEquals(listOf(10.0), use.minutes())
        assertEquals(listOf(1), use.unlocks())
    }

    @Test fun theLockGoingUpWhileTheScreenIsOnEndsTheUse() {
        val events = listOf(on(at(8)), unlocked(at(8, 0, 5)), locked(at(8, 5, 5)), off(at(8, 5, 35)))
        assertEquals(listOf(5.0), tally(events, now = at(12)).minutes())
    }

    @Test fun aFingerOnTheSensorOfADarkPhoneIsOneUnlock() {
        // The lock is reported down before the screen is reported on.
        val events = listOf(unlocked(at(8)), on(at(8, 0, 0) + 200), off(at(8, 10) + 200), locked(at(8, 10, 1)))
        val use = tally(events, now = at(12))
        assertEquals(listOf(10.0), use.minutes())
        assertEquals(listOf(1), use.unlocks())
    }

    /* ---------- days ---------- */

    @Test fun useAcrossMidnightIsSplitAndTheUnlockBelongsToTheDayItHappened() {
        val days = listOf(midnight, at(0, day = 1))
        val events = listOf(on(at(23, 49)), unlocked(at(23, 50)), off(at(0, 20, day = 1)), locked(at(0, 20, 1, day = 1)))
        val use = tally(events, now = at(9, day = 1), days = days)
        assertEquals(listOf(10.0, 20.0), use.minutes())
        assertEquals(listOf(1, 0), use.unlocks())
        assertEquals(days, use.map { it.start })
    }

    @Test fun whatHappenedBeforeTheFirstDayOnlySaysHowThingsStood() {
        val events = listOf(on(at(23, 0, day = -1)), unlocked(at(23, 0, 5, day = -1)), off(at(0, 15)), locked(at(0, 15, 1))) + session(at(7), 5)
        val use = tally(events, now = at(12))
        assertEquals(listOf(20.0), use.minutes())
        assertEquals("the unlock before midnight is yesterday's", listOf(1), use.unlocks())
    }

    @Test fun useUnderWayWhenTheLogStartsIsCountedFromTheStartOfTheDay() {
        // The first entries are the screen going dark and the lock going up: it was on and unlocked.
        val use = tally(listOf(off(at(0, 30)), locked(at(0, 30, 1))), now = at(12))
        assertEquals(listOf(30.0), use.minutes())
        assertEquals(listOf(0), use.unlocks())
    }

    @Test fun withNothingLoggedTheDayIsAsThePhoneIsNow() {
        assertEquals(listOf(0.0), tally(emptyList(), now = at(0, 10)).minutes())
        val film = tally(emptyList(), now = at(0, 10), on = true, locked = false)
        assertEquals("in use since before midnight", listOf(10.0), film.minutes())
        assertEquals(listOf(0), film.unlocks())
    }

    @Test fun aWeekIsSevenDaysEvenIfMostAreEmpty() {
        val days = (0..6).map { at(0, day = it) }
        val use = tally(session(at(8, day = 2), 30) + session(at(9, day = 6), 12), now = at(10, day = 6), days = days)
        assertEquals(listOf(0.0, 0.0, 30.0, 0.0, 0.0, 0.0, 12.0), use.minutes())
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 1), use.unlocks())
    }

    /* ---------- restarts and holes ---------- */

    @Test fun aRestartStampsEverythingBeforeTheFirstUnlockWithThatUnlock() {
        // Dark and locked, switched off, back on half an hour later and left on the desk for a while.
        // Android wrote all of the restart's entries with one time: the moment of the first unlock.
        val t = at(10, 30)
        val events = session(at(9), 10) + shutdown(at(9, 20)) +
            listOf(on(t), locked(t), off(t), on(t), unlocked(t), off(at(10, 40)), locked(at(10, 40, 1)))
        val use = tally(events, now = at(12))
        assertEquals(listOf(20.0), use.minutes())
        assertEquals(listOf(2), use.unlocks())
    }

    @Test fun aRestartWhileInUseEndsTheUseAndAddsNoUnlockOfItsOwn() {
        // No entry for the screen going dark: the shutdown is all there is. After the restart the
        // log has the screen on a moment before it has the lock up.
        val t = at(9, 25)
        val events = listOf(on(at(8, 59, 55)), unlocked(at(9)), shutdown(at(9, 20))) +
            listOf(on(t), locked(t), unlocked(t), off(at(9, 30)), locked(at(9, 30, 1)))
        val use = tally(events, now = at(12))
        assertEquals(listOf(25.0), use.minutes())
        assertEquals("one before and one after, none for the restart itself", listOf(2), use.unlocks())
    }

    @Test fun aPhoneThatDiedIsNotCountedAsInUseUntilItCameBack() {
        // Unlocked at 8:00, the last thing in the log is from 8:20, and at 14:00 the screen is simply
        // on again. No shutdown was noted, as on Android 9, which never notes one.
        val t = at(14)
        val events = listOf(on(at(7, 59, 55)), unlocked(at(8))) +
            listOf(on(t, seen = at(8, 20)), locked(t), unlocked(at(14, 0, 5)), off(at(14, 10, 5)), locked(at(14, 10, 6)))
        val use = tally(events, now = at(18))
        assertEquals("twenty minutes it can vouch for and ten after, not six hours", listOf(30.0), use.minutes())
        assertEquals(listOf(2), use.unlocks())
    }

    @Test fun switchedOffWhileInUseEndsAtTheShutdown() {
        // As Android 14 wrote it for a phone with no lock screen, restarted from the power menu:
        // nothing about the screen going dark, a shutdown noted to the second, the screen on again.
        val events = listOf(
            on(at(16, 31, 44)),
            shutdown(at(16, 33, 13), seen = at(16, 33, 1)),
            on(at(16, 34, 5), seen = at(16, 33, 1)),
        )
        val use = tally(events, now = at(16, 44, 5), on = true, locked = false)
        assertEquals(listOf(89_000L + 10 * minute), use.map { it.screenMs })
        assertEquals(listOf(2), use.unlocks())
    }

    @Test fun aShutdownNotedHoursLateIsNotBelieved() {
        // Switched off at eleven at night while in use, started at seven. The note of the shutdown
        // carries the time of the start that wrote it. The log's last entry before it is from 23:00.
        val days = listOf(midnight, at(0, day = 1))
        val t = at(7, 6, day = 1)
        val events = listOf(on(at(22, 49, 55)), unlocked(at(22, 50))) +
            shutdown(at(7, 5, day = 1), seen = at(23)) +
            listOf(on(t), locked(t), unlocked(t + 1000), off(t + 1000 + 10 * minute), locked(t + 2000 + 10 * minute))
        val use = tally(events, now = at(12, day = 1), days = days)
        assertEquals("until a minute after its last entry, not through the night", listOf(11.0, 10.0), use.minutes())
        assertEquals(listOf(1, 1), use.unlocks())
    }

    @Test fun aShutdownNotedAmongTheEntriesOfTheStartAfterIt() {
        // A phone with only a swipe to unlock is ready the moment it starts, so the start's entries
        // and its note of the shutdown before it have the same time, in either order.
        val t = at(10, 30)
        val start = listOf(on(t), locked(t), shutdown(t + 1, seen = at(9, 10, 1)), unlocked(t + 5000), off(t + 5000 + 10 * minute), locked(t + 6000 + 10 * minute))
        val use = tally(session(at(9), 10) + start, now = at(12))
        assertEquals(listOf(20.0), use.minutes())
        assertEquals(listOf(2), use.unlocks())

        // The same with no lock screen at all, and the phone in use ever since.
        val bare = listOf(on(at(9)), off(at(9, 10)), on(t), shutdown(t + 1, seen = at(9, 10)))
        val still = tally(bare, now = at(10, 45), on = true, locked = false)
        assertEquals(listOf(25.0), still.minutes())
        assertEquals(listOf(2), still.unlocks())
    }

    @Test fun aStartThatStampsItsEntriesAsTheyHappenCountsNoUseBeforeTheUnlock() {
        // Older Androids: on at three in the night after an update, the lock screen two seconds
        // later, dark again after half a minute, and unlocked in the morning.
        val events = listOf(shutdown(at(2, 59)), on(at(3)), locked(at(3, 0, 2)), off(at(3, 0, 32))) + session(at(8), 5)
        val use = tally(events, now = at(12))
        assertEquals(listOf(5.0), use.minutes())
        assertEquals(listOf(1), use.unlocks())
    }

    @Test fun sessionsLostToAPhoneThatKeptDyingCountForNextToNothing() {
        // An emulator killed over and over, as its Android 14 wrote it: on at 19:07, nothing more,
        // on again at 21:50 with a shutdown noted a second later, on again in the small hours.
        val days = listOf(midnight, at(0, day = 1))
        val events = listOf(
            on(at(19, 7, 16)),
            on(at(21, 50, 27), seen = at(19, 7, 16)),
            shutdown(at(21, 50, 28), seen = at(19, 7, 16)),
            on(at(2, 18, 5, day = 1), seen = at(21, 50, 27)),
            off(at(2, 35, 41, day = 1)),
        )
        val use = tally(events, now = at(9, day = 1), locked = false, days = days)
        assertEquals("nothing is made up for the hours in between", true, use[0].screenMs <= 61_000)
        assertEquals(17 * minute + 36_000, use[1].screenMs)
    }

    @Test fun useTheLogNeverSawTheEndOfStopsAtItsLastEntry() {
        // In use at 8:00 by the log, and the phone is dark and locked now.
        val events = listOf(on(at(7, 59)), unlocked(at(8)), locked(at(8, 30)), unlocked(at(8, 31)))
        val use = tally(events, now = at(20), on = false, locked = true)
        assertEquals(listOf(30.0), use.minutes())
    }

    @Test fun anUnlockTheLogHasNotCaughtUpWithStillCounts() {
        // Asked the instant after unlocking: the screen is on in the log, the unlock is not there yet.
        val use = tally(session(at(8), 10) + on(at(11, 59, 50)), now = at(12), on = true, locked = false)
        assertEquals(listOf(2), use.unlocks())
        assertEquals(listOf(10.0), use.minutes())
    }

    @Test fun entriesOutOfOrderOrFromTheFutureDoNoHarm() {
        val shuffled = session(at(9, 30), 25) + session(at(8), 10)
        assertEquals(listOf(35.0), tally(shuffled, now = at(12)).minutes())

        // A clock that was set back leaves entries later than now.
        val ahead = listOf(on(at(11, 55)), unlocked(at(11, 56)), off(at(15)))
        val use = tally(ahead, now = at(12))
        assertEquals(listOf(4.0), use.minutes())
        assertEquals(listOf(1), use.unlocks())
    }

    /* ---------- reading the log ---------- */

    @Test fun theReaderNotesWhenThePhoneWasLastSeenRunning() {
        val reader = ScreenLogReader()
        reader.take(at(8), null)                            // an app came to the front
        reader.take(at(8, 20), null)
        // A start writes a burst of entries with one time, the screen among them.
        val t = at(14)
        reader.take(t, null)
        reader.take(t, ScreenChange.ON)
        reader.take(t + 300, ScreenChange.LOCKED)
        reader.take(t + 300, null, marker = true)           // the start's note of itself
        reader.take(t + 5000, ScreenChange.UNLOCKED)
        val log = reader.log(now = at(15), on = true, locked = false)
        assertEquals("how far back it reaches", at(8), log.since)
        assertEquals(listOf(ScreenChange.ON, ScreenChange.LOCKED, ScreenChange.UNLOCKED), log.events.map { it.change })
        assertEquals("the burst does not vouch for itself", listOf(at(8, 20), at(8, 20), t + 300), log.events.map { it.seen })
        assertEquals(t + 5000, log.seen)
        assertTrue(log.on && !log.locked)
    }

    @Test fun whatThePhoneNotesAboutItselfIsNoSignOfLife() {
        val reader = ScreenLogReader()
        reader.take(at(23), null)
        reader.take(at(7, 5, day = 1), ScreenChange.SHUTDOWN, marker = true)
        reader.take(at(7, 5, day = 1) + 40, null, marker = true)
        reader.take(at(7, 6, day = 1), ScreenChange.ON)
        val log = reader.log(now = at(8, day = 1), on = true, locked = true)
        assertEquals(listOf(at(23), at(23)), log.events.map { it.seen })

        val empty = ScreenLogReader().log(now = at(8), on = false, locked = true)
        assertEquals("an empty log reaches back to now", at(8), empty.since)
        assertEquals(emptyList<ScreenEvent>(), empty.events)
    }

    @Test fun noDaysNoAnswer() {
        assertEquals(emptyList<DayUse>(), tallyScreen(ScreenLog(0, emptyList(), on = true, locked = false), emptyList(), at(12)))
    }
}

/** The `screen` command and the login summary's line, against a phone whose log the test supplies. */
class ScreenCommandTest {
    private val phone = FakePhone().apply { usageAccess = true }
    private val store = FakeStore()
    private val vault = FakeVault()
    private var time = 1_795_000_000_000L           // Wednesday 18 November 2026, 11:06:40 UTC
    private var changes = 0
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()
    private val midnight = 1_794_960_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Before fun fixed() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private fun shell() = Shell(phone, store, vault) { time }.also { s -> s.onChange = { changes++ }; s.start() }

    private fun Shell.rows(): List<String> = lines.map { line ->
        if (line is TextLine) line.spans.joinToString("") { it.text } else "<$line>"
    }
    private fun Shell.chips(input: String) = candidates(input)?.items?.map { it.text }

    /** Picked up, unlocked, used for [minutes] and put down, starting at [hour] o'clock [daysAgo] days ago. */
    private fun session(daysAgo: Int, hour: Int, minutes: Int): List<ScreenEvent> {
        val from = midnight - daysAgo * day + hour * 60 * minute
        return listOf(
            ScreenEvent(from - 5000, ScreenChange.ON),
            ScreenEvent(from, ScreenChange.UNLOCKED),
            ScreenEvent(from + minutes * minute, ScreenChange.OFF),
            ScreenEvent(from + minutes * minute + 1000, ScreenChange.LOCKED),
        )
    }

    /** The phone as it is while someone reads the terminal: on, and unlocked [ago] ms back. */
    private fun inHand(ago: Long) = listOf(ScreenEvent(time - ago - 8000, ScreenChange.ON), ScreenEvent(time - ago, ScreenChange.UNLOCKED))

    private fun log(events: List<ScreenEvent>) = ScreenLog(events.first().at, events, on = true, locked = false)

    private val today = session(0, 8, 10) + session(0, 9, 25)
    private val week = session(7, 20, 30) + session(6, 9, 60) +
        session(5, 9, 30) + session(5, 12, 30) + session(5, 18, 60) +
        session(3, 10, 45) + session(2, 8, 90) + session(2, 20, 90) + session(1, 9, 15) + today

    /* ---------- the command ---------- */

    @Test fun aWeekOfDaysWithTodayLast() {
        val s = shell()
        s.submit("screen")
        assertEquals("a day more than is shown, to know how the first day began", listOf(midnight - 7 * day), phone.logAsked)
        assertEquals("reading the phone's log", s.rows().last())
        assertNull("no prompt while it reads", s.prompt)
        assertEquals(listOf("cancel"), s.chips(""))

        val before = changes
        phone.logRead!!(log(week + inHand(5 * minute)))
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertFalse("reading the phone's log" in s.rows())
        assertTrue("the screen is told", changes > before)
        assertEquals(
            listOf(
                Gauge("thu", 33, "  1h 0m    1"),
                Gauge("fri", 66, "  2h 0m    3"),
                Gauge("sat", 0, "     0m    0"),
                Gauge("sun", 25, "    45m    1"),
                Gauge("mon", 100, "  3h 0m    2"),
                Gauge("tue", 8, "    15m    1"),
                Gauge("today", 22, "    40m    3"),
            ),
            s.lines.filterIsInstance<Gauge>(),
        )
        assertEquals("average: 1h 10m on screen, 1 unlock a day", s.rows().last())
    }

    @Test fun onlyTheDaysThePhonesLogReaches() {
        val s = shell()
        s.submit("screen")
        // Nothing before Monday evening: Monday is shown, but it is no whole day to average.
        phone.logRead!!(log(session(2, 20, 90) + session(1, 9, 15) + today + inHand(5 * minute)))
        assertEquals(listOf("mon", "tue", "today"), s.lines.filterIsInstance<Gauge>().map { it.label })
        assertEquals("average: 15m on screen, 1 unlock a day", s.rows().last())

        s.clear()
        s.submit("screen")
        phone.logRead!!(log(today + inHand(5 * minute)))
        assertEquals(listOf(Gauge("today", 100, "    40m    3")), s.lines.filterIsInstance<Gauge>())
        assertEquals("with no whole day there is no average", "time on screen, and unlocks", s.rows().last())
    }

    @Test fun withoutUsageAccessItSaysHowToGrantIt() {
        phone.usageAccess = false
        val s = shell()
        s.submit("screen")
        val rows = s.rows()
        assertEquals("screen: usage access is off", rows[rows.size - 2])
        assertEquals("setup usage", (s.lines.last() as TextLine).spans.last().run)
        assertTrue("the log is not asked for", phone.logAsked.isEmpty())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)

        s.submit("setup usage")
        assertEquals(listOf("usage"), phone.fixed)
    }

    @Test fun aPhoneThatKeepsNoLogSaysSo() {
        phone.keepsScreenLog = false
        val s = shell()
        s.submit("screen")
        assertEquals("screen: this phone keeps no log of it", s.rows().last())
        assertTrue(phone.logAsked.isEmpty())
    }

    @Test fun aLogThatCannotBeReadAndACancelledRead() {
        val s = shell()
        s.submit("screen")
        phone.logRead!!(null)
        assertEquals("screen: the phone's log could not be read", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)

        s.submit("screen")
        val read = phone.logRead!!
        s.press("cancel")
        assertEquals("screen: cancelled", s.rows().last())
        val rows = s.rows()
        read(log(week))
        assertEquals("what arrives after a cancel is dropped", rows, s.rows())

        s.submit("screen now")
        assertEquals("usage: screen [budget <time>|off]", s.rows().last())
        assertTrue("screen" in s.chips("scr")!!)
    }

    /* ---------- a daily budget ---------- */

    @Test fun aBudgetIsSetShownAndRemoved() {
        val s = shell()
        s.submit("screen budget")
        assertTrue("No daily budget is set. Set one:" in s.rows())
        assertEquals("screen budget ", (s.lines.last() as TextLine).spans.first().fill)

        s.submit("screen budget 2h")
        assertEquals("daily budget: 2h", s.rows().last())
        assertEquals("120", store.get("screen_budget"))

        s.submit("screen budget")
        val rows = s.rows()
        assertEquals("daily budget: 2h", rows[rows.size - 2])
        assertEquals("screen budget off", (s.lines.last() as TextLine).spans.last().run)

        s.submit("screen budget off")
        assertEquals("no daily budget", s.rows().last())
        assertNull(store.get("screen_budget"))

        assertEquals(listOf("budget"), s.chips("screen "))
        assertEquals(listOf("1h", "2h", "3h", "4h", "off"), s.chips("screen budget "))
        assertEquals(listOf("off"), s.chips("screen budget o"))
    }

    @Test fun aLengthOfTimeCanBeTypedManyWays() {
        val s = shell()
        val ways = listOf(
            Triple("90m", "90", "1h 30m"), Triple("1h30m", "90", "1h 30m"), Triple("1h 30m", "90", "1h 30m"),
            Triple("1h30", "90", "1h 30m"), Triple("1.5h", "90", "1h 30m"), Triple("1:30", "90", "1h 30m"),
            Triple("45m", "45", "45m"), Triple("3H", "180", "3h"), Triple("24h", "1440", "24h"),
        )
        for ((typed, minutes, shown) in ways) {
            s.submit("screen budget $typed")
            assertEquals(typed, minutes, store.get("screen_budget"))
            assertEquals(typed, "daily budget: $shown", s.rows().last())
        }
        s.submit("screen limit 2h")
        assertEquals("limit is another word for it", "120", store.get("screen_budget"))

        for (bad in listOf("2", "soon", "h", "2x", "-1h", "1h2h")) {
            s.submit("screen budget $bad")
            assertEquals("screen: '$bad' is not a length of time. Try 2h or 90m", s.rows().last())
        }
        for (tooMuch in listOf("25h", "0m", "9999m")) {
            s.submit("screen budget $tooMuch")
            assertEquals(tooMuch, "screen: a budget is from 1m to 24h", s.rows().last())
        }
        assertEquals("the last good one stands", "120", store.get("screen_budget"))
    }

    @Test fun aBudgetSetBeforeUsageAccessSaysWhatIsMissing() {
        phone.usageAccess = false
        val s = shell()
        s.submit("screen budget 2h")
        val rows = s.rows()
        assertEquals("daily budget: 2h", rows[rows.size - 3])
        assertEquals("screen: usage access is off, so nothing is measured yet", rows[rows.size - 2])
        assertEquals("setup usage", (s.lines.last() as TextLine).spans.last().run)
    }

    @Test fun withABudgetEachDayIsMeasuredAgainstIt() {
        store.map["screen_budget"] = "120"
        val s = shell()
        s.submit("screen")
        phone.logRead!!(log(week + inHand(5 * minute)))
        assertEquals(
            listOf(
                Gauge("thu", 50, "  1h 0m    1"),
                Gauge("fri", 100, "  2h 0m    3"),                  // the budget to the minute is not over it
                Gauge("sat", 0, "     0m    0"),
                Gauge("sun", 37, "    45m    1"),
                Gauge("mon", 100, "  3h 0m    2", alert = true),
                Gauge("tue", 12, "    15m    1"),
                Gauge("today", 33, "    40m    3"),
            ),
            s.lines.filterIsInstance<Gauge>(),
        )
        val rows = s.rows()
        assertEquals("average: 1h 10m on screen, 1 unlock a day", rows[rows.size - 2])
        assertEquals("budget 2h a day · 1h 20m left today", rows.last())
        assertEquals(Style.DIM, (s.lines.last() as TextLine).spans.single().style)

        s.submit("screen budget 30m")
        s.submit("screen")
        phone.logRead!!(log(week + inHand(5 * minute)))
        assertEquals("budget 30m a day · 10m over today", s.rows().last())
        assertEquals(Style.ERR, (s.lines.last() as TextLine).spans.single().style)
        assertEquals(Gauge("today", 100, "    40m    3", alert = true), s.lines.filterIsInstance<Gauge>().last())
    }

    @Test fun withABudgetTheLoginSummaryHasABarForIt() {
        store.map["screen_budget"] = "60"
        val s = loggedIn()
        assertEquals(listOf("day", "week", "year", "phone"), s.lines.filterIsInstance<Gauge>().map { it.label })
        assertEquals("empty until the log has been read", Gauge("phone", 0, "…"), s.lines.filterIsInstance<Gauge>().last())
        val at = s.rows().indexOf("screen time …")
        assertEquals(PromptKind.SHELL, s.prompt?.kind)

        val ids = s.lineIds
        phone.logRead!!(log(today + inHand(2000)))
        assertEquals(Gauge("phone", 58, "35m of 1h"), s.lines.filterIsInstance<Gauge>().last())
        assertEquals("the line below has the unlocks alone", "3 unlocks today", s.rows()[at])
        assertTrue((s.lines[at] as TextLine).spans.all { it.run == "screen" })
        assertEquals("both were filled in where they stood", ids, s.lineIds)
    }

    @Test fun overBudgetTheBarIsFullAndWarns() {
        store.map["screen_budget"] = "30"
        val s = loggedIn()
        phone.logRead!!(log(today + inHand(2000)))
        assertEquals(Gauge("phone", 100, "35m of 30m", alert = true), s.lines.filterIsInstance<Gauge>().last())
    }

    @Test fun aBudgetBarWithNothingToShow() {
        store.map["screen_budget"] = "60"
        val s = loggedIn()
        phone.logRead!!(null)
        assertEquals(Gauge("phone", 0, "no reading"), s.lines.filterIsInstance<Gauge>().last())
        assertTrue("screen time unavailable" in s.rows())
    }

    @Test fun withoutUsageAccessABudgetAddsNoBar() {
        store.map["screen_budget"] = "60"
        phone.usageAccess = false
        val s = loggedIn()
        assertEquals(listOf("day", "week", "year"), s.lines.filterIsInstance<Gauge>().map { it.label })
        assertTrue(phone.logAsked.isEmpty())
    }

    /* ---------- the login summary ---------- */

    private fun loggedIn(): Shell {
        vault.password = "pw"
        store.map["seen"] = "1"
        val s = shell()
        s.submit("pw")
        return s
    }

    @Test fun theLoginSummaryHasTodaysScreenTimeAndUnlocks() {
        val s = loggedIn()
        assertEquals("only today is read", listOf(midnight), phone.logAsked)
        val at = s.rows().indexOf("screen time …")
        assertTrue(s.rows().toString(), at >= 0)
        assertEquals("above what is waiting", "2 new · wifi · torch off", s.rows()[at + 1])
        assertEquals("the prompt does not wait for it", PromptKind.SHELL, s.prompt?.kind)
        assertFalse("cancel" in s.chips("").orEmpty())

        val ids = s.lineIds
        val before = changes
        phone.logRead!!(log(today + inHand(2000)))
        assertEquals("35m on screen · 3 unlocks", s.rows()[at])
        assertEquals("the same line, filled in", ids, s.lineIds)
        assertTrue(changes > before)
        val line = s.lines[at] as TextLine
        assertTrue("a tap anywhere on it runs screen", line.spans.all { it.run == "screen" })
    }

    @Test fun theFirstUnlockOfTheDayIsOneUnlock() {
        val s = loggedIn()
        phone.logRead!!(log(inHand(2000)))
        assertTrue(s.rows().toString(), "0m on screen · 1 unlock" in s.rows())
    }

    @Test fun withoutUsageAccessTheSummaryHasNoSuchLine() {
        phone.usageAccess = false
        val s = loggedIn()
        assertTrue("the summary printed", s.rows().any { it.startsWith("tip: ") })
        assertFalse(s.rows().any { it.startsWith("screen") || "on screen" in it })
        assertTrue(phone.logAsked.isEmpty())
    }

    @Test fun aLogThatCannotBeReadAtLoginIsSaidQuietly() {
        val s = loggedIn()
        val at = s.rows().indexOf("screen time …")
        phone.logRead!!(null)
        assertEquals("screen time unavailable", s.rows()[at])
        assertEquals(Style.DIM, (s.lines[at] as TextLine).spans.single().style)
    }

    @Test fun anAnswerAfterTheTerminalLockedStaysOffTheLockScreen() {
        val s = loggedIn()
        val read = phone.logRead!!
        s.lock()
        s.wake()
        val rows = s.rows()
        read(log(today + inHand(2000)))
        assertEquals(rows, s.rows())
        assertTrue(s.locked)
        assertEquals(PromptKind.LOGIN, s.prompt?.kind)
    }
}
