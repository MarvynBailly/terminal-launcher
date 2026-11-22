package com.terminallauncher.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLink : Link {
    val trusted = ArrayList<Boolean>()
    val answers = ArrayList<String?>()
    val sent = ArrayList<String>()
    val pressed = ArrayList<RemoteKey>()
    var closed = false
    override fun trust(yes: Boolean) { trusted += yes }
    override fun answer(text: String?) { answers += text }
    override fun send(text: String) { sent += text }
    override fun press(key: RemoteKey) { pressed += key }
    override fun close() { closed = true }
}

/** Records what the shell asks for. A test plays the far end by calling [events] itself. */
private class FakeRemote : Remote {
    var key: String? = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY= terminal-launcher"
    val opened = ArrayList<Target>()
    var columns = 0
    var knownKey: String? = null
    lateinit var events: LinkEvents
    var link = FakeLink()
    override fun publicKey() = key
    override fun open(target: Target, columns: Int, knownKey: String?, events: LinkEvents): Link {
        opened += target
        this.columns = columns
        this.knownKey = knownKey
        this.events = events
        link = FakeLink()
        return link
    }
}

class SshTest {
    private val phone = FakePhone()
    private val store = FakeStore()
    private val vault = FakeVault()
    private val remote = FakeRemote()
    private var time = 1_795_000_000_000L

    private fun shell() = Shell(phone, store, vault, remote) { time }.also { it.start() }

    private fun Shell.rows(): List<String> = lines.map { line ->
        if (line is TextLine) line.spans.joinToString("") { it.text } else "<$line>"
    }
    private fun Shell.text() = rows().joinToString("\n")
    private fun Shell.chips(input: String) = candidates(input)!!.items.map { it.text }

    /** A shell logged in to a computer it already knows. */
    private fun session(server: String = "SSH-2.0-OpenSSH_9.6p1 Ubuntu", terminal: Boolean = true): Shell {
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.columns = 80
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened(server, terminal)
        return s
    }

    /* ---------- logging in ---------- */

    @Test fun aNewComputerIsAskedAbout() {
        val s = shell()
        s.columns = 40
        s.submit("ssh alex@desktop")
        assertEquals(listOf(Target("alex", "desktop", 22)), remote.opened)
        assertEquals(40, remote.columns)
        assertNull(s.prompt)
        assertTrue(s.text().contains("connecting to desktop"))

        remote.events.hostKey("ED25519", "SHA256:abc")
        assertEquals(Prompt("trust it? (yes/no) ", PromptKind.ASK, mask = false), s.prompt)
        assertTrue(s.text().contains("SHA256:abc"))
        assertTrue(remote.link.trusted.isEmpty())

        s.submit("yes")
        assertEquals(listOf(true), remote.link.trusted)
        assertEquals("ED25519 SHA256:abc", store.get("ssh_key_desktop"))
        assertNull(s.prompt)
        assertEquals("trust it? (yes/no) yes", s.rows().last())
    }

    @Test fun refusingAKeySavesNothing() {
        val s = shell()
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        s.submit("no")
        assertEquals(listOf(false), remote.link.trusted)
        assertNull(store.get("ssh_key_desktop"))

        remote.events.closed("host key not accepted")
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertEquals("ssh: host key not accepted", s.rows().last())
    }

    @Test fun aKnownComputerIsNotAskedAbout() {
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.submit("ssh alex@desktop")
        assertEquals("the far end is asked for the kind of key it showed before", "ED25519", remote.knownKey)
        remote.events.hostKey("ED25519", "SHA256:abc")
        assertEquals(listOf(true), remote.link.trusted)
        assertNull(s.prompt)

        remote.events.closed("stop")
        s.submit("ssh alex@elsewhere")
        assertNull(remote.knownKey)
    }

    @Test fun aChangedKeyIsRefused() {
        store.put("ssh_key_desktop", "ED25519 SHA256:old")
        val s = shell()
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:new")
        assertEquals(listOf(false), remote.link.trusted)
        assertEquals("ED25519 SHA256:old", store.get("ssh_key_desktop"))
        assertTrue(s.text().contains("WARNING"))
        val fix = (s.lines.last() as TextLine).spans.single()
        assertEquals("ssh forget desktop", fix.fill)
        assertNull(fix.run)
    }

    @Test fun aPasswordIsTypedUnseenAndPassedOn() {
        val s = shell()
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.ask("alex@desktop's password: ", secret = true, again = false)
        assertEquals(Prompt("alex@desktop's password: ", PromptKind.ASK, mask = true), s.prompt)

        s.submit("hunter2")
        assertEquals(listOf<String?>("hunter2"), remote.link.answers)
        assertFalse(s.text().contains("hunter2"))
        assertNull(s.prompt)

        remote.events.ask("alex@desktop's password: ", secret = true, again = true)
        assertTrue(s.text().contains("Permission denied, please try again."))
    }

    @Test fun aFailedLoginSaysWhyAndReturns() {
        val s = shell()
        s.submit("ssh alex@nowhere")
        remote.events.closed("could not find nowhere")
        assertEquals("ssh: could not find nowhere", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertNull(s.remoteHost)
        assertTrue("normal chips are back", s.chips("").none { it == Shell.KEY_CANCEL })
    }

    @Test fun aNoticeBeforeLoginIsPrintedWithoutItsControlCodes() {
        val s = shell()
        s.submit("ssh alex@desktop")
        remote.events.notice("\u001b[31mAuthorized use only\u001b[0m\r\n")
        assertEquals("Authorized use only", s.rows().last())
    }

    @Test fun theUserNameIsAskedOnceAndRemembered() {
        val s = shell()
        s.submit("ssh desktop")
        assertEquals(Prompt("login as: ", PromptKind.ASK, mask = false), s.prompt)
        assertTrue(remote.opened.isEmpty())

        s.submit("alex")
        assertTrue(s.rows().contains("login as: alex"))
        assertEquals(Target("alex", "desktop", 22), remote.opened.single())
        remote.events.hostKey("ED25519", "SHA256:abc")
        s.submit("yes")
        remote.events.opened("SSH-2.0-OpenSSH_9.6", true)
        remote.events.closed(null)

        s.submit("ssh desktop")
        assertEquals(2, remote.opened.size)
        assertEquals("alex", remote.opened[1].user)
        remote.events.closed("gone")
        assertEquals(listOf("alex@desktop", "key", "forget"), s.chips("ssh "))
        assertEquals(listOf("alex@desktop"), s.chips("ssh-copy-id a"))
        assertEquals(listOf("desktop"), s.chips("ssh forget d"))
    }

    @Test fun portsAndOptionsAreRead() {
        val s = shell()
        for (line in listOf("ssh alex@Desktop -p 2222", "ssh -p 2222 alex@desktop", "ssh alex@desktop:2222", "ssh -l alex desktop:2222")) {
            s.submit(line)
            assertEquals(line, Target("alex", "desktop", 2222), remote.opened.last())
            remote.events.closed("stop")
        }
        assertEquals("desktop:2222", remote.opened.last().place)
        assertEquals("alex@desktop:2222", remote.opened.last().label)

        val before = remote.opened.size
        s.submit("ssh alex@desktop -p 99999")
        assertEquals("ssh: no port '99999'", s.rows().last())
        s.submit("ssh alex@desktop uptime")
        assertEquals("ssh: log in first, then type the command there", s.rows().last())
        s.submit("ssh -X alex@desktop")
        assertEquals("ssh: no option -X", s.rows().last())
        s.submit("ssh alex@")
        assertEquals("ssh: which computer?", s.rows().last())
        assertEquals(before, remote.opened.size)
    }

    @Test fun aKeyOnAnotherPortIsFiledApart() {
        val s = shell()
        s.submit("ssh alex@desktop:2222")
        remote.events.hostKey("ED25519", "SHA256:abc")
        s.submit("y")
        assertEquals("ED25519 SHA256:abc", store.get("ssh_key_desktop:2222"))
        assertNull(store.get("ssh_key_desktop"))
    }

    /* ---------- a session ---------- */

    @Test fun linesGoToTheFarEndAndItsOutputIsPrinted() {
        val s = session()
        assertEquals(Prompt("", PromptKind.REMOTE), s.prompt)
        assertEquals("desktop", s.remoteHost)

        remote.events.output("alex@desktop:~$ ")
        assertEquals("alex@desktop:~$ ", s.prompt!!.text)

        s.submit("ls")
        assertEquals(listOf("ls\n"), remote.link.sent)
        assertEquals("alex@desktop:~$ ls", s.rows().last())
        assertEquals("", s.prompt!!.text)

        remote.events.output("a.txt\r\nb.txt\r\nalex@desktop:~$ ")
        assertEquals(listOf("alex@desktop:~$ ls", "a.txt", "b.txt"), s.rows().takeLast(3))
        assertEquals("alex@desktop:~$ ", s.prompt!!.text)
        assertEquals(listOf("ls"), s.history)
        assertTrue("remote lines stay out of the launcher's history", "ls" !in s.also { remote.events.closed(null) }.history)
    }

    @Test fun remoteOutputDoesNotPrintItselfOut() {
        val s = session()
        remote.events.output("one\ntwo\n")
        assertTrue(s.lines.takeLast(2).all { (it as TextLine).entrance == Entrance.NONE })
    }

    @Test fun aLongRemotePromptKeepsItsEnd() {
        val s = session()
        s.columns = 34
        remote.events.output("alex@raspberrypi:~/projects/thing$ ")
        assertEquals("…projects/thing$ ", s.prompt!!.text)
        s.submit("pwd")
        assertEquals("alex@raspberrypi:~/projects/thing$ pwd", s.rows().last())
    }

    @Test fun aPasswordPromptFromTheFarEndHidesWhatIsTyped() {
        val s = session()
        remote.events.output("[sudo] password for alex: ")
        assertTrue(s.prompt!!.mask)
        s.submit("secret")
        assertEquals(listOf("secret\n"), remote.link.sent)
        assertFalse(s.text().contains("secret"))
        assertTrue(s.history.isEmpty())
        remote.events.output("\r\nalex@desktop:~# ")
        assertFalse(s.prompt!!.mask)
    }

    @Test fun aComputerThatEchoesDoesNotShowTheLineTwice() {
        val s = session("SSH-2.0-OpenSSH_for_Windows_9.5", terminal = false)
        remote.events.output("C:\\Users\\admin>")
        s.submit("dir")
        remote.events.output("di")
        assertEquals("the echo is not shown as a prompt while it arrives", "", s.prompt!!.text)
        remote.events.output("r\r\n Volume in drive C\r\nC:\\Users\\admin>")
        assertEquals(listOf("C:\\Users\\admin>dir", " Volume in drive C"), s.rows().takeLast(2))
        assertEquals("C:\\Users\\admin>", s.prompt!!.text)

        // only the line just sent is dropped, and only once
        s.submit("")
        remote.events.output("dir\r\n")
        assertEquals("dir", s.rows().last())
    }

    @Test fun withoutATerminalThereIsNoInterruptKey() {
        val s = session("SSH-2.0-OpenSSH_for_Windows_9.5", terminal = false)
        assertEquals(listOf("^D", "disconnect"), s.chips(""))
        remote.events.output("PS C:\\Users\\admin> ")
        s.cancel("ping -t localhost")
        s.press("^C")
        assertTrue(remote.link.pressed.isEmpty())
        assertFalse(remote.link.closed)
        assertTrue(s.rows().last().contains("disconnect stops a command"))
        assertEquals("the prompt is still the far end's", "PS C:\\Users\\admin> ", s.prompt!!.text)

        s.press("^D")
        assertEquals(listOf(RemoteKey.END), remote.link.pressed)
    }

    @Test fun theFarEndClosingReturnsToTheLauncher() {
        val s = session()
        remote.events.output("logout\r\n")
        remote.events.closed(null)
        assertEquals(listOf("logout", "Connection to desktop closed."), s.rows().takeLast(2))
        assertEquals(Prompt("$ ", PromptKind.SHELL), s.prompt)
        assertNull(s.remoteHost)

        val before = s.lines.size
        remote.events.output("late\n")
        remote.events.closed("late")
        assertEquals("a closed session is not heard from again", before, s.lines.size)
        s.submit("date")
        assertTrue(remote.link.sent.none { it.startsWith("date") })
    }

    @Test fun aLostConnectionShowsWhereItStopped() {
        val s = session()
        remote.events.output("copying 40%")
        remote.events.closed("connection lost")
        assertEquals(listOf("copying 40%", "ssh: connection lost"), s.rows().takeLast(2))
    }

    @Test fun theChipsAreKeysDuringASession() {
        val s = shell()
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        s.submit("ssh alex@desktop")
        assertEquals(listOf("cancel"), s.chips(""))
        assertTrue(s.candidates("")!!.items.all { it.kind == ChipKind.KEY })
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened("SSH-2.0-OpenSSH_9.6", true)
        assertEquals(listOf("^C", "^D", "disconnect"), s.chips("anything"))

        remote.events.output("$ ")
        s.press("^C")
        s.press("^D")
        assertEquals(listOf(RemoteKey.INTERRUPT, RemoteKey.END), remote.link.pressed)
        assertEquals("$ ^C", s.rows().last())

        s.press("disconnect")
        assertTrue(remote.link.closed)
        assertEquals("Disconnected from desktop.", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertTrue(s.chips("").none { it == "^C" })
    }

    @Test fun cancellingALoginHangsUp() {
        val s = shell()
        s.submit("ssh alex@desktop")
        s.press("cancel")
        assertTrue(remote.link.closed)
        assertEquals("ssh: cancelled", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)

        // Ctrl+C does the same, also while a question is waiting
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        s.cancel("")
        assertTrue(remote.link.closed)
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        s.submit("yes")
        assertTrue("the question is gone with the login", remote.link.trusted.isEmpty())
    }

    @Test fun ctrlCInASessionGoesToTheFarEnd() {
        val s = session()
        remote.events.output("$ ")
        s.cancel("sleep 100")
        assertEquals(listOf(RemoteKey.INTERRUPT), remote.link.pressed)
        assertEquals("$ sleep 100^C", s.rows().last())
        assertFalse(remote.link.closed)

        // the empty line a shell answers an interrupt with is not shown, but later ones are
        remote.events.output("\r\n$ ")
        assertEquals("$ sleep 100^C", s.rows().last())
        s.submit("echo")
        remote.events.output("\r\n$ ")
        assertEquals(listOf("$ echo", ""), s.rows().takeLast(2))
    }

    @Test fun tapsOnOldOutputDoNothingDuringASession() {
        val s = shell()
        s.submit("ssh")
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened("SSH-2.0-OpenSSH_9.6", true)
        s.runTapped("ssh key")
        assertTrue(remote.link.sent.isEmpty())
    }

    /* ---------- the lock ---------- */

    @Test fun theScreenTurningOffEndsTheSession() {
        val s = session()
        assertFalse("no password, so the terminal does not lock", s.lock())
        assertTrue(remote.link.closed)
        assertNull(s.remoteHost)
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
        assertTrue(s.rows().last().contains("ssh session was closed"))
    }

    @Test fun lockingEndsTheSessionToo() {
        vault.password = "pw"
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.submit("pw")
        s.submit("ssh alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened("SSH-2.0-OpenSSH_9.6", true)
        val link = remote.link

        assertTrue(s.lock())
        assertTrue(link.closed)
        s.wake()
        assertEquals(PromptKind.LOGIN, s.prompt!!.kind)
        remote.events.output("still here\n")
        assertFalse(s.text().contains("still here"))
        s.submit("pw")
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
    }

    @Test fun theLauncherPasswordIsAskedAgainAfterAWhile() {
        vault.password = "pw"
        val s = shell()
        s.submit("pw")

        s.submit("ssh alex@desktop")
        assertEquals("typed a moment ago: no question", 1, remote.opened.size)
        remote.events.closed("stop")

        time += 6 * 60_000L
        s.submit("ssh alex@desktop")
        assertEquals(Prompt("launcher password: ", PromptKind.ASK, mask = true), s.prompt)
        s.submit("nope")
        assertEquals("ssh: wrong launcher password", s.rows().last())
        assertEquals(1, remote.opened.size)

        s.submit("ssh alex@desktop")
        s.submit("pw")
        assertEquals(2, remote.opened.size)
        remote.events.closed("stop")

        s.submit("ssh alex@desktop")
        assertEquals("and that answer counts for a while too", 3, remote.opened.size)
    }

    /* ---------- keys ---------- */

    @Test fun sshKeyShowsTheKeyAndATapCopiesIt() {
        val s = shell()
        s.submit("ssh key")
        val line = s.lines.first { it is TextLine && it.spans.any { span -> span.text == remote.key } } as TextLine
        assertEquals("ssh key copy", line.spans.single().run)
        s.runTapped("ssh key copy")
        assertEquals(listOf(remote.key!!), phone.copied)
        assertEquals("copied", s.rows().last())

        remote.key = null
        s.submit("ssh key")
        assertEquals("ssh: could not make a key", s.rows().last())
    }

    @Test fun sshForgetDropsASavedKey() {
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.submit("ssh forget alex@Desktop")
        assertNull(store.get("ssh_key_desktop"))
        assertEquals("forgot the key of desktop", s.rows().last())
        s.submit("ssh forget desktop")
        assertEquals("ssh: no saved key for desktop", s.rows().last())
        s.submit("ssh forget")
        assertEquals("usage: ssh forget <host>", s.rows().last())
    }

    @Test fun copyIdAddsTheKeyAndLeaves() {
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.submit("ssh-copy-id alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened("SSH-2.0-OpenSSH_9.6p1 Ubuntu", true)
        val line = remote.link.sent.single()
        assertTrue(line.contains("echo '${remote.key}' >> ~/.ssh/authorized_keys"))
        assertTrue(line.endsWith("exit\n"))
        assertTrue("nothing of it is echoed", s.rows().none { it.contains("authorized_keys") })

        // the greeting and the prompt are skipped: only what the command says is shown
        remote.events.output("Welcome to Ubuntu\r\n\r\nalex@desktop:~$ ")
        assertEquals("", s.prompt!!.text)
        remote.events.output("terminal-launcher-adds-a-key\r\nKey installed. The next login needs no password.\r\nlogout\r\n")
        remote.events.closed(null)
        assertEquals(
            listOf("connecting to desktop…", "Key installed. The next login needs no password.", "logout", "Connection to desktop closed."),
            s.rows().takeLast(4),
        )
    }

    @Test fun copyIdLeavesAWindowsComputerAlone() {
        store.put("ssh_key_desktop", "ED25519 SHA256:abc")
        val s = shell()
        s.submit("ssh-copy-id alex@desktop")
        remote.events.hostKey("ED25519", "SHA256:abc")
        remote.events.opened("SSH-2.0-OpenSSH_for_Windows_9.5", false)
        assertTrue(remote.link.sent.isEmpty())
        assertTrue(s.text().contains("this is a Windows computer"))
        assertEquals("desktop", s.remoteHost)
    }

    @Test fun sshAloneListsWhatItCanDo() {
        store.put("ssh_hosts", "alex@desktop\npi@raspberrypi")
        val s = shell()
        s.submit("ssh")
        assertTrue(s.text().contains("usage: ssh [user@]host [-p port]"))
        val hosts = s.lines.filterIsInstance<TextLine>().first { it.spans.any { span -> span.text == "alex@desktop" } }
        assertEquals("ssh alex@desktop", hosts.spans.first().run)
        assertTrue(remote.opened.isEmpty())

        s.submit("help")
        assertTrue(s.text().contains("ssh <user@host>"))
    }

    @Test fun withoutSshTheCommandSaysSo() {
        val s = Shell(phone, store, vault) { time }.also { it.start() }
        s.submit("ssh alex@desktop")
        assertEquals("ssh: not available here", s.rows().last())
        assertEquals(PromptKind.SHELL, s.prompt!!.kind)
    }

    /* ---------- turning a stream into lines ---------- */

    @Test fun textIsCutIntoLines() {
        val t = RemoteText()
        assertEquals(listOf("a", "b"), t.feed("a\r\nb\n"))
        assertEquals(emptyList<String>(), t.feed("abc"))
        assertEquals("abc", t.pending)
        assertEquals(listOf("abcdef"), t.feed("def\n"))
        assertEquals("", t.pending)
        assertEquals(listOf("padded"), t.feed("padded   \n"))
    }

    @Test fun aLineBreakSplitAcrossTwoReadsIsOneBreak() {
        val t = RemoteText()
        assertEquals(emptyList<String>(), t.feed("a\r"))
        assertEquals("a", t.pending)
        assertEquals(listOf("a"), t.feed("\nb"))
        assertEquals("b", t.pending)
    }

    @Test fun aCarriageReturnStartsTheLineOver() {
        val t = RemoteText()
        assertEquals(emptyList<String>(), t.feed("10%\r20%\r30%"))
        assertEquals("30%", t.pending)
        assertEquals(listOf("30%"), t.feed("\n"))
    }

    @Test fun controlCodesAreDropped() {
        val t = RemoteText()
        assertEquals(listOf("ok"), t.feed("\u001b[1;32mok\u001b[0m\n"))
        assertEquals(emptyList<String>(), t.feed("\u001b[1;3"))
        assertEquals(listOf("green"), t.feed("2mgreen\u001b[K\n"))
        t.feed("\u001b]0;window title\u0007$ ")
        assertEquals("$ ", t.pending)
        assertEquals("$ ", t.take())
        t.feed("\u001b]0;title\u001b\\\u001b(Bx\u0007\u0000y")
        assertEquals("xy", t.pending)
    }

    @Test fun backspacesAndTabsAreApplied() {
        val t = RemoteText()
        assertEquals(listOf("aX"), t.feed("abc\b\bX\n"))
        assertEquals(listOf("a       b"), t.feed("a\tb\n"))
        assertEquals(listOf("        x"), t.feed("\tx\n"))
    }

    @Test fun takeEmptiesTheLine() {
        val t = RemoteText()
        t.feed("prompt> ")
        assertEquals("prompt> ", t.take())
        assertEquals("", t.pending)
        assertEquals(listOf("out"), t.feed("out\n"))
    }

    @Test fun aLineWithNoEndIsCutEventually() {
        val t = RemoteText()
        val out = t.feed("x".repeat(4500))
        assertEquals(2, out.size)
        assertEquals(500, t.pending.length)
    }
}
