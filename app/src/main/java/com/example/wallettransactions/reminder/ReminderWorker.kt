package com.example.wallettransactions.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.wallettransactions.Activities.MainActivity
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.DataItems.Operations.TemplateLayout
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * «Напоминание вносить операции» («Настройки» → «Уведомления»): раз в день в выбранное
 * время. Как `RecurringWorker` — цепочка одноразовых задач WorkManager: каждая
 * ставит следующую на завтра. Без точных будильников: им на Android 12+ нужно отдельное
 * разрешение, а напоминанию сдвиг на несколько минут не страшен. WorkManager сам
 * переживает перезагрузку телефона.
 *
 * Не беспокоит, если:
 *  - сегодня уже внесена операция ([hasEnteredToday]) — периодические не в счёт;
 *  - приложение открыто — и так видно;
 *  - задача опоздала больше чем на [MAX_LATE] (телефон был выключен, Doze) —
 *    напоминание «внести за сегодня» среди ночи хуже, чем никакого.
 *
 * Логи: `adb logcat -s ReminderWorker`.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    // ── Срабатывание ──────────────────────────────────────────────────────────

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!AppSettings.isRemindOn(ctx)) return Result.success()

        val dueAt = inputData.getLong(KEY_DUE_AT, System.currentTimeMillis())
        val skip = skipReason(ctx, System.currentTimeMillis() - dueAt)
        if (skip != null) Log.i(TAG, "skip: $skip")
        else show(ctx)

        // Эта задача ещё RUNNING: REPLACE отменил бы её саму, KEEP — не поставил бы новую.
        enqueue(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val TAG = "ReminderWorker"
        private const val WORK_NAME = "daily_reminder"
        private const val CHANNEL_ID = "reminders"
        // 1 — AuthSession, 2 — NewOperationsNotifier.
        private const val NOTIFICATION_ID = 3
        private const val KEY_DUE_AT = "dueAt"
        @JvmField
        internal val MAX_LATE: Duration = Duration.ofHours(2)

        // ── Расписание ────────────────────────────────────────────────────────

        /** После смены переключателя или времени: пересобрать цепочку с нуля. */
        @JvmStatic
        fun reschedule(ctx: Context) {
            if (!AppSettings.isRemindOn(ctx)) {
                WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
                return
            }
            enqueue(ctx, ExistingWorkPolicy.REPLACE)
        }

        /** На старте процесса: заводит цепочку, если её нет, а напоминание включено. */
        @JvmStatic
        fun ensureScheduled(ctx: Context) {
            // Под Robolectric WorkManager не поднят — см. RecurringWorker.ensureScheduled.
            if (!WorkManager.isInitialized() || !AppSettings.isRemindOn(ctx)) return
            enqueue(ctx, ExistingWorkPolicy.KEEP)
        }

        private fun enqueue(ctx: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now()
            val due = nextDue(now, AppSettings.getRemindHour(ctx), AppSettings.getRemindMinute(ctx))
            val request = OneTimeWorkRequest.Builder(ReminderWorker::class.java)
                .setInitialDelay(Duration.between(now, due).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putLong(KEY_DUE_AT, due.toInstant().toEpochMilli()).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, request)
            Log.i(TAG, "следующее напоминание " + due.toLocalDateTime())
        }

        /**
         * Ближайшее `hour:minute` строго после `now`. Через ZonedDateTime, а не
         * «+24 часа»: в день перевода часов сутки длиннее или короче.
         */
        @JvmStatic
        internal fun nextDue(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
            val today = now.with(LocalTime.of(hour, minute))
            return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1)
                .atTime(hour, minute).atZone(now.zone)
        }

        /** null — показывать. */
        private fun skipReason(ctx: Context, lateMillis: Long): String? {
            if (lateMillis > MAX_LATE.toMillis()) return "опоздали на " + lateMillis / 60_000 + " мин" // not UI: в лог
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return "приложение на экране" // not UI: в лог
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return "нет разрешения POST_NOTIFICATIONS" // not UI: в лог
            }
            if (hasEnteredToday(Singleton.instance.getReadableDatabase(ctx), LocalDate.now())) {
                return "сегодня операции уже вносили" // not UI: в лог
            }
            return null
        }

        /**
         * Есть ли за `day` операция, внесённая человеком: не удалённая, не вторая нога
         * перевода и не комиссия (operation_link) и не похожая на периодическую — те же счета
         * и сумма (до 0,005), что у какого-нибудь шага раскладки **шаблона с расписанием** при
         * его общей сумме (openspec operation-templates, design D7). Ручные шаблоны не
         * считаются: операция, запущенная человеком, — внесённая. Своего признака «заведено
         * по расписанию» у операции нет, поэтому сравнение по счетам и сумме — в Kotlin:
         * операций за день единицы, шаблонов десятки.
         */
        @JvmStatic
        internal fun hasEnteredToday(db: SQLiteDatabase, day: LocalDate): Boolean {
            val scheduled = scheduledPostings(db)
            db.rawQuery(
                "SELECT o.acc_fr_id, o.acc_to_id, CAST(o.amount AS REAL) FROM operation o" +
                    " WHERE o.deleted_at IS NULL AND o.date >= ? AND o.date < ?" +
                    " AND o._id NOT IN (SELECT link_id FROM operation_link WHERE deleted_at IS NULL)",
                arrayOf(day.toString(), day.plusDays(1).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val from = c.getInt(0)
                    val to = c.getInt(1)
                    val amount = c.getDouble(2)
                    val generated = scheduled.any {
                        it.from == from && it.to == to && Math.abs(it.amount - amount) < 0.005
                    }
                    if (!generated) return true
                }
            }
            return false
        }

        private class Posting(val from: Int, val to: Int, val amount: Double)

        /** Проводки, которые заводят шаблоны с расписанием, — по раскладке их общей суммы. */
        private fun scheduledPostings(db: SQLiteDatabase): List<Posting> {
            val result = ArrayList<Posting>()
            for (t in OperationTemplatesDb.loadAll(db)) {
                if (t.schedule == null) continue
                val total = t.totalAmount ?: continue
                val layout = TemplateLayout.expand(t.steps, total, OperationTemplatesDb.precisionOf(db, t))
                if (layout !is TemplateLayout.Result.Ok) continue
                for ((i, s) in t.steps.withIndex()) {
                    result.add(Posting(s.accFromId, s.accToId, layout.amounts[i].toDouble()))
                }
            }
            return result
        }

        private fun show(ctx: Context) {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT
                )
            )

            // Тап — сразу экран новой операции поверх ленты (MainActivity.EXTRA_ADD_OPERATION).
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ADD_OPERATION, true)
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            val pendingIntent = PendingIntent.getActivity(
                ctx, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.main_icon)
                .setContentTitle(ctx.getString(R.string.reminder_title))
                .setContentText(ctx.getString(R.string.reminder_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
            Log.i(TAG, "notify")
        }
    }
}
