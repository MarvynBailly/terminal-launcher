package com.terminallauncher.ui.looks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
import com.terminallauncher.ui.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Aurora: a terminal that feels like a current flagship phone. Crisp mono type over a slow, living
 * northern sky, a few panes of glass floating on it, and everything that moves, moves on a spring.
 */
object Aurora : Look() {
    override val id = "aurora"

    private val Night = Color(0xFF05060C)
    private val Sky = Palette(
        bg = Night, fg = Color(0xFFF2F4FA), dim = Color(0xFFA7AFC6), line = Color(0x1FFFFFFF),
        accent = Color(0xFF74ECD6), app = Color(0xFFBCB5FF), ok = Color(0xFF8CF0B4), err = Color(0xFFFF9478),
    )

    override fun palette(theme: String): Palette = Sky

    /* ---------------- springs: barely over, a little over, a clear bounce ---------------- */

    private fun soft(p: Float) = overshoot(p, 0.7f)
    private fun spring(p: Float) = overshoot(p, 1.6f)
    private fun bounce(p: Float) = overshoot(p, 2.3f)

    /* ---------------- type: Geist Mono ---------------- */

    private val Geist = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_light, FontWeight.Light),
    )
    override val font get() = Geist
    override val fontSize get() = 14.sp
    override val lineHeight get() = 22.sp
    override val advance get() = 0.6f
    override val gutter get() = 20.dp

    private val Flat = Shadow(Color.Transparent)

    // No soft shadow under the text: a blurred shadow on every line crashed the Android emulator's
    // graphics outright, and the veil over the sky already keeps the words readable.

    override fun span(style: Style, palette: Palette): SpanStyle =
        if (style == Style.MARK) SpanStyle(color = palette.bg, background = palette.accent, shadow = Flat)
        else SpanStyle(color = palette.of(style))

    /* ---------------- the sky ---------------- */

    /**
     * One light of the aurora: a big soft ellipse. Where it sits (in screen widths and heights) and
     * how big it is (its radius in screen widths), how flat, its tilt, and the slow rates it wanders
     * at. They all sit in the upper three fifths: the keyboard has the rest.
     */
    private class Light(
        color: Color, strength: Float,
        val x: Float, val y: Float, val r: Float, val flat: Float, val tilt: Float,
        val vx: Float, val vy: Float, val vr: Float, val phase: Float,
    ) {
        /**
         * Its colour from the middle out. The brush is made at the size it is drawn: a gradient made for
         * a tiny circle and stretched to the screen breaks some graphics drivers (the emulator's crashed).
         */
        val stops = arrayOf(
            0f to color.copy(alpha = strength),
            0.22f to color.copy(alpha = strength * 0.8f),
            0.48f to color.copy(alpha = strength * 0.4f),
            0.74f to color.copy(alpha = strength * 0.11f),
            1f to color.copy(alpha = 0f),
        )
    }

    private val Lights = listOf(
        Light(Color(0xFF10D1C4), 0.52f, x = 0.30f, y = 0.19f, r = 0.82f, flat = 0.62f, tilt = -0.55f, vx = 0.21f, vy = 0.17f, vr = 0.13f, phase = 0.3f),   // teal
        Light(Color(0xFF7C3AEE), 0.58f, x = 1.00f, y = 0.35f, r = 0.95f, flat = 0.66f, tilt = -0.75f, vx = 0.16f, vy = 0.23f, vr = 0.11f, phase = 2.1f),   // violet
        Light(Color(0xFF17D37C), 0.44f, x = 0.02f, y = 0.44f, r = 0.88f, flat = 0.50f, tilt = -0.95f, vx = 0.19f, vy = 0.14f, vr = 0.17f, phase = 4.4f),   // green
        Light(Color(0xFFEB478E), 0.40f, x = 0.74f, y = 0.63f, r = 0.70f, flat = 0.78f, tilt = 0.30f, vx = 0.25f, vy = 0.19f, vr = 0.15f, phase = 5.6f),    // rose
        Light(Color(0xFF1254ED), 0.40f, x = 0.80f, y = 0.05f, r = 0.92f, flat = 0.70f, tilt = 0.90f, vx = 0.13f, vy = 0.18f, vr = 0.09f, phase = 1.2f),    // deep blue
        Light(Color(0xFF51F6CF), 0.20f, x = 0.48f, y = 0.42f, r = 1.05f, flat = 0.21f, tilt = -0.62f, vx = 0.11f, vy = 0.15f, vr = 0.07f, phase = 3.3f),   // a thin bright ribbon
    )

    /** Night held over the sky where the words are: deepest at the top left, where a line begins. */
    private val Veil = Brush.linearGradient(
        0f to Night.copy(alpha = 0.62f), 0.34f to Night.copy(alpha = 0.44f), 1f to Night.copy(alpha = 0.3f),
    )

    /** A little grain, so the soft colour reads as light and not as a flat fill. */
    private val Grain by lazy { grain(Color.White, lo = 0f, hi = 0.05f, salt = 11, size = 128) }

    /**
     * The sky at [ms]: six lights added together, wandering. [level] is how bright it is, and
     * [risen] how far the lights have come up from below, for dawn and for nightfall.
     */
    private fun DrawScope.sky(ms: Float, level: Float = 1f, risen: Float = 1f) {
        if (level <= 0.004f) return
        val t = 40f + ms / 1000f
        val w = size.width
        val h = size.height
        // Laid one over another, not added: added light redrawn every frame crashed the Android emulator.
        for (light in Lights) {
            val x = (light.x + 0.085f * sin(t * light.vx + light.phase)) * w
            val y = (light.y + 0.03f * cos(t * light.vy + light.phase * 1.7f) + (1f - risen) * 0.2f) * h
            val r = light.r * (1f + 0.07f * sin(t * light.vr + light.phase * 2.3f)) * w
            val turn = light.tilt + 0.3f * sin(t * light.vr * 0.8f + light.phase)
            withTransform({
                translate(x, y)
                rotate(turn * 180f / PI.toFloat(), pivot = Offset.Zero)
                scale(1f, light.flat, pivot = Offset.Zero)
            }) {
                drawCircle(Brush.radialGradient(*light.stops, center = Offset.Zero, radius = r), r, Offset.Zero, alpha = level.coerceAtMost(1f))
            }
        }
        drawRect(Veil)
        drawRect(Grain, alpha = level.coerceAtMost(1f))
    }

    /** The whole ground, night included: what a movement lays over the page. */
    private fun DrawScope.ground(env: LookEnv, level: Float, risen: Float) {
        drawRect(Night)
        sky(env.time(), level, risen)
    }

    override val moves get() = true

    override fun DrawScope.background(env: LookEnv) = sky(env.time())

    /* ---------------- glass ---------------- */

    private val Glass = Color(0x7022283E)
    private val Edge = Color(0x26FFFFFF)

    private fun DrawScope.hair(): Float = maxOf(1f, 0.8.dp.toPx())

    /**
     * A pane of glass: a see-through fill with a hairline of light around it. There is no blur to be
     * had, and over this sky none is missed.
     */
    private fun DrawScope.glass(round: Float, edge: Color = Edge, topLeft: Offset = Offset.Zero, size: Size = this.size) {
        val hair = hair()
        drawRoundRect(Glass, topLeft, size, CornerRadius(round))
        drawRoundRect(
            edge, topLeft + Offset(hair / 2f, hair / 2f), Size(size.width - hair, size.height - hair),
            CornerRadius((round - hair / 2f).coerceAtLeast(0f)), style = Stroke(hair),
        )
    }

    /** A soft round light, [brush] being one made for a circle of radius 1 about the origin. */
    private fun DrawScope.halo(stops: Array<Pair<Float, Color>>, at: Offset, radius: Float, alpha: Float = 1f, blendMode: BlendMode = BlendMode.SrcOver) {
        drawCircle(Brush.radialGradient(*stops, center = at, radius = radius), radius, at, alpha = alpha, blendMode = blendMode)
    }

    private fun lamp(color: Color): Array<Pair<Float, Color>> =
        arrayOf(0f to color.copy(alpha = 0.55f), 0.35f to color.copy(alpha = 0.3f), 1f to color.copy(alpha = 0f))

    private val HomeLamp = lamp(Sky.accent)
    private val LockLamp = lamp(Sky.app)

    /* ---------------- parts ---------------- */

    /** One floating pill of glass, a little wider than the text, with a small lamp: violet while locked. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 10.dp, bottom = 12.dp)
        .layout { measurable, constraints ->
            if (!constraints.hasBoundedWidth) {
                val placed = measurable.measure(constraints)
                layout(placed.width, placed.height) { placed.place(0, 0) }
            } else {
                val out = 9.dp.roundToPx()
                val wide = constraints.maxWidth + 2 * out
                val placed = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                layout(constraints.maxWidth, placed.height) { placed.place(-out, 0) }
            }
        }
        .drawBehind {
            glass(size.height / 2f)
            val at = Offset(16.dp.toPx(), size.height / 2f)
            halo(if (env.locked) LockLamp else HomeLamp, at, 9.dp.toPx())
            drawCircle(if (env.locked) env.palette.app else env.palette.accent, 3.dp.toPx(), at)
        }
        .padding(start = 28.dp, end = 15.dp, top = 8.dp, bottom = 8.dp)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFC2C8DB), shadow = Flat)

    private val ClockGlow = Color(0xFF7EF0DC)
    private val Haze = arrayOf(0f to ClockGlow.copy(alpha = 0.2f), 0.5f to ClockGlow.copy(alpha = 0.08f), 1f to ClockGlow.copy(alpha = 0f))

    /** How far aside, in widths of the page, the clock stands while a right password takes the page away. See [granted]. */
    private const val ASIDE = 3f

    /**
     * Large light numerals with their own light around them, the colon drawn in close and breathing.
     * A right password brings them toward the viewer: they glide to the middle, swell, and are gone.
     */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val density = LocalDensity.current
        // seven times the size of the text, or as much as fits
        val em = with(density) { minOf((fontSize * 7).toPx(), width.toPx() / 2.7f) }
        val style = remember(em, env.base) {
            env.base.copy(
                fontWeight = FontWeight.Light, fontSize = with(density) { em.toSp() }, lineHeight = 1.em, color = Color.White,
                shadow = Shadow(ClockGlow.copy(alpha = 0.5f), Offset.Zero, em * 0.07f),
            )
        }
        val glyphs = remember(time, style) { time.map { env.measurer.measure(it.toString(), style) } }
        // set a little tight, and the colon tighter still
        val tight = em * 0.045f
        val pinch = em * 0.15f
        val starts = FloatArray(glyphs.size)
        var wide = 0f
        glyphs.forEachIndexed { i, glyph ->
            val colon = time[i] == ':'
            starts[i] = if (colon) wide - pinch else wide
            wide += glyph.size.width - tight - if (colon) 2 * pinch else 0f
        }
        wide += tight
        val tall = glyphs.maxOfOrNull { it.size.height }?.toFloat() ?: em
        // room around the numerals for their light, and so that nothing is cut while they grow
        val room = em * 0.5f
        val page = with(density) { width.toPx() }
        Box(Modifier.size(with(density) { DpSize(wide.toDp(), tall.toDp()) })) {
            Canvas(
                Modifier
                    .requiredSize(with(density) { DpSize((wide + 2 * room).toDp(), (tall + 2 * room).toDp()) })
                    .graphicsLayer {
                        val ms = leaving?.invoke() ?: return@graphicsLayer
                        val p = between(ms, 40f, 720f)
                        val e = p * p
                        val near = 1f + 0.14f * between(e, 0f, 0.34f) + 1.3f * between(e, 0.34f, 1f)
                        scaleX = near
                        scaleY = near
                        alpha = 1f - between(e, 0.34f, 1f)
                        // It stands aside from the words, where the page's own leaving does not reach it.
                        translationX = (page - wide) / 2f * smooth(between(ms, 40f, 420f)) - page * ASIDE
                        translationY = 12.dp.toPx() * e
                        // one picture, grown: it softens as it nears, which is all the blur there is
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                val breath = 0.7f + 0.3f * cos(env.time() / 3200f * 2f * PI.toFloat())
                val rx = wide * 0.62f
                withTransform({
                    translate(room + wide / 2f, room + tall * 0.44f)
                    scale(1f, tall * 0.7f / rx, pivot = Offset.Zero)
                }) { drawCircle(Brush.radialGradient(*Haze, center = Offset.Zero, radius = rx), rx, Offset.Zero) }
                glyphs.forEachIndexed { i, glyph ->
                    drawText(glyph, topLeft = Offset(room + starts[i], room), alpha = if (time[i] == ':') breath else 1f)
                }
            }
        }
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(fontSize = 12.sp, letterSpacing = 0.05.em, color = env.palette.dim)
        LockLine.DATE -> env.base.copy(fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.01).em)
        LockLine.INFO -> env.base.copy(color = env.palette.dim)
    }

    private val Fill = Brush.horizontalGradient(0f to Color(0xFF5FE9D0), 0.48f to Color(0xFF73C8FF), 0.78f to Color(0xFFB3A6FF), 1f to Color(0xFFF2A3D2))
    private val Alert = Brush.horizontalGradient(0f to Color(0xFFFFBE85), 0.55f to Color(0xFFFF8F73), 1f to Color(0xFFFF6F86))

    override val gaugeSize get() = DpSize(110.dp, 8.dp)

    /** One smooth bar. It runs a little past its mark and comes back. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val round = CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = round)
        val filled = if (target <= 0f) 0f else (target * bounce(shown / target)).coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(if (alert) Alert else Fill, size = Size(maxOf(size.width * filled, size.height), size.height), cornerRadius = round)
        }
    }

    /** A small chevron at the right says the line goes somewhere. */
    override fun Modifier.tapRow(env: LookEnv): Modifier = drawBehind {
        val arm = 3.2.dp.toPx()
        val tip = Offset(size.width - 1.5.dp.toPx(), size.height / 2f)
        val color = Color.White.copy(alpha = 0.3f)
        drawLine(color, tip - Offset(arm, arm), tip, 1.2.dp.toPx(), StrokeCap.Round)
        drawLine(color, tip - Offset(arm, -arm), tip, 1.2.dp.toPx(), StrokeCap.Round)
    }

    private val Typed = Brush.linearGradient(0f to Color(0x2B74ECD6), 1f to Color(0x21BCB5FF))

    /** What was typed keeps a tint of glass around it, only as wide as the words. */
    override fun Modifier.echoRow(env: LookEnv): Modifier = this
        .padding(top = 8.dp, bottom = 5.dp)
        .layout { measurable, constraints ->
            val placed = measurable.measure(constraints.copy(minWidth = 0))
            layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placed.width, placed.height) { placed.place(0, 0) }
        }
        .drawBehind {
            val left = 9.dp.toPx()
            val right = 11.dp.toPx()
            val over = 2.dp.toPx()
            val at = Offset(-left, -over)
            val box = Size(size.width + left + right, size.height + 2 * over)
            val round = CornerRadius(10.dp.toPx())
            val hair = hair()
            drawRoundRect(Typed, at, box, round)
            drawRoundRect(Color.White.copy(alpha = 0.1f), at + Offset(hair / 2f, hair / 2f), Size(box.width - hair, box.height - hair), round, style = Stroke(hair))
        }

    /** The block the row draws under a finger, rounded off. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = if (!lit) this else drawBehind {
        val out = 5.dp.toPx()
        val over = 3.dp.toPx()
        drawRoundRect(
            env.palette.app, Offset(-out, over), Size(size.width - 2 * env.cell.width + 2 * out, size.height - 2 * over), CornerRadius(7.dp.toPx()),
        )
    }

    // The sender's tile, made for a square one unit on a side.
    private fun tile(at: Offset, side: Float) = Brush.linearGradient(0f to Color(0xFF57E3CD), 0.7f to Color(0xFF7F8CFF), 1f to Color(0xFFB48CFF), start = at, end = at + Offset(side, side))
    private fun shine(at: Offset, side: Float) = Brush.radialGradient(0f to Color.White.copy(alpha = 0.45f), 0.55f to Color.White.copy(alpha = 0f), center = at + Offset(0.2f * side, 0.1f * side), radius = 1.2f * side)

    /** A frosted card with the sender's tile on its left. Its edge is brightest while it is news. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(top = 10.dp, bottom = 9.dp)
        .drawBehind {
            val old = faded()
            glass(16.dp.toPx(), edge = Color.White.copy(alpha = 0.22f - 0.1f * old))
            // the tile sits beside the first two lines: who it is from, and what they say
            val side = 32.dp.toPx()
            val at = Offset(11.dp.toPx(), 10.dp.toPx() + (2 * env.cell.height - side) / 2f)
            val round = 10.dp.toPx()
            val hair = hair()
            drawRoundRect(tile(at, side), at, Size(side, side), CornerRadius(round), alpha = 1f - 0.3f * old)
            drawRoundRect(shine(at, side), at, Size(side, side), CornerRadius(round), alpha = 1f - 0.3f * old)
            drawRoundRect(Color.White.copy(alpha = 0.3f), at + Offset(hair / 2f, hair / 2f), Size(side - hair, side - hair), CornerRadius(round), style = Stroke(hair))
        }
        .padding(start = 54.dp, end = 14.dp, top = 10.dp, bottom = 11.dp)

    /** Frosted pills. The arrows are narrower. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind { glass(size.height / 2f) }
        .padding(horizontal = if (color == env.palette.dim) 11.dp else 14.dp, vertical = 5.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle =
        env.base.copy(fontSize = 13.sp, lineHeight = 18.sp, color = if (color == env.palette.dim) env.palette.fg else color, shadow = Flat)

    /** It breathes instead of blinking: it never goes out. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.dim.copy(alpha = 0.4f))
        blinkOn -> SpanStyle(background = palette.fg, color = palette.bg, shadow = Flat)
        else -> SpanStyle(background = palette.fg.copy(alpha = 0.24f))
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 420

    /** A new line rises a little into its place, on a soft spring, and clears as it comes. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = graphicsLayer {
        val p = progress()
        if (p < 1f) {
            alpha = easeOut(between(p, 0f, 0.7f))
            translationY = (1f - soft(p)) * env.cell.height * 0.4f
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }

    /** Draws into a layer of its own, which is then laid down at [alpha]. */
    private inline fun DrawScope.layer(alpha: Float = 1f, draw: DrawScope.() -> Unit) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
            draw()
            canvas.restore()
        }
    }

    /**
     * Where the page is cut across for [bent]: its top edge, a little under that, [rows] even steps
     * down to the keyboard, and its bottom edge.
     */
    private fun DrawScope.cuts(env: LookEnv, rows: Int): FloatArray {
        val edge = 10.dp.toPx()
        val seen = size.height - maxOf(env.keyboard, 16.dp.toPx())
        return FloatArray(rows + 3) { i ->
            when (i) {
                0 -> 0f
                rows + 2 -> size.height
                else -> edge + (seen - edge) * (i - 1) / rows
            }
        }
    }

    /**
     * Draws the page with its rows moved down, the one at each of [cuts] by as much as [moved] says,
     * and what lies between two cuts stretched to fit: nothing tears, and nothing shows behind.
     */
    private fun ContentDrawScope.bent(cuts: FloatArray, moved: FloatArray) {
        for (i in 0 until cuts.size - 1) {
            val top = (cuts[i] + moved[i]).roundToInt().toFloat()
            val bottom = (cuts[i + 1] + moved[i + 1]).roundToInt().toFloat()
            val tall = cuts[i + 1] - cuts[i]
            if (bottom <= top || tall <= 0f) continue
            val stretch = (bottom - top) / tall
            clipRect(top = top, bottom = bottom) {
                withTransform({
                    translate(top = top - stretch * cuts[i])
                    scale(1f, stretch, pivot = Offset.Zero)
                }) { this@bent.drawContent() }
            }
        }
    }

    /** Lays the sky alone over the page, as thick at each of [cuts] as [hidden] says: what is left of the page shows through. */
    private fun DrawScope.veiled(env: LookEnv, cuts: FloatArray, moved: FloatArray, hidden: FloatArray, level: Float, risen: Float) {
        if (hidden.all { it <= 0f }) return
        layer {
            ground(env, level, risen)
            if (hidden.any { it < 1f }) {
                var at = 0f
                val stops = Array(cuts.size) { i ->
                    at = maxOf(at, ((cuts[i] + moved[i]) / size.height).coerceIn(0f, 1f))
                    at to Color.Black.copy(alpha = hidden[i].coerceIn(0f, 1f))
                }
                drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
            }
        }
    }

    /**
     * Dawn: the sky brightens out of night, its lights coming up from below, and then the page rises
     * into place, its top first.
     */
    override val powerOn: Motion = object : Motion(ms = 980, readableMs = 240, backdrop = Night) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) {
                drawContent()
                return
            }
            val level = smooth(between(t, 0f, 720f))
            val risen = easeOut(between(t, 0f, 900f))
            if (t <= readableMs) {
                ground(env, level, risen)
                return
            }
            val rows = 10
            val cuts = cuts(env, rows)
            val moved = FloatArray(cuts.size)
            val hidden = FloatArray(cuts.size)
            val from = 22.dp.toPx()
            for (i in 1..rows + 1) {
                val late = readableMs + 180f * (i - 1) / rows
                moved[i] = from * (1f - spring(between(t, late, late + 560f)))
                hidden[i] = 1f - easeOut(between(t, late, late + 360f))
            }
            hidden[0] = hidden[1]
            moved[rows + 2] = maxOf(0f, moved[rows + 1])
            hidden[rows + 2] = hidden[rows + 1]
            bent(cuts, moved)
            veiled(env, cuts, moved, hidden, level, risen)
        }
    }

    /** Nightfall: the page sinks and thins away, the lights go down and out. */
    override val powerOff: Motion = object : Motion(ms = 720, backdrop = Night) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val p = (t / ms).coerceIn(0f, 1f)
            if (p >= 1f) {
                drawRect(Night)
                return
            }
            if (p <= 0f) {
                drawContent()
                return
            }
            val rows = 6
            val cuts = cuts(env, rows)
            val moved = FloatArray(cuts.size)
            val hidden = FloatArray(cuts.size)
            val sunk = 34.dp.toPx() * p * p
            val gone = smooth(between(t, 40f, ms - 60f))
            for (i in 1..rows + 1) {
                // what is lower falls a little further
                moved[i] = sunk * (0.7f + 0.3f * (i - 1) / rows)
                hidden[i] = gone
            }
            hidden[0] = gone
            moved[rows + 2] = moved[rows + 1]
            hidden[rows + 2] = gone
            bent(cuts, moved)
            veiled(env, cuts, moved, hidden, level = 1f - smooth(between(t, 0f, ms - 40f)), risen = 1f - easeIn(p))
        }
    }

    private val Swell = arrayOf(0f to ClockGlow.copy(alpha = 0.34f), 0.5f to Color(0xFF8C7BFF).copy(alpha = 0.14f), 1f to Color.Transparent)

    /**
     * The words lift away, the sky swells behind them, and the clock comes toward the viewer alone.
     * The page is drawn twice for that. While it leaves, the clock stands [ASIDE] to the left of the
     * page (see [Clock]): drawn as it is, the page is the words without the clock, and drawn that
     * far to the right it is the clock without the words. So each gets a leaving of its own.
     */
    override val granted: Motion = object : Motion(ms = 760) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) return
            val swell = easeOut(between(t, 0f, 320f)) * (1f - smooth(between(t, 320f, 740f)))
            if (swell > 0f) halo(Swell, Offset(size.width / 2f, size.width * 0.56f), size.width / 2f, swell)

            val lifted = between(t, 0f, 340f).let { it * it }
            if (lifted < 1f) layer(1f - lifted) {
                translate(top = -10.dp.toPx() * lifted) { this@draw.drawContent() }
            }

            // Past the edges of the page there is no room to grow into, so the numerals thin out toward them.
            val thin = 30.dp.toPx() * between(t, 300f, 560f)
            layer {
                translate(left = size.width * ASIDE) { this@draw.drawContent() }
                if (thin > 0f) {
                    val x = thin / size.width
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, x to Color.Black, 1f - x to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, thin / size.height to Color.Black), blendMode = BlendMode.DstIn)
                }
            }
        }
    }

    override val grantedWords get() = false

    private val AppGround = Color(0xFF101216)

    override val launchMs get() = 600

    /**
     * The line lifts into a card of glass while everything behind it falls back into the dark, and
     * the card springs open into the ground the app comes up on.
     */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val lifted = between(ms, 30f, 250f)
        if (lifted <= 0f) return
        drawRect(Night, alpha = 0.62f * easeOut(between(ms, 30f, 380f)))

        // the card around its line, a little larger than life once it has lifted
        val side = gutter.toPx() - 10.dp.toPx()
        val over = 7.dp.toPx()
        val grown = 0.97f + 0.075f * bounce(lifted)
        val halfWide = (size.width / 2f - side) * grown
        val halfTall = (line.height / 2f + over) * grown
        // and opening until it is the whole screen
        val open = soft(between(ms, 190f, 540f))
        val left = (size.width / 2f - halfWide) * (1f - open)
        val top = (line.center.y - halfTall) * (1f - open)
        val right = size.width - (size.width / 2f - halfWide) * (1f - open)
        val bottom = size.height - (size.height - line.center.y - halfTall) * (1f - open)
        val at = Offset(left, top)
        val box = Size(right - left, bottom - top)
        val round = CornerRadius(14.dp.toPx() * (1f - open).coerceIn(0f, 1f))
        val there = easeOut(lifted)
        drawRoundRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.2f), 0.6f to Color.White.copy(alpha = 0.07f), 1f to Sky.app.copy(alpha = 0.12f),
                start = at, end = at + Offset(box.width, box.height),
            ),
            at, box, round, alpha = there,
        )
        val hair = hair()
        drawRoundRect(
            Color.White.copy(alpha = 0.28f * there * (1f - open).coerceIn(0f, 1f)),
            at + Offset(hair / 2f, hair / 2f), Size(box.width - hair, box.height - hair), round, style = Stroke(hair),
        )
        // the app's own ground fills the glass as it opens
        val filled = smooth(between(ms, 230f, 460f))
        if (filled > 0f) drawRoundRect(AppGround, at, box, round, alpha = filled)
    }
}
