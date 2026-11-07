package com.example.wallettransactions.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Код доступа, отпечаток и «Забыли код?» (org/docs/TODO_app_lock.md): хэш, паузы
 * после ошибок, флаг отпечатка, сброс кода, когда запирать. AndroidKeyStore под Robolectric нет — ключ Keystore подменён обычным HMAC.
 */
@RunWith(RobolectricTestRunner.class)
public class AppLockTest {

    /** HMAC фиксированным ключом; {@code lost} — ключ «пропал» из Keystore. */
    private static final class FakePepper implements PinHasher.Pepper {
        boolean lost;

        @Override public void ensureKey() { lost = false; }

        @Override
        public byte[] apply(byte[] data) throws GeneralSecurityException {
            if (lost) throw new PinHasher.KeyLostException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        }
    }

    private static final long NOW = 1_800_000_000_000L;

    private Context ctx;
    private FakePepper pepper;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        pepper = new FakePepper();
        AppLockStore.setPepperForTest(pepper);
        AppLockStore.clearPin(ctx);
        AppLock.resetForTest(false);
    }

    @After
    public void tearDown() {
        AppLockStore.setPepperForTest(new PinHasher.KeystorePepper());
    }

    // ── Хэш ───────────────────────────────────────────────────────────────────

    @Test
    public void hash_dependsOnSaltAndPepper() throws Exception {
        byte[] salt = PinHasher.newSalt();
        byte[] a = PinHasher.hash("1234", salt, 1000, pepper);
        assertArrayEquals(a, PinHasher.hash("1234", salt, 1000, pepper));
        assertFalse(Arrays.equals(a, PinHasher.hash("1234", PinHasher.newSalt(), 1000, pepper)));
        assertFalse(Arrays.equals(a, PinHasher.hash("1235", salt, 1000, pepper)));
    }

    @Test
    public void setPin_storesNoPlainCode() throws Exception {
        AppLockStore.setPin(ctx, "482916");
        String all = ctx.getSharedPreferences("app_lock", Context.MODE_PRIVATE).getAll().toString();
        assertFalse(all.contains("482916"));
        assertTrue(AppLockStore.isPinSet(ctx));
        assertEquals(6, AppLockStore.getPinLength(ctx));
    }

    @Test
    public void check_rightAndWrong() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        assertFalse(AppLockStore.check(ctx, "4321", NOW));
        assertEquals(1, AppLockStore.getFailedAttempts(ctx));
        assertTrue(AppLockStore.check(ctx, "1234", NOW));
        assertEquals(0, AppLockStore.getFailedAttempts(ctx));
    }

    @Test
    public void isValidPin_onlyFourToSixDigits() {
        assertTrue(AppLockStore.isValidPin("0000"));
        assertTrue(AppLockStore.isValidPin("123456"));
        assertFalse(AppLockStore.isValidPin("123"));
        assertFalse(AppLockStore.isValidPin("1234567"));
        assertFalse(AppLockStore.isValidPin("12a4"));
    }

    @Test
    public void check_keyLost_throws() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        pepper.lost = true;
        try {
            AppLockStore.check(ctx, "1234", NOW);
            fail();
        } catch (PinHasher.KeyLostException expected) {
            // Ошибка ключа — не неверный код: счётчик не растёт.
            assertEquals(0, AppLockStore.getFailedAttempts(ctx));
        }
    }

    // ── Паузы ─────────────────────────────────────────────────────────────────

    @Test
    public void lockoutMs_growsAndCaps() {
        assertEquals(0, AppLockStore.lockoutMs(4));
        assertEquals(30_000, AppLockStore.lockoutMs(5));
        assertEquals(60_000, AppLockStore.lockoutMs(6));
        assertEquals(120_000, AppLockStore.lockoutMs(7));
        assertEquals(AppLockStore.MAX_LOCKOUT_MS, AppLockStore.lockoutMs(50));
    }

    @Test
    public void fifthError_locksOut_evenForRightCode() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        for (int i = 0; i < 5; i++) assertFalse(AppLockStore.check(ctx, "0000", NOW));
        assertEquals(30_000, AppLockStore.getLockoutRemainingMs(ctx, NOW));
        // Во время паузы не проверяем вовсе — и верный код не проходит.
        assertFalse(AppLockStore.check(ctx, "1234", NOW + 10_000));
        assertTrue(AppLockStore.check(ctx, "1234", NOW + 30_000));
    }

    @Test
    public void lockout_clockSetBack_cappedAtMax() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        for (int i = 0; i < 5; i++) AppLockStore.check(ctx, "0000", NOW);
        assertEquals(AppLockStore.MAX_LOCKOUT_MS,
                AppLockStore.getLockoutRemainingMs(ctx, NOW - 24 * 3600_000L));
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    @Test
    public void biometric_offWithoutPin_andClearedWithPin() throws Exception {
        AppLockStore.setBiometricOn(ctx, true);
        assertFalse("без кода отпечаток не включается", AppLockStore.isBiometricOn(ctx));

        AppLockStore.setPin(ctx, "1234");
        AppLockStore.setBiometricOn(ctx, true);
        assertTrue(AppLockStore.isBiometricOn(ctx));

        AppLockStore.clearPin(ctx);
        AppLockStore.setPin(ctx, "1234");
        assertFalse("выключение кода снимает и отпечаток", AppLockStore.isBiometricOn(ctx));
    }

    @Test
    public void resetFailures_liftsLockout() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        for (int i = 0; i < 5; i++) AppLockStore.check(ctx, "0000", NOW);
        AppLockStore.resetFailures(ctx);
        assertEquals(0, AppLockStore.getLockoutRemainingMs(ctx, NOW));
        assertEquals(0, AppLockStore.getFailedAttempts(ctx));
    }

    @Test
    public void biometricErrors_reasons() {
        assertEquals("Сначала включите код доступа",
                BiometricErrors.unavailableReason(ctx, false, BiometricManager.BIOMETRIC_SUCCESS));
        assertNull(BiometricErrors.unavailableReason(ctx, true, BiometricManager.BIOMETRIC_SUCCESS));
        assertNotNull(BiometricErrors.unavailableReason(ctx, true, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE));
        assertTrue(BiometricErrors.canEnroll(true, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED));
        assertFalse(BiometricErrors.canEnroll(false, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED));
    }

    @Test
    public void biometricErrors_lockScreen() {
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_NEGATIVE_BUTTON));
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_USER_CANCELED));
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_CANCELED));
        assertEquals("Отпечаток временно недоступен — введите код",
                BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_LOCKOUT));
        assertEquals("Отпечаток временно недоступен — введите код",
                BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_LOCKOUT_PERMANENT));
        assertNotNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_HW_UNAVAILABLE));
    }

    @Test
    public void hideInRecents_onlyWithPin_defaultOn() throws Exception {
        assertFalse(AppLockStore.isHideInRecents(ctx));
        AppLockStore.setPin(ctx, "1234");
        assertTrue(AppLockStore.isHideInRecents(ctx));
        AppLockStore.setHideInRecents(ctx, false);
        assertFalse(AppLockStore.isHideInRecents(ctx));
        AppLockStore.setHideInRecents(ctx, true);
    }

    // ── Забыли код ────────────────────────────────────────────────────────────

    @Test
    public void forgotPin_resetClearsPinBiometricAndLock() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        AppLockStore.setBiometricOn(ctx, true);
        for (int i = 0; i < 5; i++) AppLockStore.check(ctx, "0000", NOW);
        AppLock.resetForTest(true);

        ForgotPin.resetAndUnlock(ctx);

        assertFalse(AppLock.isLocked());
        assertFalse(AppLockStore.isPinSet(ctx));
        assertFalse(AppLockStore.isBiometricOn(ctx));
        assertEquals(0, AppLockStore.getLockoutRemainingMs(ctx, NOW));
        // Новый код — с чистого листа, старый отпечаток не воскресает.
        AppLockStore.setPin(ctx, "5678");
        assertFalse(AppLockStore.isBiometricOn(ctx));
    }

    @Test
    public void forgotPin_wipeWarning_dependsOnServerLink() {
        assertTrue(ForgotPin.wipeWarning(ctx, true).contains("Связь с Google аккаунтом"));
        assertTrue(ForgotPin.wipeWarning(ctx, false).contains("восстановить их будет неоткуда"));
    }

    // ── Когда запирать ────────────────────────────────────────────────────────

    @Test
    public void locksAfterTimeout_notBefore() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        AppLockStore.setTimeoutMs(ctx, 60_000);

        AppLock.onAppStopped(1_000);
        AppLock.onAppStarted(ctx, 30_000);
        assertFalse(AppLock.isLocked());

        AppLock.onAppStopped(100_000);
        AppLock.onAppStarted(ctx, 160_000);
        assertTrue(AppLock.isLocked());
    }

    @Test
    public void noPin_neverLocks() {
        AppLockStore.setTimeoutMs(ctx, 0);
        AppLock.onAppStopped(1_000);
        AppLock.onAppStarted(ctx, 10_000_000);
        assertFalse(AppLock.isLocked());
    }

    @Test
    public void allowNextLeave_skipsOnlyOneReturn() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        AppLockStore.setTimeoutMs(ctx, 0);

        AppLock.allowNextLeave();
        long t = android.os.SystemClock.elapsedRealtime();
        AppLock.onAppStopped(t);
        AppLock.onAppStarted(ctx, t + 5_000);
        assertFalse(AppLock.isLocked());

        AppLock.onAppStopped(t + 6_000);
        AppLock.onAppStarted(ctx, t + 7_000);
        assertTrue(AppLock.isLocked());
    }

    @Test
    public void allowNextLeave_expires() throws Exception {
        AppLockStore.setPin(ctx, "1234");
        AppLockStore.setTimeoutMs(ctx, 0);

        AppLock.allowNextLeave();
        long t = android.os.SystemClock.elapsedRealtime();
        AppLock.onAppStopped(t);
        AppLock.onAppStarted(ctx, t + AppLock.LEAVE_GRACE_MS);
        assertTrue(AppLock.isLocked());
    }
}
