package com.example.wallettransactions.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Texts
import com.example.wallettransactions.TokenStore
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Один полный проход синка (refresh → push → pull) — общее тело для
 * [SyncWorker] (по расписанию, WorkManager) и кнопки «Синхронизировать
 * сейчас» (экран «Настройки» → «Синхронизация», по требованию пользователя).
 * Раньше это было целиком внутри `SyncWorker.doWork()` — вынесено,
 * чтобы кнопка не дублировала ту же последовательность вызовов и не
 * рассинхронизировалась с ней при следующей правке.
 *
 * Отличие от WorkManager-варианта — возвращает подробный [Outcome] (в
 * т.ч. журнал изменений, см. [SyncLog]), а не только код успеха/повтора:
 * кнопке нужно ПОКАЗАТЬ, что произошло, воркеру — только решить, звать ли
 * `Result.retry()`.
 *
 * Вызывающий обязан звать [runOnce] НЕ на главном потоке — внутри
 * сеть (HTTP) и диск (SQLite), оба блокирующие.
 *
 * ### Один проход на процесс
 * Раньше кнопка и [SyncWorker] могли идти параллельно: одни и те же
 * dirty-строки уезжали двумя порциями, два `pullAll` двигали курсоры в
 * `AppSettings`. Теперь проход идёт на своём потоке, а все вызывающие
 * (воркер, кнопка, экран «ИИ») ждут один и тот же [FutureTask]: если
 * проход уже идёт, новый не начинается — вызывающий получает исход идущего.
 * WorkManager живёт в том же процессе, поэтому замка в памяти достаточно.
 * [runOnceShared] ждёт с таймаутом и по его истечении отвечает
 * [Status.STILL_RUNNING], проход при этом не прерывается.
 */
object SyncRunner {

    /**
     * PARTIAL — push и pull прошли, но часть строк осталась dirty (см.
     * [SyncLog.getProblems]): pull уже применён, поэтому вызывающий
     * обращается с журналом как при OK (уведомления о новых операциях),
     * но повторяет синк позже.
     */
    enum class Status {
        NO_SESSION, NEEDS_RELINK, NETWORK_ERROR, ERROR, PARTIAL, OK,
        /** Только из [runOnceShared]: таймаут ожидания вышел, проход продолжается. */
        STILL_RUNNING,
        /**
         * Подключение к кошельку ждёт ответа на вопрос об операциях без входа (spec
         * ledger-connection): загрузка прошла, отправки не было.
         */
        AWAITING_DECISION
    }

    /** Причина исхода [Status.AWAITING_DECISION] — для журнала и экранов синка. */
    @JvmStatic
    fun awaitingDecisionMessage(): String = Texts.get(R.string.sync_awaiting_decision)

    class Outcome(
        @JvmField val status: Status,
        @JvmField val log: SyncLog,
        /** Только для NETWORK_ERROR/ERROR — что показать пользователю/записать в лог. */
        @JvmField val errorMessage: String?
    )

    /** Тело прохода — подменяется в тестах (SyncRunnerTest), иначе [doRunOnce]. */
    internal fun interface Pass {
        fun run(ctx: Context): Outcome
    }

    internal var pass: Pass = Pass { ctx -> doRunOnce(ctx) }

    private val LOCK = Any()
    private var current: FutureTask<Outcome>? = null

    /** Проход синка; если он уже идёт — ждёт его исход, второй не запускает. */
    @JvmStatic
    fun runOnce(ctx: Context): Outcome = awaitUninterruptibly(joinOrStart(ctx))

    /**
     * Как [runOnce], но ждёт не дольше `timeoutMs`: по таймауту —
     * [Status.STILL_RUNNING], проход доводится до конца в фоне (экран «ИИ»).
     */
    @JvmStatic
    fun runOnceShared(ctx: Context, timeoutMs: Long): Outcome {
        val task = joinOrStart(ctx)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Outcome(Status.STILL_RUNNING, SyncLog(), Texts.get(R.string.sync_still_running))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome(Status.STILL_RUNNING, SyncLog(), Texts.get(R.string.sync_still_running))
        } catch (e: ExecutionException) {
            failed(e)
        }
    }

    private fun joinOrStart(ctx: Context): FutureTask<Outcome> {
        val app = ctx.applicationContext ?: ctx
        synchronized(LOCK) {
            val cur = current
            if (cur != null && !cur.isDone) return cur
            val task = FutureTask<Outcome> {
                val outcome = pass.run(app)
                if (outcome.status == Status.OK || outcome.status == Status.PARTIAL) {
                    AppSettings.setLastSyncSuccessAt(app, System.currentTimeMillis())
                }
                // Здесь, а не у вызывающих: синк запускают и WorkManager, и кнопка, и
                // экраны ИИ/выписки — раньше ревизию поднимал только SyncWorker, и после
                // остальных экраны показывали остатки до синка.
                if (!outcome.log.isEmpty()) PeriodFilterState.get(app).markDataChanged()
                outcome
            }
            current = task
            Thread(task, "sync-pass").start()
            return task
        }
    }

    /**
     * Прерывание вызывающего проход не отменяет, а исход нужен WorkManager-у и
     * кнопке — ждём до конца и возвращаем флаг прерывания на место.
     */
    private fun awaitUninterruptibly(task: FutureTask<Outcome>): Outcome {
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    return failed(e)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun failed(e: ExecutionException): Outcome =
        Outcome(Status.ERROR, SyncLog(), Texts.get(R.string.sync_unexpected_error, e.cause.toString()))

    private fun doRunOnce(ctx: Context): Outcome {
        val log = SyncLog()

        when (AuthSession.ensureFresh(ctx)) {
            AuthSession.Outcome.NO_SESSION -> return Outcome(Status.NO_SESSION, log, null)
            // Уведомление уже показано AuthSession — тихо повторить вход
            // через Google отсюда нельзя (см. её javadoc).
            AuthSession.Outcome.NEEDS_RELINK -> return Outcome(Status.NEEDS_RELINK, log, null)
            AuthSession.Outcome.NETWORK_ERROR ->
                return Outcome(
                    Status.NETWORK_ERROR, log,
                    AuthSession.lastNetworkErrorText(Texts.context(), Texts.get(R.string.sync_server_unreachable))
                )
            AuthSession.Outcome.OK -> {}
        }

        val db = Singleton.instance.getWritableDatabase(ctx)
        startConnectionIfFresh(ctx, db)
        val api = SyncApiClient(ctx, TokenStore.getAccessToken(ctx))
        return runSteps(object : Steps {
            @Throws(IOException::class, JSONException::class)
            override fun push(log: SyncLog) {
                SyncEngine.redirtyDebtsIfPending(db, ctx, log)
                // Свои иконки — до строк (openspec optional-icon-upload): принятая иконка
                // уходит в счета своим uuid, отказ — счета уходят с заместителем.
                if (AppSettings.shouldUploadOwnIcons(ctx) &&
                    SyncEngine.pushOwnIcons(db, api, log) == SyncEngine.IconUploadResult.REFUSED
                ) {
                    SyncEngine.disableIconUpload(ctx, log)
                }
                SyncEngine.pushDirty(db, api, log)
            }

            @Throws(IOException::class, JSONException::class)
            override fun pull(log: SyncLog) {
                SyncEngine.pullAll(db, api, ctx, log)
            }

            override fun purge() {
                SyncEngine.purgeTombstones(db)
            }

            override fun connecting(): Boolean = AppSettings.isLedgerConnectionPending(ctx)

            @Throws(IOException::class, JSONException::class)
            override fun connectionPull(log: SyncLog) {
                SyncEngine.adoptLocalByKey = true
                try {
                    SyncEngine.pullAll(db, api, ctx, log)
                } finally {
                    SyncEngine.adoptLocalByKey = false
                }
            }

            override fun connect(): LedgerConnection.Step {
                val transfer = AppSettings.getConnectionDecision(ctx) == AppSettings.ConnectionDecision.TRANSFER
                val step = LedgerConnection.afterPull(db, transfer)
                // Шаг мог удалить строки набора — справочники в памяти перечитываются.
                Singleton.instance.reloadAccounts(ctx)
                Singleton.instance.reloadCurrencyList(ctx)
                Singleton.instance.reloadCurrencyPairList(ctx)
                if (step == LedgerConnection.Step.ASK) {
                    AppSettings.setConnectionDecision(
                        ctx, AppSettings.ConnectionDecision.ASKED,
                        LedgerConnection.offlineOperationCount(db)
                    )
                }
                // После решения: главный экран по этому сигналу перечитывает ленту и
                // показывает вопрос (MainActivity.showConnectionQuestion).
                PeriodFilterState.get(ctx).markDataChanged()
                return step
            }

            override fun connectionDone() {
                AppSettings.setLedgerConnectionPending(ctx, false)
            }
        }, log)
    }

    /**
     * Подключение начинается в проходе со входом, если на телефоне нет строк, бывших на
     * сервере (spec ledger-connection, design D4), и длится до прохода с прошедшей
     * отправкой — даже если загрузка прерывалась и часть строк уже принята.
     *
     * Загрузка подключения — с нуля: курсоры синка сбрасываются при старте. Иначе после
     * «Удалить всё безвозвратно» при живом входе курсоры остались бы на прежней позиции,
     * загрузка не вернула бы кошелёк, он выглядел бы пустым, и набор ушёл бы в него
     * дублями категорий. Прерванное подключение курсоры не сбрасывает — продолжает.
     */
    @JvmStatic
    internal fun startConnectionIfFresh(ctx: Context, db: SQLiteDatabase) {
        if (!AppSettings.isLedgerConnectionPending(ctx) && !SyncEngine.hasSyncedRows(db)) {
            AppSettings.setLedgerSyncSeq(ctx, 0)
            AppSettings.setReferenceSyncSeq(ctx, 0)
            AppSettings.setLedgerConnectionPending(ctx, true)
        }
    }

    /** Шаги прохода после входа — подменяются в тестах (SyncRunnerTest). */
    internal interface Steps {
        @Throws(IOException::class, JSONException::class)
        fun push(log: SyncLog)

        @Throws(IOException::class, JSONException::class)
        fun pull(log: SyncLog)

        fun purge()

        /** Идёт подключение к кошельку (spec ledger-connection) — загрузка до отправки. */
        fun connecting(): Boolean = false

        /** Загрузка подключения: с принятием серверных строк по ключу (design D6). */
        @Throws(IOException::class, JSONException::class)
        fun connectionPull(log: SyncLog) { pull(log) }

        /** Шаг подключения после загрузки (design D7, D8). */
        fun connect(): LedgerConnection.Step = LedgerConnection.Step.PUSH

        /** Отправка подключения прошла — подключение закончено. */
        fun connectionDone() {}
    }

    /**
     * push → pull → чистка отправленных удалений (openspec purge-synced-tombstones,
     * spec sync-run «Состав прохода»). Чистка — только после успешного pull: без
     * сети или при ошибке загрузки до неё не доходит, и строку, ссылку на которую
     * принесла бы незагруженная страница, она не трогает. Ошибка чистки исход не
     * меняет — данные уже синхронизированы, удалённое повторится следующим проходом.
     *
     * Подключение к кошельку (openspec new-user-default-set, design D5): pull → шаг
     * подключения → push → чистка. Шаг ответил «спросить» — исход
     * [Status.AWAITING_DECISION], отправки нет. Ошибка до push оставляет
     * подключение незаконченным — следующий проход продолжит его.
     */
    @JvmStatic
    internal fun runSteps(steps: Steps, log: SyncLog): Outcome {
        try {
            if (steps.connecting()) {
                steps.connectionPull(log)
                if (steps.connect() == LedgerConnection.Step.ASK) {
                    return Outcome(Status.AWAITING_DECISION, log, awaitingDecisionMessage())
                }
                steps.push(log)
                steps.connectionDone()
            } else {
                steps.push(log)
                steps.pull(log)
            }
            try {
                steps.purge()
            } catch (e: RuntimeException) {
                android.util.Log.w("SyncRunner", "чистка удалённых строк не удалась — повторится в следующий раз", e)
            }
            if (log.getProblems().isNotEmpty()) {
                // Push/pull прошли, но часть строк осталась dirty (например,
                // нет иконки-заместителя — см. SyncEngine.PushIconRefs) —
                // не "всё синхронизировано", подробности в журнале (✖).
                return Outcome(Status.PARTIAL, log, Texts.get(R.string.sync_partial))
            }
            return Outcome(Status.OK, log, null)
        } catch (e: IOException) {
            // Сервер выключен/недоступен по сети между push и pull — обычное
            // дело для LAN-only стенда (план, п. 1.4).
            return Outcome(Status.NETWORK_ERROR, log, NetworkErrors.describe(e))
        } catch (e: JSONException) {
            return Outcome(Status.ERROR, log, Texts.get(R.string.sync_bad_response, e.message))
        } catch (e: SyncEngine.PushRejectedException) {
            // Сервер отклонил push-порцию (не самовосстанавливающийся
            // конфликт имени счёта — тот SyncEngine.pushDirty гасит сам, см.
            // её javadoc) — раньше это тихо проглатывалось и runOnce()
            // возвращал Status.OK, будто всё прошло (реальный инцидент
            // 2026-09-09, см. README wallet-api). log здесь может уже
            // нести подтверждённые "Отправлено" за успешные порции ДО
            // упавшей — показываем оба факта разом (см.
            // IntegrationsActivity.showSyncResultDialog, ветка ERROR).
            return Outcome(Status.ERROR, log, Texts.get(R.string.sync_rejected, e.message))
        } catch (e: RuntimeException) {
            // "Максимум дебаг-информации" по прямому запросу пользователя —
            // не проглатывать неожиданное молча, кнопка вызвана вручную,
            // есть кому это увидеть прямо сейчас.
            return Outcome(Status.ERROR, log, Texts.get(R.string.sync_unexpected_error, e.toString()))
        }
    }
}
