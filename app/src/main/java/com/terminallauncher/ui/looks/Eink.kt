package com.terminallauncher.ui.looks

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSupported
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.GRANTED_TEXT
import com.terminallauncher.ui.Palette
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * E-Ink: an e-paper reader. Ink on grey-green paper, and nothing in between but dither. Nothing
 * glides: a page change is a full refresh of the panel in hard steps, and a small change lands at
 * once with one frame of invert over what changed.
 */
object Eink : Look() {
    override val id = "eink"

    private val Paper = Color(0xFFDFE2D7)
    private val Ink = Color(0xFF16181A)
    private val Dim = Color(0xFF5C6059)
    private val Rule = Color(0xFFA4A89C)

    /**
     * The shell's styles are all ink, told apart by weight, slope, underline and reverse video. Each
     * is one step of one channel away from the next, which no eye sees, so a chip can still be told
     * what kind it is by its color.
     */
    private val Sheet = Palette(
        bg = Paper, fg = Ink, dim = Dim, line = Rule,
        accent = Color(0xFF17181A), app = Color(0xFF16191A), ok = Color(0xFF16181B), err = Color(0xFF15181A),
        light = true,
    )

    override fun palette(theme: String): Palette = Sheet

    /* ---------------- type: Plex Mono for the text, a fat face for the clock ---------------- */

    private val Plex = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
    )
    private val Abril = FontFamily(Font(R.font.abril_fatface_regular))

    override val font get() = Plex

    /** Accent is a run-in heading, bold and sloped. Ok is bold. An app is underlined: it can be tapped. An error is inverted. */
    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        Style.NORMAL -> SpanStyle(color = palette.fg)
        Style.DIM -> SpanStyle(color = palette.dim)
        Style.ACCENT -> SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        Style.OK -> SpanStyle(color = palette.ok, fontWeight = FontWeight.Bold)
        Style.APP -> SpanStyle(color = palette.app, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
        Style.ERR, Style.MARK -> SpanStyle(color = palette.bg, background = palette.fg)
    }

    /* ---------------- dither: the only grey this panel has ---------------- */

    private val M4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private val M2 = intArrayOf(0, 2, 3, 1)

    /** The ordered-dither threshold at a pixel of the panel, 0 to 1: the 4 by 4 matrix, or the 8 by 8. */
    private fun bayer4(x: Int, y: Int): Float = (M4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
    private fun bayer8(x: Int, y: Int): Float =
        (4 * M4[(y and 3) * 4 + (x and 3)] + M2[((y shr 2) and 1) * 2 + ((x shr 2) and 1)] + 0.5f) / 64f

    /** One pixel of the panel, in pixels of the phone. A gauge is 56 of them wide, so about 2 dp. */
    private fun Density.panel(): Int = (110.dp.toPx() / 56f).toInt().coerceAtLeast(1)

    /** A pattern [w] by [h] panel pixels of [k] phone pixels each, [color] wherever [tone] beats the threshold. */
    private fun dither(name: String, k: Int, w: Int, h: Int, color: Color, coarse: Boolean = false, tone: (x: Int, y: Int) -> Float): ShaderBrush =
        tiled("eink $name $k", w * k, h * k) { pixels ->
            val argb = color.toArgb()
            for (y in 0 until h) for (x in 0 until w) {
                if (tone(x, y) <= (if (coarse) bayer4(x, y) else bayer8(x, y))) continue
                for (dy in 0 until k) {
                    val from = (y * k + dy) * w * k + x * k
                    pixels.fill(argb, from, from + k)
                }
            }
        }

    /** The shadow under a rule: ink thinning out downwards. */
    private val FADE = floatArrayOf(0.5f, 0.33f, 0.2f, 0.1f, 0.05f, 0.02f)
    private fun shadow(k: Int, from: Int, rows: Int) = dither("shadow $from $rows", k, 8, rows, Ink) { _, y -> FADE[y + from] }

    /** Where scrolled text runs out under the head: paper thinning out downwards. */
    private fun edge(k: Int) = dither("edge", k, 8, 2, Worn) { _, y -> if (y == 0) 0.7f else 0.36f }

    /** Sixteenths of ink in each of a gauge's 14 cells: it darkens as it fills. */
    private val BAR = intArrayOf(4, 4, 4, 6, 6, 8, 8, 8, 10, 10, 12, 12, 14, 16)
    private fun bar(k: Int) = dither("bar", k, 56, 8, Ink, coarse = true) { x, _ -> BAR[x shr 2] / 16f }
    private fun dots(k: Int) = dither("dots", k, 4, 1, Dim) { x, _ -> if (x == 1) 1f else 0f }
    private fun checks(k: Int) = dither("checks", k, 2, 2, Ink) { x, y -> if ((x + y) % 2 == 0) 1f else 0f }

    /** The clock's fill, ink settling downwards. [up] rows of the pattern lie above the top of the plate. */
    private fun ramp(k: Int, up: Int, rows: Int) =
        dither("ramp $up $rows", k, 8, rows, Ink) { _, y -> 0.1f + 0.95f * maxOf(0f, (y - up - 7) / 34f) }

    /** Fills a box with a pattern that starts at the box's own corner. */
    private fun DrawScope.patch(brush: Brush, at: Offset, box: Size) {
        if (box.width > 0f && box.height > 0f) translate(at.x, at.y) { drawRect(brush, size = box) }
    }

    /* ---------------- negative: paper and ink changing places ---------------- */

    /** Paper plus ink, channel by channel. Taking a color away from it swaps the two exactly. */
    private val Swap = Color(0xFFF5FAF1)

    /** Paints what was drawn into a layer with paper and ink swapped. */
    private val Negative = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, Swap.red * 255f,
                    0f, -1f, 0f, 0f, Swap.green * 255f,
                    0f, 0f, -1f, 0f, Swap.blue * 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    /** Draws [page] in negative inside a box. With [ground] the box is paper first, so all of it turns to ink. */
    private inline fun DrawScope.negative(at: Offset = Offset.Zero, box: Size = size, ground: Boolean = true, page: () -> Unit) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(at, box), Negative)
            if (ground) drawRect(Paper, at, box)
            page()
            canvas.restore()
        }
    }

    /* ---------------- the paper ---------------- */

    /** How much ink one grain of the paper holds. Most hold none at all. */
    private fun grainAt(i: Int): Float = (0.26f * (dice(i, 31) + dice(i, 57)) / 2f - 0.1f).coerceAtLeast(0f)

    /** Grains two pixels across. */
    private fun tooth(): ShaderBrush = tiled("eink tooth", 256, 256) { pixels ->
        val rgb = Ink.toArgb() and 0xFFFFFF
        for (y in 0 until 128) for (x in 0 until 128) {
            val argb = ((grainAt(y * 128 + x) * 255f).toInt() shl 24) or rgb
            for (d in 0 until 4) pixels[(y * 2 + d / 2) * 256 + x * 2 + d % 2] = argb
        }
    }

    /** The paper's tone with its grain evened out, for what is drawn over it and must not show. */
    private val Worn: Color by lazy { lerp(Paper, Ink, (0 until 128 * 128).sumOf { grainAt(it).toDouble() }.toFloat() / (128 * 128)) }

    /** The empty panel: its grain, and the bezel's shade along the edges. With [ground] it covers what was there. */
    private fun DrawScope.paper(ground: Boolean) {
        if (ground) drawRect(Paper)
        drawRect(tooth())
        val deep = 24.dp.toPx()
        val shade = Ink.copy(alpha = 0.07f)
        val clear = Color.Transparent
        drawRect(Brush.horizontalGradient(listOf(shade, clear), 0f, deep), size = Size(deep, size.height))
        drawRect(Brush.horizontalGradient(listOf(clear, shade), size.width - deep, size.width), Offset(size.width - deep, 0f), Size(deep, size.height))
        drawRect(Brush.verticalGradient(listOf(shade, clear), 0f, deep), size = Size(size.width, deep))
        drawRect(Brush.verticalGradient(listOf(clear, shade), size.height - deep, size.height), Offset(0f, size.height - deep), Size(size.width, deep))
    }

    override fun DrawScope.background(env: LookEnv) = paper(ground = false)

    /* ---------------- the running head ---------------- */

    private val HeadTop = 8.dp
    private val HeadLine = 18.sp
    private val HeadFoot = 7.dp
    private val HeadRoom = 14.dp

    /** A hairline under it, and the rule's shadow in dither. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = HeadTop, bottom = HeadRoom)
        .drawBehind {
            val k = panel()
            val hair = 1.dp.toPx()
            drawRect(env.palette.fg, Offset(0f, size.height - hair), Size(size.width, hair))
            patch(shadow(k, 1, 4), Offset(0f, size.height), Size(size.width, 4f * k))
        }
        .padding(bottom = HeadFoot)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 13.sp, lineHeight = HeadLine, fontStyle = FontStyle.Italic)

    /** Text that has scrolled up under the head does not stop at a hard edge: it runs out in dither. */
    override fun Modifier.frame(env: LookEnv): Modifier = drawWithContent {
        drawContent()
        val k = panel()
        val head = HeadTop.roundToPx() + ceil(HeadLine.toPx()).toInt() + HeadFoot.roundToPx() + HeadRoom.roundToPx()
        patch(edge(k), Offset(gutter.toPx(), head.toFloat()), Size(size.width - 2 * gutter.toPx(), 2f * k))
    }

    /* ---------------- the lock page ---------------- */

    /** The plate the numerals stand on, in panel pixels, and the row their feet are on. */
    private const val PLATE_W = 176
    private const val PLATE_H = 48
    private const val FEET = 46

    /** A right password turns the plate over in blocks of eight panel pixels, [TURNS] steps of [TURN_MS]. */
    private const val TURNS = 9
    private const val TURN_MS = 40f

    /** One frame of a partial refresh, and of a full one. */
    private const val FLASH_MS = 45f
    private const val FRAME_MS = 85f

    /** When "access granted" lands, and when the page starts to refresh. */
    private const val WORDS_MS = 420f
    private const val CLEAR_MS = 640f

    /** The blocks of the plate that have turned over by [step]: left to right, each waiting for its turn in the dither. */
    private fun turned(step: Int, px: Float): Path = Path().apply {
        for (r in 0 until PLATE_H / 8) for (c in 0 until PLATE_W / 8) {
            val turn = ((c / 22f * 0.6f + M4[(r and 3) * 4 + (c and 3)] / 16f * 0.4f) * TURNS).toInt()
            if (turn < step) addRect(Rect(Offset(c * 8 * px, r * 8 * px), Size(8 * px, 8 * px)))
        }
    }

    /** Fat numerals with an ink outline and a dithered fill, standing on a rule that casts a dithered shadow. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val density = LocalDensity.current
        val k = with(density) { minOf(panel(), (width.toPx() / PLATE_W).toInt()).coerceAtLeast(1) }
        val style = with(density) { TextStyle(fontFamily = Abril, fontSize = (58 * k).toSp(), letterSpacing = 0.02.em, color = Ink) }
        val laid = remember(time, style) { env.measurer.measure(time, style, softWrap = false, maxLines = 1) }
        // made once, and only when a right password asks for them
        val wipes = remember(k) { arrayOfNulls<Path>(TURNS + 1) }
        Canvas(Modifier.size(with(density) { DpSize((PLATE_W * k).toDp(), ((PLATE_H + 6) * k).toDp()) })) {
            val px = k.toFloat()
            val plate = Size(PLATE_W * px, PLATE_H * px)
            // The text's box starts some rows above the plate, and the fill's pattern starts with it.
            val up = ((laid.firstBaseline - FEET * px) / px).roundToInt()
            val at = Offset(px, -up * px)
            val fill = ramp(k, up, ceil(laid.size.height / px).toInt() + 1)
            drawText(laid, brush = fill, topLeft = at)
            drawText(laid, color = Ink, topLeft = at, drawStyle = Stroke(0.75f * px))
            drawRect(Ink, Offset(0f, plate.height), Size(plate.width, px))
            patch(shadow(k, 0, 5), Offset(0f, plate.height + px), Size(plate.width, 5 * px))

            val ms = leaving?.invoke() ?: return@Canvas
            val step = (ms / TURN_MS).toInt().coerceAtMost(TURNS)
            if (step > 0) {
                val wipe = wipes[step] ?: turned(step, px).also { wipes[step] = it }
                clipPath(wipe) {
                    negative(box = plate) {
                        drawText(laid, brush = fill, topLeft = at)
                        drawText(laid, color = Ink, topLeft = at, drawStyle = Stroke(0.75f * px))
                    }
                }
            }
            // The words land at once on the line the lock page keeps empty under itself, in negative for a frame.
            if (ms >= WORDS_MS) {
                val said = env.base.copy(color = env.palette.ok, fontWeight = FontWeight.Bold)
                val y = size.height + 16.dp.toPx() + 2 * ceil(env.cell.height)
                if (ms < WORDS_MS + FLASH_MS) {
                    val box = words(env, GRANTED_TEXT, said)
                    val margin = env.cell.width / 2f
                    negative(Offset(-margin, y), Size(box.width + 2 * margin, box.height)) { words(env, GRANTED_TEXT, said, Offset(0f, y)) }
                } else {
                    words(env, GRANTED_TEXT, said, Offset(0f, y))
                }
            }
        }
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(color = env.palette.dim, fontStyle = FontStyle.Italic)
        LockLine.DATE -> env.base.copy(fontWeight = FontWeight.Medium)
        LockLine.INFO -> env.base.copy(color = env.palette.dim)
    }

    /* ---------------- parts ---------------- */

    override val gaugeSize get() = DpSize(110.dp, 12.dp)

    /** A dither ramp that darkens as it fills, closed by an ink edge, then leader dots to the figure. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val ink = env.palette.fg
        val k = panel()
        val px = k.toFloat()
        val cell = 4 * px
        val cells = BAR.size
        val tall = (size.height / px).toInt().coerceIn(1, 8) * px
        val top = size.height - tall
        val filled = (target * cells + 0.5f).toInt().coerceIn(0, cells)
        // It fills in three hard steps of five cells, each when a bar running up smoothly would have reached it.
        val block = if (shown >= target) 2 else ((shown * cells).toInt() / 5).coerceAtMost(2)
        val lit = minOf(filled, (block + 1) * 5)
        patch(dots(k), Offset(lit * cell, size.height - px), Size(size.width - lit * cell, px))
        if (lit == 0) return
        if (alert) {
            drawRect(ink, Offset(0f, top), Size(lit * cell, tall))
        } else {
            patch(bar(k), Offset(0f, top), Size(lit * cell, tall))
            drawRect(ink, Offset(0f, size.height - px), Size(lit * cell, px))
            // The block that has just landed is solid for a frame. A full bar takes 420 ms to run up.
            val landed = (shown - block * 5f / cells) * 420f
            if (shown < target && landed < FLASH_MS && lit > block * 5) {
                drawRect(ink, Offset(block * 5 * cell, top), Size((lit - block * 5) * cell, tall))
            }
        }
        if (lit < cells) drawRect(ink, Offset(lit * cell, top), Size(px, tall))
    }

    /** An app in `ls` is underlined like every other thing that can be tapped: a hairline under its cell. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = drawBehind {
        if (lit) return@drawBehind
        val y = 6.dp.toPx() + env.cell.height * 0.74f + fontSize.toPx() * 0.26f
        drawRect(env.palette.dim, Offset(0f, y), Size(size.width - 2 * env.cell.width, 1.dp.toPx()))
    }

    /**
     * A slip: a hairline above and below, and a dithered edge. It asks a second time, with one frame
     * of negative over the whole slip, as it stops being news.
     */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 8.dp)
        .drawWithContent {
            val f = faded()
            val k = panel()
            val hair = 1.dp.toPx()
            fun slip() {
                drawRect(env.palette.fg, size = Size(size.width, hair))
                drawRect(env.palette.fg, Offset(0f, size.height - hair), Size(size.width, hair))
                patch(checks(k), Offset.Zero, Size(4f * k, size.height))
                drawContent()
            }
            if (f > 0f && f < 0.09f) negative { slip() } else slip()
        }
        .padding(start = 15.dp, top = 4.dp, bottom = 4.dp)

    /** Soft keys: a square box with a hard shadow one panel pixel down and across. A key is in reverse video. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val px = panel().toFloat()
            val hair = 1.dp.toPx()
            drawRect(env.palette.fg, Offset(px, px), size)
            drawRect(if (color == env.palette.accent) env.palette.fg else env.palette.bg)
            drawRect(env.palette.fg, Offset(hair / 2f, hair / 2f), Size(size.width - hair, size.height - hair), style = Stroke(hair))
        }
        .padding(horizontal = 10.dp, vertical = 2.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = when (color) {
        env.palette.accent -> env.base.copy(color = env.palette.bg)
        env.palette.app -> env.base.copy(textDecoration = TextDecoration.Underline)
        env.palette.fg -> env.base.copy(fontWeight = FontWeight.Medium)
        else -> env.base
    }

    /** E-paper does not blink. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle =
        if (focused) SpanStyle(background = palette.fg, color = palette.bg) else SpanStyle(background = palette.line, color = palette.fg)

    /* ---------------- movement: nothing eases ---------------- */

    /** What a partial refresh leaves where it was, frame by frame: after the negative, a ghost that goes in steps. */
    private val GHOST = floatArrayOf(0f, 0.1f, 0.1f, 0.06f, 0.06f, 0.03f, 0.03f, 0f)

    override val lineMs get() = (GHOST.size * FLASH_MS).toInt()

    /** A line does not print itself. It is there at once, in negative for one frame, and then its ghost goes. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val frame = (stepped(p.coerceAtLeast(0f), GHOST.size) * GHOST.size).toInt().coerceAtMost(GHOST.size - 1)
        val margin = env.cell.width / 2f
        val at = Offset(-margin, 0f)
        val box = Size(size.width + 2 * margin, size.height)
        if (frame == 0) {
            negative(at, box) { drawContent() }
        } else {
            drawContent()
            if (GHOST[frame] > 0f) drawRect(Ink, at, box, alpha = GHOST[frame])
        }
    }

    /** Which frame of a refresh [t] is in. */
    private fun frame(t: Float, of: Int): Int = (stepped(t / (of * FRAME_MS), of) * of).toInt()

    /** Waking is a full refresh: the panel goes black, then white, then the page comes up pale and settles. */
    override val powerOn: Motion = object : Motion(ms = (4 * FRAME_MS).toInt(), readableMs = (2 * FRAME_MS).toInt(), backdrop = Paper) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            when (frame(t, 4)) {
                0 -> drawRect(Ink)
                1 -> paper(ground = true)
                2 -> {
                    drawContent()
                    drawRect(Paper, alpha = 0.6f)
                }
                else -> drawContent()
            }
        }
    }

    /** Locking: the page as it was in negative, then black. Waking takes it from there. */
    override val powerOff: Motion = object : Motion(ms = (2 * FRAME_MS).toInt(), backdrop = Ink) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (frame(t, 2) == 0) negative(ground = false) { drawContent() } else drawRect(Ink)
        }
    }

    /**
     * A right password: the clock turns over block by block (see [Clock]), the words land, and then
     * the page refreshes: negative, black, white.
     */
    override val granted: Motion = object : Motion(ms = (CLEAR_MS + 3 * FRAME_MS).toInt()) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t < CLEAR_MS) {
                drawContent()
                return
            }
            when (frame(t - CLEAR_MS, 3)) {
                0 -> negative { drawContent() }
                1 -> drawRect(Ink)
                else -> {}      // white: the paper under the page, and the summary prints on it
            }
        }
    }

    /** The clock draws the words itself, at once: on this panel nothing resolves out of noise. */
    override val grantedWords get() = false

    override val launchMs get() = 480

    /** The line is picked, as a tapped item is on a reader: it stays in negative, and then the panel refreshes for the app. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val picked = 2 * FRAME_MS
        // the row reads "| exec name": a spinner that turns every 70 ms, six characters and the name
        val at = Offset(gutter.toPx() - env.cell.width / 2f, line.top)
        val box = Size((name.length + 8) * env.cell.width, line.height)
        val frame = if (ms < picked) -1 else frame(ms - picked, 3)
        when {
            frame >= 2 -> paper(ground = true)
            frame == 1 -> drawRect(Ink)
            // The page under this is not ours to draw again, so it is swapped where it lies.
            BlendMode.Difference.isSupported() -> {
                drawRect(Swap, at, box, blendMode = BlendMode.Difference)
                // the whole page in negative, and the picked line the right way round in it
                if (frame == 0) drawRect(Swap, blendMode = BlendMode.Difference)
            }
            // Before Android 10 there is no such blending: the picked line is written out again in negative, and the page goes straight to black.
            frame == 0 -> drawRect(Ink)
            else -> negative(at, box) {
                words(env, "${"|/-\\"[(ms / 70f).toInt() % 4]} exec $name", env.base, Offset(gutter.toPx(), line.top))
            }
        }
    }
}
