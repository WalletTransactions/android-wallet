package com.example.wallettransactions.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хэш кода доступа (org/docs/TODO_app_lock.md, п. 1.3): PBKDF2-SHA256 с солью, затем
 * HMAC ключом из Android Keystore ([Pepper]). Код из 4–6 цифр — 10⁴–10⁶
 * вариантов, один PBKDF2 такой перебор на видеокарте не держит; без ключа, который
 * не покидает этот телефон, хэш вне него не перебрать.
 */
object PinHasher {

    /** ~150 мс на телефоне среднего уровня; хранится рядом с хэшем, можно поднять. */
    const val DEFAULT_ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    /** Второй шаг хэша. В тестах — подмена: AndroidKeyStore под Robolectric нет. */
    interface Pepper {
        /** Ключ заводится только при установке кода — см. [KeystorePepper]. */
        @Throws(GeneralSecurityException::class)
        fun ensureKey()

        /** @throws KeyLostException ключа нет — код больше не проверить */
        @Throws(GeneralSecurityException::class)
        fun apply(data: ByteArray): ByteArray
    }

    /** Ключ Keystore пропал (повреждённый Keystore, сброс блокировки экрана на некоторых прошивках). */
    open class KeyLostException internal constructor() :
        GeneralSecurityException("app lock pepper key is missing")

    @JvmStatic
    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun hash(pin: String, salt: ByteArray, iterations: Int, pepper: Pepper): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return pepper.apply(stretched)
    }

    /** Сравнение за постоянное время — по длительности не угадать, сколько байт совпало. */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun matches(pin: String, salt: ByteArray, iterations: Int, expected: ByteArray, pepper: Pepper): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations, pepper), expected)

    /** HMAC-SHA256 неизвлекаемым ключом Android Keystore. */
    class KeystorePepper : Pepper {

        @Throws(GeneralSecurityException::class)
        override fun ensureKey() {
            if (loadKey() != null) return
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
            gen.generateKey()
        }

        @Throws(GeneralSecurityException::class)
        override fun apply(data: ByteArray): ByteArray {
            // Не создаём ключ здесь: новый ключ молча сделал бы любой введённый код
            // неверным, и пользователь упирался бы в паузы, не понимая почему.
            val key = loadKey() ?: throw KeyLostException()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            return mac.doFinal(data)
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
            const val ALIAS = "app_lock_pepper"

            @Throws(GeneralSecurityException::class)
            fun loadKey(): SecretKey? =
                try {
                    val ks = KeyStore.getInstance(PROVIDER)
                    ks.load(null)
                    ks.getKey(ALIAS, null) as SecretKey?
                } catch (e: java.io.IOException) {
                    throw GeneralSecurityException(e)
                }
        }
    }
}
