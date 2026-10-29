package com.votry.productstudio.cloud

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudflareConfigStore(context: Context) {
    data class Config(val accountId: String, val apiToken: String)

    private val prefs = context.getSharedPreferences("ghazi_cloud", Context.MODE_PRIVATE)
    private val alias = "ghazi_cloudflare_token"

    fun isConfigured(): Boolean = load() != null

    fun save(accountId: String, token: String) {
        val cleanId = accountId.trim()
        val cleanToken = token.trim()
        require(cleanId.isNotBlank()) { "Account ID manquant" }
        require(cleanToken.isNotBlank()) { "API token manquant" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)

        prefs.edit()
            .putString("account_id", cleanId)
            .putString("token", Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Config? {
        val accountId = prefs.getString("account_id", null)?.trim().orEmpty()
        val encoded = prefs.getString("token", null) ?: return null
        if (accountId.isBlank()) return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            Config(accountId, cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
