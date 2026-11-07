package com.example.wallettransactions.Activities;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.R;
import com.example.wallettransactions.security.AppLock;
import com.example.wallettransactions.security.AppLockStore;
import com.example.wallettransactions.security.BiometricErrors;
import com.example.wallettransactions.security.BiometricKey;
import com.example.wallettransactions.security.ForgotPin;
import com.example.wallettransactions.security.PinHasher;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/**
 * Экран кода доступа (org/docs/TODO_app_lock.md, Фаза 1) в трёх режимах:
 * <ul>
 *   <li>{@link #MODE_UNLOCK} — замок; его поднимает {@link AppLock} поверх любой
 *       активности, «Назад» уводит приложение в фон, а не к содержимому под замком;</li>
 *   <li>{@link #MODE_VERIFY} — текущий код перед выключением или сменой (из «Настроек»),
 *       результат — {@code RESULT_OK};</li>
 *   <li>{@link #MODE_SET} — новый код: «Придумайте» → «Повторите».</li>
 * </ul>
 * Проверка и хэширование — PBKDF2, ~150 мс, поэтому в фоне; клавиши на это время глухие.
 * <p>
 * Отпечаток (Фаза 2, п. 3.5) — только на замке: выключение и смена кода требуют код.
 * Диалог поднимается сам один раз на экземпляр замка, дальше — клавишей слева внизу.
 * <p>
 * «Забыли код?» (Фаза 4, {@link ForgotPin}) — тоже только на замке: код телефона снимает
 * код приложения, а без блокировки экрана остаётся только стереть данные.
 */
public class LockActivity extends AppCompatActivity {

    public static final int MODE_UNLOCK = 0;
    public static final int MODE_VERIFY = 1;
    public static final int MODE_SET = 2;

    private static final String EXTRA_MODE = "LockActivity.mode";
    private static final String STATE_FIRST_PIN = "firstPin";
    private static final String STATE_BIO_SHOWN = "bioShown";
    private static final String STATE_PROMPT_RESET = "promptReset";
    /** Подтверждение кодом телефона до API 30 — системным экраном KeyguardManager. */
    private static final int REQUEST_DEVICE_CREDENTIAL = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private int mode;
    private final StringBuilder entered = new StringBuilder();
    /** MODE_SET: код с первого шага, ждёт повтора; null — первый шаг. */
    private String firstPin;
    private boolean busy;
    private boolean keyLost;
    private boolean dimmed;
    /** Диалог отпечатка уже поднимался сам — второй раз только по клавише. */
    private boolean bioAutoShown;
    private BiometricPrompt biometricPrompt;
    /**
     * На что сейчас открыт {@link #biometricPrompt}: вход по отпечатку или код телефона
     * для «Забыли код?». Колбэк у BiometricPrompt на активность один — второй экземпляр
     * перезаписал бы колбэк первого, — поэтому один prompt и флаг.
     */
    private boolean promptForReset;

    private TextView title;
    private TextView message;
    private LinearLayout dots;
    private View keypad;
    private View keyLeft;
    private View keyOk;
    private View keyBio;

    private final Runnable lockoutTick = this::renderLockout;

    public static Intent verifyIntent(Context ctx) {
        return new Intent(ctx, LockActivity.class).putExtra(EXTRA_MODE, MODE_VERIFY);
    }

    public static Intent setIntent(Context ctx) {
        return new Intent(ctx, LockActivity.class).putExtra(EXTRA_MODE, MODE_SET);
    }

    /** Замок, а не проверка из настроек: его {@link AppLock} второй раз не накрывает. */
    public static boolean isUnlockScreen(Activity a) {
        return a instanceof LockActivity && ((LockActivity) a).mode == MODE_UNLOCK;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // До super: AppLock смотрит на mode уже в onActivityResumed.
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        title = findViewById(R.id.lockTitle);
        message = findViewById(R.id.lockMessage);
        dots = findViewById(R.id.lockDots);
        keypad = findViewById(R.id.lockKeypad);
        keyLeft = findViewById(R.id.lockKeyLeft);
        keyOk = findViewById(R.id.lockKeyOk);
        keyBio = findViewById(R.id.lockKeyBio);

        if (savedInstanceState != null) {
            firstPin = savedInstanceState.getString(STATE_FIRST_PIN);
            bioAutoShown = savedInstanceState.getBoolean(STATE_BIO_SHOWN);
            promptForReset = savedInstanceState.getBoolean(STATE_PROMPT_RESET);
        }
        // В onCreate, а не по требованию: так BiometricPrompt переживает поворот вместе с колбэком.
        if (mode == MODE_UNLOCK) {
            biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), biometricCallback);
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Поворот на шаге «Повторите код» не должен терять первый ввод. Набранное
        // на текущем шаге не сохраняем — проще набрать заново, чем держать код в Bundle.
        if (firstPin != null) out.putString(STATE_FIRST_PIN, firstPin);
        out.putBoolean(STATE_BIO_SHOWN, bioAutoShown);
        out.putBoolean(STATE_PROMPT_RESET, promptForReset);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Сняли замок, пока этот экран лежал в стеке (например, код выключен в
        // настройках из другого окна), — показывать нечего.
        if (mode == MODE_UNLOCK && !AppLock.isLocked()) { finishQuietly(); return; }
        renderLockout();
        if (isBiometricOn() && !bioAutoShown) {
            bioAutoShown = true;
            // post: окно должно получить фокус, иначе диалог на части прошивок не всплывает.
            getWindow().getDecorView().post(this::showBiometric);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(lockoutTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_UNLOCK) moveTaskToBack(true);
        else super.onBackPressed();
    }

    // ── Ввод ──────────────────────────────────────────────────────────────────

    public void onLockKey(View view) {
        String tag = (String) view.getTag();
        // Отпечаток — и во время паузы после ошибок кода, и без ключа проверки кода.
        if ("left".equals(tag) && isBiometricOn()) {
            if (!busy) showBiometric();
            return;
        }
        if (busy || keyLost || lockoutRemaining() > 0) return;

        if ("del".equals(tag)) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
        } else if ("left".equals(tag)) {
            if (isFirstSetStep() && entered.length() >= AppLockStore.MIN_LENGTH) acceptFirst();
            return;
        } else if (entered.length() < AppLockStore.MAX_LENGTH) {
            entered.append(tag);
        }
        renderDots();

        if (isFirstSetStep()) {
            // Шесть — максимум: дальше ждать «ОК» незачем.
            if (entered.length() == AppLockStore.MAX_LENGTH) acceptFirst();
        } else if (entered.length() == expectedLength()) {
            submit();
        }
    }

    private boolean isFirstSetStep() {
        return mode == MODE_SET && firstPin == null;
    }

    private int expectedLength() {
        if (mode == MODE_SET) return firstPin != null ? firstPin.length() : AppLockStore.MAX_LENGTH;
        return AppLockStore.getPinLength(this);
    }

    private void acceptFirst() {
        firstPin = entered.toString();
        entered.setLength(0);
        render();
    }

    private void submit() {
        String pin = entered.toString();
        if (mode == MODE_SET) {
            if (!pin.equals(firstPin)) {
                firstPin = null;
                entered.setLength(0);
                render();
                fail(getString(R.string.lock_pins_mismatch));
                return;
            }
            runInBackground(() -> { AppLockStore.setPin(this, pin); return true; });
        } else {
            runInBackground(() -> AppLockStore.check(this, pin, System.currentTimeMillis()));
        }
    }

    private interface PinTask {
        boolean run() throws Exception;
    }

    private void runInBackground(PinTask task) {
        busy = true;
        executor.execute(() -> {
            boolean ok;
            boolean lost = false;
            try {
                ok = task.run();
            } catch (PinHasher.KeyLostException e) {
                ok = false;
                lost = true;
            } catch (Exception e) {
                ok = false;
            }
            boolean finalOk = ok, finalLost = lost;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                onResult(finalOk, finalLost);
            });
        });
    }

    private void onResult(boolean ok, boolean lost) {
        if (ok) {
            if (mode == MODE_UNLOCK) AppLock.unlock();
            else setResult(RESULT_OK);
            finishQuietly();
            return;
        }
        entered.setLength(0);
        renderDots();
        if (lost) {
            // Без ключа Keystore код не проверить никак. Снять его — «Забыли код?»
            // (Фаза 4); до неё — только очистка данных приложения в настройках телефона.
            keyLost = true;
            dimKeypad(true);
            showMessage(getString(R.string.lock_key_lost), true);
            return;
        }
        if (mode == MODE_SET) {
            fail(getString(R.string.lock_save_failed));
        } else if (lockoutRemaining() > 0) {
            shake();
            renderLockout();
        } else {
            int left = AppLockStore.FREE_ATTEMPTS - AppLockStore.getFailedAttempts(this);
            fail(left > 0 && left <= 3 ? getString(R.string.lock_wrong_attempts_left, left) : getString(R.string.lock_wrong));
        }
    }

    private void fail(String text) {
        showMessage(text, true);
        shake();
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private void render() {
        switch (mode) {
            case MODE_SET:
                title.setText(firstPin == null ? R.string.lock_title_create : R.string.lock_title_repeat);
                showMessage(firstPin == null ? getString(R.string.lock_hint_length) : "", false);
                break;
            case MODE_VERIFY:
                title.setText(R.string.lock_title_current);
                showMessage("", false);
                break;
            default:
                title.setText(R.string.lock_title_enter);
                showMessage("", false);
        }
        boolean bio = isBiometricOn();
        keyOk.setVisibility(isFirstSetStep() ? View.VISIBLE : View.GONE);
        keyBio.setVisibility(bio ? View.VISIBLE : View.GONE);
        keyLeft.setVisibility(isFirstSetStep() || bio ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.lockForgot).setVisibility(mode == MODE_UNLOCK ? View.VISIBLE : View.GONE);
        renderDots();
    }

    /** Точки по длине кода; на первом шаге установки — по набранному, но не меньше четырёх. */
    private void renderDots() {
        int count = isFirstSetStep()
                ? Math.max(AppLockStore.MIN_LENGTH, entered.length())
                : expectedLength();
        int size = dp(14), gap = dp(9);
        dots.removeAllViews();
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(gap, 0, gap, 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(i < entered.length()
                    ? R.drawable.bg_radio_dot_filled : R.drawable.bg_radio_dot);
            dots.addView(dot);
        }
    }

    /** Пауза после ошибок: клавиатура приглушена, обратный отсчёт раз в секунду. */
    private void renderLockout() {
        main.removeCallbacks(lockoutTick);
        if (mode == MODE_SET || keyLost) return;
        long remaining = lockoutRemaining();
        if (remaining <= 0) {
            if (dimmed) {
                dimKeypad(false);
                showMessage("", false);
            }
            return;
        }
        long sec = (remaining + 999) / 1000;
        dimKeypad(true);
        showMessage(getString(R.string.lock_lockout, sec / 60, sec % 60), true);
        main.postDelayed(lockoutTick, 1000);
    }

    /** Цифры приглушены; клавиша отпечатка — нет, она работает и во время паузы. */
    private void dimKeypad(boolean dim) {
        dimmed = dim;
        ViewGroup rows = (ViewGroup) keypad;
        for (int i = 0; i < rows.getChildCount(); i++) {
            ViewGroup row = (ViewGroup) rows.getChildAt(i);
            for (int j = 0; j < row.getChildCount(); j++) {
                View key = row.getChildAt(j);
                key.setAlpha(dim && key != keyLeft ? 0.4f : 1f);
            }
        }
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    private boolean isBiometricOn() {
        return mode == MODE_UNLOCK && AppLockStore.isBiometricOn(this);
    }

    private void showBiometric() {
        if (isFinishing() || biometricPrompt == null) return;
        Cipher cipher = BiometricKey.cipherOrNull();
        if (cipher == null) {
            // Отпечатки в телефоне изменились — ключ негоден. Это защита от чужого
            // пальца, добавленного в телефон: включать заново — только из настроек.
            AppLockStore.setBiometricOn(this, false);
            render();
            showMessage(BiometricErrors.keyInvalidated(this), true);
            return;
        }
        promptForReset = false;
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setNegativeButtonText(getString(R.string.lock_biometric_use_pin))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        biometricPrompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    private final BiometricPrompt.AuthenticationCallback biometricCallback =
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    if (promptForReset) {
                        promptForReset = false;
                        onDeviceCredentialConfirmed();
                        return;
                    }
                    BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                    if (!BiometricKey.confirm(crypto != null ? crypto.getCipher() : null)) {
                        showMessage(BiometricErrors.lockScreenMessage(LockActivity.this, -1), true);
                        return;
                    }
                    AppLockStore.resetFailures(LockActivity.this);
                    AppLock.unlock();
                    finishQuietly();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    if (promptForReset) {
                        // Передумал или ошибся кодом телефона — остаёмся на замке молча.
                        promptForReset = false;
                        return;
                    }
                    String text = BiometricErrors.lockScreenMessage(LockActivity.this, errorCode);
                    if (text != null) showMessage(text, true);
                }
            };

    // ── Забыли код ────────────────────────────────────────────────────────────

    /** Работает и во время паузы после ошибок, и когда ключ проверки кода пропал. */
    public void onForgotPin(View view) {
        if (busy) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.lock_forgot_title);
        if (ForgotPin.isDeviceSecure(this)) {
            dialog.setMessage(R.string.lock_forgot_secure)
                    .setPositiveButton(R.string.lock_forgot_device_pin, (d, w) -> confirmDeviceCredential())
                    .setNeutralButton(R.string.lock_forgot_wipe, (d, w) -> confirmWipe())
                    .setNegativeButton(R.string.action_cancel, null);
        } else {
            dialog.setMessage(R.string.lock_forgot_insecure)
                    .setPositiveButton(R.string.lock_forgot_wipe, (d, w) -> confirmWipe())
                    .setNegativeButton(R.string.action_cancel, null);
        }
        dialog.show();
    }

    /** Только код телефона, без отпечатка: отпечаток члена семьи не должен снимать код. */
    @SuppressWarnings("deprecation")
    private void confirmDeviceCredential() {
        String title = getString(R.string.lock_reset_title);
        String subtitle = getString(R.string.lock_reset_subtitle);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // BiometricPrompt умеет «только код телефона» лишь с API 30.
            promptForReset = true;
            biometricPrompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build());
            return;
        }
        KeyguardManager km = getSystemService(KeyguardManager.class);
        Intent intent = km != null ? km.createConfirmDeviceCredentialIntent(title, subtitle) : null;
        if (intent == null) {
            Toast.makeText(this, R.string.lock_reset_no_keyguard, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(intent, REQUEST_DEVICE_CREDENTIAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEVICE_CREDENTIAL && resultCode == RESULT_OK) onDeviceCredentialConfirmed();
    }

    private void onDeviceCredentialConfirmed() {
        ForgotPin.resetAndUnlock(this);
        Toast.makeText(this, R.string.lock_reset_done, Toast.LENGTH_LONG).show();
        finishQuietly();
    }

    /** Два подтверждения: стирание не отменить. */
    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wipe_title)
                .setMessage(ForgotPin.wipeWarning(this, ForgotPin.isLinkedToServer(this)))
                .setPositiveButton(R.string.wipe_confirm, (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(R.string.wipe_irreversible_title)
                        .setMessage(R.string.wipe_irreversible_message)
                        .setPositiveButton(R.string.wipe_confirm_all, (d2, w2) -> wipe())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Система стирает данные и ключи Keystore приложения и завершает процесс. */
    private void wipe() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null || !am.clearApplicationUserData()) {
            Toast.makeText(this, R.string.wipe_failed, Toast.LENGTH_LONG).show();
        }
    }

    private long lockoutRemaining() {
        return mode == MODE_SET ? 0 : AppLockStore.getLockoutRemainingMs(this, System.currentTimeMillis());
    }

    private void showMessage(String text, boolean error) {
        message.setText(text);
        message.setTextColor(ContextCompat.getColor(this,
                error ? R.color.colorexpanse : R.color.colorTextSecondary));
    }

    private void shake() {
        ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0, 24, -24, 14, -14, 6, -6, 0)
                .setDuration(400).start();
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
