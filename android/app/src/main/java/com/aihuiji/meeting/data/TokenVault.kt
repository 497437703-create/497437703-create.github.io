package com.aihuiji.meeting.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Server tokens encrypted with an Android Keystore AES-GCM key (NFR: Token 不明文存储). */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("token_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(profileId: String, token: String?) {
        if (token.isNullOrBlank()) {
            prefs.edit().remove(profileId).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(token.toByteArray())
        prefs.edit().putString(profileId, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(profileId: String): String? = runCatching {
        val blob = Base64.decode(prefs.getString(profileId, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN))
    }.getOrNull()

    fun has(profileId: String): Boolean = prefs.contains(profileId)

    private companion object {
        const val ALIAS = "aihuiji_server_tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
