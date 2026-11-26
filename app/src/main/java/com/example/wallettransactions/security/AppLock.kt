package com.example.wallettransactions.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.wallettransactions.Activities.LockActivity

/**
 * Замок на интерфейс (org/docs/TODO_app_lock.md, пп. 1.5–1.6). Запирается на
 * холодном старте и после ухода в фон дольше [AppLockStore.getTimeoutMs];
 * пока заперто, любая поднявшаяся активность накрывается [LockActivity].
 * Так не нужно трогать каждую из активностей приложения.
 *
 * Уход в фон — по [ProcessLifecycleOwner]: он не дёргается на повороте
 * экрана и переходах между своими активностями. Фоновые задачи (синк,
 * периодические операции) замка не касаются — у них нет UI.
 */
object AppLock {

    /** Сколько живёт [allowNextLeave]: вернулся с выбора файла позже — запираем как обычно. */
    internal const val LEAVE_GRACE_MS = 10 * 60_000L

    private var locked = false
    private var stoppedAt = -1L
    /** elapsedRealtime вызова [allowNextLeave], -1 — не разрешали. */
    private var leaveAllowedAt = -1L

    /** Из `WalletApplication.onCreate`, с главного потока. */
    @JvmStatic
    fun install(app: Application) {
        migrateStubSettings(app)
        locked = AppLockStore.isPinSet(app)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                onAppStopped(SystemClock.elapsedRealtime())
            }

            override fun onStart(owner: LifecycleOwner) {
                onAppStarted(app, SystemClock.elapsedRealtime())
            }
        })

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // На resume, а не только на create: настройку могли сменить, пока экран лежал в стеке.
                applyRecentsPrivacy(activity)
                if (!locked || LockActivity.isUnlockScreen(activity)) return
                activity.startActivity(
                    Intent(activity, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
                activity.overridePendingTransition(0, 0)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) { applyRecentsPrivacy(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Превью в списке недавних (п. 4.2). API 33+ — просто не снимать скриншот для
     * недавних; ниже такого нет, только `FLAG_SECURE`, который заодно запрещает
     * скриншоты и запись экрана в приложении — поэтому это отдельная настройка.
     */
    @JvmStatic
    fun applyRecentsPrivacy(activity: Activity) {
        val hide = AppLockStore.isHideInRecents(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @JvmStatic
    fun isLocked(): Boolean = locked

    /** Верный код на экране замка или код выключен в настройках. */
    @JvmStatic
    fun unlock() {
        locked = false
    }

    /**
     * Перед уходом в чужой экран, из которого вернутся с результатом (выбор файла,
     * вход Google, браузер): один следующий возврат не запирает, иначе при «Сразу»
     * пользователь упирался бы в замок с только что выбранным файлом.
     */
    @JvmStatic
    fun allowNextLeave() {
        leaveAllowedAt = SystemClock.elapsedRealtime()
    }

    @VisibleForTesting
    internal fun onAppStopped(now: Long) {
        stoppedAt = now
    }

    @VisibleForTesting
    internal fun onAppStarted(ctx: Context, now: Long) {
        val graced = leaveAllowedAt >= 0 && now - leaveAllowedAt < LEAVE_GRACE_MS
        leaveAllowedAt = -1
        if (stoppedAt < 0 || graced || !AppLockStore.isPinSet(ctx)) return
        if (now - stoppedAt >= AppLockStore.getTimeoutMs(ctx)) locked = true
    }

    @VisibleForTesting
    internal fun resetForTest(lockedNow: Boolean) {
        locked = lockedNow
        stoppedAt = -1
        leaveAllowedAt = -1
    }

    /**
     * Переключатели «Код доступа»/«Вход по отпечатку» раньше были заглушками и писали
     * `accessCodeOn`/`biometricOn` в prefs `settings`, ничего не
     * включая. Удаляем: код без хэша заперло бы пользователя насовсем.
     */
    private fun migrateStubSettings(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .remove("accessCodeOn").remove("biometricOn").apply()
    }
}
