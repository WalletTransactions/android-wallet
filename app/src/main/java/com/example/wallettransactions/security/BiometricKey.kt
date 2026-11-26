package com.example.wallettransactions.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Ключ входа по отпечатку (org/docs/TODO_app_lock.md, п. 3.2). AES в Android
 * Keystore, работает только сразу после отпечатка: `BiometricPrompt`
 * получает [Cipher] на этом ключе, и успех засчитывается, лишь когда
 * cipher после него действительно шифрует.
 *
 * Добавили или удалили отпечаток в телефоне, сняли блокировку экрана — система
 * сама делает ключ негодным (`setInvalidatedByBiometricEnrollment`), и
 * [cipherOrNull] возвращает null: вход по отпечатку надо включить заново.
 * Отдельный от `app_lock_pepper` ([PinHasher.KeystorePepper]): тот к
 * отпечаткам не привязан, и смена отпечатков не должна ломать проверку кода.
 */
object BiometricKey {

    private const val TAG = "BiometricKey"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "app_lock_biometric"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Пересоздаёт ключ — при каждом включении отпечатка в настройках. */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun create() {
        delete()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Каждое использование — только по свежему отпечатку, не по коду телефона.
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(spec.build())
        gen.generateKey()
    }

    /**
     * Cipher для `BiometricPrompt.CryptoObject`; null — ключа нет или он стал
     * негодным (отпечатки в телефоне изменились).
     */
    @JvmStatic
    fun cipherOrNull(): Cipher? =
        try {
            val ks = KeyStore.getInstance(PROVIDER)
            ks.load(null)
            val key = ks.getKey(ALIAS, null) as SecretKey?
            if (key == null) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.i(TAG, "ключ инвалидирован: отпечатки в телефоне изменились")
            null
        } catch (e: Exception) {
            Log.w(TAG, "cipher", e)
            null
        }

    /** Успех отпечатка подтверждён ключом: без свежей аутентификации doFinal бросает. */
    @JvmStatic
    fun confirm(cipher: Cipher?): Boolean {
        if (cipher == null) return false
        return try {
            cipher.doFinal(byteArrayOf(1))
            true
        } catch (e: Exception) {
            Log.w(TAG, "confirm", e)
            false
        }
    }

    @JvmStatic
    fun delete() {
        try {
            val ks = KeyStore.getInstance(PROVIDER)
            ks.load(null)
            ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Под Robolectric AndroidKeyStore нет — удалять и нечего.
            Log.w(TAG, "delete", e)
        }
    }
}
