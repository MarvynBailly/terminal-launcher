package com.terminallauncher.shell

/**
 * The terminal session: the lock screen, the prompt, and every command.
 *
 * It owns the lines on screen and the current prompt, and it talks to the device only through [Phone].
 * The UI renders [lines] and [prompt], sends back what the user typed with [submit], and is told to
 * redraw through [onChange]. All calls are expected on one thread.
 */
class Shell(
    private val phone: Phone,
    private val store: Store,
    private val vault: Vault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        val THEMES = listOf("default", "green", "amber", "eink")

        private const val MAX_LINES = 400
        private const val MAX_HISTORY = 100
        private const val MAX_RECENTS = 6
        private const val KEY_THEME = "theme"
        private const val KEY_ALIASES = "aliases"
        private const val KEY_RECENTS = "recents"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val KEY_SEEN = "seen"
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

    /** null while the terminal takes no input (the lock screen during a lockout). */
    var prompt: Prompt? = null
        private set
    var locked: Boolean = false
        private set
    var theme: String = store.get(KEY_THEME)?.takeIf { it in THEMES } ?: THEMES[0]
        private set
    var onChange: () -> Unit = {}

    val history: List<String> get() = hist

    private val hist = ArrayList<String>()
    private val commands = LinkedHashMap<String, Command>()
    private val aliases = LinkedHashMap<String, String>()
    private var recents: List<String> = store.get(KEY_RECENTS)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    private var pending: ((String) -> Unit)? = null
    private var fails = 0

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
        val p = prompt ?: return
        when (p.kind) {
            PromptKind.LOGIN -> login(input)
            PromptKind.ASK -> {
                val then = pending
                pending = null
                prompt = SHELL_PROMPT
                echo(p, "")
                then?.invoke(input)
            }
            PromptKind.SHELL -> {
                echo(p, input)
                runLine(input)
            }
        }
        onChange()
    }

    /** A tap on a span or an app name: runs the command as if it had been typed. */
    fun runTapped(command: String) {
        if (prompt?.kind == PromptKind.SHELL) submit(command)
    }

    /** Ctrl+C. */
    fun cancel(input: String) {
        val p = prompt ?: return
        when (p.kind) {
            PromptKind.LOGIN -> return
            PromptKind.ASK -> {
                echo(p, "^C")
                pending = null
                prompt = SHELL_PROMPT
            }
            PromptKind.SHELL -> echo(p, "$input^C")
        }
        onChange()
    }

    /** Ctrl+L. */
    fun clear() {
        if (locked) return
        lines = emptyList()
        onChange()
    }

    /**
     * Locks the terminal and wipes the scrollback. Returns false, and does nothing, when no password is
     * set, because there would be no way back in.
     */
    fun lock(): Boolean {
        if (!vault.isSet) return false
        if (!locked) {
            showLock()
            onChange()
        }
        return true
    }

    /** Call about once a second while visible: ends a lockout when its time is up. */
    fun tick() {
        if (locked && prompt == null && lines.none { it is Lockout && it.until > now() }) {
            showLock()
            onChange()
        }
    }

    /** Prints a line from outside the shell, such as the outcome of a permission dialog. */
    fun info(text: String) {
        if (locked) return
        print(text, Style.DIM)
        onChange()
    }

    /* ---------------- completion ---------------- */

    fun candidates(input: String): Candidates? {
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
            word == "torch" -> if (firstArg) lastWord(listOf("on", "off")) else none
            word == "notifs" -> if (firstArg) lastWord(listOf("clear")) else none
            word == "unalias" -> if (firstArg) lastWord(aliases.keys.toList()) else none
            word == "setup" -> if (firstArg) lastWord(phone.setup().map { it.key }) else none
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
    }

    private fun print(text: String = "", style: Style = Style.NORMAL) = add(TextLine(listOf(Span(text, style))))

    private fun row(vararg spans: Span) = add(TextLine(spans.toList()))

    private fun echo(p: Prompt, text: String) =
        row(Span(p.text, if (p.kind == PromptKind.SHELL) Style.ACCENT else Style.NORMAL), Span(text))

    /** An error that the user can fix by granting access, with a tappable way to do it. */
    private fun needs(key: String, message: String) {
        print(message, Style.ERR)
        row(Span("fix it: ", Style.DIM), Span("setup $key", Style.APP, "setup $key"))
    }

    /* ---------------- lock screen and login ---------------- */

    private fun showLock(note: String? = null) {
        locked = true
        pending = null
        lines = listOfNotNull(LockHeader, note?.let { TextLine(listOf(Span(it, Style.ERR))) })
        prompt = LOGIN_PROMPT
    }

    private fun login(password: String) {
        if (vault.matches(password)) {
            fails = 0
            openHome()
            return
        }
        fails++
        if (fails % 3 != 0) {
            showLock("Login incorrect")
            return
        }
        // every third miss costs a wait, and the waits get longer
        val seconds = when (fails / 3) { 1 -> 10; 2 -> 30; else -> 60 }
        showLock("Login incorrect")
        add(Lockout(now() + seconds * 1000L))
        prompt = null
    }

    private fun openHome() {
        locked = false
        pending = null
        prompt = SHELL_PROMPT
        lines = emptyList()
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
            print("Type help for commands.", Style.DIM)
        }
        print()
        store.put(KEY_LAST_LOGIN, now().toString())
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
                print("${toks[0]}: command not found", Style.ERR)
                nearest(name)?.let { print("did you mean '$it'?", Style.DIM) }
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
        print("opening ${app.name}", Style.DIM)
        if (!phone.launch(app)) {
            print("open: could not start ${app.name}", Style.ERR)
            return
        }
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

    private fun nearest(word: String): String? {
        val names = commands.values.filter { it.group != null }.map { it.name } + phone.apps().map { it.name }
        if (word.length >= 2) names.firstOrNull { it.startsWith(word) }?.let { return it }
        var best: String? = null
        var bestD = if (word.length <= 3) 2 else 3     // one slip in a short word, two in a longer one
        for (n in names) {
            val d = distance(word, n)
            if (d < bestD) {
                best = n
                bestD = d
            }
        }
        return best
    }

    /* ---------------- commands ---------------- */

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

        def("date", "system", "date", "date and time") { _, _ -> print(Fmt.full(now())) }
        def("theme", "system", "theme [name]", "color theme") { args, _ ->
            val t = args.firstOrNull()?.lowercase()
            if (t == null) print(THEMES.joinToString("  ") { if (it == theme) "[$it]" else it })
            else if (t !in THEMES) print("theme: no theme '${args[0]}'. Try ${THEMES.joinToString(", ")}", Style.ERR)
            else {
                theme = t
                store.put(KEY_THEME, t)
                print("theme $t")
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
        def("clear", "system", "clear", "clear the screen") { _, _ -> lines = emptyList() }
        def("lock", "system", "lock", "lock the phone") { _, _ ->
            if (vault.isSet) showLock() else print("lock: set a password first with passwd", Style.ERR)
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
