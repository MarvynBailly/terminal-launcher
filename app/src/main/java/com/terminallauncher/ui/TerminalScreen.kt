package com.terminallauncher.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.ChipKind
import com.terminallauncher.shell.Cue
import com.terminallauncher.shell.Fmt
import com.terminallauncher.shell.Note
import com.terminallauncher.shell.Phone
import com.terminallauncher.shell.Prompt
import com.terminallauncher.shell.PromptKind
import com.terminallauncher.shell.Shell
import kotlinx.coroutines.delay

private val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)
private val FONT_SIZE = 15.sp
private val GUTTER = 16.dp
private const val ADVANCE = 0.6f      // every IBM Plex Mono glyph is 0.6 em wide

/** A cue the screen first sees this long after it was raised has missed its moment and is not played. */
private const val CUE_FRESH_MS = 2000L

/** The lock screen's screensaver starts after this long without a touch, and gives up after [SAVER_LIMIT_MS]. */
private const val SAVER_AFTER_MS = 30_000L
private const val SAVER_LIMIT_MS = 10 * 60_000L

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
 */
@Composable
fun TerminalScreen(shell: Shell, phone: Phone, rev: Int, wake: Int, visible: Boolean, motion: Boolean) {
    val palette = paletteFor(shell.theme)
    val lines = shell.lines
    val ids = shell.lineIds
    val prompt = shell.prompt
    val base = TextStyle(fontFamily = Mono, fontSize = FONT_SIZE, lineHeight = 22.sp, color = palette.fg)

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
        if (past.isEmpty() || prompt?.kind != PromptKind.SHELL) return
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

    val scene = remember(shell.cueSeq) {
        val cue = shell.cue
        val fresh = System.currentTimeMillis() - shell.cueAt < CUE_FRESH_MS
        if (motion && fresh && cue != null && cue != Cue.SAVER) Scene(cue, pageBefore.rows) else null
    }
    // Keyed on being visible too: a scene the user never saw the end of is over, not paused.
    LaunchedEffect(scene, visible) {
        if (scene != null && !scene.done) {
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
        if (!motion) typist.skipTo(ids) else if (!holding) typist.print(lines, ids)
    }
    val frontier = when {
        !motion -> Long.MAX_VALUE
        holding -> typist.shown
        else -> typist.frontier(lines, ids)
    }
    // The prompt comes back once everything that prints in turn is out.
    val printing = lines.indices.any { ids[it] >= frontier && isPaced(lines[it]) }

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
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    Box(
        Modifier
            .fillMaxSize()
            .background(if (bent != null) Color.Black else palette.bg)
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
                        .graphicsLayer {
                            val tube = bent.tube(3.dp.toPx() / size.height)
                            scaleX = tube.sx
                            scaleY = tube.sy
                            // The line forms in the middle of what can be seen, which is the part above the keyboard.
                            transformOrigin = TransformOrigin(0.5f, ((size.height - keyboardHeight) / 2f / size.height).coerceIn(0.1f, 0.5f))
                        }
                        .drawWithContent {
                            drawContent()
                            val glow = bent.tube(3.dp.toPx() / size.height).glow
                            if (glow > 0f) drawRect(TubeGlow, alpha = glow)
                        },
                )
                .background(palette.bg)
                .pointerInput(Unit) { detectTapGestures { raiseKeyboard() } },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = GUTTER),
            ) {
                StatusLine(shell, phone, clock, rev, palette, base, motion)

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val charWidth = with(LocalDensity.current) { (FONT_SIZE * ADVANCE).toDp() }
                    val columns = (maxWidth / charWidth).toInt().coerceAtLeast(20)
                    val allRows = remember(lines, ids, columns) { buildRows(lines, ids, columns) }
                    val rows = remember(allRows, frontier) { allRows.filter { it.id < frontier } }
                    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect { pageBefore.rows = rows }

                    val env = RowEnv(
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
                        onLaunch = { if (motion) launch = it },
                    )

                    // keep the prompt in view when output arrives or the keyboard moves
                    LaunchedEffect(rev, rows.size, imeBottom) { list.scrollToItem(rows.size) }

                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(rows.size, key = { rows[it].key }) { i -> RowView(rows[i], env) }
                        // The key keeps this one composition, and with it the text field's focus and the
                        // keyboard, alive while output is added above it.
                        item(key = "prompt") {
                            PromptLine(
                                prompt = prompt,
                                hidden = printing,
                                field = field,
                                palette = palette,
                                base = base,
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
                    }

                    if (playing != null && playing.showsBefore && playing.before.isNotEmpty()) {
                        PageFromBefore(
                            scene = playing,
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
                            ),
                        )
                    }
                }

                ChipRow(
                    shell = shell,
                    input = field.text,
                    browsingHistory = histIdx != -1,
                    palette = palette,
                    base = base,
                    onFill = ::setField,
                    onRun = ::runTapped,
                    onRecall = ::recall,
                )
            }

            launch?.let { fx -> key(fx) { LaunchOverlay(fx, palette, base) } }

            (preview ?: idleSaver)?.let { kind ->
                // On top of everything, and it swallows the touch that ends it so nothing underneath is hit.
                Box(Modifier.fillMaxSize().background(palette.bg).pointerInput(Unit) { detectTapGestures(onPress = {}) }) {
                    SaverView(kind, palette, Fmt.hhmm(clock))
                }
            }
        }
    }
}

/**
 * The page as it was before a scene, drawn over the live one: the home screen while a lock collapses
 * it, or the lock screen while a right password dissolves it and "access granted" resolves below.
 */
@Composable
private fun PageFromBefore(scene: Scene, env: RowEnv) {
    val granted = scene.cue == Cue.GRANTED
    Box(Modifier.fillMaxSize().background(env.palette.bg).clipToBounds()) {
        Layout(
            content = {
                Column(
                    Modifier.fillMaxWidth().graphicsLayer {
                        // the whole page lets go at the end, whatever is left of it
                        if (granted) alpha = 1f - ((scene.time.value - (GRANTED_MS - 150f)) / 150f).coerceIn(0f, 1f)
                    },
                ) {
                    // only the end of a long scrollback can be on screen
                    for (row in scene.before.takeLast(60)) key(row.key) { RowView(row, env) }
                    if (granted) {
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

/** A band in the app color that grows from the launch line until it fills the screen, with the app's name on it. */
@Composable
private fun LaunchOverlay(fx: LaunchFx, palette: Palette, base: TextStyle) {
    val time = elapsedSince(fx.bornAt, LAUNCH_SPIN_MS + LAUNCH_GROW_MS + 200)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val grown = ((time.value - LAUNCH_SPIN_MS) / LAUNCH_GROW_MS).coerceIn(0f, 1f)
            if (grown > 0f) {
                val eased = LaunchEase.transform(grown)
                val top = fx.top * (1f - eased)
                val bottom = fx.top + fx.height + (size.height - fx.top - fx.height) * eased
                drawRect(palette.app, Offset(0f, top), Size(size.width, bottom - top))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = fx.name,
            style = base.copy(color = palette.bg, fontSize = 22.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.graphicsLayer { alpha = ((time.value - LAUNCH_SPIN_MS - 180f) / 160f).coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun StatusLine(shell: Shell, phone: Phone, clock: Long, rev: Int, palette: Palette, base: TextStyle, motion: Boolean) {
    val locked = shell.locked
    val small = base.copy(fontSize = 13.sp, color = palette.dim)
    // rev and clock are the keys: the phone is only asked again when something changed or a second passed
    val track = remember(rev, clock / 1000) { if (locked) null else phone.nowPlaying()?.takeIf { it.playing } }
    // a notification that has just been printed gets a moment as a badge
    val news = !locked && shell.lines.lastOrNull { it is Note }?.let { clock - (it as Note).at < 3000 } == true
    val right = remember(rev, clock / 1000, news, palette, track != null) {
        buildAnnotatedString {
            if (phone.torch() == true) withStyle(SpanStyle(color = palette.accent)) { append("torch ") }
            // the line is short: while a track shows, its title gets the room the wifi marker had
            if (track == null && phone.wifiOn()) append("wifi ")
            if (!locked) phone.notifications()?.size?.takeIf { it > 0 }?.let {
                if (news) {
                    withStyle(SpanStyle(color = palette.bg, background = palette.accent)) { append(" $it new ") }
                    append(" ")
                } else append("$it new ")
            }
            append("${phone.battery().percent}% ")
            append(Fmt.hhmm(clock))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("tty1 · " + if (locked) "locked" else "home", style = small, maxLines = 1)
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
            val block = when {
                !focused -> SpanStyle(background = palette.dim, color = palette.bg)
                blinkOn -> SpanStyle(background = palette.fg, color = palette.bg)
                else -> SpanStyle()
            }
            withStyle(block) { append(shown.getOrNull(from)?.toString() ?: " ") }
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
            style = base.copy(color = if (prompt?.kind == PromptKind.SHELL) palette.accent else palette.fg),
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
                autoCorrect = false,
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
    browsingHistory: Boolean,
    palette: Palette,
    base: TextStyle,
    onFill: (String) -> Unit,
    onRun: (String) -> Unit,
    onRecall: (Int) -> Unit,
) {
    val found = shell.candidates(input)
    Row(
        Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (found == null) return@Row                        // no chips on the lock screen
        // phone keyboards have no arrow keys, so history gets chips of its own
        if (shell.history.isNotEmpty()) Chip("↑", palette.dim, palette, base) { onRecall(1) }
        if (browsingHistory) Chip("↓", palette.dim, palette, base) { onRecall(-1) }
        for (item in found.items.take(8)) {
            Chip(item.text, if (item.kind == ChipKind.APP) palette.app else palette.fg, palette, base) {
                val pick = shell.pick(input, found, item)
                if (pick.submit) onRun(pick.text) else onFill(pick.text)
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, palette: Palette, base: TextStyle, onClick: () -> Unit) {
    BasicText(
        text = text,
        style = base.copy(color = color),
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, palette.line, RoundedCornerShape(5.dp))
            .tap(onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}
