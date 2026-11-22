package com.terminallauncher.shell

import java.util.Calendar

/**
 * A town a forecast can be asked for. [region] is its state or province and [countryCode] the two
 * letters of its country, where the service that found it says so.
 */
data class Place(
    val name: String,
    val region: String?,
    val country: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
) {
    /**
     * As it is shown, and as it can be typed back to mean this place and no other of its name:
     * `springfield, illinois, us`. A region that only repeats the town's name, as Berlin's does, is
     * left out and the country is then written in full: `berlin, germany`.
     */
    val label: String
        get() {
            val town = name.lowercase()
            val area = region?.lowercase()?.takeIf { town !in it && it !in town }
            val land = if (area != null) countryCode ?: country else country
            return listOfNotNull(town, area, land?.lowercase()).joinToString(", ")
        }
}

/**
 * One day of a forecast. [date] is the day in the place's own calendar, written 2026-11-22. [code] is
 * a WMO weather code, and [wet] the chance of rain or snow from 0 to 100, where one is given.
 */
data class DayForecast(val date: String, val code: Int, val high: Double, val low: Double, val wet: Int?)

/** The weather now and over the coming days, in the units it was asked for. */
data class Forecast(
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val wind: Double,
    val code: Int,
    val days: List<DayForecast>,
)

/**
 * Where forecasts come from. Both calls return at once. The answer comes later, on the thread the
 * shell runs on: the result, or null with a few words on why there is none.
 */
interface Weather {
    /** The service, by the name the user is told. */
    val source: String

    /** Towns called [name], the likeliest first. An empty list means there is no such town. */
    fun find(name: String, done: (places: List<Place>?, problem: String?) -> Unit)

    /** [imperial] asks for Fahrenheit and miles an hour, otherwise Celsius and kilometres an hour. */
    fun forecast(place: Place, imperial: Boolean, done: (forecast: Forecast?, problem: String?) -> Unit)
}

/** A WMO weather code in words, none longer than 13 characters so a forecast's columns line up. */
fun describeWeather(code: Int): String = when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "icy drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71 -> "light snow"
    73 -> "snow"
    75 -> "heavy snow"
    77 -> "snow grains"
    80 -> "light showers"
    81 -> "showers"
    82 -> "heavy showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "hail storm"
    else -> "code $code"
}

/** The weekday of a date written 2026-11-22, as mon. Anything else comes back as it is. */
fun weekdayOf(date: String): String {
    val parts = date.split('-').map { it.toIntOrNull() }
    val (year, month, day) = if (parts.size == 3 && parts.none { it == null }) parts.map { it!! } else return date
    if (month !in 1..12 || day !in 1..31) return date
    // Noon, so no clock change can push the day over its edge.
    val noon = Calendar.getInstance().apply { clear(); set(year, month - 1, day, 12, 0) }
    return Fmt.weekday(noon.timeInMillis).lowercase()
}
