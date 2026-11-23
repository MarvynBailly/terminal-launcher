package com.terminallauncher.ui.looks

import android.content.res.Resources
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Flight Deck: a head-up display on a cockpit canopy. Fine linework in one cool color, amber for the
 * pilot's own marks (prompt, carets, callouts), red for alerts. The page sits in a thin frame with
 * two clipped corners; the scale down its left counts rows and the one along the bottom columns.
 *
 * The frame is drawn by [frame], not by [background]: there it is measured from the screen's safe
 * edges, as the text is, so it stays around the text under a camera cut-out and above the keyboard.
 */
object Hud : Look() {
    override val id = "hud"

    private val Deck = Palette(
        bg = Color(0xFF060A0F), fg = Color(0xFFCFE9F4), dim = Color(0xFF6C90A2), line = Color(0xFF1F3F4E),
        accent = Color(0xFFFFB23E), app = Color(0xFF55D8FF), ok = Color(0xFF7CF0DC), err = Color(0xFFFF5145),
    )

    override fun palette(theme: String): Palette = Deck

    private val Cyan = Deck.app
    private val Amber = Deck.accent

    /** A line at full strength. */
    private val Hot = Color(0xFFE6F9FF)

    /** The glass with the power off. */
    private val Dark = Color(0xFF020304)

    /** The frame's hairline, and a bracket around something. */
    private val Edge = Cyan.copy(alpha = 0.34f)
    private val Mark = Cyan.copy(alpha = 0.7f)

    /* Where things are, from the screen's safe edges: the web drawing's numbers on a phone 411 dp wide. */
    private val FrameLeft = 29.dp
    private val FrameRight = 11.dp
    private val TextLeft = 43.dp
    private val AboveStatus = 5.dp
    private val StatusHigh = 28.dp

    /** From the frame's top edge to the first line. */
    private val UnderRim = 10.dp

    /** The row of chips, which the screen keeps under the page, and how far into it the frame's bottom edge sits. */
    private val Chips = 48.dp
    private val RimDrop = 6.dp

    /** The column scale along the bottom. */
    private val Strip = 24.dp

    /** How much of a corner is clipped off: the frame's, and a chip's. */
    private val Cut = 17.dp

    /* ---------------- type: Share Tech Mono, labels in Rajdhani ---------------- */

    private val Mono = FontFamily(Font(R.font.share_tech_mono_regular))
    private val Face = FontFamily(Font(R.font.rajdhani_light, FontWeight.Light), Font(R.font.rajdhani_semibold, FontWeight.SemiBold))

    override val font get() = Mono
    override val fontSize get() = 15.sp
    override val lineHeight get() = 22.sp
    override val advance get() = 0.54f
    override val gutter get() = 24.dp

    /** Everything on the glass glows a little in its own color. */
    private fun glow(color: Color, radius: Float = 14f) = Shadow(color.copy(alpha = 0.34f), Offset.Zero, radius)

    override fun type(base: TextStyle, palette: Palette): TextStyle = base.copy(shadow = glow(palette.fg))

    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        Style.MARK -> SpanStyle(color = palette.bg, background = palette.accent, shadow = Shadow(Color.Transparent))
        Style.NORMAL -> SpanStyle(color = palette.fg)
        else -> SpanStyle(color = palette.of(style), shadow = glow(palette.of(style)))
    }

    /* ---------------- the ground: a glow, and scan lines ---------------- */

    override fun DrawScope.background(env: LookEnv) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF070D14), Color(0xFF04070A))))
        // a wide glow behind the middle of what can be seen
        val middle = Offset(size.width * 0.52f, (size.height - env.keyboard) * 0.46f)
        val reach = size.width * 0.8f
        withTransform({ scale(1f, 0.82f, middle) }) {
            drawCircle(Brush.radialGradient(listOf(Color(0x3326688C), Color.Transparent), center = middle, radius = reach), reach, middle)
        }
        scanlines(pitch = 3.2.dp.toPx().toInt(), color = Color(0x0A96E1FF), dark = 1.dp.toPx().toInt().coerceAtLeast(1))
        val lit = ground()
        if (lit < 1f) drawRect(Dark, alpha = 1f - lit)
    }

    /* ---------------- the deck: frame, scales and carets ---------------- */

    override fun Modifier.frame(env: LookEnv): Modifier = this
        .drawWithContent {
            drawContent()
            unlit(env)
            deck(env)
        }
        .padding(start = TextLeft - gutter, top = AboveStatus, bottom = Strip)

    private fun DrawScope.rimBox(): Rect =
        Rect(FrameLeft.toPx(), (AboveStatus + StatusHigh).toPx(), size.width - FrameRight.toPx(), size.height - (Strip + Chips - RimDrop).toPx())

    /** An outline with its top left and bottom right corners clipped off. */
    private fun clipped(r: Rect, cut: Float): Path = Path().apply {
        moveTo(r.left + cut, r.top)
        lineTo(r.right, r.top)
        lineTo(r.right, r.bottom - cut)
        lineTo(r.right - cut, r.bottom)
        lineTo(r.left, r.bottom)
        lineTo(r.left, r.top + cut)
        close()
    }

    private fun path(a: Offset, b: Offset, c: Offset): Path = pathOf(listOf(a, b, c))
    private fun path(a: Offset, b: Offset, c: Offset, d: Offset): Path = pathOf(listOf(a, b, c, d))

    private fun pathOf(points: List<Offset>): Path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }

    private fun DrawScope.diamond(color: Color, at: Offset, side: Float, alpha: Float = 1f) {
        val r = side * 0.7071f
        drawPath(path(Offset(at.x, at.y - r), Offset(at.x + r, at.y), Offset(at.x, at.y + r), Offset(at.x - r, at.y)).apply { close() }, color, alpha = alpha)
    }

    /** The frame's four corners out to a part of the way along its edges: [open] 1 shows the brackets, 2 all of it. */
    private fun DrawScope.corners(r: Rect, open: Float): Path {
        val arm = 39.dp.toPx()
        val out = 8.dp.toPx()
        val across = if (open <= 1f) arm * open else arm + (r.width / 2f - arm) * (open - 1f)
        val down = if (open <= 1f) arm * open else arm + (r.height / 2f - arm) * (open - 1f)
        return Path().apply {
            addRect(Rect(r.left - out, r.top - out, r.left + across, r.top + down))
            addRect(Rect(r.right - across, r.top - out, r.right + out, r.top + down))
            addRect(Rect(r.left - out, r.bottom - down, r.left + across, r.bottom + out))
            addRect(Rect(r.right - across, r.bottom - down, r.right + out, r.bottom + out))
        }
    }

    private fun DrawScope.rim(r: Rect) {
        val cut = Cut.toPx()
        val outline = clipped(r, cut)
        drawPath(outline, Cyan, alpha = 0.07f, style = Stroke(5.dp.toPx()))
        drawPath(outline, Edge, style = Stroke(1.dp.toPx()))
        // a bracket on each corner: the clipped ones longer
        val long = 37.dp.toPx()
        val short = 26.dp.toPx()
        val heavy = Stroke(2.dp.toPx())
        drawPath(path(Offset(r.left, r.top + long), Offset(r.left, r.top + cut), Offset(r.left + cut, r.top), Offset(r.left + long, r.top)), Cyan, style = heavy)
        drawPath(path(Offset(r.right - short, r.top), Offset(r.right, r.top), Offset(r.right, r.top + short)), Cyan, style = heavy)
        drawPath(path(Offset(r.right, r.bottom - long), Offset(r.right, r.bottom - cut), Offset(r.right - cut, r.bottom), Offset(r.right - long, r.bottom)), Cyan, style = heavy)
        drawPath(path(Offset(r.left, r.bottom - short), Offset(r.left, r.bottom), Offset(r.left + short, r.bottom)), Cyan, style = heavy)
        diamond(Cyan, Offset(r.right, r.center.y), 4.5.dp.toPx())
        diamond(Cyan, Offset(r.center.x, r.bottom), 4.5.dp.toPx())
    }

    /** A tick of one of the scales: every tenth is the longest and has a number, the ones between are graded. */
    private fun tick(i: Int, mid: Int): Pair<Dp, Color> = when {
        i % 10 == 0 -> 8.6.dp to Cyan
        i % mid == 0 -> 6.2.dp to Cyan.copy(alpha = 0.62f)
        else -> 3.7.dp to Cyan.copy(alpha = 0.4f)
    }

    private fun DrawScope.deck(env: LookEnv) {
        val r = rimBox()
        val hair = 1.dp.toPx()
        val open = opened()
        if (open >= 2f) rim(r) else if (open > 0f) clipPath(corners(r, open)) { rim(r) }

        val small = TextStyle(fontFamily = Mono, fontSize = 8.4.sp, color = Deck.dim)

        // down the left edge, half rows of text
        val step = env.cell.height / 2f
        val top = r.top + UnderRim.toPx()
        val ends = 22.dp.toPx()
        val rows = ((r.bottom - UnderRim.toPx() - top) / step).toInt()
        val rowsOut = (rows * rowsOut()).toInt()
        if (rowsOut() > 0f) for (i in 0..rowsOut) {
            val (long, color) = tick(i, 2)
            val y = top + i * step
            drawRect(color, Offset(ends - long.toPx(), y - hair / 2f), Size(long.toPx(), hair))
            if (i % 10 == 0) {
                val laid = env.measurer.measure((i / 2).toString().padStart(2, '0'), small)
                drawText(laid, topLeft = Offset(ends - 10.5.dp.toPx() - laid.size.width, y - laid.size.height / 2f))
            }
        }
        // an amber caret on it, parked at the middle row
        val rowCaret = lit(360f, 150f)
        if (rowCaret > 0f) {
            val y = top + ((rows / 2) or 1) * step
            val x = ends - 0.5.dp.toPx()
            drawPath(path(Offset(x, y), Offset(x + 6.2.dp.toPx(), y - 4.1.dp.toPx()), Offset(x + 6.2.dp.toPx(), y + 4.1.dp.toPx())), Amber, alpha = rowCaret)
        }

        // along the bottom, columns of text
        val from = TextLeft.toPx()
        val base = size.height - Strip.toPx() + 1.dp.toPx()
        val columns = ((size.width - from - gutter.toPx()) / env.cell.width).toInt()
        val columnsOut = (columns * columnsOut()).toInt()
        if (columnsOut() > 0f) for (i in 0..columnsOut) {
            val (long, color) = tick(i, 5)
            val x = from + i * env.cell.width
            drawRect(color, Offset(x - hair / 2f, base), Size(hair, long.toPx()))
            if (i % 10 == 0) {
                val laid = env.measurer.measure(i.toString().padStart(2, '0'), small)
                drawText(laid, topLeft = Offset(x - laid.size.width / 2f, base + 10.5.dp.toPx()))
            }
        }
        // and its caret, where an empty prompt leaves the cursor
        val columnCaret = lit(390f, 190f)
        if (columnCaret > 0f) {
            val x = from + ((if (env.locked) 10 else 2) + 0.5f) * env.cell.width
            val y = base - 1.5.dp.toPx()
            drawPath(path(Offset(x, y), Offset(x - 4.1.dp.toPx(), y - 6.2.dp.toPx()), Offset(x + 4.1.dp.toPx(), y - 6.2.dp.toPx())), Amber, alpha = columnCaret)
        }

        // a small reticle where the row scale begins
        val eye = lit(250f, 240f)
        if (eye > 0f) {
            val at = Offset(13.5.dp.toPx(), (AboveStatus + StatusHigh / 2).toPx())
            val half = 8.6.dp.toPx()
            val gap = half * 0.52f
            drawLine(Cyan, Offset(at.x - half, at.y), Offset(at.x - gap, at.y), hair, alpha = eye)
            drawLine(Cyan, Offset(at.x + gap, at.y), Offset(at.x + half, at.y), hair, alpha = eye)
            drawLine(Cyan, Offset(at.x, at.y - half), Offset(at.x, at.y - gap), hair, alpha = eye)
            drawLine(Cyan, Offset(at.x, at.y + gap), Offset(at.x, at.y + half), hair, alpha = eye)
            drawCircle(Cyan, 5.1.dp.toPx(), at, alpha = eye, style = Stroke(hair))
            drawRect(Amber, Offset(at.x - 1.25.dp.toPx(), at.y - 1.25.dp.toPx()), Size(2.5.dp.toPx(), 2.5.dp.toPx()), alpha = eye)
        }
    }

    /* ---------------- coming online and going dark ---------------- */

    /*
     * Waking and locking move the deck part by part, and a Motion is only handed the screen as one
     * picture. So while one of those two draws, it leaves the time here for the parts to read, and
     * takes it away again before its draw call returns. Outside such a call both are null, and
     * everything is at rest.
     */
    private var waking: Float? = null
    private var darkening: Float? = null

    /** Coming online: a flash, a dark beat, then on. */
    private fun online(p: Float): Float = when {
        p < 0.08f -> 0f
        p < 0.16f -> 0.85f
        p < 0.48f -> 0.1f
        p < 0.6f -> 1f
        p < 0.72f -> 0.4f
        else -> 1f
    }

    /** How lit a part is that comes on [on] ms into waking and starts to go [off] ms into locking. */
    private fun lit(on: Float, off: Float): Float =
        waking?.let { online((it - on) / 240f) } ?: darkening?.let { online(1f - (it - off) / 200f) } ?: 1f

    private fun ground(): Float = waking?.let { between(it, 0f, 260f) } ?: darkening?.let { 1f - between(it, 580f, 820f) } ?: 1f

    /** The frame: 0 nothing, 1 its corner brackets, 2 all of it. */
    private fun opened(): Float =
        waking?.let { if (it < 260f) easeOut(between(it, 20f, 200f)) else 1f + smooth(between(it, 260f, 520f)) }
            ?: darkening?.let { if (it < 600f) 2f - smooth(between(it, 360f, 560f)) else 1f - easeIn(between(it, 620f, 760f)) }
            ?: 2f

    /** The scales populate and retract a tick at a time. */
    private fun rowsOut(): Float = waking?.let { between(it, 160f, 480f) } ?: darkening?.let { 1f - between(it, 200f, 480f) } ?: 1f
    private fun columnsOut(): Float = waking?.let { between(it, 210f, 530f) } ?: darkening?.let { 1f - between(it, 240f, 520f) } ?: 1f

    /** What a movement still keeps dark of the status line, the page and the chips. */
    private fun DrawScope.unlit(env: LookEnv) {
        val on = waking
        val off = darkening
        if (on == null && off == null) return
        val r = rimBox()
        val cover = lerp(Dark, env.palette.bg, ground())
        val status = 1f - lit(300f, 110f)
        if (status > 0f) drawRect(cover, Offset(FrameLeft.toPx() - 2.dp.toPx(), 0f), Size(size.width, r.top - 3.dp.toPx()), alpha = status)
        val chips = 1f - lit(440f, 60f)
        if (chips > 0f) drawRect(cover, Offset(FrameLeft.toPx(), r.bottom + 2.dp.toPx()), Size(size.width, (Chips - RimDrop - 12.dp).toPx()), alpha = chips)
        clipPath(clipped(r, Cut.toPx())) {
            if (on != null) {
                // the page comes on as one
                val page = 1f - online((on - 440f) / 240f)
                if (page > 0f) drawRect(cover, r.topLeft, r.size, alpha = page)
            } else if (off != null) {
                // and goes line by line, from the last one up
                val line = env.cell.height
                var y = r.bottom
                var i = 0
                while (y > r.top) {
                    val gone = 1f - online(1f - (off - i * 10f) / 200f)
                    if (gone > 0f) drawRect(cover, Offset(r.left, y - line), Size(r.width, line + 1f), alpha = gone)
                    y -= line
                    i++
                }
            }
        }
    }

    /** The frame draws itself from its corners, the scales fill in a tick at a time, then the instruments come on in turn. */
    override val powerOn: Motion = object : Motion(ms = 700, readableMs = 480, backdrop = Dark) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            waking = t
            try {
                drawContent()
            } finally {
                waking = null
            }
        }
    }

    /** Systems down in turn: lines go dark, the scales retract, the frame folds into its corners, a last line shrinks to a point. */
    override val powerOff: Motion = object : Motion(ms = 820, backdrop = Dark) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            darkening = t
            try {
                drawContent()
            } finally {
                darkening = null
            }
            val grown = when {
                t < 330f -> 0f
                t < 440f -> easeOut(between(t, 330f, 440f))
                t < 580f -> 1f
                else -> 1f - 0.988f * smooth(between(t, 580f, 740f))
            }
            val alpha = 1f - between(t, 740f, 810f)
            if (grown <= 0f || alpha <= 0f) return
            val left = FrameLeft.toPx()
            val right = size.width - FrameRight.toPx()
            val half = (right - left) / 2f * grown
            val x = (left + right) / 2f
            val y = (size.height - env.keyboard) * 0.48f
            drawRect(Cyan, Offset(x - half - 3.dp.toPx(), y - 4.dp.toPx()), Size(half * 2f + 6.dp.toPx(), 8.dp.toPx()), alpha = 0.16f * alpha)
            drawRect(Cyan, Offset(x - half - 1.dp.toPx(), y - 2.dp.toPx()), Size(half * 2f + 2.dp.toPx(), 4.dp.toPx()), alpha = 0.4f * alpha)
            drawRect(Hot, Offset(x - half, y - 0.8.dp.toPx()), Size(half * 2f, 1.6.dp.toPx()), alpha = alpha)
        }
    }

    /* ---------------- the status line ---------------- */

    /** Above the frame's top edge and as wide as it: an amber mark, the label in the display's color, the readings dim. */
    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(bottom = UnderRim)
        .height(StatusHigh)
        .drawBehind {
            val x = (FrameLeft - TextLeft).toPx() + 0.5.dp.toPx()
            val y = size.height / 2f
            drawPath(path(Offset(x, y - 3.5.dp.toPx()), Offset(x + 6.3.dp.toPx(), y), Offset(x, y + 3.5.dp.toPx())), Amber)
        }
        .layout { measurable, constraints ->
            // out to the frame's right edge, and a little wider than its text so the glow is not cut
            val left = 6.dp.roundToPx()
            val more = left + (gutter - FrameRight).roundToPx()
            val placed = measurable.measure(constraints.copy(minWidth = constraints.minWidth + more, maxWidth = constraints.maxWidth + more))
            layout(constraints.maxWidth, placed.height) { placed.place(-left, 0) }
        }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // only the label is tinted: what stands to its right keeps its own colors
            val label = 6.dp.toPx() + words(env, statusText("tty1 · " + if (env.locked) "locked" else "home"), statusStyle(env)).width
            val fade = 30.dp.toPx()
            drawRect(
                Brush.horizontalGradient(0f to Cyan, label / (label + fade) to Cyan, 1f to Color.Transparent, startX = 0f, endX = label + fade),
                size = Size(label + fade, size.height),
                blendMode = BlendMode.SrcAtop,
            )
        }
        .padding(start = 6.dp)

    override fun statusStyle(env: LookEnv): TextStyle = env.base.copy(
        fontFamily = Face, fontWeight = FontWeight.SemiBold, fontSize = 12.6.sp, lineHeight = 14.sp, letterSpacing = 0.16.em,
        color = env.palette.dim, shadow = glow(env.palette.dim, 10f),
    )

    override fun statusText(text: String): String = text.uppercase()

    /* ---------------- the lock page ---------------- */

    /**
     * How big the clock's ring is: most of the page's width, less where the keyboard leaves too
     * little height for it and the words around it. The movement of a right password needs the same
     * number, so it comes from what both are told.
     */
    private fun Density.ring(width: Float, env: LookEnv): Float {
        val seen = Resources.getSystem().displayMetrics.heightPixels - env.keyboard
        // the frame, the scales and a camera cut-out, then the page's other lines and the prompt
        val room = seen - (AboveStatus + StatusHigh + UnderRim + Chips + Strip + 28.dp + 44.dp).toPx() - 5f * env.cell.height
        return minOf(width * 0.74f, room).coerceAtLeast(96.dp.toPx())
    }

    /** Thin numerals in a ring of sixty ticks, an amber caret at the minute and a white one at the hour. */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val high = with(LocalDensity.current) { ring(width.toPx(), env).toDp() }
        Canvas(Modifier.size(width, high)) { dial(time, env, leaving?.invoke()) }
    }

    /** A point on the dial: [r] out from [c] in units of [u], [deg] clockwise from twelve. */
    private fun polar(c: Offset, u: Float, r: Float, deg: Float): Offset {
        val a = (deg - 90f) * PI.toFloat() / 180f
        return Offset(c.x + r * u * cos(a), c.y + r * u * sin(a))
    }

    private fun DrawScope.caret(color: Color, c: Offset, u: Float, tip: Float, base: Float, deg: Float, half: Float, alpha: Float = 1f) {
        drawPath(path(polar(c, u, tip, deg), polar(c, u, base, deg - half), polar(c, u, base, deg + half)).apply { close() }, color, alpha = alpha)
    }

    private fun DrawScope.arc(color: Color, c: Offset, r: Float, from: Float, sweep: Float, width: Float, alpha: Float = 1f) {
        drawArc(color, from - 90f, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), alpha, Stroke(width))
    }

    private fun DrawScope.dial(time: String, env: LookEnv, leaving: Float?) {
        val c = center
        // the web drawing is 100 across, and the minute's caret stands a little outside that
        val u = size.height / 103f
        val hair = maxOf(1.dp.toPx(), 0.42f * u)
        // locked on: everything flashes
        val flash = if (leaving == null || leaving < LockAt) 0f else 1f - between(leaving, LockAt, LockAt + 240f)
        val ink = lerp(Cyan, Hot, flash)

        for (i in 0 until 60) {
            val five = i % 5 == 0
            drawLine(ink, polar(c, u, if (five) 42.9f else 44.7f, i * 6f), polar(c, u, 46.9f, i * 6f), if (five) hair * 1.3f else hair, alpha = if (five) 1f else 0.5f + 0.5f * flash)
        }
        drawCircle(Cyan, 40.4f * u, c, alpha = 0.07f, style = Stroke(5.dp.toPx()))
        drawCircle(ink, 40.4f * u, c, alpha = 0.32f + 0.5f * flash, style = Stroke(hair))
        for (q in 0 until 4) {
            arc(ink, c, 40.4f * u, q * 90f + 33f, 24f, 1.15f * u)
            drawLine(ink, polar(c, u, 36.8f, q * 90f), polar(c, u, 40.4f, q * 90f), hair)
        }

        val hour = time.substringBefore(':').toIntOrNull() ?: 0
        val minute = time.substringAfter(':').toIntOrNull() ?: 0
        caret(Amber, c, u, 47.7f, 51.2f, minute * 6f, 2.6f)
        caret(Color(0xFFEAFAFF), c, u, 39.3f, 35.9f, (hour % 12 + minute / 60f) * 30f, 3.6f)

        // the numerals, each in a cell of its own so the time does not shift as it changes
        val em = 28.8f * u
        val style = TextStyle(
            fontFamily = Face, fontWeight = FontWeight.Light, fontSize = em.toSp(), color = Color(0xFFEEFBFF),
            shadow = Shadow(Cyan.copy(alpha = 0.6f + 0.4f * flash), Offset.Zero, 4f * u * (1f + flash)),
        )
        var x = c.x - 1.1f * em
        for (ch in time) {
            val colon = ch == ':'
            val cell = (if (colon) 0.24f else 0.49f) * em
            val laid = env.measurer.measure(ch.toString(), style)
            val y = c.y - 3.3f * u + 0.32f * em - laid.firstBaseline - (if (colon) 0.05f * em else 0f)
            drawText(laid, color = if (colon) ink else style.color, topLeft = Offset(x + (cell - laid.size.width) / 2f, y))
            x += cell
        }

        if (leaving != null) reticle(c, u, leaving)
    }

    /* ---------------- a right password ---------------- */

    private const val SpinMs = 360f
    private const val LockAt = 360f
    private const val SeamAt = 480f
    private const val PartAt = 560f

    private val Spin = CubicBezierEasing(0.5f, 0f, 0.15f, 1f)
    private val Part = CubicBezierEasing(0.7f, 0f, 0.8f, 0.35f)

    /** Locked on: two quick blinks. */
    private fun blink(p: Float): Float = if (p > 0f && p < 1f && (p * 5f).toInt() % 2 == 1) 0.15f else 1f

    /** The targeting reticle: two rings of marks that spin down onto the clock, lock, and fold into the seam. */
    private fun DrawScope.reticle(c: Offset, u: Float, at: Float) {
        if (at >= PartAt) return
        val p = Spin.transform(between(at, 0f, SpinMs))
        val drawn = easeOut(between(at, 0f, 170f))
        val seen = blink(between(at, LockAt, SeamAt))
        val shut = easeIn(between(at, SeamAt, PartAt))
        val line = 0.6f * u
        withTransform({ scale(1f + 0.3f * shut, 1f - shut, c) }) {
            // four arcs and four amber marks, turning in from outside
            val near = if (p < 0.86f) p / 0.86f else 1f
            val last = if (p < 0.86f) 0f else (p - 0.86f) / 0.14f
            val inner = seen * between(p, 0f, 0.2f)
            withTransform({
                rotate(lerp(lerp(-160f, 7f, near), 0f, last), c)
                scale(lerp(lerp(1.75f, 0.95f, near), 1f, last), c)
            }) {
                for (q in 0 until 4) {
                    arc(Cyan, c, 54.5f * u, q * 90f + 18f, 54f * drawn, line, inner)
                    caret(Amber, c, u, 51.5f, 57.5f, q * 90f, 3.4f, inner)
                }
            }
            // four spokes and a dashed ring, turning the other way
            val outer = seen * between(p, 0f, 0.25f)
            withTransform({
                rotate(lerp(130f, 0f, p), c)
                scale(lerp(2.2f, 1f, p), c)
            }) {
                for (q in 0 until 4) drawLine(Cyan, polar(c, u, 60f, q * 90f + 45f), polar(c, u, 60f + 13f * drawn, q * 90f + 45f), line, alpha = outer)
                drawCircle(Cyan, 64f * u, c, alpha = 0.75f * outer, style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.8f * u, 4.6f * u))))
            }
        }
    }

    /**
     * A reticle draws around the clock, spins down onto it and locks (that part is the clock's own);
     * then the page parts along a seam through the clock, one half up and one down.
     */
    override val granted: Motion = object : Motion(ms = 900) {
        /** Where the middle of the clock is on the lock page: under the boot log if the page fits, else up from its end. */
        private fun ContentDrawScope.seam(env: LookEnv): Float {
            val line = env.cell.height
            val half = ring(size.width, env) / 2f
            val above = 8f * line + 22.dp.toPx() + half
            val below = half + 16.dp.toPx() + 4f * line
            return if (above + below > size.height) size.height - below else above
        }

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val y = seam(env)
            val hair = 1.dp.toPx()
            if (t < PartAt) {
                drawContent()
                // the seam opens from the middle outwards
                val open = easeOut(between(t, SeamAt, PartAt))
                if (open > 0f) {
                    val half = size.width / 2f * open
                    drawRect(Cyan, Offset(center.x - half, y - 3.dp.toPx()), Size(half * 2f, 6.dp.toPx()), alpha = 0.3f)
                    drawRect(Hot, Offset(center.x - half, y - hair), Size(half * 2f, hair * 2f))
                }
                return
            }
            val p = Part.transform(between(t, PartAt, ms.toFloat()))
            val glow = 9.dp.toPx()
            translate(top = -(y + glow) * p) {
                clipRect(bottom = y) { this@draw.drawContent() }
                drawRect(Hot, Offset(0f, y - hair), Size(size.width, hair))
                drawRect(Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.5f), Color.Transparent), startY = y, endY = y + glow), Offset(0f, y), Size(size.width, glow))
            }
            translate(top = (size.height - y + glow) * p) {
                clipRect(top = y) { this@draw.drawContent() }
                drawRect(Hot, Offset(0f, y), Size(size.width, hair))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.5f)), startY = y - glow, endY = y), Offset(0f, y - glow), Size(size.width, glow))
            }
        }
    }

    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.TITLE -> env.base.copy(
            fontFamily = Face, fontWeight = FontWeight.SemiBold, fontSize = 12.9.sp, letterSpacing = 0.32.em,
            color = env.palette.dim, shadow = glow(env.palette.dim, 10f),
        )
        LockLine.DATE -> env.base.copy(
            fontFamily = Face, fontWeight = FontWeight.SemiBold, fontSize = 15.3.sp, letterSpacing = 0.36.em,
            color = env.palette.accent, shadow = glow(env.palette.accent),
        )
        LockLine.INFO -> env.base.copy(fontSize = 14.1.sp, color = env.palette.dim, shadow = glow(env.palette.dim, 10f))
    }

    override fun lockText(line: LockLine, text: String): String = if (line == LockLine.INFO) text else text.uppercase()

    /* ---------------- gauges ---------------- */

    override val gaugeSize: DpSize get() = DpSize(110.dp, 24.dp)

    /** Hazard stripes for a gauge that is over its budget. */
    private fun DrawScope.hazard(): ShaderBrush {
        val p = 7.dp.toPx().toInt().coerceAtLeast(4)
        return tiled("hud hazard $p", p, p) { pixels ->
            for (i in pixels.indices) pixels[i] = (if ((i % p + i / p) % p < p / 2) Amber else Color(0xFFA3160F)).toArgb()
        }
    }

    /** Segments, a caret above at the exact value, ticks beneath. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val cells = 14
        val hair = 1.dp.toPx()
        val gap = 2.dp.toPx()
        val wide = (size.width - gap * (cells - 1)) / cells
        val top = 8.5.dp.toPx()
        val high = 7.5.dp.toPx()
        val filled = (shown * cells + 0.5f).toInt()
        val under = top + high + 2.4.dp.toPx()
        if (filled > 0 && !alert) {
            // lit segments glow
            val lit = filled * (wide + gap) - gap
            drawRect(Cyan, Offset(-3.dp.toPx(), top - 3.dp.toPx()), Size(lit + 6.dp.toPx(), high + 6.dp.toPx()), alpha = 0.1f)
            drawRect(Cyan, Offset(-1.5.dp.toPx(), top - 1.5.dp.toPx()), Size(lit + 3.dp.toPx(), high + 3.dp.toPx()), alpha = 0.14f)
        }
        for (i in 0 until cells) {
            val x = i * (wide + gap)
            when {
                i >= filled -> drawRect(env.palette.line, Offset(x + hair / 2f, top + hair / 2f), Size(wide - hair, high - hair), style = Stroke(hair))
                alert -> drawRect(hazard(), Offset(x, top), Size(wide, high))
                else -> drawRect(Cyan, Offset(x, top), Size(wide, high))
            }
            drawRect(env.palette.dim, Offset(x, under), Size(hair, (if (i % 7 == 0) 5f else 2.4f).dp.toPx()), alpha = 0.75f)
        }
        drawRect(env.palette.dim, Offset(size.width - hair, under), Size(hair, 5.dp.toPx()), alpha = 0.75f)
        // the segment that has just come on is still white
        if (filled in 1..cells && shown < target) drawRect(Color.White, Offset((filled - 1) * (wide + gap), top), Size(wide, high))
        val x = shown * size.width
        drawPath(
            path(Offset(x - 4.5.dp.toPx(), 0.3.dp.toPx()), Offset(x + 4.5.dp.toPx(), 0.3.dp.toPx()), Offset(x, 5.9.dp.toPx())),
            if (alert) env.palette.err else Color(0xFFEAFAFF),
        )
    }

    /* ---------------- rows ---------------- */

    /**
     * Lets a row's text be only as wide as it is, at the row's start, and tells [draw] how wide that
     * came out. The row itself keeps its width.
     */
    private fun Modifier.hugged(draw: ContentDrawScope.(text: Float) -> Unit): Modifier = composed {
        val wide = remember { FloatArray(1) }
        drawWithContent { draw(wide[0]) }.layout { measurable, constraints ->
            val placed = measurable.measure(constraints.copy(minWidth = 0))
            wide[0] = placed.width.toFloat()
            layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placed.width, placed.height) { placed.place(0, 0) }
        }
    }

    /** A bracket in the margin at a row's left. */
    private fun DrawScope.bracket(color: Color, alpha: Float = 1f, grown: Float = 1f) {
        val x = -9.dp.toPx()
        val arm = 5.dp.toPx()
        val hair = 1.dp.toPx()
        val half = (size.height / 2f - 4.dp.toPx()) * grown
        val y = size.height / 2f
        drawRect(color, Offset(x, y - half), Size(hair, half * 2f), alpha = alpha)
        drawRect(color, Offset(x, y - half), Size(arm, hair), alpha = alpha)
        drawRect(color, Offset(x, y + half - hair), Size(arm, hair), alpha = alpha)
    }

    /** Something tappable sits in a bracket of its own. */
    override fun Modifier.tapRow(env: LookEnv): Modifier = drawBehind { bracket(Mark) }

    /** A typed command heads its own section: a rule runs from it to a node. */
    override fun Modifier.echoRow(env: LookEnv): Modifier = hugged { text ->
        drawContent()
        val from = text + env.cell.width * 1.4f
        val node = 4.4.dp.toPx()
        val hair = 1.dp.toPx()
        if (size.width - from < env.cell.width * 2f) return@hugged
        val y = size.height / 2f
        drawRect(
            Brush.horizontalGradient(listOf(env.palette.line, Amber.copy(alpha = 0.45f)), startX = from, endX = size.width),
            Offset(from, y - hair / 2f), Size(size.width - from - node, hair), alpha = 0.8f,
        )
        drawRect(Amber, Offset(size.width - node, y - node / 2f), Size(node, node), alpha = 0.8f)
    }

    /** App names: a bracketed list, each with a small marker. */
    override fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier = this
        .fillMaxWidth()
        .drawBehind {
            val arm = 11.dp.toPx()
            val hair = 1.dp.toPx()
            val out = 3.dp.toPx()
            if (first) {
                drawRect(Mark, Offset(-out, 0f), Size(arm, hair))
                drawRect(Mark, Offset(-out, 0f), Size(hair, arm))
                drawRect(Mark, Offset(size.width + out - arm, 0f), Size(arm, hair))
                drawRect(Mark, Offset(size.width + out - hair, 0f), Size(hair, arm))
            }
            if (last) {
                drawRect(Mark, Offset(-out, size.height - hair), Size(arm, hair))
                drawRect(Mark, Offset(-out, size.height - arm), Size(hair, arm))
                drawRect(Mark, Offset(size.width + out - arm, size.height - hair), Size(arm, hair))
                drawRect(Mark, Offset(size.width + out - hair, size.height - arm), Size(hair, arm))
            }
        }
        .padding(top = if (first) 6.dp else 0.dp, bottom = if (last) 6.dp else 0.dp)

    override fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = drawBehind {
        val side = 4.2.dp.toPx()
        val r = side * 0.7071f
        val at = Offset(-7.dp.toPx(), size.height / 2f)
        drawPath(
            path(Offset(at.x, at.y - r), Offset(at.x + r, at.y), Offset(at.x, at.y + r), Offset(at.x - r, at.y)).apply { close() },
            if (lit) Hot else Cyan, alpha = 0.85f, style = Stroke(1.dp.toPx()),
        )
    }

    /** A notification: a callout from the frame's right edge, on a leader line. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(vertical = 8.dp)
        .wrapContentWidth(Alignment.End)
        .padding(end = 18.dp)
        .drawBehind {
            val old = faded()
            val color = lerp(Amber, env.palette.dim, old)
            val arm = 11.dp.toPx()
            val hair = 1.dp.toPx()
            drawRect(Amber, alpha = 0.07f * (1f - 0.6f * old))
            for (right in listOf(false, true)) for (low in listOf(false, true)) {
                val x = if (right) size.width else 0f
                val y = if (low) size.height else 0f
                drawRect(color, Offset(if (right) x - arm else x, if (low) y - hair else y), Size(arm, hair))
                drawRect(color, Offset(if (right) x - hair else x, if (low) y - arm else y), Size(hair, arm))
            }
            // the leader, out to a node on the frame
            val reach = 18.dp.toPx() + (gutter - FrameRight).toPx()
            val node = 5.dp.toPx()
            drawRect(color, Offset(size.width, size.height / 2f - hair / 2f), Size(reach, hair))
            drawRect(color, Offset(size.width + reach - node / 2f, size.height / 2f - node / 2f), Size(node, node))
        }
        .padding(horizontal = 12.dp, vertical = 5.dp)

    /* ---------------- prompt and chips ---------------- */

    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val outline = clipped(Rect(Offset.Zero, size), 6.dp.toPx())
            val edge = when (color) {
                env.palette.app -> Cyan.copy(alpha = 0.55f)
                env.palette.accent -> Amber.copy(alpha = 0.6f)
                else -> env.palette.dim.copy(alpha = 0.6f)
            }
            drawPath(outline, Cyan, alpha = 0.05f)
            drawPath(outline, edge, style = Stroke(1.dp.toPx()))
        }
        .padding(horizontal = 10.dp, vertical = 2.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(color = color, shadow = null)

    override fun promptColor(commands: Boolean, palette: Palette): Color = if (commands) palette.accent else palette.dim

    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line, color = palette.fg)
        blinkOn -> SpanStyle(background = palette.app, color = palette.bg, shadow = Shadow(palette.app.copy(alpha = 0.8f), Offset.Zero, 18f))
        else -> SpanStyle()
    }

    /* ---------------- a line arriving ---------------- */

    override val lineMs get() = 420

    /** An empty line is a divider: a hairline that ends in two nodes. */
    private fun DrawScope.rule(env: LookEnv, grown: Float) {
        val inset = env.cell.width * 1.5f
        val node = 4.4.dp.toPx()
        val hair = 1.dp.toPx()
        val y = size.height / 2f
        val wide = (size.width - 2f * inset) * grown
        if (wide <= node) return
        drawRect(env.palette.line, Offset(inset, y - hair / 2f), Size(wide, hair))
        drawRect(env.palette.dim, Offset(inset, y - node / 2f), Size(node, node))
        drawRect(env.palette.dim, Offset(inset + wide - node, y - node / 2f), Size(node, node))
    }

    /** A bracket opens at the line's left, and the text resolves behind a scanning bar. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = hugged { text ->
        val p = progress()
        if (p <= 0f) return@hugged
        // Nothing but a space, which is how an empty line is set. One letter by itself is as wide, so the rule starts after it.
        if (text <= env.cell.width * 1.2f && size.height <= env.cell.height * 1.5f) rule(env, easeOut(between(p, 0f, 0.8f)))
        if (p >= 1f) {
            drawContent()
            return@hugged
        }
        bracket(Cyan, alpha = 1f - between(p, 0.74f, 1f), grown = between(p, 0f, 0.12f))
        val scan = between(p, 0.14f, 0.7f)
        if (scan <= 0f) return@hugged
        val head = text * scan
        // the margin stays in: a tappable line keeps its own bracket there
        clipRect(left = -14.dp.toPx(), right = head) { this@hugged.drawContent() }
        if (scan < 1f) {
            val tail = minOf(head, 39.dp.toPx())
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.22f)), startX = head - tail, endX = head),
                Offset(head - tail, 0f), Size(tail, size.height),
            )
            drawRect(Hot, Offset(head, 0f), Size(2.dp.toPx(), size.height))
        }
    }

    /* ---------------- launching an app ---------------- */

    override val launchMs get() = 600

    private val Close = CubicBezierEasing(0.3f, 1.22f, 0.4f, 1f)
    private val Wall = Color(0xFF101216)

    /** Four corner brackets around [box]. */
    private fun DrawScope.lockOn(box: Rect, alpha: Float) {
        val arm = 10.7.dp.toPx()
        val wide = 2.dp.toPx()
        for (right in listOf(false, true)) for (low in listOf(false, true)) {
            val x = if (right) box.right else box.left
            val y = if (low) box.bottom else box.top
            drawRect(Cyan, Offset(if (right) x - arm - 2.dp.toPx() else x - 2.dp.toPx(), if (low) y - wide - 2.dp.toPx() else y - 2.dp.toPx()), Size(arm + 4.dp.toPx(), wide + 4.dp.toPx()), alpha = 0.22f * alpha)
            drawRect(Hot, Offset(if (right) x - arm else x, if (low) y - wide else y), Size(arm, wide), alpha = alpha)
            drawRect(Hot, Offset(if (right) x - wide else x, if (low) y - arm else y), Size(wide, arm), alpha = alpha)
        }
    }

    /** Brackets close in on the launched line and lock; then the frame rushes past, a tunnel down to the app. */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        if (ms <= 0f) return
        val hair = 1.dp.toPx()
        // about where the frame is: the safe edges are not known out here
        val frame = Rect(FrameLeft.toPx(), (AboveStatus + StatusHigh + 24.dp).toPx(), size.width - FrameRight.toPx(), size.height - env.keyboard - (Strip + Chips - RimDrop).toPx())
        val from = TextLeft.toPx()
        val target = Rect(
            from - 9.dp.toPx(), line.top - 3.dp.toPx(),
            minOf(size.width - gutter.toPx(), from + (name.length + 7) * env.cell.width) + 9.dp.toPx(), line.bottom + 3.dp.toPx(),
        )
        val focus = target.center

        val p = between(ms, 230f, 540f)
        if (p > 0f) {
            val rho = 0.66f
            val rings = 12
            val smallest = rho.pow(rings)
            val far = smallest.pow((1f - p) * (1f - p))
            val ramp = minOf(1f, p * 6f)
            fun x(v: Float, s: Float) = focus.x + (v - focus.x) * s
            fun y(v: Float, s: Float) = focus.y + (v - focus.y) * s
            // the page falls behind
            drawRect(env.palette.bg, alpha = between(p, 0f, 0.5f))
            // four rails along the tunnel's edges
            for (corner in listOf(frame.topLeft, frame.topRight, frame.bottomRight, frame.bottomLeft)) {
                drawLine(Cyan, Offset(x(corner.x, far / rho), y(corner.y, far / rho)), Offset(x(corner.x, 16f), y(corner.y, 16f)), hair, alpha = 0.22f * ramp)
            }
            // the frame, again and again: the nearer, the bigger
            for (i in 1 until rings) {
                val s = far / rho.pow(i)
                if (s < 0.02f || s > 9f) continue
                val a = ramp * minOf(1f, (s - 0.02f) / 0.1f) * minOf(1f, (9f - s) / 6f)
                val ring = clipped(Rect(x(frame.left, s), y(frame.top, s), x(frame.right, s), y(frame.bottom, s)), Cut.toPx() * s)
                drawPath(ring, Cyan, alpha = a * 0.16f, style = Stroke(hair * (3f + s)))
                drawPath(ring, Cyan, alpha = a, style = Stroke(hair * (1f + s * 0.2f)))
            }
            // the far wall: the app, the size of the phone
            val wall = Rect(focus.x * (1f - far), focus.y * (1f - far), focus.x * (1f - far) + size.width * far, focus.y * (1f - far) + size.height * far)
            drawRect(Wall, wall.topLeft, wall.size)
            drawRect(Hot, wall.topLeft, wall.size, alpha = minOf(1f, (1f - far) * 5f) * ramp, style = Stroke(hair * 1.5f))
            val label = env.measurer.measure(
                name.uppercase(),
                TextStyle(fontFamily = Face, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.32.em, color = Hot),
            )
            withTransform({ scale(far, far, wall.center) }) {
                drawText(label, topLeft = Offset(wall.center.x - label.size.width / 2f, wall.center.y - label.size.height / 2f), alpha = between(far, 0.05f, 0.5f))
            }
        }

        // the brackets: in from the frame, two blinks, then away past the eye
        val away = easeIn(between(ms, 230f, 420f))
        if (away < 1f) {
            val box = lerp(frame, target, Close.transform(between(ms, 0f, 150f)))
            val alpha = between(ms, 0f, 24f) * blink(between(ms, 150f, 230f)) * (1f - away)
            withTransform({ scale(1f + 3f * away, 1f + 3f * away, box.center) }) { lockOn(box, alpha) }
        }
    }
}
