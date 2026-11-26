package com.example.wallettransactions.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Код доступа, отпечаток и «Забыли код?» (org/docs/TODO_app_lock.md): хэш, паузы
 * после ошибок, флаг отпечатка, сброс кода, когда запирать. AndroidKeyStore под Robolectric нет — ключ Keystore подменён обычным HMAC.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockTest {

    /** HMAC фиксированным ключом; `lost` — ключ «пропал» из Keystore. */
    private class FakePepper : PinHasher.Pepper {
        var lost = false

        override fun ensureKey() {
            lost = false
        }

        @Throws(GeneralSecurityException::class)
        override fun apply(data: ByteArray): ByteArray {
            if (lost) throw PinHasher.KeyLostException()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("test-key".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data)
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
    }

    private lateinit var ctx: Context
    private lateinit var pepper: FakePepper

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        pepper = FakePepper()
        AppLockStore.setPepperForTest(pepper)
        AppLockStore.clearPin(ctx)
        AppLock.resetForTest(false)
    }

    @After
    fun tearDown() {
        AppLockStore.setPepperForTest(PinHasher.KeystorePepper())
    }

    // ── Хэш ───────────────────────────────────────────────────────────────────

    @Test
    fun hash_dependsOnSaltAndPepper() {
        val salt = PinHasher.newSalt()
        val a = PinHasher.hash("1234", salt, 1000, pepper)
        assertArrayEquals(a, PinHasher.hash("1234", salt, 1000, pepper))
        assertFalse(a.contentEquals(PinHasher.hash("1234", PinHasher.newSalt(), 1000, pepper)))
        assertFalse(a.contentEquals(PinHasher.hash("1235", salt, 1000, pepper)))
    }

    @Test
    fun setPin_storesNoPlainCode() {
        AppLockStore.setPin(ctx, "482916")
        val all = ctx.getSharedPreferences("app_lock", Context.MODE_PRIVATE).all.toString()
        assertFalse(all.contains("482916"))
        assertTrue(AppLockStore.isPinSet(ctx))
        assertEquals(6, AppLockStore.getPinLength(ctx))
    }

    @Test
    fun check_rightAndWrong() {
        AppLockStore.setPin(ctx, "1234")
        assertFalse(AppLockStore.check(ctx, "4321", NOW))
        assertEquals(1, AppLockStore.getFailedAttempts(ctx))
        assertTrue(AppLockStore.check(ctx, "1234", NOW))
        assertEquals(0, AppLockStore.getFailedAttempts(ctx))
    }

    @Test
    fun isValidPin_onlyFourToSixDigits() {
        assertTrue(AppLockStore.isValidPin("0000"))
        assertTrue(AppLockStore.isValidPin("123456"))
        assertFalse(AppLockStore.isValidPin("123"))
        assertFalse(AppLockStore.isValidPin("1234567"))
        assertFalse(AppLockStore.isValidPin("12a4"))
    }

    @Test
    fun check_keyLost_throws() {
        AppLockStore.setPin(ctx, "1234")
        pepper.lost = true
        try {
            AppLockStore.check(ctx, "1234", NOW)
            fail()
        } catch (expected: PinHasher.KeyLostException) {
            // Ошибка ключа — не неверный код: счётчик не растёт.
            assertEquals(0, AppLockStore.getFailedAttempts(ctx))
        }
    }

    // ── Паузы ─────────────────────────────────────────────────────────────────

    @Test
    fun lockoutMs_growsAndCaps() {
        assertEquals(0L, AppLockStore.lockoutMs(4))
        assertEquals(30_000L, AppLockStore.lockoutMs(5))
        assertEquals(60_000L, AppLockStore.lockoutMs(6))
        assertEquals(120_000L, AppLockStore.lockoutMs(7))
        assertEquals(AppLockStore.MAX_LOCKOUT_MS, AppLockStore.lockoutMs(50))
    }

    @Test
    fun fifthError_locksOut_evenForRightCode() {
        AppLockStore.setPin(ctx, "1234")
        for (i in 0 until 5) assertFalse(AppLockStore.check(ctx, "0000", NOW))
        assertEquals(30_000L, AppLockStore.getLockoutRemainingMs(ctx, NOW))
        // Во время паузы не проверяем вовсе — и верный код не проходит.
        assertFalse(AppLockStore.check(ctx, "1234", NOW + 10_000))
        assertTrue(AppLockStore.check(ctx, "1234", NOW + 30_000))
    }

    @Test
    fun lockout_clockSetBack_cappedAtMax() {
        AppLockStore.setPin(ctx, "1234")
        for (i in 0 until 5) AppLockStore.check(ctx, "0000", NOW)
        assertEquals(
            AppLockStore.MAX_LOCKOUT_MS,
            AppLockStore.getLockoutRemainingMs(ctx, NOW - 24 * 3600_000L)
        )
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    @Test
    fun biometric_offWithoutPin_andClearedWithPin() {
        AppLockStore.setBiometricOn(ctx, true)
        assertFalse("без кода отпечаток не включается", AppLockStore.isBiometricOn(ctx))

        AppLockStore.setPin(ctx, "1234")
        AppLockStore.setBiometricOn(ctx, true)
        assertTrue(AppLockStore.isBiometricOn(ctx))

        AppLockStore.clearPin(ctx)
        AppLockStore.setPin(ctx, "1234")
        assertFalse("выключение кода снимает и отпечаток", AppLockStore.isBiometricOn(ctx))
    }

    @Test
    fun resetFailures_liftsLockout() {
        AppLockStore.setPin(ctx, "1234")
        for (i in 0 until 5) AppLockStore.check(ctx, "0000", NOW)
        AppLockStore.resetFailures(ctx)
        assertEquals(0L, AppLockStore.getLockoutRemainingMs(ctx, NOW))
        assertEquals(0, AppLockStore.getFailedAttempts(ctx))
    }

    @Test
    fun biometricErrors_reasons() {
        assertEquals(
            "Сначала включите код доступа",
            BiometricErrors.unavailableReason(ctx, false, BiometricManager.BIOMETRIC_SUCCESS)
        )
        assertNull(BiometricErrors.unavailableReason(ctx, true, BiometricManager.BIOMETRIC_SUCCESS))
        assertNotNull(BiometricErrors.unavailableReason(ctx, true, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertTrue(BiometricErrors.canEnroll(true, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertFalse(BiometricErrors.canEnroll(false, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun biometricErrors_lockScreen() {
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_USER_CANCELED))
        assertNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_CANCELED))
        assertEquals(
            "Отпечаток временно недоступен — введите код",
            BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_LOCKOUT)
        )
        assertEquals(
            "Отпечаток временно недоступен — введите код",
            BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        )
        assertNotNull(BiometricErrors.lockScreenMessage(ctx, BiometricPrompt.ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun hideInRecents_onlyWithPin_defaultOn() {
        assertFalse(AppLockStore.isHideInRecents(ctx))
        AppLockStore.setPin(ctx, "1234")
        assertTrue(AppLockStore.isHideInRecents(ctx))
        AppLockStore.setHideInRecents(ctx, false)
        assertFalse(AppLockStore.isHideInRecents(ctx))
        AppLockStore.setHideInRecents(ctx, true)
    }

    // ── Забыли код ────────────────────────────────────────────────────────────

    @Test
    fun forgotPin_resetClearsPinBiometricAndLock() {
        AppLockStore.setPin(ctx, "1234")
        AppLockStore.setBiometricOn(ctx, true)
        for (i in 0 until 5) AppLockStore.check(ctx, "0000", NOW)
        AppLock.resetForTest(true)

        ForgotPin.resetAndUnlock(ctx)

        assertFalse(AppLock.isLocked())
        assertFalse(AppLockStore.isPinSet(ctx))
        assertFalse(AppLockStore.isBiometricOn(ctx))
        assertEquals(0L, AppLockStore.getLockoutRemainingMs(ctx, NOW))
        // Новый код — с чистого листа, старый отпечаток не воскресает.
        AppLockStore.setPin(ctx, "5678")
        assertFalse(AppLockStore.isBiometricOn(ctx))
    }

    @Test
    fun forgotPin_wipeWarning_dependsOnServerLink() {
        assertTrue(ForgotPin.wipeWarning(ctx, true).contains("Связь с Google аккаунтом"))
        assertTrue(ForgotPin.wipeWarning(ctx, false).contains("восстановить их будет неоткуда"))
    }

    // ── Когда запирать ────────────────────────────────────────────────────────

    @Test
    fun locksAfterTimeout_notBefore() {
        AppLockStore.setPin(ctx, "1234")
        AppLockStore.setTimeoutMs(ctx, 60_000)

        AppLock.onAppStopped(1_000)
        AppLock.onAppStarted(ctx, 30_000)
        assertFalse(AppLock.isLocked())

        AppLock.onAppStopped(100_000)
        AppLock.onAppStarted(ctx, 160_000)
        assertTrue(AppLock.isLocked())
    }

    @Test
    fun noPin_neverLocks() {
        AppLockStore.setTimeoutMs(ctx, 0)
        AppLock.onAppStopped(1_000)
        AppLock.onAppStarted(ctx, 10_000_000)
        assertFalse(AppLock.isLocked())
    }

    @Test
    fun allowNextLeave_skipsOnlyOneReturn() {
        AppLockStore.setPin(ctx, "1234")
        AppLockStore.setTimeoutMs(ctx, 0)

        AppLock.allowNextLeave()
        val t = android.os.SystemClock.elapsedRealtime()
        AppLock.onAppStopped(t)
        AppLock.onAppStarted(ctx, t + 5_000)
        assertFalse(AppLock.isLocked())

        AppLock.onAppStopped(t + 6_000)
        AppLock.onAppStarted(ctx, t + 7_000)
        assertTrue(AppLock.isLocked())
    }

    @Test
    fun allowNextLeave_expires() {
        AppLockStore.setPin(ctx, "1234")
        AppLockStore.setTimeoutMs(ctx, 0)

        AppLock.allowNextLeave()
        val t = android.os.SystemClock.elapsedRealtime()
        AppLock.onAppStopped(t)
        AppLock.onAppStarted(ctx, t + AppLock.LEAVE_GRACE_MS)
        assertTrue(AppLock.isLocked())
    }
}
