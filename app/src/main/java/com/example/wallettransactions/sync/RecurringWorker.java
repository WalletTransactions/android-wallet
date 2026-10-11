package com.example.wallettransactions.sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.wallettransactions.Data.RecurringOperationsDb;
import com.example.wallettransactions.DataItems.Operations.RecurringOperation;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.WalletApplication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Заводит наступившие периодические операции в фоне и уведомляет о них
 * ({@link NewOperationsNotifier}) — к моменту ближайшего повтора, независимо от
 * «Автоматического подключения» и сети. {@link SyncWorker} делает то же на своём
 * проходе, но он есть только при включённом автоподключении и раз в интервал, а
 * без него операции молча появлялись лишь при открытии приложения.
 * <p>
 * Цепочка одноразовых задач: каждая, отработав, ставит следующую на ближайший
 * повтор по всем правилам. Расчёт — только здесь, в фоне: остальные лишь
 * {@link #kick} — на старте процесса и после правки правил.
 */
public class RecurringWorker extends Worker {

    private static final String TAG = "RecurringWorker";
    private static final String WORK_NAME = "recurring_operations";

    public RecurringWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** На старте процесса: не трогает уже стоящую задачу, заводит, если её нет. */
    public static void ensureScheduled(Context ctx) {
        // На устройстве WorkManager поднимает androidx.startup раньше Application.onCreate;
        // под Robolectric его нет — без проверки падал бы старт приложения в каждом тесте.
        if (!WorkManager.isInitialized()) return;
        enqueue(ctx, ExistingWorkPolicy.KEEP, 0);
    }

    /** После правки правил: стоящая задача ждёт по старому расписанию — пересчитать сейчас. */
    public static void kick(Context ctx) {
        enqueue(ctx, ExistingWorkPolicy.REPLACE, 0);
    }

    private static void enqueue(Context ctx, ExistingWorkPolicy policy, long delayMillis) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(RecurringWorker.class)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        // После аварийного завершения базу не трогаем, пока пользователь не выбрал
        // действие в диалоге восстановления; следующий старт процесса заведёт цепочку снова.
        if (WalletApplication.hadCrash(ctx)) return Result.success();

        SQLiteDatabase db = Singleton.instance.getWritableDatabase(ctx);
        LocalDateTime now = LocalDateTime.now();
        List<Long> created = new ArrayList<>();
        RecurringOperationsDb.generateDue(db, now, created);
        if (!created.isEmpty()) {
            PeriodFilterState.get(ctx).markDataChanged();
            NewOperationsNotifier.notifyIfAny(ctx, null, created);
        }

        LocalDateTime next = nextDue(RecurringOperationsDb.loadAll(db), now);
        if (next == null) {
            Log.i(TAG, "повторов впереди нет");
            return Result.success();
        }
        long delay = Math.max(0, Duration.between(LocalDateTime.now(), next).toMillis());
        Log.i(TAG, "следующий повтор " + next);
        // Эта задача ещё RUNNING: REPLACE отменил бы её саму, KEEP — не поставил бы новую.
        enqueue(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE, delay);
        return Result.success();
    }

    /** Ближайший не созданный повтор по всем правилам; null — повторов больше не будет. */
    static LocalDateTime nextDue(List<RecurringOperation> rules, LocalDateTime now) {
        LocalDateTime best = null;
        for (RecurringOperation r : rules) {
            if (!r.isActive) continue;
            LocalDateTime next = r.nextOccurrence(now);
            if (next != null && (best == null || next.isBefore(best))) best = next;
        }
        return best;
    }
}
