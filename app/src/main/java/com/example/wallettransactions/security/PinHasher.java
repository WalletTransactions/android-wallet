package com.example.wallettransactions.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Хэш кода доступа (org/docs/TODO_app_lock.md, п. 1.3): PBKDF2-SHA256 с солью, затем
 * HMAC ключом из Android Keystore ({@link Pepper}). Код из 4–6 цифр — 10⁴–10⁶
 * вариантов, один PBKDF2 такой перебор на видеокарте не держит; без ключа, который
 * не покидает этот телефон, хэш вне него не перебрать.
 */
public final class PinHasher {

    /** ~150 мс на телефоне среднего уровня; хранится рядом с хэшем, можно поднять. */
    public static final int DEFAULT_ITERATIONS = 100_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    /** Второй шаг хэша. В тестах — подмена: AndroidKeyStore под Robolectric нет. */
    public interface Pepper {
        /** Ключ заводится только при установке кода — см. {@link KeystorePepper}. */
        void ensureKey() throws GeneralSecurityException;

        /** @throws KeyLostException ключа нет — код больше не проверить */
        byte[] apply(byte[] data) throws GeneralSecurityException;
    }

    /** Ключ Keystore пропал (повреждённый Keystore, сброс блокировки экрана на некоторых прошивках). */
    public static class KeyLostException extends GeneralSecurityException {
        KeyLostException() { super("app lock pepper key is missing"); }
    }

    private PinHasher() {}

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static byte[] hash(String pin, byte[] salt, int iterations, Pepper pepper)
            throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS);
        byte[] stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        return pepper.apply(stretched);
    }

    /** Сравнение за постоянное время — по длительности не угадать, сколько байт совпало. */
    public static boolean matches(String pin, byte[] salt, int iterations, byte[] expected, Pepper pepper)
            throws GeneralSecurityException {
        return MessageDigest.isEqual(hash(pin, salt, iterations, pepper), expected);
    }

    /** HMAC-SHA256 неизвлекаемым ключом Android Keystore. */
    public static final class KeystorePepper implements Pepper {
        private static final String PROVIDER = "AndroidKeyStore";
        private static final String ALIAS = "app_lock_pepper";

        @Override
        public void ensureKey() throws GeneralSecurityException {
            if (loadKey() != null) return;
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER);
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build());
            gen.generateKey();
        }

        @Override
        public byte[] apply(byte[] data) throws GeneralSecurityException {
            // Не создаём ключ здесь: новый ключ молча сделал бы любой введённый код
            // неверным, и пользователь упирался бы в паузы, не понимая почему.
            SecretKey key = loadKey();
            if (key == null) throw new KeyLostException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        }

        private static SecretKey loadKey() throws GeneralSecurityException {
            try {
                KeyStore ks = KeyStore.getInstance(PROVIDER);
                ks.load(null);
                return (SecretKey) ks.getKey(ALIAS, null);
            } catch (java.io.IOException e) {
                throw new GeneralSecurityException(e);
            }
        }
    }
}
