package com.terminallauncher.shell

/** A login on another computer. */
data class Target(val user: String, val host: String, val port: Int = 22) {
    /** The computer without the user, which is how its fingerprint is filed: `desktop`, or `desktop:2222`. */
    val place: String get() = if (port == 22) host else "$host:$port"

    /** What to type after `ssh` to get here again. */
    val label: String get() = "$user@$place"
}

/** The keys a session can press besides Enter. */
enum class RemoteKey { INTERRUPT, END }

/**
 * Everything the shell asks of SSH. Like [Phone] it is an interface, so the shell can be tested
 * against a fake. Calls return at once: what comes of them arrives later through [LinkEvents].
 */
interface Remote {
    /** This phone's public key as one `authorized_keys` line. It is made the first time it is asked for. null if that failed. */
    fun publicKey(): String?

    /**
     * Starts logging in to [target], for a terminal [columns] wide. [knownKey] is the kind of key the
     * computer showed last time, if it has been here before: it is asked for that kind again, so a
     * computer with several keys is not mistaken for a different one.
     */
    fun open(target: Target, columns: Int, knownKey: String?, events: LinkEvents): Link
}

/** What a connection reports. Every call arrives on the shell's thread, and never from inside a call to [Link]. */
interface LinkEvents {
    /** The computer showed this key. Nothing more happens until [Link.trust] answers. */
    fun hostKey(type: String, fingerprint: String)

    /** The computer wants something typed, such as a password. [again] means the last answer was wrong. Answer with [Link.answer]. */
    fun ask(question: String, secret: Boolean, again: Boolean)

    /** Something the computer says before the login is through. */
    fun notice(text: String)

    /**
     * Logged in, and a shell is running. [server] is what the computer calls its SSH software.
     * Without a [terminal] the far end repeats each line it is sent before acting on it, and
     * [RemoteKey.INTERRUPT] is not a key it knows.
     */
    fun opened(server: String, terminal: Boolean)

    fun output(text: String)

    /** The end, whichever way it came. [reason] is null when the far end simply closed. */
    fun closed(reason: String?)
}

interface Link {
    fun trust(yes: Boolean)

    /** null gives up on the login. */
    fun answer(text: String?)

    fun send(text: String)
    fun press(key: RemoteKey)
    fun close()
}

/**
 * Turns what a remote shell writes into plain lines, for a terminal that only prints lines.
 *
 * Colors, cursor moves and window titles are dropped. A carriage return starts the line over, so a
 * progress bar that redraws itself ends up as its last state. Whatever follows the last line break
 * is kept in [pending]: most of the time that is the remote prompt.
 */
class RemoteText {
    private enum class State { TEXT, ESCAPE, CONTROL, STRING, STRING_ESCAPE, SKIP_ONE }

    private val line = StringBuilder()
    private var state = State.TEXT

    /** A carriage return was seen: the next character to print starts the line over. */
    private var returned = false

    val pending: String get() = line.toString()

    /** Hands over [pending] and starts an empty line. */
    fun take(): String = line.toString().also {
        line.setLength(0)
        returned = false
    }

    /** The lines [text] completes. A sequence cut off at the end of [text] carries on in the next call. */
    fun feed(text: String): List<String> {
        val out = ArrayList<String>()
        fun put(c: Char) {
            if (returned) {
                line.setLength(0)
                returned = false
            }
            line.append(c)
            if (line.length >= MAX_LINE) {
                out += line.toString()
                line.setLength(0)
            }
        }
        for (c in text) {
            when (state) {
                State.TEXT -> when {
                    c == ESC -> state = State.ESCAPE
                    c == '\n' -> {
                        out += line.toString().trimEnd()
                        line.setLength(0)
                        returned = false
                    }
                    c == '\r' -> returned = true
                    c == '\b' -> if (!returned && line.isNotEmpty()) line.setLength(line.length - 1)
                    c == '\t' -> {
                        put(' ')
                        while (line.length % TAB != 0) put(' ')
                    }
                    c < ' ' || c == '\u007f' -> Unit
                    else -> put(c)
                }
                State.ESCAPE -> state = when (c) {
                    '[' -> State.CONTROL
                    ']', 'P', '^', '_', 'X' -> State.STRING
                    '(', ')', '*', '+', '#', '%', ' ' -> State.SKIP_ONE
                    else -> State.TEXT
                }
                // parameters and intermediates run until one final character
                State.CONTROL -> if (c in '@'..'~') state = State.TEXT
                State.STRING -> when (c) {
                    '\u0007' -> state = State.TEXT
                    ESC -> state = State.STRING_ESCAPE
                    else -> Unit
                }
                State.STRING_ESCAPE -> state = if (c == '\\') State.TEXT else State.STRING
                State.SKIP_ONE -> state = State.TEXT
            }
        }
        return out
    }

    private companion object {
        const val ESC = '\u001b'
        const val TAB = 8
        const val MAX_LINE = 2000
    }
}
