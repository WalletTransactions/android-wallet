package com.example.wallettransactions.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.wallettransactions.Data.RecurringOperationsDb;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.WalletApplication;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Периодическая синхронизация (экран «Настройки» → «Синхронизация» →
 * «Автоматическая синхронизация», org/docs/TODO_mobile_sync.md, Фаза 3, п. 4) —
 * планируется {@link SyncScheduler}. Сама последовательность (refresh → push
 * → pull) — в {@link SyncRunner#runOnce}, общем и с кнопкой
 * «Синхронизировать сейчас» (экран «Настройки», по требованию пользователя) —
 * этот класс только переводит её {@link SyncRunner.Outcome} на язык
 * WorkManager (успех/повтор/провал). Сверх синка — заводит наступившие
 * периодические операции и уведомляет о новых ({@link NewOperationsNotifier}).
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Периодические операции — до синка: заведённое сразу уедет на сервер этим же
        // проходом. Без этого они появлялись бы только при открытии приложения
        // (BaseTabActivity), и уведомлять о них было бы некогда. Повтор не задвоит:
        // генератор сдвигает last_date правила.
        List<Long> recurringIds = new ArrayList<>();
        if (!WalletApplication.hadCrash(ctx)) {
            RecurringOperationsDb.generateDue(Singleton.instance.getWritableDatabase(ctx),
                    LocalDateTime.now(), recurringIds);
        }

        SyncRunner.Outcome outcome = SyncRunner.runOnce(ctx);
        boolean synced = outcome.status == SyncRunner.Status.OK || outcome.status == SyncRunner.Status.PARTIAL;

        // Изменения от самого синка отмечает SyncRunner; здесь — только заведённые
        // периодические (они есть и тогда, когда сервер недоступен).
        if (!recurringIds.isEmpty()) {
            PeriodFilterState.get(ctx).markDataChanged();
        }
        // О периодических — и когда сервер недоступен: они заведены локально.
        NewOperationsNotifier.notifyIfAny(ctx, synced ? outcome.log : null, recurringIds);

        switch (outcome.status) {
            case OK:
                return Result.success();
            case NO_SESSION:
                return Result.success();
            case AWAITING_DECISION:
                // Повтор ничего не даст, пока пользователь не ответит на вопрос на главном
                // экране; ответ сам запускает проход (new-user-default-set, design D8).
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
            case PARTIAL:
            case ERROR:
            default:
                Log.w(TAG, "sync: " + outcome.errorMessage);
                return Result.retry();
        }
    }
}
