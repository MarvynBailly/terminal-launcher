package com.terminallauncher.ui.looks

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import kotlin.math.hypot

/*
 * Small tools the looks share. A look keeps no state, so anything random comes from dice(), which
 * always gives the same number for the same input, and anything costly to make is made once here.
 */

/** A number from 0 to 1 that is always the same for the same [i] and [salt]. */
fun dice(i: Int, salt: Int = 0): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) / 2147483647f
}

/** How far [t] is from [from] to [to], held between 0 and 1. */
fun between(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** [p] in [n] hard steps, for movement that jumps instead of gliding. */
fun stepped(p: Float, n: Int): Float = (p * n).toInt().coerceIn(0, n) / n.toFloat()

/** Slow start, slow stop. */
fun smooth(p: Float): Float {
    val x = p.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Fast start, gentle stop. */
fun easeOut(p: Float): Float {
    val x = 1f - p.coerceIn(0f, 1f)
    return 1f - x * x * x
}

/** Gentle start, fast stop. */
fun easeIn(p: Float): Float {
    val x = p.coerceIn(0f, 1f)
    return x * x * x
}

/** Past the end and back: a spring that settles at 1. */
fun overshoot(p: Float, by: Float = 1.6f): Float {
    val x = p.coerceIn(0f, 1f) - 1f
    return 1f + x * x * ((by + 1f) * x + by)
}

private val brushes = HashMap<String, ShaderBrush>()

/** A brush that repeats a small picture made once by [paint], which is handed a [w] by [h] grid of pixels to fill. */
fun tiled(key: String, w: Int, h: Int, paint: (pixels: IntArray) -> Unit): ShaderBrush = synchronized(brushes) {
    brushes.getOrPut(key) {
        val pixels = IntArray(w * h)
        paint(pixels)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
}

/**
 * Grain: every pixel [color] at a random strength between [lo] and [hi] (0 to 1). For paper, static
 * and anything that should not look flat. [salt] gives a different pattern.
 */
fun grain(color: Color, lo: Float, hi: Float, salt: Int = 0, size: Int = 96): ShaderBrush =
    tiled("grain ${color.toArgb()} $lo $hi $salt $size", size, size) { pixels ->
        val rgb = color.toArgb() and 0xFFFFFF
        for (i in pixels.indices) {
            val alpha = ((lo + (hi - lo) * dice(i, salt)) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or rgb
        }
    }

/** Horizontal lines [pitch] pixels apart, the lower [dark] of each in [color]: a raster, or ruled paper. */
fun DrawScope.scanlines(pitch: Int, color: Color, dark: Int = 1) {
    val p = pitch.coerceAtLeast(2)
    val brush = tiled("lines $p $dark ${color.toArgb()}", 1, p) { pixels ->
        for (i in pixels.indices) pixels[i] = if (i >= p - dark) color.toArgb() else 0
    }
    drawRect(brush)
}

/** Darkens toward the corners, as a curved tube or a lamp over a page does. [clear] is how far out it stays untouched. */
fun DrawScope.vignette(color: Color, strength: Float, clear: Float = 0.55f) {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            clear to Color.Transparent,
            1f to color.copy(alpha = strength),
            center = center,
            radius = hypot(size.width, size.height) / 2f,
        ),
    )
}

/** Fills the canvas with [brush], shifted by ([dx], [dy]): a tiled texture that jumps about from frame to frame. */
fun DrawScope.shifted(brush: Brush, dx: Float, dy: Float, alpha: Float = 1f) {
    translate(dx, dy) { drawRect(brush, topLeft = Offset(-dx, -dy), size = size, alpha = alpha) }
}

/** Draws [text] on the canvas with its top left at [at]. */
fun DrawScope.words(env: LookEnv, text: String, style: TextStyle, at: Offset, alpha: Float = 1f) {
    val laid = env.measurer.measure(text, style)
    drawText(laid, color = style.color, topLeft = at, alpha = alpha)
}

/** How much room [text] takes in [style]. */
fun words(env: LookEnv, text: String, style: TextStyle): Size {
    val laid = env.measurer.measure(text, style)
    return Size(laid.size.width.toFloat(), laid.size.height.toFloat())
}
