package com.terminallauncher

import com.jcraft.jsch.SignatureEdDSA
import com.jcraft.jsch.XDH
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/*
 * Curve25519 key exchange and Ed25519 host keys for JSch, built on what Android 13 and later provide.
 *
 * JSch has its own versions of both for desktop Java, but they sit in the part of its jar that only
 * newer Java runtimes load, which Android does not. Without them it cannot connect to a server set
 * up to offer nothing older. JSch creates these classes by name, so each needs a public constructor
 * that takes no arguments, and AndroidSsh registers them.
 *
 * Keys cross to and from Android as X.509 structures: a fixed header and then the 32 bytes that SSH
 * itself sends, in the same byte order.
 */

private val X25519_HEADER = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
private val ED25519_HEADER = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

class AndroidX25519 : XDH {
    private lateinit var agreement: KeyAgreement
    private var ours = ByteArray(0)
    private var length = 32

    override fun init(name: String, keylen: Int) {
        if (name != "X25519") throw UnsupportedOperationException(name)
        length = keylen
        // Android's generator makes this one curve and takes no parameters: asked to choose, it refuses.
        val pair = KeyPairGenerator.getInstance("XDH").generateKeyPair()
        val encoded = pair.public.encoded
        if (encoded.size != X25519_HEADER.size + keylen) throw UnsupportedOperationException("not an X25519 key")
        ours = encoded.copyOfRange(X25519_HEADER.size, encoded.size)
        agreement = KeyAgreement.getInstance("XDH").apply { init(pair.private) }
    }

    override fun getQ(): ByteArray = ours

    override fun getSecret(u: ByteArray): ByteArray {
        val theirs = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_HEADER + u))
        agreement.doPhase(theirs, true)
        return agreement.generateSecret()
    }

    override fun validate(u: ByteArray): Boolean = u.size == length
}

/** Checks a server's Ed25519 signature. It never signs: this phone's own key is ECDSA. */
class AndroidEd25519 : SignatureEdDSA {
    private lateinit var signature: Signature

    /**
     * JSch calls this first to learn whether Ed25519 can be used at all, so it is where a phone
     * without it has to fail. Android 14 has no way to load an Ed25519 key that was not made in its
     * own keystore. Rather than guess which versions do, this checks a signature whose answer is
     * known, and only a phone that gets it right is trusted with a server's.
     */
    override fun init() {
        signature = Signature.getInstance("Ed25519")
        setPubKey(KNOWN_KEY)
        if (!signature.verify(KNOWN_SIGNATURE)) throw UnsupportedOperationException("Ed25519 does not verify")
        signature = Signature.getInstance("Ed25519")
    }

    override fun setPubKey(y_arr: ByteArray) {
        signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_HEADER + y_arr)))
    }

    override fun setPrvKey(bytes: ByteArray) = throw UnsupportedOperationException("signing with Ed25519")

    override fun update(H: ByteArray) = signature.update(H)

    override fun sign(): ByteArray = throw UnsupportedOperationException("signing with Ed25519")

    /** [sig] may be the bare signature, or the SSH form of it: the algorithm's name, then the signature. */
    override fun verify(sig: ByteArray): Boolean = try {
        signature.verify(unwrap(sig))
    } catch (e: SignatureException) {
        false                                       // not even shaped like a signature
    }

    /**
     * The signature inside its SSH form, or [sig] itself if it is not in that form. The bytes come
     * from a server that is not trusted yet, so no length in them is believed before it is checked.
     */
    private fun unwrap(sig: ByteArray): ByteArray {
        if (sig.size < 4 + SSH_NAME.size + 4) return sig
        val buffer = ByteBuffer.wrap(sig)
        if (buffer.int != SSH_NAME.size) return sig
        for (expected in SSH_NAME) if (buffer.get() != expected) return sig
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) return sig
        return ByteArray(length).also { buffer.get(it) }
    }

    private companion object {
        val SSH_NAME = "ssh-ed25519".toByteArray(Charsets.US_ASCII)

        /** The first test in RFC 8032: this key signed the empty message with this signature. */
        val KNOWN_KEY = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val KNOWN_SIGNATURE = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )

        fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
