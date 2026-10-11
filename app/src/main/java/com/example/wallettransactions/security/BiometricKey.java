package com.example.wallettransactions.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Ключ входа по отпечатку (org/docs/TODO_app_lock.md, п. 3.2). AES в Android
 * Keystore, работает только сразу после отпечатка: {@code BiometricPrompt}
 * получает {@link Cipher} на этом ключе, и успех засчитывается, лишь когда
 * cipher после него действительно шифрует.
 * <p>
 * Добавили или удалили отпечаток в телефоне, сняли блокировку экрана — система
 * сама делает ключ негодным ({@code setInvalidatedByBiometricEnrollment}), и
 * {@link #cipherOrNull} возвращает null: вход по отпечатку надо включить заново.
 * Отдельный от {@code app_lock_pepper} ({@link PinHasher.KeystorePepper}): тот к
 * отпечаткам не привязан, и смена отпечатков не должна ломать проверку кода.
 */
public final class BiometricKey {

    private static final String TAG = "BiometricKey";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "app_lock_biometric";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private BiometricKey() {}

    /** Пересоздаёт ключ — при каждом включении отпечатка в настройках. */
    public static void create() throws GeneralSecurityException {
        delete();
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Каждое использование — только по свежему отпечатку, не по коду телефона.
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        gen.init(spec.build());
        gen.generateKey();
    }

    /**
     * Cipher для {@code BiometricPrompt.CryptoObject}; null — ключа нет или он стал
     * негодным (отпечатки в телефоне изменились).
     */
    public static Cipher cipherOrNull() {
        try {
            KeyStore ks = KeyStore.getInstance(PROVIDER);
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(ALIAS, null);
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher;
        } catch (KeyPermanentlyInvalidatedException e) {
            Log.i(TAG, "ключ инвалидирован: отпечатки в телефоне изменились");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "cipher", e);
            return null;
        }
    }

    /** Успех отпечатка подтверждён ключом: без свежей аутентификации doFinal бросает. */
    public static boolean confirm(Cipher cipher) {
        if (cipher == null) return false;
        try {
            cipher.doFinal(new byte[]{1});
            return true;
        } catch (Exception e) {
            Log.w(TAG, "confirm", e);
            return false;
        }
    }

    public static void delete() {
        try {
            KeyStore ks = KeyStore.getInstance(PROVIDER);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception e) {
            // Под Robolectric AndroidKeyStore нет — удалять и нечего.
            Log.w(TAG, "delete", e);
        }
    }
}
