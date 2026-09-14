package com.freebuff.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * 基于 Android Keystore 的 AES/GCM 加解密。
 * - 密钥仅存在于系统 Keystore,应用进程拿不到明文密钥
 * - 每次加密生成随机 IV,密文格式: base64(iv[12] + ciphertext)
 * - API 26+ 可用(与 minSdk 对齐)
 */
open class CryptoManager(private val alias: String = KEY_ALIAS) {

    companion object {
        private const val KEY_ALIAS = "freebuff_secret_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    // 惰性初始化:仅在真正读写密钥时才访问 Android Keystore,
    // 让依赖本类的 Repository 在纯 JVM 单测中可直接实例化(网络方法不触碰密钥)。
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** 加密任意字符串,返回 base64(iv + ciphertext)。 */
    open fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    /** 解密 base64(iv + ciphertext);格式非法或密钥不可用时返回空串。 */
    open fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size < IV_BYTES + GCM_TAG_BITS / 8) return ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipherBytes = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** 是否已经生成过密钥。 */
    fun hasKey(): Boolean = keyStore.containsAlias(alias)

    /** 强制轮换密钥(清空别名后下次自动重建)。 */
    fun clearKey() {
        keyStore.deleteEntry(alias)
    }
}

/** 生产随机字节(测试辅助)。 */
@Suppress("unused")
internal fun randomBytes(n: Int): ByteArray = ByteArray(n) { Random.nextInt(256).toByte() }
