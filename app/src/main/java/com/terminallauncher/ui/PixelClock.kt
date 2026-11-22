package com.terminallauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private val GLYPHS = mapOf(
    '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
    '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001001001001",
    '8' to "111101111101111", '9' to "111101111001111", ':' to "01010",
)

const val CLOCK_COLUMNS = 17
const val CLOCK_ROWS = 5

/** The gap between two cells of the pixel clock, as a share of a cell's side. */
const val CLOCK_GAP = 0.18f

/** HH:MM as a 17 x 5 grid of lit cells, row by row. */
fun clockCells(text: String): List<Boolean> {
    val rows = List(CLOCK_ROWS) { ArrayList<Boolean>() }
    text.forEachIndexed { i, ch ->
        val glyph = GLYPHS.getValue(ch)
        val width = glyph.length / CLOCK_ROWS
        for (r in 0 until CLOCK_ROWS) {
            if (i > 0) rows[r] += false
            for (c in 0 until width) rows[r] += glyph[r * width + c] == '1'
        }
    }
    return rows.flatten()
}

/** How wide and tall the pixel clock is, in pixels, for cells of side [cell]. */
fun clockSize(cell: Float): Size {
    val gap = cell * CLOCK_GAP
    return Size(cell * CLOCK_COLUMNS + gap * (CLOCK_COLUMNS - 1), cell * CLOCK_ROWS + gap * (CLOCK_ROWS - 1))
}

/** Draws the pixel clock. [alphaOf] can fade single cells, which is how the lock screen dissolves. */
fun DrawScope.drawPixelClock(
    cells: List<Boolean>,
    topLeft: Offset,
    cell: Float,
    color: Color,
    alphaOf: (Int) -> Float = { 1f },
) {
    val step = cell * (1 + CLOCK_GAP)
    cells.forEachIndexed { i, on ->
        if (!on) return@forEachIndexed
        val alpha = alphaOf(i)
        if (alpha <= 0f) return@forEachIndexed
        drawRect(
            color = color,
            topLeft = Offset(topLeft.x + (i % CLOCK_COLUMNS) * step, topLeft.y + (i / CLOCK_COLUMNS) * step),
            size = Size(cell, cell),
            alpha = alpha,
        )
    }
}
