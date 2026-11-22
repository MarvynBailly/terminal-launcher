package com.terminallauncher.shell

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The terminal session: the lock screen, the prompt, and every command.
 *
 * It owns the lines on screen and the current prompt, and it talks to the device only through [Phone].
 * The UI renders [lines] and [prompt], sends back what the user typed with [submit], and is told to
 * redraw through [onChange]. All calls are expected on one thread.
 *
 * With a [remote], `ssh` hands the prompt to a shell on another computer until that session ends.
 * With a [weather], `weather` prints a forecast and the login summary has a line of it.
 */
class Shell(
    private val phone: Phone,
    private val store: Store,
    private val vault: Vault,
    private val remote: Remote? = null,
    private val weather: Weather? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        val THEMES = listOf(
            "default", "green", "amber", "eink", "nord", "gruvbox", "dracula", "solarized", "synthwave", "paper",
        )

        /** The lock screen's screensavers. [SAVER_SHUFFLE] runs a different one each time. */
        val SAVERS = listOf("rain", "stars", "life", "clock")
        const val SAVER_SHUFFLE = "shuffle"
        const val SAVER_OFF = "off"
        private val SAVER_CHOICES = SAVERS + SAVER_SHUFFLE + SAVER_OFF

        private const val MAX_LINES = 400
        private const val MAX_HISTORY = 100
        private const val MAX_RECENTS = 6
        private const val WRAP = 34            // columns a row of tappable words may fill
        private const val KEY_THEME = "theme"
        private const val KEY_ALIASES = "aliases"
        private const val KEY_RECENTS = "recents"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val KEY_SEEN = "seen"
        private const val KEY_SAVER = "saver"
        private const val KEY_FX = "fx"
        private const val KEY_TIP = "tip"
        private const val KEY_HOSTS = "ssh_hosts"
        private const val KEY_HOST_KEY = "ssh_key_"        // + the computer: its key type and fingerprint
        private const val KEY_HOST_USER = "ssh_user_"      // + the computer: who logged in there last
        private const val MAX_HOSTS = 8

        /** `ssh` asks for the launcher password again once it has been this long since it was typed. */
        private const val SSH_GRACE_MS = 5 * 60_000L

        /** The keys a remote session offers as chips, and the one a login that is still under way offers. */
        const val KEY_INTERRUPT = "^C"
        const val KEY_END = "^D"
        const val KEY_DISCONNECT = "disconnect"
        const val KEY_CANCEL = "cancel"
        private const val INSTALL_MARK = "terminal-launcher-adds-a-key"

        private const val KEY_PLACE = "weather_place"      // the city `weather` reports, one field a line
        private const val KEY_UNITS = "weather_units"      // c or f; unset follows the phone's region

        private const val KEY_AT_LOGIN = "weather_login"   // off keeps the weather out of the login summary

        /** A forecast this fresh is shown again without asking for a new one. */
        private const val FORECAST_FRESH_MS = 10 * 60_000L

        /**
         * The login summary makes do with an older one. It prints at every unlock, and the phone
         * should not go to the network that often for a line that is read at a glance.
         */
        private const val LOGIN_FRESH_MS = 30 * 60_000L

        /** How many days `screen` shows, today included. The phone's own log goes back about this far. */
        private const val SCREEN_DAYS = 7

        /** From this chance up, the login summary says that today may be wet. */
        private const val WET_WORTH_SAYING = 30

        /** The WMO codes for snow, where "rain" would be the wrong word for it. */
        private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)

        /** Where the weather is read in Fahrenheit and miles an hour. */
        private val IMPERIAL_COUNTRIES = setOf("US", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")

        /** A remote prompt that asks for one of these is answered without showing what is typed. */
        private val SECRET = Regex("(password|passphrase|passcode)[^\\n]*:\\s*$", RegexOption.IGNORE_CASE)
        private val TIPS = listOf(
            "help lists every command",
            "↑ recalls your last command",
            "an app's name alone opens it",
            "alias s=open spotify makes a shortcut",
            "np shows what is playing",
            "notifs clear dismisses them all",
            "theme lists the color themes",
            "cal 12 shows December",
            "saver picks the lock screensaver",
            "ls -l lists apps with their kind",
            "fetch prints the system card",
            "fx off turns the animations off",
            "ssh user@host logs in to a computer",
            "weather prints the forecast",
            "screen shows your time on the phone",
        )
        private val SHELL_PROMPT = Prompt("$ ", PromptKind.SHELL)
        private val LOGIN_PROMPT = Prompt("password: ", PromptKind.LOGIN, mask = true)
        private val TOKEN = Regex("\"[^\"]*\"|'[^']*'|\\S+")
        private val NUMBER = Regex("^\\+?[\\d\\s().-]{3,}$")
    }

    private class Command(
        val name: String,
        val group: String?,
        val usage: String,
        val desc: String,
        val run: (args: List<String>, rest: String) -> Unit,
    )

    var lines: List<Line> = emptyList()
        private set

    /**
     * One id per line, in step with [lines]. Ids only ever rise, so the UI can tell which lines are
     * new and print those one after another.
     */
    var lineIds: List<Long> = emptyList()
        private set

    /** null while the terminal takes no input (the lock screen during a lockout). */
    var prompt: Prompt? = null
        private set
    var locked: Boolean = false
        private set
    var theme: String = store.get(KEY_THEME)?.takeIf { it in THEMES } ?: THEMES[0]
        private set

    /** One of [SAVERS], [SAVER_SHUFFLE] or [SAVER_OFF]. */
    var saver: String = store.get(KEY_SAVER)?.takeIf { it in SAVER_CHOICES } ?: SAVER_SHUFFLE
        private set

    /** false after `fx off`: no animations, and no boot log on waking. */
    var motion: Boolean = store.get(KEY_FX) != "off"
        private set

    /** The latest [Cue], a number that rises with each one, and when it was raised. */
    var cue: Cue? = null
        private set
    var cueSeq: Int = 0
        private set
    var cueAt: Long = 0
        private set
    var onChange: () -> Unit = {}

    /** How many characters fit on a line. The UI sets it, and a remote shell is told. */
    var columns: Int = WRAP

    /** What ↑ recalls: the launcher's commands, or during a remote session what was typed there. */
    val history: List<String> get() = session?.takeIf { it.open }?.recall ?: hist

    /** The computer a remote session is on, or null when there is none. */
    val remoteHost: String? get() = session?.takeIf { it.open }?.target?.host

    private val hist = ArrayList<String>()
    private val commands = LinkedHashMap<String, Command>()
    private val aliases = LinkedHashMap<String, String>()
    private var recents: List<String> = store.get(KEY_RECENTS)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    private var pending: ((String) -> Unit)? = null
    private var fails = 0
    private var nextId = 0L

    /** Until this time the lock screen takes no input. It outlasts the screen going off and on. */
    private var lockoutUntil = 0L

    /** The screen is off. A locked terminal keeps a blank page until [wake] fills it in. */
    private var asleep = false

    /** The password was right and Android is asking for its own unlock. See [awaitPhone]. */
    private var waitingForPhone = false
    private var unlockAttempt = 0

    /** A login on another computer, from the moment `ssh` starts connecting. */
    private var session: Session? = null

    /** When the launcher password was last typed. */
    private var authAt = 0L

    /**
     * A command that is waiting for an answer from the network. While it waits the terminal takes no
     * input but a cancel. Each wait has a ticket, and an answer that arrives for an old ticket, after
     * a cancel or after the terminal locked, is dropped. See [wait].
     */
    private var waitingFor = 0
    private var tickets = 0
    private var waitingName = ""
    private var waitingNote = -1L

    private class FreshForecast(val place: Place, val imperial: Boolean, val at: Long, val forecast: Forecast)

    /** The last forecast fetched, kept for [FORECAST_FRESH_MS]. */
    private var lastForecast: FreshForecast? = null

    init {
        val saved = store.get(KEY_ALIASES)
        if (saved == null) aliases["ll"] = "ls -l"
        else saved.split('\n').forEach { row ->
            val at = row.indexOf('=')
            if (at > 0) aliases[row.substring(0, at)] = row.substring(at + 1)
        }
        defineCommands()
    }

    /* ---------------- what the UI calls ---------------- */

    /** Call once when the process starts. */
    fun start() {
        if (vault.isSet) showLock() else openHome()
        onChange()
    }

    fun submit(input: String) {
        if (asleep) wake()                 // typing means the screen is on, whatever else was heard
        val p = prompt ?: return
        when (p.kind) {
            PromptKind.LOGIN -> login(input)
            PromptKind.ASK -> {
                val then = pending
                pending = null
                prompt = SHELL_PROMPT
                echo(p, if (p.mask) "" else input)
                then?.invoke(input)
            }
            PromptKind.SHELL -> {
                echo(p, input)
                runLine(input)
            }
            PromptKind.REMOTE -> session?.type(input, p.mask)
        }
        onChange()
    }

    /** A tap on a span or an app name: runs the command as if it had been typed. */
    fun runTapped(command: String) {
        if (prompt?.kind == PromptKind.SHELL) submit(command)
    }

    /** Ctrl+C. */
    fun cancel(input: String) {
        if (waitingFor != 0) {
            giveUp()
            onChange()
            return
        }
        val s = session
        if (s != null && !s.open) {             // still logging in, or answering one of its questions
            prompt?.let { echo(it, "^C") }
            s.drop()
            onChange()
            return
        }
        val p = prompt ?: return
        when (p.kind) {
            PromptKind.LOGIN -> return
            PromptKind.ASK -> {
                echo(p, "^C")
                pending = null
                prompt = SHELL_PROMPT
            }
            PromptKind.SHELL -> echo(p, "$input^C")
            PromptKind.REMOTE -> s?.interrupt(input)
        }
        onChange()
    }

    /** A tap on one of the key chips a remote session shows, or on the cancel chip of a command that waits. */
    fun press(key: String) {
        if (waitingFor != 0) {
            if (key == KEY_CANCEL) {
                giveUp()
                onChange()
            }
            return
        }
        val s = session ?: return
        when (key) {
            KEY_INTERRUPT -> if (s.open) s.interrupt("") else s.drop()
            KEY_END -> if (s.open) s.link.press(RemoteKey.END)
            KEY_DISCONNECT, KEY_CANCEL -> s.drop()
            else -> return
        }
        onChange()
    }

    /** Ctrl+L. */
    fun clear() {
        if (locked) return
        page(emptyList())
        onChange()
    }

    /**
     * Call when the screen turns off. Locks the terminal and wipes the scrollback. Returns false, and
     * locks nothing, when no password is set, because there would be no way back in.
     *
     * The locked page is left blank: nobody can see it, and [wake] prints it when the screen comes
     * back. A login that was still waiting for Android's unlock starts over from the password.
     */
    fun lock(): Boolean {
        asleep = true
        // A remote session does not outlast the screen, whether or not the terminal locks.
        if (session != null) {
            hangUp()
            pending = null
            prompt = SHELL_PROMPT
            print("The screen turned off: the ssh session was closed.", Style.DIM)
            onChange()
        }
        if (!vault.isSet) return false
        locked = true
        waitingForPhone = false
        pending = null
        stopWaiting()
        page(emptyList())
        prompt = if (lockoutUntil > now()) null else LOGIN_PROMPT
        onChange()
        return true
    }

    /**
     * Call when the screen turns on. Does nothing unless [lock] ran since the last time, so several
     * signs of the same wake are fine.
     */
    fun wake() {
        if (!asleep) return
        asleep = false
        cue(Cue.POWER_ON)
        if (locked) {
            page((if (motion) bootLog() else emptyList()) + LockHeader)
            showLock(if (lockoutUntil > now()) "Login incorrect" else null)
        }
        onChange()
    }

    /** Call about once a second while visible: ends a lockout when its time is up. */
    fun tick() {
        if (!locked) return
        if (waitingForPhone) {
            // Android was unlocked some other way while it was asking, such as from a notification.
            if (!phone.deviceLocked()) {
                openHome()
                onChange()
            }
        } else if (prompt == null && lockoutUntil <= now()) {
            showLock()
            onChange()
        }
    }

    /** Prints a line from outside the shell, such as the outcome of a permission dialog. */
    fun info(text: String, error: Boolean = false) {
        if (locked) return
        print(text, if (error) Style.ERR else Style.DIM)
        onChange()
    }

    /**
     * A notification arrived while the terminal was on screen. It is printed above the prompt. A
     * locked terminal prints nothing: the lock screen only ever shows how many are waiting.
     */
    fun notify(n: Notif) {
        if (locked) return
        val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
            .replace(Regex("\\s+"), " ").let { if (it.length > 160) it.take(159) + "…" else it }
        if (body.isEmpty()) return
        add(Note(n.app, body, now()))
        onChange()
    }

    /* ---------------- completion ---------------- */

    fun candidates(input: String): Candidates? {
        if (waitingFor != 0) return Candidates(input.length, "", listOf(Candidate(KEY_CANCEL, ChipKind.KEY)))
        session?.let { s ->
            // Nothing to complete on another computer. The chips are the keys a phone keyboard lacks.
            val keys = when {
                !s.open -> listOf(KEY_CANCEL)
                s.terminal -> listOf(KEY_INTERRUPT, KEY_END, KEY_DISCONNECT)
                else -> listOf(KEY_END, KEY_DISCONNECT)
            }
            return Candidates(input.length, "", keys.map { Candidate(it, ChipKind.KEY) })
        }
        if (prompt?.kind != PromptKind.SHELL) return null
        val body = input.trimStart()
        val lead = input.length - body.length
        val space = body.indexOf(' ')
        fun apps(names: List<String>) = names.map { Candidate(it, ChipKind.APP) }

        if (space < 0) {                                     // still typing the first word
            val p = body.lowercase()
            if (p.isEmpty()) return Candidates(lead, p, apps(liveRecents()))
            val words = commands.values.filter { it.group != null }.map { it.name } + aliases.keys
            val cmds = words.filter { it.startsWith(p) }.map { Candidate(it, ChipKind.CMD) }
            return Candidates(lead, p, cmds + apps(matchApps(p)))
        }

        val word = body.substring(0, space).lowercase()
        val tail = body.substring(space + 1)
        val argStart = lead + space + 1 + (tail.length - tail.trimStart().length)
        val arg = input.substring(argStart)
        val firstArg = ' ' !in arg
        fun lastWord(options: List<String>): Candidates {
            val at = arg.lastIndexOf(' ') + 1
            val last = arg.substring(at).lowercase()
            return Candidates(argStart + at, last, options.filter { it.startsWith(last) }.map { Candidate(it, ChipKind.ARG) })
        }
        val none = Candidates(input.length, "", emptyList())
        return when {
            word == "open" || word == "info" -> {
                val p = arg.lowercase()
                Candidates(argStart, p, apps(if (p.isEmpty()) liveRecents() else matchApps(p)))
            }
            word == "ls" -> lastWord(listOf("-l") + phone.apps().map { it.category }.distinct().filter { it != "-" }.sorted())
            word == "call" || word == "sms" ->
                if (firstArg) lastWord(phone.contacts().orEmpty().map { it.name }.distinct()) else none
            word == "theme" -> if (firstArg) lastWord(THEMES) else none
            word == "saver" -> if (firstArg) lastWord(SAVER_CHOICES + "show") else none
            word == "np" -> if (firstArg) lastWord(listOf("pause", "play", "next", "prev")) else none
            word == "fx" -> if (firstArg) lastWord(listOf("on", "off")) else none
            word == "torch" -> if (firstArg) lastWord(listOf("on", "off")) else none
            word == "notifs" -> if (firstArg) lastWord(listOf("clear")) else none
            word == "unalias" -> if (firstArg) lastWord(aliases.keys.toList()) else none
            word == "setup" -> if (firstArg) lastWord(phone.setup().map { it.key }) else none
            word == "ssh" -> when {
                firstArg -> lastWord(hosts() + listOf("key", "forget"))
                arg.startsWith("forget ") -> lastWord(hosts().map { it.substringAfter('@') }.distinct())
                else -> none
            }
            word == "ssh-copy-id" -> if (firstArg) lastWord(hosts()) else none
            word == "weather" -> when {
                firstArg -> lastWord(listOf("set", "units", "login"))
                arg.startsWith("units ") -> lastWord(listOf("c", "f"))
                arg.startsWith("login ") -> lastWord(listOf("on", "off"))
                else -> none
            }
            word in commands || word in aliases -> none
            // not a command, so the whole line is an app name with spaces in it
            else -> Candidates(lead, body.lowercase(), apps(matchApps(body.lowercase())))
        }
    }

    /** Tapping a completion chip. App chips launch straight away; the rest fill in the word. */
    fun pick(input: String, c: Candidates, item: Candidate): Pick {
        val text = input.substring(0, c.start.coerceAtMost(input.length)) + item.text
        return if (item.kind == ChipKind.APP) Pick(text, true) else Pick("$text ", false)
    }

    /** Tab on a hardware keyboard. Returns the completed input, or null if there is nothing to add. */
    fun complete(input: String): String? {
        val c = candidates(input) ?: return null
        if (c.prefix.isEmpty() || c.items.isEmpty()) return null
        val head = input.substring(0, c.start)
        if (c.items.size == 1) return head + c.items[0].text + " "
        val texts = c.items.map { it.text }.filter { it.startsWith(c.prefix) }
        if (texts.isEmpty()) return null
        var common = texts[0]
        for (t in texts) while (!t.startsWith(common)) common = common.dropLast(1)
        return if (common.length > c.prefix.length) head + common else null
    }

    /* ---------------- output ---------------- */

    private fun add(line: Line) {
        lines = (lines + line).takeLast(MAX_LINES)
        lineIds = (lineIds + nextId++).takeLast(MAX_LINES)
    }

    private fun addAll(more: List<Line>) {
        lines = (lines + more).takeLast(MAX_LINES)
        lineIds = (lineIds + more.map { nextId++ }).takeLast(MAX_LINES)
    }

    /** Replaces everything on screen. */
    private fun page(content: List<Line>) {
        lines = content
        lineIds = content.map { nextId++ }
    }

    /** Puts [line] where the line with [id] is. false if that line has left the screen. */
    private fun replace(id: Long, line: Line): Boolean {
        val at = lineIds.indexOf(id)
        if (at < 0) return false
        lines = lines.toMutableList().also { it[at] = line }
        return true
    }

    /** Takes one line back off the screen, if it is still there. */
    private fun remove(id: Long) {
        val at = lineIds.indexOf(id)
        if (at < 0) return
        lines = lines.filterIndexed { i, _ -> i != at }
        lineIds = lineIds.filterIndexed { i, _ -> i != at }
    }

    private fun print(text: String = "", style: Style = Style.NORMAL) = add(TextLine(listOf(Span(text, style))))

    /* ---------------- waiting for the network ---------------- */

    /**
     * Hides the prompt while the command called [name] waits for an answer, with [note] on screen to
     * say what for. Returns the ticket of this wait: the answer is taken with [answered], and only
     * if the terminal is still waiting for that ticket.
     */
    private fun wait(name: String, note: String): Int {
        add(TextLine(listOf(Span(note, Style.DIM)), Entrance.NONE))
        waitingNote = nextId - 1
        waitingName = name
        prompt = null
        waitingFor = ++tickets
        return waitingFor
    }

    /** Ends the wait for [ticket] and brings the prompt back. false if that wait is already over. */
    private fun answered(ticket: Int): Boolean {
        if (ticket == 0 || waitingFor != ticket) return false
        stopWaiting()
        prompt = SHELL_PROMPT
        return true
    }

    /** Ends any wait without touching the prompt. Whatever the wait was for is dropped when it arrives. */
    private fun stopWaiting() {
        if (waitingFor == 0) return
        waitingFor = 0
        remove(waitingNote)
    }

    /** The user would rather not wait. */
    private fun giveUp() {
        val name = waitingName
        stopWaiting()
        prompt = SHELL_PROMPT
        print("$name: cancelled", Style.DIM)
    }

    private fun row(vararg spans: Span) = add(TextLine(spans.toList()))

    /** What was typed is already on screen, so its echo does not print itself out again. */
    private fun echo(p: Prompt, text: String) = add(
        TextLine(
            listOf(Span(p.text, if (p.kind == PromptKind.SHELL) Style.ACCENT else Style.NORMAL), Span(text)),
            Entrance.NONE,
        ),
    )

    /** Tappable words on as many rows as they need, with the current one in brackets. */
    private fun choices(words: List<String>, current: String, command: String) {
        var spans = ArrayList<Span>()
        var width = 0
        for (word in words) {
            val label = if (word == current) "[$word]" else word
            if (width > 0 && width + 2 + label.length > WRAP) {
                add(TextLine(spans))
                spans = ArrayList()
                width = 0
            }
            if (width > 0) spans += Span("  ")
            spans += Span(label, if (word == current) Style.ACCENT else Style.APP, "$command $word")
            width += (if (width > 0) 2 else 0) + label.length
        }
        if (spans.isNotEmpty()) add(TextLine(spans))
    }

    private fun cue(c: Cue) {
        cue = c
        cueSeq++
        cueAt = now()
    }

    /** An error that the user can fix by granting access, with a tappable way to do it. */
    private fun needs(key: String, message: String) {
        print(message, Style.ERR)
        row(Span("fix it: ", Style.DIM), Span("setup $key", Style.APP, "setup $key"))
    }

    /* ---------------- lock screen and login ---------------- */

    /**
     * Cuts a lock page back to its header, keeping the boot log above it, or starts a new page with
     * just the header if the lock screen is not what is showing.
     */
    private fun toLockHeader() {
        val at = if (locked) lines.indexOf(LockHeader) else -1
        if (at < 0) {
            page(listOf(LockHeader))
        } else {
            lines = lines.take(at + 1)
            lineIds = lineIds.take(at + 1)
        }
    }

    private fun showLock(note: String? = null) {
        hangUp()
        stopWaiting()
        toLockHeader()
        locked = true
        waitingForPhone = false
        pending = null
        // These sit under the password prompt and change with every try, so they do not print themselves out.
        if (note != null) add(TextLine(listOf(Span(note, Style.ERR)), Entrance.NONE))
        if (lockoutUntil > now()) {
            add(Lockout(lockoutUntil))
            prompt = null
        } else {
            prompt = LOGIN_PROMPT
        }
    }

    private fun login(password: String) {
        if (vault.matches(password)) {
            fails = 0
            authAt = now()
            if (phone.deviceLocked()) awaitPhone() else openHome()
            return
        }
        fails++
        // every third miss costs a wait, and the waits get longer
        if (fails % 3 == 0) lockoutUntil = now() + when (fails / 3) { 1 -> 10; 2 -> 30; else -> 60 } * 1000L
        showLock("Login incorrect")
    }

    /** What a waking terminal prints above the lock header. All of it is already on the lock screen. */
    private fun bootLog(): List<Line> {
        fun ok(name: String, value: String) = TextLine(
            listOf(Span("[ ", Style.DIM), Span("ok", Style.OK), Span(" ] ", Style.DIM), Span(name.padEnd(15)), Span(value, Style.DIM)),
        )
        val out = ArrayList<Line>()
        out += TextLine(listOf(Span("[ 0.000] tty1: waking", Style.DIM)))
        out += ok("display", phone.system().display)
        out += ok("keyboard", "ready")
        out += ok("apps", "${phone.apps().size} found")
        phone.notifications()?.let { out += ok("notifications", if (it.isEmpty()) "none" else "${it.size} waiting") }
        val battery = phone.battery()
        out += ok("battery", "${battery.percent}%" + if (battery.charging) " charging" else "")
        out += TextLine(listOf(Span("")))
        return out
    }

    /**
     * The password was right, but the terminal is being shown on top of Android's lock screen and that
     * lock is still up. The terminal stays locked until Android reports its own unlock, so none of its
     * commands can be used on a phone that is still locked.
     */
    private fun awaitPhone() {
        val attempt = ++unlockAttempt
        toLockHeader()
        waitingForPhone = true
        add(TextLine(listOf(Span("unlock the phone to continue", Style.DIM)), Entrance.NONE))
        prompt = null
        phone.unlockDevice { unlocked ->
            // Out of date if the terminal was locked again, or let in by tick(), while Android was asking.
            if (waitingForPhone && attempt == unlockAttempt) {
                if (unlocked) openHome() else showLock("Phone still locked")
                onChange()
            }
        }
    }

    private fun openHome() {
        if (locked) cue(Cue.GRANTED)
        locked = false
        waitingForPhone = false
        pending = null
        stopWaiting()
        prompt = SHELL_PROMPT
        page(emptyList())
        val last = store.get(KEY_LAST_LOGIN)?.toLongOrNull()
        if (store.get(KEY_SEEN) == null) {
            store.put(KEY_SEEN, "1")
            print("terminal launcher", Style.ACCENT)
            print("No password is set, so nothing locks yet.", Style.DIM)
            print("passwd   set a password", Style.DIM)
            print("setup    grant phone access", Style.DIM)
            print("help     every command", Style.DIM)
        } else {
            if (last != null) print("Last login: ${Fmt.day(last)} ${Fmt.hhmm(last)} on tty1", Style.DIM)
            motd()
        }
        print()
        store.put(KEY_LAST_LOGIN, now().toString())
    }

    /**
     * The message of the day: how far along the day, week and year are, the weather, how much the
     * phone has been used today, what is waiting, and a tip.
     */
    private fun motd() {
        val t = now()
        print()
        add(Gauge("day", Fmt.dayPercent(t), "${Fmt.dayPercent(t)}%"))
        add(Gauge("week", Fmt.weekPercent(t), Fmt.weekday(t).lowercase()))
        add(Gauge("year", Fmt.yearPercent(t), "${Fmt.yearPercent(t)}%"))
        print()

        loginWeather()
        loginScreenTime()
        val status = ArrayList<Span>()
        phone.notifications()?.let {
            status += if (it.isEmpty()) Span("nothing new", Style.DIM) else Span("${it.size} new", Style.APP, "notifs")
        }
        status += Span(if (phone.wifiOn()) "wifi" else "wifi off", Style.DIM)
        phone.torch()?.let { status += Span(if (it) "torch on" else "torch off", Style.DIM) }
        add(TextLine(status.flatMapIndexed { i, s -> if (i == 0) listOf(s) else listOf(Span(" · ", Style.DIM), s) }))

        // a different tip each login
        val turn = (store.get(KEY_TIP)?.toIntOrNull() ?: 0).coerceAtLeast(0)
        store.put(KEY_TIP, ((turn + 1) % TIPS.size).toString())
        print("tip: ${TIPS[turn % TIPS.size]}", Style.DIM)
    }

    /* ---------------- running a line ---------------- */

    private fun ask(text: String, mask: Boolean = true, then: (String) -> Unit) {
        pending = then
        prompt = Prompt(text, PromptKind.ASK, mask)
    }

    private fun tokenize(line: String): List<String> = TOKEN.findAll(line).map { m ->
        val t = m.value
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) t.substring(1, t.length - 1) else t
    }.toList()

    private fun afterFirst(line: String): String = line.trim().replaceFirst(Regex("^\\S+\\s*"), "")

    private fun runLine(input: String) {
        val line = input.trim()
        if (line.isEmpty()) return
        if (hist.lastOrNull() != line) {
            hist.add(line)
            if (hist.size > MAX_HISTORY) hist.removeAt(0)
        }
        exec(line)
    }

    private fun exec(typed: String) {
        var line = typed
        var toks = tokenize(line)
        val first = toks.firstOrNull()?.lowercase() ?: return
        aliases[first]?.let {
            line = (it + " " + afterFirst(line)).trim()
            toks = tokenize(line)
        }
        val name = toks.firstOrNull()?.lowercase() ?: return
        commands[name]?.let {
            it.run(toks.drop(1), afterFirst(line))
            return
        }

        // Not a command. An app name on its own launches the app.
        val q = line.trim().lowercase()
        val found = resolveApp(q, loose = false)
        val exact = found.size == 1 && found[0].name == q
        when {
            found.size == 1 && (exact || q.length >= 2) -> launch(found[0])
            found.size > 1 && q.length >= 2 -> {
                print("'$q' matches ${found.size} apps", Style.DIM)
                add(AppGrid(found.map { it.name }))
            }
            else -> {
                add(TextLine(listOf(Span("${toks[0]}: command not found", Style.ERR)), Entrance.SHAKE))
                // Tapping the fix puts it at the prompt. It does not run: it could be a call.
                suggest(line)?.let { row(Span("did you mean ", Style.DIM), Span(it, Style.APP, fill = it), Span("?", Style.DIM)) }
            }
        }
    }

    /* ---------------- apps ---------------- */

    /** Exact name, then names starting with the query, then names with a word starting with it, then anywhere. */
    private fun resolveApp(query: String, loose: Boolean = true): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val apps = phone.apps()
        apps.filter { it.name == q }.let { if (it.isNotEmpty()) return it }
        apps.filter { it.name.startsWith(q) }.let { if (it.isNotEmpty()) return it }
        apps.filter { " ${it.name}".contains(" $q") }.let { if (it.isNotEmpty()) return it }
        return if (loose) apps.filter { q in it.name } else emptyList()
    }

    private fun matchApps(p: String): List<String> {
        val names = phone.apps().map { it.name }
        val starts = names.filter { it.startsWith(p) }
        val words = names.filter { " $it".contains(" $p") }
        val anywhere = names.filter { p in it }
        return (starts + words + anywhere).distinct()
    }

    private fun liveRecents(): List<String> {
        val names = phone.apps().map { it.name }.toSet()
        return recents.filter { it in names }
    }

    private fun launch(app: AppEntry) {
        if (!phone.launch(app)) {
            print("open: could not start ${app.name}", Style.ERR)
            return
        }
        add(Launching(app.name))
        recents = (listOf(app.name) + recents.filter { it != app.name }).take(MAX_RECENTS)
        store.put(KEY_RECENTS, recents.joinToString("\n"))
    }

    /** Runs [then] with the one app [query] names, or prints why it could not pick one. */
    private fun oneApp(cmd: String, query: String, then: (AppEntry) -> Unit) {
        val found = resolveApp(query)
        when {
            found.isEmpty() -> print("$cmd: no app matches '$query'", Style.ERR)
            found.size > 1 -> {
                print("$cmd: '$query' matches ${found.size} apps", Style.DIM)
                add(AppGrid(found.map { it.name }))
            }
            else -> then(found[0])
        }
    }

    /* ---------------- contacts ---------------- */

    /** Contacts whose name is [query], or failing that, whose name has a word starting with each word of it. */
    private fun matchContacts(all: List<Contact>, query: String): List<Contact> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        all.filter { it.name == q }.let { if (it.isNotEmpty()) return it }
        val words = q.split(' ').filter { it.isNotEmpty() }
        return all.filter { c -> words.all { w -> c.name.split(' ').any { it.startsWith(w) } } }
    }

    private fun listContacts(cmd: String, found: List<Contact>, suffix: String = "") {
        found.take(8).forEach { c ->
            // no spaces in the number, so it stays one word when the tapped command is parsed
            val run = "$cmd ${c.number.filterNot { it.isWhitespace() }}$suffix"
            row(Span(c.name, Style.APP, run), Span("  ${c.number}", Style.DIM, run))
        }
        if (found.size > 8) print("and ${found.size - 8} more", Style.DIM)
    }

    /* ---------------- did you mean ---------------- */

    private fun distance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val keep = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = keep
            }
        }
        return row[b.length]
    }

    /** The name closest to [word] and how many slips apart they are. A name [word] starts counts as none. */
    private fun nearest(word: String, names: List<String>): Pair<String, Int>? {
        if (word.length >= 2) names.firstOrNull { it.startsWith(word) }?.let { return it to 0 }
        var best: String? = null
        var bestD = if (word.length <= 3) 2 else 3     // one slip in a short word, two in a longer one
        for (n in names) {
            val d = distance(word, n)
            if (d < bestD) {
                best = n
                bestD = d
            }
        }
        return best?.let { it to bestD }
    }

    /**
     * The line the user most likely meant: the first word corrected to a command with the rest kept,
     * or an app's name, whichever is the smaller change.
     */
    private fun suggest(line: String): String? {
        val q = line.trim().lowercase()
        val first = q.substringBefore(' ')
        val rest = q.substringAfter(' ', "").trim()
        val apps = phone.apps().map { it.name }
        val command = nearest(first, commands.values.filter { it.group != null }.map { it.name })
        val app = nearest(q, apps) ?: if (rest.isEmpty()) null else nearest(first, apps)
        return when {
            command != null && (app == null || command.second <= app.second) ->
                if (rest.isEmpty()) command.first else "${command.first} $rest"
            else -> app?.first
        }
    }

    /* ---------------- ssh ---------------- */

    /** The logins that worked, latest first, as they are typed after `ssh`. */
    private fun hosts(): List<String> = store.get(KEY_HOSTS)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** Ends a remote session without a word. Whatever it reports from then on is ignored. */
    private fun hangUp() {
        val s = session ?: return
        session = null
        s.link.close()
    }

    /**
     * One login on another computer. Until it is [open] the terminal takes nothing but the answers the
     * login asks for. After that each line typed goes to the far end, and what the far end writes is
     * printed. The text it leaves without a line break, usually its own prompt, becomes the prompt here.
     */
    private inner class Session(val target: Target, val install: String?) : LinkEvents {
        lateinit var link: Link
        var open = false
        val recall = ArrayList<String>()
        private val text = RemoteText()

        /** false on a computer that gave no terminal: it repeats what it is sent, and has no ^C. */
        var terminal = true

        /** A line the far end is about to repeat back. */
        private var sent: String? = null

        /** Output is not shown until a line ends with this. `ssh-copy-id` uses it to skip the greeting. */
        private var hiddenUntil: String? = null

        /** An interrupted shell answers with an empty line before its prompt. One of those is not shown. */
        private var interrupted = false

        private val live get() = session === this

        /** Asks the user something for the login. Once answered, the terminal goes quiet again. */
        private fun inquire(question: String, mask: Boolean, then: (String) -> Unit) = this@Shell.ask(question, mask) { typed ->
            if (live) {
                prompt = null
                then(typed)
            }
        }

        private fun showPrompt() {
            var partial = if (hiddenUntil == null) text.pending else ""
            // the start of an echo that is still arriving is not a prompt
            sent?.let { if (it.startsWith(partial)) partial = "" }
            // a long prompt keeps its end, so there is still room to type beside it
            val room = (columns / 2).coerceAtLeast(8)
            val shown = if (partial.length <= room) partial else "…" + partial.takeLast(room - 1)
            prompt = Prompt(shown, PromptKind.REMOTE, SECRET.containsMatchIn(partial))
        }

        /** Back to the launcher's own prompt, with [message] as the last word on the session. */
        private fun finish(message: String, error: Boolean) {
            val rest = text.take()
            if (rest.isNotBlank()) add(TextLine(listOf(Span(rest)), Entrance.NONE))
            pending = null
            print(message, if (error) Style.ERR else Style.DIM)
            prompt = SHELL_PROMPT
        }

        fun type(input: String, secret: Boolean) {
            add(TextLine(listOf(Span(text.take(), Style.ACCENT), Span(if (secret) "" else input)), Entrance.NONE))
            if (!secret && input.isNotBlank() && recall.lastOrNull() != input) {
                recall.add(input)
                if (recall.size > MAX_HISTORY) recall.removeAt(0)
            }
            sent = input.takeIf { !terminal && !secret && it.isNotBlank() }
            interrupted = false
            link.send(input + "\n")
            showPrompt()
        }

        fun interrupt(input: String) {
            if (!terminal) {
                // there the signal would end the whole session, which is what disconnect is for
                print("This computer gave no terminal, so it has no ^C. disconnect stops a command.", Style.DIM)
                return
            }
            add(TextLine(listOf(Span(text.take(), Style.ACCENT), Span("$input^C")), Entrance.NONE))
            sent = null
            interrupted = true
            link.press(RemoteKey.INTERRUPT)
            showPrompt()
        }

        /** The user ends it from this side. */
        fun drop() {
            val wasOpen = open
            hangUp()
            finish(if (wasOpen) "Disconnected from ${target.host}." else "ssh: cancelled", error = false)
        }

        override fun hostKey(type: String, fingerprint: String) {
            if (!live) return
            val shown = "$type $fingerprint"
            when (val known = store.get(KEY_HOST_KEY + target.place)) {
                shown -> link.trust(true)
                null -> {
                    print("First connection to ${target.place}.")
                    print("Its $type key fingerprint is", Style.DIM)
                    print(fingerprint)
                    inquire("trust it? (yes/no) ", mask = false) { typed ->
                        val yes = typed.trim().lowercase() in setOf("y", "yes")
                        if (yes) store.put(KEY_HOST_KEY + target.place, shown)
                        link.trust(yes)
                    }
                }
                else -> {
                    print("WARNING: ${target.place} is not showing the key it had before.", Style.ERR)
                    print("was  $known", Style.DIM)
                    print("now  $shown", Style.DIM)
                    print("Another computer may be answering in its place. If this one was reinstalled, forget the old key and connect again:", Style.DIM)
                    val fix = "ssh forget ${target.place}"
                    row(Span(fix, Style.APP, fill = fix))
                    link.trust(false)
                }
            }
            onChange()
        }

        override fun ask(question: String, secret: Boolean, again: Boolean) {
            if (!live) return
            if (again) print("Permission denied, please try again.", Style.ERR)
            inquire(question, secret) { link.answer(it) }
            onChange()
        }

        override fun notice(text: String) {
            if (!live) return
            // through the same filter as the session, so a banner cannot send control codes either
            RemoteText().feed(text + "\n").filter { it.isNotBlank() }.forEach { print(it, Style.DIM) }
            onChange()
        }

        override fun opened(server: String, terminal: Boolean) {
            if (!live) return
            open = true
            this.terminal = terminal
            pending = null
            store.put(KEY_HOST_USER + target.place, target.user)
            store.put(KEY_HOSTS, (listOf(target.label) + hosts().filter { it != target.label }).take(MAX_HOSTS).joinToString("\n"))
            if (install != null) {
                if ("windows" in server.lowercase()) {
                    print("ssh-copy-id: this is a Windows computer.", Style.ERR)
                    print("Add the key to its authorized_keys file by hand. ssh key shows the key.", Style.DIM)
                } else {
                    hiddenUntil = INSTALL_MARK
                    link.send(installLine(install))
                }
            }
            showPrompt()
            onChange()
        }

        override fun output(text: String) {
            if (!live || !open) return
            var done = this.text.feed(text)
            hiddenUntil?.let { mark ->
                val at = done.indexOfFirst { it.endsWith(mark) }
                if (at >= 0) hiddenUntil = null
                done = if (at >= 0) done.drop(at + 1) else emptyList()
            }
            val expected = sent
            if (expected != null && done.isNotEmpty()) {
                sent = null
                if (done[0].trim() == expected.trim()) done = done.drop(1)
            }
            if (interrupted && done.isNotEmpty()) {
                interrupted = false
                if (done[0].isBlank()) done = done.drop(1)
            }
            if (done.isNotEmpty()) addAll(done.map { TextLine(listOf(Span(it)), Entrance.NONE) })
            showPrompt()
            onChange()
        }

        override fun closed(reason: String?) {
            if (!live) return
            session = null
            when {
                reason != null -> finish("ssh: $reason", error = true)
                open -> finish("Connection to ${target.host} closed.", error = false)
                else -> finish("ssh: ${target.host} closed the connection", error = true)
            }
            onChange()
        }
    }

    /**
     * What `ssh-copy-id` runs on the far end: adds [key] to the account's authorized keys unless it is
     * there already, says so, and logs out. Plain enough for sh, bash, zsh and fish. It first prints
     * [INSTALL_MARK], so the greeting and the prompt before it can be left off the screen.
     */
    private fun installLine(key: String): String {
        val file = "~/.ssh/authorized_keys"
        return " echo $INSTALL_MARK; umask 077; mkdir -p ~/.ssh; grep -qxF '$key' $file 2>/dev/null || echo '$key' >> $file; " +
            "grep -qxF '$key' $file && echo 'Key installed. The next login needs no password.'; exit\n"
    }

    /** Reads `[user@]host[:port] [-p port] [-l user]`. Prints what is wrong and returns null if it cannot. */
    private fun parseTarget(cmd: String, args: List<String>): Triple<String?, String, Int>? {
        fun fail(message: String): Triple<String?, String, Int>? {
            print("$cmd: $message", Style.ERR)
            return null
        }
        var user: String? = null
        var port: Int? = null
        var spec: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-p" || a == "-l" -> {
                    val value = args.getOrNull(++i) ?: return fail("$a needs a value")
                    if (a == "-l") user = value
                    else port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return fail("no port '$value'")
                }
                a.startsWith("-") -> return fail("no option $a")
                spec == null -> spec = a
                else -> return fail("log in first, then type the command there")
            }
            i++
        }
        var host = spec ?: return fail("which computer?")
        val at = host.lastIndexOf('@')
        if (at >= 0) {
            user = host.substring(0, at)
            host = host.substring(at + 1)
        }
        if (host.count { it == ':' } == 1) {            // host:port. More colons would be an IPv6 address.
            val p = host.substringAfter(':')
            port = port ?: p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return fail("no port '$p'")
            host = host.substringBefore(':')
        }
        if (host.isEmpty() || user?.isEmpty() == true) return fail("which computer?")
        return Triple(user, host.lowercase(), port ?: 22)
    }

    /** Works out who to log in as, then connects. With [install], the session only adds that key and leaves. */
    private fun sshTo(cmd: String, args: List<String>, install: String? = null) {
        val (user, host, port) = parseTarget(cmd, args) ?: return
        val known = user ?: store.get(KEY_HOST_USER + Target("", host, port).place)
        if (known != null) connect(Target(known, host, port), install)
        else ask("login as: ", mask = false) { name ->
            if (name.isBlank()) print("$cmd: no user given", Style.ERR) else connect(Target(name.trim(), host, port), install)
        }
    }

    /**
     * The phone holds a key to other computers, so `ssh` wants the launcher password again unless it
     * was typed a moment ago, the way `sudo` does. With no password set there is nothing to ask.
     */
    private fun connect(target: Target, install: String?) {
        val r = remote
        if (r == null) {
            print("ssh: not available here", Style.ERR)
        } else if (vault.isSet && now() - authAt > SSH_GRACE_MS) {
            ask("launcher password: ") { typed ->
                if (vault.matches(typed)) {
                    authAt = now()
                    dial(r, target, install)
                } else print("ssh: wrong launcher password", Style.ERR)
            }
        } else dial(r, target, install)
    }

    private fun dial(r: Remote, target: Target, install: String?) {
        print("connecting to ${target.place}…", Style.DIM)
        val s = Session(target, install)
        session = s
        prompt = null
        s.link = r.open(target, columns, store.get(KEY_HOST_KEY + target.place)?.substringBefore(' '), s)
    }

    private fun sshKey(copy: Boolean) {
        val key = remote?.publicKey()
        when {
            remote == null -> print("ssh: not available here", Style.ERR)
            key == null -> print("ssh: could not make a key", Style.ERR)
            copy -> if (phone.copy(key)) print("copied", Style.DIM) else print("ssh: could not reach the clipboard", Style.ERR)
            else -> {
                print("This phone's public key. Tap it to copy.", Style.DIM)
                add(TextLine(listOf(Span(key, run = "ssh key copy"))))
                row(Span("put it on a computer: ", Style.DIM), Span("ssh-copy-id", Style.APP, fill = "ssh-copy-id "))
            }
        }
    }

    /* ---------------- commands ---------------- */

    /* ---------------- weather ---------------- */

    private fun imperial(): Boolean = when (store.get(KEY_UNITS)) {
        "f" -> true
        "c" -> false
        else -> Locale.getDefault().country in IMPERIAL_COUNTRIES
    }

    private fun savedPlace(): Place? {
        val fields = store.get(KEY_PLACE)?.split('\n') ?: return null
        if (fields.size != 6) return null
        val latitude = fields[4].toDoubleOrNull() ?: return null
        val longitude = fields[5].toDoubleOrNull() ?: return null
        return Place(fields[0], fields[1].ifEmpty { null }, fields[2].ifEmpty { null }, fields[3].ifEmpty { null }, latitude, longitude)
    }

    private fun savePlace(p: Place) {
        fun field(text: String?) = text.orEmpty().replace('\n', ' ')
        val fields = listOf(field(p.name), field(p.region), field(p.country), field(p.countryCode), p.latitude, p.longitude)
        store.put(KEY_PLACE, fields.joinToString("\n"))
    }

    /**
     * The first of [places] that all of [hint] fits: `springfield, illinois` picks the Springfield
     * whose region, country or country letters have a word starting with each word typed after the
     * comma. With nothing after the name it is the first, which is the likeliest.
     */
    private fun choose(places: List<Place>, hint: String): Place? {
        fun words(text: String) = text.lowercase().split(' ', '-', ',').filter { it.isNotBlank() }
        val wanted = words(hint)
        if (wanted.isEmpty()) return places.firstOrNull()
        return places.firstOrNull { p ->
            val has = listOfNotNull(p.region, p.country, p.countryCode).flatMap(::words)
            wanted.all { want -> has.any { it.startsWith(want) } }
        }
    }

    /**
     * Finds the city [typed] and prints its forecast. With [keep] it becomes the city that `weather`
     * alone reports, and the other cities of that name are offered in case it is the wrong one.
     */
    private fun lookUp(w: Weather, typed: String, keep: Boolean) {
        val name = typed.substringBefore(',').trim()
        val hint = typed.substringAfter(',', "")
        if (name.length < 2) {
            print("weather: '${typed.trim()}' is too short to look up", Style.ERR)
            return
        }
        val ticket = wait("weather", "asking ${w.source}")
        w.find(name) { places, problem ->
            if (waitingFor != ticket) return@find
            val match = places?.let { choose(it, hint) }
            if (match == null) {
                answered(ticket)
                print(if (places == null) "weather: ${problem ?: "no answer"}" else "weather: no city called '${typed.trim()}'", Style.ERR)
            } else {
                // Namesakes first: a search for oslo also turns up oslob.
                val others = if (!keep) emptyList() else places.filter { it.label != match.label }.distinctBy { it.label }
                    .sortedBy { !it.name.equals(match.name, ignoreCase = true) }.take(3)
                report(w, match, ticket) {
                    if (!keep) return@report
                    // Only now: a command that failed, or was cancelled, has changed nothing.
                    savePlace(match)
                    print("This is now the city weather reports.", Style.DIM)
                    for (other in others) row(Span("not it? ", Style.DIM), Span(other.label, Style.APP, "weather set ${other.label}"))
                }
            }
            onChange()
        }
    }

    /**
     * Prints the forecast for [place], asking for a new one unless the last is fresh. [ticket] is the
     * wait this is the end of, or 0 to start one. [after] runs once the forecast is on screen.
     */
    private fun report(w: Weather, place: Place, ticket: Int = 0, after: () -> Unit = {}) {
        val imperial = imperial()
        val fresh = lastForecast?.takeIf { it.place == place && it.imperial == imperial && now() - it.at in 0 until FORECAST_FRESH_MS }
        if (fresh != null) {
            answered(ticket)
            showForecast(fresh, w.source)
            after()
            return
        }
        val waiting = if (ticket != 0) ticket else wait("weather", "asking ${w.source}")
        w.forecast(place, imperial) { forecast, problem ->
            if (!answered(waiting)) return@forecast
            if (forecast == null) {
                print("weather: ${problem ?: "no answer"}", Style.ERR)
            } else {
                val got = FreshForecast(place, imperial, now(), forecast)
                lastForecast = got
                showForecast(got, w.source)
                after()
            }
            onChange()
        }
    }

    // What arrives is checked where it is read, but a number that cannot be rounded must not end the app.
    private fun whole(v: Double): String = if (v.isNaN() || v.isInfinite()) "?" else v.roundToInt().toString()
    private fun degrees(v: Double) = whole(v) + "°"

    /**
     * The weather in one line of the login summary, for the city `weather` reports. With no city, or
     * after `weather login off`, there is no line and nothing is asked.
     *
     * It never holds the prompt up. A forecast newer than [LOGIN_FRESH_MS] is printed as it is.
     * Otherwise a line saying one is on its way is printed, and filled in when the answer comes. If
     * that line has left the screen by then, because the page was cleared or the terminal locked,
     * the answer is kept for the next login and nothing is shown.
     */
    private fun loginWeather() {
        val w = weather ?: return
        if (store.get(KEY_AT_LOGIN) == "off") return
        val place = savedPlace() ?: return
        val imperial = imperial()
        val known = lastForecast?.takeIf { it.place == place && it.imperial == imperial && now() - it.at in 0 until LOGIN_FRESH_MS }
        if (known != null) {
            add(brief(known))
            return
        }
        // Tappable like the line that takes its place, so the two are the same height.
        add(TextLine(listOf(Span("weather …", Style.DIM, "weather"))))
        val id = nextId - 1
        w.forecast(place, imperial) { forecast, _ ->
            val got = forecast?.let { FreshForecast(place, imperial, now(), it) }
            if (got != null) lastForecast = got
            // Why there is none is too long for the summary. A tap runs `weather`, which says.
            val line = if (got != null) brief(got) else TextLine(listOf(Span("weather unavailable", Style.DIM, "weather")))
            if (replace(id, line)) onChange()
        }
    }

    /**
     * A forecast in one line: the weather now, today's high and low, and the chance of rain or snow
     * if that is worth saying. What does not fit on the screen is left off from the end. A tap
     * anywhere on it runs `weather`.
     */
    private fun brief(got: FreshForecast): TextLine {
        val f = got.forecast
        val parts = ArrayList<String>()
        parts += degrees(f.temperature) + (if (got.imperial) "F " else "C ") + describeWeather(f.code)
        f.days.firstOrNull()?.let { today ->
            parts += degrees(today.high) + "/" + degrees(today.low)
            val wet = today.wet
            if (wet != null && wet >= WET_WORTH_SAYING) parts += (if (today.code in SNOW_CODES) "snow" else "rain") + " $wet%"
        }
        while (parts.size > 1 && parts.sumOf { it.length } + 3 * (parts.size - 1) > columns) parts.removeAt(parts.size - 1)
        val spans = arrayListOf(Span(parts[0], Style.APP, "weather"))
        if (parts.size > 1) spans += Span(parts.drop(1).joinToString("") { " · $it" }, Style.DIM, "weather")
        return TextLine(spans)
    }

    private fun weatherAtLogin(word: String?) {
        when (val choice = word?.lowercase()) {
            null -> choices(listOf("on", "off"), if (store.get(KEY_AT_LOGIN) == "off") "off" else "on", "weather login")
            "on", "off" -> {
                store.put(KEY_AT_LOGIN, choice)
                print("weather at login $choice")
            }
            else -> print("usage: weather login [on|off]", Style.ERR)
        }
    }

    private fun showForecast(got: FreshForecast, source: String) {
        val f = got.forecast
        print(got.place.label, Style.ACCENT)
        row(Span(degrees(f.temperature) + if (got.imperial) "F" else "C"), Span("  " + describeWeather(f.code)))
        print(
            "feels ${degrees(f.feelsLike)} · wind ${whole(f.wind)} ${if (got.imperial) "mph" else "km/h"} · humidity ${f.humidity}%",
            Style.DIM,
        )
        if (f.days.isNotEmpty()) print()
        f.days.forEachIndexed { i, day ->
            val wet = day.wet
            row(
                Span((if (i == 0) "today" else weekdayOf(day.date)).padEnd(6), Style.DIM),
                Span(degrees(day.high).padStart(4)),
                Span(degrees(day.low).padStart(5), Style.DIM),
                Span("  " + describeWeather(day.code).padEnd(13)),
                Span(if (wet == null) "" else "$wet%".padStart(4), if (wet != null && wet >= 50) Style.APP else Style.DIM),
            )
        }
        print("$source · ${Fmt.hhmm(got.at)}", Style.DIM)
    }

    private fun weatherUnits(word: String?) {
        val unit = word?.lowercase()
        when (unit) {
            null -> choices(listOf("c", "f"), if (imperial()) "f" else "c", "weather units")
            "c", "f" -> {
                store.put(KEY_UNITS, unit)
                print(if (unit == "f") "weather in °F and mph" else "weather in °C and km/h")
            }
            else -> print("usage: weather units [c|f]", Style.ERR)
        }
    }

    /* ---------------- screen time ---------------- */

    /** Midnight at the start of the day [daysAgo] days before today, by the phone's own calendar. */
    private fun dayStart(daysAgo: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now()
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    /** Whether the phone's log of its screen may be read. null on a phone that keeps none. */
    private fun usageAccess(): Boolean? = phone.setup().firstOrNull { it.key == "usage" }?.ok

    private fun onScreen(ms: Long): String = Fmt.span(ms / 60_000)

    /** `screen`: the last [SCREEN_DAYS] days, as far back as the phone's log goes. */
    private fun screenTime() {
        when (usageAccess()) {
            null -> {
                print("screen: this phone keeps no log of it", Style.ERR)
                return
            }
            false -> {
                needs("usage", "screen: usage access is off")
                return
            }
            true -> {}
        }
        val days = (SCREEN_DAYS - 1 downTo 0).map(::dayStart)
        val ticket = wait("screen", "reading the phone's log")
        // From a day further back than is shown: that says how things stood when the first day began,
        // and whether the log holds all of that day or starts part way through it.
        phone.screenLog(dayStart(SCREEN_DAYS)) { log ->
            if (!answered(ticket)) return@screenLog
            if (log == null) print("screen: the phone's log could not be read", Style.ERR) else showScreenTime(log, days)
            onChange()
        }
    }

    private fun showScreenTime(log: ScreenLog, days: List<Long>) {
        val all = tallyScreen(log, days, now())
        // Only the days the log reaches. Today is always one of them.
        val shown = all.filterIndexed { i, _ -> i == all.lastIndex || days[i + 1] > log.since }
        val most = shown.maxOf { it.screenMs }.coerceAtLeast(1)
        shown.forEachIndexed { i, day ->
            val label = if (i == shown.lastIndex) "today" else Fmt.weekday(day.start).lowercase()
            add(Gauge(label, (day.screenMs * 100 / most).toInt(), onScreen(day.screenMs).padStart(7) + day.unlocks.toString().padStart(5)))
        }
        // An average needs whole days: not today, and not the day the log starts part way through.
        val whole = shown.dropLast(1).filter { it.start >= log.since }
        if (whole.isEmpty()) print("time on screen, and unlocks", Style.DIM)
        else {
            val time = whole.sumOf { it.screenMs } / whole.size
            val unlocks = (whole.sumOf { it.unlocks } + whole.size / 2) / whole.size
            print("average: ${onScreen(time)} on screen, ${unlockCount(unlocks)} a day", Style.DIM)
        }
    }

    private fun unlockCount(n: Int): String = if (n == 1) "1 unlock" else "$n unlocks"

    /**
     * Today's screen time and unlocks in one line of the login summary. There is no line until the
     * phone's log may be read. Like the weather's, it is printed as on its way and filled in when
     * the log has been read, so the prompt does not wait for it.
     */
    private fun loginScreenTime() {
        if (usageAccess() != true) return
        add(TextLine(listOf(Span("screen time …", Style.DIM, "screen"))))
        val id = nextId - 1
        val today = listOf(dayStart(0))
        phone.screenLog(today[0]) { log ->
            val line = if (log == null) TextLine(listOf(Span("screen time unavailable", Style.DIM, "screen")))
            else tallyScreen(log, today, now())[0].let { use ->
                TextLine(
                    listOf(
                        Span("${onScreen(use.screenMs)} on screen", Style.APP, "screen"),
                        Span(" · ${unlockCount(use.unlocks)}", Style.DIM, "screen"),
                    ),
                )
            }
            if (replace(id, line)) onChange()
        }
    }

    private fun def(name: String, group: String?, usage: String, desc: String, run: (args: List<String>, rest: String) -> Unit) {
        commands[name] = Command(name, group, usage, desc, run)
    }

    private fun saveAliases() = store.put(KEY_ALIASES, aliases.entries.joinToString("\n") { "${it.key}=${it.value}" })

    private fun defineCommands() {
        def("ls", "apps", "ls [-l] [text]", "list apps") { args, _ ->
            val long = "-l" in args
            val q = args.filter { !it.startsWith("-") }.joinToString(" ").lowercase()
            val all = phone.apps()
            val list = if (q.isEmpty()) all else all.filter { q in it.name || it.category == q }
            if (list.isEmpty()) {
                print(if (q.isEmpty()) "ls: no apps found" else "ls: no apps match '$q'", Style.ERR)
            } else if (!long) {
                add(AppGrid(list.map { it.name }))
            } else {
                print("total ${list.size}", Style.DIM)
                val width = (list.maxOf { it.name.length } + 2).coerceAtMost(26)
                list.forEach {
                    val gap = " ".repeat((width - it.name.length).coerceAtLeast(2))
                    row(Span(it.name, Style.APP, "open ${it.name}"), Span(gap + it.category, Style.DIM))
                }
            }
        }
        def("open", "apps", "open <app>", "launch an app") { args, rest ->
            if (args.isEmpty()) print("usage: open <app>", Style.ERR) else oneApp("open", rest) { launch(it) }
        }
        def("info", "apps", "info <app>", "app info page") { args, rest ->
            if (args.isEmpty()) print("usage: info <app>", Style.ERR)
            else oneApp("info", rest) {
                if (phone.appInfo(it)) print("app info: ${it.name}", Style.DIM)
                else print("info: could not open the page for ${it.name}", Style.ERR)
            }
        }

        def("notifs", "phone", "notifs [clear]", "notifications") { args, _ ->
            val list = phone.notifications()
            if (list == null) {
                needs("notifs", "notifs: notification access is off")
            } else if (args.firstOrNull() == "clear") {
                val n = phone.clearNotifications()
                print(if (n > 0) "cleared $n" else "nothing to clear", Style.DIM)
            } else if (args.isNotEmpty()) {
                print("usage: notifs [clear]", Style.ERR)
            } else if (list.isEmpty()) {
                print("no notifications", Style.DIM)
            } else {
                list.forEach { n ->
                    row(Span(n.app, Style.APP, "open ${n.app}"), Span("  ${Fmt.age(n.ageMinutes)}", Style.DIM))
                    val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
                        .replace(Regex("\\s+"), " ").let { if (it.length > 160) it.take(159) + "…" else it }
                    if (body.isNotEmpty()) print("  $body")
                }
            }
        }
        def("call", "phone", "call <name>", "start a call") { args, rest ->
            if (args.isEmpty()) {
                print("usage: call <name or number>", Style.ERR)
            } else if (NUMBER.matches(rest)) {
                dial(rest, rest)
            } else {
                val all = phone.contacts()
                if (all == null) needs("contacts", "call: contacts access is off")
                else {
                    val found = matchContacts(all, rest)
                    when {
                        found.isEmpty() -> print("call: no contact matches '$rest'", Style.ERR)
                        found.size > 1 -> {
                            print("call: '$rest' matches ${found.size} numbers", Style.DIM)
                            listContacts("call", found)
                        }
                        else -> dial(found[0].number, "${found[0].name} (${found[0].number})")
                    }
                }
            }
        }
        def("sms", "phone", "sms <name> <msg>", "write a text") { args, rest ->
            if (args.size < 2) {
                print("usage: sms <name or number> <message>", Style.ERR)
            } else if (NUMBER.matches(args[0])) {
                text(args[0], args[0], afterFirst(rest))
            } else {
                val all = phone.contacts()
                if (all == null) needs("contacts", "sms: contacts access is off")
                else {
                    // the name may be more than one word: take the longest leading run that names one contact
                    val words = rest.trim().split(Regex("\\s+"))
                    var sent = false
                    var ambiguous: List<Contact> = emptyList()
                    for (k in minOf(3, words.size - 1) downTo 1) {
                        val found = matchContacts(all, words.take(k).joinToString(" "))
                        val message = words.drop(k).joinToString(" ")
                        if (found.size == 1) {
                            text(found[0].number, "${found[0].name} (${found[0].number})", message)
                            sent = true
                            break
                        }
                        if (found.size > 1 && ambiguous.isEmpty()) ambiguous = found
                    }
                    if (!sent && ambiguous.isNotEmpty()) {
                        print("sms: '${words[0]}' matches ${ambiguous.size} numbers", Style.DIM)
                        listContacts("sms", ambiguous, " " + words.drop(1).joinToString(" "))
                    } else if (!sent) {
                        print("sms: no contact matches '${words[0]}'", Style.ERR)
                    }
                }
            }
        }
        def("torch", "phone", "torch [on|off]", "flashlight") { args, _ ->
            val current = phone.torch()
            val want = args.firstOrNull()?.lowercase()
            if (current == null) print("torch: this phone has no flashlight", Style.ERR)
            else if (want != null && want != "on" && want != "off") print("torch: expected on or off", Style.ERR)
            else {
                val target = if (want == null) !current else want == "on"
                if (phone.setTorch(target)) print("torch ${if (target) "on" else "off"}")
                else print("torch: the camera is busy", Style.ERR)
            }
        }
        def("wifi", "phone", "wifi", "wi-fi panel") { _, _ ->
            print("wifi is ${if (phone.wifiOn()) "on" else "off"}")
            if (phone.openPanel(Panel.WIFI)) print("Android only switches it from its own panel. Opening it.", Style.DIM)
            else print("wifi: could not open the panel", Style.ERR)
        }
        def("bt", "phone", "bt", "bluetooth panel") { _, _ ->
            if (phone.openPanel(Panel.BLUETOOTH)) print("opening bluetooth settings", Style.DIM)
            else print("bt: could not open bluetooth settings", Style.ERR)
        }
        def("vol", "phone", "vol [0-100]", "media volume") { args, _ ->
            val n = level("vol", args)
            if (n != -2) {
                if (n >= 0) phone.setVolume(n)
                val v = phone.volume()
                print("volume ${v.toString().padStart(3)}  ${Fmt.bar(v)}")
            }
        }
        def("bright", "phone", "bright [0-100]", "brightness") { args, _ ->
            val n = level("bright", args)
            if (n != -2) {
                if (n >= 0 && !phone.setBrightness(n)) {
                    needs("bright", "bright: not allowed to change system settings")
                } else {
                    val v = if (n >= 0) n else phone.brightness()
                    print("brightness ${v.toString().padStart(3)}  ${Fmt.bar(v)}")
                }
            }
        }
        def("battery", "phone", "battery", "charge level") { _, _ ->
            val b = phone.battery()
            print("${b.percent}%  ${if (b.charging) "charging" else "on battery"}")
        }
        def("screen", "phone", "screen", "screen time") { args, _ ->
            if (args.isNotEmpty()) print("usage: screen", Style.ERR) else screenTime()
        }
        def("np", "phone", "np [next|prev]", "now playing") { args, _ ->
            val word = args.firstOrNull()?.lowercase()
            val key = when (word) {
                null -> null
                "next" -> MediaKey.NEXT
                "prev", "previous" -> MediaKey.PREVIOUS
                "pause", "stop" -> MediaKey.PAUSE
                "play" -> MediaKey.PLAY
                else -> {
                    print("usage: np [pause|play|next|prev]", Style.ERR)
                    return@def
                }
            }
            val track = phone.nowPlaying()
            when {
                // Android only shows the media player to apps that may read notifications
                phone.notifications() == null -> needs("notifs", "np: notification access is off")
                track == null -> print("np: nothing is playing", Style.DIM)
                key == null -> add(NowPlaying(track))
                phone.media(key) -> print(
                    when (key) {
                        MediaKey.NEXT -> "next track"
                        MediaKey.PREVIOUS -> "previous track"
                        MediaKey.PAUSE -> "paused"
                        MediaKey.PLAY -> "playing"
                    },
                    Style.DIM,
                )
                else -> print("np: the player did not answer", Style.ERR)
            }
        }

        def("date", "system", "date", "date and time") { _, _ -> print(Fmt.full(now())) }
        def("cal", "system", "cal [mon] [year]", "month calendar") { args, _ ->
            val today = Calendar.getInstance().apply { timeInMillis = now() }
            val word = args.getOrNull(0)?.lowercase()
            val month = when {
                word == null -> today.get(Calendar.MONTH)
                word.toIntOrNull() != null -> (word.toInt() - 1).takeIf { it in 0..11 }
                else -> Fmt.MONTH_NAMES.indexOfFirst { word.length >= 3 && it.lowercase().startsWith(word) }.takeIf { it >= 0 }
            }
            val year = if (args.size < 2) today.get(Calendar.YEAR) else args[1].toIntOrNull()?.takeIf { it in 1..9999 }
            if (args.size > 2) print("usage: cal [month] [year]", Style.ERR)
            else if (month == null) print("cal: no month '${args[0]}'", Style.ERR)
            else if (year == null) print("cal: no year '${args[1]}'", Style.ERR)
            else calendar(month, year, today)
        }
        def("weather", "system", "weather [city]", "forecast") { args, rest ->
            val w = weather
            val first = args.firstOrNull()?.lowercase()
            when {
                w == null -> print("weather: not available here", Style.ERR)
                first == "units" -> if (args.size > 2) print("usage: weather units [c|f]", Style.ERR) else weatherUnits(args.getOrNull(1))
                first == "login" -> if (args.size > 2) print("usage: weather login [on|off]", Style.ERR) else weatherAtLogin(args.getOrNull(1))
                first == "set" -> {
                    val city = afterFirst(rest)
                    if (city.isBlank()) print("usage: weather set <city>", Style.ERR) else lookUp(w, city, keep = true)
                }
                first == null -> {
                    val place = savedPlace()
                    if (place != null) report(w, place)
                    else {
                        print("No city is set yet. Set one:", Style.DIM)
                        row(Span("weather set ", Style.APP, fill = "weather set "), Span("<city>", Style.DIM))
                    }
                }
                // Another city, just this once. The first city ever asked about is remembered.
                else -> lookUp(w, rest, keep = savedPlace() == null)
            }
        }
        def("fetch", "system", "fetch", "system card") { _, _ ->
            val sys = phone.system()
            val battery = phone.battery()
            fun fact(name: String, value: String) = TextLine(listOf(Span(name.padEnd(8), Style.DIM), Span(value)))
            add(
                Fetch(
                    listOf(
                        TextLine(listOf(Span("phone@tty1", Style.ACCENT))),
                        fact("os", sys.os),
                        fact("device", sys.device),
                        fact("up", Fmt.span(sys.uptimeMinutes)),
                        fact("apps", phone.apps().size.toString()),
                        fact("batt", "${battery.percent}%" + if (battery.charging) " charging" else ""),
                        fact("disk", "${sys.diskUsedGb} / ${sys.diskTotalGb} GB"),
                    ),
                ),
            )
        }
        def("theme", "system", "theme [name]", "color theme") { args, _ ->
            val t = args.firstOrNull()?.lowercase()
            if (t == null) choices(THEMES, theme, "theme")
            else if (t !in THEMES) {
                print("theme: no theme '${args[0]}'. Try one of", Style.ERR)
                choices(THEMES, theme, "theme")
            } else {
                theme = t
                store.put(KEY_THEME, t)
                print("theme $t")
            }
        }
        def("saver", "system", "saver [name]", "lock screensaver") { args, _ ->
            val word = args.firstOrNull()?.lowercase()
            when {
                word == null -> {
                    print(if (saver == SAVER_OFF) "No screensaver." else "After 30s idle on the lock screen,", Style.DIM)
                    if (saver != SAVER_OFF) print("while charging, for up to 10 min.", Style.DIM)
                    choices(SAVER_CHOICES, saver, "saver")
                    if (saver != SAVER_OFF) row(Span("see it now: ", Style.DIM), Span("saver show", Style.APP, "saver show"))
                }
                word == "show" || word == "preview" ->
                    if (saver == SAVER_OFF) print("saver: it is off. Pick one with saver <name>", Style.ERR)
                    else cue(Cue.SAVER)
                word in SAVER_CHOICES -> {
                    saver = word
                    store.put(KEY_SAVER, word)
                    if (word == SAVER_OFF) print("saver off")
                    else row(Span("saver $word  "), Span("see it now", Style.APP, "saver show"))
                }
                else -> {
                    print("saver: no screensaver '${args[0]}'. Try one of", Style.ERR)
                    choices(SAVER_CHOICES, saver, "saver")
                }
            }
        }
        def("fx", "system", "fx [on|off]", "animations") { args, _ ->
            when (val word = args.firstOrNull()?.lowercase()) {
                null -> print("animations are ${if (motion) "on" else "off"}")
                "on", "off" -> {
                    motion = word == "on"
                    store.put(KEY_FX, word)
                    print("animations $word")
                }
                else -> print("fx: expected on or off", Style.ERR)
            }
        }
        def("alias", "system", "alias [n=cmd]", "shortcuts") { _, rest ->
            val m = Regex("^([A-Za-z][\\w-]*)=(.*)$").find(rest)
            val value = m?.groupValues?.get(2)?.trim()?.let {
                if (it.length >= 2 && (it[0] == '"' || it[0] == '\'') && it.last() == it[0]) it.substring(1, it.length - 1).trim() else it
            }.orEmpty()
            if (rest.isEmpty()) {
                if (aliases.isEmpty()) print("no aliases. Add one: alias s=open spotify", Style.DIM)
                else aliases.keys.sorted().forEach { print("$it='${aliases[it]}'") }
            } else if (m == null || value.isEmpty()) {
                print("usage: alias name=command", Style.ERR)
            } else {
                val name = m.groupValues[1].lowercase()
                if (name in commands) print("alias: '$name' is a built-in command", Style.ERR)
                else {
                    aliases[name] = value
                    saveAliases()
                    print("$name='$value'")
                }
            }
        }
        def("unalias", null, "unalias <name>", "remove a shortcut") { args, _ ->
            val name = args.firstOrNull()?.lowercase().orEmpty()
            if (aliases.remove(name) == null) print("unalias: no alias '$name'", Style.ERR) else saveAliases()
        }
        def("history", "system", "history", "past commands") { _, _ ->
            val from = maxOf(0, hist.size - 20)
            for (i in from until hist.size) print((i + 1).toString().padStart(3) + "  " + hist[i])
        }
        def("passwd", "system", "passwd [-d]", "set password") { args, _ ->
            val remove = args.firstOrNull() == "-d"
            val had = vault.isSet
            fun choose() = ask("new password: ") { a ->
                if (a.isEmpty()) print("passwd: password unchanged", Style.ERR)
                else ask("retype new password: ") { b ->
                    if (a != b) print("passwd: passwords do not match", Style.ERR)
                    else {
                        vault.set(a)
                        print(if (had) "password updated" else "password set. The phone now locks here.", Style.OK)
                    }
                }
            }
            if (!had) {
                if (remove) print("passwd: no password is set", Style.ERR) else choose()
            } else ask("current password: ") { current ->
                if (!vault.matches(current)) print("passwd: wrong password", Style.ERR)
                else if (remove) {
                    vault.set(null)
                    print("password removed. The phone no longer locks here.", Style.OK)
                } else choose()
            }
        }
        def("setup", "system", "setup [name]", "phone access") { args, _ ->
            val items = phone.setup()
            val key = args.firstOrNull()?.lowercase()
            if (key == null) {
                items.forEach {
                    val run = "setup ${it.key}"
                    row(
                        Span(if (it.ok) "on " else "off", if (it.ok) Style.OK else Style.ERR, run),
                        Span("  " + it.key.padEnd(10), Style.APP, run),
                        Span(it.label, Style.DIM, run),
                    )
                }
                print("Tap a line, or run setup <name>.", Style.DIM)
            } else if (items.none { it.key == key }) {
                print("setup: no item '${args[0]}'. Try ${items.joinToString(", ") { it.key }}", Style.ERR)
            } else if (phone.fix(key)) {
                print("opening the setting for $key", Style.DIM)
            } else {
                print("$key is already on", Style.DIM)
            }
        }
        def("ssh", "system", "ssh <user@host>", "remote shell") { args, _ ->
            when (args.firstOrNull()?.lowercase()) {
                null -> {
                    print("usage: ssh [user@]host [-p port]", Style.DIM)
                    choices(hosts(), "", "ssh")
                    row(Span("ssh key".padEnd(17), Style.APP, "ssh key"), Span("this phone's key", Style.DIM))
                    row(Span("ssh-copy-id".padEnd(17), Style.APP, fill = "ssh-copy-id "), Span("put it on a host", Style.DIM))
                    row(Span("ssh forget".padEnd(17), Style.APP, fill = "ssh forget "), Span("drop a host's key", Style.DIM))
                }
                "key" -> sshKey(copy = args.getOrNull(1) == "copy")
                "forget" -> {
                    val name = args.getOrNull(1)?.substringAfter('@')?.lowercase()
                    if (name == null) print("usage: ssh forget <host>", Style.ERR)
                    else if (store.get(KEY_HOST_KEY + name) == null) print("ssh: no saved key for $name", Style.ERR)
                    else {
                        store.put(KEY_HOST_KEY + name, null)
                        print("forgot the key of $name")
                    }
                }
                else -> sshTo("ssh", args)
            }
        }
        def("ssh-copy-id", null, "ssh-copy-id <user@host>", "install this phone's key") { args, _ ->
            val key = remote?.publicKey()
            if (args.isEmpty()) print("usage: ssh-copy-id [user@]host", Style.ERR)
            else if (remote == null) print("ssh-copy-id: not available here", Style.ERR)
            else if (key == null) print("ssh-copy-id: could not make a key", Style.ERR)
            else sshTo("ssh-copy-id", args, key)
        }
        def("clear", "system", "clear", "clear the screen") { _, _ -> page(emptyList()) }
        def("lock", "system", "lock", "lock the phone") { _, _ ->
            if (vault.isSet) {
                cue(Cue.POWER_OFF)
                showLock()
            } else print("lock: set a password first with passwd", Style.ERR)
        }
        def("help", "system", "help", "this list") { _, _ ->
            for (group in listOf("apps", "phone", "system")) {
                print(group, Style.ACCENT)
                commands.values.filter { it.group == group }.forEach { row(Span(it.usage.padEnd(17)), Span(it.desc, Style.DIM)) }
                if (group == "apps") row(Span("<app>".padEnd(17)), Span("same, name alone", Style.DIM))
            }
        }
        def("exit", null, "", "") { _, _ -> commands.getValue("lock").run(emptyList(), "") }
        def("logout", null, "", "") { _, _ -> commands.getValue("lock").run(emptyList(), "") }
        def("?", null, "", "") { _, _ -> commands.getValue("help").run(emptyList(), "") }
    }

    /** Parses an optional 0-100 argument: the number, -1 if absent, -2 (after printing why) if invalid. */
    private fun level(cmd: String, args: List<String>): Int {
        val raw = args.firstOrNull() ?: return -1
        val n = raw.toIntOrNull()
        if (n == null || n !in 0..100) {
            print("$cmd: expected a number from 0 to 100", Style.ERR)
            return -2
        }
        return n
    }

    /** Prints a month the way `cal` does, in the week order of the phone's region, with today marked. */
    private fun calendar(month: Int, year: Int, today: Calendar) {
        val first = Calendar.getInstance().apply {
            clear()
            set(year, month, 1)
        }
        val weekStart = first.firstDayOfWeek
        val title = "${Fmt.MONTH_NAMES[month]} $year"
        print(" ".repeat(((20 - title.length) / 2).coerceAtLeast(0)) + title)
        val names = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        print((0 until 7).joinToString(" ") { names[(weekStart - 1 + it) % 7] }, Style.DIM)

        val offset = (first.get(Calendar.DAY_OF_WEEK) - weekStart + 7) % 7
        val isThisMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        var spans = ArrayList<Span>()
        if (offset > 0) spans += Span("   ".repeat(offset))
        for (day in 1..first.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val column = (offset + day - 1) % 7
            if (column == 0 && day > 1) {
                add(TextLine(spans))
                spans = ArrayList()
            }
            if (column > 0 && day > 1) spans += Span(" ")
            val marked = isThisMonth && day == today.get(Calendar.DAY_OF_MONTH)
            spans += Span(day.toString().padStart(2), if (marked) Style.MARK else Style.NORMAL)
        }
        add(TextLine(spans))
    }

    private fun dial(number: String, label: String) {
        if (phone.call(number)) print("calling $label", Style.DIM)
        else print("dialer opened for $label", Style.DIM)
    }

    private fun text(number: String, label: String, message: String) {
        if (phone.sms(number, message)) {
            print("to $label: $message", Style.DIM)
            print("Opened in your messages app. Press send there.", Style.DIM)
        } else print("sms: no messages app could take it", Style.ERR)
    }
}
