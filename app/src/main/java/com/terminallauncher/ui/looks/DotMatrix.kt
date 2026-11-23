package com.terminallauncher.ui.looks

import android.content.res.Resources
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.CLOCK_COLUMNS
import com.terminallauncher.ui.CLOCK_ROWS
import com.terminallauncher.ui.GRANTED_TEXT
import com.terminallauncher.ui.Palette
import com.terminallauncher.ui.clockCells
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Dot Matrix: the reflective four-shade LCD of an early-90s handheld. Four olive shades and nothing
 * else: emphasis is a darker shade, reverse video or a checkerboard of dots, and everything that
 * moves does it the way that hardware could, in a few hard steps of contrast.
 */
object DotMatrix : Look() {
    override val id = "dotmatrix"

    /* The four shades, pale to dark. Paper is the palest and full ink the darkest. */
    private val S0 = Color(0xFFC5CF8A)
    private val S1 = Color(0xFF98A85C)
    private val S2 = Color(0xFF465C26)
    private val S3 = Color(0xFF16250F)

    private val Lcd = Palette(bg = S0, fg = S3, dim = S2, line = S1, accent = S3, app = S3, ok = S3, err = S3, light = true)

    override fun palette(theme: String): Palette = Lcd

    /* ---------------- the dots ---------------- */

    /** The small dot that dithers and rules are made of, in whole pixels: half the pitch of the glass. */
    private fun DrawScope.dot(): Int = 1.5.dp.toPx().roundToInt().coerceAtLeast(1)

    /** A length in dp as pixels, where nothing that draws is at hand to ask. */
    private fun px(dp: Float): Float = dp * Resources.getSystem().displayMetrics.density

    /** Every other dot, like a chessboard: the half tone of a screen that has no grays to spare. */
    private fun check(color: Color, dot: Int): ShaderBrush = tiled("dotmatrix check ${color.toArgb()} $dot", dot * 2, dot * 2) { pixels ->
        for (i in pixels.indices) if ((i % (dot * 2)) / dot == (i / (dot * 2)) / dot) pixels[i] = color.toArgb()
    }

    /** One dot in four: a quarter tone. */
    private fun quarter(color: Color, dot: Int): ShaderBrush = tiled("dotmatrix quarter ${color.toArgb()} $dot", dot * 2, dot * 2) { pixels ->
        for (i in pixels.indices) if (i % (dot * 2) < dot && i / (dot * 2) < dot) pixels[i] = color.toArgb()
    }

    /** The gaps between the dots of the glass: a line [hair] pixels thick along the right and the bottom of every cell. */
    private fun gaps(color: Color, pitch: Int, hair: Int): ShaderBrush = tiled("dotmatrix gaps ${color.toArgb()} $pitch $hair", pitch, pitch) { pixels ->
        for (i in pixels.indices) if (i % pitch >= pitch - hair || i / pitch >= pitch - hair) pixels[i] = color.toArgb()
    }

    /** Fills a box with a pattern that starts at the box's own corner, so its dots are whole at the edges. */
    private fun DrawScope.pattern(brush: Brush, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        translate(left.roundToInt().toFloat(), top.roundToInt().toFloat()) { drawRect(brush, size = Size(width, height)) }
    }

    /**
     * A change of palette for something already drawn: each channel runs on a straight line that
     * takes [a] to [toA] and [b] to [toB]. Two shades land exactly, the two between them close by.
     */
    private fun remap(a: Color, toA: Color, b: Color, toB: Color): Paint {
        fun row(a: Float, toA: Float, b: Float, toB: Float): Pair<Float, Float> {
            val slope = (toB - toA) / (b - a)
            return slope to (toA - slope * a) * 255f
        }
        val (r, r0) = row(a.red, toA.red, b.red, toB.red)
        val (g, g0) = row(a.green, toA.green, b.green, toB.green)
        val (bl, b0) = row(a.blue, toA.blue, b.blue, toB.blue)
        return Paint().apply {
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, r0, 0f, g, 0f, 0f, g0, 0f, 0f, bl, 0f, b0, 0f, 0f, 0f, 1f, 0f)),
            )
        }
    }

    /** Reverse video: ink becomes paper and paper ink. */
    private val Reversed = remap(S0, S3, S3, S0)

    /** Dots that have only begun to respond: full ink shows as the lightest shade, then as the middle one. */
    private val Faint = remap(S0, S0, S3, S1)
    private val Half = remap(S0, S0, S3, S2)

    /** The page sinking into the dark: its paper is already black, its ink still glows for two steps. */
    private val Sinking = remap(S0, S3, S3, S1)
    private val Sunk = remap(S0, S3, S3, S2)

    /** Draws [block] through a change of palette. */
    private inline fun DrawScope.through(palette: Paint, block: () -> Unit) {
        val room = 4.dp.toPx()
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(-room, -room, size.width + room, size.height + room), palette)
            block()
            canvas.restore()
        }
    }

    /* ---------------- type: VT323 for the terminal, Tiny5 for labels ---------------- */

    private val Vt = FontFamily(Font(R.font.vt323_regular))
    private val Tiny = FontFamily(Font(R.font.tiny5_regular))
    override val font get() = Vt
    override val fontSize get() = 22.sp
    override val lineHeight get() = 24.sp
    override val advance get() = 0.4f

    /** Every lit dot throws a faint shadow one dot down and right, on the reflector behind the glass. */
    private fun cast(): Shadow = px(0.8f).roundToInt().coerceAtLeast(1).toFloat().let { Shadow(S1.copy(alpha = 0.45f), Offset(it, it), 0f) }
    private val NoShadow = Shadow(Color.Transparent)

    /** Every line is exactly one line high, so a block of reverse video is as tall as its row. */
    private val WholeLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    override fun type(base: TextStyle, palette: Palette): TextStyle = base.copy(shadow = cast(), lineHeightStyle = WholeLines)

    private fun label(env: LookEnv, color: Color): TextStyle =
        env.base.copy(fontFamily = Tiny, fontSize = 18.sp, lineHeight = 22.sp, color = color)

    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        // a dark block with pale text: the one way this screen has to raise its voice
        Style.ACCENT, Style.MARK -> SpanStyle(color = palette.bg, background = palette.fg, shadow = NoShadow)
        else -> SpanStyle(color = palette.of(style))
    }

    /* ---------------- the panel and its glass ---------------- */

    /** The paper between the dots, a little brighter than the dots themselves, and the light on it. */
    private val Gap = Color(0xFFD9E1A3)
    private val Sheen = Color(0xFFCDD594)

    /** What the glass does to a lit dot, and the bezel's shadow on the panel below it. */
    private val Glass = Color(0x5CE2EAAC)
    private val Bezel = Color(0xFF162208)

    private fun DrawScope.paper(alpha: Float = 1f) {
        val at = Offset(size.width * 0.4f, size.height * 0.22f)
        val wide = size.width * 0.84f
        scale(1f, size.height * 0.49f / wide, pivot = at) {
            drawCircle(Brush.radialGradient(listOf(Sheen, Sheen.copy(alpha = 0f)), center = at, radius = wide), radius = wide, center = at, alpha = alpha)
        }
        drawRect(gaps(Gap, dot() * 2, (dot() / 2).coerceAtLeast(1)), alpha = alpha)
    }

    private fun DrawScope.glass(alpha: Float = 1f) {
        // over the picture the gaps are hairlines: they break a lit block into dots and leave the text its ink
        drawRect(gaps(Glass, dot() * 2, (dot() / 4).coerceAtLeast(1)), alpha = alpha)
        // the panel sits a little below its bezel, which shades it most from the top and the left
        fun edge(brush: Brush) = drawRect(brush, alpha = alpha)
        val near = 20.dp.toPx()
        val far = 12.dp.toPx()
        val clear = Bezel.copy(alpha = 0f)
        edge(Brush.verticalGradient(listOf(Bezel.copy(alpha = 0.34f), clear), startY = 0f, endY = near))
        edge(Brush.horizontalGradient(listOf(Bezel.copy(alpha = 0.3f), clear), startX = 0f, endX = near))
        edge(Brush.verticalGradient(listOf(clear, Bezel.copy(alpha = 0.2f)), startY = size.height - far, endY = size.height))
        edge(Brush.horizontalGradient(listOf(clear, Bezel.copy(alpha = 0.2f)), startX = size.width - far, endX = size.width))
    }

    override fun DrawScope.background(env: LookEnv) = paper()

    override fun DrawScope.overlay(env: LookEnv) = glass()

    /**
     * Turns the contrast down over everything drawn so far: at [level] 2 full ink shows as the
     * middle shade, at 1 as the lightest, at 0 the panel is blank. The glass is put back on top.
     */
    private fun DrawScope.contrast(level: Int) {
        if (level >= 3) return
        val to = when (level) {
            2 -> S2
            1 -> S1
            else -> S0
        }
        // how far each channel of full ink has to go toward paper
        val r = (to.red - S3.red) / (S0.red - S3.red)
        val g = (to.green - S3.green) / (S0.green - S3.green)
        val b = (to.blue - S3.blue) / (S0.blue - S3.blue)
        drawRect(Color(1f - r, 1f - g, 1f - b), blendMode = BlendMode.Modulate)
        drawRect(Color(r * S0.red, g * S0.green, b * S0.blue), blendMode = BlendMode.Plus)
        val gone = (r + g + b) / 3f
        paper(gone)
        glass(gone)
    }

    /* ---------------- parts ---------------- */

    /** The halftone shadow a dark bar throws: two dots of checkerboard to its right and below it. */
    private fun DrawScope.halftone() {
        val d = dot()
        val deep = d * 2f
        pattern(check(S3, d), size.width, deep, deep, size.height)
        pattern(check(S3, d), deep, size.height, size.width - deep, deep)
    }

    /** Room at the bottom of the page for the rule that sets the soft keys apart. */
    override fun Modifier.frame(env: LookEnv): Modifier = drawBehind {
        if (env.locked) return@drawBehind
        val d = dot()
        val gutter = 16.dp.toPx()
        pattern(dashes(S2, d), gutter, size.height - 48.dp.toPx(), size.width - 2 * gutter, d.toFloat())
    }

    /** A dotted rule: one dot on, one off. */
    private fun dashes(color: Color, dot: Int): ShaderBrush = tiled("dotmatrix dashes ${color.toArgb()} $dot", dot * 2, 1) { pixels ->
        for (i in 0 until dot) pixels[i] = color.toArgb()
    }

    /** An inverted bar with a halftone shadow. Whatever is written on it is written in reverse. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 8.dp, bottom = 13.dp, end = 3.dp)
        .drawWithContent {
            halftone()
            drawRect(S3)
            through(Reversed) { this@drawWithContent.drawContent() }
        }
        .padding(horizontal = 7.dp, vertical = 1.dp)

    override fun statusStyle(env: LookEnv): TextStyle = label(env, S3).copy(shadow = NoShadow)

    private const val CLOCK_GAP_DOTS = 3

    /**
     * The side of one cell of a clock [width] pixels wide, in pixels: an odd number of dots, so the
     * quarter tone of an unlit cell starts and ends on a dot.
     */
    private fun clockCell(width: Float, dot: Int): Int {
        val most = ((width - dot - CLOCK_GAP_DOTS * dot * (CLOCK_COLUMNS - 1)) / CLOCK_COLUMNS / dot).toInt().coerceIn(3, 11)
        return (if (most % 2 == 0) most - 1 else most) * dot
    }

    /** The seventeen by five cells as big square dots. The ones that are off still show, as they do on such a panel. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val cells = remember(time) { clockCells(time) }
        val density = LocalDensity.current
        val d = with(density) { 1.5.dp.toPx() }.roundToInt().coerceAtLeast(1)
        val gap = CLOCK_GAP_DOTS * d
        val cell = clockCell(with(density) { width.toPx() }, d)
        val wide = cell * CLOCK_COLUMNS + gap * (CLOCK_COLUMNS - 1) + d
        val tall = cell * CLOCK_ROWS + gap * (CLOCK_ROWS - 1) + d
        val date = lockStyle(LockLine.DATE, env).lineHeight
        Canvas(Modifier.size(with(density) { DpSize(wide.toDp(), tall.toDp()) })) {
            val off = quarter(S1, d)
            val side = cell.toFloat()
            cells.forEachIndexed { i, on ->
                val x = (i % CLOCK_COLUMNS) * (cell + gap).toFloat()
                val y = (i / CLOCK_COLUMNS) * (cell + gap).toFloat()
                if (on) {
                    drawRect(S1, Offset(x + d, y + d), Size(side, side), alpha = 0.5f)
                    drawRect(S3, Offset(x, y), Size(side, side))
                } else pattern(off, x, y, side, side)
            }
            // A right password: the words come up where the prompt was, as a label that blinks twice.
            val ms = leaving?.invoke() ?: return@Canvas
            val text = " $GRANTED_TEXT "
            val style = env.base.copy(color = S0, shadow = NoShadow)
            val top = size.height + 16.dp.toPx() + date.toPx() + 2 * env.cell.height
            drawRect(if (ms < 70f || (ms >= 140f && ms < 210f)) S1 else S3, Offset(0f, top), words(env, text, style))
            words(env, text, style, Offset(0f, top))
        }
    }

    /** The date is the one big line, in the chunky face. */
    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.DATE -> env.base.copy(fontFamily = Tiny, fontSize = 32.sp, lineHeight = 40.sp)
        else -> env.base.copy(color = env.palette.dim)
    }

    override val gaugeSize: DpSize get() = DpSize(110.dp, 10.dp)

    /** Ten square blocks. The empty ones are a checkerboard, and a block that has just come on is not yet dark. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val cells = 10
        val d = dot()
        val gap = d.toFloat()
        // whole dots, and an even number of them, so every block holds the same piece of checkerboard
        val side = (floor((size.width - d - gap * (cells - 1)) / cells / (2 * d)) * 2 * d).coerceAtMost(floor((size.height - d / 2f) / d) * d)
        val top = ((size.height - side) / 2f).roundToInt().toFloat()
        val filled = (shown * cells + 0.5f).toInt()
        val empty = check(if (alert) S3 else S2, d)
        for (i in 0 until cells) {
            val x = i * (side + gap)
            if (i >= filled) {
                pattern(empty, x, top, side, side)
                continue
            }
            // the dots are slow: the two blocks the bar reached last are still on their way to full ink
            val fresh = if (shown < target) filled - 1 - i else 2
            if (fresh >= 2) drawRect(S1, Offset(x + d / 2f, top + d / 2f), Size(side, side), alpha = 0.5f)
            drawRect(if (fresh >= 2) S3 else if (fresh == 1) S2 else S1, Offset(x, top), Size(side, side))
        }
    }

    /** A dotted rule under the line, and a small arrowhead at its end, three dots by five. */
    override fun Modifier.tapRow(env: LookEnv): Modifier = drawBehind {
        val d = dot()
        pattern(dashes(S2, d), 0f, size.height - d, size.width, d.toFloat())
        val top = ((size.height - 5 * d) / 2f).roundToInt()
        for (row in 0 until 5) {
            drawRect(S2, Offset(size.width - 3f * d, (top + row * d).toFloat()), Size((3f - abs(row - 2)) * d, d.toFloat()))
        }
    }

    /** A little air around a command, so the block its prompt stands on touches nothing. */
    override fun Modifier.echoRow(env: LookEnv): Modifier = padding(top = 4.dp, bottom = 2.dp)

    /** How far a row of names moves over to make room for the square before each of them. */
    private fun bulletRoom(env: LookEnv): Float = env.cell.width * 1.5f

    /**
     * The row moves over without getting narrower, so no name loses a character to its square: all
     * that leaves the page is the empty end of the last column.
     */
    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier = layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        layout(row.width, row.height) { row.place(bulletRoom(env).roundToInt(), 0) }
    }

    /** A small square before every name. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = drawBehind {
        val side = 3f * dot()
        val left = (-bulletRoom(env) + (bulletRoom(env) - side) * 0.3f).roundToInt().toFloat()
        drawRect(if (lit) S3 else S2, Offset(left, ((size.height - side) / 2f).roundToInt().toFloat()), Size(side, side))
    }

    /** An inverted bar with a halftone shadow, like the status line: the phone speaking, not the shell. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(top = 6.dp, bottom = 10.dp, end = 3.dp)
        .drawWithContent {
            halftone()
            drawRect(S3)
            through(Reversed) { this@drawWithContent.drawContent() }
        }
        .padding(horizontal = 8.dp, vertical = 3.dp)

    /** A bordered box that throws the same shadow as the text in it. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val line = 2.dp.toPx().roundToInt().toFloat()
            val cast = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
            drawRect(S1, Offset(size.width, cast), Size(cast, size.height), alpha = 0.5f)
            drawRect(S1, Offset(cast, size.height), Size(size.width - cast, cast), alpha = 0.5f)
            drawRect(color, Offset.Zero, Size(size.width, line))
            drawRect(color, Offset(0f, size.height - line), Size(size.width, line))
            drawRect(color, Offset(0f, line), Size(line, size.height - 2 * line))
            drawRect(color, Offset(size.width - line, line), Size(line, size.height - 2 * line))
        }
        .padding(horizontal = 10.dp, vertical = 4.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = label(env, color)

    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line, color = palette.fg, shadow = NoShadow)
        blinkOn -> SpanStyle(background = palette.fg, color = palette.bg, shadow = NoShadow)
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 220

    /** A line arrives as its dots respond: the lightest shade, the middle one, then full ink. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        when {
            p >= 1f -> drawContent()
            p < 0.04f -> Unit
            p < 0.5f -> through(Faint) { this@drawWithContent.drawContent() }
            else -> through(Half) { this@drawWithContent.drawContent() }
        }
    }

    /** The contrast comes up through the shades: blank, pale, mid, full. */
    override val powerOn: Motion = object : Motion(ms = 420, readableMs = 0, backdrop = S0) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            contrast((t / 140f).toInt())
        }
    }

    /** The picture drains away through the shades to blank paper, and one dark row lingers across the middle. */
    override val powerOff: Motion = object : Motion(ms = 720, backdrop = S0) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            val step = (t / 130f).toInt()
            contrast(2 - step)
            val row = when (step) {
                1, 3 -> S2
                2 -> S3
                4 -> S1
                else -> return
            }
            val d = dot()
            val y = floor((size.height - env.keyboard) * 0.44f / (2 * d)) * 2 * d
            drawRect(row, Offset(0f, y), Size(size.width, 2f * d - (2 * d / 4).coerceAtLeast(1)))
        }
    }

    override val grantedWords get() = false

    private const val GRANTED_HOLD = 190f
    private const val GRANTED_BEAT = 50f
    private const val IRIS_AT = GRANTED_HOLD + 6 * GRANTED_BEAT
    private const val IRIS_STEPS = 9
    private const val IRIS_STEP_MS = 36f

    /**
     * The words blink on, the picture inverts twice, its light parts sink into the dark, and a
     * blocky iris opens on the page underneath.
     */
    override val granted: Motion = object : Motion(ms = (IRIS_AT + (IRIS_STEPS + 2) * IRIS_STEP_MS).toInt() + 4) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t < GRANTED_HOLD) return drawContent()
            if (t >= IRIS_AT) return iris(((t - IRIS_AT) / IRIS_STEP_MS).toInt(), env)
            val look = when (((t - GRANTED_HOLD) / GRANTED_BEAT).toInt()) {
                0, 2 -> Reversed
                1 -> return drawContent()
                3 -> Sinking
                4 -> Sunk
                else -> null
            }
            drawRect(S3)
            if (look != null) through(look) { this@draw.drawContent() }
        }
    }

    /**
     * The dark page with a round hole in it, drawn in chunks of three dots of the glass. The hole
     * grows a band a step, and its edge trails two paler bands: dots that have not caught up.
     */
    private fun DrawScope.iris(step: Int, env: LookEnv) {
        val d = dot()
        val chunk = d * 6f
        // It opens about where the clock was: under the boot log and the title, if the page began at its top.
        val cell = clockCell(size.width, d)
        val cx = (cell * CLOCK_COLUMNS + CLOCK_GAP_DOTS * d * (CLOCK_COLUMNS - 1)) / 2f
        val cy = minOf(env.cell.height * 7 + 22.dp.toPx() + (cell * CLOCK_ROWS + CLOCK_GAP_DOTS * d * (CLOCK_ROWS - 1)) / 2f, size.height * 0.62f)
        val band = (hypot(maxOf(cx, size.width - cx), maxOf(cy, size.height - cy)) + chunk) / IRIS_STEPS
        val front = band * (step - 2)
        val columns = ceil(size.width / chunk).toInt()
        val rows = ceil(size.height / chunk).toInt()
        for (row in 0 until rows) {
            val dy = (row + 0.5f) * chunk - cy
            var run = -1
            var from = 0
            for (column in 0..columns) {
                val shade = if (column == columns) -2 else floor((hypot((column + 0.5f) * chunk - cx, dy) - front) / band).toInt().coerceIn(-1, 2)
                if (shade == run) continue
                if (run >= 0) {
                    drawRect(if (run == 0) S1 else if (run == 1) S2 else S3, Offset(from * chunk, row * chunk), Size((column - from) * chunk, chunk))
                }
                run = shade
                from = column
            }
        }
    }

    override val launchMs get() = 560

    /**
     * A shutter of twelve horizontal bars. Each closes downward a slice at a time, its leading edge
     * two paler slices; the bar the launch line is in goes first and the others follow outward.
     */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val step = ((ms - 50f) / 34f).toInt()
        if (step <= 0) return
        val bars = 12
        val slices = 5
        val pitch = dot() * 2f
        val slice = ceil(size.height / bars / slices / pitch) * pitch
        val bar = slice * slices
        val first = (line.center.y / bar).toInt().coerceIn(0, bars - 1)
        for (k in 0 until bars) {
            val shut = (step - (abs(k - first) * 0.4f).roundToInt()).coerceIn(0, slices + 2)
            val top = k * bar
            fun part(from: Int, to: Int, shade: Color) {
                val a = from.coerceAtLeast(0)
                val b = to.coerceAtMost(slices)
                if (b > a) drawRect(shade, Offset(0f, top + a * slice), Size(size.width, (b - a) * slice))
            }
            part(0, shut - 2, S3)
            part(shut - 2, shut - 1, S2)
            part(shut - 1, shut, S1)
        }
    }
}
