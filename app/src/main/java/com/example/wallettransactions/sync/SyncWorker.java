package com.example.wallettransactions.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Периодическая синхронизация (экран «Настройки» → «Синхронизация» →
 * «Автоматическое подключение», org/docs/TODO_mobile_sync.md, Фаза 3, п. 4) —
 * планируется {@link SyncScheduler}. Сама последовательность (refresh → push
 * → pull) — в {@link SyncRunner#runOnce}, общем и с кнопкой
 * «Синхронизировать сейчас» (экран «Настройки», по требованию пользователя) —
 * этот класс только переводит её {@link SyncRunner.Outcome} на язык
 * WorkManager (успех/повтор/провал), сам ничего не делает напрямую.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SyncRunner.Outcome outcome = SyncRunner.runOnce(getApplicationContext());
        switch (outcome.status) {
            case NO_SESSION:
            case OK:
                return Result.success();
            case NEEDS_RELINK:
                // Уведомление уже показано AuthSession — тихо повторить вход
                // через Google отсюда нельзя (см. её javadoc).
                return Result.failure();
            case NETWORK_ERROR:
                // Сервер выключен/недоступен по сети — обычное дело для
                // LAN-only стенда (план, п. 1.4).
                Log.i(TAG, "sync: " + outcome.errorMessage);
                return Result.retry();
            case ERROR:
            default:
                Log.w(TAG, "sync: " + outcome.errorMessage);
                return Result.retry();
        }
    }
}
