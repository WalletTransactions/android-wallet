package com.example.wallettransactions.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.wallettransactions.Activities.MainActivity;
import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * «Напоминание вносить операции» («Настройки» → «Уведомления»): раз в день в выбранное
 * время. Как {@code RecurringWorker} — цепочка одноразовых задач WorkManager: каждая
 * ставит следующую на завтра. Без точных будильников: им на Android 12+ нужно отдельное
 * разрешение, а напоминанию сдвиг на несколько минут не страшен. WorkManager сам
 * переживает перезагрузку телефона.
 * <p>
 * Не беспокоит, если:
 * <ul>
 *   <li>сегодня уже внесена операция ({@link #hasEnteredToday}) — периодические не в счёт;</li>
 *   <li>приложение открыто — и так видно;</li>
 *   <li>задача опоздала больше чем на {@link #MAX_LATE} (телефон был выключен, Doze) —
 *       напоминание «внести за сегодня» среди ночи хуже, чем никакого.</li>
 * </ul>
 * Логи: {@code adb logcat -s ReminderWorker}.
 */
public class ReminderWorker extends Worker {

    private static final String TAG = "ReminderWorker";
    private static final String WORK_NAME = "daily_reminder";
    private static final String CHANNEL_ID = "reminders";
    // 1 — AuthSession, 2 — NewOperationsNotifier.
    private static final int NOTIFICATION_ID = 3;
    private static final String KEY_DUE_AT = "dueAt";
    static final Duration MAX_LATE = Duration.ofHours(2);

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // ── Расписание ────────────────────────────────────────────────────────────

    /** После смены переключателя или времени: пересобрать цепочку с нуля. */
    public static void reschedule(Context ctx) {
        if (!AppSettings.isRemindOn(ctx)) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME);
            return;
        }
        enqueue(ctx, ExistingWorkPolicy.REPLACE);
    }

    /** На старте процесса: заводит цепочку, если её нет, а напоминание включено. */
    public static void ensureScheduled(Context ctx) {
        // Под Robolectric WorkManager не поднят — см. RecurringWorker.ensureScheduled.
        if (!WorkManager.isInitialized() || !AppSettings.isRemindOn(ctx)) return;
        enqueue(ctx, ExistingWorkPolicy.KEEP);
    }

    private static void enqueue(Context ctx, ExistingWorkPolicy policy) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime due = nextDue(now, AppSettings.getRemindHour(ctx), AppSettings.getRemindMinute(ctx));
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                .setInitialDelay(Duration.between(now, due).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putLong(KEY_DUE_AT, due.toInstant().toEpochMilli()).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, request);
        Log.i(TAG, "следующее напоминание " + due.toLocalDateTime());
    }

    /**
     * Ближайшее {@code hour:minute} строго после {@code now}. Через ZonedDateTime, а не
     * «+24 часа»: в день перевода часов сутки длиннее или короче.
     */
    static ZonedDateTime nextDue(ZonedDateTime now, int hour, int minute) {
        ZonedDateTime today = now.with(LocalTime.of(hour, minute));
        return today.isAfter(now) ? today : now.toLocalDate().plusDays(1)
                .atTime(hour, minute).atZone(now.getZone());
    }

    // ── Срабатывание ──────────────────────────────────────────────────────────

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!AppSettings.isRemindOn(ctx)) return Result.success();

        long dueAt = getInputData().getLong(KEY_DUE_AT, System.currentTimeMillis());
        String skip = skipReason(ctx, System.currentTimeMillis() - dueAt);
        if (skip != null) Log.i(TAG, "skip: " + skip);
        else show(ctx);

        // Эта задача ещё RUNNING: REPLACE отменил бы её саму, KEEP — не поставил бы новую.
        enqueue(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE);
        return Result.success();
    }

    /** null — показывать. */
    private static String skipReason(Context ctx, long lateMillis) {
        if (lateMillis > MAX_LATE.toMillis()) return "опоздали на " + lateMillis / 60_000 + " мин";
        if (ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            return "приложение на экране";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return "нет разрешения POST_NOTIFICATIONS";
        }
        if (hasEnteredToday(Singleton.instance.getReadableDatabase(ctx), LocalDate.now())) {
            return "сегодня операции уже вносили";
        }
        return null;
    }

    /**
     * Есть ли за {@code day} операция, внесённая человеком: не удалённая, не вторая нога
     * перевода и не комиссия (operation_link) и не похожая на периодическую — те же счета
     * и сумма, что у какого-нибудь правила «Повторяющихся операций». Своего признака
     * «заведено правилом» у операции нет, поэтому сравнение по счетам и сумме.
     */
    static boolean hasEnteredToday(SQLiteDatabase db, LocalDate day) {
        Cursor c = db.rawQuery(
                "SELECT 1 FROM operation o"
                        + " WHERE o.deleted_at IS NULL AND o.date >= ? AND o.date < ?"
                        + " AND o._id NOT IN (SELECT link_id FROM operation_link WHERE deleted_at IS NULL)"
                        + " AND NOT EXISTS (SELECT 1 FROM recurring_operation r"
                        + "   WHERE r.acc_fr_id = o.acc_fr_id AND r.acc_to_id = o.acc_to_id"
                        + "   AND ABS(CAST(r.amount AS REAL) - CAST(o.amount AS REAL)) < 0.005)"
                        + " LIMIT 1",
                new String[]{day.toString(), day.plusDays(1).toString()});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private static void show(Context ctx) {
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT));

        // Тап — сразу экран новой операции поверх ленты (MainActivity.EXTRA_ADD_OPERATION).
        Intent intent = new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ADD_OPERATION, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.main_icon)
                .setContentTitle("Не забудьте внести операции")
                .setContentText("Что сегодня потратили или получили?")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
        Log.i(TAG, "notify");
    }
}
