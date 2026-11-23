package com.terminallauncher.ui.looks

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.Palette

/**
 * Brutal: a Swiss poster. Paper, black and one red, all of it flat: nothing is shaded, lit or
 * rounded. An enormous grotesque for the clock and the date, a sturdy mono in bold for the rest,
 * rules and slabs for everything else. Every movement is a block going in a straight line and
 * stopping dead.
 *
 * The sheet has a margin line down each side and is divided in three columns, whose lines show
 * behind the page. Slabs (the status band, a command, a notification) run from edge to edge.
 */
object Brutal : Look() {
    override val id = "brutal"

    private val Paper = Color(0xFFF5F4EF)
    private val Ink = Color(0xFF000000)
    private val Sig = Color(0xFFE8230F)

    // A chip is told its kind only by its color, and here every kind is black. So the palette has
    // three blacks that no eye can tell apart, and a chip can still tell what it is.
    private val AppInk = Color(0xFF000100)
    private val DimInk = Color(0xFF010000)

    private val Sheet = Palette(
        bg = Paper, fg = Ink, dim = DimInk, line = Ink,
        accent = Sig, app = AppInk, ok = Ink, err = Sig,
        light = true,
    )

    override fun palette(theme: String): Palette = Sheet

    private val Hair = 1.dp                 // a hairline
    private val Rule = 5.dp                 // a thick rule

    private val Snap = CubicBezierEasing(0.85f, 0f, 0.1f, 1f)       // out of nowhere, dead stop
    private val Slam = CubicBezierEasing(0.5f, 0f, 0.9f, 0.45f)     // gathers speed and hits
    private val Out = CubicBezierEasing(0.6f, 0f, 1f, 0.7f)         // leaves faster and faster
    private val Wipe = CubicBezierEasing(0.7f, 0f, 0.2f, 1f)

    /** A value that goes from one of [values] to the next in hard moves: each takes [move] ms, then holds. */
    private fun steps(t: Float, start: Float, every: Float, move: Float, vararg values: Float): Float {
        var v = values[0]
        for (i in 1 until values.size) {
            val from = start + (i - 1) * every
            v += (values[i] - values[i - 1]) * Snap.transform(between(t, from, from + move))
        }
        return v
    }

    /* ---------------- type: Space Mono in bold, 43 columns on a phone 411 dp wide ---------------- */

    private val Mono = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))
    private val Anton = FontFamily(Font(R.font.anton_regular))

    override val font get() = Mono
    override val fontSize get() = 14.sp
    override val lineHeight get() = 21.sp
    override val advance get() = 0.612f
    override val gutter get() = 20.dp

    override fun type(base: TextStyle, palette: Palette): TextStyle = base.copy(fontWeight = FontWeight.Bold)

    /** One black in two weights. What can be tapped is underlined, what went well is set in reverse. */
    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        Style.DIM -> SpanStyle(color = palette.dim, fontWeight = FontWeight.Normal)
        Style.APP -> SpanStyle(color = palette.app, textDecoration = TextDecoration.Underline)
        Style.OK -> SpanStyle(color = palette.bg, background = palette.fg)
        Style.MARK -> SpanStyle(color = palette.bg, background = palette.accent)
        Style.ERR -> SpanStyle(color = palette.err)
        else -> SpanStyle(color = palette.fg)
    }

    /* ---------------- printing in negative ---------------- */

    /**
     * What turns a part of the page into its negative: black becomes paper and paper black, and red
     * stays red. The text of a slab is drawn as it would be on paper, through this.
     */
    private val Negative: Paint by lazy {
        fun rgb(c: Color) = floatArrayOf(c.red * 255f, c.green * 255f, c.blue * 255f)
        val p = rgb(Paper)
        val s = rgb(Sig)
        val det = p[0] * s[1] - p[1] * s[0]
        val m = FloatArray(20)
        for (i in 0 until 3) {
            // out = paper - (x * red + y * green), with paper going to black and the signal red to itself
            val x = (p[i] * s[1] - p[1] * (p[i] - s[i])) / det
            val y = (p[0] * (p[i] - s[i]) - s[0] * p[i]) / det
            m[i * 5] = -x
            m[i * 5 + 1] = -y
            m[i * 5 + 4] = p[i]
        }
        m[18] = 1f
        Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(m)) }
    }

    private fun ContentDrawScope.negative() = drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Negative)
        drawContent()
        canvas.restore()
    }

    /* ---------------- the sheet ---------------- */

    /** The margin lines, and the lines of the three columns between them. */
    override fun DrawScope.background(env: LookEnv) {
        val g = gutter.toPx()
        val hair = Hair.toPx()
        val columns = ((size.width - 2 * g) / env.cell.width).toInt().coerceAtLeast(20)
        val third = (columns / 3) * env.cell.width
        drawRect(Ink, Offset(g - hair, 0f), Size(hair, size.height))
        drawRect(Ink, Offset(g + third - hair, 0f), Size(hair, size.height))
        drawRect(Ink, Offset(g + 2 * third - hair, 0f), Size(hair, size.height))
        drawRect(Ink, Offset(size.width - g, 0f), Size(hair, size.height))
    }

    /** The chips stand on a strip of bare paper under a thick rule. The 48 dp are the height of their row. */
    override fun Modifier.frame(env: LookEnv): Modifier = drawBehind {
        if (env.locked) return@drawBehind
        val top = size.height - 48.dp.toPx()
        drawRect(Paper, Offset(0f, top), Size(size.width, size.height - top))
        drawRect(Ink, Offset(0f, top), Size(size.width, Rule.toPx()))
    }

    /* ---------------- parts ---------------- */

    /** A black band from edge to edge, up through the top of the screen. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(bottom = 9.dp)
        .drawWithContent {
            val g = gutter.toPx()
            val above = 400.dp.toPx()
            drawRect(Ink, Offset(-g, -above), Size(size.width + 2 * g, size.height + above))
            negative()
        }
        .padding(top = 6.dp, bottom = 7.dp)

    // set in black like everything else: the band prints it in negative
    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.05.em, color = Ink)

    override fun statusText(text: String): String = text.uppercase()

    private const val ClockEms = 2.23f      // the widest time, 00:00, is this many ems of Anton
    private const val ClockCap = 0.867f     // its numerals are this tall,
    private const val AntonCap = 0.859f     // its capitals this,
    private const val AntonAscent = 1.1763f // and a line of it has its baseline this far under its top
    private val ClockRise = 8.dp            // the numerals stand this far into the gap above the clock
    private val RuleGap = 13.dp             // between a rule and the type over and under it
    private val HeaderGap = 16.dp           // what the lock page leaves between the clock and the date
    private val DateSize = 64.sp
    private val DateLine = 6.sp

    /**
     * Set in type as large as the measure allows, one numeral to a box and the boxes spread to both
     * margins, over a thick rule. The rule under the date is drawn from here too.
     *
     * A right password has the numerals shoved off the sheet by solid blocks: the middle ones up
     * and down by pistons that cross the whole page, the outer two sideways.
     */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val em = width / ClockEms
        val date = with(LocalDensity.current) { DateSize.toDp() }
        // The rule under the numerals hangs below the clock's own box, so that the date, which keeps
        // the whole ascent of its face over its capitals, stands as close under the rule as the numerals over it.
        val height = em * ClockCap - ClockRise + RuleGap + Rule + RuleGap - HeaderGap - date * (AntonAscent - AntonCap)
        // where the clock is on the screen, for the pistons: its top, and how tall the screen is
        val spot = remember { FloatArray(2) }
        Canvas(
            Modifier
                .size(width, height)
                .onGloballyPositioned {
                    spot[0] = it.positionInRoot().y
                    spot[1] = it.findRootCoordinates().size.height.toFloat()
                },
        ) {
            val glyphs = time.map { env.measurer.measure(it.toString(), TextStyle(fontFamily = Anton, fontSize = em.toPx().toSp(), color = Ink)) }
            val n = glyphs.size
            if (n == 0) return@Canvas
            val spread = if (n > 1) (size.width - glyphs.sumOf { it.size.width }) / (n - 1) else 0f
            val x = FloatArray(n)
            for (i in 1 until n) x[i] = x[i - 1] + glyphs[i - 1].size.width + spread
            // where one numeral's column ends and the next begins
            val edge = FloatArray(n + 1)
            for (i in 1 until n) edge[i] = (x[i - 1] + glyphs[i - 1].size.width + x[i]) / 2f
            edge[n] = size.width
            val top = -ClockRise.toPx()
            val foot = top + em.toPx() * ClockCap

            val dx = FloatArray(n)
            val dy = FloatArray(n)
            val ms = leaving?.invoke()
            val pistons = ms != null && n >= 3
            val up = if (spot[1] > 0f) -spot[0] - (foot - top) else -size.height * 6f
            val down = if (spot[1] > 0f) spot[1] - env.keyboard - spot[0] else size.height * 6f
            fun pushed(k: Int): Float = if (ms == null) 0f else Slam.transform(between(ms, PistonAt + k * PistonEvery, PistonAt + k * PistonEvery + PistonMs))
            if (pistons) {
                for (i in 1 until n - 1) {
                    val p = pushed(i - 1)
                    dy[i] = if (i % 2 == 1) minOf(0f, down + (up - down) * p - foot) else maxOf(0f, up + (down - up) * p - top)
                }
                dx[0] = -edge[1] * pushed(n - 2)
                dx[n - 1] = (size.width - edge[n - 1]) * pushed(n - 1)
            }
            for (i in 0 until n) drawText(glyphs[i], topLeft = Offset(x[i] + dx[i], foot - glyphs[i].firstBaseline + dy[i]))

            val rule = Rule.toPx()
            drawRect(Ink, Offset(0f, foot + RuleGap.toPx()), Size(size.width, rule))
            // and the one under the date, whose baseline is a line's ascent below the gap that follows the clock
            drawRect(Ink, Offset(0f, size.height + HeaderGap.toPx() + DateSize.toPx() * AntonAscent + 11.dp.toPx()), Size(size.width, rule))

            if (pistons) {
                for (i in 1 until n - 1) {
                    val p = pushed(i - 1)
                    if (p <= 0f) continue
                    if (i % 2 == 1) {
                        val head = down + (up - down) * p
                        drawRect(Ink, Offset(edge[i], head), Size(edge[i + 1] - edge[i], down - head))
                    } else {
                        val head = up + (down - up) * p
                        drawRect(Ink, Offset(edge[i], up), Size(edge[i + 1] - edge[i], head - up))
                    }
                }
                val tall = foot + 4.dp.toPx() - top
                val left = pushed(n - 2)
                if (left > 0f) drawRect(Ink, Offset(edge[1] * (1f - left), top), Size(edge[1] * left, tall))
                val right = pushed(n - 1)
                if (right > 0f) drawRect(Ink, Offset(edge[n - 1], top), Size((size.width - edge[n - 1]) * right, tall))
            }
        }
    }

    /**
     * The title in spaced capitals, and the date in the clock's face, justified to the measure.
     * A single line is never justified, so the date is given a second one of nothing but spaces
     * that do not break. The lines are set almost on top of each other, which leaves under the
     * date just the room its rule needs.
     */
    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.14.em)
        LockLine.DATE -> TextStyle(
            fontFamily = Anton, fontSize = DateSize, lineHeight = DateLine, color = Ink,
            textAlign = TextAlign.Justify,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
        LockLine.INFO -> env.base.copy(fontWeight = FontWeight.Normal)
    }

    override fun lockText(line: LockLine, text: String): String = when (line) {
        LockLine.TITLE -> text.uppercase()
        LockLine.DATE -> text.uppercase() + " " + " ".repeat(18)
        LockLine.INFO -> text
    }

    override val gaugeSize get() = DpSize(110.dp, 14.dp)

    /**
     * A slab on a ticked hairline that ends in a stop. As it arrives the slab slams out past its
     * length, holds, and is pulled back.
     */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val hair = Hair.toPx()
        // a printed line hides the column lines: here from the bar on, over the note that follows it
        drawRect(Paper, Offset(0f, (size.height - env.cell.height) / 2f), Size(env.cell.width * 25f, env.cell.height))
        drawRect(Ink, Offset(0f, size.height - hair), Size(size.width, hair))
        drawRect(Ink, Offset(size.width - hair, 0f), Size(hair, size.height))
        for (i in 0 until 7) drawRect(Ink, Offset(i * size.width / 7f, size.height - 4.dp.toPx()), Size(hair, 4.dp.toPx()))
        val p = if (target > 0f) shown / target else 1f
        val reach = when {
            p >= 1f -> 1f
            p < 0.6f -> 1.09f * Slam.transform(p / 0.6f)
            else -> 1.09f - 0.09f * Snap.transform(between(p, 0.85f, 1f))
        }
        drawRect(if (alert) Sig else Ink, Offset.Zero, Size(size.width * target * reach, size.height - hair))
    }

    /** Ruled off above and below from edge to edge, with a wedge in the margin at the end of the row. */
    override fun Modifier.tapRow(env: LookEnv): Modifier = drawBehind {
        val g = gutter.toPx()
        val hair = Hair.toPx()
        drawRect(Paper, Offset.Zero, size)
        drawRect(Ink, Offset(-g, 0f), Size(size.width + 2 * g, hair))
        drawRect(Ink, Offset(-g, size.height), Size(size.width + 2 * g, hair))
        val w = 6.dp.toPx()
        val h = 8.dp.toPx()
        val x = size.width + 7.dp.toPx()
        val y = (size.height - h) / 2f
        drawPath(
            Path().apply {
                moveTo(x, y)
                lineTo(x + w, y + h / 2f)
                lineTo(x, y + h)
                close()
            },
            Ink,
        )
    }

    /** What was typed: a black slab from edge to edge, its words in negative. */
    override fun Modifier.echoRow(env: LookEnv): Modifier = this
        .padding(top = 8.dp, bottom = 6.dp)
        .drawWithContent {
            val g = gutter.toPx()
            drawRect(Ink, Offset(-g, 0f), Size(size.width + 2 * g, size.height))
            negative()
        }
        .padding(vertical = 3.dp)

    /** The app names are a ruled table: a hairline over and under every row, on bare paper. */
    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier = drawBehind {
        val g = gutter.toPx()
        val hair = Hair.toPx()
        val far = size.width + 2000.dp.toPx()
        drawRect(Paper, Offset.Zero, size)
        drawRect(Ink, Offset(-g, 0f), Size(far, hair))
        drawRect(Ink, Offset(-g, size.height), Size(far, hair))
    }

    /** And a hairline down the left of every cell. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = drawBehind {
        val hair = Hair.toPx()
        drawRect(Ink, Offset(-hair, 0f), Size(hair, size.height))
    }

    /**
     * A slab of the signal red from edge to edge, its words in negative, the sender ruled off from
     * the message. When it stops being news the slab snaps shut into the left margin and leaves the
     * words in black on the paper.
     */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 9.dp)
        .drawWithContent {
            val g = gutter.toPx()
            val hair = Hair.toPx()
            val full = size.width + g
            val edge = full + (-g + 8.dp.toPx() - full) * Snap.transform(between(faded(), 0f, 0.7f))
            drawRect(Paper, Offset.Zero, size)
            drawRect(Sig, Offset(-g, 0f), Size(edge + g, size.height))
            val under = 7.dp.toPx() + env.cell.height
            val box = 9.dp.toPx()
            fun furniture(color: Color) {
                drawRect(color, Offset(0f, under), Size(size.width, hair))
                drawRect(color, Offset(size.width - box, under - 6.dp.toPx() - box), Size(box, box))
            }
            clipRect(left = -g, right = edge) {
                this@drawWithContent.negative()
                furniture(Paper)
            }
            if (edge < full) clipRect(left = edge, right = full) {
                this@drawWithContent.drawContent()
                furniture(Ink)
            }
        }
        .padding(top = 7.dp, bottom = 9.dp)

    /**
     * Square boxes with heavy rules, each sharing a rule with the next: a chip reaches across the
     * gap to its neighbour. A command is a solid block.
     */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val rule = 2.dp.toPx()
            val reach = 8.dp.toPx() + rule
            if (color == env.palette.fg) drawRect(Ink, Offset(rule, 0f), Size(size.width + reach - 2 * rule, size.height))
            else drawRect(Ink, Offset(rule / 2f, rule / 2f), Size(size.width + reach - rule, size.height - rule), style = Stroke(rule))
        }
        .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = when (color) {
        env.palette.fg -> env.base.copy(color = Paper)
        env.palette.dim -> env.base.copy(color = Ink, fontWeight = FontWeight.Normal)
        else -> env.base.copy(color = color)
    }

    /** A question, such as the one for the password, is the one loud thing on its page. */
    override fun promptColor(commands: Boolean, palette: Palette): Color = if (commands) palette.fg else palette.accent

    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.fg, color = palette.bg)
        blinkOn -> SpanStyle(background = palette.accent, color = palette.bg)
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 280

    /** A black bar as long as the words runs out over the line and pulls back, leaving them. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier {
        val natural = IntArray(1)           // how long the words are, measured as the line is laid out
        return this
            .layout { measurable, constraints ->
                natural[0] = measurable.maxIntrinsicWidth(constraints.maxHeight)
                val placed = measurable.measure(constraints)
                layout(placed.width, placed.height) { placed.place(0, 0) }
            }
            .drawWithContent {
                // the column lines show only where nothing is printed
                drawRect(Paper, Offset.Zero, size)
                val p = progress()
                if (p >= 1f) {
                    drawContent()
                    return@drawWithContent
                }
                if (p <= 0f) return@drawWithContent
                val w = minOf(natural[0].toFloat(), size.width)
                val covers = w > env.cell.width * 1.5f          // an empty line has nothing to cover
                val inset = 2.dp.toPx()
                if (p < 0.46f) {
                    if (covers) drawRect(Ink, Offset(0f, inset), Size(w * Wipe.transform(p / 0.46f), size.height - 2 * inset))
                } else {
                    drawContent()
                    val gone = w * Wipe.transform((p - 0.46f) / 0.54f)
                    if (covers) drawRect(Ink, Offset(gone, inset), Size(w - gone, size.height - 2 * inset))
                }
            }
    }

    /** How much of the screen can be seen: the part above the keyboard. */
    private fun DrawScope.seen(env: LookEnv): Float = (size.height - env.keyboard).coerceAtLeast(size.height * 0.4f)

    /**
     * The screen is a grid of black panels. They slide off row by row, each row out of the other
     * side, the panel by the edge first and the rest after it like wagons. When the last has left,
     * the sheet takes the knock.
     */
    override val powerOn: Motion = object : Motion(ms = 700, readableMs = 260) {
        override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
            if (t >= 636f && t < ms) translationY = 3.dp.toPx()
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            val columns = 3
            val rows = 4
            val w = size.width / columns
            val h = seen(env) / rows
            val lap = 0.5.dp.toPx()
            for (r in 0 until rows) {
                val dir = if (r % 2 == 1) 1f else -1f
                for (k in 0 until columns) {
                    // k = 0 is the panel by the edge its row leaves through
                    val from = 40f + (r * columns + k) * 36f
                    val p = Out.transform(between(t, from, from + 130f + k * 35f))
                    if (p >= 1f) continue
                    val column = if (dir < 0f) k else columns - 1 - k
                    val bottom = if (r == rows - 1) size.height else (r + 1) * h
                    drawRect(
                        Color.Black,
                        Offset(column * w + dir * (k + 1) * w * 1.01f * p - lap, r * h - lap),
                        Size(w + 2 * lap, bottom - r * h + 2 * lap),
                    )
                }
            }
        }
    }

    /** Shutters: black bars close from alternating sides, top to bottom. */
    override val powerOff: Motion = object : Motion(ms = 520) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            val bars = 8
            val h = seen(env) / bars
            val lap = 0.5.dp.toPx()
            for (i in 0 until bars) {
                val p = Slam.transform(between(t, i * 40f, i * 40f + 170f))
                if (p <= 0f) continue
                val bottom = if (i == bars - 1) size.height else (i + 1) * h
                drawRect(
                    Color.Black,
                    Offset((if (i % 2 == 1) 1f else -1f) * size.width * (1f - p), i * h - lap),
                    Size(size.width, bottom - i * h + 2 * lap),
                )
            }
        }
    }

    // a right password: when each piston starts and how long it takes, then the bar of red
    private const val PistonAt = 90f
    private const val PistonEvery = 48f
    private const val PistonMs = 190f
    private const val SweepAt = 470f
    private const val SweepIn = 150f
    private const val SweepHold = 60f

    /**
     * The words flash at the foot of the page while the pistons take the clock away (see [Clock]).
     * Then a bar of red crosses, and the summary is behind it.
     */
    override val granted: Motion = object : Motion(ms = 900) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val covered = SweepAt + SweepIn
            if (t < covered) {
                drawContent()
                // twice black on paper, twice paper on black, and it stays that way
                val reversed = t >= 180f || (t / 60f).toInt() % 2 == 1
                val tall = env.cell.height * 2f
                drawRect(if (reversed) Ink else Paper, Offset(0f, size.height - tall), Size(size.width, tall))
                words(
                    env, "ACCESS GRANTED", env.base.copy(color = if (reversed) Paper else Ink, letterSpacing = 0.14.em),
                    Offset(env.cell.width, size.height - tall + env.cell.height / 2f),
                )
            }
            if (t < SweepAt) return
            val x = when {
                t < covered -> -size.width * (1f - Wipe.transform(between(t, SweepAt, covered)))
                t < covered + SweepHold -> 0f
                else -> size.width * Wipe.transform(between(t, covered + SweepHold, ms.toFloat()))
            }
            drawRect(Sig, Offset(x, 0f), size)
        }
    }

    /** The words are set by the look, in a block at the foot of the page. */
    override val grantedWords get() = false

    override val launchMs get() = 620

    /** The line turns black, and its block grows to the whole screen in three hard steps. The words grow with it. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        if (ms < 50f) return
        val grown = steps(ms, 130f, 95f, 48f, 0f, 0.18f, 0.5f, 1f)
        val pad = 3.dp.toPx()
        val top = (line.top - pad) * (1f - grown)
        val bottom = line.bottom + pad + (size.height - line.bottom - pad) * grown
        drawRect(Color.Black, Offset(0f, top), Size(size.width, bottom - top))
        val g = gutter.toPx()
        // where the spinner was: a square that shows one quarter at a time
        val side = env.cell.width * 1.2f
        val half = side / 2f
        val quarter = (ms / 110f).toInt() % 4
        drawRect(
            Paper,
            Offset(g + if (quarter == 1 || quarter == 2) half else 0f, line.center.y - half + if (quarter >= 2) half else 0f),
            Size(half, half),
        )
        scale(steps(ms, 130f, 95f, 48f, 1f, 1.5f, 2.1f, 2.7f), pivot = Offset(g + env.cell.width * 2f, line.bottom)) {
            words(env, "exec $name", env.base.copy(color = Paper), Offset(g + env.cell.width * 2f, line.top))
        }
    }
}
