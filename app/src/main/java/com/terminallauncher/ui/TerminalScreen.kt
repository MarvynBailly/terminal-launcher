package com.terminallauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.terminallauncher.R
import com.terminallauncher.shell.AppGrid
import com.terminallauncher.shell.ChipKind
import com.terminallauncher.shell.Fmt
import com.terminallauncher.shell.Line
import com.terminallauncher.shell.LockHeader
import com.terminallauncher.shell.Lockout
import com.terminallauncher.shell.Phone
import com.terminallauncher.shell.Prompt
import com.terminallauncher.shell.PromptKind
import com.terminallauncher.shell.Shell
import com.terminallauncher.shell.TextLine
import kotlinx.coroutines.delay

private val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)
private val FONT_SIZE = 15.sp
private val GUTTER = 16.dp
private const val ADVANCE = 0.6f      // every IBM Plex Mono glyph is 0.6 em wide

/** What the scrolling list is made of. An [AppGrid] line becomes several [GridRow]s. */
private sealed interface Row
private data class TextRow(val line: TextLine) : Row
private data class GridRow(val cells: List<String>, val chars: Int) : Row
private object HeaderRow : Row
private data class LockoutRow(val until: Long) : Row

/**
 * The whole launcher: a status line, the scrolling terminal, the prompt, and a row of completion chips
 * that sits just above the keyboard.
 *
 * [rev] changes whenever the shell or the phone has something new to show, [wake] whenever the
 * terminal should grab focus and raise the keyboard, and [visible] is false while the activity is stopped.
 */
@Composable
fun TerminalScreen(shell: Shell, phone: Phone, rev: Int, wake: Int, visible: Boolean) {
    val palette = paletteFor(shell.theme)
    val lines = shell.lines
    val prompt = shell.prompt
    val base = TextStyle(fontFamily = Mono, fontSize = FONT_SIZE, lineHeight = 22.sp, color = palette.fg)

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var histIdx by remember { mutableIntStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()

    fun setField(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }
    fun submit(text: String) {
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
    fun raiseKeyboard() {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    // once a second while on screen: the clock, and the end of a lockout
    LaunchedEffect(visible) {
        while (visible) {
            clock = System.currentTimeMillis()
            shell.tick()
            delay(1000)
        }
    }
    LaunchedEffect(wake) { raiseKeyboard() }
    LaunchedEffect(prompt?.kind, shell.locked) {
        setField("")
        histIdx = -1
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .pointerInput(Unit) { detectTapGestures { raiseKeyboard() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = GUTTER),
        ) {
            StatusLine(shell.locked, phone, clock, rev, palette, base)

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val charWidth = with(LocalDensity.current) { (FONT_SIZE * ADVANCE).toDp() }
                val columns = (maxWidth / charWidth).toInt().coerceAtLeast(20)
                val rows = remember(lines, columns) { buildRows(lines, columns) }
                val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

                // keep the prompt in view when output arrives or the keyboard moves
                LaunchedEffect(rev, rows.size, imeBottom) { list.scrollToItem(rows.size) }

                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(rows.size) { i ->
                        when (val row = rows[i]) {
                            is TextRow -> TextRowView(row.line, palette, base, ::runTapped, ::raiseKeyboard)
                            is GridRow -> GridRowView(row, charWidth, palette, base, ::runTapped)
                            HeaderRow -> LockHeaderView(phone, clock, maxWidth, palette, base)
                            is LockoutRow -> {
                                val left = ((row.until - clock + 999) / 1000).coerceAtLeast(0)
                                BasicText("Too many attempts. Wait ${left}s", style = base.copy(color = palette.err))
                            }
                        }
                    }
                    // The key keeps this one composition, and with it the text field's focus and the
                    // keyboard, alive while output is added above it.
                    item(key = "prompt") {
                        PromptLine(
                            prompt = prompt,
                            field = field,
                            palette = palette,
                            base = base,
                            focus = focus,
                            onChange = { v ->
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
    }
}

private fun buildRows(lines: List<Line>, columns: Int): List<Row> {
    val out = ArrayList<Row>(lines.size)
    for (line in lines) {
        when (line) {
            is TextLine -> out += TextRow(line)
            LockHeader -> out += HeaderRow
            is Lockout -> out += LockoutRow(line.until)
            is AppGrid -> {
                // Size the columns for a typical name, not the longest: one long name should not cost a column.
                val lengths = line.names.map { it.length }.sorted()
                val typical = lengths[(lengths.size * 0.85f).toInt().coerceAtMost(lengths.size - 1)]
                val count = (columns / (typical + 2).coerceAtLeast(10)).coerceIn(1, 4)
                val chars = columns / count
                val perColumn = (line.names.size + count - 1) / count
                // filled down each column, like `ls`
                for (r in 0 until perColumn) {
                    out += GridRow((0 until count).mapNotNull { c -> line.names.getOrNull(c * perColumn + r) }, chars)
                }
            }
        }
    }
    return out
}

@Composable
private fun StatusLine(locked: Boolean, phone: Phone, clock: Long, rev: Int, palette: Palette, base: TextStyle) {
    val small = base.copy(fontSize = 13.sp, color = palette.dim)
    // rev and clock are the keys: the phone is only asked again when something changed or a second passed
    val right = remember(rev, clock / 1000) {
        buildAnnotatedString {
            if (phone.torch() == true) withStyle(SpanStyle(color = palette.accent)) { append("torch ") }
            if (phone.wifiOn()) append("wifi ")
            if (!locked) phone.notifications()?.size?.takeIf { it > 0 }?.let { append("$it new ") }
            append("${phone.battery().percent}% ")
            append(Fmt.hhmm(clock))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText("tty1 · " + if (locked) "locked" else "home", style = small, maxLines = 1)
        BasicText(right, style = small, maxLines = 1)
    }
}

@Composable
private fun TextRowView(line: TextLine, palette: Palette, base: TextStyle, onRun: (String) -> Unit, onBlank: () -> Unit) {
    val text = remember(line, palette) {
        buildAnnotatedString {
            for (span in line.spans) {
                val from = length
                withStyle(SpanStyle(color = palette.of(span.style))) { append(span.text) }
                if (span.run != null) addStringAnnotation("run", span.run, from, length)
            }
            if (length == 0) append(" ")
        }
    }
    val tappable = line.spans.any { it.run != null }
    ClickableText(
        text = text,
        style = base,
        // a little more height where there is something to hit
        modifier = Modifier.fillMaxWidth().padding(vertical = if (tappable) 4.dp else 0.dp),
        onClick = { at -> text.getStringAnnotations("run", at, at).firstOrNull()?.let { onRun(it.item) } ?: onBlank() },
    )
}

@Composable
private fun GridRowView(row: GridRow, charWidth: Dp, palette: Palette, base: TextStyle, onRun: (String) -> Unit) {
    Row {
        for (name in row.cells) {
            BasicText(
                text = name,
                style = base.copy(color = palette.app),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(charWidth * row.chars)
                    .tap { onRun("open $name") }
                    .padding(top = 6.dp, bottom = 6.dp, end = charWidth * 2),
            )
        }
    }
}

private val GLYPHS = mapOf(
    '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
    '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001001001001",
    '8' to "111101111101111", '9' to "111101111001111", ':' to "01010",
)

/** HH:MM as a 17 x 5 grid of lit cells, row by row. */
private fun clockCells(text: String): List<Boolean> {
    val rows = List(5) { ArrayList<Boolean>() }
    text.forEachIndexed { i, ch ->
        val glyph = GLYPHS.getValue(ch)
        val width = glyph.length / 5
        for (r in 0 until 5) {
            if (i > 0) rows[r] += false
            for (c in 0 until width) rows[r] += glyph[r * width + c] == '1'
        }
    }
    return rows.flatten()
}

@Composable
private fun LockHeaderView(phone: Phone, clock: Long, available: Dp, palette: Palette, base: TextStyle) {
    val time = Fmt.hhmm(clock)
    val cells = remember(time) { clockCells(time) }
    val cell = min(available * 0.035f, 14.dp)
    val gap = cell * 0.18f
    val summary = remember(clock / 1000) {
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
        BasicText("phone login · tty1", style = base.copy(color = palette.dim))
        Spacer(Modifier.height(22.dp))
        Canvas(Modifier.size(width = cell * 17 + gap * 16, height = cell * 5 + gap * 4)) {
            val side = cell.toPx()
            val step = side + gap.toPx()
            cells.forEachIndexed { i, on ->
                if (on) drawRect(palette.fg, topLeft = Offset((i % 17) * step, (i / 17) * step), size = Size(side, side))
            }
        }
        Spacer(Modifier.height(16.dp))
        BasicText(Fmt.day(clock), style = base)
        BasicText(summary, style = base.copy(color = palette.dim))
        BasicText(" ", style = base)
    }
}

@Composable
private fun PromptLine(
    prompt: Prompt?,
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
            withStyle(block) { append(shown.getOrNull(from)?.toString() ?: " ") }
            if (from < shown.length) append(shown.substring(from + 1))
        } else {
            withStyle(SpanStyle(background = palette.dim, color = palette.bg)) { append(shown.substring(from, to)) }
            append(shown.substring(to))
        }
    }

    // The field stays in the tree and focused even when there is no prompt, so the keyboard does not
    // drop and come back between a wrong password and the next try.
    Row(Modifier.fillMaxWidth().alpha(if (prompt == null) 0f else 1f)) {
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

/**
 * A tap target that cannot take keyboard focus. `clickable` would make app names and chips focusable,
 * and then focus could leave the prompt: typed keys would go nowhere and Enter would launch whatever
 * held focus. The prompt is the only focusable thing on screen.
 */
@Composable
private fun Modifier.tap(onTap: () -> Unit): Modifier {
    val current by rememberUpdatedState(onTap)
    return this
        .pointerInput(Unit) { detectTapGestures { current() } }
        .semantics {
            role = Role.Button
            onClick { current(); true }
        }
}
