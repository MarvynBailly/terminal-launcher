package com.terminallauncher.ui.looks

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.Style
import com.terminallauncher.ui.CLOCK_COLUMNS
import com.terminallauncher.ui.CLOCK_GAP
import com.terminallauncher.ui.CLOCK_ROWS
import com.terminallauncher.ui.Palette
import com.terminallauncher.ui.clockCells
import com.terminallauncher.ui.drawPixelClock

/** The face the launcher has always had, and the one a look gets if it names no other. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** What a look is told when it draws. */
class LookEnv(
    val palette: Palette,
    /** One character of the terminal's text, in pixels: its advance and the height of a line. */
    val cell: Size,
    /** Draws text on a canvas, for the few things that are drawn and not laid out. */
    val measurer: TextMeasurer,
    /** The terminal's text style, the look's own shaping applied. */
    val base: TextStyle,
    /** true while the lock page is what shows. */
    val locked: Boolean,
    /**
     * Milliseconds that run on while the terminal is on screen, for looks that move at rest (see
     * [Look.moves]). Read it only while drawing, so each frame redraws and nothing is laid out again.
     * It stays 0 when animations are off.
     */
    val time: () -> Float,
    /** How much of the screen's bottom the keyboard covers, in pixels. */
    val keyboard: Float,
)

/** The three lines of words on the lock page: "phone login · tty1", the date, and what is waiting. */
enum class LockLine { TITLE, DATE, INFO }

/**
 * One movement of a whole page, as plain functions of the time since it began. Nothing here keeps
 * state, so any moment of it can be drawn by itself.
 */
open class Motion(
    /** How long it lasts. */
    val ms: Int,
    /** When the page it leads to is far enough along to start printing its lines. */
    val readableMs: Int = ms,
    /** What shows behind a page that has been moved or shrunk away. */
    val backdrop: Color = Color.Black,
) {
    /** Bends, moves or fades the page as a whole. */
    open fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {}

    /**
     * Draws the page at [t]. `drawContent()` draws the page as it is, and may be called more than
     * once, inside clips and translations, or not at all. Anything drawn after it lies over the page.
     */
    open fun ContentDrawScope.draw(t: Float, env: LookEnv) = drawContent()
}

/**
 * A look: how the terminal is drawn and how it moves. The shell decides what is on screen, and a
 * look never changes that. Every member has a default, which is the plain terminal, so a look
 * overrides only what makes it itself.
 *
 * Almost everything is a plain function of what it is given (a time, a progress, a size). That is
 * what lets any moment of any look be drawn off the phone and looked at. Keep it so: no state
 * that outlives a call, apart from caches of things that are costly to make (a noise bitmap).
 */
abstract class Look {
    /** The name the user types: `look phosphor`. One of Shell.LOOKS. */
    abstract val id: String

    /** The colors. Only the stock look listens to [theme]; the others are their own palette. */
    abstract fun palette(theme: String): Palette

    /* ---------------- type ---------------- */

    open val font: FontFamily get() = PlexMono
    open val fontSize: TextUnit get() = 15.sp
    open val lineHeight: TextUnit get() = 22.sp

    /** How wide one character is, as a share of [fontSize]. It decides how many columns the shell is told. */
    open val advance: Float get() = 0.6f

    /** The margin left and right of the text. */
    open val gutter: Dp get() = 16.dp

    /** The look's own shaping of the terminal's text style: a weight, letter spacing, a glow. */
    open fun type(base: TextStyle, palette: Palette): TextStyle = base

    /** How one of the shell's text styles is drawn. */
    open fun span(style: Style, palette: Palette): SpanStyle =
        if (style == Style.MARK) SpanStyle(color = palette.bg, background = palette.accent) else SpanStyle(color = palette.of(style))

    /* ---------------- layers ---------------- */

    /** true if [background] or [overlay] change from frame to frame at rest. The screen then redraws them continuously. */
    open val moves: Boolean get() = false

    /** Drawn behind everything, over the whole screen, on top of the palette's background color. */
    open fun DrawScope.background(env: LookEnv) {}

    /** Drawn over everything, over the whole screen: the glass in front of the picture. */
    open fun DrawScope.overlay(env: LookEnv) {}

    /* ---------------- parts ---------------- */

    /**
     * Around everything between the screen's safe edges, outside the gutters: room at the top or
     * the bottom for a look that frames the page or hangs it from something.
     */
    open fun Modifier.frame(env: LookEnv): Modifier = this

    /** The status line at the top: what is behind and around it. The default is only its padding. */
    open fun Modifier.status(env: LookEnv): Modifier = padding(top = 8.dp, bottom = 6.dp)
    open fun statusStyle(env: LookEnv): TextStyle = env.base.copy(fontSize = 13.sp, color = env.palette.dim)

    /** The words of the status line as they are shown, for a look that sets them in capitals. */
    open fun statusText(text: String): String = text

    /**
     * The lock page's clock, [width] wide at most. [leaving] is null at rest; while a right password
     * takes the lock page away it gives the milliseconds since that began, for a clock that has its
     * own way of going.
     */
    @Composable
    open fun Clock(time: String, width: Dp, env: LookEnv, leaving: (() -> Float)?) {
        val cells = remember(time) { clockCells(time) }
        val cell = minOf(width * 0.035f, 14.dp)
        val delays = remember { FloatArray(CLOCK_COLUMNS * CLOCK_ROWS) { Math.random().toFloat() * 380f } }
        Canvas(Modifier.size(width = cell * CLOCK_COLUMNS + cell * CLOCK_GAP * (CLOCK_COLUMNS - 1), height = cell * CLOCK_ROWS + cell * CLOCK_GAP * (CLOCK_ROWS - 1))) {
            val ms = leaving?.invoke()
            // the cells go dark one by one
            drawPixelClock(cells, Offset.Zero, cell.toPx(), env.palette.fg) { i ->
                if (ms == null) 1f else 1f - ((ms - 200f - delays[i]) / 160f).coerceIn(0f, 1f)
            }
        }
    }

    /** The words of the lock page around the clock: how each line is set, and its text as shown. */
    open fun lockStyle(line: LockLine, env: LookEnv): TextStyle =
        if (line == LockLine.DATE) env.base else env.base.copy(color = env.palette.dim)
    open fun lockText(line: LockLine, text: String): String = text

    /** The box a gauge's bar is drawn in. Its width is the same in every look so the notes after it line up. */
    open val gaugeSize: DpSize get() = DpSize(110.dp, 11.dp)

    /**
     * A gauge's bar, filling the canvas it is given. [shown] is how much of it is filled right now,
     * from 0 to 1: it runs up to [target] as the gauge arrives.
     */
    open fun DrawScope.gauge(shown: Float, target: Float, alert: Boolean, env: LookEnv) {
        val cells = 14
        val gap = size.width * 2f / 110f
        val width = (size.width - gap * (cells - 1)) / cells
        val filled = (shown * cells + 0.5f).toInt()
        for (i in 0 until cells) {
            drawRect(
                color = if (i >= filled) env.palette.line else if (alert) env.palette.err else env.palette.fg,
                topLeft = Offset(i * (width + gap), 0f),
                size = Size(width, size.height),
            )
        }
    }

    /** Around a line that can be tapped, such as the weather in the login summary. Its padding is added inside this. */
    open fun Modifier.tapRow(env: LookEnv): Modifier = this

    /** Around a line that repeats what was typed at the prompt. */
    open fun Modifier.echoRow(env: LookEnv): Modifier = this

    /**
     * Around one row of app names in `ls`. [first] and [last] say where the row is in the list,
     * for a look that draws a frame around the whole list.
     */
    open fun Modifier.appRow(first: Boolean, last: Boolean, env: LookEnv): Modifier = this

    /** Around one app's name in `ls`. [lit] is true while a finger is on it. */
    open fun Modifier.appCell(lit: Boolean, env: LookEnv): Modifier = this

    /**
     * Around a notification, padding included. [faded] runs from 0 to 1 as it stops being news;
     * read it only while drawing.
     */
    open fun Modifier.note(faded: () -> Float, env: LookEnv): Modifier = this
        .padding(top = 3.dp, bottom = 6.dp)
        .drawBehind { drawRect(lerp(env.palette.accent, env.palette.line, faded()), size = Size(2.dp.toPx(), size.height)) }
        .padding(start = 10.dp)

    /** Around a completion chip, padding included. [color] is the color of its text. */
    open fun Modifier.chip(color: Color, env: LookEnv): Modifier = this
        .border(1.dp, env.palette.line, RoundedCornerShape(5.dp))
        .padding(horizontal = 11.dp, vertical = 5.dp)

    /** The text of a chip. [color] says what kind it is: an app, a key, a plain word. */
    open fun chipStyle(color: Color, env: LookEnv): TextStyle = env.base.copy(color = color)

    /** The color of the prompt itself: `$ ` when it takes [commands], otherwise a question such as `password: `. */
    open fun promptColor(commands: Boolean, palette: Palette): Color = if (commands) palette.accent else palette.fg

    /** The block under the character the cursor is on. */
    open fun cursor(focused: Boolean, blinkOn: Boolean, palette: Palette): SpanStyle = when {
        !focused -> SpanStyle(background = palette.dim, color = palette.bg)
        blinkOn -> SpanStyle(background = palette.fg, color = palette.bg)
        else -> SpanStyle()
    }

    /* ---------------- movement ---------------- */

    /** How long a line takes to arrive. */
    open val lineMs: Int get() = 180

    /**
     * How a printed line arrives, [progress] running from 0 to 1 over [lineMs]. At 1 the line must
     * be exactly as it is at rest. Read [progress] only while drawing.
     */
    open fun Modifier.arriving(progress: () -> Float, env: LookEnv): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) drawContent()
        else if (p > 0f) clipRect(right = size.width * (p * 14).toInt() / 14) { this@drawWithContent.drawContent() }
    }

    /** The screen coming on, applied to the whole screen. */
    open val powerOn: Motion get() = TubeOn

    /** The screen going dark after `lock`, applied to the whole screen as it was. */
    open val powerOff: Motion get() = TubeOff

    /**
     * A right password: applied to the lock page, which lies over the home page until this is over.
     * The clock has its own part in it through [Clock]'s `leaving`.
     */
    open val granted: Motion get() = FadeOut

    /** Whether "access granted" resolves out of noise under the lock page while [granted] plays. */
    open val grantedWords: Boolean get() = true

    /**
     * An app being opened, drawn over the whole screen. [line] is where the launch line is. The app
     * itself fades in over this about 420 ms after the start, so by then the screen should be
     * somewhere an app can come from. It is drawn for [launchMs] at most.
     */
    open val launchMs: Int get() = 700
    open fun DrawScope.launch(ms: Float, line: Rect, name: String, env: LookEnv) {
        val grown = ((ms - 150f) / 340f).coerceIn(0f, 1f)
        if (grown <= 0f) return
        val eased = LaunchEase.transform(grown)
        val top = line.top * (1f - eased)
        val bottom = line.bottom + (size.height - line.bottom) * eased
        drawRect(env.palette.app, Offset(0f, top), Size(size.width, bottom - top))
        val alpha = ((ms - 330f) / 160f).coerceIn(0f, 1f)
        if (alpha > 0f) {
            val label = env.measurer.measure(name, env.base.copy(color = env.palette.bg, fontSize = 22.sp, fontWeight = FontWeight.Medium))
            drawText(label, color = env.palette.bg, topLeft = Offset((size.width - label.size.width) / 2f, (size.height - label.size.height) / 2f), alpha = alpha)
        }
    }
}

private val LaunchEase = CubicBezierEasing(0.3f, 0.8f, 0.2f, 1f)
private val TubeEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

/** The color of the line an old tube collapses into. */
val TubeGlow = Color(0xFFF2F5EF)

/** The thinnest the picture gets, as a share of the screen's height. */
private fun DrawScope.thin(): Float = 3.dp.toPx() / size.height
private fun GraphicsLayerScope.thin(): Float = 3.dp.toPx() / size.height

/** The line forms in the middle of what can be seen, which is the part above the keyboard. */
private fun GraphicsLayerScope.aboveKeyboard(env: LookEnv) {
    transformOrigin = TransformOrigin(0.5f, ((size.height - env.keyboard) / 2f / size.height).coerceIn(0.1f, 0.5f))
}

/** An old tube warming up: a bright line grows from the middle, then opens into the picture. */
object TubeOn : Motion(ms = 650, readableMs = 230) {
    private fun open(t: Float): Float = TubeEase.transform(((t / ms - 0.35f) / 0.65f).coerceIn(0f, 1f))

    override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
        val p = (t / ms).coerceIn(0f, 1f)
        aboveKeyboard(env)
        if (p < 0.35f) {
            scaleX = TubeEase.transform(p / 0.35f)
            scaleY = thin()
        } else {
            scaleY = thin() + (1f - thin()) * open(t)
        }
    }

    override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
        drawContent()
        val glow = if (t / ms < 0.35f) 1f else 1f - open(t)
        if (glow > 0f) drawRect(TubeGlow, alpha = glow)
    }
}

/** And switching off: the picture flattens into a bright line, and the line shrinks to a dot. */
object TubeOff : Motion(ms = 520) {
    override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
        val p = (t / ms).coerceIn(0f, 1f)
        aboveKeyboard(env)
        if (p < 0.55f) {
            scaleY = 1f - (1f - thin()) * EaseIn.transform(p / 0.55f)
        } else {
            scaleX = 1f - EaseIn.transform((p - 0.55f) / 0.45f)
            scaleY = thin()
        }
    }

    override fun ContentDrawScope.draw(t: Float, env: LookEnv) {
        drawContent()
        val p = (t / ms).coerceIn(0f, 1f)
        val glow = if (p < 0.55f) EaseIn.transform(p / 0.55f) else 1f
        if (glow > 0f) drawRect(TubeGlow, alpha = glow)
    }
}

/** The lock page lets go at the very end, whatever is left of it. */
object FadeOut : Motion(ms = 850) {
    override fun GraphicsLayerScope.shape(t: Float, env: LookEnv) {
        alpha = 1f - ((t - (ms - 150f)) / 150f).coerceIn(0f, 1f)
    }
}
