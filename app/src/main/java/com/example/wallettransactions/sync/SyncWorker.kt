package com.example.wallettransactions.sync

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.WalletApplication
import java.time.LocalDateTime

/**
 * Периодическая синхронизация (экран «Настройки» → «Синхронизация» →
 * «Автоматическая синхронизация», org/docs/TODO_mobile_sync.md, Фаза 3, п. 4) —
 * планируется [SyncScheduler]. Сама последовательность (refresh → push
 * → pull) — в [SyncRunner.runOnce], общем и с кнопкой
 * «Синхронизировать сейчас» (экран «Настройки», по требованию пользователя) —
 * этот класс только переводит её [SyncRunner.Outcome] на язык
 * WorkManager (успех/повтор/провал). Сверх синка — заводит наступившие
 * периодические операции и уведомляет о новых ([NewOperationsNotifier]).
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext

        // Периодические операции — до синка: заведённое сразу уедет на сервер этим же
        // проходом. Без этого они появлялись бы только при открытии приложения
        // (BaseTabActivity), и уведомлять о них было бы некогда. Повтор не задвоит:
        // генератор сдвигает last_date правила.
        val recurringIds = ArrayList<Long>()
        if (!WalletApplication.hadCrash(ctx)) {
            OperationTemplatesDb.generateDue(
                Singleton.instance.getWritableDatabase(ctx),
                LocalDateTime.now(), recurringIds
            )
        }

        val outcome = SyncRunner.runOnce(ctx)
        val synced = outcome.status == SyncRunner.Status.OK || outcome.status == SyncRunner.Status.PARTIAL

        // Изменения от самого синка отмечает SyncRunner; здесь — только заведённые
        // периодические (они есть и тогда, когда сервер недоступен).
        if (recurringIds.isNotEmpty()) {
            PeriodFilterState.get(ctx).markDataChanged()
        }
        // О периодических — и когда сервер недоступен: они заведены локально.
        NewOperationsNotifier.notifyIfAny(ctx, if (synced) outcome.log else null, recurringIds)

        return when (outcome.status) {
            SyncRunner.Status.OK -> Result.success()
            SyncRunner.Status.NO_SESSION -> Result.success()
            // Повтор ничего не даст, пока пользователь не ответит на вопрос на главном
            // экране; ответ сам запускает проход (new-user-default-set, design D8).
            SyncRunner.Status.AWAITING_DECISION -> Result.success()
            // Уведомление уже показано AuthSession — тихо повторить вход
            // через Google отсюда нельзя (см. её javadoc).
            SyncRunner.Status.NEEDS_RELINK -> Result.failure()
            // Сервер выключен/недоступен по сети — обычное дело для
            // LAN-only стенда (план, п. 1.4).
            SyncRunner.Status.NETWORK_ERROR -> {
                Log.i(TAG, "sync: " + outcome.errorMessage)
                Result.retry()
            }
            else -> {
                Log.w(TAG, "sync: " + outcome.errorMessage)
                Result.retry()
            }
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
