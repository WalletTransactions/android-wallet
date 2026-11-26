package com.example.wallettransactions

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.reminder.ReminderWorker
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.sync.RecurringWorker
import com.example.wallettransactions.sync.SyncScheduler
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

class WalletApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // Показ системных категорий на языке интерфейса (openspec android-i18n).
        AccountItem.initDisplayNames(this)
        Texts.init(this)
        // До первой активности: иначе стартовый экран успевает подняться в светлой теме
        // и тут же пересоздаться в тёмной — заметным миганием.
        AppSettings.applyThemeMode(this)
        installThemeResync()
        // Код доступа: запирает на холодном старте и после ухода в фон.
        AppLock.install(this)
        // Обычно не нужен — androidx.work сам переживает выгрузку процесса
        // и перезагрузку устройства (расписание в его собственной базе),
        // это просто страховка на случай, если оно потерялось. Не трогает
        // уже идущее расписание (см. SyncWorker/SyncScheduler).
        SyncScheduler.ensureScheduled(this)
        // Периодические операции в фоне — цепочка задач, та же страховка.
        RecurringWorker.ensureScheduled(this)
        // «Напоминание вносить операции» — тоже цепочка задач, та же страховка.
        ReminderWorker.ensureScheduled(this)
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
    private fun installThemeResync() {
        val builtWith: MutableMap<Activity, String> = WeakHashMap()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                builtWith[activity] = AppSettings.getThemeMode(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                val was = builtWith[activity]
                val now = AppSettings.getThemeMode(activity)
                if (was == null || was == now) return
                // Отмечаем сразу: иначе экран, который платформа почему-то не пересоздала,
                // просил бы пересоздания на каждом onResume.
                builtWith[activity] = now
                activity.recreate()
            }

            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ── Crash handler ──────────────────────────────────────────────────────────

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildCrashReport(thread, throwable)
                Log.e(TAG, report)
                saveCrashToPrefs(report)
                writeCrashToFile(report)
            } catch (ignored: Exception) {
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        pw.println("=== Crash Report ===")
        pw.println("Time   : $ts")
        pw.println("Thread : " + thread.name)
        pw.println("App    : $packageName")
        pw.println()
        t.printStackTrace(pw)

        // include cause chain
        var cause = t.cause
        while (cause != null) {
            pw.println("\nCaused by:")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        return sw.toString()
    }

    private fun saveCrashToPrefs(report: String) {
        // Trim to 8 KB so prefs don't bloat
        val truncated = if (report.length > 8192) report.substring(0, 8192) + "\n...(truncated)" else report
        prefs().edit()
            .putBoolean(KEY_HAD_CRASH, true)
            .putString(KEY_CRASH_LOG, truncated)
            .apply()
    }

    private fun writeCrashToFile(report: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$ts.txt")

            val fw = FileWriter(file, false)
            fw.write(report)
            fw.close()

            Log.i(TAG, "Crash report written to: " + file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write crash file: " + e.message)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun prefs(): SharedPreferences = prefs(this)

    companion object {
        private const val TAG = "WalletCrash"
        private const val PREF_CRASH = "wallet_crash_prefs"
        private const val KEY_HAD_CRASH = "had_crash"
        private const val KEY_CRASH_LOG = "last_crash_log"

        // ── Public API (called from MainActivity) ──────────────────────────────────

        @JvmStatic
        fun hadCrash(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAD_CRASH, false)

        @JvmStatic
        fun getLastCrashLog(ctx: Context): String? = prefs(ctx).getString(KEY_CRASH_LOG, "")

        @JvmStatic
        fun clearCrashFlag(ctx: Context) {
            prefs(ctx).edit()
                .remove(KEY_HAD_CRASH)
                .remove(KEY_CRASH_LOG)
                .apply()
        }

        private fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREF_CRASH, MODE_PRIVATE)
    }
}
