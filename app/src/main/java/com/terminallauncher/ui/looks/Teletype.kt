package com.terminallauncher.ui.looks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.CLOCK_COLUMNS
import com.terminallauncher.ui.CLOCK_ROWS
import com.terminallauncher.ui.Palette
import com.terminallauncher.ui.clockCells
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Teletype: a teleprinter typing onto a continuous roll of paper. The paper runs between two parts
 * of the machine: the paper bail with its column scale (the status line) and the tear bar with the
 * keys (the chips). What is on the paper was struck through a ribbon, black or red, a key at a time.
 */
object Teletype : Look() {
    override val id = "teletype"

    private val Sheet = Palette(
        bg = Color(0xFFEFE7D5), fg = Color(0xFF25211D), dim = Color(0xFF5F584C), line = Color(0xFFCDC3AB),
        accent = Color(0xFFB1271F), app = Color(0xFF2A3D63), ok = Color(0xFF2C6249), err = Color(0xFFC21D16),
        light = true,
    )

    /** The legends on the machine, the machine itself, and the room with the lamp off. */
    private val Cream = Color(0xFFDED4BC)
    private val Machine = Color(0xFF141210)
    private val Night = Color(0xFF0C0B0A)

    /** What age and a warm lamp do to paper. */
    private val Stain = Color(0xFF3A2810)

    override fun palette(theme: String): Palette = Sheet

    /* ---------------- type: Courier Prime, ten to the inch ---------------- */

    private val Courier = FontFamily(
        Font(R.font.courier_prime_regular, FontWeight.Normal),
        Font(R.font.courier_prime_bold, FontWeight.Bold),
    )
    private val Elite = FontFamily(Font(R.font.special_elite_regular))

    override val font get() = Courier
    override val fontSize get() = 14.sp
    override val lineHeight get() = 21.sp
    override val advance get() = 0.6f

    /** Room for the tractor holes. With the smaller face it comes to the same columns as stock. */
    override val gutter get() = 26.dp

    /** The size of the type in pixels. */
    private fun em(env: LookEnv): Float = env.cell.width / advance

    /** The red half of the ribbon and a second strike are all the emphasis there is. */
    override fun span(style: Style, palette: Palette): SpanStyle = when (style) {
        Style.OK, Style.ACCENT -> SpanStyle(color = palette.of(style), fontWeight = FontWeight.Bold)
        Style.MARK -> SpanStyle(color = palette.bg, background = palette.accent, fontWeight = FontWeight.Bold)
        else -> SpanStyle(color = palette.of(style))
    }

    /* ---------------- the paper ---------------- */

    /** Smooth noise from 0 to 1 that repeats every [w] by [h] pixels; its features are [fx] by [fy] pixels. */
    private fun cloud(x: Int, y: Int, w: Int, h: Int, fx: Int, fy: Int, salt: Int): Float {
        val nx = w / fx
        val ny = h / fy
        val gx = x.toFloat() / fx
        val gy = y.toFloat() / fy
        val x0 = gx.toInt()
        val y0 = gy.toInt()
        val tx = smooth(gx - x0)
        val ty = smooth(gy - y0)
        fun at(i: Int, j: Int) = dice((j % ny) * nx + (i % nx), salt)
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val low = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (low - top) * ty
    }

    /** The fibres of cheap paper: short dark ones lying across the roll, pale streaks along it. */
    private val Fibres: ShaderBrush by lazy {
        tiled("teletype fibres", 256, 256) { pixels ->
            for (i in pixels.indices) {
                val x = i % 256
                val y = i / 256
                val dark = ((cloud(x, y, 256, 256, 32, 2, 11) - 0.72f) * 0.3f).coerceIn(0f, 1f)
                val pale = ((cloud(x, y, 256, 256, 2, 64, 29) - 0.66f) * 0.5f).coerceIn(0f, 1f)
                pixels[i] = if (pale > dark) ((pale * 255f).toInt() shl 24) or 0xFFFAEB else ((dark * 255f).toInt() shl 24) or 0x6B4F29
            }
        }
    }

    /** Where a rubber stamp did not take the ink: soft blots, to be cut out of whatever it printed. */
    private val Blots: ShaderBrush by lazy {
        tiled("teletype blots", 256, 256) { pixels ->
            for (i in pixels.indices) {
                val x = i % 256
                val y = i / 256
                val n = 0.5f * cloud(x, y, 256, 256, 64, 64, 5) + 0.26f * cloud(x, y, 256, 256, 32, 32, 6) +
                    0.15f * cloud(x, y, 256, 256, 16, 16, 7) + 0.09f * cloud(x, y, 256, 256, 4, 4, 8)
                val gone = (1.9f - 3.4f * n).coerceIn(0f, 0.92f)
                pixels[i] = (gone * 255f).toInt() shl 24
            }
        }
    }

    /** The surface of the paper, over whatever color is under it. */
    private fun DrawScope.tooth() {
        drawRect(grain(Color(0xFF66502A), lo = 0f, hi = 0.11f, salt = 4))
        drawRect(Fibres)
    }

    /** A round pool of light or shade about [at], [rx] by [ry], running out to the edges of the canvas. */
    private fun DrawScope.pool(at: Offset, rx: Float, ry: Float, vararg stops: Pair<Float, Color>, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver) {
        val squash = ry / rx
        scale(1f, squash, pivot = at) {
            drawRect(
                Brush.radialGradient(*stops, center = at, radius = rx),
                topLeft = Offset(0f, at.y - at.y / squash),
                size = Size(size.width, size.height / squash),
                alpha = alpha,
                blendMode = blend,
            )
        }
    }

    /**
     * The tractor feed down both edges, between [from] and [to]: a hole a line, and the perforation
     * the strips tear off along.
     */
    private fun DrawScope.feedHoles(env: LookEnv, from: Float = 0f, to: Float = size.height) {
        val pitch = env.cell.height
        val r = 4.dp.toPx()
        val inset = 11.dp.toPx()
        val rim = Color(0xFF46341E)
        var y = pitch * (((from - r * 2f) / pitch).toInt() + 0.5f)
        while (y < to + r * 2f) {
            if (y - r * 1.5f >= from && y + r * 1.5f <= to) {
                for (x in floatArrayOf(inset, size.width - inset)) {
                    // the paper is pressed in around the punch
                    drawCircle(rim, r * 1.42f, Offset(x, y), alpha = 0.16f)
                    drawCircle(rim, r * 1.16f, Offset(x, y), alpha = 0.4f)
                    drawCircle(Color(0xFF191714), r, Offset(x, y))
                }
            }
            y += pitch
        }
        val tear = 21.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(pitch / 8f, pitch / 8f), -from)
        for (x in floatArrayOf(tear, size.width - tear)) {
            drawLine(Color(0x615C482C), Offset(x, from), Offset(x, to), strokeWidth = 0.8.dp.toPx().coerceAtLeast(1f), pathEffect = dash)
        }
    }

    override fun DrawScope.background(env: LookEnv) {
        tooth()
        // the lamp over the paper: brightest under the bail, falling off toward the corners
        pool(Offset(size.width / 2f, size.height * 0.09f), size.width * 0.88f, size.height * 0.4f, 0f to Color(0x99FFFBEC), 1f to Color(0x00FFFBEC))
        pool(
            Offset(size.width / 2f, size.height * 0.3f), size.width * 1.25f, size.height * 0.82f,
            0f to Stain.copy(alpha = 0f), 0.52f to Stain.copy(alpha = 0f), 1f to Stain.copy(alpha = 0.25f),
        )
        drawRect(Brush.verticalGradient(0.45f to Stain.copy(alpha = 0f), 1f to Stain.copy(alpha = 0.12f)))
        feedHoles(env)
    }

    /**
     * What is behind the paper: the dark body of the machine, ribbed, and the roller the paper runs
     * over, its highlight ending at [roller].
     */
    private fun DrawScope.platen(roller: Float) {
        drawRect(Machine)
        scanlines(pitch = 10.dp.toPx().toInt(), color = Color(0xFF1B1815), dark = 1.dp.toPx().toInt().coerceAtLeast(1))
        val band = 150.dp.toPx()
        drawRect(
            Brush.verticalGradient(
                0f to Color(0x0024211D), 0.27f to Color(0xFF24211D), 0.42f to Color(0xFF3B3631), 0.65f to Color(0xFF1B1916), 1f to Color(0xFF090807),
                startY = roller - band, endY = roller,
            ),
            topLeft = Offset(0f, roller - band), size = Size(size.width, band),
        )
        drawRect(Color(0xFF090807), topLeft = Offset(0f, roller), size = Size(size.width, (size.height - roller).coerceAtLeast(0f)))
    }

    /* ---------------- the machine: the paper bail above, the tear bar below ---------------- */

    /** The chips stand in a row this tall at the foot of the page. */
    private val KEYS = 48.dp

    /** The saw the paper is torn against stands this far above the bar. */
    private val TEETH = 5.dp

    /**
     * Everything the status line prints is printed as on paper, dark on light. On the bail it shows
     * as its negative: ink comes out cream, the red stays red, a red label keeps its dark letters.
     */
    private val Engraved: Paint by lazy {
        Paint().apply {
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0f, -0.5f, -0.5f, 0f, 255f,
                        -0.96f, 0f, 0f, 0f, 245f,
                        -0.86f, 0f, 0f, 0f, 219f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }

    override fun Modifier.status(env: LookEnv): Modifier = this
        .padding(bottom = 10.dp)
        .drawBehind { bail(env) }
        .drawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Engraved)
                drawContent()
                canvas.restore()
            }
        }
        .padding(top = 6.dp, bottom = 17.dp)

    override fun statusStyle(env: LookEnv): TextStyle =
        env.base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = env.palette.fg, letterSpacing = 0.02.em)

    /** The bail: a dark bar across the machine, a column scale along its lower edge, and the carriage pointer. */
    private fun DrawScope.bail(env: LookEnv) {
        val side = gutter.toPx()
        val wide = size.width + side * 2f
        val above = 400.dp.toPx()
        val top = Color(0xFF131210)
        // it runs up behind the phone's own icons
        drawRect(top, Offset(-side, -above), Size(wide, above))
        drawRect(
            Brush.verticalGradient(0f to top, 0.62f to Color(0xFF23201D), 1f to Color(0xFF2C2925), startY = 0f, endY = size.height),
            Offset(-side, 0f), Size(wide, size.height),
        )
        val edge = 1.dp.toPx()
        drawRect(Color(0xFF7D7667), Offset(-side, size.height - edge), Size(wide, edge))
        val shade = 9.dp.toPx()
        drawRect(
            Brush.verticalGradient(0f to Color(0x802C1C08), 1f to Color(0x002C1C08), startY = size.height, endY = size.height + shade),
            Offset(-side, size.height), Size(wide, shade),
        )

        // a tick for every column, a longer one every fifth, numbered every tenth
        val cell = env.cell.width
        val columns = (size.width / cell + 0.01f).toInt()
        val mark = Cream.copy(alpha = 0.78f)
        val foot = size.height - edge
        val thick = 1.dp.toPx()
        val numeral = TextStyle(fontFamily = Courier, fontSize = 7.sp, color = mark)
        for (i in 0 until columns) {
            val x = (i + 0.5f) * cell
            val tall = if ((i + 1) % 5 == 0) 7.dp.toPx() else 4.dp.toPx()
            drawRect(mark, Offset(x - thick / 2f, foot - tall), Size(thick, tall))
            if ((i + 1) % 10 == 0) {
                val laid = env.measurer.measure("${i + 1}", numeral)
                drawText(laid, topLeft = Offset(x - laid.size.width / 2f, foot - 8.dp.toPx() - laid.lastBaseline))
            }
        }

        // the carriage, home at the left margin
        val drop = 7.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(0f, size.height)
                lineTo(cell, size.height)
                lineTo(cell / 2f, size.height + drop)
                close()
            },
            env.palette.accent,
        )
    }

    /** No room of its own: only the tear bar, drawn behind where the chips stand. */
    override fun Modifier.frame(env: LookEnv): Modifier = drawBehind { tearBar() }

    private fun DrawScope.tearBar() {
        val top = size.height - KEYS.toPx()
        val below = 400.dp.toPx()
        drawRect(
            Brush.verticalGradient(0f to Color(0xFF2D2A26), 0.4f to Color(0xFF1C1A17), 1f to Color(0xFF100F0D), startY = top, endY = size.height),
            Offset(0f, top), Size(size.width, KEYS.toPx()),
        )
        drawRect(Color(0xFF100F0D), Offset(0f, size.height), Size(size.width, below))
        // ribbed like a grip
        val rib = 6.dp.toPx()
        var y = top + rib
        while (y < size.height) {
            drawRect(Color.Black, Offset(0f, y - 1.dp.toPx()), Size(size.width, 1.dp.toPx()), alpha = 0.28f)
            y += rib
        }
        drawRect(Brush.verticalGradient(0f to Color(0x17FFF0D2), 1f to Color(0x00FFF0D2), startY = top, endY = top + 7.dp.toPx()), Offset(0f, top), Size(size.width, 7.dp.toPx()))
        drawRect(Color(0xFF6E685C), Offset(0f, top), Size(size.width, 1.dp.toPx()))

        // the saw the paper is torn against
        val tooth = 8.dp.toPx()
        val tall = TEETH.toPx()
        val lit = Path()
        val dark = Path()
        var x = 0f
        while (x < size.width) {
            lit.moveTo(x, top)
            lit.lineTo(x + tooth / 2f, top - tall)
            lit.lineTo(x + tooth, top)
            lit.close()
            dark.moveTo(x + tooth / 2f, top - tall)
            dark.lineTo(x + tooth, top)
            dark.lineTo(x + tooth * 0.7f, top)
            dark.close()
            x += tooth
        }
        drawPath(lit, Color(0xFFA59F91))
        drawPath(dark, Color(0xFF605B51))
    }

    /** Keys: black for an app, cream for a word, red for the ones that act. */
    override fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .drawBehind {
            val p = env.palette
            val (crown, skirt, ring) = when (color) {
                p.accent -> Triple(Color(0xFFCF3B30), Color(0xFF8C1B15), Color(0xFFA39C8B))
                p.fg -> Triple(Color(0xFFFBF5E6), Color(0xFFD6CCB3), Color(0xFF8A8372))
                else -> Triple(Color(0xFF3A3632), Color(0xFF191715), Color(0xFF948D7C))
            }
            val round = CornerRadius(size.height / 2f)
            val line = 1.2.dp.toPx()
            drawRoundRect(Color.Black, Offset(0f, 2.5.dp.toPx()), size, round, alpha = 0.55f)
            drawRoundRect(Brush.verticalGradient(0f to crown, 0.75f to skirt, 1f to skirt), cornerRadius = round)
            // light catches the top of the key, and its foot is in its own shade
            drawRoundRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = if (color == p.fg) 0.7f else 0.2f), 0.22f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f)),
                cornerRadius = round,
            )
            drawRoundRect(ring, Offset(line / 2f, line / 2f), Size(size.width - line, size.height - line), round, style = Stroke(line))
        }
        .padding(horizontal = 13.dp, vertical = 4.dp)

    override fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(
        fontSize = 13.sp,
        color = when (color) {
            env.palette.fg -> Color(0xFF26221E)
            env.palette.accent -> Color(0xFFFFF3E0)
            env.palette.dim -> Color(0xFFA79E89)
            else -> Cream
        },
    )

    /* ---------------- on the paper ---------------- */

    /**
     * Typewriter art: every cell of the clock is two columns of M struck over with W, twice, half a
     * line apart, on the ink that soaked in under all those strikes.
     */
    @Composable
    override fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val cells = remember(time) { clockCells(time) }
        val seed = time.hashCode()
        val density = LocalDensity.current
        val fit = with(density) { minOf(1f, width.toPx() / (env.cell.width * 2f * CLOCK_COLUMNS)) }
        val wide = with(density) { (env.cell.width * 2f * fit).toDp() }
        val tall = with(density) { (em(env) * 1.15f * fit).toDp() }
        Canvas(Modifier.size(wide * CLOCK_COLUMNS, tall * CLOCK_ROWS)) {
            val w = size.width / CLOCK_COLUMNS
            val h = size.height / CLOCK_ROWS
            val em = h / 1.15f
            val face = env.base.copy(fontSize = em.toSp(), fontWeight = FontWeight.Bold, color = env.palette.fg)
            val m = env.measurer.measure("MM", face)
            val v = env.measurer.measure("WW", face)
            val cap = em * 0.6f
            val soak = Path()
            cells.forEachIndexed { i, on ->
                if (!on) return@forEachIndexed
                val x = (i % CLOCK_COLUMNS) * w
                val y = (i / CLOCK_COLUMNS) * h
                fun bleed(side: Int) = 0.01f + 0.045f * dice(i * 4 + side, seed)
                soak.addRect(Rect(x - w * bleed(0), y - h * bleed(1), x + w + w * bleed(2), y + h + h * bleed(3)))
            }
            drawPath(soak, env.palette.fg, alpha = 0.5f)
            cells.forEachIndexed { i, on ->
                if (!on) return@forEachIndexed
                val x = (i % CLOCK_COLUMNS) * w
                val y = (i / CLOCK_COLUMNS) * h
                for (half in 0..1) {
                    // no two strikes land quite alike
                    val top = y + half * h / 2f + (h / 2f - cap) / 2f - (m.firstBaseline - cap)
                    drawText(
                        m,
                        topLeft = Offset(x + if (i % 7 == 2) em * 0.03f else 0f, top - if (i % 7 == 2) em * 0.035f else 0f),
                        alpha = if (i % 3 == 0) 0.8f else 1f,
                    )
                    drawText(
                        v,
                        topLeft = Offset(x - if (i % 5 == 3) em * 0.03f else 0f, top + if (i % 4 == 1) em * 0.03f else 0f),
                        alpha = if (i % 4 == 1) 0.72f else 1f,
                    )
                }
            }
        }
    }

    /** The date is ruled in red: the other half of the ribbon. One brush inks the letters and the rule under them. */
    override fun lockStyle(line: LockLine, env: LookEnv): TextStyle = when (line) {
        LockLine.DATE -> {
            val laid = env.measurer.measure("0", env.base)
            val tall = laid.size.height.toFloat()
            val rule = ((laid.firstBaseline + em(env) * 0.07f) / tall).coerceIn(0f, 1f)
            env.base.copy(
                brush = Brush.verticalGradient(0f to env.palette.fg, rule to env.palette.fg, rule to env.palette.accent, 1f to env.palette.accent, startY = 0f, endY = tall),
                textDecoration = TextDecoration.Underline,
            )
        }
        else -> env.base.copy(color = env.palette.dim)
    }

    override val gaugeSize: DpSize get() = DpSize(110.dp, 15.dp)

    /** The same overstrike for what is used, typed blanks for what is left. */
    override fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val p = env.palette
        val cells = 14
        val w = size.width / cells
        val em = w / advance
        val ink = if (alert) p.err else p.fg
        val face = env.base.copy(fontSize = em.toSp(), fontWeight = FontWeight.Bold, color = ink)
        val m = env.measurer.measure("M", face)
        val v = env.measurer.measure("W", face)
        val cap = em * 0.6f
        val base = (size.height + cap) / 2f
        val filled = (shown * cells + 0.5f).toInt()
        for (i in 0 until cells) {
            val x = i * w
            if (i >= filled) {
                drawRect(p.dim, Offset(x + w * 0.1f, base + 1.dp.toPx()), Size(w * 0.8f, 1.2.dp.toPx()), alpha = 0.55f)
                continue
            }
            val top = base - m.firstBaseline
            drawText(m, topLeft = Offset(x, top + if (i % 4 == 2) em * 0.04f else 0f))
            drawText(v, topLeft = Offset(x, top), alpha = if (i % 3 == 0) 0.7f else 1f)
            // a heavy key now and then, and the one just struck is still wet
            if (i % 5 == 1 || (i == filled - 1 && shown < target)) drawText(m, topLeft = Offset(x + em * 0.04f, top))
        }
    }

    /** A slip torn off a pad and pinned to the page. */
    override fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(top = 12.dp, bottom = 12.dp)
        .wrapContentWidth(Alignment.Start)
        .graphicsLayer {
            rotationZ = -1.3f
            translationX = env.cell.width
            transformOrigin = TransformOrigin(0.5f, 0.15f)
        }
        .drawBehind { slip(faded(), env) }
        .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp)

    private fun DrawScope.slip(faded: Float, env: LookEnv) {
        val em = em(env)
        val steps = 17
        val torn = Path().apply {
            moveTo(0f, em * 0.22f * dice(0, 31))
            for (i in 1..steps) lineTo(size.width * i / steps, em * 0.24f * dice(i, 31))
            for (i in steps downTo 0) lineTo(size.width * i / steps, size.height - em * 0.22f * dice(i, 32))
            close()
        }
        // it stands a little off the page
        for (i in 1..4) translate(top = i * 1.3.dp.toPx()) { drawPath(torn, Color(0xFF321F0C), alpha = 0.07f) }
        drawPath(torn, Brush.verticalGradient(listOf(lerp(Color(0xFFFFFAF0), Color(0xFFF6EEDC), faded), lerp(Color(0xFFF8F1E0), Color(0xFFF1E8D3), faded))))
        // the pin
        val r = em * 0.45f
        val at = Offset(size.width / 2f, em * 0.25f)
        drawCircle(Color(0xFF281406), r, at + Offset(em * 0.14f, em * 0.3f), alpha = 0.32f)
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFFFB3A6), 0.26f to Color(0xFFD43A2F), 0.62f to Color(0xFFA81F18), 1f to Color(0xFF6F100C),
                center = at + Offset(-r * 0.28f, -r * 0.4f), radius = r * 1.5f,
            ),
            r, at,
        )
    }

    /** The ribbon waits where the next key will strike. */
    override fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.line)
        blinkOn -> SpanStyle(background = palette.accent.copy(alpha = 0.92f), color = palette.bg)
        else -> SpanStyle(background = palette.accent.copy(alpha = 0.28f))
    }

    /* ---------------- movement ---------------- */

    /** A value that runs through [stops] (where, what) as [p] goes from 0 to 1, straight from each to the next. */
    private fun keyed(p: Float, vararg stops: Pair<Float, Float>): Float {
        if (p <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (at, value) = stops[i]
            if (p <= at) {
                val (from, was) = stops[i - 1]
                return was + (value - was) * (p - from) / (at - from)
            }
        }
        return stops.last().second
    }

    override val lineMs get() = 220

    /** Struck a key at a time, left to right. The last one is still wet: it stands a little heavy. */
    override fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val cell = env.cell.width
        val columns = (size.width / cell).toInt() + 1
        val struck = (p * columns).toInt()
        if (struck <= 0) return@drawWithContent
        val head = struck * cell
        clipRect(right = head) { this@drawWithContent.drawContent() }
        val em = em(env)
        clipRect(left = head - cell, right = head + cell * 0.25f) {
            translate(left = em * 0.04f, top = em * 0.045f) { this@drawWithContent.drawContent() }
            translate(left = -em * 0.025f) { this@drawWithContent.drawContent() }
        }
    }

    /** The lamp: how dark it still is, from 1 to 0, and its glare on the paper as it catches. */
    private fun DrawScope.lamp(dark: Float, glare: Float) {
        if (dark > 0f) {
            pool(
                Offset(size.width / 2f, 0f), size.width * 1.2f, size.height * 0.7f,
                0f to Color(0xFF2A180A), 0.75f to Color(0xFF0E0804), 1f to Color(0xFF0E0804),
                alpha = dark, blend = BlendMode.Multiply,
            )
        }
        if (glare > 0f) {
            pool(
                Offset(size.width / 2f, size.height * 0.04f), size.width * 0.95f, size.height * 0.46f,
                0f to Color(0xBFFFE8B4), 1f to Color(0x00FFE8B4),
                alpha = glare,
            )
        }
    }

    /**
     * The lamp flickers on over the machine, and the platen is turned by hand, click by click, until
     * the page is up.
     */
    override val powerOn: Motion = object : Motion(ms = 880, readableMs = 560, backdrop = Machine) {
        private val clicks = 8

        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val turned = between(t, 190f, 810f) * clicks
            val whole = turned.toInt()
            val fed = whole + smooth(between(turned - whole, 0.25f, 0.85f))
            val down = (clicks - fed) * env.cell.height * 1.25f
            if (down > 0f) clipRect(bottom = down) { platen(down) }
            translate(top = down) { this@draw.drawContent() }
            val p = t / 580f
            lamp(
                dark = keyed(p, 0f to 1f, 0.12f to 1f, 0.17f to 0.42f, 0.25f to 0.9f, 0.4f to 0.25f, 0.5f to 0.36f, 1f to 0f),
                glare = keyed(t / 680f, 0f to 0f, 0.12f to 0f, 0.17f to 0.7f, 0.25f to 0.05f, 0.4f to 0.8f, 0.5f to 0.5f, 1f to 0f),
            )
            val black = 1f - between(t, 0f, 100f)
            if (black > 0f) drawRect(Night, alpha = black)
        }
    }

    /** The paper feeds out past its end, red stripe and all, and the lamp goes off. */
    override val powerOff: Motion = object : Motion(ms = 900, backdrop = Night) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            if (t >= ms) {
                drawRect(Night)
                return
            }
            val bar = size.height - env.keyboard - KEYS.toPx() - TEETH.toPx()
            val end = bar + 34.dp.toPx()
            val out = between(t, 80f, 540f)
            val up = out * out * (end + 20.dp.toPx())
            platen(bar)
            clipRect(bottom = end - up) {
                translate(top = -up) {
                    this@draw.drawContent()
                    // where the bar was the paper runs on to its end, with the stripe that warns of it
                    drawRect(Sheet.bg, Offset(0f, bar), Size(size.width, end - bar))
                    clipRect(top = bar, bottom = end) { tooth() }
                    feedHoles(env, bar, end)
                    drawRect(Sheet.accent, Offset(0f, end - 25.dp.toPx()), Size(size.width, 11.dp.toPx()), alpha = 0.42f)
                }
            }
            // the bar itself stays where it is
            clipRect(top = bar) { this@draw.drawContent() }
            lamp(dark = keyed(between(t, 540f, 820f), 0f to 0f, 0.16f to 0.7f, 0.3f to 0.45f, 0.55f to 0.86f, 1f to 1f), glare = 0f)
            val black = between(t, 790f, 890f)
            if (black > 0f) drawRect(Night, alpha = black)
        }
    }

    override val grantedWords get() = false

    private val StampRed = Color(0xFFB1271F)

    /** Stamp ink sinks into the paper and darkens what is under it. */
    private val Inked: Paint by lazy {
        Paint().apply {
            alpha = 0.9f
            blendMode = BlendMode.Multiply
        }
    }

    /** When the stamp lands, and when the page starts to feed away. */
    private const val STAMP_HIT = 190f
    private const val STAMP_FEED = 640f

    /**
     * A rubber stamp comes down across the lock page, lifts off its ink, and the page is fed away
     * upward under the bail.
     */
    override val granted: Motion = object : Motion(ms = 1040) {
        override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
            val pitch = env.cell.height
            val fed = between(t, STAMP_FEED, ms.toFloat())
            val away = fed * fed * (size.height + pitch * 2f)
            // the carriage takes the knock
            val knock = if (t < STAMP_HIT) 0f else 1f - easeOut(between(t, STAMP_HIT, STAMP_HIT + 190f))
            translate(left = -1.9.dp.toPx() * knock, top = 4.6.dp.toPx() * knock - away) {
                this@draw.drawContent()
                // the fold between this page and the next comes up after it
                drawLine(
                    Color(0x805C482C), Offset(0f, size.height + pitch), Offset(size.width, size.height + pitch),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(pitch / 6f, pitch / 6f)),
                )
                stamp(t, env)
            }
        }
    }

    private fun DrawScope.stamp(t: Float, env: LookEnv) {
        val unit = size.width / 87f
        val seen = (size.height - env.keyboard).coerceAtLeast(size.height * 0.4f)
        val at = Offset(size.width * 0.54f, minOf(seen * 0.42f, 44f * unit))
        val type = 11.4f * unit
        val face = TextStyle(fontFamily = Elite, fontSize = type.toSp(), color = StampRed, letterSpacing = 0.04.em)
        val first = env.measurer.measure("ACCESS", face)
        val second = env.measurer.measure("GRANTED", face)
        val lead = type * 1.04f
        val cap = type * 0.66f
        val wide = maxOf(first.size.width, second.size.width) + 9.2f * unit
        val tall = lead * 2f + 4.4f * unit
        val box = Rect(-wide / 2f, -tall / 2f, wide / 2f, tall / 2f)

        // down hard, a little past, and back
        val grow = when {
            t < STAMP_HIT -> 1.9f - 0.95f * easeIn(t / STAMP_HIT)
            t < STAMP_HIT + 80f -> 0.95f + 0.065f * easeOut((t - STAMP_HIT) / 80f)
            else -> 1.015f - 0.015f * between(t, STAMP_HIT + 80f, STAMP_HIT + 170f)
        }
        val tilt = -14f + 7f * easeIn(between(t, 0f, STAMP_HIT))
        withTransform({
            translate(at.x, at.y)
            rotate(tilt, Offset.Zero)
            scale(grow, grow, Offset.Zero)
        }) {
            // the ink spreads into the paper as the rubber lifts
            val mist = keyed(between(t, STAMP_HIT, STAMP_HIT + 450f), 0f to 0f, 0.14f to 0.9f, 1f to 0f)
            if (mist > 0f) {
                val spread = 0.7f + 0.42f * between(t, STAMP_HIT, STAMP_HIT + 450f)
                scale(spread, spread, Offset.Zero) {
                    val rx = wide / 2f + 6f * unit
                    val ry = tall / 2f + 6f * unit
                    scale(1f, ry / rx, Offset.Zero) {
                        drawCircle(Brush.radialGradient(0f to StampRed.copy(alpha = 0.34f), 1f to StampRed.copy(alpha = 0f), center = Offset.Zero, radius = rx), rx, Offset.Zero, alpha = mist)
                    }
                }
            }
            if (t >= STAMP_HIT) {
                drawIntoCanvas { canvas ->
                    val room = 4f * unit
                    canvas.saveLayer(box.inflate(room), Inked)
                    val line = 0.85f * unit
                    drawRoundRect(StampRed, box.topLeft + Offset(line / 2f, line / 2f), Size(wide - line, tall - line), CornerRadius(1.7f * unit), style = Stroke(line))
                    val gap = 0.9f * unit
                    drawRoundRect(StampRed, box.topLeft - Offset(gap, gap), Size(wide + gap * 2f, tall + gap * 2f), CornerRadius(2.4f * unit), style = Stroke(0.3f * unit))
                    val middle = 0.7f * unit
                    drawText(first, topLeft = Offset(-first.size.width / 2f, middle - lead / 2f + cap / 2f - first.firstBaseline))
                    drawText(second, topLeft = Offset(-second.size.width / 2f, middle + lead / 2f + cap / 2f - second.firstBaseline))
                    // the rubber did not take the ink evenly
                    drawRect(Blots, box.topLeft - Offset(room, room), Size(wide + room * 2f, tall + room * 2f), blendMode = BlendMode.DstOut)
                    canvas.restore()
                }
            }
            // the die itself, in the hand until it has printed
            val held = keyed(t / STAMP_HIT, 0f to 0f, 0.7f to 0.97f, 1f to 0.97f) * (1f - smooth(between(t, STAMP_HIT + 90f, STAMP_HIT + 320f)))
            if (held > 0f) {
                val lifted = smooth(between(t, STAMP_HIT + 90f, STAMP_HIT + 320f))
                translate(1.5f * unit * lifted, -5f * unit * lifted) {
                    scale(1f + 0.22f * lifted, 1f + 0.22f * lifted, Offset.Zero) {
                        val body = box.inflate(1.6f * unit)
                        drawRoundRect(Color(0xFF140802), body.topLeft + Offset(0f, 3f * unit), body.size, CornerRadius(2.6f * unit), alpha = 0.3f * held)
                        drawRoundRect(
                            Brush.linearGradient(0f to Color(0xFF74332A), 0.6f to Color(0xFF45170F), 1f to Color(0xFF2C0D08), start = body.topLeft, end = body.bottomRight),
                            body.topLeft, body.size, CornerRadius(2.6f * unit), alpha = held,
                        )
                    }
                }
            }
        }
    }

    override val launchMs get() = 600

    /**
     * The sheet is torn off just under the launch line, from the left, and whisked away. What it
     * leaves is the dark machine, which is where the app comes up.
     */
    override fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val lift = smooth(between(ms, 50f, 200f))
        if (lift <= 0f) return
        val gone = easeIn(between(ms, 190f, 450f))
        val tear = line.bottom + env.cell.height * 0.8f
        val turn = (3.2f * lift + 11.8f * gone) * PI.toFloat() / 180f
        val dx = 0.34f * size.width * gone
        val dy = -(0.004f * lift + 1.22f * gone) * size.height
        // the torn edge, and the same edge where the sheet has got to: it turns about its right end
        val edge = ArrayList<Offset>()
        var x = 0f
        var i = 0
        while (x < size.width) {
            edge += Offset(x, tear + (dice(i, 23) - 0.5f) * size.height * 0.008f * (if (dice(i, 24) < 0.22f) 2.4f else 1f))
            x += size.width * (0.014f + 0.034f * dice(i, 25))
            i++
        }
        edge += Offset(size.width, tear + (dice(i, 23) - 0.5f) * size.height * 0.008f)
        fun moved(p: Offset): Offset {
            val rx = p.x - size.width
            val ry = p.y - tear
            return Offset(size.width + rx * cos(turn) - ry * sin(turn) + dx, tear + rx * sin(turn) + ry * cos(turn) + dy)
        }
        val far = -size.height * 3f
        fun sheet(place: (Offset) -> Offset) = Path().apply {
            val start = place(Offset(0f, far))
            moveTo(start.x, start.y)
            val corner = place(Offset(size.width, far))
            lineTo(corner.x, corner.y)
            for (p in edge.asReversed()) place(p).let { lineTo(it.x, it.y) }
            close()
        }
        val was = sheet { it }
        val now = sheet(::moved)
        clipPath(was) {
            clipPath(now, ClipOp.Difference) { platen(tear) }
            // the sheet in flight is too quick to read
            clipPath(now) { drawRect(Sheet.bg, alpha = 0.85f * between(ms, 190f, 300f)) }
            // its torn edge catches the lamp, and throws a shade on the roller
            val rim = Path().apply { edge.forEachIndexed { n, p -> moved(p).let { if (n == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
            translate(top = 3.dp.toPx()) { drawPath(rim, Color.Black, alpha = 0.35f, style = Stroke(5.dp.toPx())) }
            drawPath(rim, Color(0xFFFFFAF0), style = Stroke(1.5.dp.toPx()))
        }
        val dark = between(ms, 400f, 570f)
        if (dark > 0f) drawRect(Color(0xFF101216), alpha = dark)
    }
}
