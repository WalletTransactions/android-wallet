package com.example.wallettransactions.sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.TokenStore;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Один полный проход синка (refresh → push → pull) — общее тело для
 * {@link SyncWorker} (по расписанию, WorkManager) и кнопки «Синхронизировать
 * сейчас» (экран «Настройки» → «Синхронизация», по требованию пользователя).
 * Раньше это было целиком внутри {@code SyncWorker.doWork()} — вынесено,
 * чтобы кнопка не дублировала ту же последовательность вызовов и не
 * рассинхронизировалась с ней при следующей правке.
 * <p>
 * Отличие от WorkManager-варианта — возвращает подробный {@link Outcome} (в
 * т.ч. журнал изменений, см. {@link SyncLog}), а не только код успеха/повтора:
 * кнопке нужно ПОКАЗАТЬ, что произошло, воркеру — только решить, звать ли
 * {@code Result.retry()}.
 * <p>
 * Вызывающий обязан звать {@link #runOnce} НЕ на главном потоке — внутри
 * сеть (HTTP) и диск (SQLite), оба блокирующие.
 *
 * <h3>Один проход на процесс</h3>
 * Раньше кнопка и {@link SyncWorker} могли идти параллельно: одни и те же
 * dirty-строки уезжали двумя порциями, два {@code pullAll} двигали курсоры в
 * {@code AppSettings}. Теперь проход идёт на своём потоке, а все вызывающие
 * (воркер, кнопка, экран «ИИ») ждут один и тот же {@link FutureTask}: если
 * проход уже идёт, новый не начинается — вызывающий получает исход идущего.
 * WorkManager живёт в том же процессе, поэтому замка в памяти достаточно.
 * {@link #runOnceShared} ждёт с таймаутом и по его истечении отвечает
 * {@link Status#STILL_RUNNING}, проход при этом не прерывается.
 */
public final class SyncRunner {

    private SyncRunner() {}

    /**
     * PARTIAL — push и pull прошли, но часть строк осталась dirty (см.
     * {@link SyncLog#getProblems}): pull уже применён, поэтому вызывающий
     * обращается с журналом как при OK (уведомления о новых операциях),
     * но повторяет синк позже.
     */
    public enum Status { NO_SESSION, NEEDS_RELINK, NETWORK_ERROR, ERROR, PARTIAL, OK,
        /** Только из {@link #runOnceShared}: таймаут ожидания вышел, проход продолжается. */
        STILL_RUNNING }

    public static final class Outcome {
        public final Status status;
        public final SyncLog log;
        /** Только для NETWORK_ERROR/ERROR — что показать пользователю/записать в лог. */
        public final String errorMessage;

        public Outcome(Status status, SyncLog log, String errorMessage) {
            this.status = status;
            this.log = log;
            this.errorMessage = errorMessage;
        }
    }

    /** Тело прохода — подменяется в тестах (SyncRunnerTest), иначе {@link #doRunOnce}. */
    interface Pass {
        Outcome run(Context ctx);
    }

    static Pass pass = SyncRunner::doRunOnce;

    private static final Object LOCK = new Object();
    private static FutureTask<Outcome> current;

    /** Проход синка; если он уже идёт — ждёт его исход, второй не запускает. */
    public static Outcome runOnce(Context ctx) {
        return awaitUninterruptibly(joinOrStart(ctx));
    }

    /**
     * Как {@link #runOnce}, но ждёт не дольше {@code timeoutMs}: по таймауту —
     * {@link Status#STILL_RUNNING}, проход доводится до конца в фоне (экран «ИИ»).
     */
    public static Outcome runOnceShared(Context ctx, long timeoutMs) {
        FutureTask<Outcome> task = joinOrStart(ctx);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Outcome(Status.STILL_RUNNING, new SyncLog(), "синхронизация ещё идёт");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Status.STILL_RUNNING, new SyncLog(), "синхронизация ещё идёт");
        } catch (ExecutionException e) {
            return failed(e);
        }
    }

    private static FutureTask<Outcome> joinOrStart(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        synchronized (LOCK) {
            if (current != null && !current.isDone()) return current;
            FutureTask<Outcome> task = new FutureTask<>(() -> {
                Outcome outcome = pass.run(app);
                if (outcome.status == Status.OK || outcome.status == Status.PARTIAL) {
                    AppSettings.setLastSyncSuccessAt(app, System.currentTimeMillis());
                }
                // Здесь, а не у вызывающих: синк запускают и WorkManager, и кнопка, и
                // экраны ИИ/выписки — раньше ревизию поднимал только SyncWorker, и после
                // остальных экраны показывали остатки до синка.
                if (!outcome.log.isEmpty()) PeriodFilterState.get(app).markDataChanged();
                return outcome;
            });
            current = task;
            new Thread(task, "sync-pass").start();
            return task;
        }
    }

    /**
     * Прерывание вызывающего проход не отменяет, а исход нужен WorkManager-у и
     * кнопке — ждём до конца и возвращаем флаг прерывания на место.
     */
    private static Outcome awaitUninterruptibly(FutureTask<Outcome> task) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    return failed(e);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Outcome failed(ExecutionException e) {
        return new Outcome(Status.ERROR, new SyncLog(), "Неожиданная ошибка: " + e.getCause());
    }

    private static Outcome doRunOnce(Context ctx) {
        SyncLog log = new SyncLog();

        AuthSession.Outcome authOutcome = AuthSession.ensureFresh(ctx);
        switch (authOutcome) {
            case NO_SESSION:
                return new Outcome(Status.NO_SESSION, log, null);
            case NEEDS_RELINK:
                // Уведомление уже показано AuthSession — тихо повторить вход
                // через Google отсюда нельзя (см. её javadoc).
                return new Outcome(Status.NEEDS_RELINK, log, null);
            case NETWORK_ERROR:
                return new Outcome(Status.NETWORK_ERROR, log, "Сервер недоступен по сети");
            case OK:
                break;
        }

        SQLiteDatabase db = Singleton.instance.getWritableDatabase(ctx);
        SyncApiClient api = new SyncApiClient(ctx, TokenStore.getAccessToken(ctx));
        try {
            SyncEngine.pushDirty(db, api, log);
            SyncEngine.pullAll(db, api, ctx, log);
            if (!log.getProblems().isEmpty()) {
                // Push/pull прошли, но часть строк осталась dirty (например,
                // иконка не залилась — см. SyncEngine.collectDirtyIcons) —
                // не "всё синхронизировано", подробности в журнале (✖).
                return new Outcome(Status.PARTIAL, log, "Часть изменений не отправлена — повторите синхронизацию");
            }
            return new Outcome(Status.OK, log, null);
        } catch (IOException e) {
            // Сервер выключен/недоступен по сети между push и pull — обычное
            // дело для LAN-only стенда (план, п. 1.4).
            return new Outcome(Status.NETWORK_ERROR, log, "Сервер недоступен: " + e.getMessage());
        } catch (JSONException e) {
            return new Outcome(Status.ERROR, log, "Некорректный ответ сервера: " + e.getMessage());
        } catch (SyncEngine.PushRejectedException e) {
            // Сервер отклонил push-порцию (не самовосстанавливающийся
            // конфликт имени счёта — тот SyncEngine.pushDirty гасит сам, см.
            // её javadoc) — раньше это тихо проглатывалось и runOnce()
            // возвращал Status.OK, будто всё прошло (реальный инцидент
            // 2026-09-09, см. README wallet-api). log здесь может уже
            // нести подтверждённые "Отправлено" за успешные порции ДО
            // упавшей — показываем оба факта разом (см.
            // IntegrationsActivity.showSyncResultDialog, ветка ERROR).
            return new Outcome(Status.ERROR, log, "Сервер отклонил часть изменений — " + e.getMessage());
        } catch (RuntimeException e) {
            // "Максимум дебаг-информации" по прямому запросу пользователя —
            // не проглатывать неожиданное молча, кнопка вызвана вручную,
            // есть кому это увидеть прямо сейчас.
            return new Outcome(Status.ERROR, log, "Неожиданная ошибка: " + e);
        }
    }
}
