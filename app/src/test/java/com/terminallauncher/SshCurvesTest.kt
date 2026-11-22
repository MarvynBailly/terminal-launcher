package com.terminallauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.KeyAgreement

/**
 * The two classes only pass keys in and out of the platform's own Curve25519 and Ed25519, so what
 * can go wrong is the packing. These run on the desktop JVM, which has the same interfaces, against
 * keys and signatures the JVM makes by itself.
 */
class SshCurvesTest {
    private fun sshForm(signature: ByteArray): ByteArray {
        val name = "ssh-ed25519".toByteArray()
        return ByteBuffer.allocate(8 + name.size + signature.size).putInt(name.size).put(name).putInt(signature.size).put(signature).array()
    }

    @Test fun twoSidesReachTheSameSecret() {
        val a = AndroidX25519().apply { init("X25519", 32) }
        val b = AndroidX25519().apply { init("X25519", 32) }
        assertEquals(32, a.q.size)
        assertTrue(a.validate(b.q))
        assertFalse(a.validate(ByteArray(31)))
        val secret = a.getSecret(b.q)
        assertEquals(32, secret.size)
        assertArrayEquals(secret, b.getSecret(a.q))
        assertFalse("not the all-zero secret", secret.all { it == 0.toByte() })
    }

    @Test fun theSecretMatchesThePlatformsOwn() {
        // the far end, done without the class under test
        val theirs = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val theirPublic = theirs.public.encoded.takeLast(32).toByteArray()

        val ours = AndroidX25519().apply { init("X25519", 32) }
        val secret = ours.getSecret(theirPublic)

        val oursAsKey = java.security.KeyFactory.getInstance("X25519").generatePublic(
            java.security.spec.X509EncodedKeySpec(theirs.public.encoded.copyOf(12) + ours.q),
        )
        val expected = KeyAgreement.getInstance("X25519").apply {
            init(theirs.private)
            doPhase(oursAsKey, true)
        }.generateSecret()
        assertArrayEquals(expected, secret)
    }

    @Test fun otherCurvesAreRefused() {
        try {
            AndroidX25519().init("X448", 56)
            throw AssertionError("X448 was accepted")
        } catch (e: UnsupportedOperationException) {
            // expected
        }
    }

    @Test fun aRealSignatureVerifiesInBothForms() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = pair.public.encoded.takeLast(32).toByteArray()
        val message = "exchange hash".toByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(pair.private)
            update(message)
        }.sign()

        fun check(sig: ByteArray, text: ByteArray = message, key: ByteArray = raw): Boolean {
            val v = AndroidEd25519()
            v.init()                    // also runs the known-answer check
            v.setPubKey(key)
            v.update(text)
            return v.verify(sig)
        }
        assertTrue("bare", check(signature))
        assertTrue("as SSH sends it", check(sshForm(signature)))
        assertFalse("another message", check(signature, "exchange hasH".toByteArray()))
        assertFalse("a damaged signature", check(signature.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        assertFalse("a damaged signature in SSH form", check(sshForm(signature.copyOf().also { it[40] = (it[40] + 1).toByte() })))
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded.takeLast(32).toByteArray()
        assertFalse("another key", check(signature, key = other))
    }

    @Test fun nonsenseIsRefusedWithoutBeingBelieved() {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded.takeLast(32).toByteArray()
        fun check(sig: ByteArray): Boolean {
            val v = AndroidEd25519()
            v.init()
            v.setPubKey(key)
            v.update("exchange hash".toByteArray())
            return v.verify(sig)
        }
        // Lengths that would be huge, negative, or longer than what follows them. None may be allocated.
        val name = "ssh-ed25519".toByteArray()
        fun framed(length: Int, body: Int) =
            ByteBuffer.allocate(8 + name.size + body).putInt(name.size).put(name).putInt(length).put(ByteArray(body)).array()
        for (sig in listOf(
            ByteArray(0),
            ByteArray(3),
            ByteArray(64) { 0x7f },                               // read as a length, about two gigabytes
            ByteArray(64) { 0xff.toByte() },                      // read as a length, negative
            framed(Int.MAX_VALUE, 64),
            framed(-1, 64),
            framed(65, 64),
            framed(64, 64),                                       // well formed, and wrong
        )) assertFalse("${sig.size} bytes", check(sig))
    }

    @Test fun signingIsNotOffered() {
        val v = AndroidEd25519().apply { init() }
        try {
            v.sign()
            throw AssertionError("it signed")
        } catch (e: UnsupportedOperationException) {
            // expected
        }
    }
}
