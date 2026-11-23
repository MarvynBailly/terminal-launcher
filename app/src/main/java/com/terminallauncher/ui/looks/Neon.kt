package com.terminallauncher.ui.looks

import android.content.res.Resources
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Neon Grid: an outrun night. A banded sun on the horizon and a grid floor running toward you, and
 * the terminal as a neon sign in front of it: magenta and cyan tubes on indigo-black.
 *
 * The scene is one function of a handful of numbers (how far the sun is up, how much of the grid is
 * drawn, how dark the night behind the words is). At rest they are all 1. Waking and locking move
 * them, which is how the sun rises and the grid draws itself out.
 */
object Neon : Look() {
    override val id = "neon"

    private val Dusk = Palette(
        bg = Color(0xFF05030F), fg = Color(0xFFECE8FF), dim = Color(0xFF8D84C6), line = Color(0xFF2E2466),
        accent = Color(0xFFFF4FD8), app = Color(0xFF3DF2FF), ok = Color(0xFF6DFFC1), err = Color(0xFFFF3D6E),
    )

    override fun palette(theme: String): Palette = Dusk

    /* the two tubes, the alarm and the glass around lit words */
    private val Magenta = Color(0xFFFF4FD8)
    private val Cyan = Color(0xFF3DF2FF)
    private val Red = Color(0xFFFF3D6E)
    private val Mint = Color(0xFF6DFFC1)
    private val Lilac = Color(0xFFC4B4FF)
    private val Edge = Color(0xFFFFE6F9)
    private val Night = Color(0xFF020106)
    private val Ink = Color(0xFF05030F)

    private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255f + 0.5f).toInt())

    /* ---------------- type: Share Tech Mono, narrow enough for a size up ---------------- */

    private val Share = FontFamily(Font(R.font.share_tech_mono_regular))
    @OptIn(ExperimentalTextApi::class)
    private val Orbitron = FontFamily(
        Font(R.font.orbitron_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.orbitron_variable, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

    override val font get() = Share
    override val fontSize get() = 16.sp
    override val lineHeight get() = 21.sp
    override val advance get() = 0.54f
    override val gutter get() = 17.dp

    /** A style is shaped where no density is handed over, so a glow takes its size from the screen's own. */
    private val perDp: Float get() = Resources.getSystem().displayMetrics.density

    /** Light around a lit word, [spread] dp wide. */
    private fun glow(color: Color, strength: Float, spread: Float) = Shadow(color.copy(alpha = strength), Offset.Zero, spread * perDp)
    private val Unlit = Shadow(Color.Transparent)

    /** Every line its full height, a line alone too: a sign wants air between its rows. */
    private val Aired = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    /** Lit words glow, dim words are unlit glass. */
    override fun type(base: TextStyle, palette: Palette): TextStyle = base.copy(shadow = glow(Lilac, 0.5f, 8f), lineHeightStyle = Aired)

    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        Style.DIM -> SpanStyle(color = palette.dim, shadow = Unlit)
        Style.ACCENT -> SpanStyle(color = palette.accent, shadow = glow(Magenta, 0.8f, 7.5f))
        Style.APP -> SpanStyle(color = palette.app, shadow = glow(Cyan, 0.7f, 7.5f))
        Style.OK -> SpanStyle(color = palette.ok, shadow = glow(Mint, 0.65f, 7.5f))
        Style.ERR -> SpanStyle(color = palette.err, shadow = glow(Red, 0.8f, 7.5f))
        Style.MARK -> SpanStyle(color = palette.bg, background = palette.accent, shadow = Unlit)
        else -> SpanStyle(color = palette.fg)
    }

    /* ---------------- the scene: sky, sun, floor ---------------- */

    /** How fast the floor runs at rest, in grid cells a second. */
    private const val CALM = 0.42f

    /** How strong the night over the scene is where the words are. */
    private const val SCRIM = 0.74f

    /** The numbers the scene is drawn from. All of them 1 (and [run] 0) is the picture at rest. */
    private class Stage(
        val sky: Float = 1f,
        /** How far the sun is up. */
        val sun: Float = 1f,
        /** How far the horizon line has opened from the middle. */
        val hz: Float = 1f,
        /** How far out from the horizon the grid is drawn. */
        val reach: Float = 1f,
        /** How much of the grid is lit: as it goes out, the outer and the nearer lines go first. */
        val lines: Float = 1f,
        /** The night behind the words. */
        val dim: Float = 1f,
        /** Cells the floor has run beyond its calm pace. */
        val run: Float = 0f,
        /** The words may be anywhere on the page, whatever page the shell says it is. */
        val anywhere: Boolean = false,
    )

    private val Rest = Stage()

    /**
     * Set only while a waking or a locking screen draws the page, and cleared before that call
     * returns: the background is drawn inside that call, and this is how it is told where the sun is.
     */
    private var staged: Stage? = null

    /** Where the floor ends: the top of the keyboard, or the bottom of the screen. */
    private fun DrawScope.ground(env: LookEnv): Float = size.height - env.keyboard

    /** The horizon. Under a keyboard it comes up, so the sun and a strip of floor still show above the keys. */
    private fun DrawScope.horizon(env: LookEnv): Float = ground(env) * 0.69f + env.keyboard * 0.165f

    /** Everything about the scene that only changes with the screen's size: its gradients and its stars. */
    private class Kept(val w: Float, val h: Float, val ground: Float, val hy: Float, val r: Float, lockEnd: Float, chips: Float) {
        val u = w / 100f
        val cx = w / 2f

        /** The sun's centre at rest. */
        val sunY = hy - 0.36f * r

        val sky = Brush.verticalGradient(
            0f to Color(0xFF04020D), 0.42f to Color(0xFF0A0522), 0.78f to Color(0xFF1A0A3D), 1f to Color(0xFF3B1060),
            startY = 0f, endY = hy,
        )
        val halo = Brush.radialGradient(
            0f to rgba(255, 80, 170, 0.5f), 0.185f to rgba(255, 80, 170, 0.5f), 0.495f to rgba(214, 44, 214, 0.17f), 1f to rgba(110, 20, 200, 0f),
            center = Offset(cx, sunY), radius = r * 2.7f,
        )
        val disc = Brush.verticalGradient(
            0f to Color(0xFFFFF27A), 0.36f to Color(0xFFFFB24A), 0.7f to Color(0xFFFF5A8F), 1f to Color(0xFFFF2BD6),
            startY = sunY - r, endY = sunY + r * 0.62f,
        )
        val haze = Brush.verticalGradient(0f to rgba(255, 60, 200, 0f), 1f to rgba(255, 60, 200, 0.3f), startY = hy - 16f * u, endY = hy)
        val floor = Brush.verticalGradient(
            0f to Color(0xFF230841), 0.22f to Color(0xFF11052B), 1f to Color(0xFF05020E),
            startY = hy, endY = ground,
        )
        val pool = Brush.radialGradient(
            0f to rgba(255, 96, 170, 0.5f), 0.5f to rgba(230, 50, 200, 0.14f), 1f to rgba(180, 40, 220, 0f),
            center = Offset(cx, hy), radius = 46f * u,
        )

        /** The grid's lines: magenta at the horizon, cyan under your feet. */
        val ink = Brush.verticalGradient(
            0f to rgba(255, 70, 214, 0f), 0.07f to rgba(255, 70, 214, 0.62f), 0.5f to rgba(168, 96, 255, 0.8f), 1f to rgba(70, 228, 255, 0.95f),
            startY = hy, endY = ground,
        )
        val band = Brush.horizontalGradient(
            0f to rgba(255, 90, 220, 0f), 0.18f to rgba(255, 120, 230, 0.9f), 0.5f to rgba(255, 236, 250, 1f),
            0.82f to rgba(255, 120, 230, 0.9f), 1f to rgba(255, 90, 220, 0f),
            startX = 0f, endX = w,
        )

        /** Night over the whole page, lifting a little behind the chips, where a line of text never is. */
        val pageScrim = Brush.verticalGradient(
            0f to Ink.copy(alpha = SCRIM), ((ground - chips) / ground).coerceIn(0.5f, 0.99f) to Ink.copy(alpha = SCRIM), 1f to Ink.copy(alpha = SCRIM * 0.4f),
            startY = 0f, endY = ground,
        )

        /** The lock page is never longer than its boot log, its clock and a prompt: below that the scene is left alone. */
        val lockScrim = (lockEnd + 14f * u).let { end ->
            if (end >= ground - chips) pageScrim
            else Brush.verticalGradient(
                0f to Ink.copy(alpha = SCRIM), lockEnd / end to Ink.copy(alpha = SCRIM),
                (lockEnd + 4.9f * u) / end to Ink.copy(alpha = SCRIM * 0.62f), (lockEnd + 9.8f * u) / end to Ink.copy(alpha = SCRIM * 0.2f), 1f to Ink.copy(alpha = 0f),
                startY = 0f, endY = end,
            )
        }

        /** Four handfuls of stars, each twinkling at its own pace. */
        val stars = Array(4) { g ->
            List(11) { i ->
                val n = g * 11 + i
                Offset(floor(dice(n, 41) * w), floor(hy * (0.075f + 0.84f * dice(n, 42).pow(1.25f))))
            }
        }
    }

    private var kept: Kept? = null
    private val sunBands = Path()
    private val spokes = Path()

    private fun DrawScope.kept(env: LookEnv): Kept {
        val w = size.width
        val ground = ground(env)
        val hy = horizon(env)
        kept?.let { if (it.w == w && it.h == size.height && it.ground == ground && it.hy == hy) return it }
        // the sun is smaller over a keyboard, where there is less sky for it
        val r = 0.265f * w * (1f - 0.6f * env.keyboard / size.height)
        // a status bar, the status line, the boot log and the lock header down to a line under its prompt
        val lockEnd = 28.dp.toPx() + 44.dp.toPx() + 13.3f * env.cell.height + 38.dp.toPx() + 0.86f * min(w * 0.175f, 78.dp.toPx())
        return Kept(w, size.height, ground, hy, r, lockEnd, 48.dp.toPx()).also { kept = it }
    }

    override val moves get() = true

    override fun DrawScope.background(env: LookEnv) = scene(env, staged ?: Rest)

    private fun DrawScope.scene(env: LookEnv, s: Stage) {
        val k = kept(env)
        val w = k.w
        val u = k.u
        val cx = k.cx
        val hy = k.hy
        val r = k.r
        val fh = k.ground - hy
        val t = env.time() / 1000f

        if (s.sky < 1f) drawRect(Night)
        if (s.sky > 0.003f) {
            drawRect(k.sky, size = Size(w, hy + u), alpha = s.sky)
            for (g in 0 until 4) {
                val twinkle = 0.55f + 0.45f * sin(t * (0.5f + 0.55f * g) + g * 1.7f)
                drawPoints(k.stars[g], PointMode.Points, Color(0xFFE6DCFF), strokeWidth = max(1f, u * (0.26f + 0.12f * g)), cap = StrokeCap.Square, alpha = s.sky * twinkle * (0.5f + 0.1f * g))
            }
        }

        /* the sun: a disc cut into bands that widen toward the horizon, behind the floor */
        if (s.sky > 0.003f && s.sun > 0.003f) {
            val up = (s.sun * 1.6f).coerceIn(0f, 1f)
            clipRect(bottom = hy + u) {
                // drawn where it rests, and moved to where it is
                translate(top = (hy + r * (1.14f - 1.5f * s.sun)) - k.sunY) {
                    drawRect(k.halo, Offset(cx - r * 2.7f, k.sunY - r * 2.7f), Size(r * 5.4f, r * 5.4f), alpha = s.sky * up * 0.8f)
                    // the dark bands drift down the disc, far too slowly to watch
                    val drift = (t * 0.04f) % 1f
                    // Each band is the disc under a rectangular clip. A path clip rebuilt every frame
                    // has to be rasterised into a mask each time, and crashed the Android emulator.
                    var top = k.sunY - r - 1f
                    for (i in 0..9) {
                        val bottom = if (i == 9) k.sunY + r + 1f else {
                            val q = (i + drift) / 9f
                            k.sunY - r * 0.48f + q * r * 1.5f - r * 0.125f * q / 2f
                        }
                        if (bottom > top) clipRect(left = cx - r - 1f, top = top, right = cx + r + 1f, bottom = bottom) {
                            drawCircle(k.disc, r, Offset(cx, k.sunY), alpha = s.sky * 0.96f)
                        }
                        if (i < 9) {
                            val q = (i + drift) / 9f
                            top = k.sunY - r * 0.48f + q * r * 1.5f + r * 0.125f * q / 2f
                        }
                    }
                }
                // haze where the sky meets the floor
                drawRect(k.haze, Offset(0f, hy - 16f * u), Size(w, 16f * u), alpha = s.sky * up)
            }
        }

        /* the floor, and the sun on it */
        if (s.sky > 0.003f) {
            drawRect(k.floor, Offset(0f, hy), Size(w, size.height - hy), alpha = s.sky)
            if (s.sun > 0.003f) scale(1f, 0.62f, pivot = Offset(cx, hy)) {
                drawRect(k.pool, Offset(cx - 46f * u, hy), Size(92f * u, 46f * u), alpha = s.sky * (s.sun * 1.4f).coerceIn(0f, 1f) * 0.9f)
            }
        }

        /* the grid */
        val reach = min(1f, s.reach * 1.04f)
        if (reach > 0.003f && s.lines > 0.003f) clipRect(top = hy, bottom = k.ground) {
            val lit = 0.72f
            val thin = max(1f, 0.26f * u)
            val yEnd = hy + fh * reach
            // the lines that run away from you; when the grid goes out, the outer ones go first
            val spread = 25f * u * reach
            if (s.lines >= 0.999f) {
                spokes.reset()
                for (i in -SPOKES..SPOKES) {
                    spokes.moveTo(cx, hy)
                    spokes.lineTo(cx + i * spread, yEnd)
                }
                drawPath(spokes, k.ink, alpha = 0.15f * lit, style = Stroke(thin * 4.2f))
                drawPath(spokes, k.ink, alpha = lit, style = Stroke(thin))
            } else {
                for (i in -SPOKES..SPOKES) {
                    val a = ((s.lines - kotlin.math.abs(i) / SPOKES.toFloat() * 0.7f) / 0.12f).coerceIn(0f, 1f)
                    if (a > 0f) drawLine(k.ink, Offset(cx, hy), Offset(cx + i * spread, yEnd), strokeWidth = thin, alpha = lit * a)
                }
            }
            // the lines that come toward you
            val phase = (0.3f + t * CALM + s.run).let { it - floor(it) }
            val farthest = 1f + (RUNGS - 1) * RUNG
            for (i in 1 until RUNGS) {
                // when the grid goes out, the nearest line goes first
                val out = if (s.lines >= 0.999f) 1f else ((s.lines - (1f - (i + 1f) / RUNGS) * 0.88f) / 0.07f).coerceIn(0f, 1f)
                if (out <= 0f) continue
                val d = 1f + (i - phase) * RUNG
                val y = hy + fh / d
                if (y > yEnd) continue
                val a = min(1f, 1.3f / d) * ((farthest - d) / (2f * RUNG)).coerceIn(0f, 1f) * lit * out
                val thick = max(1f, u * (0.16f + 0.42f / d))
                drawRect(k.ink, Offset(0f, y - thick * 2.2f), Size(w, thick * 4.4f), alpha = a * 0.16f)
                drawRect(k.ink, Offset(0f, y - thick / 2f), Size(w, thick), alpha = a)
            }
            // while the grid is still drawing itself, its leading edge is hot
            if (reach < 0.999f && s.lines >= 0.999f) {
                val edge = (reach * 6f).coerceIn(0f, 1f) * ((1f - reach) * 5f).coerceIn(0f, 1f)
                val thick = max(1f, u * 0.5f)
                drawRect(Color(0xFFC9FBFF), Offset(0f, yEnd - thick * 3f), Size(w, thick * 6f), alpha = edge * 0.22f)
                drawRect(Color(0xFFC9FBFF), Offset(0f, yEnd - thick / 2f), Size(w, thick), alpha = edge * 0.95f)
            }
        }

        /* the horizon: it opens from the middle and closes back to it */
        if (s.hz > 0.003f) {
            val thick = max(1f, u * 0.36f)
            val on = (s.hz * 3f).coerceIn(0f, 1f)
            scale(s.hz, 1f, pivot = Offset(cx, hy)) {
                drawRect(k.band, Offset(0f, hy - thick * 4f), Size(w, thick * 8f), alpha = 0.2f * on)
                drawRect(k.band, Offset(0f, hy - thick / 2f), Size(w, thick), alpha = 0.85f * on)
            }
        }

        /* night over the scene where the words are */
        if (s.dim > 0.004f) drawRect(if (env.locked && !s.anywhere) k.lockScrim else k.pageScrim, alpha = s.dim)
    }

    private const val SPOKES = 10
    private const val RUNGS = 14
    private const val RUNG = 0.34f

    /* ---------------- parts ---------------- */

    private val Hairline = Brush.horizontalGradient(0f to Magenta.copy(alpha = 0.95f), 0.55f to Cyan.copy(alpha = 0.9f), 1f to Cyan.copy(alpha = 0f))
    private val HairGlow = Brush.horizontalGradient(0f to Magenta, 0.7f to Magenta.copy(alpha = 0.4f), 1f to Magenta.copy(alpha = 0f))

    /** Dim words over a hairline that runs magenta to cyan. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 8.dp, bottom = 8.dp)
        .drawBehind {
            val thick = max(1f, 1.1.dp.toPx())
            val y = size.height - thick / 2f
            drawRect(HairGlow, Offset(0f, y - 4.dp.toPx()), Size(size.width, 8.dp.toPx()), alpha = 0.1f)
            drawRect(HairGlow, Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), alpha = 0.16f)
            drawRect(Hairline, Offset(0f, size.height - thick), Size(size.width, thick))
        }
        .padding(bottom = 9.dp)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 13.5.sp, lineHeight = 19.sp, letterSpacing = 0.03.em, color = env.palette.dim, shadow = Unlit)

    /** The clock is the sign over the door: hollow tube numerals. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val density = LocalDensity.current
        val measurer = env.measurer
        val sign = remember(time, width, density) {
            fun set(size: Dp): TextLayoutResult {
                val sp = with(density) { size.toSp() }
                return measurer.measure(
                    time,
                    TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = sp, lineHeight = sp * 0.86f, lineHeightStyle = Aired, letterSpacing = 0.06.em),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            val room = with(density) { width.toPx() }
            var size = minOf(width * 0.19f, 78.dp)
            var laid = set(size)
            if (laid.size.width > room) {
                size *= room / laid.size.width * 0.98f
                laid = set(size)
            }
            laid to with(density) { size.toPx() }
        }
        val laid = sign.first
        val em = sign.second
        // A right password overdrives the tubes, one after another, before the page tears. Each bit is a numeral.
        val going by rememberUpdatedState(leaving)
        val struck by remember {
            derivedStateOf {
                val ms = going?.invoke() ?: return@derivedStateOf 0
                var mask = 0
                for (i in 0 until 8) {
                    val f = (ms - 20f - 28f * i) / 170f
                    if ((f >= 0f && f < 0.18f) || (f >= 0.34f && f < 0.6f) || f >= 0.78f) mask = mask or (1 shl i)
                }
                mask
            }
        }
        val pad = em * 0.55f
        val w = with(density) { laid.size.width.toDp() }
        val h = with(density) { laid.size.height.toDp() }
        val around = with(density) { pad.toDp() }
        Box(Modifier.size(w, h)) {
            // The glow is costly and the screen behind it redraws every frame: it is drawn once and kept,
            // in a layer with room for its light that takes no room on the page.
            Canvas(Modifier.requiredSize(w + around * 2, h + around * 2).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                translate(pad, pad) {
                    val hot = struck
                    if (hot == 0) tubes(laid, em, false)
                    else for (i in time.indices) {
                        val box = laid.getBoundingBox(i)
                        clipRect(
                            left = if (i == 0) -pad else box.left,
                            top = -pad,
                            right = if (i == time.lastIndex) laid.size.width + pad else box.right,
                            bottom = laid.size.height + pad,
                        ) { tubes(laid, em, hot and (1 shl i) != 0) }
                    }
                }
            }
        }
    }

    /** The numerals as glass tubes: a little gas inside, a pale wall, and the light they throw. */
    private fun DrawScope.tubes(laid: TextLayoutResult, em: Float, hot: Boolean) {
        val wall = Stroke(width = em * 0.03f, join = StrokeJoin.Round)
        drawText(laid, color = Magenta.copy(alpha = if (hot) 0.55f else 0.13f))
        drawText(laid, color = Edge, drawStyle = wall, shadow = Shadow(Magenta.copy(alpha = if (hot) 0.75f else 0.42f), Offset.Zero, em * 0.42f))
        drawText(laid, color = Edge, drawStyle = wall, shadow = Shadow(Magenta.copy(alpha = 0.8f), Offset.Zero, em * 0.13f))
        drawText(laid, color = if (hot) Color.White else Edge, drawStyle = wall, shadow = Shadow(Magenta, Offset.Zero, em * 0.035f))
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(color = env.palette.dim, letterSpacing = 0.08.em, shadow = Unlit)
        LockLine.DATE -> env.base.copy(
            fontFamily = Orbitron, fontWeight = FontWeight.Medium, fontSize = 18.5.sp, lineHeight = 27.sp, letterSpacing = 0.24.em,
            color = env.palette.app, shadow = glow(Cyan, 0.85f, 9f),
        )
        LockLine.INFO -> env.base.copy(color = env.palette.dim, shadow = Unlit)
    }

    /** A lit tube inside a glass one. As it fills, the gas stutters before it holds. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val tube = if (alert) Red else Cyan
        val thick = 7.dp.toPx()
        val y = size.height / 2f
        val round = CornerRadius(thick / 2f)
        drawRoundRect(tube.copy(alpha = 0.08f), Offset(0f, y - thick / 2f), Size(size.width, thick), round)
        drawRoundRect(tube.copy(alpha = 0.26f), Offset(0f, y - thick / 2f), Size(size.width, thick), round, style = Stroke(max(1f, 0.8.dp.toPx())))
        if (shown <= 0f) return
        val p = if (target > 0f) shown / target else 1f
        val lit = when {
            p >= 1f -> 1f
            p < 0.5f -> 0.25f + 1.5f * p
            p >= 0.58f && p < 0.66f -> 0.3f
            p >= 0.74f && p < 0.82f -> 0.6f
            else -> 1f
        }
        val length = max(thick, size.width * shown)
        val from = Offset(thick / 2f, y)
        val to = Offset(length - thick / 2f, y)
        // its light: wider and fainter, three times
        drawLine(tube, from, to, strokeWidth = thick * 3.4f, cap = StrokeCap.Round, alpha = 0.09f * lit)
        drawLine(tube, from, to, strokeWidth = thick * 2.3f, cap = StrokeCap.Round, alpha = 0.15f * lit)
        drawLine(tube, from, to, strokeWidth = thick * 1.5f, cap = StrokeCap.Round, alpha = 0.24f * lit)
        drawRoundRect(
            Brush.verticalGradient(0f to tube, 0.38f to Color.White, 0.58f to Color.White, 1f to tube, startY = y - thick / 2f, endY = y + thick / 2f),
            Offset(0f, y - thick / 2f), Size(length, thick), round, alpha = lit,
        )
    }

    /** A box of light [round] at its corners: the line itself, and its glow as wider, fainter lines around it. */
    private fun DrawScope.sign(color: Color, round: Float, glow: Float, ground: Color) {
        val corner = CornerRadius(round)
        drawRoundRect(ground, cornerRadius = corner)
        if (glow > 0f) {
            for (j in 1..4) drawRoundRect(color, cornerRadius = corner, alpha = glow * (0.2f - 0.035f * j), style = Stroke(j * j * 1.1.dp.toPx()))
        }
        drawRoundRect(color, cornerRadius = corner, style = Stroke(max(1f, 1.1.dp.toPx())))
    }

    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier =
        padding(top = if (first) 2.dp else 0.dp, bottom = if (last) 2.dp else 0.dp)

    /** Under a finger a name is a lit tube, and throws light. */
    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = if (!lit) this else drawBehind {
        val top = 6.dp.toPx()
        val right = size.width - 2f * env.cell.width
        for (j in 1..3) {
            val out = j * 2.2.dp.toPx()
            drawRoundRect(Cyan, Offset(-out, top - out), Size(right + 2f * out, size.height - 2f * top + 2f * out), CornerRadius(out), alpha = 0.14f)
        }
    }

    /** A small sign of its own, which dims to plain glass as the note stops being news. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 8.dp)
        .drawBehind {
            val f = faded()
            sign(lerp(Magenta.copy(alpha = 0.95f), env.palette.line, f), 12.dp.toPx(), 1f - f, Color(0x9E18052C))
        }
        .padding(horizontal = 11.dp, vertical = 5.dp)

    /** Outlined pills: cyan for an app, magenta for a command, unlit for the rest. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val p = env.palette
            val (edge, glow) = when (color) {
                p.app -> Cyan.copy(alpha = 0.95f) to 0.8f
                p.fg -> Magenta.copy(alpha = 0.95f) to 0.8f
                p.accent -> Magenta.copy(alpha = 0.7f) to 0f
                else -> p.dim.copy(alpha = 0.65f) to 0f
            }
            sign(edge, size.height / 2f, glow, Ink.copy(alpha = 0.6f))
        }
        .padding(horizontal = 12.5.dp, vertical = 2.5.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = when (color) {
        env.palette.app -> env.base.copy(color = color, shadow = glow(Cyan, 0.7f, 7.5f))
        env.palette.fg -> env.base.copy(color = Color(0xFFFFD9F6), shadow = glow(Magenta, 0.8f, 7.5f))
        env.palette.accent -> env.base.copy(color = color, shadow = glow(Magenta, 0.8f, 7.5f))
        else -> env.base.copy(color = color, shadow = Unlit)
    }

    /** A cyan block that dims instead of going out. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line, color = palette.fg)
        blinkOn -> SpanStyle(background = palette.app, color = palette.bg, shadow = Unlit)
        else -> SpanStyle(background = palette.app.copy(alpha = 0.22f))
    }

    /* ---------------- movement ---------------- */

    private val Slide = CubicBezierEasing(0.16f, 0.84f, 0.3f, 1f)
    private val Converge = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
    private val Leave = CubicBezierEasing(0.6f, 0f, 0.9f, 0.35f)

    /* Paints for drawing a part of the page again as something else: fainter, or as one colour. */
    private val Fainter = Paint()
    private val CyanGhost = Paint().apply { colorFilter = ColorFilter.tint(Cyan, BlendMode.SrcIn) }
    private val MagentaGhost = Paint().apply { colorFilter = ColorFilter.tint(Magenta, BlendMode.SrcIn) }
    private val CyanSplit = Paint().apply { colorFilter = ColorFilter.tint(Color(0xFF00FFFF), BlendMode.SrcIn) }
    private val MagentaSplit = Paint().apply { colorFilter = ColorFilter.tint(Color(0xFFFF1EBE), BlendMode.SrcIn) }

    private inline fun DrawScope.through(paint: Paint, alpha: Float, bounds: Rect, block: () -> Unit) {
        val canvas = drawContext.canvas
        paint.alpha = alpha.coerceIn(0f, 1f)
        canvas.saveLayer(bounds, paint)
        try {
            block()
        } finally {
            canvas.restore()
        }
    }

    override val lineMs get() = 420

    /** A new line slides in from the left while its cyan and magenta ghosts converge on it. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val em = env.cell.width / advance
        val here = Slide.transform(between(p, 0f, 0.75f))
        val x = -1.5f * em * (1f - here)
        val apart = 0.95f * em * (1f - Converge.transform(p))
        val bounds = Rect(-3f * em, -0.6f * em, size.width + 1.5f * em, size.height + 0.6f * em)
        val ghost = 0.9f * here * (1f - p * p)
        through(CyanGhost, ghost, bounds) { translate(left = x - apart) { this@drawWithContent.drawContent() } }
        through(MagentaGhost, ghost, bounds) { translate(left = x + apart) { this@drawWithContent.drawContent() } }
        through(Fainter, here, bounds) { translate(left = x) { this@drawWithContent.drawContent() } }
    }

    /** The page cut into uneven bands: where each one ends, down to [height]. The same cut every time for the same [salt]. */
    private fun bands(height: Float, salt: Int): FloatArray {
        val ends = FloatArray(48)
        var n = 0
        var y = 0f
        while (y < height && n < ends.size) {
            y = if (n == ends.size - 1) height else min(height, floor(y + height * (0.025f + 0.085f * dice(n, salt))))
            ends[n++] = y
        }
        return ends.copyOf(n)
    }

    private val litBands = Path()
    private val dimBands = Path()

    /**
     * The scene as [stage] has it, and over it the page as a sign that is partly on: [level] says
     * of each band whether it is dark (0), stuttering (1) or lit (2).
     */
    private inline fun ContentDrawScope.signOver(env: LookEnv, stage: Stage, level: (band: Int) -> Int) {
        scene(env, stage)
        val ends = bands(size.height, 3)
        litBands.reset()
        dimBands.reset()
        var top = 0f
        var lit = false
        var dim = false
        for (i in ends.indices) {
            when (level(i)) {
                2 -> { litBands.addRect(Rect(0f, top, size.width, ends[i])); lit = true }
                1 -> { dimBands.addRect(Rect(0f, top, size.width, ends[i])); dim = true }
            }
            top = ends[i]
        }
        staged = stage
        try {
            if (lit) clipPath(litBands) { this@signOver.drawContent() }
            if (dim) through(Fainter, 0.32f, Rect(Offset.Zero, size)) { clipPath(dimBands) { this@signOver.drawContent() } }
        } finally {
            staged = null
        }
    }

    /** A tube striking, [f] of the way through: dark, a couple of stutters, then steady. */
    private fun striking(f: Float, late: Float): Int = when {
        f < 0.07f -> 0
        f < 0.14f + late -> 2
        f < 0.36f + late -> 0
        f < 0.46f + late -> 2
        f < 0.58f + late -> 1
        f < 0.68f + late -> 2
        f < 0.76f + late -> 1
        else -> 2
    }

    /** And going out. */
    private fun dying(f: Float, late: Float): Int = when {
        f < 0.1f -> 2
        f < 0.2f + late -> 1
        f < 0.42f + late -> 2
        f < 0.52f + late -> 0
        f < 0.66f + late -> 2
        f < 0.8f -> 0
        f < 0.88f -> 1
        else -> 0
    }

    private fun inOut(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    /** Waking: the horizon opens, the grid draws itself out from it, the sun comes up, the sign strikes. */
    override val powerOn: Motion = object : Motion(ms = 1250, readableMs = 260, backdrop = Night) {
        private fun stage(t: Float): Stage {
            // the floor starts fast and settles; in all it runs one cell more than it would have, so it lands in step
            val settling = between(t, 250f, 1250f)
            return Stage(
                sky = easeOut(t / 700f),
                hz = easeOut(t / 300f),
                reach = inOut(between(t, 150f, 1000f)),
                sun = easeOut(between(t, 180f, 1250f)),
                dim = inOut(between(t, 300f, 900f)),
                run = 2f * (min(t, 250f) / 1000f + 0.25f * (1f - (1f - settling).pow(4))),
            )
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) {
                drawContent()
                return
            }
            if (t < readableMs) {
                scene(env, stage(t))
                return
            }
            signOver(env, stage(t)) { i -> striking((t - readableMs - 260f * dice(i, 11)) / 520f, 0.06f * dice(i, 12)) }
        }
    }

    /** Locking: the sign flickers out, the sun sets, the grid goes out line by line, the horizon closes. */
    override val powerOff: Motion = object : Motion(ms = 1050, backdrop = Night) {
        private fun stage(t: Float) = Stage(
            sun = 1f - easeIn(between(t, 80f, 820f)),
            lines = 1f - between(t, 220f, 860f),
            sky = 1f - inOut(between(t, 600f, 980f)),
            hz = 1f - easeIn(between(t, 880f, 1040f)),
            dim = 1f - inOut(between(t, 150f, 600f)),
            anywhere = true,
        )

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t <= 0f) {
                drawContent()
                return
            }
            signOver(env, stage(t)) { i -> dying((t - 300f * dice(i, 13)) / 420f, 0.08f * dice(i, 14)) }
        }
    }

    /** When the page starts to tear, after "access granted" has had a moment to be read. */
    private const val TEAR = 200f
    private val Tears = floatArrayOf(0.14f, 0.5f, 0.22f, 0.85f, 0.4f, 1.1f, 1.45f)
    private const val TEAR_MS = 54f

    /**
     * A right password: the lock page is cut into slices that shear apart, harder each time, their
     * colours split, and then leave, every other one the other way.
     */
    override val granted: Motion = object : Motion(ms = 900) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t < TEAR) {
                drawContent()
                return
            }
            val w = size.width
            val em = env.cell.width / advance
            val ends = bands(size.height, 9)
            val n = ends.size
            val step = ((t - TEAR) / TEAR_MS).toInt().coerceAtMost(Tears.lastIndex)
            val force = Tears[step]
            val leaving = TEAR + Tears.size * TEAR_MS
            val at = FloatArray(n)
            val apart = FloatArray(n)
            var faint = 0L
            for (i in 0 until n) {
                val seed = i * 16 + step
                val hit = dice(seed, 21) < 0.28f + force * 0.42f
                var x = if (hit) (dice(seed, 22) * 2f - 1f) * force * 0.15f * w else 0f
                apart[i] = (if (hit) force * (0.25f + 0.55f * dice(seed, 23)) else force * 0.1f) * em
                if (t >= leaving) {
                    val gone = Leave.transform(between(t, leaving + i * 6f, leaving + i * 6f + 190f))
                    x += ((if (i % 2 == 1) 1.12f else -1.12f) * w - x) * gone
                } else if (dice(seed, 24) < force * 0.1f) {
                    faint = faint or (1L shl i)
                }
                at[i] = x
            }
            val all = Rect(Offset.Zero, size)
            // each slice's two ghosts, then the slice itself
            through(CyanSplit, 0.85f, all) { slices(ends, at, apart, -1f, -1L) }
            through(MagentaSplit, 0.85f, all) { slices(ends, at, apart, 1f, -1L) }
            slices(ends, at, apart, 0f, faint.inv())
            if (faint != 0L) through(Fainter, 0.2f, all) { slices(ends, at, apart, 0f, faint) }
        }

        /** Draws the page once for each band in [which], through that band only, moved to where the band is. */
        private fun ContentDrawScope.slices(ends: FloatArray, at: FloatArray, apart: FloatArray, side: Float, which: Long) {
            var top = 0f
            for (i in ends.indices) {
                if (which and (1L shl i) != 0L) {
                    clipRect(top = top, bottom = ends[i]) {
                        translate(left = at[i] + side * apart[i]) { this@slices.drawContent() }
                    }
                }
                top = ends[i]
            }
        }
    }

    override val launchMs get() = 640

    private val Streaks = arrayOf(Cyan, Magenta, Color.White, Color(0xFFB98CFF), Color(0xFFFFD9F6), Cyan)
    private val LineFlare = Brush.horizontalGradient(0f to Color.White, 0.5f to Cyan, 1f to Cyan.copy(alpha = 0f))

    /** Warp: the page falls back into the night while streaks pour out of the horizon, into white. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        if (ms <= 0f) return
        val w = size.width
        val u = w / 100f
        val cx = w / 2f
        val hy = horizon(env)
        val far = hypot(cx, max(hy, size.height - hy)) * 1.08f
        val x = between(ms, 40f, 400f)
        val warp = x * x * x

        // the launch line overdriven for a moment
        val flare = 1f - between(ms, 0f, 240f)
        if (flare > 0f) drawRect(LineFlare, line.topLeft, line.size, alpha = flare * 0.6f, blendMode = BlendMode.Plus)
        drawRect(Night, alpha = 0.62f * smooth(between(ms, 50f, 330f)))

        val travelled = 2.2f * (0.3f * ms + 234f * x * x * x * x) / 1000f
        val long = 0.03f + 0.55f * warp.pow(1.6f)
        val shown = min(1f, x * 3f)
        for (i in 0 until 64) {
            val angle = dice(i, 31) * 2f * PI.toFloat()
            val p = (dice(i, 32) + travelled * (0.6f + 0.9f * dice(i, 33))) % 1f
            val from = far * p * p
            val to = from + far * long * (0.25f + p)
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                Streaks[i % Streaks.size],
                Offset(cx + dx * from, hy + dy * from),
                Offset(cx + dx * to, hy + dy * to),
                strokeWidth = max(1f, u * (0.12f + p * 0.85f * (0.5f + dice(i, 35)))),
                cap = StrokeCap.Round,
                alpha = shown * min(1f, p * 4f) * (0.45f + 0.55f * dice(i, 34)),
                blendMode = BlendMode.Plus,
            )
        }

        // the bloom they end in, and white
        val flash = easeIn(between(ms, 200f, 430f))
        if (flash > 0f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.White, 0.45f to rgba(255, 236, 252, 0.9f), 0.75f to rgba(255, 140, 232, 0.4f), 1f to rgba(255, 90, 220, 0f),
                    center = Offset(cx, hy), radius = far * (0.1f + 1.7f * flash.pow(1.4f)),
                ),
                alpha = min(1f, flash * 1.5f),
            )
            val white = ((flash - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (white > 0f) drawRect(Color.White, alpha = white * white)
        }
    }
}
