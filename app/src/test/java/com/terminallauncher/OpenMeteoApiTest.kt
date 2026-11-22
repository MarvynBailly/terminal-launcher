package com.terminallauncher

import com.terminallauncher.shell.Place
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Reading what open-meteo.com sends. The two long answers below are ones the service really gave,
 * with the list of postcodes cut short.
 */
class OpenMeteoApiTest {
    private val locale = Locale.getDefault()

    @After fun restore() = Locale.setDefault(locale)

    private val search = """
        {"results":[
         {"id":4409896,"name":"Springfield","latitude":37.21533,"longitude":-93.29824,"elevation":396.0,
          "feature_code":"PPLA2","country_code":"US","admin1_id":4398678,"timezone":"America/Chicago",
          "population":170188,"postcodes":["65801","65802"],"country_id":6252001,"country":"United States",
          "admin1":"Missouri","admin2":"Greene","admin3":"Springfield Township"},
         {"id":4250542,"name":"Springfield","latitude":39.80172,"longitude":-89.64371,"elevation":182.0,
          "feature_code":"PPLA","country_code":"US","timezone":"America/Chicago","population":114394,
          "country_id":6252001,"country":"United States","admin1":"Illinois","admin2":"Sangamon"}
        ],"generationtime_ms":0.6}
    """.trimIndent()

    private val forecast = """
        {"latitude":52.512604,"longitude":13.419517,"generationtime_ms":0.52,"utc_offset_seconds":3600,
         "timezone":"Europe/Berlin","timezone_abbreviation":"GMT+1","elevation":38.0,
         "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°F","apparent_temperature":"°F",
          "relative_humidity_2m":"%","weather_code":"wmo code","wind_speed_10m":"mp/h"},
         "current":{"time":"2026-11-22T15:00","interval":900,"temperature_2m":40.9,"apparent_temperature":34.2,
          "relative_humidity_2m":73,"weather_code":3,"wind_speed_10m":7.1},
         "daily_units":{"time":"iso8601","weather_code":"wmo code","temperature_2m_max":"°F",
          "temperature_2m_min":"°F","precipitation_probability_max":"%"},
         "daily":{"time":["2026-11-22","2026-11-23","2026-11-24","2026-11-25","2026-11-26"],
          "weather_code":[80,80,77,3,3],
          "temperature_2m_max":[42.1,39.0,36.6,34.4,33.6],
          "temperature_2m_min":[35.8,31.8,32.3,29.5,28.5],
          "precipitation_probability_max":[88,23,18,2,8]}}
    """.trimIndent()

    /* ---------- what is asked ---------- */

    @Test fun aCityNameIsMadeSafeForAnAddress() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo+%26+x%3D1&count=10&language=en&format=json",
            OpenMeteoApi.searchUrl("São Paulo & x=1"),
        )
    }

    @Test fun aPositionIsRoundedAndNeverWrittenWithAComma() {
        Locale.setDefault(Locale.GERMANY)           // where 52.52 is written 52,52
        val berlin = Place("Berlin", null, "Germany", "DE", 52.52437, 13.41053)
        val metric = OpenMeteoApi.forecastUrl(berlin, imperial = false)
        assertTrue(metric, metric.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current="))
        assertTrue("the service's own units are metric", "unit" !in metric)
        assertTrue(metric.endsWith("&timezone=auto&forecast_days=5"))

        val south = Place("Ushuaia", null, "Argentina", "AR", -54.8, -68.30295)
        val imperial = OpenMeteoApi.forecastUrl(south, imperial = true)
        assertTrue(imperial, "latitude=-54.80&longitude=-68.30&" in imperial)
        assertTrue(imperial.endsWith("&temperature_unit=fahrenheit&wind_speed_unit=mph"))
    }

    /* ---------- finding a city ---------- */

    @Test fun citiesAreReadInTheOrderGiven() {
        val found = OpenMeteoApi.places(search)
        assertEquals(
            listOf(
                Place("Springfield", "Missouri", "United States", "US", 37.21533, -93.29824),
                Place("Springfield", "Illinois", "United States", "US", 39.80172, -89.64371),
            ),
            found,
        )
        assertEquals("springfield, missouri, us", found[0].label)
    }

    @Test fun noSuchCityIsAnEmptyListNotAnError() {
        assertEquals(emptyList<Place>(), OpenMeteoApi.places("""{"generationtime_ms":0.069737434}"""))
        assertEquals(emptyList<Place>(), OpenMeteoApi.places("""{"results":[]}"""))
    }

    @Test fun aCityWithNoRegionOrCountryIsStillACity() {
        val found = OpenMeteoApi.places("""{"results":[{"name":"Monaco","latitude":43.73,"longitude":7.42,"admin1":null}]}""")
        assertEquals(listOf(Place("Monaco", null, null, null, 43.73, 7.42)), found)
        assertEquals("monaco", found[0].label)
    }

    @Test fun namesAreCleanedBeforeTheyReachTheScreen() {
        val found = OpenMeteoApi.places(
            """{"results":[{"name":"  Evil\n\u001b[2Jtown\t ","latitude":1,"longitude":2,"country":"${"x".repeat(500)}","country_code":"LONGCODE"}]}""",
        )
        assertEquals("Evil[2Jtown", found[0].name)
        assertEquals(60, found[0].country!!.length)
        assertEquals("LON", found[0].countryCode)
    }

    @Test fun entriesThatMakeNoSenseAreLeftOut() {
        val found = OpenMeteoApi.places(
            """
            {"results":[
              {"name":"North of north","latitude":91,"longitude":0},
              {"name":"East of east","latitude":0,"longitude":180.5},
              {"name":"Nowhere","longitude":0},
              {"name":"Text","latitude":"north","longitude":0},
              {"name":"","latitude":1,"longitude":1},
              {"name":7,"latitude":1,"longitude":1},
              {"latitude":1,"longitude":1},
              "not an entry",
              {"name":"Fine","latitude":-90,"longitude":180}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(Place("Fine", null, null, null, -90.0, 180.0)), found)
    }

    @Test fun aLongListIsCutShort() {
        val many = (1..50).joinToString(",") { """{"name":"Town $it","latitude":1,"longitude":1}""" }
        assertEquals(20, OpenMeteoApi.places("""{"results":[$many]}""").size)
    }

    /* ---------- a forecast ---------- */

    @Test fun aForecastIsRead() {
        val f = OpenMeteoApi.forecast(forecast)
        assertEquals(40.9, f.temperature, 0.0)
        assertEquals(34.2, f.feelsLike, 0.0)
        assertEquals(73, f.humidity)
        assertEquals(7.1, f.wind, 0.0)
        assertEquals(3, f.code)
        assertEquals(5, f.days.size)
        val first = f.days[0]
        assertEquals("2026-11-22", first.date)
        assertEquals(80, first.code)
        assertEquals(42.1, first.high, 0.0)
        assertEquals(35.8, first.low, 0.0)
        assertEquals(88, first.wet)
        assertEquals(listOf(88, 23, 18, 2, 8), f.days.map { it.wet })
    }

    @Test fun aDayWithNoChanceOfRainGivenHasNone() {
        val some = forecast.replace("[88,23,18,2,8]", "[88,null,18]")
        assertEquals(listOf(88, null, 18, null, null), OpenMeteoApi.forecast(some).days.map { it.wet })
        val none = forecast.replace(Regex(""",\s*"precipitation_probability_max":\[[^\]]*]"""), "")
        assertTrue("the test took the list out", "precipitation_probability_max\":[" !in none)
        assertEquals(5, OpenMeteoApi.forecast(none).days.size)
        assertNull(OpenMeteoApi.forecast(none).days[0].wet)
        val odd = forecast.replace("[88,23,18,2,8]", "[250,-4,18,2,8]")
        assertEquals("kept within 0 to 100", listOf(100, 0), OpenMeteoApi.forecast(odd).days.take(2).map { it.wet })
    }

    @Test fun onlyNowIsEnough() {
        val f = OpenMeteoApi.forecast(
            """{"current":{"temperature_2m":-3,"apparent_temperature":-9.5,"relative_humidity_2m":140,"weather_code":71,"wind_speed_10m":20}}""",
        )
        assertEquals(-3.0, f.temperature, 0.0)
        assertEquals("humidity is kept within 0 to 100", 100, f.humidity)
        assertTrue(f.days.isEmpty())
    }

    @Test fun noMoreThanAWeekIsKept() {
        val days = (10..24).map { "\"2026-11-$it\"" }
        val long = """{"current":{"temperature_2m":1,"apparent_temperature":1,"relative_humidity_2m":50,"weather_code":0,"wind_speed_10m":1},
            "daily":{"time":$days,"weather_code":${days.map { 1 }},"temperature_2m_max":${days.map { 5 }},"temperature_2m_min":${days.map { 1 }}}}"""
        assertEquals(7, OpenMeteoApi.forecast(long).days.size)
    }

    private fun refused(json: String) {
        try {
            val f = OpenMeteoApi.forecast(json)
            throw AssertionError("read as $f")
        } catch (e: JSONException) {
            // expected
        }
    }

    @Test fun answersThatCannotBeAForecastAreRefused() {
        refused("")
        refused("not json")
        refused("[]")
        refused("""{"reason":"Latitude must be in range of -90 to 90°. Given: 999.0.","error":true}""")
        refused(forecast.replace("\"temperature_2m\":40.9,", ""))
        refused(forecast.replace("\"temperature_2m\":40.9", "\"temperature_2m\":\"warm\""))
        refused(forecast.replace("\"temperature_2m\":40.9", "\"temperature_2m\":null"))
        refused(forecast.replace("\"temperature_2m\":40.9", "\"temperature_2m\":\"NaN\""))
        refused(forecast.replace("\"wind_speed_10m\":7.1", "\"wind_speed_10m\":\"Infinity\""))
        refused(forecast.replace("\"2026-11-23\"", "\"tomorrow\\n\""))
        refused(forecast.replace("[42.1,39.0,36.6,34.4,33.6]", "[42.1,39.0]"))
        refused(forecast.replace("[80,80,77,3,3]", "[80,\"rain\",77,3,3]"))
    }

    @Test fun aSearchAnswerThatIsNotJsonIsRefused() {
        for (bad in listOf("", "<html>busy</html>", "[1,2]")) {
            try {
                val found = OpenMeteoApi.places(bad)
                throw AssertionError("read as $found")
            } catch (e: JSONException) {
                // expected
            }
        }
    }
}
