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

/** Records what the shell asks for. A test plays the service by calling [found] and [told] itself. */
private class FakeWeather : Weather {
    override val source = "open-meteo.com"
    val searches = ArrayList<String>()
    val asked = ArrayList<Pair<Place, Boolean>>()
    var found: ((List<Place>?, String?) -> Unit)? = null
    var told: ((Forecast?, String?) -> Unit)? = null

    override fun find(name: String, done: (places: List<Place>?, problem: String?) -> Unit) {
        searches += name
        found = done
    }

    override fun forecast(place: Place, imperial: Boolean, done: (forecast: Forecast?, problem: String?) -> Unit) {
        asked += place to imperial
        told = done
    }
}

class WeatherTest {
    private val phone = FakePhone()
    private val store = FakeStore()
    private val vault = FakeVault()
    private val weather = FakeWeather()
    private var time = 1_795_000_000_000L       // Wednesday 18 November 2026, 11:06 UTC
    private var changes = 0
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    private val berlin = Place("Berlin", "State of Berlin", "Germany", "DE", 52.52437, 13.41053)
    private val missouri = Place("Springfield", "Missouri", "United States", "US", 37.21533, -93.29824)
    private val illinois = Place("Springfield", "Illinois", "United States", "US", 39.80172, -89.64371)
    private val oregon = Place("Springfield", "Oregon", "United States", "US", 44.04624, -123.02203)

    private val cold = Forecast(
        temperature = 5.4, feelsLike = 1.2, humidity = 73, wind = 11.6, code = 3,
        days = listOf(
            DayForecast("2026-11-18", 80, 5.6, 2.1, 88),
            DayForecast("2026-11-19", 61, 3.9, -0.4, 23),
            DayForecast("2026-11-20", 77, 2.5, -6.2, null),
        ),
    )

    @Before fun fixed() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private fun shell() = Shell(phone, store, vault, null, weather) { time }.also { s -> s.onChange = { changes++ }; s.start() }

    private fun Shell.rows(): List<String> = lines.map { line ->
        if (line is TextLine) line.spans.joinToString("") { it.text } else "<$line>"
    }
    private fun Shell.text() = rows().joinToString("\n")
    private fun Shell.chips(input: String) = candidates(input)?.items?.map { it.text }

    /** A shell whose city is already Berlin. */
    private fun settled(): Shell {
        val s = shell()
        s.submit("weather set berlin")
        weather.found!!(listOf(berlin), null)
        weather.told!!(cold, null)
        s.clear()
        return s
    }

    /* ---------- the first time ---------- */

    @Test fun withNoCityItSaysHowToNameOne() {
        val s = shell()
        s.submit("weather")
        assertTrue(s.text(), "No city is set yet" in s.text())
        val hint = s.lines.filterIsInstance<TextLine>().flatMap { it.spans }.single { it.fill != null }
        assertEquals("weather set ", hint.fill)
        assertTrue("nothing was asked", weather.searches.isEmpty() && weather.asked.isEmpty())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
    }

    @Test fun settingACityFindsItAndPrintsItsForecast() {
        val s = shell()
        s.submit("weather set Berlin")
        assertEquals(listOf("Berlin"), weather.searches)
        assertNull("no prompt while it waits", s.prompt)
        assertEquals("asking open-meteo.com", s.rows().last())
        assertEquals(listOf("cancel"), s.chips(""))

        val before = changes
        weather.found!!(listOf(berlin), null)
        assertNull("still waiting for the forecast", s.prompt)
        assertEquals(listOf(berlin to false), weather.asked)
        assertTrue("the screen is told", changes > before)

        weather.told!!(cold, null)
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        val rows = s.rows()
        assertFalse("the note is gone", "asking open-meteo.com" in rows)
        val at = rows.indexOf("berlin, germany")
        assertTrue(s.text(), at >= 0)
        assertEquals("5°C  overcast", rows[at + 1])
        assertEquals("feels 1° · wind 12 km/h · humidity 73%", rows[at + 2])
        assertEquals("", rows[at + 3])
        assertEquals("today   6°   2°  light showers 88%", rows[at + 4])
        assertEquals("thu     4°   0°  light rain    23%", rows[at + 5])
        assertEquals("fri     3°  -6°  snow grains  ", rows[at + 6])
        assertEquals("open-meteo.com · 11:06", rows[at + 7])
        assertEquals("This is now the city weather reports.", rows[at + 8])
        assertTrue("no line is wider than a narrow screen", rows.drop(at + 4).take(3).all { it.length <= 34 })

        assertEquals("Berlin\nState of Berlin\nGermany\nDE\n52.52437\n13.41053", store.get("weather_place"))
    }

    @Test fun aLikelyDayOfRainStandsOut() {
        val s = settled()
        s.submit("weather")
        val days = s.lines.filterIsInstance<TextLine>().filter { it.spans.size == 5 }
        assertEquals(Style.APP, days[0].spans[4].style)
        assertEquals(Style.DIM, days[1].spans[4].style)
    }

    @Test fun theFirstCityEverAskedAboutIsRemembered() {
        val s = shell()
        s.submit("weather berlin")
        weather.found!!(listOf(berlin), null)
        weather.told!!(cold, null)
        assertTrue("This is now the city weather reports." in s.rows())
        s.submit("weather")
        assertEquals("the saved city needs no search", 1, weather.searches.size)
    }

    /* ---------- afterwards ---------- */

    @Test fun aFreshForecastIsNotAskedForAgain() {
        val s = settled()
        assertEquals(1, weather.asked.size)
        time += 9 * 60_000
        s.submit("weather")
        assertEquals("shown from what is already known", 1, weather.asked.size)
        assertTrue("5°C  overcast" in s.rows())
        assertTrue("with the time it was fetched", "open-meteo.com · 11:06" in s.rows())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)

        time += 2 * 60_000
        s.submit("weather")
        assertEquals("a stale one is fetched again", 2, weather.asked.size)
        assertNull(s.prompt)
        weather.told!!(cold, null)
        assertTrue("open-meteo.com · 11:17" in s.rows())
    }

    @Test fun anotherCityIsShownOnceAndNotKept() {
        val s = settled()
        val saved = store.get("weather_place")
        s.submit("weather springfield")
        assertEquals("springfield", weather.searches.last())
        weather.found!!(listOf(missouri, illinois), null)
        assertEquals(missouri to false, weather.asked.last())
        weather.told!!(cold, null)
        assertTrue("springfield, missouri, us" in s.rows())
        assertFalse("This is now the city weather reports." in s.rows())
        assertFalse("no other cities are offered", s.rows().any { it.startsWith("not it?") })
        assertEquals(saved, store.get("weather_place"))
    }

    @Test fun wordsAfterACommaPickAmongCitiesOfOneName() {
        val s = settled()
        s.submit("weather set Springfield, Illinois")
        assertEquals("only the name is searched for", "Springfield", weather.searches.last())
        weather.found!!(listOf(missouri, illinois, oregon), null)
        assertEquals(illinois to false, weather.asked.last())
        weather.told!!(cold, null)
        assertTrue(store.get("weather_place")!!.startsWith("Springfield\nIllinois\n"))

        s.submit("weather set springfield, us")
        weather.found!!(listOf(missouri, illinois), null)
        assertEquals("a country's two letters work too", missouri, weather.asked.last().first)
        weather.told!!(cold, null)

        s.submit("weather set springfield, ohio")
        weather.found!!(listOf(missouri, illinois), null)
        assertEquals("weather: no city called 'springfield, ohio'", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
    }

    @Test fun theOtherCitiesOfThatNameAreOneTapAway() {
        val s = shell()
        s.submit("weather set springfield")
        weather.found!!(listOf(missouri, illinois, oregon), null)
        weather.told!!(cold, null)
        val offers = s.lines.filterIsInstance<TextLine>().filter { it.spans.first().text == "not it? " }.map { it.spans[1] }
        assertEquals(listOf("springfield, illinois, us", "springfield, oregon, us"), offers.map { it.text })

        s.runTapped(offers[0].run!!)
        assertEquals("springfield", weather.searches.last())
        weather.found!!(listOf(missouri, illinois, oregon), null)
        assertEquals(illinois, weather.asked.last().first)
    }

    /* ---------- when it goes wrong ---------- */

    @Test fun aCityNobodyKnows() {
        val s = shell()
        s.submit("weather set zzzzqqqq")
        weather.found!!(emptyList(), null)
        assertEquals("weather: no city called 'zzzzqqqq'", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertNull(store.get("weather_place"))
        assertTrue(weather.asked.isEmpty())

        s.submit("weather set x")
        assertEquals("weather: 'x' is too short to look up", s.rows().last())
        assertEquals("not searched for", 1, weather.searches.size)
    }

    @Test fun noConnection() {
        val s = shell()
        s.submit("weather set berlin")
        weather.found!!(null, "could not reach open-meteo.com. Is the phone online?")
        assertEquals("weather: could not reach open-meteo.com. Is the phone online?", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertFalse("asking open-meteo.com" in s.rows())

        val t = settled()
        time += 60 * 60_000
        t.submit("weather")
        weather.told!!(null, "open-meteo.com did not answer in time")
        assertEquals("weather: open-meteo.com did not answer in time", t.rows().last())
        assertEquals(PromptKind.SHELL, t.prompt?.kind)
    }

    @Test fun aNumberThatIsNotOneDoesNotEndTheApp() {
        val s = settled()
        time += 60 * 60_000
        s.submit("weather")
        weather.told!!(cold.copy(temperature = Double.NaN, wind = Double.POSITIVE_INFINITY), null)
        assertTrue(s.text(), "?°C  overcast" in s.rows())
        assertTrue(s.rows().any { it.startsWith("feels 1° · wind ? km/h") })
    }

    /* ---------- while it waits ---------- */

    @Test fun nothingTypedIsRunWhileItWaits() {
        val s = shell()
        s.submit("weather set berlin")
        val rows = s.rows().size
        s.submit("ls")
        s.runTapped("help")
        assertEquals(rows, s.rows().size)
        assertTrue(phone.launched.isEmpty())
    }

    @Test fun cancelStopsWaitingAndTheLateAnswerIsDropped() {
        val s = shell()
        s.submit("weather set berlin")
        val find = weather.found!!
        s.press("cancel")
        assertEquals("weather: cancelled", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertFalse("asking open-meteo.com" in s.rows())

        val rows = s.rows()
        find(listOf(berlin), null)
        assertEquals("nothing is printed", rows, s.rows())
        assertNull("nothing is saved", store.get("weather_place"))
        assertTrue("and no forecast is asked for", weather.asked.isEmpty())

        // Ctrl+C does the same, here while the forecast itself is on its way
        s.submit("weather set berlin")
        weather.found!!(listOf(berlin), null)
        val tell = weather.told!!
        s.cancel("")
        assertEquals("weather: cancelled", s.rows().last())
        val after = s.rows()
        tell(cold, null)
        assertEquals(after, s.rows())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertNull("the city was found, but a cancelled command changes nothing", store.get("weather_place"))
    }

    @Test fun aCityIsOnlyKeptOnceItsForecastHasShown() {
        val s = settled()
        val saved = store.get("weather_place")
        time += 60 * 60_000

        s.submit("weather set springfield")
        weather.found!!(listOf(missouri), null)
        assertEquals("found, but not kept yet", saved, store.get("weather_place"))
        weather.told!!(null, "open-meteo.com did not answer in time")
        assertEquals("weather: open-meteo.com did not answer in time", s.rows().last())
        assertEquals("a command that failed changes nothing", saved, store.get("weather_place"))
        assertFalse("This is now the city weather reports." in s.rows())

        s.submit("weather")
        assertEquals("so weather still means the old city", berlin, weather.asked.last().first)
        weather.told!!(cold, null)

        s.submit("weather set springfield")
        weather.found!!(listOf(missouri), null)
        weather.told!!(cold, null)
        assertTrue(store.get("weather_place")!!.startsWith("Springfield\nMissouri\n"))
    }

    @Test fun anAnswerForAnOlderRequestDoesNotEndANewerWait() {
        val s = settled()
        time += 60 * 60_000
        s.submit("weather")
        val first = weather.told!!
        s.press("cancel")
        s.submit("weather")
        first(cold, null)
        assertNull("the second request is still out", s.prompt)
        assertEquals("asking open-meteo.com", s.rows().last())
        weather.told!!(cold, null)
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
    }

    @Test fun aLockedTerminalStaysLockedWhenTheAnswerArrives() {
        vault.password = "pw"
        val s = shell()
        s.submit("pw")
        s.submit("weather set berlin")
        weather.found!!(listOf(berlin), null)
        val tell = weather.told!!

        assertTrue(s.lock())
        s.wake()
        assertEquals(PromptKind.LOGIN, s.prompt?.kind)
        tell(cold, null)
        assertTrue(s.locked)
        assertEquals("the password is still what is asked for", PromptKind.LOGIN, s.prompt?.kind)
        assertFalse("and no forecast shows on the lock screen", s.rows().any { "overcast" in it })
        assertNull("no cancel chip either", s.chips(""))
        assertNull("and the city is not kept", store.get("weather_place"))

        s.submit("pw")
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
        assertFalse(s.rows().any { "overcast" in it })
    }

    @Test fun aSearchStillOutWhenTheTerminalLocksGoesNoFurther() {
        vault.password = "pw"
        val s = shell()
        s.submit("pw")
        s.submit("weather set berlin")
        val find = weather.found!!
        s.lock()
        s.wake()
        find(listOf(berlin), null)
        assertTrue("no forecast is asked for from a locked terminal", weather.asked.isEmpty())
        assertEquals(PromptKind.LOGIN, s.prompt?.kind)
    }

    @Test fun withNoPasswordTheScreenTurningOffDoesNotLoseTheAnswer() {
        val s = shell()
        s.submit("weather set berlin")
        weather.found!!(listOf(berlin), null)
        assertFalse("nothing locks without a password", s.lock())
        s.wake()
        weather.told!!(cold, null)
        assertTrue("5°C  overcast" in s.rows())
        assertEquals(PromptKind.SHELL, s.prompt?.kind)
    }

    /* ---------- units ---------- */

    @Test fun unitsFollowThePhonesRegionUntilOneIsChosen() {
        Locale.setDefault(Locale.US)
        val s = settled()
        assertEquals(berlin to true, weather.asked.last())

        s.submit("weather units")
        val words = s.lines.filterIsInstance<TextLine>().last().spans.filter { it.run != null }
        assertEquals(listOf("c", "[f]"), words.map { it.text })
        assertEquals("weather units c", words[0].run)

        s.submit("weather units c")
        assertEquals("weather in °C and km/h", s.rows().last())
        s.submit("weather")
        assertEquals("the forecast in the other units is not reused", berlin to false, weather.asked.last())
        weather.told!!(cold, null)
        assertTrue("5°C  overcast" in s.rows())

        s.submit("weather units f")
        s.submit("weather")
        weather.told!!(cold.copy(temperature = 41.7, wind = 7.2), null)
        assertTrue(s.text(), "42°F  overcast" in s.rows())
        assertTrue(s.rows().any { "wind 7 mph" in it })

        s.submit("weather units kelvin")
        assertEquals("usage: weather units [c|f]", s.rows().last())
    }

    /* ---------- the rest ---------- */

    @Test fun usageAndCompletion() {
        val s = settled()
        s.submit("weather set")
        assertEquals("usage: weather set <city>", s.rows().last())
        assertEquals(listOf("set", "units"), s.chips("weather "))
        assertEquals(listOf("units"), s.chips("weather u"))
        assertEquals(listOf("c", "f"), s.chips("weather units "))
        assertEquals(emptyList<String>(), s.chips("weather set ber"))
        assertTrue("weather" in s.chips("wea")!!)
    }

    @Test fun withoutAServiceItSaysSo() {
        val s = Shell(phone, store, vault) { time }.also { it.start() }
        s.submit("weather")
        assertEquals("weather: not available here", s.rows().last())
    }

    @Test fun theWeatherAppIsStillReachable() {
        val s = settled()
        s.submit("open weather")
        assertEquals(listOf("weather"), phone.launched)
    }

    @Test fun aSavedCityThatCannotBeReadCountsAsNone() {
        store.put("weather_place", "Berlin\nGermany\n52.5")
        val s = shell()
        s.submit("weather")
        assertTrue("No city is set yet" in s.text())
        store.put("weather_place", "Berlin\n\nGermany\nDE\nnorth\n13.4")
        s.submit("weather")
        assertTrue(weather.asked.isEmpty())
    }

    @Test fun aCityIsNamedBrieflyButSoItCanBeToldFromItsNamesakes() {
        assertEquals("a region that repeats the name is left out", "berlin, germany", berlin.label)
        assertEquals("springfield, illinois, us", illinois.label)
        assertEquals("new york, united states", Place("New York", "New York", "United States", "US", 40.7, -74.0).label)
        assertEquals("mexico city, mexico", Place("Mexico City", "Mexico", "Mexico", "MX", 19.4, -99.1).label)
        assertEquals("paris, île-de-france, fr", Place("Paris", "Île-de-France", "France", "FR", 48.85, 2.35).label)
        assertEquals("paris, texas, united states", Place("Paris", "Texas", "United States", null, 33.66, -95.55).label)
        assertEquals("monaco", Place("Monaco", null, null, null, 43.73, 7.42).label)
    }

    @Test fun aLabelTypedBackFindsTheSamePlace() {
        val paris = Place("Paris", "Île-de-France", "France", "FR", 48.85, 2.35)
        val texas = Place("Paris", "Texas", "United States", "US", 33.66, -95.55)
        val s = settled()
        for (wanted in listOf(texas, paris)) {
            s.submit("weather ${wanted.label}")
            assertEquals("paris", weather.searches.last())
            weather.found!!(listOf(paris, texas), null)
            assertEquals(wanted, weather.asked.last().first)
            weather.told!!(cold, null)
        }
    }

    @Test fun everyKnownSkyFitsItsColumn() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in codes) {
            val words = describeWeather(code)
            assertTrue("$code: $words", words.length in 3..13 && !words.startsWith("code"))
        }
        assertEquals("code 42", describeWeather(42))
    }

    @Test fun weekdaysComeFromTheDate() {
        assertEquals("wed", weekdayOf("2026-11-18"))
        assertEquals("sun", weekdayOf("2026-11-22"))
        assertEquals("thu", weekdayOf("2024-02-29"))
        assertEquals("what is not a date is left alone", "soon", weekdayOf("soon"))
        assertEquals("2026-13-01", weekdayOf("2026-13-01"))
    }
}
