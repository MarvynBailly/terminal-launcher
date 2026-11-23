package com.terminallauncher.ui.looks

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
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
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.GRANTED_TEXT
import com.terminallauncher.ui.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sumi: ink on handmade paper. Warm paper, charcoal ink in a few dilutions, indigo for app names
 * and one vermilion, kept for the seal and for alerts. The brush is in the drawing, not in the
 * ground: a gauge is a single stroke, a rule is a thin uneven line, and what moves is ink in water.
 */
object Sumi : Look() {
    override val id = "sumi"

    private val Paper = Palette(
        bg = Color(0xFFF1ECDF), fg = Color(0xFF33312D), dim = Color(0xFF7A746A), line = Color(0xFFCBC3B2),
        accent = Color(0xFF161513), app = Color(0xFF2B3F6C), ok = Color(0xFF4E6A58), err = Color(0xFFC4432A),
        light = true,
    )

    /** Ink as it is while wet, on a loaded brush, let down with water, and in the wash behind the page. */
    private val Wet = Color(0xFF0C0B0A)
    private val Loaded = Color(0xFF1E1C1A)
    private val Thin = Color(0xFF68645D)
    private val Tone = Color(0xFF302E2B)

    /** The ground of the app that opens: the bloom ends in it. */
    private val Night = Color(0xFF101216)

    override fun palette(theme: String): Palette = Paper

    /* ---------------- type: DM Mono, a touch under the usual size inside wider margins ---------------- */

    private val Mono = FontFamily(
        Font(R.font.dm_mono_regular, FontWeight.Normal),
        Font(R.font.dm_mono_medium, FontWeight.Medium),
    )
    private val Mincho = FontFamily(Font(R.font.shippori_mincho_regular))

    override val font get() = Mono
    override val fontSize get() = 14.8.sp
    override val lineHeight get() = 23.sp
    override val advance get() = 0.6f
    override val gutter get() = 18.dp

    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        // burnt ink is also pressed harder
        Style.ACCENT -> SpanStyle(color = palette.accent, fontWeight = FontWeight.Medium)
        Style.MARK -> SpanStyle(color = palette.bg, background = palette.accent)
        else -> SpanStyle(color = palette.of(style))
    }

    /* ---------------- small tools ---------------- */

    /** Smooth noise along a line, 0 to 1: the same for the same input. */
    private fun noise(x: Float, salt: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val a = dice(i, salt)
        return a + (dice(i + 1, salt) - a) * f * f * (3f - 2f * f)
    }

    /** The same, round a circle: [turn] from 0 to 1 is once round, past [cells] bumps. */
    private fun ring(turn: Float, cells: Int, salt: Int): Float {
        val x = (turn - floor(turn)) * cells
        val i = x.toInt() % cells
        val f = x - x.toInt()
        val a = dice(i, salt)
        return a + (dice((i + 1) % cells, salt) - a) * f * f * (3f - 2f * f)
    }

    /**
     * How far ink has got from [at] when it has spread [radius] on average: the points of its edge,
     * as x and y in turn, from [from] to [to] of the way round (0 is to the right, 0.25 straight down).
     */
    private fun edge(at: Offset, radius: Float, rough: Float, salt: Int, cells: Int, from: Float = 0f, to: Float = 1f, steps: Int = 72): FloatArray {
        val xy = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val turn = from + (to - from) * i / steps
            val lobe = 0.62f * ring(turn, cells, salt) + 0.38f * ring(turn, cells * 2 + 3, salt + 1)
            val r = radius * (1f + rough * (2f * lobe - 1f))
            val a = turn * 2f * PI.toFloat()
            xy[2 * i] = at.x + r * cos(a)
            xy[2 * i + 1] = at.y + r * sin(a)
        }
        return xy
    }

    /** Carries the path on through the points of [xy], rounding every corner. */
    private fun Path.through(xy: FloatArray, begin: Boolean = false): Path {
        val n = xy.size / 2
        if (begin) moveTo(xy[0], xy[1]) else lineTo(xy[0], xy[1])
        for (i in 1 until n - 1) {
            quadraticTo(xy[2 * i], xy[2 * i + 1], (xy[2 * i] + xy[2 * i + 2]) / 2f, (xy[2 * i + 1] + xy[2 * i + 3]) / 2f)
        }
        lineTo(xy[2 * n - 2], xy[2 * n - 1])
        return this
    }

    /* ---------------- the paper, and the wash behind the page ---------------- */

    /** The fibres of the sheet: pale ones under the ink, darker ones over it. Made once for a size of screen. */
    private class Fibres(val size: Size, val pale: Path, val dark: Path)

    private var sheet: Fibres? = null

    private fun DrawScope.fibres(): Fibres {
        sheet?.let { if (it.size == size) return it }
        val u = size.width / 100f
        fun strands(count: Int, salt: Int) = Path().apply {
            for (i in 0 until count) {
                val x = (dice(i, salt) * 1.08f - 0.04f) * size.width
                val y = (dice(i, salt + 1) * 1.04f - 0.02f) * size.height
                val turn = dice(i, salt + 2) * 2f * PI.toFloat()
                val long = (2.2f + 6.5f * dice(i, salt + 3)) * u
                val bend = (dice(i, salt + 4) - 0.5f) * long * 0.8f
                val dx = cos(turn) * long
                val dy = sin(turn) * long
                moveTo(x, y)
                quadraticTo(x + dx / 2f - sin(turn) * bend, y + dy / 2f + cos(turn) * bend, x + dx, y + dy)
            }
        }
        return Fibres(size, pale = strands(30, 101), dark = strands(52, 111)).also { sheet = it }
    }

    private fun DrawScope.paleFibres() {
        drawPath(fibres().pale, Color(0xFFFFFDF4), alpha = 0.75f, style = Stroke(0.7.dp.toPx(), cap = StrokeCap.Round))
    }

    /** The paper's tooth and its darker fibres. They lie over everything, so that ink sits in the paper. */
    private fun DrawScope.tooth() {
        val cell = 0.8.dp.toPx().coerceAtLeast(1f)
        val speckle = grain(Color(0xFF6B5C42), lo = -0.07f, hi = 0.12f, salt = 3, size = 128)
        scale(cell, cell, pivot = Offset.Zero) { drawRect(speckle, size = Size(size.width / cell + 1f, size.height / cell + 1f)) }
        drawPath(fibres().dark, Color(0xFF7D6C4C), alpha = 0.3f, style = Stroke(0.7.dp.toPx(), cap = StrokeCap.Round))
    }

    /** A sheet with nothing on it yet: what the screen is before it wakes and after it locks. */
    private fun DrawScope.blankPaper() {
        drawRect(Paper.bg)
        paleFibres()
        tooth()
    }

    /** A far ridge: [rough] and [peaks] (where, how high, how broad) shape its crest; [mist] is how much of it shows from left to right. */
    private class Ridge(val salt: Int, val rough: Float, val depth: Float, val ink: Float, val peaks: List<FloatArray>, val mist: List<Pair<Float, Float>>)

    private val FarRidge = Ridge(
        salt = 21, rough = 1.5f, depth = 9f, ink = 0.11f,
        peaks = listOf(floatArrayOf(0.2f, 8.5f, 0.17f), floatArrayOf(0.47f, 3.4f, 0.1f), floatArrayOf(0.92f, 5.6f, 0.16f)),
        mist = listOf(0f to 0.8f, 0.28f to 1f, 0.52f to 0.55f, 0.68f to 0.1f, 0.84f to 0.45f, 1f to 0.8f),
    )
    private val NearRidge = Ridge(
        salt = 8, rough = 2.1f, depth = 10f, ink = 0.15f,
        peaks = listOf(floatArrayOf(0f, 2.6f, 0.2f), floatArrayOf(0.66f, 7.6f, 0.2f), floatArrayOf(1.06f, 5f, 0.15f)),
        mist = listOf(0f to 0.08f, 0.28f to 0.3f, 0.52f to 0.95f, 0.8f to 1f, 1f to 0.6f),
    )

    /** Darkest along its crest, thinning downwards into mist, and lost in places. [u] is a hundredth of the screen's width. */
    private fun DrawScope.ridge(base: Float, u: Float, r: Ridge) {
        val w = size.width
        val first = -3
        val last = 93
        val crest = Path()
        val under = Path()
        for (i in first..last) {
            val x = i / 90f
            var rise = r.rough * (noise(x * 12f, r.salt) + 0.32f * noise(x * 42f, r.salt + 1))
            for (p in r.peaks) rise += p[1] * exp(-(abs(x - p[0]) / p[2]).pow(1.6f))
            val y = base - rise * u
            if (i == first) {
                crest.moveTo(x * w, y)
                under.moveTo(x * w, y)
            } else {
                crest.lineTo(x * w, y)
                under.lineTo(x * w, y)
            }
        }
        val bed = base + r.depth * u * 2f
        under.lineTo(last / 90f * w, bed)
        under.lineTo(first / 90f * w, bed)
        under.close()
        fun veil(alpha: Float) = Brush.horizontalGradient(*r.mist.map { (x, a) -> x to Tone.copy(alpha = alpha * a) }.toTypedArray(), startX = 0f, endX = w)
        val passes = 14
        val pass = veil(r.ink / passes)
        clipPath(under) {
            for (k in 1..passes) {
                drawPath(crest, pass, style = Stroke(2f * r.depth * u * (k / passes.toFloat()).pow(1.5f), join = StrokeJoin.Round))
            }
        }
        drawPath(crest, veil(r.ink * 0.8f), style = Stroke(0.45f * u, join = StrokeJoin.Round))
    }

    /**
     * A moon and two far ridges in very thin ink. They stand still, in the part of the screen the
     * keyboard leaves free, and are faint enough to be written over.
     */
    private fun DrawScope.wash(env: LookEnv) {
        val u = size.width / 100f
        val foot = minOf(size.height * 0.57f, size.height - env.keyboard - 52.dp.toPx())
        val near = foot - 8f * u
        val far = near - 12f * u
        // the moon: one pass, pooled a little towards its edge
        val r = 10.5f * u
        val c = Offset(78f * u, far - 22f * u)
        val moon = Path()
        for (i in 0 until 64) {
            val t = i / 64f * 2f * PI.toFloat()
            val rr = r * (1f + 0.011f * sin(3f * t + 1f) + 0.006f * sin(7f * t + 4f))
            if (i == 0) moon.moveTo(c.x + rr * cos(t), c.y + rr * sin(t)) else moon.lineTo(c.x + rr * cos(t), c.y + rr * sin(t))
        }
        moon.close()
        drawPath(
            moon,
            Brush.radialGradient(
                0f to Tone.copy(alpha = 0.035f), 0.62f to Tone.copy(alpha = 0.06f), 0.88f to Tone.copy(alpha = 0.095f), 1f to Tone.copy(alpha = 0.125f),
                center = Offset(c.x - r * 0.1f, c.y - r * 0.14f), radius = r * 1.18f,
            ),
        )
        ridge(far, u, FarRidge)
        ridge(near, u, NearRidge)
    }

    override fun DrawScope.background(env: LookEnv) {
        paleFibres()
        wash(env)
    }

    override fun DrawScope.overlay(env: LookEnv) {
        tooth()
    }

    /* ---------------- the seal ---------------- */

    /** A square of vermilion with the prompt cut out of it, in a box 40 wide. */
    private val SealShape: Path by lazy {
        Path().apply {
            fillType = PathFillType.EvenOdd
            // cut by hand: no edge of it is quite straight
            val corners = listOf(Offset(3.5f, 3.5f), Offset(36.5f, 3.5f), Offset(36.5f, 36.5f), Offset(3.5f, 36.5f))
            val xy = ArrayList<Float>()
            for (s in 0 until 4) {
                val a = corners[s]
                val step = (corners[(s + 1) % 4] - a) / 6f
                val out = Offset(step.y, -step.x) / 5.5f
                for (i in 0..5) {
                    val p = a + step * i.toFloat() + if (i == 0) Offset.Zero else out * ((dice(s * 8 + i, 61) - 0.5f) * 0.9f)
                    xy += p.x
                    xy += p.y
                }
            }
            val n = xy.size / 2
            fun x(i: Int) = xy[2 * (i % n)]
            fun y(i: Int) = xy[2 * (i % n) + 1]
            moveTo((x(0) + x(1)) / 2f, (y(0) + y(1)) / 2f)
            for (i in 1..n) quadraticTo(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
            close()
            // the chevron of the prompt, and its underscore
            val a = Offset(11.5f, 11.5f)
            val b = Offset(21f, 19.5f)
            val c = Offset(11.5f, 27.5f)
            val half = 2f
            val d1 = (b - a) / (b - a).getDistance()
            val d2 = (c - b) / (c - b).getDistance()
            val n1 = Offset(-d1.y, d1.x)
            val n2 = Offset(-d2.y, d2.x)
            val m = (n1 + n2) / (n1 + n2).getDistance()
            val mitre = m * (half / (m.x * n1.x + m.y * n1.y))
            val a0 = a - d1 * half
            val c0 = c + d2 * half
            val elbow = listOf(a0 + n1 * half, b + mitre, c0 + n2 * half, c0 - n2 * half, b - mitre, a0 - n1 * half)
            moveTo(elbow[0].x, elbow[0].y)
            for (i in 1 until elbow.size) lineTo(elbow[i].x, elbow[i].y)
            close()
            addRect(Rect(20.5f, 27f, 32f, 31f))
        }
    }

    private val SealInk = Brush.linearGradient(0f to Color(0xFFC4432A), 1f to Color(0xFFCD5339), start = Offset(4f, 4f), end = Offset(36f, 36f))

    /** The seal, [side] across, with its middle at [centre]. */
    private fun DrawScope.seal(centre: Offset, side: Float, env: LookEnv, alpha: Float) {
        val u = side / 40f
        withTransform({
            translate(centre.x - side / 2f, centre.y - side / 2f)
            scale(u, u, Offset.Zero)
        }) {
            drawPath(SealShape, SealInk, alpha = alpha)
            // where the stone did not take the ink
            for (i in 0 until 16) {
                drawCircle(env.palette.bg, radius = 0.22f + 0.42f * dice(i, 71), center = Offset(5f + 30f * dice(i, 72), 5f + 30f * dice(i, 73)), alpha = alpha * 0.75f)
            }
        }
    }

    /** Kept small in the corner while the phone is open, beside the chips. */
    override fun Modifier.frame(env: LookEnv): Modifier = drawBehind {
        if (env.locked) return@drawBehind
        val side = minOf(size.width * 0.076f, 34.dp.toPx())
        val centre = Offset(size.width - gutter.toPx() - side / 2f, size.height - 24.dp.toPx())
        rotate(-3f, centre) { seal(centre, side, env, alpha = 0.9f) }
    }

    /* ---------------- parts ---------------- */

    /** One thin stroke of a small brush at height [y]: heavier where it lands, running out at the far end. */
    private fun DrawScope.rule(from: Float, to: Float, y: Float, alpha: Float) {
        val steps = 44
        val unit = 1.dp.toPx()
        val under = FloatArray(steps + 1)
        val path = Path()
        for (i in 0..steps) {
            val p = i / steps.toFloat()
            val load = (0.5f + 0.65f * exp(-p * 11f)) * (1f - between(p, 0.86f, 1f).pow(1.5f)) * (0.55f + 0.45f * between(p, 0f, 0.012f))
            val half = unit * load * (0.75f + 0.6f * noise(p * 17f, 6))
            val mid = y + unit * 0.7f * (noise(p * 5f, 7) - 0.5f)
            val x = from + (to - from) * p
            if (i == 0) path.moveTo(x, mid - half) else path.lineTo(x, mid - half)
            under[i] = mid + half
        }
        for (i in steps downTo 0) path.lineTo(from + (to - from) * i / steps, under[i])
        path.close()
        drawPath(path, Color(0xFF2E2C29), alpha = alpha)
    }

    /** Small and spaced, over a drawn rule. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 8.dp)
        .drawBehind { rule(-5.dp.toPx(), size.width + 5.dp.toPx(), size.height - 6.dp.toPx(), alpha = 0.6f) }
        .padding(bottom = 15.dp)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 11.8.sp, lineHeight = 16.sp, letterSpacing = 0.05.em, color = env.palette.dim)

    private val ClockInk = Brush.verticalGradient(0.2f to Color(0xFF0F0E0D), 0.86f to Color(0xFF4D4A45))

    /** A large light serif for the hour, its ink thinner towards the foot. A right password presses the seal beside it. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val tall = with(LocalDensity.current) { minOf(width * 0.295f, 124.dp).toSp() }
        BasicText(
            text = time,
            style = TextStyle(brush = ClockInk, fontFamily = Mincho, fontSize = tall, lineHeight = tall, letterSpacing = (-0.02).em, fontFeatureSettings = "lnum"),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .width(width)
                .drawWithContent {
                    drawContent()
                    if (leaving != null) press(leaving(), env)
                }
                // The face leaves a great deal of room over and under its figures. The box is cut to
                // the figures, so the date sits close under them: it ends a little above their foot.
                .layout { measurable, constraints ->
                    val text = measurable.measure(constraints)
                    val figures = tall.toPx() * FIGURE
                    val top = text[FirstBaseline] - figures - CLOCK_OVER.toPx()
                    layout(text.width, (figures + CLOCK_OVER.toPx() - CLOCK_UNDER.toPx()).roundToInt()) { text.place(0, -top.roundToInt()) }
                },
        )
    }

    /** How tall the face's figures are, as a share of its size, and where the clock's box ends: over their heads and above their foot. */
    private const val FIGURE = 0.712f
    private val CLOCK_OVER = 9.dp
    private val CLOCK_UNDER = 10.dp

    /** The seal coming down at the right of the clock [ms] after the password was right, and the words under it. */
    private fun DrawScope.press(ms: Float, env: LookEnv) {
        val side = size.width * 0.2f
        val foot = size.height + CLOCK_UNDER.toPx()             // where the figures stand
        val centre = Offset(size.width - side * 0.54f, foot + 10.dp.toPx() - side / 2f)
        val p = between(ms, 0f, 300f)
        val down = easeIn(between(p, 0f, 0.52f))
        val set = easeOut(between(p, 0.52f, 1f))
        // once it has landed, the ink spreads a little past its edge
        val bled = between(ms, 156f, 1000f)
        if (bled > 0f) {
            val a = if (bled < 0.4f) 0.16f * bled / 0.4f else 0.16f - 0.06f * (bled - 0.4f) / 0.6f
            rotate(-4f, centre) {
                for (i in 0 until 2) {
                    val half = side * 0.4125f * (1.02f + 0.06f * easeOut(bled)) + i * 1.4.dp.toPx()
                    drawRoundRect(env.palette.err, Offset(centre.x - half, centre.y - half), Size(half * 2f, half * 2f), CornerRadius(side * 0.08f), alpha = a / (i + 1))
                }
            }
        }
        val grown = 1.75f - 0.82f * down + 0.07f * set
        withTransform({
            translate(size.width * 0.011f * (1f - down), -size.width * 0.055f * (1f - down))
            rotate(-13f + 10f * down - set, centre)
            scale(grown, grown, centre)
        }) { seal(centre, side, env, alpha = down) }
        // the words, soaking in under it
        val said = between(ms, 190f, 560f)
        if (said > 0f) {
            val style = env.base.copy(fontSize = 12.7.sp, letterSpacing = 0.16.em, color = lerp(Wet, env.palette.accent, said))
            val room = words(env, GRANTED_TEXT, style)
            val at = Offset(size.width - room.width, foot + 15.dp.toPx())
            clipRect(left = at.x, top = at.y, right = at.x + room.width * easeOut(said) * 1.02f, bottom = at.y + room.height) {
                words(env, GRANTED_TEXT, style, at, alpha = 0.3f + 0.7f * said)
            }
        }
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(fontSize = 12.7.sp, letterSpacing = 0.1.em, color = env.palette.dim)
        LockLine.DATE -> TextStyle(fontFamily = Mincho, fontSize = 22.sp, lineHeight = 32.sp, letterSpacing = 0.06.em, color = env.palette.fg)
        LockLine.INFO -> env.base.copy(color = env.palette.dim)
    }

    override val gaugeSize get() = DpSize(110.dp, 14.dp)

    private val InkTones = listOf(Color(0xFF12110F), Color(0xFF2A2826), Color(0xFF56524C))
    private val RedTones = listOf(Color(0xFFA8311B), Color(0xFFC4432A), Color(0xFFD9694F))

    /**
     * One brush stroke, as long as the value, over a faint pencil line that is the whole: heavy
     * where the brush lands, each hair running dry in its own place where it lifts. It is painted
     * out along its length as the gauge arrives.
     */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val pencil = env.palette.line.copy(alpha = 0.85f)
        val thin = (0.6.dp.toPx()).coerceAtLeast(1f)
        drawRect(pencil, Offset(0f, (size.height - thin) / 2f), Size(size.width, thin))
        drawRect(pencil, Offset(size.width - thin, size.height * 0.28f), Size(thin, size.height * 0.44f))
        if (target <= 0f || shown <= 0f) return

        // drawn in a box 100 long and 10 high, as the value is in hundredths
        val ux = size.width / 100f
        val uy = size.height / 10f
        val pct = (target * 100f).coerceIn(4f, 100f)
        val salt = (target * 1000f).toInt() + if (alert) 7919 else 0
        var drawn = 0
        fun r() = dice(drawn++, salt)

        val path = Path()
        val phase = r() * 6.28f
        val sway = 0.25f + r() * 0.3f
        val tilt = (r() - 0.5f) * 0.7f
        val lean = sway * sin(phase) - tilt / 2f
        // where the brush lands: a rounded head that the hairs run out of
        path.moveTo(6f * ux, (8.9f + lean) * uy)
        path.quadraticTo(0.8f * ux, (8.9f + lean) * uy, 0.3f * ux, (5.1f + lean) * uy)
        path.quadraticTo(-0.2f * ux, (1.3f + lean) * uy, 5.5f * ux, (1.1f + lean) * uy)
        path.close()
        val top = ArrayList<Offset>()
        val bottom = ArrayList<Offset>()
        var start = Offset.Zero
        var end = Offset.Zero
        // each run of a hair ends in a point, as a streak does
        fun lift() {
            if (top.isNotEmpty()) {
                path.moveTo(start.x * ux, start.y * uy)
                for (o in top) path.lineTo(o.x * ux, o.y * uy)
                path.lineTo(end.x * ux, end.y * uy)
                for (i in bottom.indices.reversed()) path.lineTo(bottom[i].x * ux, bottom[i].y * uy)
                path.close()
            }
            top.clear()
            bottom.clear()
        }
        val hairs = 9
        for (k in 0 until hairs) {
            val off = k / (hairs - 1f) * 2f - 1f                 // -1 the top hair, 1 the bottom one
            val reach = pct * (1.04f - 0.14f * abs(off).pow(1.5f) - r() * 0.08f)
            val load = 0.96f + r() * 0.3f
            // the brush lands on its belly: the middle hairs touch first
            var x = 2f * off * off + 0.5f * (off + 1f) + r() * 0.5f
            while (x <= reach) {
                val p = x / reach
                val press = 1f + 0.26f * exp(-x / 4f) - 0.3f * p * p
                val y = 5f + off * 3.05f * press + sway * sin(x * 0.07f + phase) + tilt * (p - 0.5f) + (r() - 0.5f) * 0.14f
                val dry = p * p * p * p
                val half = 0.6f * load * (1f - 0.88f * dry) + (r() - 0.5f) * (0.07f + 0.5f * dry)
                if (half < 0.15f || (p > 0.7f && r() < dry * 0.3f)) lift()
                else {
                    if (top.isEmpty()) start = Offset(x - 0.9f, y)
                    end = Offset(x + 0.9f, y)
                    top += Offset(x, y - half)
                    bottom += Offset(x, y + half)
                }
                x += if (p < 0.6f) 2.6f else 1.2f
            }
            lift()
        }

        val tones = if (alert) RedTones else InkTones
        val ink = Brush.horizontalGradient(0f to tones[0], 0.62f to tones[1], 1f to tones[2], startX = 0f, endX = pct * ux)
        val painted = if (shown >= target) size.width * 1.2f else shown / target * pct * 1.06f * ux
        clipRect(left = -size.width * 0.03f, top = -size.height * 0.4f, right = painted, bottom = size.height * 1.4f) {
            drawPath(path, ink, alpha = if (shown >= target) 0.9f else 1f)
        }
    }

    /** A command that was typed opens a section: a thin uneven line above it. */
    override fun Modifier.echoRow(env: LookEnv): Modifier = this
        .padding(top = 8.dp)
        .drawBehind { rule(0f, size.width, 2.dp.toPx(), alpha = 0.34f) }
        .padding(top = 9.dp)

    /** Under the finger the indigo runs a little past the name. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = if (!lit) this else drawBehind {
        val out = 3.dp.toPx()
        drawRoundRect(env.palette.app, Offset(-out, 6.dp.toPx() - out), Size(size.width - env.cell.width * 2f + out * 2f, size.height - 12.dp.toPx() + out * 2f), CornerRadius(out * 1.4f), alpha = 0.2f)
    }

    /** An ink dot hanging in the margin: wet, with water round it, while the note is news. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 7.dp)
        .drawBehind {
            val wet = 1f - faded()
            val r = 4.3.dp.toPx()
            val c = Offset(-11.dp.toPx(), env.cell.height / 2f)
            val ink = env.palette.accent
            if (wet > 0f) {
                drawCircle(
                    Brush.radialGradient(0.3f to Color.Transparent, 0.62f to ink.copy(alpha = 0.26f * wet), 1f to Color.Transparent, center = c, radius = r * 2.3f),
                    radius = r * 2.3f, center = c,
                )
            }
            val blot = Path().through(edge(c, r, rough = 0.1f, salt = 77, cells = 5, steps = 24), begin = true)
            blot.close()
            drawPath(blot, Brush.radialGradient(0f to ink, 0.62f to ink, 0.86f to ink.copy(alpha = 0.5f), 1f to Color.Transparent, center = c, radius = r * 1.12f))
        }

    /** Paper labels with a thin drawn edge, no corner quite like another. */
    private val Label = RoundedCornerShape(topStart = 3.dp, topEnd = 5.dp, bottomEnd = 3.dp, bottomStart = 4.5.dp)

    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .background(Color(0xF0F6F2E8), Label)
        .border(1.dp, env.palette.fg.copy(alpha = 0.27f), Label)
        .padding(horizontal = 9.dp, vertical = 3.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(color = color, fontSize = 13.3.sp)

    /** A small block of ink that breathes: it thins, and never quite goes. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line, color = palette.fg)
        blinkOn -> SpanStyle(background = palette.accent.copy(alpha = 0.84f), color = palette.bg)
        else -> SpanStyle(background = palette.accent.copy(alpha = 0.16f))
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 760

    private val Soak = CubicBezierEasing(0.3f, 0.5f, 0.3f, 1f)
    private val Whole = Paint()

    /** The way ink soaks into paper: from the left, soft-edged, spread and dark while it is wet. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val soaked = Soak.transform(between(p, 0f, 0.64f))
        val wet = 1f - smooth(between(p, 0.12f, 1f))
        val room = 3.dp.toPx()
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(-room, -room, size.width + room, size.height + room), Whole)
            val spread = wet * 0.55.dp.toPx()
            if (spread > 0.05f) {
                translate(-spread, 0f) { this@drawWithContent.drawContent() }
                translate(spread, spread * 0.6f) { this@drawWithContent.drawContent() }
            }
            this@drawWithContent.drawContent()
            if (wet > 0f) drawRect(Wet, Offset(-room, -room), Size(size.width + 2f * room, size.height + 2f * room), alpha = 0.85f * wet, blendMode = BlendMode.SrcAtop)
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width * (2f * soaked - 0.98f), endX = size.width * (2f * soaked - 0.5f)),
                Offset(-room, -room), Size(size.width + 2f * room, size.height + 2f * room),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }

    /**
     * Waking: a wash of ink bleeds across blank paper from the top left corner, thick at its front,
     * and the page is under it as it dries.
     */
    override val powerOn: Motion = object : Motion(ms = 1250, readableMs = 520, backdrop = Paper.bg) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) {
                drawContent()
                return
            }
            blankPaper()
            val p = (t / ms).coerceIn(0f, 1f)
            val reach = hypot(size.width, size.height - env.keyboard)
            val spread = reach * (-0.06f + 1.85f * p.pow(1.15f))
            if (spread <= 0f) return
            val from = Offset(-0.04f * size.width, -0.02f * size.height)
            val xy = edge(from, spread, rough = 0.22f, salt = 11, cells = 13, from = -0.05f, to = 0.3f, steps = 56)
            val front = Path().through(xy, begin = true)
            val inked = Path().apply {
                moveTo(from.x, from.y)
                through(xy)
                close()
            }
            val drying = 1f - between(p, 0.82f, 1f)
            clipPath(inked) {
                this@draw.drawContent()
                // the wash: full as far back as the front is uneven, then thinning away behind it
                val outer = spread * 1.22f
                val bays = spread * 0.78f / outer
                val band = reach * 0.5f / outer
                val begins = maxOf(0f, bays - band)
                val stops = ArrayList<Pair<Float, Color>>()
                for (i in 0..6) {
                    val x = begins + (bays - begins) * i / 6f
                    stops += x to Loaded.copy(alpha = 0.72f * drying * (1f - (bays - x) / band).pow(2.2f))
                }
                stops += 1f to Loaded.copy(alpha = 0.72f * drying)
                drawRect(Brush.radialGradient(*stops.toTypedArray(), center = from, radius = outer))
                // and thick where it leads
                for (wide in floatArrayOf(4f, 10f, 22f)) {
                    drawPath(front, Loaded, alpha = 0.16f * drying, style = Stroke(2f * wide.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            // and a little water runs ahead of the ink
            drawPath(front, Thin, alpha = 0.3f * drying, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        }
    }

    /**
     * Locking: every mark lets go of its edges, thins to a pale wash that sinks a little, and the
     * paper is left blank.
     */
    override val powerOff: Motion = object : Motion(ms = 950, backdrop = Paper.bg) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t <= 0f) {
                drawContent()
                return
            }
            blankPaper()
            val p = (t / ms).coerceIn(0f, 1f)
            if (p >= 1f) return
            val left = (1f - between(p, 0.12f, 1f)).pow(1.3f)              // how much ink the page still holds
            val crisp = left * (1f - smooth(between(p, 0f, 0.4f)))          // and how much of that has not let go yet
            val loose = (left - crisp) / 3f
            val spread = easeOut(p) * 6.dp.toPx()
            val sink = p * 12.dp.toPx()
            val bounds = Rect(0f, 0f, size.width, size.height)
            drawIntoCanvas { canvas ->
                // the same page three times over, each a little further out: what a mark is when its edges have gone
                if (loose > 0.002f) for (i in 0 until 3) {
                    val turn = (i / 3f + 0.08f) * 2f * PI.toFloat()
                    canvas.saveLayer(bounds, Paint().apply { alpha = (loose / (1f - crisp - (2 - i) * loose)).coerceIn(0f, 1f) })
                    translate(cos(turn) * spread, sin(turn) * spread * 0.7f + sink) { this@draw.drawContent() }
                    canvas.restore()
                }
                if (crisp > 0.002f) {
                    canvas.saveLayer(bounds, Paint().apply { alpha = crisp })
                    translate(0f, sink * 0.3f) { this@draw.drawContent() }
                    canvas.restore()
                }
            }
        }
    }

    override val grantedWords get() = false

    private const val SWEEP_AT = 430f
    private const val SWEEP_MS = 700f

    /** How much ink one hair of the broad brush holds, 0 to 1. Neighbours hold alike. */
    private fun hair(k: Int): Float = 0.55f * dice(k, 51) + 0.45f * dice(k / 4, 52)

    /**
     * A right password: the seal is pressed beside the clock (see [press]) and the paper gives a
     * little under it. Then one broad wet stroke takes the page away from left to right: darkest at
     * its ragged front, each hair leaving its own tone and drying at its own pace.
     */
    override val granted: Motion = object : Motion(ms = 1150) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t < SWEEP_AT) {
                val give = 2.2.dp.toPx() * sin(between(t, 150f, 400f).pow(0.6f) * PI.toFloat())
                translate(0f, give) { this@draw.drawContent() }
                return
            }
            val w = size.width
            val h = size.height
            val front = -0.1f + 2f * (t - SWEEP_AT) / SWEEP_MS            // in widths of the page
            val pitch = 5.dp.toPx()
            val hairs = (h / pitch).toInt() + 1
            val at = FloatArray(hairs)
            // what the brush has not reached yet is still the page
            val untouched = Path()
            var any = false
            for (k in 0 until hairs) {
                val lag = ((k + 0.5f) * pitch - h / 2f) / (h / 2f)      // the middle of the brush leads
                at[k] = w * (front + 0.09f * (hair(k) - 0.5f) - 0.05f * lag * lag)
                if (at[k] < w) {
                    untouched.addRect(Rect(at[k].coerceAtLeast(0f), k * pitch, w, (k + 1) * pitch))
                    any = true
                }
            }
            if (any) clipPath(untouched) { this@draw.drawContent() }
            // behind it, wash: full for a hand's breadth, then drying
            val tail = w * 0.74f
            fun streak(a: Float, b: Float, c: Float) = Brush.horizontalGradient(
                0f to Color.Transparent, 0.2f to Thin.copy(alpha = c), 0.4f to Thin.copy(alpha = b), 0.6f to Thin.copy(alpha = a),
                0.81f to Thin.copy(alpha = 0.72f), 0.89f to Thin.copy(alpha = 0.72f), 0.965f to Thin, 1f to Thin.copy(alpha = 0f),
                startX = -tail, endX = 0f,
            )
            val slow = streak(0.49f, 0.285f, 0.117f)
            val fast = streak(0.33f, 0.113f, 0.02f)
            for (k in 0 until hairs) {
                if (at[k] <= 0f || at[k] - tail >= w) continue
                val held = hair(k)
                translate(at[k], 0f) {
                    drawRect(if (held > 0.5f) slow else fast, Offset(-tail, k * pitch), Size(tail, pitch + 0.5f), alpha = (0.5f + 0.5f * held).coerceAtMost(1f))
                }
            }
        }
    }

    override val launchMs get() = 720

    private const val DROP_MS = 100f
    private const val BLACK_MS = 440f

    /** A single drop falls on the launched line and blooms outward, a fringe of water ahead of it, until the page is black. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        if (ms <= 0f) return
        val at = Offset(gutter.toPx() + env.cell.width * 0.5f, line.center.y)
        if (ms < DROP_MS) {
            val fall = easeIn(ms / DROP_MS)
            val drop = 3.4.dp.toPx()
            val wide = drop * (0.55f + 0.45f * fall)
            val tall = drop * (1.7f - 0.7f * fall)
            val y = at.y - (1f - fall) * size.height * 0.16f
            drawOval(Night, Offset(at.x - wide, y - tall), Size(wide * 2f, tall * 2f), alpha = between(ms, 0f, DROP_MS * 0.35f))
            return
        }
        val p = between(ms, DROP_MS, BLACK_MS)
        if (p >= 1f) {
            drawRect(Night)
            return
        }
        val far = maxOf(
            hypot(at.x, at.y), hypot(size.width - at.x, at.y),
            hypot(at.x, size.height - at.y), hypot(size.width - at.x, size.height - at.y),
        )
        // it lands as a small blot, gathers, and then runs
        val blot = 13.dp.toPx()
        val r = if (p < 0.16f) blot * sin(p / 0.16f * PI.toFloat() / 2f) else blot + (far * 1.32f - blot) * ((p - 0.16f) / 0.84f).pow(2.4f)
        val water = Path().through(edge(at, r * 1.035f + 3.dp.toPx(), rough = 0.2f, salt = 43, cells = 9), begin = true)
        water.close()
        drawPath(water, Thin, alpha = 0.34f)
        val ink = Path().through(edge(at, r, rough = 0.2f, salt = 41, cells = 9), begin = true)
        ink.close()
        drawPath(ink, Night)
    }
}
