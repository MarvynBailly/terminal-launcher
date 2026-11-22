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
        Style.MARK -> accent         // drawn inverted: this is its background
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

private val Nord = Palette(
    bg = Color(0xFF2E3440), fg = Color(0xFFD8DEE9), dim = Color(0xFF7B88A1), line = Color(0xFF434C5E),
    accent = Color(0xFFEBCB8B), app = Color(0xFF88C0D0), ok = Color(0xFFA3BE8C), err = Color(0xFFBF616A),
)
private val Gruvbox = Palette(
    bg = Color(0xFF282828), fg = Color(0xFFEBDBB2), dim = Color(0xFF928374), line = Color(0xFF3C3836),
    accent = Color(0xFFFABD2F), app = Color(0xFF83A598), ok = Color(0xFFB8BB26), err = Color(0xFFFB4934),
)
private val Dracula = Palette(
    bg = Color(0xFF282A36), fg = Color(0xFFF8F8F2), dim = Color(0xFF7B88B8), line = Color(0xFF44475A),
    accent = Color(0xFFFFB86C), app = Color(0xFF8BE9FD), ok = Color(0xFF50FA7B), err = Color(0xFFFF5555),
)
private val Solarized = Palette(
    bg = Color(0xFF002B36), fg = Color(0xFFA9B8B8), dim = Color(0xFF657B83), line = Color(0xFF073642),
    accent = Color(0xFFB58900), app = Color(0xFF2AA198), ok = Color(0xFF859900), err = Color(0xFFDC322F),
)
private val Synthwave = Palette(
    bg = Color(0xFF1A1033), fg = Color(0xFFF4E9FF), dim = Color(0xFF8676B8), line = Color(0xFF34294F),
    accent = Color(0xFFFF7EDB), app = Color(0xFF36F9F6), ok = Color(0xFF72F1B8), err = Color(0xFFFE4450),
)
private val Paper = Palette(
    bg = Color(0xFFF4F1E8), fg = Color(0xFF2B2B28), dim = Color(0xFF857F70), line = Color(0xFFD8D2C2),
    accent = Color(0xFFA3470F), app = Color(0xFF1F55AB), ok = Color(0xFF2F6F3A), err = Color(0xFFB3261E),
    light = true,
)

fun paletteFor(theme: String): Palette = when (theme) {
    "green" -> Green
    "amber" -> Amber
    "eink" -> Eink
    "nord" -> Nord
    "gruvbox" -> Gruvbox
    "dracula" -> Dracula
    "solarized" -> Solarized
    "synthwave" -> Synthwave
    "paper" -> Paper
    else -> Default
}
