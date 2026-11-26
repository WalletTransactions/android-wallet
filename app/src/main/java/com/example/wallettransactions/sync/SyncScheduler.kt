package com.example.wallettransactions.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.example.wallettransactions.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Планирование [SyncWorker] — экран «Настройки» → «Синхронизация» →
 * «Автоматическая синхронизация» (org/docs/TODO_mobile_sync.md, Фаза 1/3/4).
 *
 * androidx.work сам переживает выгрузку процесса и перезагрузку устройства
 * (расписание живёт в его собственной базе), поэтому расписание нужно
 * трогать только когда пользователь меняет настройку — не на каждом старте
 * приложения. Исключение — [ensureScheduled], безопасный вызов на
 * старте процесса (см. `WalletApplication`) на случай, если
 * расписание почему-то потерялось (переустановка поверх и т.п.).
 */
object SyncScheduler {

    private const val WORK_NAME = "auto_connect"

    /** Пересобирает расписание с текущими настройками — звать сразу после
     *  изменения переключателя или интервала, чтобы новое значение
     *  подхватилось немедленно, а не с большим текущим периодом. */
    @JvmStatic
    fun reschedule(ctx: Context) {
        if (!AppSettings.isAutoConnectEnabled(ctx)) {
            cancel(ctx)
            return
        }
        enqueue(ctx, ExistingPeriodicWorkPolicy.REPLACE)
    }

    /** Безопасный вызов на старте процесса — не трогает уже идущее
     *  расписание с тем же именем, только заводит его, если оно почему-то
     *  отсутствует, а настройка включена. */
    @JvmStatic
    fun ensureScheduled(ctx: Context) {
        if (!AppSettings.isAutoConnectEnabled(ctx)) return
        enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP)
    }

    @JvmStatic
    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val minutes = AppSettings.getAutoConnectIntervalMinutes(ctx)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request =
            PeriodicWorkRequest.Builder(SyncWorker::class.java, minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }
}
