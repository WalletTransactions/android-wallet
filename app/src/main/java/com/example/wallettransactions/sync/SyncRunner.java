package com.example.wallettransactions.sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.TokenStore;

import org.json.JSONException;

import java.io.IOException;

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
 */
public final class SyncRunner {

    private SyncRunner() {}

    /**
     * PARTIAL — push и pull прошли, но часть строк осталась dirty (см.
     * {@link SyncLog#getProblems}): pull уже применён, поэтому вызывающий
     * обращается с журналом как при OK (уведомления о новых операциях),
     * но повторяет синк позже.
     */
    public enum Status { NO_SESSION, NEEDS_RELINK, NETWORK_ERROR, ERROR, PARTIAL, OK }

    public static final class Outcome {
        public final Status status;
        public final SyncLog log;
        /** Только для NETWORK_ERROR/ERROR — что показать пользователю/записать в лог. */
        public final String errorMessage;

        private Outcome(Status status, SyncLog log, String errorMessage) {
            this.status = status;
            this.log = log;
            this.errorMessage = errorMessage;
        }
    }

    public static Outcome runOnce(Context ctx) {
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
