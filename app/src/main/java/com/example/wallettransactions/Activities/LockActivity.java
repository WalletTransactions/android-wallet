package com.example.wallettransactions.Activities;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.R;
import com.example.wallettransactions.security.AppLock;
import com.example.wallettransactions.security.AppLockStore;
import com.example.wallettransactions.security.PinHasher;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 */
public class LockActivity extends AppCompatActivity {

    public static final int MODE_UNLOCK = 0;
    public static final int MODE_VERIFY = 1;
    public static final int MODE_SET = 2;

    private static final String EXTRA_MODE = "LockActivity.mode";
    private static final String STATE_FIRST_PIN = "firstPin";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private int mode;
    private final StringBuilder entered = new StringBuilder();
    /** MODE_SET: код с первого шага, ждёт повтора; null — первый шаг. */
    private String firstPin;
    private boolean busy;
    private boolean keyLost;

    private TextView title;
    private TextView message;
    private LinearLayout dots;
    private View keypad;
    private View keyOk;

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
        keyOk = findViewById(R.id.lockKeyOk);

        if (savedInstanceState != null) firstPin = savedInstanceState.getString(STATE_FIRST_PIN);
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Поворот на шаге «Повторите код» не должен терять первый ввод. Набранное
        // на текущем шаге не сохраняем — проще набрать заново, чем держать код в Bundle.
        if (firstPin != null) out.putString(STATE_FIRST_PIN, firstPin);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Сняли замок, пока этот экран лежал в стеке (например, код выключен в
        // настройках из другого окна), — показывать нечего.
        if (mode == MODE_UNLOCK && !AppLock.isLocked()) { finishQuietly(); return; }
        renderLockout();
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
        if (busy || keyLost || lockoutRemaining() > 0) return;
        String tag = (String) view.getTag();

        if ("del".equals(tag)) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
        } else if ("ok".equals(tag)) {
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
                fail("Коды не совпали — придумайте код заново");
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
            keypad.setAlpha(0.4f);
            showMessage("Ключ проверки кода на телефоне пропал, код не проверить. "
                    + "Очистите данные приложения: Настройки телефона → Приложения.", true);
            return;
        }
        if (mode == MODE_SET) {
            fail("Не удалось сохранить код");
        } else if (lockoutRemaining() > 0) {
            shake();
            renderLockout();
        } else {
            int left = AppLockStore.FREE_ATTEMPTS - AppLockStore.getFailedAttempts(this);
            fail(left > 0 && left <= 3 ? "Неверный код. Попыток до паузы: " + left : "Неверный код");
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
                title.setText(firstPin == null ? "Придумайте код" : "Повторите код");
                showMessage(firstPin == null ? "От 4 до 6 цифр, затем «ОК»" : "", false);
                break;
            case MODE_VERIFY:
                title.setText("Введите текущий код");
                showMessage("", false);
                break;
            default:
                title.setText("Введите код");
                showMessage("", false);
        }
        keyOk.setVisibility(isFirstSetStep() ? View.VISIBLE : View.INVISIBLE);
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
            if (keypad.getAlpha() < 1f) {
                keypad.setAlpha(1f);
                showMessage("", false);
            }
            return;
        }
        long sec = (remaining + 999) / 1000;
        keypad.setAlpha(0.4f);
        showMessage(String.format(Locale.US, "Слишком много попыток. Повторите через %d:%02d",
                sec / 60, sec % 60), true);
        main.postDelayed(lockoutTick, 1000);
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
