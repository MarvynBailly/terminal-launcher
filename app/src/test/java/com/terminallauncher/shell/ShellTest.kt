package com.terminallauncher.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

internal class FakePhone : Phone {
    val names = listOf(
        "browser", "calculator", "calendar", "camera", "clock", "contacts", "files", "firefox", "mail",
        "maps", "messages", "music", "notes", "phone", "photos", "play store", "settings", "signal",
        "spotify", "weather", "youtube",
    )
    val media = setOf("camera", "music", "photos", "spotify", "youtube")
    val launched = ArrayList<String>()
    val infoOpened = ArrayList<String>()
    val calls = ArrayList<String>()
    val texts = ArrayList<Pair<String, String>>()
    val panels = ArrayList<Panel>()
    val fixed = ArrayList<String>()
    var notifAccess = true
    var contactAccess = true
    var canWriteSettings = false
    var hasTorch = true
    var torchOn = false
    var vol = 60
    var bright = 100
    var notifList = mutableListOf(
        Notif("messages", "Mom", "are you coming on Sunday?", 2),
        Notif("mail", "", "Your order has shipped", 18),
    )

    override fun apps() = names.map { AppEntry(it, if (it in media) "media" else "-", "id:$it") }
    override fun launch(app: AppEntry): Boolean { launched += app.name; return true }
    override fun appInfo(app: AppEntry): Boolean { infoOpened += app.name; return true }
    override fun notifications(): List<Notif>? = if (notifAccess) notifList else null
    override fun clearNotifications(): Int { val n = notifList.size; notifList = mutableListOf(); return n }
    override fun contacts(): List<Contact>? = if (!contactAccess) null else listOf(
        Contact("mom", "555 0142"), Contact("alex", "555 0117"), Contact("john smith", "555 0199"),
        Contact("john doe", "555 0100"), Contact("john doe", "555 0101"),
    )
    override fun call(number: String): Boolean { calls += number; return true }
    override fun sms(number: String, body: String): Boolean { texts += number to body; return true }
    override fun torch(): Boolean? = if (hasTorch) torchOn else null
    override fun setTorch(on: Boolean): Boolean { torchOn = on; return true }
    override fun volume() = vol
    override fun setVolume(percent: Int) { vol = percent }
    override fun brightness() = bright
    override fun setBrightness(percent: Int): Boolean { if (!canWriteSettings) return false; bright = percent; return true }
    override fun battery() = Battery(82, false)
    override fun wifiOn() = true
    override fun system() = SystemInfo("android 14", "pixel 8", "1080x2400", 3 * 24 * 60 + 4 * 60 + 5, 41, 128)

    var track: Track? = null
    val keys = ArrayList<MediaKey>()
    override fun nowPlaying(): Track? = if (notifAccess) track else null
    override fun media(key: MediaKey): Boolean { keys += key; return track != null }
    override fun openPanel(panel: Panel): Boolean { panels += panel; return true }
    val copied = ArrayList<String>()
    override fun copy(text: String): Boolean { copied += text; return true }
    override fun setup() = listOfNotNull(
        SetupItem("home", "default home app", true),
        SetupItem("notifs", "read notifications", notifAccess),
        SetupItem("usage", "screen time and unlocks", usageAccess).takeIf { keepsScreenLog },
    )
    override fun fix(key: String): Boolean { fixed += key; return key != "home" }

    /** The phone's log of its screen. A test plays the phone by calling [logRead] with what it holds. */
    var usageAccess = false
    var keepsScreenLog = true
    val logAsked = ArrayList<Long>()
    var logRead: ((ScreenLog?) -> Unit)? = null
    override fun screenLog(from: Long, done: (ScreenLog?) -> Unit) { logAsked += from; logRead = done }

    /** Android's own lock screen, and the answer it is still waiting to give to an unlock request. */
    var androidLocked = false
    var unlockAnswer: ((Boolean) -> Unit)? = null
    override fun deviceLocked() = androidLocked
    override fun unlockDevice(done: (Boolean) -> Unit) { unlockAnswer = done }
}

internal class FakeStore : Store {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

internal class FakeVault(var password: String? = null) : Vault {
    override val isSet get() = password != null
    override fun matches(password: String) = this.password == password
    override fun set(password: String?) { this.password = password }
}

class ShellTest {
    private val phone = FakePhone()
    private val store = FakeStore()
    private val vault = FakeVault()
    private var time = 1_795_000_000_000L
    private var changes = 0

    private fun shell() = Shell(phone, store, vault) { time }.also { s -> s.onChange = { changes++ }; s.start() }

    /** 1_795_000_000_000 is Wednesday 18 November 2026, 11:06 UTC. Weeks start on Monday. */
    @Before fun fixedCalendar() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    private fun Shell.text(): String = lines.joinToString("\n") { line ->
        when (line) {
            is TextLine -> line.spans.joinToString("") { it.text }
            is AppGrid -> line.names.joinToString(" | ")
            LockHeader -> "<lock header>"
            is Lockout -> "<lockout>"
            is Gauge -> "${line.label} ${line.percent} ${line.note}"
            is Fetch -> line.rows.joinToString("\n") { row -> row.spans.joinToString("") { it.text } }
            is Launching -> "opening ${line.name}"
            is Note -> "${line.app}: ${line.text}"
            is NowPlaying -> "${line.track.title} by ${line.track.artist}"
        }
    }

    private fun Shell.run(vararg lines: String): Shell { lines.forEach { submit(it) }; return this }
    private fun Shell.lastGrid() = lines.last { it is AppGrid } as AppGrid
    private fun Shell.chips(input: String) = candidates(input)!!.items.map { it.text }

    /* ---------- first run, password, lock ---------- */

    @Test fun firstRunOpensUnlockedAndExplains() {
        val s = shell()
        assertFalse(s.locked)
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertTrue(s.text().contains("No password is set"))
        assertTrue(changes > 0)
    }

    @Test fun lockRefusesWithoutPassword() {
        val s = shell().run("lock")
        assertFalse(s.locked)
        assertTrue(s.text().contains("set a password first"))
        assertFalse(s.lock())
    }

    @Test fun passwdSetsPasswordThenLockAndLogin() {
        val s = shell().run("passwd")
        assertEquals(Prompt("new password: ", PromptKind.ASK, mask = true), s.prompt)
        s.run("hunter2")
        assertEquals("retype new password: ", s.prompt!!.text)
        s.run("hunter2")
        assertTrue(s.text().contains("password set"))
        assertFalse("the password must never be echoed", s.text().contains("hunter2"))
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)

        s.run("lock")
        assertTrue(s.locked)
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        assertTrue(s.prompt!!.mask)
        assertEquals("scrollback is wiped", "<lock header>", s.text())

        s.run("nope")
        assertTrue(s.locked)
        assertTrue(s.text().contains("Login incorrect"))
        s.run("hunter2")
        assertFalse(s.locked)
        assertTrue(s.text().contains("Last login"))
    }

    @Test fun passwdChecksRetypeAndCurrent() {
        val s = shell().run("passwd", "a", "b")
        assertTrue(s.text().contains("passwords do not match"))
        assertFalse(vault.isSet)
        s.run("passwd", "a", "a", "passwd", "wrong")
        assertTrue(s.text().contains("passwd: wrong password"))
        s.run("passwd", "a", "b2", "b2")
        assertTrue(s.text().contains("password updated"))
        assertTrue(vault.matches("b2"))
        s.run("passwd -d", "b2")
        assertFalse(vault.isSet)
    }

    @Test fun startsLockedWhenPasswordExists() {
        vault.password = "pw"
        val s = shell()
        assertTrue(s.locked)
        assertNull(s.candidates(""))
        s.run("ls")                       // typed at the password prompt: just a wrong password
        assertTrue(s.locked)
        assertTrue(phone.launched.isEmpty())
    }

    /* ---------- on top of Android's lock screen ---------- */

    private fun waitingForAndroid(): Shell {
        vault.password = "pw"
        phone.androidLocked = true
        return shell().run("pw")
    }

    @Test fun rightPasswordStillWaitsForAndroidsUnlock() {
        val s = waitingForAndroid()
        assertTrue("the terminal must not open on a locked phone", s.locked)
        assertNull(s.prompt)
        assertTrue(s.text().contains("unlock the phone"))
        assertNotNull(phone.unlockAnswer)
        s.run("ls")
        time += 5_000
        s.tick()
        assertTrue(s.locked)
        assertNull("still waiting, not back at the password", s.prompt)

        phone.androidLocked = false
        phone.unlockAnswer!!(true)
        assertFalse(s.locked)
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertFalse(s.text().contains("unlock the phone"))
    }

    @Test fun wrongPasswordNeverAsksAndroid() {
        vault.password = "pw"
        phone.androidLocked = true
        val s = shell().run("nope")
        assertNull(phone.unlockAnswer)
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
    }

    @Test fun backingOutOfAndroidsUnlockReturnsToThePassword() {
        val s = waitingForAndroid()
        phone.unlockAnswer!!(false)
        assertTrue(s.locked)
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        assertTrue(s.text().contains("Phone still locked"))
    }

    @Test fun androidUnlockedAnotherWayLetsTheLoginThrough() {
        val s = waitingForAndroid()
        phone.androidLocked = false
        s.tick()
        assertFalse(s.locked)
        s.run("lock")
        phone.unlockAnswer!!(false)             // the old request answering late must not matter
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        assertFalse(s.text().contains("Phone still locked"))
    }

    @Test fun screenOffWhileWaitingStartsOverAtThePassword() {
        val s = waitingForAndroid()
        assertTrue(s.lock())
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        phone.unlockAnswer!!(true)              // a late answer cannot open a terminal that locked again
        assertTrue(s.locked)
    }

    @Test fun threeWrongPasswordsLockOutThenRecover() {
        vault.password = "pw"
        val s = shell().run("x", "x")
        assertNotNull(s.prompt)
        s.run("x")
        assertNull("no input during a lockout", s.prompt)
        assertTrue(s.lines.any { it is Lockout })
        s.run("pw")
        assertTrue("input is ignored while locked out", s.locked)
        time += 5_000
        s.tick()
        assertNull(s.prompt)
        time += 6_000
        s.tick()
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        s.run("pw")
        assertFalse(s.locked)
    }

    @Test fun screenOffLockKeepsARunningLockout() {
        vault.password = "pw"
        val s = shell().run("x", "x", "x")
        assertTrue(s.lock())
        assertNull("locking again must not cancel the wait", s.prompt)
    }

    @Test fun cancelAbandonsAQuestion() {
        val s = shell().run("passwd")
        s.cancel("")
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        s.run("date")
        assertFalse(vault.isSet)
    }

    /* ---------- apps ---------- */

    @Test fun lsListsFiltersAndLongFormat() {
        val s = shell().run("ls")
        assertEquals(21, s.lastGrid().names.size)
        s.run("ls media")
        assertEquals(listOf("camera", "music", "photos", "spotify", "youtube"), s.lastGrid().names)
        s.run("ls -l cal")
        assertTrue(s.text().contains("total 2"))
        s.run("ll")
        assertTrue(s.text().contains("total 21"))
        s.run("ls zzz")
        assertTrue(s.text().contains("no apps match 'zzz'"))
    }

    @Test fun openByPrefixExactAndMultiWord() {
        val s = shell().run("open spo", "open Play Store", "open store")
        assertEquals(listOf("spotify", "play store", "play store"), phone.launched)
        s.run("open ca")
        assertTrue(s.text().contains("'ca' matches 3 apps"))
        assertEquals(listOf("calculator", "calendar", "camera"), s.lastGrid().names)
        s.run("open zzz")
        assertTrue(s.text().contains("no app matches 'zzz'"))
        assertEquals(3, phone.launched.size)
    }

    @Test fun bareAppNameLaunches() {
        val s = shell().run("camera", "spot", "play store")
        assertEquals(listOf("camera", "spotify", "play store"), phone.launched)
        s.run("ca")
        assertTrue(s.text().contains("'ca' matches 3 apps"))
        assertEquals(3, phone.launched.size)
    }

    @Test fun unknownCommandSuggests() {
        val s = shell().run("foo")
        assertTrue(s.text().contains("foo: command not found"))
        assertFalse(s.text().contains("did you mean"))
        s.run("opne")
        assertTrue(s.text().contains("did you mean open?"))
        assertTrue(phone.launched.isEmpty())
    }

    @Test fun infoOpensAppPage() {
        shell().run("info spot")
        assertEquals(listOf("spotify"), phone.infoOpened)
    }

    @Test fun tappedSpansRunCommands() {
        val s = shell().run("ls -l cal")
        val span = (s.lines.last() as TextLine).spans.first()
        assertEquals("open calendar", span.run)
        s.runTapped(span.run!!)
        assertEquals(listOf("calendar"), phone.launched)
        assertTrue(s.text().contains("$ open calendar"))
    }

    /* ---------- completion ---------- */

    @Test fun chipsForFirstWordMixCommandsAndApps() {
        val s = shell()
        assertEquals(listOf("call", "cal", "calculator", "calendar"), s.chips("cal"))
        assertEquals(ChipKind.CMD, s.candidates("cal")!!.items[1].kind)
        assertEquals(ChipKind.APP, s.candidates("cal")!!.items[2].kind)
        assertEquals(emptyList<String>(), s.chips(""))
    }

    @Test fun recentsBecomeTheEmptyPromptChipsAndPersist() {
        val s = shell().run("open maps", "open spotify", "open maps")
        assertEquals(listOf("maps", "spotify"), s.chips(""))
        assertEquals(listOf("maps", "spotify"), s.chips("open "))
        assertEquals(listOf("maps", "spotify"), Shell(phone, store, vault) { time }.also { it.start() }.chips(""))
    }

    @Test fun chipsForArguments() {
        val s = shell()
        assertEquals(listOf("spotify"), s.chips("open sp"))
        assertEquals(listOf("play store"), s.chips("open play s"))
        assertEquals(listOf("play store"), s.chips("play s"))
        assertEquals(listOf("mom"), s.chips("call m"))
        assertEquals(listOf("john smith", "john doe"), s.chips("sms jo"))
        assertEquals(emptyList<String>(), s.chips("sms mom jo"))
        assertEquals(listOf("green", "gruvbox"), s.chips("theme g"))
        assertEquals(listOf("rain"), s.chips("saver r"))
        assertEquals(listOf("pause", "play", "prev"), s.chips("np p"))
        assertEquals(listOf("on", "off"), s.chips("torch "))
        assertEquals(listOf("-l", "media"), s.chips("ls "))
        assertEquals(listOf("notifs"), s.chips("setup n"))
    }

    @Test fun pickingAnAppChipRunsItAndACommandChipFillsIt() {
        val s = shell()
        val c = s.candidates("open sp")!!
        assertEquals(Pick("open spotify", true), s.pick("open sp", c, c.items[0]))
        val d = s.candidates("the")!!
        assertEquals(Pick("theme ", false), s.pick("the", d, d.items[0]))
    }

    @Test fun tabCompletes() {
        val s = shell()
        assertEquals("calculator ", s.complete("calc"))
        assertNull(s.complete("cal"))
        assertEquals("open spotify ", s.complete("open sp"))
        assertEquals("pho", s.complete("ph"))          // phone, photos: extends to what they share
        assertNull(s.complete("ca"))
        assertNull(s.complete(""))
    }

    /* ---------- phone ---------- */

    @Test fun notifsListAndClear() {
        val s = shell().run("notifs")
        assertTrue(s.text().contains("messages  2m ago"))
        assertTrue(s.text().contains("  Mom: are you coming on Sunday?"))
        assertTrue(s.text().contains("  Your order has shipped"))
        s.run("notifs clear", "notifs")
        assertTrue(s.text().contains("cleared 2"))
        assertTrue(s.text().contains("no notifications"))
    }

    @Test fun notifsWithoutAccessPointsAtSetup() {
        phone.notifAccess = false
        val s = shell().run("notifs")
        assertTrue(s.text().contains("notification access is off"))
        val fix = (s.lines.last() as TextLine).spans.last()
        assertEquals("setup notifs", fix.run)
        s.runTapped(fix.run!!)
        assertEquals(listOf("notifs"), phone.fixed)
    }

    @Test fun callByNameNumberAndAmbiguity() {
        val s = shell().run("call mom", "call 555-1234", "call john smith")
        assertEquals(listOf("555 0142", "555-1234", "555 0199"), phone.calls)
        s.run("call john")
        assertTrue(s.text().contains("'john' matches 3 numbers"))
        assertEquals("call 5550101", (s.lines.last() as TextLine).spans.first().run)
        s.run("call nobody")
        assertTrue(s.text().contains("no contact matches 'nobody'"))
        assertEquals(3, phone.calls.size)
    }

    @Test fun smsFindsWhereTheNameEnds() {
        val s = shell().run("sms mom on my way", "sms john smith running late", "sms 5550000 hi there")
        assertEquals(
            listOf("555 0142" to "on my way", "555 0199" to "running late", "5550000" to "hi there"),
            phone.texts,
        )
        s.run("sms john hello")
        assertTrue(s.text().contains("'john' matches 3 numbers"))
        assertEquals("sms 5550199 hello", (s.lines[s.lines.size - 3] as TextLine).spans.first().run)
        s.run("sms bob hi")
        assertTrue(s.text().contains("no contact matches 'bob'"))
        s.run("sms mom")
        assertTrue(s.text().contains("usage: sms"))
        assertEquals(3, phone.texts.size)
    }

    @Test fun contactsWithoutAccessPointAtSetup() {
        phone.contactAccess = false
        val s = shell().run("call mom")
        assertTrue(s.text().contains("contacts access is off"))
        assertTrue(phone.calls.isEmpty())
        assertEquals(emptyList<String>(), s.chips("call m"))
    }

    @Test fun torchVolumeBrightness() {
        val s = shell().run("torch")
        assertTrue(phone.torchOn)
        s.run("torch off", "torch maybe")
        assertFalse(phone.torchOn)
        assertTrue(s.text().contains("expected on or off"))

        s.run("vol 30")
        assertEquals(30, phone.vol)
        assertTrue(s.text().contains("volume  30  [###-------]"))
        s.run("vol 130", "vol x")
        assertEquals(30, phone.vol)
        assertTrue(s.text().contains("expected a number from 0 to 100"))

        s.run("bright 40")
        assertEquals(100, phone.bright)
        assertTrue(s.text().contains("not allowed to change system settings"))
        phone.canWriteSettings = true
        s.run("bright 40")
        assertEquals(40, phone.bright)
        assertTrue(s.text().contains("brightness  40  [####------]"))

        phone.hasTorch = false
        s.run("torch")
        assertTrue(s.text().contains("no flashlight"))
    }

    @Test fun wifiAndBluetoothOpenPanels() {
        shell().run("wifi", "bt")
        assertEquals(listOf(Panel.WIFI, Panel.BLUETOOTH), phone.panels)
    }

    /* ---------- system ---------- */

    @Test fun aliasesExpandPersistAndCannotShadowBuiltins() {
        val s = shell().run("alias s=open spotify", "s", "alias m=\"open maps\"", "m")
        assertEquals(listOf("spotify", "maps"), phone.launched)
        s.run("alias ls=x")
        assertTrue(s.text().contains("'ls' is a built-in command"))
        val again = Shell(phone, store, vault) { time }.also { it.start() }.run("s")
        assertEquals(3, phone.launched.size)
        again.run("unalias s", "s")
        assertEquals(3, phone.launched.size)
    }

    @Test fun themeSwitchesAndPersists() {
        val s = shell().run("theme eink")
        assertEquals("eink", s.theme)
        assertEquals("eink", Shell(phone, store, vault) { time }.theme)
        s.run("theme nope")
        assertTrue(s.text().contains("no theme 'nope'"))
        assertEquals("eink", s.theme)
    }

    @Test fun historySkipsRepeatsAndBlankLines() {
        val s = shell().run("date", "date", "  ", "battery")
        assertEquals(listOf("date", "battery"), s.history)
    }

    @Test fun setupListsAndFixes() {
        phone.notifAccess = false
        val s = shell().run("setup")
        assertTrue(s.text().contains("on   home      default home app"))
        assertTrue(s.text().contains("off  notifs    read notifications"))
        s.run("setup notifs", "setup home", "setup nope")
        assertEquals(listOf("notifs", "home"), phone.fixed)
        assertTrue(s.text().contains("home is already on"))
        assertTrue(s.text().contains("no item 'nope'"))
    }

    @Test fun helpFitsAPhoneScreen() {
        val s = shell().run("clear", "help")
        assertTrue(s.text().contains("lock the phone"))
        val widest = s.lines.filterIsInstance<TextLine>().maxOf { l -> l.spans.sumOf { it.text.length } }
        assertTrue("help line is $widest columns wide", widest <= 36)
    }

    /* ---------- waking, the boot log, the login summary ---------- */

    private fun asleep(): Shell {
        vault.password = "pw"
        return shell().also { it.lock() }
    }

    @Test fun everyLineHasARisingId() {
        val s = shell().run("date", "help", "clear", "date")
        assertEquals(s.lines.size, s.lineIds.size)
        assertEquals(s.lineIds.sorted(), s.lineIds)
        assertEquals(s.lineIds.size, s.lineIds.toSet().size)
        assertEquals("what was typed does not print itself out again", Entrance.NONE, (s.lines[0] as TextLine).entrance)
    }

    @Test fun screenOffLeavesABlankPageAndWakingPrintsTheBootLogOnce() {
        val s = asleep()
        assertTrue(s.locked)
        assertTrue(s.lines.isEmpty())
        val before = s.cueSeq

        s.wake()
        assertEquals(Cue.POWER_ON, s.cue)
        assertEquals(before + 1, s.cueSeq)
        assertTrue(s.text().startsWith("[ 0.000] tty1: waking"))
        assertTrue(s.text().contains("[ ok ] display        1080x2400"))
        assertTrue(s.text().contains("[ ok ] apps           21 found"))
        assertTrue(s.text().contains("[ ok ] notifications  2 waiting"))
        assertTrue(s.text().contains("[ ok ] battery        82%"))
        assertEquals(LockHeader, s.lines.last())
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)

        val ids = s.lineIds
        s.wake()                                 // a second sign of the same wake
        assertEquals(ids, s.lineIds)
        assertEquals(before + 1, s.cueSeq)
    }

    @Test fun bootLogStaysPutThroughAWrongPassword() {
        val s = asleep().also { it.wake() }
        val boot = s.lineIds
        s.run("nope")
        assertEquals("the boot log keeps its lines, so it is not printed again", boot, s.lineIds.take(boot.size))
        assertTrue(s.text().endsWith("<lock header>\nLogin incorrect"))
        s.run("nope again")
        assertEquals(1, Regex("Login incorrect").findAll(s.text()).count())
    }

    @Test fun aLockoutOutlastsTheScreenGoingOffAndOn() {
        vault.password = "pw"
        val s = shell().run("x", "x", "x")
        s.lock()
        s.wake()
        assertNull(s.prompt)
        assertTrue(s.lines.last() is Lockout)
        time += 11_000
        s.tick()
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        assertTrue(s.text().contains("tty1: waking"))
    }

    @Test fun wakingWithoutAPasswordOnlyTurnsTheScreenOn() {
        val s = shell().run("date")
        val text = s.text()
        assertFalse(s.lock())
        s.wake()
        assertEquals(Cue.POWER_ON, s.cue)
        assertEquals(text, s.text())
    }

    @Test fun loginDissolvesTheLockAndPrintsTheSummary() {
        vault.password = "pw"
        store.map["seen"] = "1"
        store.map["last_login"] = (time - 3_600_000).toString()
        val s = shell().run("pw")
        assertEquals(Cue.GRANTED, s.cue)
        assertTrue(s.text().startsWith("Last login: Wed 18 Nov 10:06 on tty1"))
        assertEquals(
            listOf(Gauge("day", 46, "46%"), Gauge("week", 42, "wed"), Gauge("year", 88, "88%")),
            s.lines.filterIsInstance<Gauge>(),
        )
        assertTrue(s.text().contains("2 new · wifi · torch off"))
        assertTrue(s.text().contains("tip: help lists every command"))
        val count = s.lines.filterIsInstance<TextLine>().first { l -> l.spans.any { it.text == "2 new" } }.spans.first()
        assertEquals("notifs", count.run)

        s.run("lock")
        assertEquals(Cue.POWER_OFF, s.cue)
        s.run("pw")
        assertTrue("a different tip each login", s.text().contains("tip: ↑ recalls your last command"))
    }

    @Test fun firstStartRaisesNoCue() {
        assertNull(shell().cue)
    }

    @Test fun animationsCanBeTurnedOff() {
        vault.password = "pw"
        val s = shell().run("pw", "fx off")
        assertFalse(s.motion)
        assertFalse(Shell(phone, store, vault) { time }.motion)
        s.lock()
        s.wake()
        assertEquals("no boot log", "<lock header>", s.text())
        s.run("pw", "fx on", "fx sideways")
        assertTrue(s.motion)
        assertTrue(s.text().contains("fx: expected on or off"))
    }

    /* ---------- the new commands ---------- */

    @Test fun typosOfferTheWholeLineBack() {
        val s = shell().run("opne maps")
        val line = s.lines.last() as TextLine
        assertEquals("did you mean open maps?", line.spans.joinToString("") { it.text })
        assertEquals("tapping it fills the prompt", "open maps", line.spans[1].fill)
        assertNull("and does not run it", line.spans[1].run)
        assertEquals(Entrance.SHAKE, (s.lines[s.lines.size - 2] as TextLine).entrance)
        assertTrue(phone.launched.isEmpty())

        s.run("mapss")
        assertEquals("maps", (s.lines.last() as TextLine).spans[1].fill)
        s.run("thme green")
        assertEquals("theme green", (s.lines.last() as TextLine).spans[1].fill)
    }

    @Test fun launchingPrintsALineTheScreenCanGrow() {
        val s = shell().run("maps")
        assertEquals(Launching("maps"), s.lines.last())
    }

    @Test fun calPrintsTheMonthWithTodayMarked() {
        val s = shell().run("clear", "cal")
        val text = s.text().lines()
        assertEquals("   November 2026", text[1])
        assertEquals("Mo Tu We Th Fr Sa Su", text[2])
        assertEquals("                   1", text[3])
        assertEquals(" 2  3  4  5  6  7  8", text[4])
        assertEquals("30", text[8])
        val marked = s.lines.filterIsInstance<TextLine>().flatMap { it.spans }.filter { it.style == Style.MARK }
        assertEquals(listOf("18"), marked.map { it.text })

        s.run("clear", "cal feb 2024")
        assertTrue(s.text().contains("   February 2024"))
        assertTrue(s.text().contains("26 27 28 29"))
        assertTrue("another month has no today", s.lines.filterIsInstance<TextLine>().none { l -> l.spans.any { it.style == Style.MARK } })
        s.run("cal 12")
        assertTrue(s.text().contains("   December 2026"))
        s.run("cal 13", "cal smarch", "cal 1 year", "cal 1 2 3")
        assertTrue(s.text().contains("cal: no month '13'"))
        assertTrue(s.text().contains("cal: no month 'smarch'"))
        assertTrue(s.text().contains("cal: no year 'year'"))
        assertTrue(s.text().contains("usage: cal"))
    }

    @Test fun calFollowsTheRegionsWeek() {
        Locale.setDefault(Locale.US)
        val text = shell().run("clear", "cal").text().lines()
        assertEquals("Su Mo Tu We Th Fr Sa", text[2])
        assertEquals(" 1  2  3  4  5  6  7", text[3])
    }

    @Test fun fetchPrintsTheSystemCard() {
        val s = shell().run("fetch")
        val card = s.text().lines().takeLast(7)
        assertEquals(
            listOf("phone@tty1", "os      android 14", "device  pixel 8", "up      3d 4h", "apps    21", "batt    82%", "disk    41 / 128 GB"),
            card,
        )
    }

    @Test fun nowPlayingShowsTheTrackAndTakesKeys() {
        val s = shell().run("np")
        assertTrue(s.text().contains("np: nothing is playing"))
        phone.track = Track("low tide", "night drive", true, 131_000, 220_000)
        s.run("np")
        assertEquals(NowPlaying(phone.track!!), s.lines.last())
        s.run("np next", "np prev", "np pause", "np play")
        assertEquals(listOf(MediaKey.NEXT, MediaKey.PREVIOUS, MediaKey.PAUSE, MediaKey.PLAY), phone.keys)
        assertTrue(s.text().contains("next track"))
        s.run("np louder")
        assertTrue(s.text().contains("usage: np"))
        assertEquals(4, phone.keys.size)

        phone.notifAccess = false
        s.run("np")
        assertTrue(s.text().contains("np: notification access is off"))
        assertEquals("setup notifs", (s.lines.last() as TextLine).spans.last().run)
    }

    @Test fun notificationsArriveAboveThePromptButNeverOnTheLockScreen() {
        val s = shell()
        s.notify(Notif("messages", "Mom", "are you\ncoming on Sunday?", 0))
        assertEquals(Note("messages", "Mom: are you coming on Sunday?", time), s.lines.last())
        s.notify(Notif("mail", "", "", 0))
        assertEquals("nothing to say, nothing printed", 1, s.lines.count { it is Note })

        vault.password = "pw"
        s.lock()
        s.wake()
        val text = s.text()
        s.notify(Notif("messages", "Mom", "hello?", 0))
        assertEquals(text, s.text())
    }

    @Test fun themesListAsTappableWordsThatFitTheScreen() {
        val s = shell().run("clear", "theme")
        val rows = s.lines.filterIsInstance<TextLine>().drop(1)
        val words = rows.flatMap { it.spans }.filter { it.run != null }
        assertEquals(Shell.THEMES.map { "theme $it" }, words.map { it.run })
        assertEquals("[default]", words[0].text)
        assertTrue(rows.all { r -> r.spans.sumOf { it.text.length } <= 36 })
        s.run("theme synthwave")
        assertEquals("synthwave", s.theme)
    }

    @Test fun saverIsChosenPersistsAndCanBeShown() {
        val s = shell()
        assertEquals(Shell.SAVER_SHUFFLE, s.saver)
        s.run("saver rain")
        assertEquals("rain", s.saver)
        assertEquals("rain", Shell(phone, store, vault) { time }.saver)
        assertEquals("saver show", (s.lines.last() as TextLine).spans.last().run)
        s.run("saver show")
        assertEquals(Cue.SAVER, s.cue)

        val seq = s.cueSeq
        s.run("saver off", "saver show", "saver disco")
        assertEquals(Shell.SAVER_OFF, s.saver)
        assertEquals("an off screensaver does not start", seq, s.cueSeq)
        assertTrue(s.text().contains("saver: it is off"))
        assertTrue(s.text().contains("no screensaver 'disco'"))
        s.run("clear", "saver")
        assertTrue(s.text().contains("[off]"))
    }

    @Test fun clearAndCtrlL() {
        val s = shell().run("help", "clear")
        assertTrue(s.lines.isEmpty())
        s.run("date")
        s.clear()
        assertTrue(s.lines.isEmpty())
    }
}
