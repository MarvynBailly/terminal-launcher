package com.terminallauncher.ui.looks

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.ui.Palette
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Blueprint: an engineer's cyanotype. A Prussian blue sheet with a square grid and a drafting
 * border, white linework, pencil yellow for the notes and red pencil for what is wrong. Everything
 * is drawn the way a plotter would draw it: straight, exact, unhurried.
 */
object Blueprint : Look() {
    override val id = "blueprint"

    private val Cyanotype = Palette(
        bg = Color(0xFF0D3769), fg = Color(0xFFEEF5FF), dim = Color(0xFF8FB5E0), line = Color(0xFF3F6CA6),
        accent = Color(0xFFFFD166), app = Color(0xFF86E1F5), ok = Color(0xFFA8ECB9), err = Color(0xFFFF7D6E),
    )

    override fun palette(theme: String): Palette = Cyanotype

    /** Secondary linework, construction lines, and the grid's ink before it is thinned. */
    private val Ink = Color(0x8CD6E9FF)
    private val Faint = Color(0x4DAAD0FF)
    private val GridInk = Color(0xFFBEDCFF)

    /** The paper where the lamp is on it, and at its far corners. In between it is the palette's blue. */
    private val Lit = Color(0xFF134886)
    private val Corner = Color(0xFF08274D)

    /** What a launch goes into. */
    private val Dark = Color(0xFF101216)

    /** A thin pen and the border pen. */
    private val Hair = 1.dp
    private val Bold = 2.dp

    /** A pen that starts and stops without a jerk. */
    private val PenEase = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)

    /* ---------------- type: Share Tech Mono ---------------- */

    private val Mono = FontFamily(Font(R.font.share_tech_mono_regular))
    override val font get() = Mono
    override val fontSize get() = 15.5.sp
    override val lineHeight get() = 22.sp
    override val advance get() = 0.54f
    override val gutter get() = 24.dp

    /** The height of a capital M's square, in pixels: the unit the small linework is sized by. */
    private fun em(env: LookEnv): Float = env.cell.width / advance

    /* ---------------- the sheet: paper, grid, border, registration marks ---------------- */

    /** The border leaves about this much around it, and has this many grid squares across. */
    private val Margin = 12.dp
    private const val COLUMNS = 25

    /** Where the linework lies on a sheet, in whole pixels: the border, the side of a grid square, the two pens. */
    private class Plan(val border: Rect, val cell: Int, val pen: Int, val bold: Int) {
        /** The border's line covers this much before and after where it runs. */
        val before get() = bold / 2
        val after get() = bold - bold / 2

        /** The room inside the border. */
        val within get() = Rect(border.left + after, border.top + after, border.right - before, border.bottom - before)
    }

    /** The plan of a sheet [w] wide and [h] high. The grid is fitted to whole pixels and the border to the grid. */
    private fun Density.plan(w: Float, h: Float): Plan {
        val cell = ((w - 2 * Margin.toPx()) / COLUMNS).roundToInt().coerceAtLeast(2)
        val edge = ((w - cell * COLUMNS) / 2f).roundToInt().toFloat()
        return Plan(
            border = Rect(edge, edge, edge + cell * COLUMNS, maxOf(edge + cell, (h - edge).roundToInt().toFloat())),
            cell = cell,
            pen = Hair.toPx().roundToInt().coerceAtLeast(1),
            bold = Bold.toPx().roundToInt().coerceAtLeast(2),
        )
    }

    private fun paperAt(at: Offset, w: Float, h: Float): Color {
        val d = hypot((at.x - w * 0.5f) / (w * 1.3f), (at.y - h * 0.36f) / (h * 0.78f).coerceAtLeast(1f))
        return if (d < 0.56f) lerp(Lit, Cyanotype.bg, d / 0.56f) else lerp(Cyanotype.bg, Corner, between(d, 0.56f, 1f))
    }

    /** The paper of a sheet [w] by [h], lit from above its middle, with a little tooth. It is painted down to [fill]. */
    private fun DrawScope.paper(w: Float, h: Float, fill: Float = h, alpha: Float = 1f) {
        val lamp = Offset(w * 0.5f, h * 0.36f)
        val across = w * 1.3f
        val down = (h * 0.78f).coerceAtLeast(1f)
        val reach = w / 2f * down / across + 1f
        scale(across / down, 1f, lamp) {
            drawRect(
                Brush.radialGradient(0f to Lit, 0.56f to Cyanotype.bg, 1f to Corner, center = lamp, radius = down),
                topLeft = Offset(lamp.x - reach, 0f), size = Size(reach * 2f, fill), alpha = alpha,
            )
        }
        drawRect(grain(Color(0xFFCCE6FF), lo = 0f, hi = 0.09f, salt = 3), Offset.Zero, Size(w, fill), alpha = alpha)
    }

    /** Five squares by five: fine lines, a heavier one every fifth, and a cross where the heavy ones meet. */
    private fun squares(cell: Int, pen: Int): ShaderBrush = tiled("blueprint grid $cell $pen", cell * 5, cell * 5) { pixels ->
        val n = cell * 5
        val arm = cell / 4
        val rgb = GridInk.toArgb() and 0xFFFFFF
        for (y in 0 until n) for (x in 0 until n) {
            val heavyX = x < pen
            val heavyY = y < pen
            val byCrossX = x < pen + arm || x >= n - arm
            val byCrossY = y < pen + arm || y >= n - arm
            val strength = when {
                (heavyY && byCrossX) || (heavyX && byCrossY) -> 0.3f
                heavyX || heavyY -> 0.2f
                x % cell < pen || y % cell < pen -> 0.095f
                else -> 0f
            }
            pixels[y * n + x] = ((strength * 255f).toInt() shl 24) or rgb
        }
    }

    /** Diagonal lines [period] pixels apart along a row and [thick] pixels wide: a hatched fill. */
    private fun hatch(color: Color, period: Int, thick: Float): ShaderBrush {
        val n = period.coerceAtLeast(3)
        return tiled("blueprint hatch ${color.toArgb()} $n $thick", n, n) { pixels ->
            val rgb = color.toArgb() and 0xFFFFFF
            for (y in 0 until n) for (x in 0 until n) {
                // a line runs from the bottom left to the top right, so x + y is the same all along it
                val k = (x + y) % n
                val off = minOf(k, n - k) / 1.4142f
                val strength = (thick / 2f + 0.5f - off).coerceIn(0f, 1f)
                pixels[y * n + x] = ((strength * 255f).toInt() shl 24) or rgb
            }
        }
    }

    /** The grid inside the border, and a zone tick outside it at every heavy line. */
    private fun DrawScope.grid(plan: Plan, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val r = plan.border
        translate(r.left, r.top) { drawRect(squares(plan.cell, plan.pen), Offset.Zero, r.size, alpha = alpha) }
        val tick = 4.5.dp.toPx().roundToInt().toFloat()
        val pen = plan.pen.toFloat()
        val step = plan.cell * 5f
        var x = r.left + step
        while (x < r.right - 1f) {
            drawRect(GridInk, Offset(x, r.top - plan.before - tick), Size(pen, tick), alpha = 0.6f * alpha)
            drawRect(GridInk, Offset(x, r.bottom + plan.after), Size(pen, tick), alpha = 0.6f * alpha)
            x += step
        }
        var y = r.top + step
        while (y < r.bottom - plan.cell) {
            drawRect(GridInk, Offset(r.left - plan.before - tick, y), Size(tick, pen), alpha = 0.6f * alpha)
            drawRect(GridInk, Offset(r.right + plan.after, y), Size(tick, pen), alpha = 0.6f * alpha)
            y += step
        }
    }

    /**
     * The border, as far as one pen has drawn it going clockwise from the top left corner, and a
     * registration mark on each corner the pen has been to. With [nib] the pen itself shows.
     */
    private fun DrawScope.border(plan: Plan, drawn: Float = 1f, nib: Boolean = false) {
        if (drawn <= 0f) return
        val r = plan.border
        val a = plan.before.toFloat()
        val bold = plan.bold.toFloat()
        val fg = Cyanotype.fg
        val round = 2f * (r.width + r.height)
        val gone = drawn.coerceAtMost(1f) * round
        val top = gone.coerceIn(0f, r.width)
        val right = (gone - r.width).coerceIn(0f, r.height)
        val bottom = (gone - r.width - r.height).coerceIn(0f, r.width)
        val left = (gone - 2f * r.width - r.height).coerceIn(0f, r.height)
        drawRect(fg, Offset(r.left - a, r.top - a), Size(top + bold, bold))
        if (right > 0f) drawRect(fg, Offset(r.right - a, r.top - a), Size(bold, right + bold))
        if (bottom > 0f) drawRect(fg, Offset(r.right - a - bottom, r.bottom - a), Size(bottom + bold, bold))
        if (left > 0f) drawRect(fg, Offset(r.left - a, r.bottom - a - left), Size(bold, left + bold))

        // a circle on crossed lines that run past the border, grown as the pen leaves the corner
        val grow = round * 0.26f
        val starts = floatArrayOf(0f, r.width, r.width + r.height, 2f * r.width + r.height)
        val corners = arrayOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
        val middle = (plan.bold % 2) / 2f
        for (i in 0 until 4) {
            val grown = if (drawn >= 1f) 1f else easeOut(between(gone, starts[i], starts[i] + grow))
            if (grown <= 0f) continue
            val at = corners[i] + Offset(middle, middle)
            val arm = 9.dp.toPx() * grown
            drawLine(Ink, at - Offset(arm, 0f), at + Offset(arm, 0f), Hair.toPx(), alpha = grown)
            drawLine(Ink, at - Offset(0f, arm), at + Offset(0f, arm), Hair.toPx(), alpha = grown)
            drawCircle(fg, 4.5.dp.toPx() * grown, at, alpha = grown, style = Stroke(Hair.toPx()))
        }

        if (nib && drawn < 1f) {
            val at = when {
                left > 0f -> Offset(r.left, r.bottom - left)
                bottom > 0f -> Offset(r.right - bottom, r.bottom)
                right > 0f -> Offset(r.right, r.top + right)
                else -> Offset(r.left + top, r.top)
            }
            val glow = 11.dp.toPx()
            drawCircle(Brush.radialGradient(0f to Cyanotype.accent.copy(alpha = 0.55f), 1f to Color.Transparent, center = at, radius = glow), glow, at)
            drawCircle(Cyanotype.accent, 3.5.dp.toPx(), at)
        }
    }

    /** The sheet ends where the keyboard begins. Nothing on it moves at rest. */
    override fun DrawScope.background(env: LookEnv) {
        val tall = size.height - env.keyboard
        val plan = plan(size.width, tall)
        paper(size.width, tall, fill = size.height)
        grid(plan)
        border(plan)
    }

    /* ---------------- the page inside the border ---------------- */

    /** Between the border and what it holds. */
    private val Clear = 1.5.dp

    /** The title strip: the room over its words, under them, and under its rule. */
    private val StripOver = 8.dp
    private val StripUnder = 6.dp
    private val StripGap = 6.dp
    private val StripLine = 16.sp

    /** The row the chips sit in is this high, whatever the look. */
    private val ChipStrip = 48.dp

    private fun Density.stripHeight(): Float = (StripOver + StripUnder + StripGap).toPx() + StripLine.toPx()

    /** Keeps the page inside the border, and rules off the strip the chips sit in above the bottom edge. */
    override fun Modifier.frame(env: LookEnv): Modifier = this
        .drawBehind {
            val plan = plan(size.width, size.height)
            val y = size.height - plan.border.top - plan.bold - Clear.toPx() - ChipStrip.toPx()
            drawLine(Ink, Offset(plan.border.left, y), Offset(plan.border.right, y), Hair.toPx())
        }
        .layout { measurable, constraints ->
            val plan = plan(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val inset = (plan.border.top + plan.bold + Clear.toPx()).roundToInt()
            val page = measurable.measure(constraints.offset(vertical = -2 * inset))
            layout(page.width, page.height + 2 * inset) { page.place(0, inset) }
        }

    /** The status line is the title strip under the top border, ruled off from side to side. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(bottom = StripGap)
        .drawBehind {
            val gut = gutter.toPx()
            val plan = plan(size.width + 2f * gut, size.height)
            val y = size.height - Hair.toPx() / 2f
            drawLine(Ink, Offset(plan.border.left - gut, y), Offset(plan.border.right - gut, y), Hair.toPx())
        }
        .padding(top = StripOver, bottom = StripUnder)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 12.5.sp, lineHeight = StripLine, letterSpacing = 0.07.em, color = env.palette.dim)

    override fun statusText(text: String): String = text.uppercase()

    /* ---------------- the lock sheet ---------------- */

    private val DateLine = 28.sp

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(color = env.palette.dim, letterSpacing = 0.04.em)
        LockLine.DATE -> env.base.copy(fontSize = 20.sp, lineHeight = DateLine, letterSpacing = 0.05.em)
        LockLine.INFO -> env.base.copy(color = env.palette.dim)
    }

    /** A numeral as the path of one fat pen, in a box 3 wide and 5 high; [from] and [to] are where its ink ends. */
    private class Numeral(val pen: Path, val at: Float, val from: Float, val to: Float, val digit: Boolean)

    private val Pens: Map<Char, Path> by lazy {
        mapOf(
            '0' to "M0.4 1.5 a1.1 1.1 0 0 1 2.2 0 v2 a1.1 1.1 0 0 1 -2.2 0 z",
            '1' to "M0.9 1.4 l1 -1 v4.2",
            '2' to "M0.4 1.5 a1.1 1.1 0 0 1 2.2 0 c0 0.9 -2.2 1.9 -2.2 3.1 h2.2",
            '3' to "M0.4 0.4 h2.2 l-1.3 1.7 a1.25 1.25 0 1 1 -0.9 2.1",
            '4' to "M2 4.6 v-4.2 l-1.6 2.9 h2.2",
            '5' to "M2.5 0.4 h-1.9 l-0.2 1.8 a1.3 1.3 0 1 1 0 2.1",
            '6' to "M2.3 0.4 c-1.1 0.3 -1.9 1.5 -1.9 3.1 a1.1 1.1 0 1 0 2.2 0 a1.1 1.1 0 1 0 -2.2 0",
            '7' to "M0.4 0.4 h2.2 l-1.5 4.2",
            '8' to "M1.5 2.4 a1 1 0 1 1 0 -2 a1 1 0 1 1 0 2 a1.1 1.1 0 1 1 0 2.2 a1.1 1.1 0 1 1 0 -2.2",
            '9' to "M0.7 4.6 c1.1 -0.3 1.9 -1.5 1.9 -3.1 a1.1 1.1 0 1 0 -2.2 0 a1.1 1.1 0 1 0 2.2 0",
            ':' to "M0.5 1.6 v0.01 M0.5 3.4 v0.01",
        ).mapValues { PathParser().parsePathString(it.value).toPath() }
    }

    private fun numerals(time: String): List<Numeral> {
        val set = ArrayList<Numeral>()
        var x = 0f
        for (ch in time) {
            val pen = Pens[ch] ?: continue
            if (set.isNotEmpty()) x += 1f
            val digit = ch != ':'
            set += Numeral(pen, x, if (ch == '1') 0.5f else 0f, if (ch == '1') 2.3f else 3f, digit)
            x += if (digit) 3f else 1f
        }
        return set
    }

    /** The construction lines run this far past the numerals. */
    private val Past = 10.dp

    /**
     * Outlined numerals, hatched inside, on their construction lines: cap line, centre line, base
     * line and the two sides of every numeral. A right password closes four lines on them, strikes
     * them through and stamps the sheet under its last line.
     */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val figures = remember(time) { numerals(time) }
        val wide = figures.lastOrNull()?.let { it.at + if (it.digit) 3f else 1f } ?: 17f
        val unit = minOf((width - Past * 2) / maxOf(wide, 17f), 18.dp)
        Canvas(Modifier.size(width = unit * wide + Past * 2, height = unit * 5 + Past * 2)) {
            val u = unit.toPx()
            val hair = Hair.toPx()
            val em = em(env)
            val box = Rect(Past.toPx(), Past.toPx(), Past.toPx() + wide * u, Past.toPx() + 5f * u)

            drawLine(Faint, Offset(0f, box.top), Offset(size.width, box.top), hair)
            drawLine(Faint, Offset(0f, box.bottom), Offset(size.width, box.bottom), hair)
            drawLine(
                Faint, Offset(0f, box.center.y), Offset(size.width, box.center.y), hair,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(em, 0.22f * em, 0.18f * em, 0.22f * em)),
            )
            for (n in figures) if (n.digit) {
                val l = box.left + (n.at + n.from) * u
                val r = box.left + (n.at + n.to) * u
                drawLine(Faint, Offset(l, 0f), Offset(l, size.height), hair)
                drawLine(Faint, Offset(r, 0f), Offset(r, size.height), hair)
            }

            fun pen(width: Float, color: Color, blend: BlendMode = BlendMode.SrcOver) {
                for (n in figures) withTransform({
                    translate(box.left + n.at * u, box.top)
                    scale(u, u, Offset.Zero)
                }) {
                    drawPath(n.pen, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
                }
            }
            drawIntoCanvas { canvas ->
                val all = Rect(Offset.Zero, size)
                // where the fat pen went, hatched
                canvas.saveLayer(all, Paint().apply { alpha = 0.5f })
                pen(0.63f, Color.White)
                drawRect(hatch(env.palette.fg, (0.51f * u).roundToInt(), maxOf(1f, 0.045f * u)), blendMode = BlendMode.SrcIn)
                canvas.restore()
                // and only the rim of its stroke
                canvas.saveLayer(all, Paint())
                pen(0.8f, env.palette.fg)
                pen(0.63f, Color.Black, BlendMode.Clear)
                canvas.restore()
            }

            val gone = leaving?.invoke() ?: return@Canvas
            val accent = env.palette.accent
            val reach = size.width * 6f

            // four lines come in from the edges of the sheet and close on the numerals
            val open = 1f - PenEase.transform(between(gone, 0f, 300f))
            val seen = 0.85f * between(gone, 0f, 70f)
            val side = gutter.toPx() * 0.5f
            val top = box.top - 5f * u * open
            val bottom = box.bottom + 5f * u * open
            val left = box.left - (box.left + side) * open
            val right = box.right + (width.toPx() + side - box.right) * open
            drawLine(accent, Offset(-reach, top), Offset(reach, top), hair, alpha = seen)
            drawLine(accent, Offset(-reach, bottom), Offset(reach, bottom), hair, alpha = seen)
            drawLine(accent, Offset(left, -reach), Offset(left, reach), hair, alpha = seen)
            drawLine(accent, Offset(right, -reach), Offset(right, reach), hair, alpha = seen)

            // a pencil strikes them through
            val struck = PenEase.transform(between(gone, 300f, 460f))
            if (struck > 0f) rotate(-7f, box.center) {
                val from = box.left - 0.03f * width.toPx()
                val to = box.right + 0.03f * width.toPx()
                drawLine(accent, Offset(from, box.center.y), Offset(from + (to - from) * struck, box.center.y), 2.3.dp.toPx(), cap = StrokeCap.Round)
            }

            // and the sheet is stamped, under the last of its lines
            val stamped = between(gone, 440f, 600f)
            if (stamped > 0f) {
                val ok = env.palette.ok
                val style = env.base.copy(color = ok, letterSpacing = 0.12.em)
                val label = words(env, "ACCESS GRANTED", style)
                val plate = Size(label.width + 1.6f * em, label.height + 0.3f * em)
                val at = Offset(3.dp.toPx(), size.height + 16.dp.toPx() + DateLine.toPx() + lineHeight.toPx() + 7.dp.toPx())
                val hinge = Offset(at.x, at.y + plate.height / 2f)
                val struckIn = between(stamped, 0f, 0.4f)
                val press = 1.5f - 0.5f * easeOut(stamped)
                withTransform({
                    rotate(-3f, hinge)
                    scale(press, press, hinge)
                }) {
                    val gap = 2.5.dp.toPx()
                    drawRect(ok, at, plate, alpha = struckIn, style = Stroke(hair))
                    drawRect(ok, at + Offset(gap, gap), Size(plate.width - 2f * gap, plate.height - 2f * gap), alpha = struckIn, style = Stroke(hair))
                    words(env, "ACCESS GRANTED", style, at + Offset(0.8f * em, 0.15f * em), alpha = struckIn)
                }
            }
        }
    }

    /* ---------------- gauges: measured bars ---------------- */

    override val gaugeSize get() = DpSize(110.dp, 18.dp)

    /**
     * A baseline with tick marks, the measured length hatched above it, and at its end an extension
     * line with a dimension tick, in pencil. The rule is drawn from the left as the bar runs up.
     */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val hair = Hair.toPx()
        val e = size.height / 1.24f
        val base = 0.9f * e
        val top = 0.32f * e
        val ruled = size.width * if (target <= 0f) 1f else (shown / target * 1.5f).coerceIn(0f, 1f)
        drawLine(Ink, Offset(0f, base), Offset(ruled, base), hair)
        for (i in 0..14) {
            val x = size.width * i / 14f
            if (x > ruled) break
            drawLine(Ink, Offset(x, base), Offset(x, base + (if (i % 7 == 0) 0.34f else 0.2f) * e), hair)
        }
        val end = size.width * shown.coerceIn(0f, 1f)
        val ink = if (alert) env.palette.err else env.palette.fg
        if (end > 0f) {
            drawRect(hatch(ink, (0.34f * em(env)).roundToInt(), hair), Offset(0f, top), Size(end, base - top))
            drawLine(ink, Offset(0f, base), Offset(0f, top), hair)
            drawLine(ink, Offset(0f, top), Offset(end, top), hair)
            drawLine(ink, Offset(end, top), Offset(end, base), hair)
        }
        val pencil = env.palette.accent
        drawLine(pencil, Offset(end, 0f), Offset(end, size.height), hair)
        drawLine(pencil, Offset(end - 0.23f * e, 0.46f * e), Offset(end + 0.23f * e, 0f), hair * 1.2f)
    }

    /* ---------------- rows ---------------- */

    /**
     * Lays its content out no wider than it needs and hands the drawing that width: how long the
     * lettering is. The row still takes all the room it was given.
     */
    private class Inked(val paint: ContentDrawScope.(inked: Float) -> Unit) : LayoutModifier, DrawModifier {
        private var inked = 0f

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints.copy(minWidth = 0))
            inked = placeable.width.toFloat()
            return layout(maxOf(placeable.width, constraints.minWidth), placeable.height) { placeable.place(0, 0) }
        }

        override fun ContentDrawScope.draw() = paint(inked)
    }

    /** A line that can be tapped is underlined with a dashed reference line, as long as its lettering. */
    override fun Modifier.tapRow(env: LookEnv): Modifier = then(
        Inked { inked ->
            drawContent()
            val y = size.height - 5.dp.toPx()
            drawLine(
                env.palette.app, Offset(0f, y), Offset(inked, y), Hair.toPx(), alpha = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            )
        },
    )

    /** App names are nodes on a rail. The rail stops a little past the first and the last of them. */
    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier =
        if (!first && !last) this
        else drawWithContent {
            val stub = 6.dp.toPx()
            clipRect(top = if (first) stub else 0f, bottom = if (last) size.height - stub else size.height) { this@drawWithContent.drawContent() }
        }

    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = this
        .drawBehind {
            val at = Offset(env.cell.width * 0.5f, size.height / 2f)
            val r = 3.2.dp.toPx()
            val hair = Hair.toPx()
            drawLine(Faint, Offset(at.x, 0f), Offset(at.x, at.y - r), hair)
            drawLine(Faint, Offset(at.x, at.y + r), Offset(at.x, size.height), hair)
            if (lit) drawCircle(env.palette.app, r, at)
            drawCircle(env.palette.app, r, at, style = Stroke(hair))
        }
        // the name moves over into the gap between the columns, so it keeps all the room it had
        .offset { IntOffset((env.cell.width * 1.3f).roundToInt(), 0) }

    /** The notification is a callout: a leader from a dot down to a shelf under its words. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(top = 9.dp, bottom = 8.dp)
        .then(
            Inked { inked ->
                drawContent()
                val color = lerp(env.palette.accent, env.palette.line, faded())
                val em = em(env)
                val hair = Hair.toPx()
                val shelf = size.height - hair / 2f
                val knee = Offset(2.5f * env.cell.width, shelf)
                val dot = Offset(0.24f * em, size.height - 1.66f * em)
                drawCircle(color, 0.19f * em, dot)
                drawLine(color, dot, knee, hair)
                drawLine(color, knee, Offset(inked, shelf), hair)
            },
        )
        .padding(start = 23.dp, end = 5.dp, bottom = 2.dp)

    /** A small labelled rectangle whose lines run past its corners. An app's has a node in it. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .padding(horizontal = 3.dp)
        .drawBehind {
            val hair = Hair.toPx()
            val past = 4.dp.toPx()
            drawLine(Ink, Offset(-past, 0f), Offset(size.width + past, 0f), hair)
            drawLine(Ink, Offset(-past, size.height), Offset(size.width + past, size.height), hair)
            drawLine(Ink, Offset(0f, -past), Offset(0f, size.height + past), hair)
            drawLine(Ink, Offset(size.width, -past), Offset(size.width, size.height + past), hair)
            if (color == env.palette.app) drawCircle(color, 3.dp.toPx(), Offset(10.dp.toPx(), size.height * 0.52f), style = Stroke(hair))
        }
        .padding(start = if (color == env.palette.app) 19.dp else 9.dp, end = 9.dp, top = 3.dp, bottom = 3.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(color = color, fontSize = 14.5.sp)

    /** The pen at the prompt: a pencil block, lighter than solid, as the hatched box of the drawing is. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.accent.copy(alpha = 0.3f), color = palette.fg)
        blinkOn -> SpanStyle(background = palette.accent.copy(alpha = 0.82f), color = palette.bg)
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 460

    /** A line as wide as the page takes this share of [lineMs] to plot. Shorter ones are done sooner: one pen speed for all. */
    private const val PEN_SHARE = 0.7f

    /**
     * Plotted: a thin baseline is drawn from the left and the lettering is revealed just behind a
     * small pen. The pen lifts where the lettering ends and the baseline fades.
     */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = then(
        Inked plot@{ inked ->
            val p = progress()
            if (p >= 1f || inked <= env.cell.width * 1.5f) {
                // at rest, or an empty line, which has nothing to plot
                if (p > 0f) drawContent()
                return@plot
            }
            if (p <= 0f) return@plot
            val em = em(env)
            val hair = Hair.toPx()
            val rows = ((size.height + 1f) / env.cell.height).toInt().coerceAtLeast(1)
            val bottom = size.height - (size.height - rows * env.cell.height).coerceAtLeast(0f) / 2f
            val base = bottom - 0.2f * em
            val pen = p / PEN_SHARE * size.width
            if (pen < inked) {
                clipRect(right = pen + 0.5f * em) { this@plot.drawContent() }
                drawLine(Ink, Offset(0f, base), Offset(pen, base), hair)
                val x = pen + 0.25f * em
                val tip = bottom - 0.22f * em
                drawLine(env.palette.accent, Offset(x, bottom - env.cell.height), Offset(x, tip), hair)
                drawCircle(env.palette.accent, 0.15f * em, Offset(x, tip))
            } else {
                drawContent()
                val lifted = PEN_SHARE * inked / size.width
                drawLine(Ink, Offset(0f, base), Offset(inked, base), hair, alpha = 1f - between(p, lifted, lifted + 1f - PEN_SHARE))
            }
        },
    )

    /**
     * The sheet with some of its drawing still to do: paper ([light]) and grid ([ruled]) at a strength, the border as
     * far round as the pen has got, and of each line of the page the share [lettered] gives it,
     * from the left. Line 0 is the title strip, and there are `of` of them down to the bottom edge.
     */
    private fun ContentDrawScope.drawing(env: LookEnv, light: Float, ruled: Float, round: Float, lettered: (line: Int, of: Int) -> Float) {
        val tall = size.height - env.keyboard
        val plan = plan(size.width, tall)
        if (light > 0f) paper(size.width, tall, fill = size.height, alpha = light)
        grid(plan, ruled)

        val room = plan.within
        val first = (plan.border.top + plan.bold + Clear.toPx() + stripHeight()).roundToInt().toFloat()
        val row = env.cell.height
        val count = 1 + ceil((room.bottom - first) / row).toInt().coerceAtLeast(1)
        val shown = Path()
        var any = false
        var all = true
        for (i in 0 until count) {
            val share = lettered(i, count).coerceIn(0f, 1f)
            if (share < 1f) all = false
            if (share <= 0f) continue
            any = true
            val from = if (i == 0) room.top else (first + (i - 1) * row).roundToInt().toFloat()
            val to = if (i == 0) first else minOf(room.bottom, (first + i * row).roundToInt().toFloat())
            shown.addRect(Rect(room.left, from, room.left + room.width * share, to))
        }
        when {
            all -> clipRect(room.left, room.top, room.right, room.bottom) { this@drawing.drawContent() }
            any -> clipPath(shown) { this@drawing.drawContent() }
        }
        border(plan, round, nib = true)
    }

    /**
     * The light comes up under the sheet, the grid fades in, the border draws itself clockwise from
     * the top left corner, and the page is plotted line after line behind it.
     */
    override val powerOn: Motion = object : Motion(ms = 1050, readableMs = 400) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) {
                drawContent()
                return
            }
            drawing(
                env,
                light = easeOut(between(t, 0f, 300f)),
                ruled = between(t, 100f, 460f),
                round = between(t, 150f, 860f),
            ) { line, of ->
                val start = 380f + (ms - 380f - 240f) * line / (of - 1)
                smooth(between(t, start, start + 240f))
            }
        }
    }

    /**
     * The linework is taken up again, last line first, then the pen goes back round the border and
     * takes it along; the grid fades and the bare sheet goes dark.
     */
    override val powerOff: Motion = object : Motion(ms = 900) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawing(
                env,
                light = 1f - easeIn(between(t, 700f, 880f)),
                ruled = 1f - between(t, 560f, 800f),
                round = 1f - between(t, 280f, 740f),
            ) { line, of ->
                val start = 420f * (of - 1 - line) / (of - 1)
                1f - smooth(between(t, start, start + 180f))
            }
        }
    }

    private val LiftEase = CubicBezierEasing(0.3f, 0f, 0.5f, 1f)
    private val PullEase = CubicBezierEasing(0.55f, 0f, 0.6f, 0.9f)

    /** The clock is closed in on, struck through and stamped by [Clock]; this is when the sheet starts to go. */
    private const val PULL_AT = 620f

    /**
     * The sheet is lifted by its bottom left corner and drawn off to the left, and the next one lies
     * under it. Only the page can be moved, so a copy of the sheet is drawn under the page and goes
     * with it: its grid, its border and the shadow of its edge.
     */
    override val granted: Motion = object : Motion(ms = 1120) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val p = between(t, PULL_AT, ms.toFloat())
            if (p <= 0f) {
                drawContent()
                return
            }
            // where this page lies on the sheet, taking the screen to begin at the sheet's top
            val gut = gutter.toPx()
            val w = size.width + 2f * gut
            val edge = plan(w, 0f).let { it.border.top + it.bold } + Clear.toPx()
            val above = (edge.roundToInt() + stripHeight()).roundToInt().toFloat()
            val h = above + size.height + ChipStrip.toPx() + edge.roundToInt()
            val plan = plan(w, h)

            val lift = LiftEase.transform(between(p, 0f, 0.26f))
            val pull = PullEase.transform(between(p, 0.26f, 1f))
            val cast = 40.dp.toPx()
            val shade = Color(0xFF000512).copy(alpha = 0.6f * between(p, 0f, 0.2f))
            withTransform({
                translate(-w * (0.025f * lift + 1.195f * pull) - gut, -h * (0.005f * lift + 0.015f * pull) - above)
                rotate(-(1.2f * lift + 8.8f * pull), Offset(0f, h))
            }) {
                drawRect(Brush.horizontalGradient(0f to shade, 1f to Color.Transparent, startX = w, endX = w + cast), Offset(w, 0f), Size(cast, h))
                drawRect(Brush.verticalGradient(0f to shade, 1f to Color.Transparent, startY = h, endY = h + cast), Offset(0f, h), Size(w, cast))
                paper(w, h)
                grid(plan)
                border(plan)
                translate(gut, above) { this@draw.drawContent() }
            }
        }
    }

    /** The words are the stamp [Clock] puts on the sheet. */
    override val grantedWords get() = false

    override val launchMs get() = 520

    private val ZoomEase = CubicBezierEasing(0.6f, 0f, 0.9f, 0.55f)
    private const val SPINNER = "|/-\\"

    /**
     * A detail circle is drawn round the launch line, and the view goes into the detail until it is
     * all there is. The page under it cannot be enlarged, so the circle is: it grows past the edges
     * of the screen with the sheet, its grid and the line enlarged inside it, darkening as it goes.
     */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val ringed = PenEase.transform(between(ms, 20f, 180f))
        if (ringed <= 0f) return
        val gut = gutter.toPx()
        val lettered = env.cell.width * (name.length + 7)
        val middle = Offset(minOf(gut + lettered / 2f, size.width / 2f), line.center.y)
        val round = maxOf(46.dp.toPx(), lettered / 2f + 12.dp.toPx())
        val far = hypot(maxOf(middle.x, size.width - middle.x), maxOf(middle.y, size.height - middle.y))
        val t = between(ms, 190f, 480f)
        val zoom = 1f + (far / round * 1.05f - 1f) * ZoomEase.transform(t)
        val r = round * zoom

        if (t > 0f) clipPath(Path().apply { addOval(Rect(middle, r)) }) {
            val there = between(t, 0f, 0.12f)
            val tall = size.height - env.keyboard
            val plan = plan(size.width, tall)
            drawRect(paperAt(middle, size.width, tall), alpha = there)
            val cell = plan.cell.toFloat()
            val pen = plan.pen * zoom
            var i = ceil((middle.x - round - plan.border.left) / cell).toInt()
            while (plan.border.left + i * cell <= middle.x + round) {
                val x = middle.x + (plan.border.left + i * cell - middle.x) * zoom
                drawRect(GridInk, Offset(x, middle.y - r), Size(pen, 2f * r), alpha = there * if (i % 5 == 0) 0.2f else 0.095f)
                i++
            }
            var j = ceil((middle.y - round - plan.border.top) / cell).toInt()
            while (plan.border.top + j * cell <= middle.y + round) {
                val y = middle.y + (plan.border.top + j * cell - middle.y) * zoom
                drawRect(GridInk, Offset(middle.x - r, y), Size(2f * r, pen), alpha = there * if (j % 5 == 0) 0.2f else 0.095f)
                j++
            }
            val legible = there * (1f - between(t, 0.35f, 0.75f))
            if (legible > 0f) scale(zoom, zoom, middle) {
                words(env, SPINNER[(ms / 70f).toInt() % SPINNER.length].toString(), env.base.copy(color = env.palette.accent), Offset(gut, line.top), legible)
                words(env, "exec $name", env.base, Offset(gut + 2f * env.cell.width, line.top), legible)
            }
            drawRect(Dark, alpha = if (t < 0.4f) 0.25f * t / 0.4f else 0.25f + 0.75f * between(t, 0.4f, 0.92f))
        }
        drawArc(
            env.palette.accent, startAngle = -135f, sweepAngle = 360f * ringed, useCenter = false,
            topLeft = middle - Offset(r, r), size = Size(2f * r, 2f * r),
            style = Stroke(Bold.toPx() * zoom, cap = StrokeCap.Round),
        )
    }
}
