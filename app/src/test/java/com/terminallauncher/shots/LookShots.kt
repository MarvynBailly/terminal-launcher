package com.terminallauncher.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.terminallauncher.shell.Cue
import com.terminallauncher.shell.DayForecast
import com.terminallauncher.shell.FakePhone
import com.terminallauncher.shell.FakeStore
import com.terminallauncher.shell.FakeVault
import com.terminallauncher.shell.Forecast
import com.terminallauncher.shell.Notif
import com.terminallauncher.shell.Place
import com.terminallauncher.shell.ScreenChange
import com.terminallauncher.shell.ScreenEvent
import com.terminallauncher.shell.ScreenLog
import com.terminallauncher.shell.Shell
import com.terminallauncher.shell.Weather
import com.terminallauncher.ui.Pose
import com.terminallauncher.ui.TerminalScreen
import com.terminallauncher.ui.looks.lookFor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Pictures of the looks, drawn by the real UI on the desktop: each look's pages at rest and its
 * movements held at a handful of moments. They are for looking at, not for asserting on.
 *
 *   ./gradlew testDebugUnitTest -Pshots            every look
 *   ./gradlew testDebugUnitTest -Pshots=phosphor   one, or several with commas
 *
 * The PNGs land in app/build/shots (or -PshotsDir=...), named look__group__nn.png.
 * tools/look-sheets.py tiles them into two sheets a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class LookShots {
    private val now = 1_795_000_000_000L            // Wednesday 18 November 2026, 11:06:40 UTC
    private val midnight = 1_794_960_000_000L
    private val minute = 60_000L
    private val dir = File(System.getProperty("shots.dir") ?: "build/shots").also { it.mkdirs() }
    private val only = (System.getProperty("shots.only") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** rest, wake, granted, off, launch, lines, rest-moving. Empty means all of them. */
    private val groups = (System.getProperty("shots.groups") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun wanted(group: String) = groups.isEmpty() || group in groups
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before fun fixed() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private class ShotWeather : Weather {
        override val source = "open-meteo.com"
        var told: ((Forecast?, String?) -> Unit)? = null
        override fun find(name: String, done: (places: List<Place>?, problem: String?) -> Unit) = done(emptyList(), null)
        override fun forecast(place: Place, imperial: Boolean, done: (forecast: Forecast?, problem: String?) -> Unit) { told = done }
        fun answer() = told?.invoke(
            Forecast(
                temperature = 9.2, feelsLike = 6.1, humidity = 81, wind = 14.0, code = 61,
                days = listOf(
                    DayForecast("2026-11-18", 61, 11.0, 6.0, 70),
                    DayForecast("2026-11-19", 3, 8.0, 3.0, 20),
                    DayForecast("2026-11-20", 2, 7.0, 1.0, 10),
                ),
            ),
            null,
        )
    }

    /** A day's use: [unlocks] sittings of equal length from six in the morning on, [total] minutes in all. */
    private fun day(daysAgo: Int, total: Int, unlocks: Int, every: Int = 20): List<ScreenEvent> {
        val out = ArrayList<ScreenEvent>()
        val each = total * minute / unlocks
        for (i in 0 until unlocks) {
            val from = midnight - daysAgo * 24 * 60 * minute + (if (daysAgo == 0) 10 else 360) * minute + i * every * minute
            out += ScreenEvent(from - 4000, ScreenChange.ON)
            out += ScreenEvent(from, ScreenChange.UNLOCKED)
            out += ScreenEvent(from + each, ScreenChange.OFF)
            out += ScreenEvent(from + each + 1000, ScreenChange.LOCKED)
        }
        return out
    }

    private fun log(events: List<ScreenEvent>): ScreenLog {
        val inHand = listOf(ScreenEvent(now - 98_000, ScreenChange.ON), ScreenEvent(now - 90_000, ScreenChange.UNLOCKED))
        val all = (events + inHand).sortedBy { it.at }
        return ScreenLog(all.first().at, all, on = true, locked = false, seen = now)
    }

    /** One phone, set up the same for every look: a password, a city, a budget, a week of use. */
    private inner class World(look: String) {
        val phone = FakePhone().apply { usageAccess = true }
        val store = FakeStore().apply {
            map["seen"] = "1"
            map["look"] = look
            map["last_login"] = (now - 8_640_000).toString()
            map["screen_budget"] = "120"
            map["weather_place"] = "Berlin\nState of Berlin\nGermany\nDE\n52.52437\n13.41053"
        }
        val weather = ShotWeather()
        val shell = Shell(phone, store, FakeVault("pw"), null, weather) { now }.also { it.start() }

        /** The lock page as the phone wakes to it: boot log, clock, password prompt. */
        fun locked() = apply {
            shell.lock()
            shell.wake()
        }

        /** Logged in, the summary printed and filled in. */
        fun home() = apply {
            shell.submit("pw")
            weather.answer()
            phone.logRead?.invoke(log(day(0, 70, 22, 28)))
        }

        /** After a few commands and a notification. */
        fun used() = apply {
            home()
            shell.submit("ls")
            shell.submit("weather")
            shell.notify(Notif("messages", "Mom", "are you coming on Sunday?", 0))
            shell.submit("screen")
            phone.logRead?.invoke(log(day(7, 30, 4) + day(3, 151, 44) + day(2, 95, 38) + day(1, 65, 31) + day(0, 70, 22, 28)))
        }
    }

    private fun shot(name: String, world: World, pose: Pose) {
        if (!wanted(name.split("__")[1])) return
        captureRoboImage(File(dir, "$name.png").path) {
            Box(Modifier.fillMaxSize()) {
                TerminalScreen(world.shell, world.phone, rev = 0, wake = 0, visible = true, motion = true, pose = pose)
            }
        }
    }

    /** [count] moments spread over [ms], none at the very start or the very end. */
    private fun moments(ms: Int, count: Int): List<Float> = (1..count).map { ms * (it - 0.5f) / count }

    @Test fun shots() {
        val ids = Shell.LOOKS.map { it.first }.filter { only.isEmpty() || it in only }
        for (id in ids) {
            val look = lookFor(id)
            dir.listFiles { f -> f.name.startsWith("${id}__") && wanted(f.name.split("__")[1]) }?.forEach { it.delete() }

            shot("${id}__rest__1-lock", World(id).locked(), Pose())
            shot("${id}__rest__2-home", World(id).home(), Pose())
            shot("${id}__rest__3-use", World(id).used(), Pose())

            // the screen coming on, onto the lock page
            moments(look.powerOn.ms, 5).forEachIndexed { i, ms ->
                shot("${id}__wake__$i", World(id).locked(), Pose(cue = Cue.POWER_ON, ms = ms))
            }
            // a right password: the lock page leaves, the summary is under it
            val lock = World(id).locked().shell
            moments(look.granted.ms, 6).forEachIndexed { i, ms ->
                shot("${id}__granted__$i", World(id).home(), Pose(cue = Cue.GRANTED, ms = ms, before = lock.lines to lock.lineIds))
            }
            // `lock`: the home page as it was goes dark
            val home = World(id).used().shell
            moments(look.powerOff.ms, 5).forEachIndexed { i, ms ->
                shot("${id}__off__$i", World(id).locked(), Pose(cue = Cue.POWER_OFF, ms = ms, before = home.lines to home.lineIds))
            }
            // an app being opened from a line a little over half way down
            moments(minOf(look.launchMs, 560), 5).forEachIndexed { i, ms ->
                shot("${id}__launch__$i", World(id).used(), Pose(launch = ms))
            }
            // every line part of the way in
            listOf(0.12f, 0.35f, 0.6f, 0.85f).forEachIndexed { i, p ->
                shot("${id}__lines__$i", World(id).home(), Pose(arriving = p))
            }
            // looks that move at rest, a few seconds apart
            if (look.moves) listOf(0f, 900f, 2600f, 6100f).forEachIndexed { i, t ->
                shot("${id}__rest-moving__$i", World(id).home(), Pose(time = t))
            }
        }
    }
}
