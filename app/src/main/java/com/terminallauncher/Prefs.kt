package com.terminallauncher

import android.content.SharedPreferences
import android.util.Base64
import com.terminallauncher.shell.Store
import com.terminallauncher.shell.Vault
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PrefsStore(private val prefs: SharedPreferences) : Store {
    override fun get(key: String): String? = prefs.getString("s_$key", null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("s_$key") else putString("s_$key", value) }.apply()
    }
}

/** Keeps the launcher password as a salted PBKDF2 hash. The password itself is never stored. */
class PrefsVault(private val prefs: SharedPreferences) : Vault {
    override val isSet: Boolean get() = prefs.contains(HASH)

    override fun matches(password: String): Boolean {
        val salt = decode(prefs.getString(SALT, null)) ?: return false
        val want = decode(prefs.getString(HASH, null)) ?: return false
        return MessageDigest.isEqual(want, hash(password, salt))
    }

    override fun set(password: String?) {
        if (password == null) {
            prefs.edit().remove(HASH).remove(SALT).apply()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH, Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
            .apply()
    }

    private fun decode(s: String?): ByteArray? = s?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private companion object {
        const val HASH = "pw_hash"
        const val SALT = "pw_salt"
        const val ITERATIONS = 60_000
    }
}
