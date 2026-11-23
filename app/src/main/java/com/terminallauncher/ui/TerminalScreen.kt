package com.terminallauncher.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.terminallauncher.shell.ChipKind
import com.terminallauncher.shell.Cue
import com.terminallauncher.shell.Fmt
import com.terminallauncher.shell.Line
import com.terminallauncher.shell.Note
import com.terminallauncher.shell.Phone
import com.terminallauncher.shell.Prompt
import com.terminallauncher.shell.PromptKind
import com.terminallauncher.shell.Shell
import com.terminallauncher.ui.looks.Look
import com.terminallauncher.ui.looks.LookEnv
import com.terminallauncher.ui.looks.lookFor
import kotlinx.coroutines.delay

/**
 * A fixed moment to draw in place of the live one, for pictures taken off the phone: a scene held
 * at [ms], every line held [arriving] of the way in, a launch held at [launch] ms, and the clock
 * that looks move by held at [time]. Nothing animates while a pose is given.
 */
class Pose(
    val cue: Cue? = null,
    val ms: Float = 0f,
    /** The page from before the scene, as the shell had it: its lines and their ids. */
    val before: Pair<List<Line>, List<Long>>? = null,
    val arriving: Float? = null,
    val launch: Float? = null,
    val time: Float = 0f,
)

/** A cue the screen first sees this long after it was raised has missed its moment and is not played. */
private const val CUE_FRESH_MS = 2000L

/** The lock screen's screensaver starts after this long without a touch, and gives up after [SAVER_LIMIT_MS]. */
private const val SAVER_AFTER_MS = 30_000L
private const val SAVER_LIMIT_MS = 10 * 60_000L

/** A prompt for command lines, the launcher's own or another computer's, as opposed to one for an answer. */
private fun takesCommands(prompt: Prompt?) = prompt?.kind == PromptKind.SHELL || prompt?.kind == PromptKind.REMOTE

/** The rows that were on screen at the last draw, for a scene that needs the page from before it. */
private class PageBefore {
    var rows: List<Row> = emptyList()
}

/** How long it has been since the user last touched or typed anything. */
private class Idle {
    var lastInput = SystemClock.uptimeMillis()
    var ms by mutableLongStateOf(0L)

    fun touch() {
        lastInput = SystemClock.uptimeMillis()
        ms = 0
    }
}

/**
 * The whole launcher: a status line, the scrolling terminal, the prompt, and a row of completion chips
 * that sits just above the keyboard.
 *
 * [rev] changes whenever the shell or the phone has something new to show, [wake] whenever the
 * terminal should grab focus and raise the keyboard, and [visible] is false while the activity is
 * stopped. With [motion] off nothing animates: output appears at once and no scene plays.
 *
 * How any of it looks and moves is the business of the shell's [Look].
 */
@Composable
fun TerminalScreen(shell: Shell, phone: Phone, rev: Int, wake: Int, visible: Boolean, motion: Boolean, pose: Pose? = null) {
    val look = lookFor(shell.look)
    val palette = look.palette(shell.theme)
    val lines = shell.lines
    val ids = shell.lineIds
    val prompt = shell.prompt
    val base = remember(look, palette) {
        // No ligatures: in a terminal every character keeps its own cell.
        look.type(
            TextStyle(fontFamily = look.font, fontSize = look.fontSize, lineHeight = look.lineHeight, color = palette.fg, fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0"),
            palette,
        )
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    // The clock a look moves by at rest. It is read while drawing, so a tick redraws and lays nothing out.
    val ambient = remember(pose) { mutableFloatStateOf(pose?.time ?: 0f) }
    LaunchedEffect(look, motion, visible, pose) {
        if (pose != null || !motion || !visible || !look.moves) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { ambient.floatValue = (it - start).toFloat() }
    }
    val env = LookEnv(
        palette = palette,
        cell = with(density) { Size(look.fontSize.toPx() * look.advance, look.lineHeight.toPx()) },
        measurer = measurer,
        base = base,
        locked = shell.locked,
        time = { ambient.floatValue },
        keyboard = keyboardHeight.toFloat(),
    )

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var histIdx by remember { mutableIntStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var charging by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val list = rememberLazyListState()

    // Whatever is on screen when the terminal is first drawn is simply there. Only later lines print.
    val typist = remember { Typist((ids.lastOrNull() ?: -1L) + 1) }
    val pageBefore = remember { PageBefore() }
    val idle = remember(visible) { Idle() }
    var launch by remember { mutableStateOf<LaunchFx?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }       // a screensaver asked for with `saver show`
    var idleSaver by remember { mutableStateOf<String?>(null) }     // the one that started by itself
    var lastSaver by remember { mutableStateOf<String?>(null) }

    fun setField(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }
    fun raiseKeyboard() {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    /** Any touch or key: the idle clock starts over, and a running screensaver gives the screen back. */
    fun touched() {
        idle.touch()
        if (preview != null || idleSaver != null) {
            preview = null
            idleSaver = null
            raiseKeyboard()
        }
    }
    fun submit(text: String) {
        touched()
        setField("")
        histIdx = -1
        shell.submit(text)
    }
    fun runTapped(command: String) {
        if (prompt?.kind != PromptKind.SHELL) return
        setField("")
        histIdx = -1
        shell.runTapped(command)
    }
    fun fill(text: String) {
        if (prompt?.kind == PromptKind.SHELL) setField(text)
        raiseKeyboard()
    }
    fun recall(direction: Int) {                 // 1 = older, -1 = newer
        val past = shell.history
        if (past.isEmpty() || !takesCommands(prompt)) return
        if (histIdx == -1) {
            if (direction < 0) return
            draft = field.text
            histIdx = past.size
        }
        histIdx = (histIdx - direction).coerceIn(0, past.size)
        if (histIdx == past.size) {
            setField(draft)
            histIdx = -1
        } else setField(past[histIdx])
    }
    fun pickSaver(): String =
        if (shell.saver != Shell.SAVER_SHUFFLE) shell.saver
        else Shell.SAVERS.filter { it != lastSaver }.random().also { lastSaver = it }

    // once a second while on screen: the clock, the end of a lockout, and how long nothing has happened
    LaunchedEffect(visible) {
        while (visible) {
            clock = System.currentTimeMillis()
            shell.tick()
            charging = phone.battery().charging
            idle.ms = SystemClock.uptimeMillis() - idle.lastInput
            delay(1000)
        }
    }
    LaunchedEffect(wake) {
        idle.touch()
        if (preview == null) raiseKeyboard()
    }
    LaunchedEffect(prompt?.kind, shell.locked) {
        setField("")
        histIdx = -1
        idle.touch()
    }

    /* ---- scenes: the screen powering on and off, and the lock dissolving after a right password ---- */

    val scene = remember(shell.cueSeq, pose, look) {
        val cue = shell.cue
        val fresh = System.currentTimeMillis() - shell.cueAt < CUE_FRESH_MS
        when {
            pose != null -> pose.cue?.let { Scene(it, emptyList(), look, pose.ms) }
            motion && fresh && cue != null && cue != Cue.SAVER -> Scene(cue, pageBefore.rows, look)
            else -> null
        }
    }
    // Keyed on being visible too: a scene the user never saw the end of is over, not paused.
    LaunchedEffect(scene, visible) {
        if (scene != null && !scene.done && pose == null) {
            if (visible) scene.play() else scene.finish()
        }
    }
    LaunchedEffect(shell.cueSeq) {
        if (shell.cue == Cue.SAVER && System.currentTimeMillis() - shell.cueAt < CUE_FRESH_MS) {
            preview = pickSaver()
            keyboard?.hide()
        }
    }
    val playing = scene?.takeIf { !it.done }
    val holding = playing != null && !playing.released

    /* ---- printing new lines one after another ---- */

    LaunchedEffect(ids.firstOrNull(), ids.lastOrNull(), ids.size, holding, motion) {
        if (!motion || pose != null) typist.skipTo(ids) else if (!holding) typist.print(lines, ids)
    }
    val frontier = when {
        pose != null -> if (holding) Long.MIN_VALUE else Long.MAX_VALUE     // a posed scene hides the page it leads to
        !motion -> Long.MAX_VALUE
        holding -> typist.shown
        else -> typist.frontier(lines, ids)
    }
    // The prompt comes back once everything that prints in turn is out.
    val printing = (pose != null && holding) || lines.indices.any { ids[it] >= frontier && isPaced(lines[it]) }

    /* ---- the lock screen's screensaver ---- */

    val saverArmed = visible && shell.locked && shell.saver != Shell.SAVER_OFF && charging
    val saverDue = saverArmed && idle.ms >= SAVER_AFTER_MS && idle.ms < SAVER_LIMIT_MS
    LaunchedEffect(saverDue) {
        if (saverDue) {
            idleSaver = pickSaver()
            keyboard?.hide()
        } else if (idleSaver != null) {
            idleSaver = null
            raiseKeyboard()
        }
    }
    // While a screensaver is due or running the screen must not time out. After the limit it may.
    val keepScreenOn = saverArmed && idle.ms < SAVER_LIMIT_MS
    SideEffect { view.keepScreenOn = keepScreenOn }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    /* ---- an app launch growing out of its line ---- */

    LaunchedEffect(launch) {
        if (launch != null) {
            keyboard?.hide()
            delay(LAUNCH_GIVE_UP_MS.toLong())
            launch = null                       // the app never covered the terminal
            raiseKeyboard()
        }
    }
    LaunchedEffect(visible) { if (!visible) launch = null }

    val bent = playing?.takeIf { it.bendsScreen }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (bent != null) bent.motion().backdrop else palette.bg)
            .pointerInput(Unit) {
                // sees every touch on its way down to whatever it lands on, and takes none of them
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (bent == null) Modifier
                    else Modifier
                        .graphicsLayer { with(bent.motion()) { shape(bent.motionTime(), env) } }
                        .drawWithContent { with(bent.motion()) { draw(bent.motionTime(), env) } },
                )
                .background(palette.bg)
                .drawBehind { with(look) { background(env) } }
                .pointerInput(Unit) { detectTapGestures { raiseKeyboard() } },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // A look that moves at rest redraws its background every frame. The text goes in a
                    // layer of its own so that does not redraw it too: glowing text redrawn sixty times
                    // a second is costly, and crashed the Android emulator outright.
                    .then(if (look.moves && motion) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(with(look) { Modifier.frame(env) })
                    .padding(horizontal = look.gutter),
            ) {
                StatusLine(shell, phone, clock, rev, look, env, motion)

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val charWidth = with(LocalDensity.current) { (look.fontSize * look.advance).toDp() }
                    val columns = (maxWidth / charWidth).toInt().coerceAtLeast(20)
                    val allRows = remember(lines, ids, columns) { buildRows(lines, ids, columns) }
                    val rows = remember(allRows, frontier) { allRows.filter { it.id < frontier } }
                    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect {
                        pageBefore.rows = rows
                        shell.columns = columns
                    }

                    val rowEnv = RowEnv(
                        palette = palette,
                        base = base,
                        charWidth = charWidth,
                        width = maxWidth,
                        phone = phone,
                        clock = clock,
                        bornAt = typist::bornAt,
                        onRun = ::runTapped,
                        onFill = ::fill,
                        onBlank = ::raiseKeyboard,
                        onLaunch = { if (motion && pose == null) launch = it },
                        look = look,
                        lookEnv = env,
                        fixed = pose?.let { it.arriving ?: 1f },
                    )

                    // keep the end of the output in view when more arrives or the keyboard moves
                    LaunchedEffect(rev, rows.size, imeBottom) { if (rows.isNotEmpty()) list.scrollToItem(rows.size - 1) }

                    // The prompt sits under the list, not in it. A list rebuilds its items when most of
                    // them change at once, as on `clear` or a burst of remote output, and a rebuilt text
                    // field loses its focus and the keyboard. Out here it is one composition for good.
                    // The list is only as tall as its rows, so the prompt still follows the last line.
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list) {
                            items(rows.size, key = { rows[it].key }) { i -> RowView(rows[i], rowEnv) }
                        }
                        PromptLine(
                            prompt = prompt,
                            hidden = printing,
                            field = field,
                            palette = palette,
                            base = base,
                            look = look,
                            focus = focus,
                            onChange = { v ->
                                touched()
                                if (prompt != null) {
                                    if ('\n' in v.text) submit(v.text.replace("\n", ""))
                                    else {
                                        if (v.text != field.text) histIdx = -1
                                        field = v
                                    }
                                }
                            },
                            onSubmit = { submit(field.text) },
                            onTab = { shell.complete(field.text)?.let { setField(it) } },
                            onRecall = ::recall,
                            onCancel = {
                                shell.cancel(field.text)
                                setField("")
                                histIdx = -1
                            },
                            onClear = { shell.clear() },
                        )
                    }

                    // A posed scene is handed the page from before. A live one remembers it.
                    val posedBefore = remember(pose, columns) { pose?.before?.let { (was, wasIds) -> buildRows(was, wasIds, columns) } }
                    val before = posedBefore ?: playing?.before.orEmpty()
                    if (playing != null && playing.showsBefore && before.isNotEmpty()) {
                        PageFromBefore(
                            scene = playing,
                            before = before,
                            env = RowEnv(
                                palette = palette,
                                base = base,
                                charWidth = charWidth,
                                width = maxWidth,
                                phone = phone,
                                clock = clock,
                                bornAt = { null },
                                onRun = {},
                                onFill = {},
                                onBlank = {},
                                onLaunch = {},
                                dissolve = if (playing.cue == Cue.GRANTED) ({ playing.time.value }) else null,
                                look = look,
                                lookEnv = env,
                                fixed = 1f,
                            ),
                        )
                    }
                }

                ChipRow(
                    shell = shell,
                    input = field.text,
                    canRecall = takesCommands(prompt),
                    browsingHistory = histIdx != -1,
                    look = look,
                    env = env,
                    onFill = ::setField,
                    onRun = ::runTapped,
                    onRecall = ::recall,
                    onKey = { key ->
                        touched()
                        shell.press(key)
                    },
                )
            }

            launch?.let { fx -> key(fx) { LaunchOverlay(fx, look, env, null) } }
            // A posed launch starts from a line a little over half way down.
            pose?.launch?.let { ms ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val top = constraints.maxHeight * 0.55f
                    LaunchOverlay(LaunchFx("spotify", top, env.cell.height, 0L), look, env, ms / look.launchMs)
                }
            }

            (preview ?: idleSaver)?.let { kind ->
                // On top of everything, and it swallows the touch that ends it so nothing underneath is hit.
                Box(Modifier.fillMaxSize().background(palette.bg).pointerInput(Unit) { detectTapGestures(onPress = {}) }) {
                    SaverView(kind, palette, Fmt.hhmm(clock))
                }
            }

            // The look's glass, in front of everything. It takes no touches.
            Box(Modifier.fillMaxSize().drawBehind { with(look) { overlay(env) } })
        }
    }
}

/**
 * The page as it was before a scene, drawn over the live one: the home screen while a lock collapses
 * it, or the lock screen while a right password dissolves it and "access granted" resolves below.
 */
@Composable
private fun PageFromBefore(scene: Scene, before: List<Row>, env: RowEnv) {
    val granted = scene.cue == Cue.GRANTED
    val look = env.look
    val leaving = look.granted
    // No ground of its own: the page it lies over prints nothing until this one has gone, and the
    // look's background shows through both.
    Box(
        Modifier.fillMaxSize().clipToBounds().then(
            if (!granted) Modifier
            else Modifier
                .graphicsLayer { with(leaving) { shape(scene.time.value, env.lookEnv) } }
                .drawWithContent { with(leaving) { draw(scene.time.value, env.lookEnv) } },
        ),
    ) {
        Layout(
            content = {
                Column(Modifier.fillMaxWidth()) {
                    // only the end of a long scrollback can be on screen
                    for (row in before.takeLast(60)) key(row.key) { RowView(row, env) }
                    if (granted && look.grantedWords) {
                        val seed = remember { (Math.random() * 1000).toInt() }
                        val frame by remember { derivedStateOf { (scene.time.value / 22f).toInt() } }
                        BasicText(scramble(frame * 22f, seed), style = env.base.copy(color = env.palette.ok))
                    } else {
                        BasicText(" ", style = env.base)       // where the prompt was
                    }
                }
            },
        ) { measurables, constraints ->
            // Like the live list: from the top if it fits, otherwise showing its end.
            val page = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(constraints.maxWidth, constraints.maxHeight) { page.place(0, minOf(0, constraints.maxHeight - page.height)) }
        }
    }
}

/** An app being opened: whatever the look draws between the launch line and the app taking over. */
@Composable
private fun LaunchOverlay(fx: LaunchFx, look: Look, env: LookEnv, fixed: Float?) {
    val time = elapsedSince(fx.bornAt, look.launchMs, fixed)
    Box(
        Modifier.fillMaxSize().drawBehind {
            with(look) { launch(time.value, Rect(0f, fx.top, size.width, fx.top + fx.height), fx.name, env) }
        },
    )
}

@Composable
private fun StatusLine(shell: Shell, phone: Phone, clock: Long, rev: Int, look: Look, env: LookEnv, motion: Boolean) {
    val locked = shell.locked
    val palette = env.palette
    val small = look.statusStyle(env)
    // rev and clock are the keys: the phone is only asked again when something changed or a second passed
    val track = remember(rev, clock / 1000) { if (locked) null else phone.nowPlaying()?.takeIf { it.playing } }
    // a notification that has just been printed gets a moment as a badge
    val news = !locked && shell.lines.lastOrNull { it is Note }?.let { clock - (it as Note).at < 3000 } == true
    val right = remember(rev, clock / 1000, news, palette, track != null, look) {
        buildAnnotatedString {
            fun say(text: String) = append(look.statusText(text))
            if (phone.torch() == true) withStyle(SpanStyle(color = palette.accent)) { say("torch ") }
            // the line is short: while a track shows, its title gets the room the wifi marker had
            if (track == null && phone.wifiOn()) say("wifi ")
            if (!locked) phone.notifications()?.size?.takeIf { it > 0 }?.let {
                if (news) {
                    withStyle(SpanStyle(color = palette.bg, background = palette.accent)) { say(" $it new ") }
                    append(" ")
                } else say("$it new ")
            }
            append("${phone.battery().percent}% ")
            append(Fmt.hhmm(clock))
        }
    }
    Row(with(look) { Modifier.fillMaxWidth().status(env) }, verticalAlignment = Alignment.CenterVertically) {
        BasicText(look.statusText("tty1 · " + (shell.remoteHost ?: if (locked) "locked" else "home")), style = small, maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                BasicText(
                    text = "♪ ${track.title}",
                    style = small.copy(color = palette.fg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 14.dp),
                )
                LevelMeter(palette.accent, motion)
            }
            BasicText(right, style = small, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Four small bars that move while music plays. */
@Composable
private fun LevelMeter(color: Color, motion: Boolean) {
    val levels = if (!motion) null else {
        val beat = rememberInfiniteTransition(label = "level meter")
        listOf(700, 430, 580, 360).map { ms ->
            beat.animateFloat(0.3f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar")
        }
    }
    val still = floatArrayOf(0.5f, 0.9f, 0.65f, 0.4f)
    Canvas(Modifier.padding(start = 6.dp).size(width = 11.dp, height = 9.dp)) {
        for (i in 0 until 4) {
            val tall = (levels?.get(i)?.value ?: still[i]) * size.height
            drawRect(color, Offset(i * 3.dp.toPx(), size.height - tall), Size(2.dp.toPx(), tall))
        }
    }
}

@Composable
private fun PromptLine(
    prompt: Prompt?,
    hidden: Boolean,
    field: TextFieldValue,
    palette: Palette,
    base: TextStyle,
    look: Look,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onTab: () -> Unit,
    onRecall: (Int) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var blinkOn by remember { mutableStateOf(true) }
    // solid while typing, blinking once the keys go quiet
    LaunchedEffect(field.text, field.selection, focused) {
        blinkOn = true
        while (focused) {
            delay(550)
            blinkOn = !blinkOn
        }
    }

    val mask = prompt?.mask == true
    val shown = if (mask) "*".repeat(field.text.length) else field.text
    val from = field.selection.min.coerceIn(0, shown.length)
    val to = field.selection.max.coerceIn(0, shown.length)
    // What the user sees. The real text field sits on top with invisible ink, so the keyboard, the caret
    // and touch all behave natively while the line still looks like a terminal with a block cursor.
    val mirror = buildAnnotatedString {
        append(shown.substring(0, from))
        if (from == to) {
            withStyle(look.cursor(focused, blinkOn, palette)) { append(shown.getOrNull(from)?.toString() ?: " ") }
            if (from < shown.length) append(shown.substring(from + 1))
        } else {
            withStyle(SpanStyle(background = palette.dim, color = palette.bg)) { append(shown.substring(from, to)) }
            append(shown.substring(to))
        }
    }

    // The field stays in the tree and focused even when there is no prompt, or while output is still
    // printing above it, so the keyboard does not drop and what is typed meanwhile is not lost.
    Row(Modifier.fillMaxWidth().alpha(if (prompt == null || hidden) 0f else 1f)) {
        BasicText(
            text = prompt?.text ?: "",
            style = base.copy(color = look.promptColor(takesCommands(prompt), palette)),
        )
        BasicTextField(
            value = field,
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> onSubmit()
                        e.key == Key.Tab -> onTab()
                        e.key == Key.DirectionUp -> onRecall(1)
                        e.key == Key.DirectionDown -> onRecall(-1)
                        e.isCtrlPressed && e.key == Key.C -> onCancel()
                        e.isCtrlPressed && e.key == Key.L -> onClear()
                        e.isCtrlPressed && e.key == Key.U -> onChange(TextFieldValue(""))
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            textStyle = base.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            visualTransformation = if (mask) PasswordVisualTransformation('*') else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                // the password type also stops the keyboard from suggesting or learning what is typed
                keyboardType = if (mask) KeyboardType.Password else KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onAny = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    BasicText(mirror, style = base)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ChipRow(
    shell: Shell,
    input: String,
    canRecall: Boolean,
    browsingHistory: Boolean,
    look: Look,
    env: LookEnv,
    onFill: (String) -> Unit,
    onRun: (String) -> Unit,
    onRecall: (Int) -> Unit,
    onKey: (String) -> Unit,
) {
    val found = shell.candidates(input)
    val palette = env.palette
    Row(
        Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (found == null) return@Row                        // no chips on the lock screen
        // phone keyboards have no arrow keys, so history gets chips of its own
        if (canRecall && shell.history.isNotEmpty()) Chip("↑", palette.dim, look, env) { onRecall(1) }
        if (canRecall && browsingHistory) Chip("↓", palette.dim, look, env) { onRecall(-1) }
        for (item in found.items.take(8)) {
            val color = when (item.kind) {
                ChipKind.APP -> palette.app
                ChipKind.KEY -> palette.accent
                else -> palette.fg
            }
            Chip(item.text, color, look, env) {
                if (item.kind == ChipKind.KEY) onKey(item.text)
                else {
                    val pick = shell.pick(input, found, item)
                    if (pick.submit) onRun(pick.text) else onFill(pick.text)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, look: Look, env: LookEnv, onClick: () -> Unit) {
    BasicText(
        text = text,
        style = look.chipStyle(color, env),
        maxLines = 1,
        modifier = Modifier.tap(onClick).then(with(look) { Modifier.chip(color, env) }),
    )
}
