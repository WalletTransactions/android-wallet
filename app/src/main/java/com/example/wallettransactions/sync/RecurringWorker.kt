package com.example.wallettransactions.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.WalletApplication
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Заводит наступившие периодические операции в фоне и уведомляет о них
 * ([NewOperationsNotifier]) — к моменту ближайшего повтора, независимо от
 * «Автоматического подключения» и сети. [SyncWorker] делает то же на своём
 * проходе, но он есть только при включённом автоподключении и раз в интервал, а
 * без него операции молча появлялись лишь при открытии приложения.
 *
 * Цепочка одноразовых задач: каждая, отработав, ставит следующую на ближайший
 * повтор по всем шаблонам с расписанием. Расчёт — только здесь, в фоне: остальные
 * лишь [kick] — на старте процесса и после правки шаблонов.
 */
class RecurringWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        // После аварийного завершения базу не трогаем, пока пользователь не выбрал
        // действие в диалоге восстановления; следующий старт процесса заведёт цепочку снова.
        if (WalletApplication.hadCrash(ctx)) return Result.success()

        val db = Singleton.instance.getWritableDatabase(ctx)
        val now = LocalDateTime.now()
        val created = ArrayList<Long>()
        OperationTemplatesDb.generateDue(db, now, created)
        if (created.isNotEmpty()) {
            PeriodFilterState.get(ctx).markDataChanged()
            NewOperationsNotifier.notifyIfAny(ctx, null, created)
        }

        val next = nextDue(OperationTemplatesDb.loadAll(db), now)
        if (next == null) {
            Log.i(TAG, "повторов впереди нет")
            return Result.success()
        }
        val delay = maxOf(0L, Duration.between(LocalDateTime.now(), next).toMillis())
        Log.i(TAG, "следующий повтор $next")
        // Эта задача ещё RUNNING: REPLACE отменил бы её саму, KEEP — не поставил бы новую.
        enqueue(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE, delay)
        return Result.success()
    }

    companion object {
        private const val TAG = "RecurringWorker"
        private const val WORK_NAME = "recurring_operations"

        /** На старте процесса: не трогает уже стоящую задачу, заводит, если её нет. */
        @JvmStatic
        fun ensureScheduled(ctx: Context) {
            // На устройстве WorkManager поднимает androidx.startup раньше Application.onCreate;
            // под Robolectric его нет — без проверки падал бы старт приложения в каждом тесте.
            if (!WorkManager.isInitialized()) return
            enqueue(ctx, ExistingWorkPolicy.KEEP, 0)
        }

        /** После правки шаблонов: стоящая задача ждёт по старому расписанию — пересчитать сейчас. */
        @JvmStatic
        fun kick(ctx: Context) {
            if (!WorkManager.isInitialized()) return
            enqueue(ctx, ExistingWorkPolicy.REPLACE, 0)
        }

        private fun enqueue(ctx: Context, policy: ExistingWorkPolicy, delayMillis: Long) {
            val request = OneTimeWorkRequest.Builder(RecurringWorker::class.java)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, request)
        }

        /** Ближайший не созданный повтор по всем шаблонам с расписанием; null — повторов больше не будет. */
        @JvmStatic
        internal fun nextDue(templates: List<OperationTemplate>, now: LocalDateTime): LocalDateTime? {
            var best: LocalDateTime? = null
            for (t in templates) {
                val r = t.schedule ?: continue
                if (!r.isActive) continue
                val next = r.nextOccurrence(now)
                if (next != null && (best == null || next.isBefore(best))) best = next
            }
            return best
        }
    }
}
