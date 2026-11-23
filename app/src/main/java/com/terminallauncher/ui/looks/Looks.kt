package com.terminallauncher.ui.looks

import com.terminallauncher.ui.Palette
import com.terminallauncher.ui.paletteFor

/** The plain terminal: every default of [Look], in whichever color theme is chosen. */
object Stock : Look() {
    override val id = "stock"
    override fun palette(theme: String): Palette = paletteFor(theme)
}

/** Every look that is drawn its own way. A name the shell knows and this list does not is drawn as stock. */
private val LOOKS: Map<String, Look> = listOf<Look>(
    Stock,
    Phosphor,
    Aurora,
    Teletype,
    Neon,
    Eink,
    Hud,
    Sumi,
    Bbs,
    Brutal,
    Blueprint,
    DotMatrix,
).associateBy { it.id }

fun lookFor(id: String): Look = LOOKS[id] ?: Stock
