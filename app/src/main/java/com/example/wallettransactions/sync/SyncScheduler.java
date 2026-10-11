package com.example.wallettransactions.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.wallettransactions.AppSettings;

import java.util.concurrent.TimeUnit;

/**
 * Планирование {@link SyncWorker} — экран «Настройки» → «Синхронизация» →
 * «Автоматическая синхронизация» (org/docs/TODO_mobile_sync.md, Фаза 1/3/4).
 *
 * androidx.work сам переживает выгрузку процесса и перезагрузку устройства
 * (расписание живёт в его собственной базе), поэтому расписание нужно
 * трогать только когда пользователь меняет настройку — не на каждом старте
 * приложения. Исключение — {@link #ensureScheduled}, безопасный вызов на
 * старте процесса (см. {@code WalletApplication}) на случай, если
 * расписание почему-то потерялось (переустановка поверх и т.п.).
 */
public final class SyncScheduler {

    private static final String WORK_NAME = "auto_connect";

    private SyncScheduler() {}

    /** Пересобирает расписание с текущими настройками — звать сразу после
     *  изменения переключателя или интервала, чтобы новое значение
     *  подхватилось немедленно, а не с большим текущим периодом. */
    public static void reschedule(Context ctx) {
        if (!AppSettings.isAutoConnectEnabled(ctx)) {
            cancel(ctx);
            return;
        }
        enqueue(ctx, ExistingPeriodicWorkPolicy.REPLACE);
    }

    /** Безопасный вызов на старте процесса — не трогает уже идущее
     *  расписание с тем же именем, только заводит его, если оно почему-то
     *  отсутствует, а настройка включена. */
    public static void ensureScheduled(Context ctx) {
        if (!AppSettings.isAutoConnectEnabled(ctx)) return;
        enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME);
    }

    private static void enqueue(Context ctx, ExistingPeriodicWorkPolicy policy) {
        int minutes = AppSettings.getAutoConnectIntervalMinutes(ctx);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(SyncWorker.class, minutes, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, policy, request);
    }
}
