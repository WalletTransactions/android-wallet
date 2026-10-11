package com.example.wallettransactions.security;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.example.wallettransactions.Activities.LockActivity;

/**
 * Замок на интерфейс (org/docs/TODO_app_lock.md, пп. 1.5–1.6). Запирается на
 * холодном старте и после ухода в фон дольше {@link AppLockStore#getTimeoutMs};
 * пока заперто, любая поднявшаяся активность накрывается {@link LockActivity}.
 * Так не нужно трогать каждую из активностей приложения.
 * <p>
 * Уход в фон — по {@link ProcessLifecycleOwner}: он не дёргается на повороте
 * экрана и переходах между своими активностями. Фоновые задачи (синк,
 * периодические операции) замка не касаются — у них нет UI.
 */
public final class AppLock {

    /** Сколько живёт {@link #allowNextLeave}: вернулся с выбора файла позже — запираем как обычно. */
    static final long LEAVE_GRACE_MS = 10 * 60_000;

    private static boolean locked;
    private static long stoppedAt = -1;
    /** elapsedRealtime вызова {@link #allowNextLeave}, -1 — не разрешали. */
    private static long leaveAllowedAt = -1;

    private AppLock() {}

    /** Из {@code WalletApplication.onCreate}, с главного потока. */
    public static void install(Application app) {
        migrateStubSettings(app);
        locked = AppLockStore.isPinSet(app);

        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                onAppStopped(SystemClock.elapsedRealtime());
            }

            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                onAppStarted(app, SystemClock.elapsedRealtime());
            }
        });

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                // На resume, а не только на create: настройку могли сменить, пока экран лежал в стеке.
                applyRecentsPrivacy(activity);
                if (!locked || LockActivity.isUnlockScreen(activity)) return;
                activity.startActivity(new Intent(activity, LockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
                activity.overridePendingTransition(0, 0);
            }

            @Override public void onActivityCreated(@NonNull Activity a, Bundle b) { applyRecentsPrivacy(a); }
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
    }

    /**
     * Превью в списке недавних (п. 4.2). API 33+ — просто не снимать скриншот для
     * недавних; ниже такого нет, только {@code FLAG_SECURE}, который заодно запрещает
     * скриншоты и запись экрана в приложении — поэтому это отдельная настройка.
     */
    public static void applyRecentsPrivacy(Activity activity) {
        boolean hide = AppLockStore.isHideInRecents(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!hide);
        } else if (hide) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    public static boolean isLocked() {
        return locked;
    }

    /** Верный код на экране замка или код выключен в настройках. */
    public static void unlock() {
        locked = false;
    }

    /**
     * Перед уходом в чужой экран, из которого вернутся с результатом (выбор файла,
     * вход Google, браузер): один следующий возврат не запирает, иначе при «Сразу»
     * пользователь упирался бы в замок с только что выбранным файлом.
     */
    public static void allowNextLeave() {
        leaveAllowedAt = SystemClock.elapsedRealtime();
    }

    @VisibleForTesting
    static void onAppStopped(long now) {
        stoppedAt = now;
    }

    @VisibleForTesting
    static void onAppStarted(Context ctx, long now) {
        boolean graced = leaveAllowedAt >= 0 && now - leaveAllowedAt < LEAVE_GRACE_MS;
        leaveAllowedAt = -1;
        if (stoppedAt < 0 || graced || !AppLockStore.isPinSet(ctx)) return;
        if (now - stoppedAt >= AppLockStore.getTimeoutMs(ctx)) locked = true;
    }

    @VisibleForTesting
    static void resetForTest(boolean lockedNow) {
        locked = lockedNow;
        stoppedAt = -1;
        leaveAllowedAt = -1;
    }

    /**
     * Переключатели «Код доступа»/«Вход по отпечатку» раньше были заглушками и писали
     * {@code accessCodeOn}/{@code biometricOn} в prefs {@code settings}, ничего не
     * включая. Удаляем: код без хэша заперло бы пользователя насовсем.
     */
    private static void migrateStubSettings(Context ctx) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .remove("accessCodeOn").remove("biometricOn").apply();
    }
}
