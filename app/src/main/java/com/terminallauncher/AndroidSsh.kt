package com.terminallauncher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.terminallauncher.shell.Link
import com.terminallauncher.shell.LinkEvents
import com.terminallauncher.shell.Remote
import com.terminallauncher.shell.RemoteKey
import com.terminallauncher.shell.Target
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * SSH for the shell, on top of JSch.
 *
 * Each connection runs on threads of its own and reports to the shell on the main thread. The far
 * end is asked for a terminal that does not echo and is too simple for colors or cursor moves, so
 * what comes back is plain lines. A Windows computer is asked for no terminal at all: the one it
 * would give redraws the screen instead of writing lines.
 */
class AndroidSsh(context: Context) : Remote {
    private val main = Handler(Looper.getMainLooper())
    private val keys = KeyBox(File(context.filesDir, "ssh"))

    override fun publicKey(): String? = try {
        keys.publicLine()
    } catch (e: Exception) {
        Log.w(TAG, "no ssh key", e)
        null
    }

    override fun open(target: Target, columns: Int, knownKey: String?, events: LinkEvents): Link =
        Connection(target, columns, knownKey, events).also { it.start() }

    private inner class Connection(
        private val target: Target,
        private val columns: Int,
        private val knownKey: String?,
        private val events: LinkEvents,
    ) : Link {
        /** What the shell answered: a Boolean for a host key, a String for a question, [CANCEL] to give up. */
        private val answers = LinkedBlockingQueue<Any>()
        private val writer = Executors.newSingleThreadExecutor()

        /** Output read but not yet handed to the shell. Guarded by [lock]. */
        private val waiting = StringBuilder()
        private var flushQueued = false
        private val lock = ReentrantLock()
        private val drained = lock.newCondition()

        /** Nothing more is to be reported: the shell hung up, or it has been told the end. */
        @Volatile private var over = false
        @Volatile private var session: Session? = null
        @Volatile private var channel: ChannelShell? = null
        @Volatile private var stdin: OutputStream? = null
        @Volatile private var terminal = false
        private var lastQuestion: String? = null

        fun start() {
            thread(name = "ssh ${target.host}", isDaemon = true) { run() }
        }

        private fun run() {
            var reason: String? = null
            try {
                val jsch = JSch()
                try {
                    val (privatePem, publicLine) = keys.pair()
                    jsch.addIdentity(KEY_COMMENT, privatePem, publicLine, null)
                } catch (e: Exception) {
                    Log.w(TAG, "connecting without a key", e)       // a password can still get in
                }
                jsch.hostKeyRepository = Verifier()
                val s = jsch.getSession(target.user, target.host, target.port)
                session = s
                s.setConfig("StrictHostKeyChecking", "yes")
                // gssapi is left out: Android has none of the classes it needs
                s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                hostKeyOrder(knownKey)?.let { s.setConfig("server_host_key", it) }
                s.userInfo = Asker()
                s.setServerAliveInterval(ALIVE_MS)
                s.setServerAliveCountMax(2)
                s.connect(CONNECT_MS)
                if (over) return                    // the shell hung up while this was still connecting

                val server = s.serverVersion.orEmpty()
                val windows = "windows" in server.lowercase()
                val ch = s.openChannel("shell") as ChannelShell
                if (windows) {
                    ch.setPty(false)
                } else {
                    ch.setPtyType("dumb", columns.coerceIn(20, 500), ROWS, 0, 0)
                    ch.setTerminalMode(NO_ECHO)
                }
                val out = ch.inputStream
                val err = ch.extInputStream
                stdin = ch.outputStream
                channel = ch
                ch.connect(CONNECT_MS)
                if (over) return
                terminal = !windows
                post { events.opened(server, !windows) }

                // With a terminal the far end mixes errors into its output. Without one they come apart.
                if (windows) thread(name = "ssh ${target.host} errors", isDaemon = true) { pump(err) }
                pump(out)

                // The exit status can trail the end of the output by a moment.
                var waited = 0
                while (!ch.isClosed && waited < 1000) {
                    Thread.sleep(50)
                    waited += 50
                }
                if (ch.exitStatus < 0 && !over) reason = "the connection was lost"
            } catch (e: Exception) {
                reason = explain(e)
            } finally {
                end(reason)
            }
        }

        /** Reads [stream] to its end, passing on what it reads. */
        private fun pump(stream: InputStream) {
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val chunk = CharArray(8192)
            try {
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    deliver(String(chunk, 0, n))
                }
            } catch (e: IOException) {
                // the channel was closed under the read: the same as its end
            }
        }

        /**
         * Queues [text] for the shell. Bursts are handed over together a moment later, and a reader that
         * gets far ahead of the screen waits, which in turn slows the far end down.
         */
        private fun deliver(text: String) {
            val queue = lock.withLock {
                var patience = 100
                while (waiting.length > HIGH_WATER && !over && patience-- > 0) {
                    drained.await(50, TimeUnit.MILLISECONDS)
                }
                waiting.append(text)
                !flushQueued.also { flushQueued = true }
            }
            if (queue) main.postDelayed(::flush, FLUSH_MS)
        }

        private fun flush() {
            val text = lock.withLock {
                flushQueued = false
                val all = waiting.toString()
                waiting.setLength(0)
                drained.signalAll()
                all
            }
            if (!over && text.isNotEmpty()) events.output(text)
        }

        private fun post(work: () -> Unit) = main.post { if (!over) work() }

        /** Blocks the connection until the shell answers. null if it gave up or took too long. */
        private fun await(): Any? {
            if (over) return null
            return answers.poll(ANSWER_MS, TimeUnit.MILLISECONDS)?.takeIf { it !== CANCEL }
        }

        private fun end(reason: String?) {
            hangUp()
            main.post {
                flush()
                if (!over) {
                    over = true
                    events.closed(reason)
                }
            }
        }

        private fun hangUp() {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
            writer.shutdown()
        }

        private fun write(bytes: ByteArray) {
            try {
                writer.execute {
                    try {
                        stdin?.let {
                            it.write(bytes)
                            it.flush()
                        }
                    } catch (e: Exception) {
                        // the reader notices a dead connection and reports it
                    }
                }
            } catch (e: RejectedExecutionException) {
                // already closed
            }
        }

        override fun trust(yes: Boolean) {
            answers.offer(yes)
        }

        override fun answer(text: String?) {
            answers.offer(text ?: CANCEL)
        }

        override fun send(text: String) =
            write((if (terminal) text else text.replace("\n", "\r\n")).toByteArray(Charsets.UTF_8))

        override fun press(key: RemoteKey) {
            when {
                // a terminal turns these two bytes into the interrupt signal and the end of input
                terminal -> write(byteArrayOf(if (key == RemoteKey.INTERRUPT) 3 else 4))
                // Without one there is no interrupt to send. The SSH signal for it makes a Windows
                // server end the whole session, so the shell does not offer the key at all.
                key == RemoteKey.INTERRUPT -> Unit
                else -> thread(isDaemon = true) { runCatching { stdin?.close() } }
            }
        }

        override fun close() {
            over = true
            answers.offer(CANCEL)
            thread(isDaemon = true) { hangUp() }       // closing a socket is network work too
        }

        private fun explain(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is UnknownHostException -> return "could not find ${target.host}"
                    is SocketTimeoutException -> return "${target.host} did not answer"
                    is NoRouteToHostException -> return "no route to ${target.host}"
                    is ConnectException -> return "${target.host} refused the connection on port ${target.port}"
                }
                cause = cause.cause
            }
            val text = e.message.orEmpty()
            return when {
                e is JSchUnknownHostKeyException || e is JSchChangedHostKeyException -> "host key not accepted"
                "Auth cancel" in text -> "login cancelled"
                "Auth fail" in text -> "Permission denied"
                "timeout" in text.lowercase() -> "${target.host} did not answer"
                "session is down" in text || "closed by foreign host" in text -> "${target.host} closed the connection"
                text.startsWith("Algorithm negotiation fail") -> {
                    val offered = Regex("serverProposal=\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
                    "${target.host} and this phone have no method in common. It offers " + offered.replace(",", ", ")
                }
                text.isBlank() -> e.javaClass.simpleName
                else -> text
            }
        }

        /** Hands every host key to the shell, which keeps the list of the ones it trusts. */
        private inner class Verifier : HostKeyRepository {
            override fun check(host: String?, key: ByteArray): Int {
                val type = keyType(key)
                val print = fingerprint(key)
                post { events.hostKey(type, print) }
                return if (await() == true) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "terminal launcher"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }

        /** Puts the login's questions to the shell and waits for what the user types. */
        private inner class Asker : UserInfo, UIKeyboardInteractive {
            private var password: String? = null

            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = password
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = false

            override fun showMessage(message: String?) {
                if (!message.isNullOrBlank()) post { events.notice(message) }
            }

            override fun promptPassword(message: String?): Boolean {
                password = ask("${target.user}@${target.host}'s password: ", secret = true)
                return password != null
            }

            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<String>?,
                echo: BooleanArray?,
            ): Array<String>? {
                showMessage(name)
                showMessage(instruction)
                val prompts = prompt ?: return emptyArray()
                return Array(prompts.size) { i ->
                    val question = prompts[i].let { if (it.isEmpty() || it.last().isWhitespace()) it else "$it " }
                    ask(question, secret = echo?.getOrNull(i) != true) ?: return null
                }
            }

            /** The same question twice in a row means the first answer was wrong. */
            private fun ask(question: String, secret: Boolean): String? {
                val again = question == lastQuestion
                lastQuestion = question
                post { events.ask(question, secret, again) }
                return await() as? String
            }
        }
    }

    private companion object {
        init {
            // Android 13 brought Curve25519 and Ed25519. JSch is told to use them through the classes in SshCurves.kt.
            if (Build.VERSION.SDK_INT >= 33) {
                JSch.setConfig("xdh", AndroidX25519::class.java.name)
                JSch.setConfig("ssh-ed25519", AndroidEd25519::class.java.name)
            }
        }

        /** The host key algorithms behind each name [keyType] gives. */
        val HOST_KEY_ALGORITHMS = mapOf(
            "ED25519" to listOf("ssh-ed25519"),
            "ECDSA" to listOf("ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"),
            "RSA" to listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"),
        )

        /** JSch's list of host key algorithms with the ones for a [known] kind of key moved to the front. */
        fun hostKeyOrder(known: String?): String? {
            val first = HOST_KEY_ALGORITHMS[known] ?: return null
            val all = JSch.getConfig("server_host_key").split(',')
            return (first.filter { it in all } + all.filter { it !in first }).joinToString(",")
        }

        const val TAG = "ssh"
        const val KEY_COMMENT = "terminal-launcher"
        const val CONNECT_MS = 15_000
        const val ALIVE_MS = 30_000
        const val ANSWER_MS = 120_000L
        const val FLUSH_MS = 30L
        const val HIGH_WATER = 256 * 1024
        const val ROWS = 24
        val CANCEL = Any()

        /** Terminal modes for the far end: echo (opcode 53) off, then the end of the list. */
        val NO_ECHO = byteArrayOf(53, 0, 0, 0, 0, 0)

        /** The name OpenSSH prints for the kind of key in [blob]. */
        fun keyType(blob: ByteArray): String {
            val buffer = ByteBuffer.wrap(blob)
            // the length is the server's word: it is checked before anything is made that size
            val length = if (buffer.remaining() >= 4) buffer.int else return "unknown"
            if (length !in 1..64 || length > buffer.remaining()) return "unknown"
            val name = ByteArray(length).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            return when {
                name == "ssh-rsa" -> "RSA"
                name == "ssh-ed25519" -> "ED25519"
                name.startsWith("ecdsa-") -> "ECDSA"
                else -> name.uppercase()
            }
        }

        /** The fingerprint the way `ssh` and `ssh-keygen -l` print it. */
        fun fingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/**
 * This phone's SSH key pair, made on first use. The private half is stored encrypted with a key
 * that lives in Android's keystore and cannot be read out of it, so the file is no use off this phone.
 */
private class KeyBox(private val dir: File) {
    private val publicFile = File(dir, "id_ecdsa.pub")
    private val privateFile = File(dir, "id_ecdsa.enc")

    /** One `authorized_keys` line. */
    @Synchronized
    fun publicLine(): String {
        ensure()
        return publicFile.readText().trim()
    }

    /** The private key as PEM and the public key as its `authorized_keys` line. */
    @Synchronized
    fun pair(): Pair<ByteArray, ByteArray> {
        ensure()
        return open(privateFile.readBytes()) to publicFile.readBytes()
    }

    private fun ensure() {
        if (publicFile.exists() && privateFile.exists()) return
        dir.mkdirs()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val private = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toByteArray()
            val public = ByteArrayOutputStream().also { pair.writePublicKey(it, "terminal-launcher") }.toByteArray()
            privateFile.writeBytes(seal(private))
            publicFile.writeBytes(public)
        } finally {
            pair.dispose()
        }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** The nonce the keystore picked, then the ciphertext. */
    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, sealed, 0, NONCE))
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    private companion object {
        const val ALIAS = "ssh-key-wrap"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val NONCE = 12
    }
}
