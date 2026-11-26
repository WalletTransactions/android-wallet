package com.example.wallettransactions.Activities

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

import com.example.wallettransactions.R
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.security.AppLockStore
import com.example.wallettransactions.security.BiometricErrors
import com.example.wallettransactions.security.BiometricKey
import com.example.wallettransactions.security.ForgotPin
import com.example.wallettransactions.security.PinHasher

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экран кода доступа (org/docs/TODO_app_lock.md, Фаза 1) в трёх режимах:
 *
 *  * [MODE_UNLOCK] — замок; его поднимает [AppLock] поверх любой
 *    активности, «Назад» уводит приложение в фон, а не к содержимому под замком;
 *  * [MODE_VERIFY] — текущий код перед выключением или сменой (из «Настроек»),
 *    результат — `RESULT_OK`;
 *  * [MODE_SET] — новый код: «Придумайте» → «Повторите».
 *
 * Проверка и хэширование — PBKDF2, ~150 мс, поэтому в фоне; клавиши на это время глухие.
 *
 * Отпечаток (Фаза 2, п. 3.5) — только на замке: выключение и смена кода требуют код.
 * Диалог поднимается сам один раз на экземпляр замка, дальше — клавишей слева внизу.
 *
 * «Забыли код?» (Фаза 4, [ForgotPin]) — тоже только на замке: код телефона снимает
 * код приложения, а без блокировки экрана остаётся только стереть данные.
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val MODE_UNLOCK = 0
        const val MODE_VERIFY = 1
        const val MODE_SET = 2

        private const val EXTRA_MODE = "LockActivity.mode"
        private const val STATE_FIRST_PIN = "firstPin"
        private const val STATE_BIO_SHOWN = "bioShown"
        private const val STATE_PROMPT_RESET = "promptReset"
        /** Подтверждение кодом телефона до API 30 — системным экраном KeyguardManager. */
        private const val REQUEST_DEVICE_CREDENTIAL = 1

        @JvmStatic
        fun verifyIntent(ctx: Context): Intent {
            return Intent(ctx, LockActivity::class.java).putExtra(EXTRA_MODE, MODE_VERIFY)
        }

        @JvmStatic
        fun setIntent(ctx: Context): Intent {
            return Intent(ctx, LockActivity::class.java).putExtra(EXTRA_MODE, MODE_SET)
        }

        /** Замок, а не проверка из настроек: его [AppLock] второй раз не накрывает. */
        @JvmStatic
        fun isUnlockScreen(a: Activity?): Boolean {
            return a is LockActivity && a.mode == MODE_UNLOCK
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var mode = 0
    private val entered = StringBuilder()
    /** MODE_SET: код с первого шага, ждёт повтора; null — первый шаг. */
    private var firstPin: String? = null
    private var busy = false
    private var keyLost = false
    private var dimmed = false
    /** Диалог отпечатка уже поднимался сам — второй раз только по клавише. */
    private var bioAutoShown = false
    private var biometricPrompt: BiometricPrompt? = null
    /**
     * На что сейчас открыт [biometricPrompt]: вход по отпечатку или код телефона
     * для «Забыли код?». Колбэк у BiometricPrompt на активность один — второй экземпляр
     * перезаписал бы колбэк первого, — поэтому один prompt и флаг.
     */
    private var promptForReset = false

    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var dots: LinearLayout
    private lateinit var keypad: View
    private lateinit var keyLeft: View
    private lateinit var keyOk: View
    private lateinit var keyBio: View

    private val lockoutTick = Runnable { renderLockout() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // До super: AppLock смотрит на mode уже в onActivityResumed.
        mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        title = findViewById(R.id.lockTitle)
        message = findViewById(R.id.lockMessage)
        dots = findViewById(R.id.lockDots)
        keypad = findViewById(R.id.lockKeypad)
        keyLeft = findViewById(R.id.lockKeyLeft)
        keyOk = findViewById(R.id.lockKeyOk)
        keyBio = findViewById(R.id.lockKeyBio)

        if (savedInstanceState != null) {
            firstPin = savedInstanceState.getString(STATE_FIRST_PIN)
            bioAutoShown = savedInstanceState.getBoolean(STATE_BIO_SHOWN)
            promptForReset = savedInstanceState.getBoolean(STATE_PROMPT_RESET)
        }
        // В onCreate, а не по требованию: так BiometricPrompt переживает поворот вместе с колбэком.
        if (mode == MODE_UNLOCK) {
            biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), biometricCallback)
        }
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        // Поворот на шаге «Повторите код» не должен терять первый ввод. Набранное
        // на текущем шаге не сохраняем — проще набрать заново, чем держать код в Bundle.
        if (firstPin != null) out.putString(STATE_FIRST_PIN, firstPin)
        out.putBoolean(STATE_BIO_SHOWN, bioAutoShown)
        out.putBoolean(STATE_PROMPT_RESET, promptForReset)
    }

    override fun onResume() {
        super.onResume()
        // Сняли замок, пока этот экран лежал в стеке (например, код выключен в
        // настройках из другого окна), — показывать нечего.
        if (mode == MODE_UNLOCK && !AppLock.isLocked()) { finishQuietly(); return }
        renderLockout()
        if (isBiometricOn() && !bioAutoShown) {
            bioAutoShown = true
            // post: окно должно получить фокус, иначе диалог на части прошивок не всплывает.
            window.decorView.post { showBiometric() }
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(lockoutTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == MODE_UNLOCK) moveTaskToBack(true)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── Ввод ──────────────────────────────────────────────────────────────────

    fun onLockKey(view: View) {
        val tag = view.tag as String
        // Отпечаток — и во время паузы после ошибок кода, и без ключа проверки кода.
        if ("left" == tag && isBiometricOn()) {
            if (!busy) showBiometric()
            return
        }
        if (busy || keyLost || lockoutRemaining() > 0) return

        if ("del" == tag) {
            if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
        } else if ("left" == tag) {
            if (isFirstSetStep() && entered.length >= AppLockStore.MIN_LENGTH) acceptFirst()
            return
        } else if (entered.length < AppLockStore.MAX_LENGTH) {
            entered.append(tag)
        }
        renderDots()

        if (isFirstSetStep()) {
            // Шесть — максимум: дальше ждать «ОК» незачем.
            if (entered.length == AppLockStore.MAX_LENGTH) acceptFirst()
        } else if (entered.length == expectedLength()) {
            submit()
        }
    }

    private fun isFirstSetStep(): Boolean {
        return mode == MODE_SET && firstPin == null
    }

    private fun expectedLength(): Int {
        val first = firstPin
        if (mode == MODE_SET) return if (first != null) first.length else AppLockStore.MAX_LENGTH
        return AppLockStore.getPinLength(this)
    }

    private fun acceptFirst() {
        firstPin = entered.toString()
        entered.setLength(0)
        render()
    }

    private fun submit() {
        val pin = entered.toString()
        if (mode == MODE_SET) {
            if (pin != firstPin) {
                firstPin = null
                entered.setLength(0)
                render()
                fail(getString(R.string.lock_pins_mismatch))
                return
            }
            runInBackground { AppLockStore.setPin(this, pin); true }
        } else {
            runInBackground { AppLockStore.check(this, pin, System.currentTimeMillis()) }
        }
    }

    private fun runInBackground(task: () -> Boolean) {
        busy = true
        executor.execute {
            var ok: Boolean
            var lost = false
            try {
                ok = task()
            } catch (e: PinHasher.KeyLostException) {
                ok = false
                lost = true
            } catch (e: Exception) {
                ok = false
            }
            val finalOk = ok
            val finalLost = lost
            main.post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                onResult(finalOk, finalLost)
            }
        }
    }

    private fun onResult(ok: Boolean, lost: Boolean) {
        if (ok) {
            if (mode == MODE_UNLOCK) AppLock.unlock()
            else setResult(RESULT_OK)
            finishQuietly()
            return
        }
        entered.setLength(0)
        renderDots()
        if (lost) {
            // Без ключа Keystore код не проверить никак. Снять его — «Забыли код?»
            // (Фаза 4); до неё — только очистка данных приложения в настройках телефона.
            keyLost = true
            dimKeypad(true)
            showMessage(getString(R.string.lock_key_lost), true)
            return
        }
        if (mode == MODE_SET) {
            fail(getString(R.string.lock_save_failed))
        } else if (lockoutRemaining() > 0) {
            shake()
            renderLockout()
        } else {
            val left = AppLockStore.FREE_ATTEMPTS - AppLockStore.getFailedAttempts(this)
            fail(if (left > 0 && left <= 3) getString(R.string.lock_wrong_attempts_left, left) else getString(R.string.lock_wrong))
        }
    }

    private fun fail(text: String) {
        showMessage(text, true)
        shake()
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private fun render() {
        when (mode) {
            MODE_SET -> {
                title.setText(if (firstPin == null) R.string.lock_title_create else R.string.lock_title_repeat)
                showMessage(if (firstPin == null) getString(R.string.lock_hint_length) else "", false)
            }
            MODE_VERIFY -> {
                title.setText(R.string.lock_title_current)
                showMessage("", false)
            }
            else -> {
                title.setText(R.string.lock_title_enter)
                showMessage("", false)
            }
        }
        val bio = isBiometricOn()
        keyOk.visibility = if (isFirstSetStep()) View.VISIBLE else View.GONE
        keyBio.visibility = if (bio) View.VISIBLE else View.GONE
        keyLeft.visibility = if (isFirstSetStep() || bio) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.lockForgot).visibility = if (mode == MODE_UNLOCK) View.VISIBLE else View.GONE
        renderDots()
    }

    /** Точки по длине кода; на первом шаге установки — по набранному, но не меньше четырёх. */
    private fun renderDots() {
        val count = if (isFirstSetStep())
            Math.max(AppLockStore.MIN_LENGTH, entered.length)
        else
            expectedLength()
        val size = dp(14)
        val gap = dp(9)
        dots.removeAllViews()
        for (i in 0 until count) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(gap, 0, gap, 0)
            dot.layoutParams = lp
            dot.setBackgroundResource(if (i < entered.length)
                R.drawable.bg_radio_dot_filled else R.drawable.bg_radio_dot)
            dots.addView(dot)
        }
    }

    /** Пауза после ошибок: клавиатура приглушена, обратный отсчёт раз в секунду. */
    private fun renderLockout() {
        main.removeCallbacks(lockoutTick)
        if (mode == MODE_SET || keyLost) return
        val remaining = lockoutRemaining()
        if (remaining <= 0) {
            if (dimmed) {
                dimKeypad(false)
                showMessage("", false)
            }
            return
        }
        val sec = (remaining + 999) / 1000
        dimKeypad(true)
        showMessage(getString(R.string.lock_lockout, sec / 60, sec % 60), true)
        main.postDelayed(lockoutTick, 1000)
    }

    /** Цифры приглушены; клавиша отпечатка — нет, она работает и во время паузы. */
    private fun dimKeypad(dim: Boolean) {
        dimmed = dim
        val rows = keypad as ViewGroup
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i) as ViewGroup
            for (j in 0 until row.childCount) {
                val key = row.getChildAt(j)
                key.alpha = if (dim && key !== keyLeft) 0.4f else 1f
            }
        }
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    private fun isBiometricOn(): Boolean {
        return mode == MODE_UNLOCK && AppLockStore.isBiometricOn(this)
    }

    private fun showBiometric() {
        val prompt = biometricPrompt
        if (isFinishing || prompt == null) return
        val cipher = BiometricKey.cipherOrNull()
        if (cipher == null) {
            // Отпечатки в телефоне изменились — ключ негоден. Это защита от чужого
            // пальца, добавленного в телефон: включать заново — только из настроек.
            AppLockStore.setBiometricOn(this, false)
            render()
            showMessage(BiometricErrors.keyInvalidated(this), true)
            return
        }
        promptForReset = false
        val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setNegativeButtonText(getString(R.string.lock_biometric_use_pin))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private val biometricCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (promptForReset) {
                promptForReset = false
                onDeviceCredentialConfirmed()
                return
            }
            val crypto = result.cryptoObject
            if (!BiometricKey.confirm(crypto?.cipher)) {
                showMessage(BiometricErrors.lockScreenMessage(this@LockActivity, -1), true)
                return
            }
            AppLockStore.resetFailures(this@LockActivity)
            AppLock.unlock()
            finishQuietly()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (promptForReset) {
                // Передумал или ошибся кодом телефона — остаёмся на замке молча.
                promptForReset = false
                return
            }
            val text = BiometricErrors.lockScreenMessage(this@LockActivity, errorCode)
            if (text != null) showMessage(text, true)
        }
    }

    // ── Забыли код ────────────────────────────────────────────────────────────

    /** Работает и во время паузы после ошибок, и когда ключ проверки кода пропал. */
    fun onForgotPin(view: View) {
        if (busy) return
        val dialog = AlertDialog.Builder(this).setTitle(R.string.lock_forgot_title)
        if (ForgotPin.isDeviceSecure(this)) {
            dialog.setMessage(R.string.lock_forgot_secure)
                    .setPositiveButton(R.string.lock_forgot_device_pin) { _, _ -> confirmDeviceCredential() }
                    .setNeutralButton(R.string.lock_forgot_wipe) { _, _ -> confirmWipe() }
                    .setNegativeButton(R.string.action_cancel, null)
        } else {
            dialog.setMessage(R.string.lock_forgot_insecure)
                    .setPositiveButton(R.string.lock_forgot_wipe) { _, _ -> confirmWipe() }
                    .setNegativeButton(R.string.action_cancel, null)
        }
        dialog.show()
    }

    /** Только код телефона, без отпечатка: отпечаток члена семьи не должен снимать код. */
    @Suppress("DEPRECATION")
    private fun confirmDeviceCredential() {
        val title = getString(R.string.lock_reset_title)
        val subtitle = getString(R.string.lock_reset_subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // BiometricPrompt умеет «только код телефона» лишь с API 30.
            promptForReset = true
            biometricPrompt!!.authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build())
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        val intent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) {
            Toast.makeText(this, R.string.lock_reset_no_keyguard, Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(intent, REQUEST_DEVICE_CREDENTIAL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_CREDENTIAL && resultCode == RESULT_OK) onDeviceCredentialConfirmed()
    }

    private fun onDeviceCredentialConfirmed() {
        ForgotPin.resetAndUnlock(this)
        Toast.makeText(this, R.string.lock_reset_done, Toast.LENGTH_LONG).show()
        finishQuietly()
    }

    /** Два подтверждения: стирание не отменить. */
    private fun confirmWipe() {
        AlertDialog.Builder(this)
                .setTitle(R.string.wipe_title)
                .setMessage(ForgotPin.wipeWarning(this, ForgotPin.isLinkedToServer(this)))
                .setPositiveButton(R.string.wipe_confirm) { _, _ ->
                    AlertDialog.Builder(this)
                            .setTitle(R.string.wipe_irreversible_title)
                            .setMessage(R.string.wipe_irreversible_message)
                            .setPositiveButton(R.string.wipe_confirm_all) { _, _ -> wipe() }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /** Система стирает данные и ключи Keystore приложения и завершает процесс. */
    private fun wipe() {
        val am = getSystemService(ActivityManager::class.java)
        if (am == null || !am.clearApplicationUserData()) {
            Toast.makeText(this, R.string.wipe_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun lockoutRemaining(): Long {
        return if (mode == MODE_SET) 0 else AppLockStore.getLockoutRemainingMs(this, System.currentTimeMillis())
    }

    private fun showMessage(text: String?, error: Boolean) {
        message.text = text
        message.setTextColor(ContextCompat.getColor(this,
                if (error) R.color.colorexpanse else R.color.colorTextSecondary))
    }

    private fun shake() {
        ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, 24f, -24f, 14f, -14f, 6f, -6f, 0f)
                .setDuration(400).start()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }
}
