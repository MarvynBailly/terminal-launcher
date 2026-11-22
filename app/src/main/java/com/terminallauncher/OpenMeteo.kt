package com.terminallauncher

import android.os.Handler
import android.os.Looper
import com.terminallauncher.shell.DayForecast
import com.terminallauncher.shell.Forecast
import com.terminallauncher.shell.Place
import com.terminallauncher.shell.Weather
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Forecasts from open-meteo.com, which needs no account or key.
 *
 * Two things leave the phone, and only when `weather` is run: the name of a city, to find where it
 * is, and that city's position, rounded to about a kilometre, to get its forecast. Nothing says
 * who is asking.
 */
class OpenMeteo : Weather {
    override val source = HOST

    private val main = Handler(Looper.getMainLooper())

    override fun find(name: String, done: (places: List<Place>?, problem: String?) -> Unit) =
        ask(OpenMeteoApi.searchUrl(name), OpenMeteoApi::places, done)

    override fun forecast(place: Place, imperial: Boolean, done: (forecast: Forecast?, problem: String?) -> Unit) =
        ask(OpenMeteoApi.forecastUrl(place, imperial), OpenMeteoApi::forecast, done)

    /**
     * Fetches [url] off the main thread and hands [done] what [read] makes of it, back on the main
     * thread. [done] is called once, and no later than [DEADLINE_MS] from now: a host with several
     * addresses is tried at each in turn, and the timeouts of all of them together are too long to
     * sit through.
     */
    private fun <T : Any> ask(url: String, read: (String) -> T, done: (T?, String?) -> Unit) {
        var settled = false                         // read and written on the main thread only
        fun settle(value: T?, problem: String?) {
            if (settled) return
            settled = true
            done(value, problem)
        }
        val tooLate = Runnable { settle(null, "$HOST did not answer in time") }
        main.postDelayed(tooLate, DEADLINE_MS)
        thread(name = "weather", isDaemon = true) {
            var value: T? = null
            var problem: String? = null
            try {
                value = read(get(url))
            } catch (e: Exception) {
                problem = explain(e)
            }
            main.post {
                main.removeCallbacks(tooLate)
                settle(value, problem)
            }
        }
    }

    private class Refused(val status: Int) : IOException("HTTP $status")

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            // Android's own value names the phone's model and build. This names only the app.
            connection.setRequestProperty("User-Agent", "terminal-launcher")
            val status = connection.responseCode
            if (status != 200) throw Refused(status)
            return connection.inputStream.use(::readAll)
        } finally {
            connection.disconnect()
        }
    }

    /** An answer is a few kilobytes. One that goes on and on is not read to the end. */
    private fun readAll(stream: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            bytes.write(chunk, 0, n)
            if (bytes.size() > MAX_ANSWER) throw IOException("answer too long")
        }
        return bytes.toString("UTF-8")
    }

    private fun explain(e: Exception): String = when (e) {
        is UnknownHostException -> "could not reach $HOST. Is the phone online?"
        is SocketTimeoutException -> "$HOST did not answer in time"
        is Refused -> "$HOST answered with an error (${e.status})"
        is IOException -> "could not reach $HOST"
        else -> "$HOST sent an answer this version cannot read"
    }

    private companion object {
        const val HOST = "open-meteo.com"
        const val TIMEOUT_MS = 8000
        const val DEADLINE_MS = 12_000L
        const val MAX_ANSWER = 512 * 1024
    }
}

/**
 * The addresses open-meteo.com is asked at, and how its answers are read. Nothing here touches the
 * network or Android, so it is tested on a desktop JVM against answers the service really gave.
 *
 * The answers are checked as they are read: they come from outside, and what they hold ends up on
 * screen and in the saved settings.
 */
internal object OpenMeteoApi {
    private const val MAX_DAYS = 7
    private const val MAX_NAME = 60

    fun searchUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=10&language=en&format=json"

    fun forecastUrl(place: Place, imperial: Boolean): String {
        fun rounded(v: Double) = String.format(Locale.US, "%.2f", v)
        return "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${rounded(place.latitude)}&longitude=${rounded(place.longitude)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=5" +
            if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
    }

    /** The towns in a search answer. One with no results at all is an empty list, not an error. */
    fun places(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until minOf(results.length(), 20)) {
            val o = results.optJSONObject(i) ?: continue
            val name = text(o, "name") ?: continue
            val latitude = o.optDouble("latitude")
            val longitude = o.optDouble("longitude")
            if (!(latitude >= -90.0 && latitude <= 90.0) || !(longitude >= -180.0 && longitude <= 180.0)) continue
            out += Place(name, text(o, "admin1"), text(o, "country"), text(o, "country_code")?.take(3), latitude, longitude)
        }
        return out
    }

    fun forecast(json: String): Forecast {
        val root = JSONObject(json)
        val now = root.getJSONObject("current")
        val daily = root.optJSONObject("daily")
        val days = ArrayList<DayForecast>()
        if (daily != null) {
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            val wet = daily.optJSONArray("precipitation_probability_max")
            for (i in 0 until minOf(dates.length(), MAX_DAYS)) {
                val date = dates.getString(i)
                if (!DATE.matches(date)) throw JSONException("not a date")
                days += DayForecast(date, codes.getInt(i), number(highs, i), number(lows, i), percent(wet, i))
            }
        }
        return Forecast(
            temperature = number(now, "temperature_2m"),
            feelsLike = number(now, "apparent_temperature"),
            humidity = now.getInt("relative_humidity_2m").coerceIn(0, 100),
            wind = number(now, "wind_speed_10m"),
            code = now.getInt("weather_code"),
            days = days,
        )
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** A name as it may be shown: one line, no control characters, not endless. null if nothing is left. */
    private fun text(o: JSONObject, key: String): String? {
        if (o.isNull(key)) return null
        val raw = o.opt(key) as? String ?: return null
        return raw.filter { !it.isISOControl() }.trim().take(MAX_NAME).ifEmpty { null }
    }

    private fun finite(v: Double): Double {
        if (v.isNaN() || v.isInfinite()) throw JSONException("not a number")
        return v
    }

    private fun number(o: JSONObject, key: String): Double = finite(o.getDouble(key))
    private fun number(a: JSONArray, i: Int): Double = finite(a.getDouble(i))

    /** A chance from 0 to 100, or null where the service gives none for that day. */
    private fun percent(a: JSONArray?, i: Int): Int? {
        if (a == null || i >= a.length() || a.isNull(i)) return null
        val v = a.optDouble(i)
        return if (v.isNaN() || v.isInfinite()) null else v.toInt().coerceIn(0, 100)
    }
}
