package com.terminallauncher.ui.looks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.CLOCK_COLUMNS
import com.terminallauncher.ui.CLOCK_ROWS
import com.terminallauncher.ui.Palette
import com.terminallauncher.ui.clockCells
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ANSI: a dial-up bulletin board from 1994, in the sixteen VGA colours and nothing else, flat.
 *
 * The screen is a grid of character cells and everything sits on it: the inverse bar on top, the
 * blue panels, the frames, the gauges, the clock. VT323 has no box-drawing or shade characters,
 * so a frame is drawn lines and a shade is a dither. Two things move, and both are what a 14.4k
 * modem and a text screen do: text arrives a character at a time behind a block, and the page is
 * overwritten with rows of shade and then cleared.
 */
object Bbs : Look() {
    override val id = "bbs"

    /* ---------------- the sixteen colours ---------------- */

    private val Black = Color(0xFF000000)
    private val Blue = Color(0xFF0000AA)
    private val Green = Color(0xFF00AA00)
    private val Cyan = Color(0xFF00AAAA)
    private val Red = Color(0xFFAA0000)
    private val Magenta = Color(0xFFAA00AA)
    private val Brown = Color(0xFFAA5500)
    private val Gray = Color(0xFFAAAAAA)
    private val DarkGray = Color(0xFF555555)
    private val BrightBlue = Color(0xFF5555FF)
    private val BrightGreen = Color(0xFF55FF55)
    private val BrightCyan = Color(0xFF55FFFF)
    private val BrightRed = Color(0xFFFF5555)
    private val BrightMagenta = Color(0xFFFF55FF)
    private val Yellow = Color(0xFFFFFF55)
    private val White = Color(0xFFFFFFFF)

    /** The colours a band of shade cycles through, and the ones line noise comes in. */
    private val Bright = listOf(BrightCyan, Yellow, BrightMagenta, BrightGreen, White)
    private val Noise = Bright + BrightBlue + BrightRed

    private val Vga = Palette(
        bg = Black, fg = Gray, dim = Cyan, line = DarkGray,
        accent = Yellow, app = BrightCyan, ok = BrightGreen, err = BrightRed,
    )

    override fun palette(theme: String): Palette = Vga

    /* ---------------- type: VT323 on a grid of cells 0.4 em by 1 em ---------------- */

    private val Vt = FontFamily(Font(R.font.vt323_regular))
    override val font get() = Vt
    override val fontSize get() = 21.sp
    override val lineHeight get() = 21.sp
    override val advance get() = 0.4f

    /** Three columns: text starts in the fourth, and the frames run through the gutter. */
    override val gutter: Dp get() = 25.2.dp

    override fun span(style: Style, palette: Palette): SpanStyle =
        if (style == Style.MARK) SpanStyle(color = Black, background = Gray) else SpanStyle(color = palette.of(style))

    /* ---------------- cells, lines and shades ---------------- */

    /** A filled box with its edges on whole pixels, so cells side by side leave no seam between them. */
    private fun DrawScope.block(color: Color, left: Float, top: Float, right: Float, bottom: Float) {
        val x = left.roundToInt()
        val y = top.roundToInt()
        val w = right.roundToInt() - x
        val h = bottom.roundToInt() - y
        if (w > 0 && h > 0) drawRect(color, Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))
    }

    private fun DrawScope.block(brush: Brush, left: Float, top: Float, right: Float, bottom: Float) {
        val x = left.roundToInt()
        val y = top.roundToInt()
        val w = right.roundToInt() - x
        val h = bottom.roundToInt() - y
        if (w > 0 && h > 0) drawRect(brush, Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))
    }

    /** One dot of the screen: a quarter of a column. A single line is one dot thick. */
    private fun dot(env: LookEnv): Int = (env.cell.width / 4f).roundToInt().coerceAtLeast(1)

    /**
     * The shade characters as dither: [level] 1, 2 and 3 are light, medium and dark, a quarter, a
     * half and three quarters of the dots in [ink], the rest in [paper].
     */
    private fun shade(level: Int, ink: Color, paper: Color, dot: Int): ShaderBrush =
        tiled("bbs shade $level ${ink.toArgb()} ${paper.toArgb()} $dot", 4 * dot, 2 * dot) { pixels ->
            val across = 4 * dot
            for (i in pixels.indices) {
                val x = i % across / dot
                val y = i / across / dot
                val on = when (level) {
                    1 -> x == y * 2
                    2 -> (x + y) % 2 == 0
                    else -> x != 1 + y * 2
                }
                pixels[i] = (if (on) ink else paper).toArgb()
            }
        }

    /**
     * A box frame whose strokes run along [left] and [right] and, where given, [top] and [bottom]:
     * one stroke, or two with a gap between. A side with no edge at its end runs on to the end of
     * the canvas, where the next row's piece of the frame takes over.
     */
    private fun DrawScope.frame(color: Color, w: Int, left: Float, right: Float, top: Float?, bottom: Float?, double: Boolean) {
        val half = w / 2f
        for (inward in if (double) listOf(-w.toFloat(), w.toFloat()) else listOf(0f)) {
            val l = left + inward
            val r = right - inward
            val t = top?.plus(inward)
            val b = bottom?.minus(inward)
            val from = t?.minus(half) ?: 0f
            val to = b?.plus(half) ?: size.height
            block(color, l - half, from, l + half, to)
            block(color, r - half, from, r + half, to)
            if (t != null) block(color, l - half, t - half, r + half, t + half)
            if (b != null) block(color, l - half, b - half, r + half, b + half)
        }
    }

    /** Room around the content in pixels, so that it can be whole cells. */
    private fun Modifier.room(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f): Modifier =
        layout { measurable, constraints ->
            val x = left.roundToInt()
            val y = top.roundToInt()
            val across = x + right.roundToInt()
            val down = y + bottom.roundToInt()
            val placeable = measurable.measure(constraints.offset(-across, -down))
            layout(constraints.constrainWidth(placeable.width + across), constraints.constrainHeight(placeable.height + down)) {
                placeable.place(x, y)
            }
        }

    /*
     * A row cannot tell the shell's styles to be other colours where its ground is not black, so
     * its text is drawn through a colour matrix that maps each of the palette's colours to another
     * of the sixteen. Each row below is red, green and blue out of red, green, blue and a constant.
     */
    private fun attributes(vararg rgb: Float): Paint =
        Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(*rgb, 0f, 0f, 0f, 1f, 0f))) }

    /** On the cyan bar: black and yellow stay, and the gray of a track's title becomes black. */
    private val BarInk by lazy { attributes(1.5f, 0f, -1.5f, 0f, 0f, 0f, 1.5f, -1.5f, 0f, 0f, 0.5f, 0f, -0.5f, 0f, 0f) }

    /** What was typed: the yellow prompt becomes bright magenta and the gray words white. */
    private val EchoInk by lazy { attributes(2f, 0f, 0f, 0f, -85f, 0f, 0f, 2f, 0f, -85f, 0f, 2f, 0f, 0f, -85f) }

    /** On a blue panel: gray and bright cyan become white, cyan becomes bright cyan, the rest stay. */
    private val PanelInk by lazy { attributes(1f, 0f, 1f, 0f, -85f, -1f, 1f, 0f, 0f, 255f, 0f, 0f, 2f, 0f, -85f) }

    /** In a pop-up: gray becomes white, cyan bright cyan, and the bright cyan of its title yellow. */
    private val PopupInk by lazy { attributes(1f, 0f, 1f, 0f, -85f, 0f, 1f, 0f, 0f, 85f, 0f, 0f, -2f, 0f, 595f) }

    private fun ContentDrawScope.drawContentIn(ink: Paint) = drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), ink)
        drawContent()
        canvas.restore()
    }

    /* ---------------- parts ---------------- */

    /**
     * An inverse bar from edge to edge: black on cyan. Every label starts with the terminal's
     * name, which is set brighter and fades into the bar through the three shades.
     */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 2.dp, bottom = 10.dp)
        .drawWithContent {
            val edge = gutter.toPx()
            val ch = env.cell.width
            block(Cyan, -edge, 0f, size.width + edge, size.height)
            block(BrightCyan, -edge, 0f, 4 * ch, size.height)
            for (i in 0..2) block(shade(3 - i, BrightCyan, Cyan, dot(env)), (4 + i) * ch, 0f, (5 + i) * ch, size.height)
            drawContentIn(BarInk)
        }

    override fun statusStyle(env: LookEnv): TextStyle = env.base.copy(color = Black)

    /**
     * Block digits two columns wide and a row high, in a double frame, with a shade for a shadow
     * half a digit's cell to the right and a row down.
     */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val cells = remember(time) { clockCells(time) }
        // a row for each edge of the frame, and one for the shadow of the lowest cells
        val height = with(LocalDensity.current) { (env.cell.height * (CLOCK_ROWS + 3)).toDp() }
        Canvas(Modifier.size(width, height)) {
            val ch = env.cell.width
            val rh = env.cell.height
            val w = dot(env)
            frame(BrightCyan, w, 1.5f * w, size.width - 1.5f * w, rh / 2f, size.height - rh / 2f, double = true)
            val cell = minOf(2f * ch, (size.width - 4f * ch) / (CLOCK_COLUMNS + 0.5f))
            val shadow = shade(2, Brown, Color.Transparent, w)
            // a right password: the digits are white for a moment, the way a line is as it arrives
            val ink = if (leaving != null && leaving() < 130f) White else Yellow
            for (lit in 0..1) cells.forEachIndexed { i, on ->
                if (!on) return@forEachIndexed
                val x = 2f * ch + (i % CLOCK_COLUMNS) * cell
                val y = rh + (i / CLOCK_COLUMNS) * rh
                if (lit == 0) block(shadow, x + cell / 2f, y + rh, x + cell * 1.5f, y + 2f * rh)
                else block(ink, x, y, x + cell, y + rh)
            }
        }
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle =
        env.base.copy(color = if (line == LockLine.INFO) env.palette.dim else White)

    override val gaugeSize: DpSize get() = DpSize(110.dp, 13.dp)

    /** Full blocks, and light shade for the rest. The block being written is white. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val cells = 14
        val cell = size.width / cells
        val filled = (shown * cells + 0.5f).toInt().coerceIn(0, cells)
        block(if (alert) BrightRed else BrightGreen, 0f, 0f, filled * cell, size.height)
        block(shade(1, if (alert) Red else Green, Black, dot(env)), filled * cell, 0f, size.width, size.height)
        if (shown < target && filled > 0) block(White, (filled - 1) * cell, 0f, filled * cell, size.height)
    }

    /**
     * What can be tapped is a DOS-blue panel with a single line around it. The line lies on the
     * panel's edge, so rows one under another share it and read as one panel, ruled apart.
     */
    override fun Modifier.tapRow(env: LookEnv): Modifier = drawWithContent {
        val out = gutter.toPx() * 2f / 3f
        val w = dot(env)
        block(Blue, -out, 0f, size.width + out, size.height)
        frame(BrightCyan, w, -out + w / 2f, size.width + out - w / 2f, 0f, size.height, double = false)
        drawContentIn(PanelInk)
    }

    override fun Modifier.echoRow(env: LookEnv): Modifier = drawWithContent { drawContentIn(EchoInk) }

    /** The app list is one blue panel in a double frame, with a row to spare above and below. */
    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier {
        val rh = env.cell.height
        return this
            .fillMaxWidth()
            .drawBehind {
                val edge = gutter.toPx()
                block(Blue, -edge * 2f / 3f, 0f, size.width + edge * 2f / 3f, size.height)
                frame(
                    BrightCyan, dot(env), -edge / 2f, size.width + edge / 2f,
                    top = if (first) rh / 2f else null, bottom = if (last) size.height - rh / 2f else null, double = true,
                )
            }
            .room(top = if (first) rh else 0f, bottom = if (last) rh else 0f)
    }

    /**
     * A pop-up box: magenta, a double line inside its edge, and its shadow in shade two columns
     * to the right and most of a row down.
     */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier {
        val ch = env.cell.width
        val rh = env.cell.height
        val drop = rh * 0.8f
        return this
            .room(top = rh * 0.3f, right = 2f * ch, bottom = drop + rh * 0.2f)
            .drawWithContent {
                block(shade(2, DarkGray, Color.Transparent, dot(env)), 2f * ch, drop, size.width + 2f * ch, size.height + drop)
                block(Magenta, 0f, 0f, size.width, size.height)
                frame(White, dot(env), ch, size.width - ch, rh * 0.3f, size.height - rh * 0.3f, double = true)
                drawContentIn(PopupInk)
            }
            .room(left = 2f * ch, top = rh * 0.6f, right = 2f * ch, bottom = rh * 0.6f)
    }

    /** A key label in inverse: bright cyan for an app, yellow for a word, white for a key. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .padding(vertical = 10.dp)                  // room to hit it, not part of the label
        .background(
            when (color) {
                env.palette.app -> BrightCyan
                env.palette.fg -> Yellow
                else -> White
            },
        )
        .padding(horizontal = 8.dp, vertical = 1.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(color = Black)

    override fun promptColor(commands: Boolean, palette: Palette): Color = BrightMagenta

    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = DarkGray, color = Black)
        blinkOn -> SpanStyle(background = White, color = Black)
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 150

    /** Received down the line: a character at a time, row by row, behind the block that writes them. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val ch = env.cell.width
        val columns = ceil(size.width / ch).toInt().coerceAtLeast(1)
        val rows = (size.height / env.cell.height).roundToInt().coerceAtLeast(1)
        val rh = size.height / rows
        val at = (p * columns * rows).toInt()
        val row = at / columns
        val column = at % columns
        val edge = gutter.toPx()
        if (row > 0) clipRect(left = -edge, right = size.width + edge, bottom = row * rh) { this@drawWithContent.drawContent() }
        clipRect(left = -edge, top = row * rh, right = column * ch, bottom = (row + 1) * rh) { this@drawWithContent.drawContent() }
        block(White, column * ch, row * rh, (column + 1) * ch, (row + 1) * rh)
    }

    /**
     * Waking: the dark screen is written over from the top left by a head of white shade trailing
     * bright cyan, cyan and blue, and is then cleared the same way to the page.
     */
    override val powerOn: Motion = object : Motion(ms = 640, readableMs = 330) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            if (t >= ms) return
            val ch = env.cell.width
            val rh = env.cell.height
            val d = dot(env)
            val columns = ceil(size.width / ch).toInt()
            val rows = ceil(size.height / rh).toInt()
            // how many cells have been written, and how many of those cleared: half a row at a time
            val head = (stepped(between(t, 0f, 280f), rows * 2) * rows * columns).toInt()
            val tail = (stepped(between(t, 330f, 630f), rows * 2) * rows * columns).toInt()
            for (r in 0 until rows) {
                val y = r * rh
                val from = (tail - r * columns).coerceIn(0, columns)
                val to = (head - r * columns).coerceIn(0, columns)
                if (to < columns) block(Black, to * ch, y, size.width, y + rh)
                if (to <= from) continue
                val age = head / columns - r                // rows behind the head
                val left = r - tail / columns               // rows until the tail gets here
                val ink = if (age < 5) BrightCyan else if (age < 11) Cyan else BrightBlue
                when {
                    tail > 0 && left < 2 -> block(shade(1 + left, ink, Black, d), from * ch, y, to * ch, y + rh)
                    age == 0 -> block(shade(2, White, Black, d), from * ch, y, to * ch, y + rh)
                    age < 3 -> block(BrightCyan, from * ch, y, to * ch, y + rh)
                    age < 5 -> block(shade(3, BrightCyan, Cyan, d), from * ch, y, to * ch, y + rh)
                    age < 8 -> block(Cyan, from * ch, y, to * ch, y + rh)
                    age < 11 -> block(shade(2, Cyan, Blue, d), from * ch, y, to * ch, y + rh)
                    else -> block(shade(2, BrightBlue, Blue, d), from * ch, y, to * ch, y + rh)
                }
            }
        }
    }

    /**
     * Locking: the page scrolls away two rows at a time, bars and all, and leaves a cursor on
     * the dark screen that blinks once and goes out.
     */
    override val powerOff: Motion = object : Motion(ms = 700) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val rh = env.cell.height
            val steps = ceil(size.height / (2f * rh)).toInt()
            val gone = ceil(between(t, 0f, 330f) * steps) * 2f * rh
            if (gone < size.height) clipRect { translate(top = -gone) { this@draw.drawContent() } }
            if (t < 480f || (t >= 580f && t < ms)) {
                val x = gutter.toPx()
                val y = ((size.height - env.keyboard - 48.dp.toPx()) / rh).toInt() * rh - 2f * rh
                block(White, x, y, x + env.cell.width, y + rh)
            }
        }
    }

    /**
     * A right password: the lock page is overwritten row by row with bands of shade, three rows
     * to a colour, the colours cycling up through them, and the bands are then cleared from the top.
     */
    override val granted: Motion = object : Motion(ms = 830) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val clearing = t >= 590f
            // the lock page is gone once the bands have covered it: what they clear to is the new page
            if (!clearing) drawContent()
            if (t < 300f || t >= ms) return
            val ch = env.cell.width
            val rh = env.cell.height
            val d = dot(env)
            val columns = ceil(size.width / ch).toInt()
            val rows = ceil(size.height / rh).toInt()
            val head = (stepped(between(t, 300f, 550f), rows * 2) * rows * columns).toInt()
            val tail = (stepped(between(t, 590f, ms.toFloat()), rows * 2) * rows * columns).toInt()
            val turn = (t / 45f).toInt()
            for (r in 0 until rows) {
                val y = r * rh
                val from = (tail - r * columns).coerceIn(0, columns)
                val to = (head - r * columns).coerceIn(0, columns)
                if (to <= from) continue
                val ink = Bright[(r + turn) / 3 % Bright.size]
                val left = r - tail / columns
                when {
                    to < columns -> block(shade(2, White, Black, d), from * ch, y, to * ch, y + rh)
                    clearing && left < 2 -> block(shade(1 + left, ink, Black, d), from * ch, y, to * ch, y + rh)
                    (r + turn) % 3 == 1 -> block(ink, from * ch, y, to * ch, y + rh)
                    else -> block(shade(if ((r + turn) % 3 == 0) 3 else 2, ink, Black, d), from * ch, y, to * ch, y + rh)
                }
            }
        }
    }

    override val launchMs get() = 620

    /**
     * Line noise: block and shade characters in runs of colour take the screen over, spreading
     * from the launch line and thickening until nothing else is left, then thin out to black for
     * the app to come up in.
     */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        if (ms <= 0f) return
        if (ms >= 300f) drawRect(Black)
        if (ms >= launchMs) return
        val ch = env.cell.width
        val rh = env.cell.height
        val d = dot(env)
        val columns = ceil(size.width / ch).toInt()
        val rows = ceil(size.height / rh).toInt()
        val thinning = ms >= 360f
        val thick = between(ms, 0f, 300f) * 1.25f
        val thin = between(ms, 360f, launchMs.toFloat()) * 1.25f
        val beat = (ms / 100f).toInt()
        val home = (line.center.y / rh).toInt()
        fun noise(i: Int, salt: Int): Color = Noise[(dice(i, salt) * Noise.size).toInt().coerceAtMost(Noise.size - 1)]
        for (r in 0 until rows) {
            val y = r * rh
            val far = minOf(1f, abs(r - home) / (rows * 0.7f))
            // line noise keeps the colour it was last told: the colours come in runs along a row
            var ink = noise(r, 5)
            var runFrom = 0
            var runLevel = 0
            var runInk = ink
            for (c in 0..columns) {
                var level = 0
                if (c < columns) {
                    val i = r * columns + c
                    if (dice(i, 3) < 0.13f) ink = noise(i, 4)
                    val raw = if (thinning) dice(i, 2) + 0.25f - thin else thick - 0.5f * dice(i, 1) - 0.5f * far
                    // how thick a cell may get changes every few beats, a third of the cells at a time
                    val most = 2 + (dice(i, 7 + (beat + i % 3) / 3) * 3f).toInt().coerceAtMost(2)
                    level = ceil(raw / 0.0625f).toInt().coerceIn(0, most)
                }
                if (c < columns && level == runLevel && ink == runInk) continue
                when (runLevel) {
                    0 -> {}
                    4 -> block(runInk, runFrom * ch, y, c * ch, y + rh)
                    else -> block(shade(runLevel, runInk, Black, d), runFrom * ch, y, c * ch, y + rh)
                }
                runFrom = c
                runLevel = level
                runInk = ink
            }
        }
    }
}
