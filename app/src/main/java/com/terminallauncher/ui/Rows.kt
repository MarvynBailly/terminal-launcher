package com.terminallauncher.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.terminallauncher.shell.AppGrid
import com.terminallauncher.shell.Entrance
import com.terminallauncher.shell.Fetch
import com.terminallauncher.shell.Fmt
import com.terminallauncher.shell.Gauge
import com.terminallauncher.shell.Launching
import com.terminallauncher.shell.Line
import com.terminallauncher.shell.LockHeader
import com.terminallauncher.shell.Lockout
import com.terminallauncher.shell.MediaKey
import com.terminallauncher.shell.Note
import com.terminallauncher.shell.NowPlaying
import com.terminallauncher.shell.Phone
import com.terminallauncher.shell.Style
import com.terminallauncher.shell.TextLine
import com.terminallauncher.shell.Track
import com.terminallauncher.ui.looks.LockLine
import com.terminallauncher.ui.looks.Look
import com.terminallauncher.ui.looks.LookEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the scrolling list is made of. Every row knows its line's id; an [AppGrid] becomes several rows. */
internal sealed interface Row {
    val id: Long
    val part: Int get() = 0
}

internal data class TextRow(override val id: Long, val line: TextLine) : Row
internal data class GridRow(override val id: Long, override val part: Int, val cells: List<String>, val chars: Int, val count: Int) : Row
internal data class HeaderRow(override val id: Long) : Row
internal data class LockoutRow(override val id: Long, val until: Long) : Row
internal data class GaugeRow(override val id: Long, val gauge: Gauge) : Row
internal data class FetchRow(override val id: Long, val fetch: Fetch) : Row
internal data class LaunchRow(override val id: Long, val name: String) : Row
internal data class NoteRow(override val id: Long, val note: Note) : Row
internal data class PlayerRow(override val id: Long, val track: Track) : Row

/** Stable across recompositions and unique in the list. No grid has anywhere near 4096 rows. */
internal val Row.key: Long get() = id * 4096 + part

internal fun buildRows(lines: List<Line>, ids: List<Long>, columns: Int): List<Row> {
    val out = ArrayList<Row>(lines.size)
    lines.forEachIndexed { i, line ->
        val id = ids[i]
        when (line) {
            is TextLine -> out += TextRow(id, line)
            LockHeader -> out += HeaderRow(id)
            is Lockout -> out += LockoutRow(id, line.until)
            is Gauge -> out += GaugeRow(id, line)
            is Fetch -> out += FetchRow(id, line)
            is Launching -> out += LaunchRow(id, line.name)
            is Note -> out += NoteRow(id, line)
            is NowPlaying -> out += PlayerRow(id, line.track)
            is AppGrid -> {
                // Size the columns for a typical name, not the longest: one long name should not cost a column.
                val lengths = line.names.map { it.length }.sorted()
                val typical = lengths[(lengths.size * 0.85f).toInt().coerceAtMost(lengths.size - 1)]
                val count = (columns / (typical + 2).coerceAtLeast(10)).coerceIn(1, 4)
                val chars = columns / count
                val perColumn = (line.names.size + count - 1) / count
                // filled down each column, like `ls`
                for (r in 0 until perColumn) {
                    out += GridRow(id, r, (0 until count).mapNotNull { c -> line.names.getOrNull(c * perColumn + r) }, chars, perColumn)
                }
            }
        }
    }
    return out
}

/** Everything a row needs from the screen around it. */
internal class RowEnv(
    val palette: Palette,
    val base: TextStyle,
    val charWidth: Dp,
    val width: Dp,
    val phone: Phone,
    val clock: Long,
    /** When the line with this id went on screen; null if it has no entrance left to play. */
    val bornAt: (Long) -> Long?,
    val onRun: (String) -> Unit,
    val onFill: (String) -> Unit,
    val onBlank: () -> Unit,
    val onLaunch: (LaunchFx) -> Unit,
    /** Milliseconds into the lock screen's dissolve, while the page is being dissolved. */
    val dissolve: (() -> Float)? = null,
    /** How everything is drawn, and what the look is told when it draws. */
    val look: Look,
    val lookEnv: LookEnv,
    /** Holds every entrance this far through, from 0 to 1, in place of letting it play. For pictures. */
    val fixed: Float? = null,
)

@Composable
internal fun RowView(row: Row, env: RowEnv) {
    when (row) {
        is TextRow -> TextRowView(row, env)
        is GridRow -> GridRowView(row, env)
        is HeaderRow -> LockHeaderView(env)
        is LockoutRow -> {
            val left = ((row.until - env.clock + 999) / 1000).coerceAtLeast(0)
            BasicText("Too many attempts. Wait ${left}s", style = env.base.copy(color = env.palette.err))
        }
        is GaugeRow -> GaugeRowView(row, env)
        is FetchRow -> FetchRowView(row, env)
        is LaunchRow -> LaunchRowView(row, env)
        is NoteRow -> NoteRowView(row, env)
        is PlayerRow -> PlayerRowView(row, env)
    }
}

private fun styled(line: TextLine, look: Look, palette: Palette): AnnotatedString = buildAnnotatedString {
    for (span in line.spans) {
        val from = length
        withStyle(look.span(span.style, palette)) { append(span.text) }
        if (span.run != null) addStringAnnotation("run", span.run, from, length)
        if (span.fill != null) addStringAnnotation("fill", span.fill, from, length)
    }
    if (length == 0) append(" ")
}

/** Draws only the left part of the content: [progress] of its width, in [TYPE_STEPS] jumps. */
private fun Modifier.printed(progress: () -> Float): Modifier = drawWithContent {
    val p = progress()
    if (p >= 1f) drawContent()
    else if (p > 0f) clipRect(right = size.width * (p * TYPE_STEPS).toInt() / TYPE_STEPS) { this@drawWithContent.drawContent() }
}

@Composable
private fun TextRowView(row: TextRow, env: RowEnv) {
    val line = row.line
    val look = env.look
    val text = remember(line, env.palette, look) { styled(line, look, env.palette) }
    val tappable = line.spans.any { it.run != null || it.fill != null }
    val born = env.bornAt(row.id)
    val entrance = when (line.entrance) {
        Entrance.TYPE -> {
            val time = elapsedSince(born, look.lineMs, env.fixed)
            with(look) { Modifier.arriving({ time.value / look.lineMs }, env.lookEnv) }
        }
        Entrance.SHAKE -> {
            val time = elapsedSince(born, SHAKE_MS, env.fixed)
            Modifier.graphicsLayer { translationX = shakeAt(time.value / SHAKE_MS) * density }
        }
        Entrance.NONE -> Modifier
    }
    val around = with(look) {
        when {
            line.echo -> Modifier.echoRow(env.lookEnv)
            tappable -> Modifier.tapRow(env.lookEnv)
            else -> Modifier
        }
    }
    ClickableText(
        text = text,
        style = env.base,
        // a little more height where there is something to hit
        modifier = Modifier.fillMaxWidth().then(entrance).then(around).padding(vertical = if (tappable) 4.dp else 0.dp),
        onClick = { at ->
            val run = text.getStringAnnotations("run", at, at).firstOrNull()
            val fill = text.getStringAnnotations("fill", at, at).firstOrNull()
            when {
                run != null -> env.onRun(run.item)
                fill != null -> env.onFill(fill.item)
                else -> env.onBlank()
            }
        },
    )
}

/** How far apart in time the columns and rows of an app grid start, and how long a name takes to rise. */
private const val WAVE_COLUMN_MS = 110f
private const val WAVE_ROWS_MS = 450f
private const val WAVE_RISE_MS = 320f

@Composable
private fun GridRowView(row: GridRow, env: RowEnv) {
    val palette = env.palette
    val rowGap = minOf(45f, WAVE_ROWS_MS / row.count)
    val total = (3 * WAVE_COLUMN_MS + row.part * rowGap + WAVE_RISE_MS).toInt()
    val time = elapsedSince(env.bornAt(row.id), total, env.fixed)
    val run by rememberUpdatedState(env.onRun)
    Row(with(env.look) { Modifier.appRow(row.part == 0, row.part == row.count - 1, env.lookEnv) }) {
        row.cells.forEachIndexed { column, name ->
            // lights up under the finger and stays lit for a moment, so the tap is seen before the app opens
            var lit by remember(name) { mutableStateOf(false) }
            BasicText(
                text = name,
                style = env.base.copy(color = if (lit) palette.bg else palette.app),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(env.charWidth * row.chars)
                    .graphicsLayer {
                        val p = ((time.value - column * WAVE_COLUMN_MS - row.part * rowGap) / WAVE_RISE_MS).coerceIn(0f, 1f)
                        val eased = 1f - (1f - p) * (1f - p)
                        alpha = eased
                        translationY = (1f - eased) * 5.dp.toPx()
                    }
                    .pointerInput(name) {
                        detectTapGestures(
                            onPress = {
                                lit = true
                                if (tryAwaitRelease()) delay(180)
                                lit = false
                            },
                            onTap = { run("open $name") },
                        )
                    }
                    .semantics {
                        role = Role.Button
                        onClick { run("open $name"); true }
                    }
                    .then(with(env.look) { Modifier.appCell(lit, env.lookEnv) })
                    .padding(top = 6.dp, bottom = 6.dp, end = env.charWidth * 2)
                    .background(if (lit) palette.app else Color.Transparent),
            )
        }
    }
}

@Composable
private fun LockHeaderView(env: RowEnv) {
    val palette = env.palette
    val base = env.base
    val time = Fmt.hhmm(env.clock)
    val phone = env.phone
    val summary = remember(env.clock / 1000) {
        val count = phone.notifications()?.size
        val battery = "battery ${phone.battery().percent}%"
        when (count) {
            null -> battery
            0 -> "no notifications · $battery"
            1 -> "1 notification · $battery"
            else -> "$count notifications · $battery"
        }
    }
    Column {
        val look = env.look
        BasicText(look.lockText(LockLine.TITLE, "phone login · tty1"), style = look.lockStyle(LockLine.TITLE, env.lookEnv))
        Spacer(Modifier.height(22.dp))
        look.Clock(time, env.width, env.lookEnv, env.dissolve)
        Spacer(Modifier.height(16.dp))
        BasicText(look.lockText(LockLine.DATE, Fmt.day(env.clock)), style = look.lockStyle(LockLine.DATE, env.lookEnv))
        BasicText(look.lockText(LockLine.INFO, summary), style = look.lockStyle(LockLine.INFO, env.lookEnv))
        BasicText(" ", style = base)
    }
}

/** A full bar takes this long to fill. */
private const val GAUGE_FILL_MS = 420f

@Composable
private fun GaugeRowView(row: GaugeRow, env: RowEnv) {
    val palette = env.palette
    val gauge = row.gauge
    val look = env.look
    val target = gauge.percent.coerceIn(0, 100) / 100f
    val time = elapsedSince(env.bornAt(row.id), (GAUGE_FILL_MS + 120).toInt(), env.fixed)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(gauge.label.padEnd(6), style = env.base.copy(color = palette.dim))
        Canvas(Modifier.size(look.gaugeSize)) {
            // the bar runs up to its value, and the note follows once it is there
            with(look) { gauge(minOf(target, time.value / GAUGE_FILL_MS), target, gauge.alert, env.lookEnv) }
        }
        BasicText(
            text = " " + gauge.note,
            style = env.base.copy(color = if (gauge.alert) palette.err else palette.dim),
            modifier = Modifier.graphicsLayer { alpha = if (time.value >= minOf(GAUGE_FILL_MS, target * GAUGE_FILL_MS + 30f)) 1f else 0f },
        )
    }
}

/** A prompt chevron and an underscore, 9 cells by 7. 'a' cells take the accent color, 'd' the text color. */
private val LOGO = listOf(".........", ".a.......", "..a......", "...a.....", "..a......", ".a..dddd.", ".........")
private const val FETCH_ROW_MS = 70

@Composable
private fun FetchRowView(row: FetchRow, env: RowEnv) {
    val palette = env.palette
    val rows = row.fetch.rows
    val time = elapsedSince(env.bornAt(row.id), rows.size * FETCH_ROW_MS + TYPE_MS + 120, env.fixed)
    val cell = 9.dp
    Row(Modifier.padding(vertical = 4.dp)) {
        Canvas(Modifier.padding(top = 4.dp).size(width = cell * 9, height = cell * 7)) {
            val side = cell.toPx()
            LOGO.forEachIndexed { r, line ->
                line.forEachIndexed { c, ch ->
                    if (ch != '.') drawRect(if (ch == 'a') palette.accent else palette.fg, Offset(c * side, r * side), Size(side, side))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            rows.forEachIndexed { i, line ->
                val text = remember(line, palette, env.look) { styled(line, env.look, palette) }
                BasicText(
                    text = text,
                    style = env.base,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.printed { (time.value - i * FETCH_ROW_MS) / TYPE_MS },
                )
            }
            Row(
                Modifier.padding(top = 6.dp).graphicsLayer { alpha = if (time.value >= rows.size * FETCH_ROW_MS) 1f else 0f },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (color in listOf(palette.err, palette.accent, palette.ok, palette.app, palette.dim, palette.fg)) {
                    Spacer(Modifier.size(width = 14.dp, height = 9.dp).background(color))
                }
            }
        }
    }
}

private const val SPINNER = "|/-\\"

@Composable
private fun LaunchRowView(row: LaunchRow, env: RowEnv) {
    val palette = env.palette
    val born = env.bornAt(row.id)
    val time = elapsedSince(born, 700, env.fixed)
    val turn by remember { derivedStateOf { if (time.value >= 700f) -1 else (time.value / 70f).toInt() % SPINNER.length } }
    val onLaunch by rememberUpdatedState(env.onLaunch)
    var told by remember { mutableStateOf(false) }
    val text = buildAnnotatedString {
        if (turn < 0) {
            withStyle(SpanStyle(color = palette.dim)) { append("opening ${row.name}") }
        } else {
            withStyle(SpanStyle(color = palette.accent)) { append(SPINNER[turn]) }
            append(" exec ${row.name}")
        }
    }
    BasicText(
        text = text,
        style = env.base,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { where ->
            // Only a launch that is happening right now grows; one further up the scrollback is history.
            if (!told && born != null && SystemClock.uptimeMillis() - born < 400) {
                told = true
                onLaunch(LaunchFx(row.name, where.positionInRoot().y, where.size.height.toFloat(), born))
            }
        },
    )
}

/** A notification types itself at up to this pace, stays bright for [NOTE_HOLD_MS], then fades back. */
private const val NOTE_CHAR_MS = 28f
private const val NOTE_TYPE_MAX_MS = 1200f
private const val NOTE_HOLD_MS = 1700f
private const val NOTE_FADE_MS = 600f

@Composable
private fun NoteRowView(row: NoteRow, env: RowEnv) {
    val palette = env.palette
    val note = row.note
    val length = note.text.length
    val perChar = minOf(NOTE_CHAR_MS, NOTE_TYPE_MAX_MS / length.coerceAtLeast(1))
    val typeMs = length * perChar
    // Posed, a note is all there and still news.
    val time = elapsedSince(env.bornAt(row.id), (typeMs + NOTE_HOLD_MS + NOTE_FADE_MS).toInt(), env.fixed?.let { typeMs / (typeMs + NOTE_HOLD_MS + NOTE_FADE_MS) })
    val typed by remember { derivedStateOf { minOf(length, (time.value / perChar).toInt()) } }
    fun faded(): Float = ((time.value - typeMs - NOTE_HOLD_MS) / NOTE_FADE_MS).coerceIn(0f, 1f)
    // The part still to come is there but invisible, so the note does not change height as it types.
    val body = buildAnnotatedString {
        append(note.text.take(typed))
        withStyle(SpanStyle(color = Color.Transparent)) { append(note.text.drop(typed)) }
    }
    val age = if (env.clock - note.at < 60_000) "now" else Fmt.hhmm(note.at)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = 1f - 0.5f * faded() }
            .then(with(env.look) { Modifier.note(::faded, env.lookEnv) }),
    ) {
        Row {
            BasicText(note.app, style = env.base.copy(color = palette.app), modifier = Modifier.tap { env.onRun("open ${note.app}") })
            BasicText(" · $age", style = env.base.copy(color = palette.dim))
        }
        BasicText(body, style = env.base)
    }
}

private const val PLAYER_CELLS = 22

@Composable
private fun PlayerRowView(row: PlayerRow, env: RowEnv) {
    val palette = env.palette
    val phone = env.phone
    var nudge by remember { mutableIntStateOf(0) }       // asks the player again soon after a key, not a second later
    val live = remember(env.clock / 1000, nudge) { phone.nowPlaying() }
    val track = live ?: row.track
    val scope = rememberCoroutineScope()
    fun press(key: MediaKey) {
        phone.media(key)
        scope.launch {
            delay(300)
            nudge++
        }
    }
    Column(Modifier.padding(vertical = 2.dp)) {
        BasicText(track.title, style = env.base.copy(color = palette.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val time = when {
            live == null -> "stopped"
            track.durationMs > 0 -> "${Fmt.clock(track.positionMs)} / ${Fmt.clock(track.durationMs)}"
            else -> Fmt.clock(track.positionMs)
        }
        BasicText(
            text = listOf(track.artist, time).filter { it.isNotEmpty() }.joinToString(" · "),
            style = env.base.copy(color = palette.dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (live != null && track.durationMs > 0) {
            val cellWidth = 6.dp
            val gap = 2.dp
            val filled = (track.positionMs * PLAYER_CELLS / track.durationMs).toInt().coerceIn(0, PLAYER_CELLS)
            Canvas(Modifier.padding(vertical = 5.dp).size(width = cellWidth * PLAYER_CELLS + gap * (PLAYER_CELLS - 1), height = 11.dp)) {
                val step = (cellWidth + gap).toPx()
                for (i in 0 until PLAYER_CELLS) {
                    drawRect(if (i < filled) palette.fg else palette.line, Offset(i * step, 0f), Size(cellWidth.toPx(), size.height))
                }
            }
        }
        if (live != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(env.charWidth * 3)) {
                val key = env.base.copy(color = palette.app)
                BasicText("prev", style = key, modifier = Modifier.tap { press(MediaKey.PREVIOUS) }.padding(vertical = 4.dp))
                BasicText(
                    text = if (track.playing) "pause" else "play",
                    style = key,
                    modifier = Modifier.tap { press(if (track.playing) MediaKey.PAUSE else MediaKey.PLAY) }.padding(vertical = 4.dp),
                )
                BasicText("next", style = key, modifier = Modifier.tap { press(MediaKey.NEXT) }.padding(vertical = 4.dp))
            }
        }
    }
}

/**
 * A tap target that cannot take keyboard focus. `clickable` would make app names and chips focusable,
 * and then focus could leave the prompt: typed keys would go nowhere and Enter would launch whatever
 * held focus. The prompt is the only focusable thing on screen.
 */
@Composable
internal fun Modifier.tap(onTap: () -> Unit): Modifier {
    val current by rememberUpdatedState(onTap)
    return this
        .pointerInput(Unit) { detectTapGestures { current() } }
        .semantics {
            role = Role.Button
            onClick { current(); true }
        }
}
