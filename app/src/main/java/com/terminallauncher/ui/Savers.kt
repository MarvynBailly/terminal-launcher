package com.terminallauncher.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.terminallauncher.R
import kotlin.math.pow
import kotlin.random.Random

/**
 * One of the lock screen's screensavers, filling whatever space it is given. [time] is the clock
 * text, HH:MM, for the ones that show it. Each keeps its own state and is stepped by the frame clock,
 * so it only costs anything while it is on screen.
 */
@Composable
fun SaverView(kind: String, palette: Palette, time: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mono = remember { ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular) ?: Typeface.MONOSPACE }
    val saver = remember(kind) {
        when (kind) {
            "rain" -> Rain(mono)
            "stars" -> Stars()
            "life" -> Life()
            else -> Bounce()
        }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(saver) {
        // Redraw only as often as the picture changes: the ones that move in ticks need far fewer frames.
        while (true) withFrameNanos { if (it - frame >= saver.frameNanos) frame = it }
    }
    Canvas(modifier.fillMaxSize()) {
        saver.advance(frame, size, density)
        with(saver) { draw(palette, time) }
    }
}

private abstract class Saver {
    /** The least time between two redraws. 0 redraws on every frame. */
    open val frameNanos: Long = 0L
    private var last = 0L

    /** Moves the picture on by the time since the last frame, capped so a long pause is not one big jump. */
    fun advance(frameNanos: Long, size: Size, density: Float) {
        val dt = if (last == 0L || frameNanos <= last) 0f else ((frameNanos - last) / 1e9f).coerceAtMost(0.1f)
        last = frameNanos
        step(dt, size, density)
    }

    abstract fun step(dt: Float, size: Size, density: Float)
    abstract fun DrawScope.draw(palette: Palette, time: String)
}

/** Draws the pixel clock in the middle on a plate of the background color, so it reads over anything. */
private fun DrawScope.centerClock(time: String, palette: Palette) {
    val cell = (size.width * 0.032f).coerceAtMost(13f * density)
    val clock = clockSize(cell)
    val at = Offset((size.width - clock.width) / 2f, (size.height - clock.height) / 2f)
    val pad = cell * 2.2f
    drawRect(palette.bg, Offset(at.x - pad, at.y - pad), Size(clock.width + pad * 2, clock.height + pad * 2))
    drawPixelClock(clockCells(time), at, cell, palette.fg)
}

/* ---------------- code rain ---------------- */

private class Rain(private val typeface: Typeface) : Saver() {
    private val glyphs = "01<>[]{}/\\|=+-*#$%&?:;".toCharArray()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val one = CharArray(1)
    private var columns = 0
    private var rows = 0
    private var cellW = 0f
    private var cellH = 0f
    private var head = IntArray(0)

    /** Which glyph sits in each cell, the tick it was drawn on, and whether it was a bright one. */
    private var glyph = CharArray(0)
    private var drawnAt = IntArray(0)
    private var hot = BooleanArray(0)
    private var tick = 0
    private var owed = 0f
    override val frameNanos = 55_000_000L

    private companion object {
        const val TICK_S = 0.062f
        const val FADE = 0.86f           // what is left of a glyph's brightness after each tick
        const val TRAIL = 22             // ticks after which a glyph is no longer drawn
    }

    private fun layout(size: Size, density: Float) {
        paint.typeface = typeface
        paint.textSize = 14f * density
        cellW = paint.textSize * 0.8f
        cellH = paint.textSize * 1.15f
        columns = (size.width / cellW).toInt() + 1
        rows = (size.height / cellH).toInt() + 2
        head = IntArray(columns) { -Random.nextInt(40) }
        glyph = CharArray(columns * rows) { ' ' }
        drawnAt = IntArray(columns * rows) { -TRAIL * 2 }
        hot = BooleanArray(columns * rows)
    }

    override fun step(dt: Float, size: Size, density: Float) {
        if (columns == 0 || columns != (size.width / (14f * density * 0.8f)).toInt() + 1) layout(size, density)
        owed += dt
        while (owed >= TICK_S) {
            owed -= TICK_S
            tick++
            for (c in 0 until columns) {
                val r = head[c]
                if (r in 0 until rows) {
                    val i = c * rows + r
                    glyph[i] = glyphs[Random.nextInt(glyphs.size)]
                    drawnAt[i] = tick
                    hot[i] = Random.nextFloat() < 0.08f
                }
                // past the bottom, a column waits a random while before it falls again
                head[c] = if (r >= rows && Random.nextFloat() > 0.93f) 0 else r + 1
            }
        }
    }

    override fun DrawScope.draw(palette: Palette, time: String) {
        val green = palette.ok.toArgb()
        val bright = (if (palette.light) palette.fg else Color.White).toArgb()
        val canvas = drawContext.canvas.nativeCanvas
        for (c in 0 until columns) {
            for (r in 0 until rows) {
                val i = c * rows + r
                val age = tick - drawnAt[i]
                if (age >= TRAIL) continue
                paint.color = if (hot[i] && age < 6) bright else green
                paint.alpha = (255 * FADE.pow(age)).toInt()
                one[0] = glyph[i]
                canvas.drawText(one, 0, 1, c * cellW, (r + 1) * cellH, paint)
            }
        }
        centerClock(time, palette)
    }
}

/* ---------------- starfield ---------------- */

private class Stars : Saver() {
    private val count = 240
    private val x = FloatArray(count) { Random.nextFloat() * 2f - 1f }
    private val y = FloatArray(count) { Random.nextFloat() * 2f - 1f }

    /** How far away each star is: 1 is the far end, 0 is the screen. */
    private val z = FloatArray(count) { Random.nextFloat() * 0.98f + 0.02f }

    private companion object {
        const val SPEED = 0.38f          // depth covered in a second
        const val TAIL = 0.05f           // how much depth a star's streak spans, whatever the frame rate
    }

    override fun step(dt: Float, size: Size, density: Float) {
        for (i in 0 until count) {
            z[i] -= SPEED * dt
            if (z[i] <= 0.03f) {
                x[i] = Random.nextFloat() * 2f - 1f
                y[i] = Random.nextFloat() * 2f - 1f
                z[i] = 1f
            }
        }
    }

    override fun DrawScope.draw(palette: Palette, time: String) {
        // Scaled to the screen each way, so every star starts in view and flies out past an edge.
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (i in 0 until count) {
            val tail = (z[i] + TAIL).coerceAtMost(1f)
            drawLine(
                color = palette.fg,
                start = Offset(cx + x[i] / tail * cx, cy + y[i] / tail * cy),
                end = Offset(cx + x[i] / z[i] * cx, cy + y[i] / z[i] * cy),
                strokeWidth = (1.2f + (1f - z[i]) * 2.4f) * density,
                cap = StrokeCap.Round,
                alpha = (1.25f - z[i]).coerceIn(0f, 1f),
            )
        }
    }
}

/* ---------------- game of life ---------------- */

private class Life : Saver() {
    private var columns = 0
    private var rows = 0
    private var cell = 0f
    private var grid = BooleanArray(0)
    private var previous = BooleanArray(0)
    private var owed = 0f
    private var generation = 0
    private var stale = 0
    override val frameNanos = 100_000_000L

    private companion object {
        const val TICK_S = 0.11f
    }

    private fun seed(size: Size, density: Float) {
        cell = 10f * density
        columns = (size.width / cell).toInt().coerceAtLeast(4)
        rows = (size.height / cell).toInt().coerceAtLeast(4)
        grid = BooleanArray(columns * rows) { Random.nextFloat() < 0.3f }
        previous = BooleanArray(columns * rows)
        generation = 0
        stale = 0
    }

    override fun step(dt: Float, size: Size, density: Float) {
        if (columns == 0 || columns != (size.width / (10f * density)).toInt().coerceAtLeast(4)) seed(size, density)
        owed += dt
        while (owed >= TICK_S) {
            owed -= TICK_S
            val next = BooleanArray(grid.size)
            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    var around = 0
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if ((dr != 0 || dc != 0) && grid[(r + dr + rows) % rows * columns + (c + dc + columns) % columns]) around++
                        }
                    }
                    next[r * columns + c] = around == 3 || (grid[r * columns + c] && around == 2)
                }
            }
            // A board that has stopped changing, or only blinks, has run its course. So has a very old one.
            stale = if (next.contentEquals(grid) || next.contentEquals(previous)) stale + 1 else 0
            previous = grid
            grid = next
            generation++
            if (stale > 25 || generation > 1500) seed(size, density)
        }
    }

    override fun DrawScope.draw(palette: Palette, time: String) {
        val inset = cell * 0.12f
        val side = Size(cell - inset * 2, cell - inset * 2)
        // centered, so the leftover margin is the same on both sides
        val left = (size.width - columns * cell) / 2f
        val top = (size.height - rows * cell) / 2f
        for (i in grid.indices) {
            if (grid[i]) drawRect(palette.ok, Offset(left + (i % columns) * cell + inset, top + (i / columns) * cell + inset), side)
        }
    }
}

/* ---------------- bouncing clock ---------------- */

private class Bounce : Saver() {
    private var x = -1f
    private var y = 0f
    private var vx = 58f
    private var vy = 45f
    private var hits = 0

    override fun step(dt: Float, size: Size, density: Float) {
        val clock = clockSize(cellFor(size, density))
        if (x < 0f) {
            x = (size.width - clock.width) * Random.nextFloat()
            y = (size.height - clock.height) * Random.nextFloat()
        }
        x += vx * density * dt
        y += vy * density * dt
        val maxX = (size.width - clock.width).coerceAtLeast(0f)
        val maxY = (size.height - clock.height).coerceAtLeast(0f)
        var hit = false
        if (x <= 0f || x >= maxX) {
            vx = if (x <= 0f) kotlin.math.abs(vx) else -kotlin.math.abs(vx)
            x = x.coerceIn(0f, maxX)
            hit = true
        }
        if (y <= 0f || y >= maxY) {
            vy = if (y <= 0f) kotlin.math.abs(vy) else -kotlin.math.abs(vy)
            y = y.coerceIn(0f, maxY)
            hit = true
        }
        if (hit) hits++
    }

    private fun cellFor(size: Size, density: Float) = (size.width * 0.03f).coerceAtMost(12f * density)

    override fun DrawScope.draw(palette: Palette, time: String) {
        val colors = listOf(palette.fg, palette.accent, palette.app, palette.ok, palette.err)
        drawPixelClock(clockCells(time), Offset(x.coerceAtLeast(0f), y), cellFor(size, density), colors[hits % colors.size])
    }
}
