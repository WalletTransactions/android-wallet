package com.example.wallettransactions;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public class WalletApplication extends Application {

    private static final String TAG = "WalletCrash";
    private static final String PREF_CRASH = "wallet_crash_prefs";
    private static final String KEY_HAD_CRASH = "had_crash";
    private static final String KEY_CRASH_LOG  = "last_crash_log";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
        // Показ системных категорий на языке интерфейса (openspec android-i18n).
        com.example.wallettransactions.DataItems.Accounts.AccountItem.initDisplayNames(this);
        Texts.init(this);
        // До первой активности: иначе стартовый экран успевает подняться в светлой теме
        // и тут же пересоздаться в тёмной — заметным миганием.
        AppSettings.applyThemeMode(this);
        installThemeResync();
        // Код доступа: запирает на холодном старте и после ухода в фон.
        com.example.wallettransactions.security.AppLock.install(this);
        // Обычно не нужен — androidx.work сам переживает выгрузку процесса
        // и перезагрузку устройства (расписание в его собственной базе),
        // это просто страховка на случай, если оно потерялось. Не трогает
        // уже идущее расписание (см. SyncWorker/SyncScheduler).
        com.example.wallettransactions.sync.SyncScheduler.ensureScheduled(this);
        // Периодические операции в фоне — цепочка задач, та же страховка.
        com.example.wallettransactions.sync.RecurringWorker.ensureScheduled(this);
        // «Напоминание вносить операции» — тоже цепочка задач, та же страховка.
        com.example.wallettransactions.reminder.ReminderWorker.ensureScheduled(this);
    }

    // ── Тема ───────────────────────────────────────────────────────────────────

    /**
     * Донастраивает смену темы для экранов, уже лежащих в стеке.
     *
     * setDefaultNightMode меняет только глобальный флаг, а разбирается с ним делегат
     * AppCompat — и в support-библиотеке 28 он делает это лишь в onCreate. Поэтому
     * открытый до переключения экран возвращался бы из стека в старой теме. Здесь мы
     * ловим его и пересоздаём сами.
     *
     * Сверяем не конфигурацию ресурсов, а режим, при котором экран собрали: делегат
     * правит конфигурацию общих Resources процесса, так что у отставшего экрана она уже
     * показывает новую тему, — а вёрстка в нём осталась со старыми цветами (проверено
     * на устройстве: по этой сверке экран считался «в теме» и не перекрашивался).
     *
     * Пересоздаём на onResume, а не на onStart: recreate() до того, как активность стала
     * resumed, платформа молча теряет — тоже проверено.
     *
     * Режим «как в системе» сюда не попадает: там смену темы делает сама платформа,
     * и строка в prefs остаётся прежней.
     */
    private void installThemeResync() {
        final Map<Activity, String> builtWith = new WeakHashMap<>();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                builtWith.put(activity, AppSettings.getThemeMode(activity));
            }

            @Override
            public void onActivityResumed(Activity activity) {
                String was = builtWith.get(activity);
                String now = AppSettings.getThemeMode(activity);
                if (was == null || was.equals(now)) return;
                // Отмечаем сразу: иначе экран, который платформа почему-то не пересоздала,
                // просил бы пересоздания на каждом onResume.
                builtWith.put(activity, now);
                activity.recreate();
            }

            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    // ── Crash handler ──────────────────────────────────────────────────────────

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String report = buildCrashReport(thread, throwable);
                Log.e(TAG, report);
                saveCrashToPrefs(report);
                writeCrashToFile(report);
            } catch (Exception ignored) {}

            if (defaultHandler != null)
                defaultHandler.uncaughtException(thread, throwable);
        });
    }

    private String buildCrashReport(Thread thread, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        pw.println("=== Crash Report ===");
        pw.println("Time   : " + ts);
        pw.println("Thread : " + thread.getName());
        pw.println("App    : " + getPackageName());
        pw.println();
        t.printStackTrace(pw);

        // include cause chain
        Throwable cause = t.getCause();
        while (cause != null) {
            pw.println("\nCaused by:");
            cause.printStackTrace(pw);
            cause = cause.getCause();
        }
        return sw.toString();
    }

    private void saveCrashToPrefs(String report) {
        // Trim to 8 KB so prefs don't bloat
        String truncated = report.length() > 8192 ? report.substring(0, 8192) + "\n...(truncated)" : report;
        prefs().edit()
                .putBoolean(KEY_HAD_CRASH, true)
                .putString(KEY_CRASH_LOG, truncated)
                .apply();
    }

    private void writeCrashToFile(String report) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "crash_" + ts + ".txt");

            FileWriter fw = new FileWriter(file, false);
            fw.write(report);
            fw.close();

            Log.i(TAG, "Crash report written to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Could not write crash file: " + e.getMessage());
        }
    }

    // ── Public API (called from MainActivity) ──────────────────────────────────

    public static boolean hadCrash(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HAD_CRASH, false);
    }

    public static String getLastCrashLog(Context ctx) {
        return prefs(ctx).getString(KEY_CRASH_LOG, "");
    }

    public static void clearCrashFlag(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_HAD_CRASH)
                .remove(KEY_CRASH_LOG)
                .apply();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private SharedPreferences prefs() { return prefs(this); }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_CRASH, MODE_PRIVATE);
    }
}
