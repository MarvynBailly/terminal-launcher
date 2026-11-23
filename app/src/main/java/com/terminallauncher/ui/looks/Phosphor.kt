package com.terminallauncher.ui.looks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.Palette
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phosphor: a P3 amber tube from 1983, seen through deep glass. One hue at three strengths, an
 * error in reverse video as it was, and everything that arrives drawn by the beam.
 */
object Phosphor : Look() {
    override val id = "phosphor"

    private val Amber = Palette(
        bg = Color(0xFF0A0602), fg = Color(0xFFFFB000), dim = Color(0xFFC08219), line = Color(0xFF6E460C),
        accent = Color(0xFFFFE6B3), app = Color(0xFFFFCD68), ok = Color(0xFFFFCD68), err = Color(0xFFFFB000),
    )

    /** The beam itself, before the phosphor cools to amber. */
    private val Hot = Color(0xFFFFF3D6)

    override fun palette(theme: String): Palette = Amber

    /* ---------------- type: VT323, tall and narrow ---------------- */

    private val Vt = FontFamily(Font(R.font.vt323_regular))
    override val font get() = Vt
    override val fontSize get() = 21.sp
    override val lineHeight get() = 23.sp
    override val advance get() = 0.4f

    private fun bloom(color: Color, radius: Float = 9f) = Shadow(color.copy(alpha = 0.6f), Offset.Zero, radius)

    override fun type(base: TextStyle, palette: Palette): TextStyle = base.copy(shadow = bloom(palette.fg))

    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        // reverse video: the one way this tube has to shout
        Style.ERR, Style.MARK -> SpanStyle(color = palette.bg, background = palette.fg, shadow = Shadow(Color.Transparent))
        // brighter is more light, so more bloom
        Style.ACCENT, Style.APP, Style.OK -> SpanStyle(color = palette.of(style), shadow = bloom(palette.fg, 14f))
        else -> SpanStyle(color = palette.of(style))
    }

    /* ---------------- the glass ---------------- */

    override val moves get() = true

    override fun DrawScope.overlay(env: LookEnv) {
        val t = env.time()
        scanlines(pitch = 3.dp.toPx().toInt(), color = Color.Black.copy(alpha = 0.26f))
        // the refresh band drifts down the tube every seven seconds
        val band = size.height * 0.16f
        val y = (t % 7000f) / 7000f * (size.height + band) - band
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.5f to Amber.fg.copy(alpha = 0.05f), 1f to Color.Transparent, startY = y, endY = y + band),
            topLeft = Offset(0f, y), size = Size(size.width, band),
        )
        vignette(Color.Black, strength = 0.62f, clear = 0.5f)
        // mains flicker, barely there
        val flicker = 0.012f * (1f + sin(t / 1000f * 2f * PI.toFloat() * 8.3f)) + 0.01f * (1f + sin(t / 1000f * 2f * PI.toFloat() * 0.7f))
        drawRect(Color.Black, alpha = flicker)
    }

    /* ---------------- parts ---------------- */

    /** A reverse-video bar at half strength. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(top = 8.dp, bottom = 8.dp)
        .background(env.palette.dim)
        .padding(horizontal = 6.dp)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 19.sp, lineHeight = 21.sp, color = env.palette.bg, shadow = Shadow(Color.Transparent))

    /** Double-size characters, as a VT100 drew a headline. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        BasicText(
            text = time,
            style = env.base.copy(fontSize = 92.sp, lineHeight = 78.sp, color = env.palette.fg, shadow = bloom(env.palette.fg, 28f)),
            // a right password overdrives it for a moment before the picture rolls
            modifier = Modifier.graphicsLayer { if (leaving != null) alpha = 1f - 0.35f * between(leaving(), 0f, 120f) * (1f - between(leaving(), 120f, 260f)) },
        )
    }

    /** Blocks between square brackets: `[#########.....]`. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val p = env.palette
        val bracket = 3.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val edge = if (alert) Hot else p.fg
        // [
        drawRect(edge, Offset(0f, 0f), Size(stroke, size.height))
        drawRect(edge, Offset(0f, 0f), Size(bracket, stroke))
        drawRect(edge, Offset(0f, size.height - stroke), Size(bracket, stroke))
        // ]
        drawRect(edge, Offset(size.width - stroke, 0f), Size(stroke, size.height))
        drawRect(edge, Offset(size.width - bracket, 0f), Size(bracket, stroke))
        drawRect(edge, Offset(size.width - bracket, size.height - stroke), Size(bracket, stroke))
        val cells = 14
        val inner = size.width - 2 * (bracket + 1.dp.toPx())
        val gap = 1.5.dp.toPx()
        val w = (inner - gap * (cells - 1)) / cells
        val x0 = bracket + 1.dp.toPx()
        val inset = 2.dp.toPx()
        val filled = (shown * cells + 0.5f).toInt()
        for (i in 0 until cells) {
            val lit = i < filled
            drawRect(
                color = if (!lit) p.line else if (alert) Hot else p.fg,
                topLeft = Offset(x0 + i * (w + gap), inset),
                size = Size(w, size.height - 2 * inset),
            )
        }
        // the cell the beam has just lit is still white-hot
        if (filled in 1..cells && shown < target) {
            drawRect(Hot, Offset(x0 + (filled - 1) * (w + gap), inset), Size(w, size.height - 2 * inset))
        }
    }

    /** A box drawn in line characters, its frame cooling as the note stops being news. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 6.dp)
        .drawBehind { drawRect(lerp(env.palette.fg, env.palette.line, faded()), style = Stroke(1.dp.toPx())) }
        .padding(horizontal = 10.dp, vertical = 4.dp)

    /** Soft keys: reverse-video labels along the bottom of the tube. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .background(if (color == env.palette.dim) env.palette.line else env.palette.dim)
        .padding(horizontal = 9.dp, vertical = 2.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle =
        env.base.copy(color = if (color == env.palette.dim) env.palette.fg else env.palette.bg, shadow = Shadow(Color.Transparent))

    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line, color = palette.bg)
        blinkOn -> SpanStyle(background = palette.fg, color = palette.bg, shadow = Shadow(Color.Transparent))
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    override val lineMs get() = 230

    /** Drawn by the beam, left to right: a white-hot spot leads, and what it has just drawn is still cooling. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val head = size.width * p
        clipRect(right = head) { this@drawWithContent.drawContent() }
        val tail = env.cell.width * 6f
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Hot.copy(alpha = 0.32f), startX = head - tail, endX = head),
            topLeft = Offset((head - tail).coerceAtLeast(0f), 0f),
            size = Size(minOf(tail, head), size.height),
            blendMode = BlendMode.Plus,
        )
        drawRect(Hot, Offset(head, size.height * 0.15f), Size(env.cell.width * 0.7f, size.height * 0.7f))
    }

    /** Warming up: a hot line, a white-hot bloom as it opens past its size, then the degauss wobble. */
    override val powerOn: Motion = object : Motion(ms = 900, readableMs = 380) {
        override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
            transformOrigin = TransformOrigin(0.5f, ((size.height - env.keyboard) / 2f / size.height).coerceIn(0.1f, 0.5f))
            val thin = 3.dp.toPx() / size.height
            when {
                t < 220f -> {
                    scaleX = easeOut(t / 220f)
                    scaleY = thin
                }
                t < 520f -> scaleY = thin + (1.035f - thin) * easeOut((t - 220f) / 300f)
                else -> {
                    // the degauss coil: the picture shivers and settles
                    val p = between(t, 520f, ms.toFloat())
                    val swing = (1f - p) * (1f - p)
                    scaleY = 1f + 0.035f * swing * kotlin.math.cos(p * 7f * PI.toFloat())
                    translationX = size.width * 0.012f * swing * sin(p * 9f * PI.toFloat())
                    rotationZ = 0.5f * swing * sin(p * 6f * PI.toFloat())
                }
            }
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            val glow = when {
                t < 220f -> 1f
                t < 560f -> 1f - easeOut((t - 220f) / 340f)
                else -> 0f
            }
            if (glow > 0f) drawRect(Hot, alpha = glow)
        }
    }

    /** Switched off: the picture collapses to a line, the line to a dot, and the dot lingers. */
    override val powerOff: Motion = object : Motion(ms = 820) {
        override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
            transformOrigin = TransformOrigin(0.5f, ((size.height - env.keyboard) / 2f / size.height).coerceIn(0.1f, 0.5f))
            val thin = 3.dp.toPx() / size.height
            val dot = 5.dp.toPx() / size.width
            when {
                t < 240f -> scaleY = 1f - (1f - thin) * easeIn(t / 240f)
                t < 420f -> {
                    scaleY = thin
                    scaleX = 1f - (1f - dot) * easeIn((t - 240f) / 180f)
                }
                else -> {
                    scaleY = thin * 1.6f
                    scaleX = dot
                    alpha = 1f - easeIn(between(t, 420f, ms.toFloat()))
                }
            }
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            drawContent()
            drawRect(Hot, alpha = easeIn(between(t, 0f, 240f)))
        }
    }

    /**
     * Vertical hold is lost: the page rolls upward, faster and faster, with the blanking bar
     * between one picture and the next, and leaves its burn-in behind as it goes.
     */
    override val granted: Motion = object : Motion(ms = 880) {
        override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
            alpha = 1f - easeIn(between(t, 620f, ms.toFloat()))
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t < 140f) {
                // it holds for a moment, jittering
                translate(top = size.height * 0.004f * sin(t / 9f)) { this@draw.drawContent() }
                return
            }
            val p = between(t, 140f, ms.toFloat())
            val rolled = (p * p * 2.6f * size.height) % size.height
            val bar = size.height * 0.07f
            clipRect {
                translate(top = -rolled) { this@draw.drawContent() }
                translate(top = size.height - rolled + bar) { this@draw.drawContent() }
                drawRect(Color.Black, Offset(0f, size.height - rolled), Size(size.width, bar))
                drawRect(Amber.fg.copy(alpha = 0.35f), Offset(0f, size.height - rolled + bar - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
        }
    }

    override val launchMs get() = 620

    /** A channel change: amber static with a rolling bar, settling to dark for the app to come up in. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val up = between(ms, 0f, 110f)
        if (up <= 0f) return
        val frame = (ms / 33f).toInt()
        drawRect(Color(0xFF040200), alpha = up)
        val snow = grain(Amber.fg, lo = 0f, hi = 0.95f, salt = 7, size = 128)
        val settle = 1f - 0.75f * between(ms, 300f, 480f)
        shifted(snow, dx = dice(frame, 1) * 128f, dy = dice(frame, 2) * 128f, alpha = up * settle)
        // the rolling bar, and the launch line overdriven where the cut began
        val bar = size.height * 0.18f
        val y = ((ms / 300f) % 1f) * (size.height + bar) - bar
        drawRect(Color.Black, Offset(0f, y), Size(size.width, bar), alpha = 0.55f * up)
        drawRect(Hot, Offset(0f, line.top), Size(size.width, line.height), alpha = (1f - between(ms, 0f, 200f)) * 0.9f)
    }
}
