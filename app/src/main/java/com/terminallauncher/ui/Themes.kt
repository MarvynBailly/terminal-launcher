package com.terminallauncher.ui

import androidx.compose.ui.graphics.Color
import com.terminallauncher.shell.Style

/** One terminal color theme. [light] tells the system to draw dark navigation-bar icons. */
data class Palette(
    val bg: Color,
    val fg: Color,
    val dim: Color,
    val line: Color,
    val accent: Color,
    val app: Color,
    val ok: Color,
    val err: Color,
    val light: Boolean = false,
) {
    fun of(style: Style): Color = when (style) {
        Style.NORMAL -> fg
        Style.DIM -> dim
        Style.ACCENT -> accent
        Style.APP -> app
        Style.OK -> ok
        Style.ERR -> err
    }
}

// True black by default: on an OLED screen those pixels are off.
private val Default = Palette(
    bg = Color(0xFF000000), fg = Color(0xFFD6D8D2), dim = Color(0xFF7B8086), line = Color(0xFF2B2E33),
    accent = Color(0xFFF0B45E), app = Color(0xFF8FBAF0), ok = Color(0xFFA3D18B), err = Color(0xFFF58675),
)
private val Green = Palette(
    bg = Color(0xFF010A04), fg = Color(0xFF86E8A0), dim = Color(0xFF4A9460), line = Color(0xFF17472A),
    accent = Color(0xFFD2FFDC), app = Color(0xFFB4F5C4), ok = Color(0xFFD2FFDC), err = Color(0xFFFFB3A3),
)
private val Amber = Palette(
    bg = Color(0xFF0B0700), fg = Color(0xFFFFB84D), dim = Color(0xFFA3722F), line = Color(0xFF4D3614),
    accent = Color(0xFFFFE3B0), app = Color(0xFFFFD591), ok = Color(0xFFFFE3B0), err = Color(0xFFFF9478),
)
private val Eink = Palette(
    bg = Color(0xFFECEEEC), fg = Color(0xFF151716), dim = Color(0xFF666B68), line = Color(0xFFC4C8C5),
    accent = Color(0xFF0D6A5C), app = Color(0xFF1C55AD), ok = Color(0xFF2B7634), err = Color(0xFFB0271F),
    light = true,
)

fun paletteFor(theme: String): Palette = when (theme) {
    "green" -> Green
    "amber" -> Amber
    "eink" -> Eink
    else -> Default
}
