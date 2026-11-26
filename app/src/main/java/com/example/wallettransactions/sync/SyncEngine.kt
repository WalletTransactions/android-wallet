package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.OwnIconNames
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Texts
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/**
 * Движок push/pull (org/docs/TODO_mobile_sync.md, Фаза 3, п. 4) — переливает
 * dirty-строки локальной SQLite в `POST /sync/push` и применяет
 * `GET /sync/pull` обратно. Протокол — README wallet-api,
 * "Синхронизация"; вызывается из [SyncWorker], транспорт — в
 * [SyncApiClient].
 *
 * ### Перевод id ↔ uuid
 * Локальная схема ссылается на связанные строки целочисленным `_id`
 * (свой на каждом устройстве), протокол сервера — `uuid` (общий для
 * всех устройств). Push переводит `_id -> uuid` через [uuidById],
 * pull — `uuid -> _id` через [findIdByUuid], каждый раз читая
 * таблицу-цель напрямую (батчи небольшие, кэш не нужен).
 *
 * ### Порядок применения pull (см. [TABLE_ORDER])
 * Строка со ссылкой (например, `account.icon_uuid`) обязана применяться
 * ПОСЛЕ строки, на которую она ссылается, иначе перевод uuid->_id не найдёт
 * локальную запись. Оба потока pull (ledger/reference, см. README wallet-api)
 * сливаются в одну карту ПЕРЕД применением ([mergeChanges]) и
 * обходятся в фиксированном порядке зависимостей — это безопасно, потому что
 * все строки одной страницы одного push-батча делят один и тот же
 * `seq`, а ссылающаяся строка не может быть создана раньше той, на
 * которую она ссылается (то есть её `seq` не может быть МЕНЬШЕ) —
 * см. README wallet-api, "Курсор и страницы".
 *
 * ### Порции push (см. [pushDirty])
 * Один вызов [pushDirty] может отправить НЕСКОЛЬКО `POST
 * /sync/push` подряд — по [MAX_PUSH_BATCH_ROWS] строк на порцию, а не
 * весь dirty-набор одним запросом. Порядок ТАБЛИЦ между порциями — тот же
 * [TABLE_ORDER], что и у pull (см. выше) и `kApplyOrder` на
 * сервере (sync_repo.cpp) — обязателен по той же причине: строка-ссылка не
 * должна уйти в порции РАНЬШЕ строки, на которую ссылается.
 *
 * ### Иконки — только скачиваются (openspec server-managed-icons)
 * Общий набор иконок ведёт администратор на сайте; телефон иконки на сервер
 * НЕ отправляет — ни строк `icon` в push, ни байт в `POST
 * /sync/blobs`. Иконка на телефоне — либо «с сервера» (`icon.origin
 * = 0`), либо «своя» (`origin = 1`, из галереи или seed.json). У счёта
 * или валюты со своей иконкой в push уходит `icon_uuid` заместителя с
 * сервера ([IconSubstitutes], [PushIconRefs]); нет заместителя —
 * строка и всё, что на неё ссылается, откладываются ([deferDependents]).
 * Pull с тем же заместителем свою иконку не затирает ([PullIconGuard]).
 *
 * На pull метаданные иконки идут строкой `icon`, а байты — отдельным
 * `GET /sync/blobs` ([SyncApiClient.downloadBlob]): для НОВОЙ
 * (по uuid) иконки сначала скачиваем содержимое, для уже существующей —
 * только обновляем метаданные, байты не перекачиваем (см. [applyIcon]);
 * все нужные для страницы скачивания делаются ДО `db.beginTransaction()`
 * (см. [prefetchNewIconBlobs]), не внутри неё — держать
 * sqlite-транзакцию открытой на время сетевого запроса означало бы
 * блокировать доступ к БД для всего остального приложения (один общий
 * connection, см. `Singleton`) на непредсказуемое время.
 *
 * Локальное удаление своей иконки (`IconEditDialog.deleteIcon`) —
 * жёсткий `DELETE` (защиту от удаления ИСПОЛЬЗУЕМОЙ даёт FK); иконки с
 * сервера на телефоне не правятся и не удаляются.
 *
 * ### Что ещё сознательно не решено в этом заходе
 *  - Конфликт дублирующегося имени/кода диагностируется сервером
 *    осмысленно (409 + `existing_uuid`) только для `account`
 *    (см. [resolveAccountConflicts]) — для остальных уникальных
 *    индексов (currency.name/iso_code, icon.name,
 *    currency_pair_rate/currency_conversion PK) столкновение на pull-вставке
 *    просто пропускает строку с предупреждением в лог (см.
 *    [upsertByUuid]), не пытаясь слить дубли — тот же класс проблемы,
 *    что и с account, но без протокола разрешения (см.
 *    org/docs/TODO_mobile_sync.md, "Открытые вопросы").
 *  - Исключение — `currency_pair.symbol`: общая пара и пара кошелька с одним
 *    символом законно сосуществуют на сервере (два уникальных индекса), а на телефоне индекс
 *    один; пара кошелька заменяет общую (uuid меняется, `_id` и курсы остаются),
 *    общая уступает паре кошелька — см. [resolvePairSymbolConflict]
 *    (openspec sync-pair-symbol-adopt).
 */
internal object SyncEngine {

    private const val TAG = "SyncEngine"

    /**
     * Идёт загрузка подключения (openspec new-user-default-set, design D6): пришедшая
     * валюта, пара или счёт заменяет неотправленную локальную строку с тем же ключом
     * ([LedgerConnection.adoptByKey]), а не пропускается на уникальном индексе.
     * Ставит и снимает `SyncRunner` вокруг загрузки подключения; проход синка в
     * процессе один, поэтому статического флага достаточно.
     */
    @Volatile
    @JvmStatic
    var adoptLocalByKey: Boolean = false

    /** Действие строки журнала загрузки: «Удалено» / «Добавлено» / «Изменено». */
    private fun pullAction(deleted: Boolean, inserted: Boolean): String =
        Texts.get(
            if (deleted) R.string.sync_action_deleted
            else if (inserted) R.string.sync_action_added else R.string.sync_action_changed
        )

    /** См. javadoc класса, "Порядок применения pull" — icon первым: на него ссылаются account/currency. */
    internal val TABLE_ORDER = arrayOf(
        "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
        "account", "account_operating", "account_asset", "counterparty", "account_debt",
        "operation", "operation_link",
    )

    /**
     * Верхняя граница числа строк в ОДНОЙ порции push (см. [pushDirty],
     * "Порции push" в javadoc класса) — не протокольное ограничение (сервер
     * принял бы и больше, см. README wallet-api, "Синхронизация": размер
     * батча там нигде не проверяется), а разумный практический предел: после
     * долгого офлайна dirty-строк может накопиться десятки тысяч (например,
     * импорт истории курсов), и заливать их одним HTTP-телом/одной
     * Postgres-транзакцией — рискованно по памяти на телефоне и по времени
     * удержания транзакции на сервере. 1000 — округлый порядок, подобранный
     * "на глаз", не из измерений.
     */
    private const val MAX_PUSH_BATCH_ROWS = 1000

    /**
     * Верхняя граница числа РАУНДОВ самолечения в ОДНОМ вызове
     * [pushDirty] (см. её javadoc, "Цикл самолечения") — не ожидаемый предел
     * (каждый раунд чинит минимум одну РАЗЛИЧНУЮ строку — `redirty()`/
     * `resolveAccountConflicts` не тронули бы её повторно, будь она уже
     * dirty=1 либо уже переклеена, см. их javadoc, — так что раундов не может
     * быть больше, чем протухших строк, а это конечное число), а защита от
     * пока не виданного бага — например, сервер как-то умудрился отвечать
     * missing_reference на одну и ту же строку раз за разом. 200 — с большим
     * запасом (реальный инцидент 2026-09-14 чинился за десятки раундов, не
     * за сотни).
     */
    private const val MAX_SELF_HEAL_ROUNDS = 200

    // ── ОТПРАВКА СВОИХ ИКОНОК (openspec optional-icon-upload) ────────────────

    /** Итог шага [pushOwnIcons]. */
    internal enum class IconUploadResult {
        /** Шаг прошёл: отправлено всё, что получилось; сбои отдельных иконок — в журнале. */
        DONE,
        /** Сервер не принимает свои иконки (`icon_upload_disabled`) — см. [disableIconUpload]. */
        REFUSED,
    }

    /** Попыток загрузки содержимого одной иконки (обрыв на медленном relay, инцидент 2026-10-11). */
    internal const val BLOB_UPLOAD_ATTEMPTS = 3

    /** Пауза между попытками; тесты подменяют, чтобы не ждать секунды. */
    internal fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleep(ms: Long)
    }

    internal var sleeper: Sleeper = Sleeper { ms -> Thread.sleep(ms) }

    /** Отказ сервера по флагу приёма своих иконок — прерывает шаг целиком. */
    private class IconUploadRefused : Exception()

    /** Своя иконка телефона: то, что нужно для отправки. */
    private class OwnIconRow(val uuid: String?, val name: String?, val image: ByteArray?, val iconType: Int)

    /**
     * Отправка своих иконок (`origin = 1`) до push остальных строк — только при
     * включённой настройке и разрешающем флаге сервера (проверяет вызывающий,
     * [AppSettings.shouldUploadOwnIcons]). Выбор — по происхождению, не по `dirty`
     * (design п. 5): v86 обнулил `dirty` у иконок, а включение настройки должно
     * отправить и накопленные.
     *
     * Каждая иконка — отдельно: `HEAD`/`POST /sync/blobs` (до
     * [BLOB_UPLOAD_ATTEMPTS] попыток), затем свой push-батч с одной строкой `icon`.
     * Отдельные батчи — чтобы ни отброшенная по флагу иконка, ни `409` по имени одной
     * иконки не откатили ни счета (они уходят позже, в [pushDirty]), ни другие иконки.
     * Принятая становится иконкой с сервера (`origin = 0`) — дальше [PushIconRefs]
     * отдаёт её uuid, а не заместителя.
     *  - Та же картинка уже есть среди иконок с сервера — не отправляется (design п. 6).
     *  - `403 icon_upload_disabled` или `icons_rejected` — [IconUploadResult.REFUSED],
     *    шаг прерывается.
     *  - HTTP-отказ одной иконки (`409`, `5xx`, исчерпаны попытки) — запись ⚠ в журнал,
     *    иконка остаётся своей, следующая. Не «проблема»: проход не становится «частично».
     *  - Обрыв сети после всех попыток — [IOException] наружу, проход «нет сети».
     */
    @Throws(IOException::class, JSONException::class)
    internal fun pushOwnIcons(db: SQLiteDatabase, api: SyncApiClient, log: SyncLog): IconUploadResult {
        val own = ArrayList<OwnIconRow>() // {uuid, name, image, iconType}
        val c = db.rawQuery(
            "SELECT " + WalletContract.IconEntry.COLUMN_UUID +
                ", " + WalletContract.IconEntry.COLUMN_NAME +
                ", " + WalletContract.IconEntry.COLUMN_IMAGE +
                ", " + WalletContract.IconEntry.COLUMN_ICON_TYPE +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry.COLUMN_ORIGIN + " = " + WalletContract.IconEntry.ORIGIN_LOCAL +
                " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                " ORDER BY " + WalletContract.IconEntry._ID, null
        )
        try {
            while (c.moveToNext()) {
                own.add(OwnIconRow(c.getString(0), c.getString(1), c.getBlob(2), c.getInt(3)))
            }
        } finally {
            c.close() // сеть — уже после, не поверх открытого курсора
        }
        if (own.isEmpty()) return IconUploadResult.DONE

        val server = IconSubstitutes.load(db)
        for (r in own) {
            val uuid = r.uuid
            val name = r.name
            val image = r.image
            if (uuid == null || image == null || image.isEmpty()) continue // нечего отправлять
            val sha256 = IconSubstitutes.sha256Hex(image)
            if (server.hasServerImage(sha256)) continue

            val contentSha256: String
            try {
                contentSha256 = ensureBlobUploaded(api, image, sha256)
            } catch (e: IconUploadRefused) {
                return IconUploadResult.REFUSED
            } catch (e: SyncApiClient.HttpStatusException) {
                Log.w(TAG, "push icons: " + uuid + " не загружена: " + e.message)
                log.healed(Texts.get(R.string.sync_icon_upload_failed, name, e.message))
                continue
            }

            val row = JSONObject()
            row.put("uuid", uuid)
            row.put("name", name)
            row.put("content_sha256", contentSha256)
            row.put("icon_type", r.iconType)
            row.put("deleted", false)
            val changes = JSONObject()
            changes.put("icon", JSONArray().put(row))

            val result = api.push(UUID.randomUUID().toString(), changes)
            if (result.status == 200) {
                if (SyncApiClient.iconsRejected(result.body).isNotEmpty()) return IconUploadResult.REFUSED
                markIconFromServer(db, uuid)
                // Теперь это иконка с сервера: своя с той же картинкой дальше в этом же шаге
                // не грузится (иначе на сервере два одинаковых изображения).
                server.addServerIcon(uuid, name, contentSha256)
                log.push(Texts.get(R.string.sync_type_icon), Texts.get(R.string.sync_action_sent))
                continue
            }
            if (SyncApiClient.isIconUploadDisabled(result.status, result.body)) return IconUploadResult.REFUSED
            val reason = "HTTP " + result.status + (if (result.body.has("error")) " " + result.body.optString("error") else "")
            Log.w(TAG, "push icons: " + uuid + " отклонена: " + result.body)
            log.healed(Texts.get(R.string.sync_icon_upload_failed, name, reason))
        }
        return IconUploadResult.DONE
    }

    /**
     * Содержимое иконки на сервере: `HEAD`, при отсутствии — `POST`. Возвращает
     * `content_sha256` (при расхождении с локальным — серверный: строка `icon`
     * сошлётся на то, что реально лежит в blob). Последняя неудача — наружу: HTTP-отказ
     * ([SyncApiClient.HttpStatusException]) или обрыв сети ([IOException]).
     */
    @Throws(IOException::class, IconUploadRefused::class)
    private fun ensureBlobUploaded(api: SyncApiClient, image: ByteArray, sha256: String): String {
        var last: IOException? = null
        for (attempt in 1..BLOB_UPLOAD_ATTEMPTS) {
            try {
                if (api.blobExists(sha256)) return sha256
                val uploaded = api.uploadBlob(image)
                if (SyncApiClient.isIconUploadDisabled(uploaded.status, uploaded.body)) throw IconUploadRefused()
                if (uploaded.status == 200) {
                    val serverSha = uploaded.body.optString("sha256", "")
                    if (serverSha.isNotEmpty() && serverSha != sha256) {
                        Log.w(TAG, "push icons: sha256 расходится: локально $sha256, сервер $serverSha")
                        return serverSha
                    }
                    return sha256
                }
                val failure = SyncApiClient.HttpStatusException(
                    "POST /sync/blobs -> HTTP " + uploaded.status +
                        (if (uploaded.body.length() > 0) " " + uploaded.body else ""), uploaded.status
                )
                last = failure
                if (uploaded.status == 401 || (uploaded.status >= 400 && uploaded.status < 500)) {
                    throw failure // ответ не изменится от повтора
                }
            } catch (e: JSONException) {
                last = SyncApiClient.HttpStatusException(
                    "POST /sync/blobs: malformed server response (" + e.message + ")", 200
                )
            } catch (e: IOException) {
                if (e === last) throw e
                last = e
            }
            if (attempt < BLOB_UPLOAD_ATTEMPTS) {
                Log.i(TAG, "push icons: попытка " + attempt + " не удалась (" + last?.message + "), повторяю")
                try {
                    sleeper.sleep(1000L * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw last!!
                }
            }
        }
        throw last!!
    }

    /**
     * Своя иконка принята сервером — теперь она «с сервера»: `origin = 0`, `dirty = 0`.
     * Два UPDATE из-за `trg_icon_dirty` (`WHEN NEW.dirty = OLD.dirty`, см.
     * `WalletDbHelper.markIconOriginV86`): первый триггер помечает dirty, второй меняет
     * значение 1→0 — триггер молчит. Счета и валюты с этой иконкой снова dirty
     * ([redirtyIconDependents]): раньше они ушли с заместителем.
     */
    @JvmStatic
    internal fun markIconFromServer(db: SQLiteDatabase, uuid: String) {
        val table = WalletContract.IconEntry.TABLE_NAME
        db.beginTransaction()
        try {
            val id = findIdByUuid(db, table, uuid)
            if (id != null) redirtyIconDependents(db, id)
            db.execSQL(
                "UPDATE " + table + " SET " + WalletContract.IconEntry.COLUMN_ORIGIN + " = " +
                    WalletContract.IconEntry.ORIGIN_SERVER + " WHERE uuid = ?", arrayOf<Any?>(uuid)
            )
            db.execSQL(
                "UPDATE " + table + " SET " + WalletContract.IconEntry.COLUMN_DIRTY + " = 0 WHERE uuid = ? AND " +
                    WalletContract.IconEntry.COLUMN_DIRTY + " <> 0", arrayOf<Any?>(uuid)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Своя иконка стала иконкой с сервера (принята или перенята на pull) — живые счета и валюты
     * с ней снова dirty: пока она была своей, они ушли на сервер с `icon_uuid`
     * заместителя, и на сайте и других устройствах показывали бы его, а не её. Следующий
     * [pushDirty] (в том же проходе — сразу после шага иконок) отправит её uuid.
     */
    @JvmStatic
    internal fun redirtyIconDependents(db: SQLiteDatabase, iconId: Long): Int {
        var n = 0
        for (t in arrayOf("account", "currency")) {
            val cv = ContentValues()
            cv.put("dirty", 1)
            n += db.update(t, cv, "icon_id=? AND dirty=0 AND deleted_at IS NULL", arrayOf(iconId.toString()))
        }
        return n
    }

    /**
     * Сервер не принимает свои иконки: выключить настройку, запомнить запрет и сказать об этом
     * в журнале (⚠, не «проблема» — проход остаётся успешным). Повторно включить можно, только
     * когда pull снова принесёт `capabilities.icon_upload = true`.
     */
    @JvmStatic
    internal fun disableIconUpload(ctx: Context, log: SyncLog) {
        AppSettings.setIconUploadEnabled(ctx, false)
        AppSettings.setServerIconUploadAllowed(ctx, false)
        log.healed(Texts.get(R.string.sync_icons_server_refused))
    }

    /**
     * Флаг сервера из pull (`capabilities.icon_upload`). Стал запрещающим при включённой
     * настройке — флаг выключили, пока телефон ничего не отправлял: настройка выключается.
     */
    @JvmStatic
    internal fun applyServerIconFlag(ctx: Context, allowed: Boolean, log: SyncLog) {
        if (!allowed && AppSettings.isIconUploadEnabled(ctx)) {
            disableIconUpload(ctx, log)
            return
        }
        AppSettings.setServerIconUploadAllowed(ctx, allowed)
    }

    // ── PUSH ─────────────────────────────────────────────────────────────────

    /**
     * Порции push — НЕ один HTTP-вызов на весь dirty-набор (как было раньше),
     * а последовательность вызовов `POST /sync/push` по НЕ БОЛЕЕ
     * [MAX_PUSH_BATCH_ROWS] строк каждый (см. [chunkIntoPortions]) —
     * свой `batch_id`, своя Postgres-транзакция на КАЖДУЮ порцию (сервер
     * про порции ничего не знает, это чисто клиентское разбиение одного и
     * того же протокола, см. README wallet-api, "Синхронизация").
     *
     * Порции идут строго по [TABLE_ORDER] (порядок собирает
     * [collectDirtyByTable]) и отправляются ПОСЛЕДОВАТЕЛЬНО, дожидаясь
     * ответа на предыдущую перед отправкой следующей — не параллельно и не
     * заранее все разом: строка-ссылка (например, `account.icon_uuid`)
     * не может уйти раньше строки, на которую ссылается (тот же принцип, что
     * и `kApplyOrder` внутри одного батча на сервере, см. sync_repo.cpp),
     * а тут это порядок между ОТДЕЛЬНЫМИ HTTP-вызовами, а не строк внутри
     * одного. Сетевое исключение прерывает весь метод — дальнейшие порции
     * этого вызова не пробуем (нечем: соединения с сервером больше нет).
     * Уже успешно отправленные порции при этом остаются применёнными (dirty
     * у них уже сброшен) — повторный раунд/цикл синка соберёт заново только
     * то, что не прошло.
     *
     * `log` получает запись "Отправлено" ТОЛЬКО за уже подтверждённую
     * (HTTP 200) порцию (см. [logPortionSent]) — раньше эти записи
     * добавлялись во время СБОРА строк (до самого HTTP-вызова, см. историю
     * [collectDirtyByTable]), и провал push всё равно оставлял в
     * журнале "Отправлено" для строк, которые на самом деле не долетели.
     * Любой отказ, кроме самовосстанавливающихся конфликта имени счёта и
     * `missing_reference` со связанным table/uuid (см. ниже,
     * [parseMissingReference]), пробрасывается
     * [PushRejectedException] — раньше тихо проглатывался (только
     * `Log.w` в системный лог) и [SyncRunner.runOnce] возвращал
     * `Status.OK`, будто всё прошло (реальный инцидент 2026-09-09 —
     * см. README wallet-api, "system_kind/kind").
     *
     * ### Цикл самолечения (инцидент 2026-09-14)
     * Раньше самовосстановление одной порции (конфликт имени счёта ИЛИ
     * `missing_reference`) заканчивалось `return` из ВСЕГО
     * метода — расчёт был "почини одну ссылку, следующий клик «Синхронизировать»
     * повторит попытку". На практике это означало, что при НЕСКОЛЬКИХ
     * протухших ссылках сразу (например, локальная база считает десятки
     * строк уже отправленными `dirty=0`, а сервер, на который они
     * ссылаются, успел потерять данные — типичный случай на dev-стенде после
     * пересборки контейнера БД с чистым volume) пользователю приходилось
     * вручную жать «Синхронизировать» заново на КАЖДУЮ ссылку по отдельности,
     * а диалог каждый раз показывал пустой журнал "Изменений нет", будто
     * ничего не произошло.
     *
     * Теперь [pushDirty] — внешний цикл ПО РАУНДАМ: раунд заново
     * собирает `byTable` (см. [collectDirtyByTable]) и шлёт
     * порции, пока не наткнётся либо на конец (всё отправлено — выходим),
     * либо на самовосстанавливающийся отказ. В последнем случае чиним ОДНУ
     * ссылку (`resolveAccountConflicts`/`redirty`) и СРАЗУ ЖЕ,
     * без ожидания следующего клика, начинаем СЛЕДУЮЩИЙ раунд — заново
     * собираем `byTable` (уже без только что вылеченной строки —
     * `redirty()` требует `dirty<>1`, второй раз её не тронет,
     * так что раундов не может быть больше, чем протухших строк) и пробуем
     * снова, теперь и порции ПОСЛЕ бывшей сбойной (например, `operation`)
     * тоже в деле. [MAX_SELF_HEAL_ROUNDS] — защитный потолок на случай
     * ещё не виданного бага (см. её javadoc), не ожидаемый предел.
     * `log.healed(...)` — чтобы каждая починка была ВИДНА в диалоге
     * (см. [SyncLog.healed]), а не тонула в logcat как раньше: иначе
     * синк с пустым журналом выглядит как "ничего не произошло", хотя на
     * самом деле часть данных застряла и чинится раунд за раундом прямо
     * сейчас, одним нажатием кнопки.
     */
    @Throws(IOException::class, JSONException::class)
    internal fun pushDirty(db: SQLiteDatabase, api: SyncApiClient, log: SyncLog) {
        // Строки, отложенные из-за своей иконки без заместителя (см. PushIconRefs,
        // deferDependents) — пересобираются каждым раундом заново, в журнал
        // попадает состояние последнего. finally — чтобы пользователь узнал о
        // них и тогда, когда push упал по другой причине.
        val problems = ArrayList<String>()
        try {
            pushDirtyRounds(db, api, log, problems)
        } finally {
            for (p in problems) log.problem(p)
        }
    }

    @Throws(IOException::class, JSONException::class)
    private fun pushDirtyRounds(db: SQLiteDatabase, api: SyncApiClient, log: SyncLog, problems: MutableList<String>) {
        var round = 1
        while (true) {
            problems.clear()
            val byTable = collectDirtyByTable(db, problems)
            if (byTable.isEmpty()) return // нечего отправлять — либо с самого начала, либо всё уже вылечено

            var healed = false
            for (portion in chunkIntoPortions(byTable)) {
                val batchId = UUID.randomUUID().toString()
                val result = api.push(batchId, portion)

                if (result.status == 200) {
                    clearDirty(db, portion)
                    logPortionSent(portion, log)
                    continue // следующая порция этого же раунда
                }

                val conflicts = if (result.status == 409) result.body.optJSONArray("conflicts") else null
                if (conflicts != null) {
                    // Конфликт имени счёта — ожидаемый, самовосстанавливающийся
                    // случай (см. javadoc класса и README wallet-api):
                    // переклеиваем uuid и НЕ считаем это ошибкой синка целиком.
                    // ЭТА порция не применилась (dirty остаётся как есть), но
                    // вместо return из всего метода — break из порций этого
                    // раунда и НОВЫЙ раунд с нуля (см. javadoc метода, "Цикл
                    // самолечения"): переклеенная строка больше не конфликтна,
                    // остальной dirty-набор пересобирается и уходит прямо
                    // сейчас, а не ждёт следующего клика «Синхронизировать».
                    resolveAccountConflicts(db, conflicts)
                    val detail = Texts.get(R.string.sync_healed_name_conflict)
                    Log.w(TAG, "push: account name conflict, relinked (round $round): ${result.body}")
                    log.healed(detail)
                    healed = true
                    break
                }

                // missing_reference со связанным table/uuid (см. sync_repo.cpp,
                // push_batch — реальный инцидент 2026-09-13: currency_pair ушла
                // раньше своей currency, потому что та на клиенте была НЕ dirty)
                // — сервер называет строку, которой ему не хватило; если она
                // реально есть в ЭТОЙ локальной базе, просто была помечена уже
                // отправленной (dirty=0) без факта успешного пуша именно на
                // этот сервер — помечаем dirty=1 заново и не считаем это
                // отказом синка целиком, тем же путём, что и конфликт имени
                // счёта выше: break + новый раунд, не return (см. javadoc
                // метода, "Цикл самолечения" — реальный инцидент 2026-09-14:
                // dev-стенд с пересозданной БД, десятки строк считались уже
                // отправленными, хотя сервер их никогда не видел). redirty()
                // == 0 — строки с таким uuid здесь нет вовсе (сервер прислал
                // что-то, чего в этой базе никогда не было) — самим нечем
                // помочь, падаем как раньше.
                // debts (design D3/D12): удаление контрагента, у которого на сервере остался
                // живой заём, — батч откачен. Контрагент возвращается (deleted_at = NULL) и
                // остаётся dirty, остальные строки порции и так dirty — новый раунд.
                val inUse = parseCounterpartyInUse(result.status, result.body)
                if (inUse != null && reviveCounterparty(db, inUse) > 0) {
                    Log.w(TAG, "push: counterparty_in_use ($inUse), restored (round $round): ${result.body}")
                    log.healed(Texts.get(R.string.sync_healed_counterparty_in_use))
                    healed = true
                    break
                }

                val missing = parseMissingReference(result.status, result.body)
                if (missing != null && redirty(db, missing.table, missing.uuid) > 0) {
                    val detail = Texts.get(R.string.sync_healed_missing_reference, missing.table + "/" + missing.uuid)
                    Log.w(
                        TAG, "push: missing_reference (" + missing.table + "/" + missing.uuid +
                            "), dirty again (round " + round + "), retrying now: " + result.body
                    )
                    log.healed(detail)
                    healed = true
                    break
                }

                // Любой другой отказ — missing_reference без table/uuid (или без
                // соответствующей локальной строки)/duplicate_key (409),
                // invalid_row (400 — например, версия клиента новее/старше
                // протокола сервера), 500 и т.п. — не самовосстанавливающийся:
                // пользователь должен узнать, что часть изменений не долетела,
                // а не увидеть "Синхронизация завершена" с журналом, будто всё
                // отправлено (см. javadoc метода выше).
                throw PushRejectedException(result.status, result.body)
            }

            if (!healed) return // все порции этого раунда прошли — отправлять больше нечего

            if (round >= MAX_SELF_HEAL_ROUNDS) {
                // Не должно происходить (см. javadoc MAX_SELF_HEAL_ROUNDS) —
                // но зависнуть в цикле навсегда хуже, чем показать пользователю
                // честную ошибку. RuntimeException (не PushRejectedException,
                // у которой есть настоящий httpStatus/body с сервера — здесь
                // отказа СЕРВЕРА нет, это клиент сам себя останавливает) —
                // SyncRunner.runOnce ловит её тем же "максимум дебаг-информации"
                // путём, что и любой прочий неожиданный сбой.
                throw IllegalStateException(
                    "push: self-healing did not converge in " + MAX_SELF_HEAL_ROUNDS +
                        " rounds — looks like a conflict loop, stopping"
                )
            }
            round++
        }
    }

    /**
     * uuid контрагента из тела `409 counterparty_in_use` (`{error, table, uuid}`,
     * wallet-api `CounterpartyInUseError`) или `null` — другой отказ. Только JSON,
     * без БД — для юнит-теста.
     */
    @JvmStatic
    internal fun parseCounterpartyInUse(httpStatus: Int, body: JSONObject?): String? {
        if (httpStatus != 409 || body == null) return null
        if ("counterparty_in_use" != body.optString("error", null)) return null
        if ("counterparty" != body.optString("table", null)) return null
        val uuid = body.optString("uuid", null)
        return if (uuid == null || uuid.isEmpty()) null else uuid
    }

    /**
     * Возвращает удалённого контрагента: `deleted_at = NULL`, dirty поднимает триггер.
     * 0 — такой строки здесь нет или она уже живая: самим нечем помочь.
     */
    @JvmStatic
    internal fun reviveCounterparty(db: SQLiteDatabase, uuid: String): Int {
        val local = localUuid(db, "counterparty", uuid) ?: return 0
        val cv = ContentValues()
        cv.putNull("deleted_at")
        cv.put("dirty", 1)
        return db.update("counterparty", cv, "uuid=? AND deleted_at IS NOT NULL", arrayOf(local))
    }

    /**
     * Однократная повторная отправка займов и контрагентов после шага БД v91 (openspec debts,
     * spec sync-run, design D2): все живые строки `counterparty`/`account_debt`
     * помечаются dirty, флаг снимается сразу — дальше их держит сам dirty, и упавший push
     * отправит их следующим синком.
     */
    @JvmStatic
    internal fun redirtyDebtsIfPending(db: SQLiteDatabase, ctx: Context, log: SyncLog) {
        if (!AppSettings.isDebtsRedirtyPending(ctx)) return
        db.execSQL("UPDATE counterparty SET dirty = 1 WHERE deleted_at IS NULL AND dirty <> 1")
        db.execSQL("UPDATE account_debt SET dirty = 1 WHERE deleted_at IS NULL AND dirty <> 1")
        AppSettings.setDebtsRedirtyPending(ctx, false)
        if (hasRows(db, "SELECT 1 FROM counterparty WHERE deleted_at IS NULL LIMIT 1")) {
            log.healed(Texts.get(R.string.sync_healed_debts_redirty))
        }
    }

    private fun hasRows(db: SQLiteDatabase, sql: String): Boolean {
        val c = db.rawQuery(sql, null)
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /** Раскрывает "как есть" в [SyncRunner.runOnce] — см. её javadoc. */
    internal class PushRejectedException(val httpStatus: Int, body: JSONObject?) :
        RuntimeException("HTTP $httpStatus: $body")

    /** См. [parseMissingReference]. */
    internal class MissingReference(val table: String, val uuid: String)

    /**
     * Чистая разборка тела 409-ответа `missing_reference` (см.
     * sync_repo.cpp, push_batch) — без обращения к БД/сети, только JSON, ради
     * юнит-тестируемости (см. SyncEngineMissingReferenceTest). `null`,
     * если тело не такой формы и самовосстановиться нечем:
     *  - статус не 409 или `error` не `"missing_reference"` —
     *    это вообще другой отказ (см. вызывающий код, `pushDirty`);
     *  - `table`/`uuid` отсутствуют — сервер не смог разобрать
     *    свою же ошибку (например, составной внешний ключ, см.
     *    sync_repo.cpp, `parse_missing_reference`) — старое поведение,
     *    без изменений, до этой правки сервер вообще не присылал эти поля;
     *  - `table` — не одна из [TABLE_ORDER] — сервер (или
     *    кто угодно между ним и клиентом) прислал имя, для которого клиент
     *    НЕ должен выполнять `UPDATE <имя> SET dirty=1` — тут нет
     *    доверенного соединения с базой данных сервера, а есть только текст
     *    HTTP-ответа, который нельзя подставлять в SQL не проверив.
     */
    @JvmStatic
    internal fun parseMissingReference(httpStatus: Int, body: JSONObject?): MissingReference? {
        if (httpStatus != 409 || body == null) return null
        if ("missing_reference" != body.optString("error", null)) return null
        val table = body.optString("table", null)
        val uuid = body.optString("uuid", null)
        if (table == null || uuid == null || uuid.isEmpty()) return null
        for (known in TABLE_ORDER) {
            if (known == table) return MissingReference(table, uuid)
        }
        return null
    }

    /**
     * Возвращает эту одну строку в очередь push — `table` уже сверена
     * со [TABLE_ORDER] в [parseMissingReference], так что имя
     * таблицы здесь безопасно подставлять напрямую. `AND dirty<>1` —
     * не обязателен для корректности (WHERE по uuid и так задевает не более
     * одной строки), но держит поведение симметричным `createDirtyTrigger`
     * в WalletDbHelper: UPDATE, который реально ничего не меняет, не должен
     * молча идти в лог как "нашли и починили". Возвращает число задетых
     * строк — 0 означает "такого uuid в этой таблице нет вообще", тогда
     * вызывающий код не считает случай самовосстановившимся.
     */
    private fun redirty(db: SQLiteDatabase, table: String, uuid: String): Int {
        val cv = ContentValues()
        cv.put("dirty", 1)
        val local = localUuid(db, table, uuid) ?: return 0
        return db.update(table, cv, "uuid=? AND dirty<>1", arrayOf(local))
    }

    /**
     * Записывает в `log` "Отправлено"/"Отправлено (удаление)" по
     * КАЖДОЙ строке уже подтверждённой (HTTP 200) порции — вызывать только
     * после успеха, не во время сборки строк (см. javadoc
     * [pushDirty], почему). Тип строки — по имени таблицы (тот же
     * человекочитаемый набор, что раньше проставляли лямбды
     * [collectDirtyByTable]); `account_asset` — свой тип: пометка актива
     * идёт без строки `account` (design п. 10 capital-assets); `account_operating`/
     * `operation_link`/`currency_conversion` сознательно не
     * упомянуты — слишком технические, всегда сопровождают сиблинга (та же
     * причина, что и раньше).
     */
    @Throws(JSONException::class)
    private fun logPortionSent(portion: JSONObject, log: SyncLog) {
        val displayType = HashMap<String, String>()
        displayType["currency"] = Texts.get(R.string.sync_type_currency)
        displayType["currency_pair"] = Texts.get(R.string.sync_type_pair)
        displayType["currency_pair_rate"] = Texts.get(R.string.sync_type_rate)
        displayType["account"] = Texts.get(R.string.sync_type_account)
        displayType["account_asset"] = Texts.get(R.string.sync_type_asset)
        displayType["counterparty"] = Texts.get(R.string.sync_type_counterparty)
        displayType["account_debt"] = Texts.get(R.string.sync_type_debt)
        displayType["operation"] = Texts.get(R.string.sync_type_operation)

        val tables = portion.keys()
        while (tables.hasNext()) {
            val table = tables.next()
            val type = displayType[table] ?: continue
            val rows = portion.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val deleted = rows.getJSONObject(i).optBoolean("deleted", false)
                log.push(type, Texts.get(if (deleted) R.string.sync_action_sent_delete else R.string.sync_action_sent))
            }
        }
    }

    @Throws(JSONException::class)
    private fun collect(db: SQLiteDatabase, sql: String, builder: (Cursor) -> JSONObject): JSONArray {
        val rows = JSONArray()
        val c = db.rawQuery(sql, null)
        try {
            while (c.moveToNext()) rows.put(builder(c))
        } finally {
            c.close()
        }
        return rows
    }

    /**
     * Dirty-строки `currency` для push. `show_in_equivalent` шлётся всегда
     * (openspec currency-ui-tweaks, design D6): сервер без колонки строит SQL по своему
     * списку колонок и лишний ключ игнорирует. `icon_uuid` своей иконки — заместитель
     * ([PushIconRefs]).
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun collectDirtyCurrencies(db: SQLiteDatabase, icons: PushIconRefs): JSONArray =
        collect(
            db,
            "SELECT uuid, name, iso_code, icon_id, symbol, precision, deleted_at, show_in_equivalent " +
                "FROM currency WHERE dirty=1"
        ) { c ->
            val row = JSONObject()
            row.put("uuid", c.getString(0))
            row.put("name", c.getString(1))
            row.put("iso_code", c.getString(2))
            row.put("icon_uuid", icons.uuidFor(c.getLong(3)))
            row.put("symbol", c.getString(4))
            row.put("precision", c.getInt(5))
            row.put("deleted", !c.isNull(6))
            row.put("show_in_equivalent", c.getInt(7) != 0)
            row
        }

    private fun putOrdered(byTable: MutableMap<String, JSONArray>, table: String, rows: JSONArray) {
        if (rows.length() > 0) byTable[table] = rows
    }

    /**
     * Режет `byTable` (уже в порядке [TABLE_ORDER] — так его
     * строит [collectDirtyByTable], `LinkedHashMap` сохраняет
     * порядок вставки) на порции по НЕ БОЛЕЕ [MAX_PUSH_BATCH_ROWS]
     * строк суммарно. Таблица крупнее одной порции режется сама по себе на
     * несколько подряд идущих порций; соседние МЕЛКИЕ таблицы, наоборот,
     * могут ужиться в одной порции — граница режется по факту, а не заранее
     * "одна порция — одна таблица". Порядок таблиц между порциями не
     * перемешивается: следующая таблица начинает заполняться (в текущей ИЛИ
     * в новой порции, если места не осталось) только когда ВСЕ строки
     * предыдущей уже распределены — см. [pushDirty], почему порядок
     * между порциями важен так же, как порядок таблиц внутри одной.
     */
    @Throws(JSONException::class)
    private fun chunkIntoPortions(byTable: Map<String, JSONArray>): List<JSONObject> {
        val portions = ArrayList<JSONObject>()
        var current = JSONObject()
        var currentRows = 0

        for ((table, rows) in byTable) {
            var offset = 0
            while (offset < rows.length()) {
                if (currentRows >= MAX_PUSH_BATCH_ROWS) {
                    portions.add(current)
                    current = JSONObject()
                    currentRows = 0
                }
                val take = minOf(MAX_PUSH_BATCH_ROWS - currentRows, rows.length() - offset)
                current.put(table, sliceArray(rows, offset, take))
                currentRows += take
                offset += take
            }
        }
        if (currentRows > 0) portions.add(current)
        return portions
    }

    @Throws(JSONException::class)
    private fun sliceArray(rows: JSONArray, offset: Int, count: Int): JSONArray {
        val slice = JSONArray()
        for (i in 0 until count) slice.put(rows.get(offset + i))
        return slice
    }

    /**
     * Собирает все dirty-строки по 8 синхронизируемым таблицам (`icon`
     * в push не уходит, см. javadoc класса, "Иконки") в
     * порядке [TABLE_ORDER] — `LinkedHashMap` сохраняет именно
     * этот порядок вставки, на нём строится нарезка на порции (см.
     * [chunkIntoPortions]). Раньше это была одна `changes` на
     * один push-батч (README wallet-api, "Вся changes — одна транзакция") —
     * теперь так же одна транзакция, но НА КАЖДУЮ ПОРЦИЮ, не на весь
     * dirty-набор разом (см. [pushDirty]).
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun collectDirtyByTable(db: SQLiteDatabase, problems: MutableList<String>): MutableMap<String, JSONArray> {
        val byTable = LinkedHashMap<String, JSONArray>()

        val deferred = HashSet<String>()
        val icons = PushIconRefs(db, deferred, problems)

        putOrdered(byTable, "currency", collectDirtyCurrencies(db, icons))

        putOrdered(
            byTable, "currency_pair", collect(
                db,
                "SELECT uuid, symbol, base_currency_id, quote_currency_id, precision, deleted_at " +
                    "FROM currency_pair WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("symbol", c.getString(1))
                row.put("base_currency_uuid", uuidById(db, "currency", c.getLong(2)))
                row.put("quote_currency_uuid", uuidById(db, "currency", c.getLong(3)))
                row.put("precision", c.getInt(4))
                row.put("deleted", !c.isNull(5))
                row
            }
        )

        putOrdered(
            byTable, "currency_pair_rate", collect(
                db,
                "SELECT uuid, currency_pair_id, date, CAST(rate AS TEXT), deleted_at FROM currency_pair_rate WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("currency_pair_uuid", uuidById(db, "currency_pair", c.getLong(1)))
                // Локальная колонка — DATETIME (первые 10 символов "yyyy-MM-dd",
                // время дальше не пишется никаким актуальным кодом — см.
                // DateUtils.DateToDateStr/WalletFillData.InsertCurrencyPairRate),
                // но на старых строках (до этой правки) время могло сохраниться.
                // Сервер хранит "date" без времени вообще (см.
                // wallet-api/schema/table/currency_pair_rate.sql) — обрезаем здесь
                // тоже, а не только полагаемся на неявный CAST на стороне
                // Postgres: два локальных дубля одного дня с разным временем
                // иначе бьются об uq_currency_pair_rate_ledger при push.
                val rawDate = c.getString(2)
                row.put("date", if (rawDate.length > 10) rawDate.substring(0, 10) else rawDate)
                row.put("rate", c.getString(3)) // текстом и через CAST — тот же довод, что у amount ниже
                row.put("deleted", !c.isNull(4))
                row
            }
        )

        putOrdered(
            byTable, "currency_conversion", collect(
                db,
                "SELECT uuid, from_currency_id, to_currency_id, num, currency_pair_id, is_reverse, deleted_at " +
                    "FROM currency_conversion WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("from_currency_uuid", uuidById(db, "currency", c.getLong(1)))
                row.put("to_currency_uuid", uuidById(db, "currency", c.getLong(2)))
                row.put("num", c.getInt(3))
                row.put("currency_pair_uuid", uuidById(db, "currency_pair", c.getLong(4)))
                row.put("is_reverse", c.getInt(5) != 0)
                row.put("deleted", !c.isNull(6))
                row
            }
        )

        putOrdered(
            byTable, "account", collect(
                db,
                // system_kind/kind — v75/v76 (см. WalletContract.AccountEntry) — добавлены в серверный
                // TableSpec/схему коммитом "wallet share step1" вместе с этим SELECT'ом; сервер читает
                // каждую колонку spec.columns через json::at() без дефолта (wallet-api/src/
                // sync_repo.cpp), поэтому отсутствие ключа здесь — не "0 по умолчанию", а падение
                // "key 'system_kind' not found" на push.
                // category_kind — v92 (openspec category-kind-income-expense): wallet-api с миграцией
                // 000041 требует колонку в каждой строке account, без неё весь батч — 400 invalid_row.
                "SELECT uuid, name, comment, icon_id, is_archived, system_kind, kind, deleted_at, category_kind FROM account WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("name", c.getString(1))
                row.put("comment", c.getString(2))
                row.put("icon_uuid", icons.uuidFor(c.getLong(3)))
                row.put("is_archived", c.getInt(4) != 0)
                row.put("system_kind", c.getInt(5))
                row.put("kind", c.getInt(6))
                row.put("category_kind", c.getInt(8))
                row.put("deleted", !c.isNull(7))
                row
            }
        )

        putOrdered(
            byTable, "account_operating", collect(
                db,
                "SELECT uuid, currency_id, deleted_at FROM account_operating WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                // uuid — тот же, что у account того же счёта (инвариант,
                // см. WalletDbHelper.backfillAccountOperatingUuid).
                row.put("uuid", c.getString(0))
                row.put("currency_uuid", uuidById(db, "currency", c.getLong(1)))
                row.put("deleted", !c.isNull(2))
                row
            }
        )

        putOrdered(
            byTable, "account_asset", collect(
                db,
                // capital-assets: ключи — по TableSpec wallet-api (asset_type, include_in_net_worth,
                // comment), сервер читает каждую колонку без дефолта. uuid — тот же, что у счёта.
                "SELECT uuid, asset_type, include_in_net_worth, comment, deleted_at FROM account_asset WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("asset_type", c.getInt(1))
                row.put("include_in_net_worth", c.getInt(2) != 0)
                row.put("comment", c.getString(3))
                row.put("deleted", !c.isNull(4))
                row
            }
        )

        putOrdered(
            byTable, "counterparty", collect(
                db,
                // debts (design D3): ключи — CounterpartyPushRow wallet-api. icon_uuid уходит
                // всегда, null — «без иконки»: отсутствие ключа сервер понимает как «оставить
                // прежнее», и снятая иконка не доехала бы.
                "SELECT uuid, name, comment, icon_uuid, is_archived, deleted_at FROM counterparty WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("name", c.getString(1))
                row.put("comment", c.getString(2))
                row.put("icon_uuid", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                row.put("is_archived", c.getInt(4) != 0)
                row.put("deleted", !c.isNull(5))
                row
            }
        )

        putOrdered(
            byTable, "account_debt", collect(
                db,
                // debts (design D3): AccountDebtPushRow. due_date — всегда, null снимает срок
                // (по той же причине, что icon_uuid выше). uuid — тот же, что у счёта.
                "SELECT uuid, counterparty_uuid, comment, due_date, deleted_at FROM account_debt WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("counterparty_uuid", c.getString(1))
                row.put("comment", c.getString(2))
                row.put("due_date", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                row.put("deleted", !c.isNull(4))
                row
            }
        )

        putOrdered(
            byTable, "operation", collect(
                db,
                "SELECT uuid, date, acc_fr_id, acc_to_id, CAST(amount AS TEXT), comment, deleted_at FROM operation WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("date", c.getString(1))
                row.put("acc_fr_uuid", uuidById(db, "account", c.getLong(2)))
                row.put("acc_to_uuid", uuidById(db, "account", c.getLong(3)))
                // Строкой, не getDouble (см. README wallet-api/schema, "Деньги —
                // numeric" — суммы протокол несёт строками). CAST в самом SELECT
                // обязателен: getString() по REAL-колонке форматирует Android
                // (CursorWindow, "%g") — 6 значащих цифр, 73791.98 уходил как
                // "73792". CAST AS TEXT форматирует SQLite — 15 цифр, без потерь.
                row.put("amount", c.getString(4))
                row.put("comment", c.getString(5))
                row.put("deleted", !c.isNull(6))
                row
            }
        )

        putOrdered(
            byTable, "operation_link", collect(
                db,
                // type (v77, OperationItem.LinkType) — тот же класс пропуска, что был у account.system_kind/
                // kind (см. комментарий там): сервер требует ключ через json::at() без дефолта, DEFAULT 0
                // в самой колонке для push/pull JSON значения не имеет.
                "SELECT uuid, main_id, link_id, type, deleted_at FROM operation_link WHERE dirty=1"
            ) { c ->
                val row = JSONObject()
                row.put("uuid", c.getString(0))
                row.put("main_uuid", uuidById(db, "operation", c.getLong(1)))
                row.put("link_uuid", uuidById(db, "operation", c.getLong(2)))
                row.put("type", c.getInt(3))
                row.put("deleted", !c.isNull(4))
                row
            }
        )

        if (deferred.isNotEmpty()) deferDependents(byTable, deferred, problems)
        return byTable
    }

    /**
     * Убирает из `byTable` строки, которые ссылаются (через любое поле
     * `*_uuid`) на uuid из `deferred` — свою иконку, для которой
     * нет заместителя с сервера (см. [PushIconRefs]), — и, по
     * цепочке, всё, что ссылается уже на них: счёт с такой иконкой → его
     * `account_operating` (тот же uuid, что у счёта) → операции по
     * нему → их `operation_link`. Иначе сервер ответил бы
     * `missing_reference` на иконку, которой у него нет и не будет, — и весь
     * push упал бы. Один проход по [TABLE_ORDER]
     * достаточен: ссылка всегда указывает на таблицу РАНЬШЕ по порядку.
     * Отложенные строки остаются dirty и уйдут следующим синком (после pull
     * иконок с сервера заместитель обычно находится).
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun deferDependents(
        byTable: MutableMap<String, JSONArray>, deferred: MutableSet<String>,
        problems: MutableList<String>
    ) {
        var skipped = 0
        val tables = byTable.entries.iterator()
        while (tables.hasNext()) {
            val entry = tables.next()
            val kept = JSONArray()
            val rows = entry.value
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (referencesDeferred(row, deferred)) {
                    deferred.add(row.getString("uuid"))
                    skipped++
                } else {
                    kept.put(row)
                }
            }
            if (kept.length() == 0) tables.remove()
            else entry.setValue(kept)
        }
        if (skipped > 0) {
            problems.add(Texts.get(R.string.sync_problem_deferred_icons, skipped))
        }
    }

    private fun referencesDeferred(row: JSONObject, deferred: Set<String>): Boolean {
        val keys = row.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "uuid" && !key.endsWith("_uuid")) continue
            val value = row.optString(key, null)
            if (value != null && deferred.contains(value)) return true
        }
        return false
    }

    /**
     * `icon_id` → `icon_uuid` для push `currency`/`account`
     * (openspec server-managed-icons, design п. 3). Иконка с сервера — её uuid. Своя —
     * uuid заместителя ([IconSubstitutes], индекс строится один раз за раунд и
     * только если в раунде есть своя иконка). Нет заместителя — uuid своей иконки
     * кладётся в `deferred`: [deferDependents] уберёт строку и всё, что на
     * неё ссылается, а в журнал — «нет иконки-заместителя для «<имя>»: строка отложена»
     * (один раз на иконку).
     */
    internal class PushIconRefs(
        private val db: SQLiteDatabase,
        private val deferred: MutableSet<String>,
        private val problems: MutableList<String>
    ) {
        private val resolved = HashMap<Long, String?>()
        private var substitutes: IconSubstitutes? = null

        fun uuidFor(iconId: Long): String? {
            if (resolved.containsKey(iconId)) return resolved[iconId]
            val uuid = resolve(iconId)
            resolved[iconId] = uuid
            return uuid
        }

        private fun resolve(iconId: Long): String? {
            val uuid: String
            val name: String?
            val image: ByteArray?
            val c = db.rawQuery(
                "SELECT uuid, origin, name, image FROM icon WHERE _id=? LIMIT 1",
                arrayOf(iconId.toString())
            )
            try {
                if (!c.moveToFirst()) return null // как uuidById: ссылка в никуда — пусть разбирается сервер
                if (c.getInt(1) != WalletContract.IconEntry.ORIGIN_LOCAL) return c.getString(0)
                uuid = c.getString(0)
                name = c.getString(2)
                image = c.getBlob(3)
            } finally {
                c.close()
            }
            val subs = substitutes ?: IconSubstitutes.load(db).also { substitutes = it }
            val substitute = subs.substituteFor(name, image)
            if (substitute != null) return substitute
            Log.w(TAG, "push: нет иконки-заместителя для $uuid ($name) — строка отложена")
            deferred.add(uuid)
            problems.add(Texts.get(R.string.sync_problem_no_substitute, name))
            return uuid
        }
    }

    /**
     * Сбрасывает `dirty` только у строк, реально вошедших в успешно
     * применённый батч (по их `uuid` из уже отправленного
     * `changes`) — не бланковым `WHERE dirty=1`: пока ответ шёл обратно,
     * пользователь мог успеть отредактировать что-то ещё, и такая строка
     * обязана остаться dirty для следующего цикла.
     */
    @Throws(JSONException::class)
    private fun clearDirty(db: SQLiteDatabase, changes: JSONObject) {
        db.beginTransaction()
        try {
            val tables = changes.keys()
            while (tables.hasNext()) {
                val table = tables.next()
                val rows = changes.getJSONArray(table)
                for (i in 0 until rows.length()) {
                    val uuid = rows.getJSONObject(i).getString("uuid")
                    val cv = ContentValues()
                    cv.put("dirty", 0)
                    db.update(table, cv, "uuid=?", arrayOf(uuid))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 409 duplicate_name (см. README wallet-api, "Конфликт имени счёта") —
     * переклеивает identity локального счёта на выжившую строку.
     * Единственный сценарий переписывания локальных ссылок
     * (org/docs/TODO_mobile_sync.md, Фаза 3, п. 5): локальный `_id` и
     * всё, что на него ссылается (`operation.acc_fr_id/acc_to_id` и
     * т.п.), не трогаем — они и так указывают на верную строку, меняется
     * только то, каким `uuid` она назовётся при следующей отправке.
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun resolveAccountConflicts(db: SQLiteDatabase, conflicts: JSONArray) {
        for (i in 0 until conflicts.length()) {
            val conflict = conflicts.getJSONObject(i)
            if ("duplicate_name" != conflict.optString("reason")) {
                continue // сегодня единственная осмысленная причина, см. sync_repo.cpp
            }
            if ("counterparty" == conflict.optString("table")) {
                resolveCounterpartyConflict(db, conflict)
                continue
            }
            if ("account" != conflict.optString("table")) continue
            val ourUuid = conflict.optString("uuid", null)
            val survivorUuid = conflict.optString("existing_uuid", null)
            // Защитно: старый сервер без поля "uuid" в конфликте (до фикса
            // sync_repo.hpp/Conflict) — нечем понять, какую строку переклеивать.
            if (ourUuid == null || survivorUuid == null) continue

            val cv = ContentValues()
            cv.put("uuid", survivorUuid)
            val updated = db.update("account", cv, "uuid=?", arrayOf(ourUuid))
            if (updated == 0) {
                Log.w(
                    TAG, "resolveAccountConflicts: локальный account uuid=" + ourUuid + // not UI: лог
                        " не найден (уже переклеен раньше?)" // not UI: лог
                ) // not UI: лог
                continue
            }

            // account_operating.uuid обязан остаться равным account.uuid
            // (см. WalletDbHelper.backfillAccountOperatingUuid) — переклеиваем
            // тем же ключом, пока он ещё ищется по старому uuid.
            val cvOp = ContentValues()
            cvOp.put("uuid", survivorUuid)
            db.update("account_operating", cvOp, "uuid=?", arrayOf(ourUuid))
            // account_asset.uuid — тот же инвариант (capital-assets), иначе признак актива
            // ушёл бы со старым uuid несуществующего на сервере счёта.
            db.update("account_asset", cvOp, "uuid=?", arrayOf(ourUuid))
            // account_debt.uuid — тот же инвариант (debts).
            db.update("account_debt", cvOp, "uuid=?", arrayOf(ourUuid))

            Log.i(
                TAG, "account uuid " + ourUuid + " переклеен на существующий " + survivorUuid + // not UI: лог
                    " (конфликт имени счёта)" // not UI: лог
            ) // not UI: лог
        }
    }

    /**
     * 409 duplicate_name по `counterparty` (debts, design D3): тот же контрагент заведён
     * на другом устройстве. Своя строка берёт uuid выжившего, ссылки займов
     * (`account_debt.counterparty_uuid`) и привязка к контакту переклеиваются. Если
     * выживший уже пришёл pull'ом и живёт здесь отдельной строкой, своя строка мягко
     * удаляется локально без отправки — иначе uq_counterparty_name не дал бы сменить имя.
     */
    private fun resolveCounterpartyConflict(db: SQLiteDatabase, conflict: JSONObject) {
        val ourUuid = conflict.optString("uuid", null)
        val survivorUuid = conflict.optString("existing_uuid", null)
        if (ourUuid == null || survivorUuid == null) return
        val ours = localUuid(db, "counterparty", ourUuid)
        if (ours == null) {
            Log.w(
                TAG, "resolveCounterpartyConflict: локальный counterparty uuid=" + ourUuid + // not UI: лог
                    " не найден (уже переклеен раньше?)" // not UI: лог
            ) // not UI: лог
            return
        }
        var survivorLocal = localUuid(db, "counterparty", survivorUuid)
        if (survivorLocal != null && survivorLocal != ours) {
            // Выживший уже здесь: своя строка — дубль, её некому отправлять.
            db.delete("counterparty", "uuid=?", arrayOf(ours))
        } else {
            val cv = ContentValues()
            cv.put("uuid", survivorUuid)
            db.update("counterparty", cv, "uuid=?", arrayOf(ours))
            survivorLocal = survivorUuid
        }
        val ref = ContentValues()
        ref.put("counterparty_uuid", survivorLocal)
        db.update("account_debt", ref, "counterparty_uuid=?", arrayOf(ours))
        val contact = ContentValues()
        contact.put("counterparty_uuid", survivorLocal)
        db.updateWithOnConflict(
            "counterparty_contact", contact, "counterparty_uuid=?", arrayOf(ours),
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.delete("counterparty_contact", "counterparty_uuid=?", arrayOf(ours))
        Log.i(
            TAG, "counterparty uuid " + ourUuid + " переклеен на существующий " + survivorUuid + // not UI: лог
                " (конфликт имени контрагента)" // not UI: лог
        ) // not UI: лог
    }

    // ── PULL ─────────────────────────────────────────────────────────────────

    /**
     * Тянет `GET /sync/pull` постранично, пока оба потока не догонят
     * сервер (см. условие выхода ниже), применяя каждую страницу отдельной
     * транзакцией — курсоры сохраняются ТОЛЬКО после применения (см. javadoc
     * `AppSettings.getLedgerSyncSeq`), обрыв посреди страницы не теряет
     * её тихо, просто повторит на следующий вызов.
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    internal fun pullAll(db: SQLiteDatabase, api: SyncApiClient, ctx: Context, log: SyncLog) {
        // Строки, пропущенные до localUuid (ссылка на счёт с uuid без дефисов),
        // уже позади курсора — один раз тянем оба потока заново. Upsert по uuid
        // идемпотентен, а push уже прошёл, так что локальные правки не теряются.
        // То же для строк, чей родитель пришёл на более поздней странице (см.
        // PullDeferredRows): до неё они терялись — например, account_operating
        // кошелька, чью валюту недавно правили, и кошелёк оставался без валюты.
        // И для пар кошелька, потерянных на конфликте символа с общей парой
        // (openspec sync-pair-symbol-adopt).
        if (!AppSettings.isUuidFormatResyncDone(ctx) || !AppSettings.isDeferredRowsResyncDone(ctx) ||
            !AppSettings.isPairSymbolResyncDone(ctx)
        ) {
            startFullReload(db, ctx)
            AppSettings.setUuidFormatResyncDone(ctx)
            AppSettings.setDeferredRowsResyncDone(ctx)
            AppSettings.setPairSymbolResyncDone(ctx)
            log.healed(Texts.get(R.string.sync_healed_resync))
        }
        // Однократный перечит ledger после шага БД v88 (capital-assets, design п. 2):
        // прежняя версия пропускала строки account_asset, а курсор уходил дальше. Только
        // ledger — reference и ledger_id не трогаем; флаг снимается после первой
        // применённой страницы, дальше обрыв продолжает с сохранённого курсора. Это не
        // загрузка с вычисткой: upsert по uuid идемпотентен, удалять нечего.
        var ledgerReread = AppSettings.isLedgerRereadPending(ctx)
        if (ledgerReread) {
            AppSettings.setLedgerSyncSeq(ctx, 0)
            log.healed(Texts.get(R.string.sync_healed_ledger_reread))
        }
        var ledgerSeq = AppSettings.getLedgerSyncSeq(ctx)
        var referenceSeq = AppSettings.getReferenceSyncSeq(ctx)
        // Поток с нуля при уже синкнутых строках (вход в другой аккаунт,
        // resetSyncCursors) — тоже загрузка с вычисткой (purge-synced-tombstones,
        // design п. 4): иначе строки, которых на сервере больше нет, остались бы.
        // Оба курсора с нуля — локально не различить, какой поток принёс строку.
        if (!ledgerReread && !AppSettings.isFullReloadPending(ctx) && (ledgerSeq == 0L || referenceSeq == 0L) &&
            hasSyncedRows(db)
        ) {
            startFullReload(db, ctx)
            ledgerSeq = 0
            referenceSeq = 0
        }
        var reloadPending = AppSettings.isFullReloadPending(ctx)
        // Своя иконка, перенятая на pull, помечает dirty свои счета и валюты (applyIcon) — но не
        // во время подключения к кошельку: LedgerConnection удаляет локальное dirty <> 0, а
        // помеченная строка уже есть на сервере (optional-icon-upload).
        val redirtyAdopted = !AppSettings.isLedgerConnectionPending(ctx)
        var caughtUp = false
        if (ledgerSeq == 0L) log.fullReload()
        val deferred = PullDeferredRows.load(ctx)
        val touched = HashSet<String>()
        // Сброс от сервера за проход — не больше одного: после него оба курсора
        // идут с нуля, и второй сброс значит, что сервер не даёт догрузить поток
        // (ручная проверка на test 2026-11-04: граница страницы ниже purged_seq
        // -> "purged" -> снова 0 — 2170 pull за 20 минут). Проход кончается
        // ошибкой, а не крутит сеть до капа на страницы.
        var serverResetSeen = false

        // Кап на число страниц — защита от бага в курсорах/ответе сервера,
        // который иначе крутил бы цикл вечно (условие выхода ниже опирается
        // на то, что курсор реально продвигается).
        for (page in 0 until 10_000) {
            val result = api.pull(
                ledgerSeq, referenceSeq, 500,
                AppSettings.getSyncLedgerId(ctx)
            )
            applyServerIconFlag(ctx, result.iconUploadAllowed, log)

            // Леджер сменился (другой user_id при том же email, инвайт) —
            // курсор от старого леджера может оказаться и МЕНЬШЕ головы
            // нового, тогда сервер reset не поставит и часть истории молча
            // пропадёт. Страницу выбрасываем и тянем поток с нуля.
            val ledgerId = result.ledger.ledgerId
            if (ledgerId != null && ledgerId != AppSettings.getSyncLedgerId(ctx)) {
                val knownBefore = AppSettings.getSyncLedgerId(ctx) != null
                AppSettings.setSyncLedgerId(ctx, ledgerId)
                if (knownBefore && ledgerSeq != 0L) {
                    Log.w(TAG, "pull: леджер сменился на $ledgerId — курсор $ledgerSeq сброшен")
                    log.healed(Texts.get(R.string.sync_healed_ledger_changed))
                    log.fullReload()
                    // Отложенное ждало строк старого леджера — их уже не будет.
                    deferred.clear()
                    deferred.save()
                    // Строки старого кошелька, которых нет в новом, уйдут при вычистке.
                    startFullReload(db, ctx)
                    reloadPending = true
                    ledgerSeq = 0
                    referenceSeq = 0
                    continue
                }
            }
            if (result.ledger.reset || result.reference.reset) {
                // Сервер сбросил курсор: он дальше головы потока ("ahead", реальный
                // случай 2026-09-30: 146 при голове 13 — записи бота не доезжали)
                // или сервер уже вычистил удаления, которых телефон не видел
                // ("purged", purge-synced-tombstones). Строки, удалённые за это
                // время, сервер больше не пришлёт — загрузка с вычисткой. Страницу
                // выбрасываем и тянем оба потока с нуля: локально не различить,
                // какой поток принёс строку. С курсорами 0 сброса не бывает.
                val purged = "purged" == result.ledger.resetReason ||
                    "purged" == result.reference.resetReason
                Log.w(
                    TAG, "pull: сброс курсора (ledger=" + ledgerSeq + " " + result.ledger.resetReason + // not UI: лог
                        ", reference=" + referenceSeq + " " + result.reference.resetReason + ") — поток начат заново" // not UI: лог
                ) // not UI: лог
                log.healed(
                    Texts.get(
                        if (purged) R.string.sync_healed_purged
                        else R.string.sync_healed_cursor_reset
                    )
                )
                if (ledgerSeq != 0L || referenceSeq != 0L) {
                    if (serverResetSeen) {
                        throw JSONException(
                            "server reset the cursor twice in one pass (ledger=" +
                                ledgerSeq + " " + result.ledger.resetReason + ") — full reload does not progress"
                        )
                    }
                    serverResetSeen = true
                    log.fullReload()
                    startFullReload(db, ctx)
                    reloadPending = true
                    ledgerSeq = 0
                    referenceSeq = 0
                    continue
                }
            }

            val advanced = result.ledger.nextSeq != ledgerSeq || result.reference.nextSeq != referenceSeq
            if (!advanced) {
                caughtUp = true
                break // догнали сервер — оба потока пусты
            }

            touched.addAll(
                applyPage(
                    db, result.ledger.changes, result.reference.changes, api, log, deferred,
                    reloadPending, redirtyAdopted
                )
            )

            // Сначала отложенное, потом курсоры: умрём между ними — страница
            // придёт снова, и её строки просто заменят свои отложенные копии.
            deferred.save()
            ledgerSeq = result.ledger.nextSeq
            referenceSeq = result.reference.nextSeq
            AppSettings.setLedgerSyncSeq(ctx, ledgerSeq)
            AppSettings.setReferenceSyncSeq(ctx, referenceSeq)
            if (ledgerReread) {
                // Страница перечита применена и курсор сохранён — дальше обычная позиция.
                AppSettings.setLedgerRereadPending(ctx, false)
                ledgerReread = false
            }
        }
        if (ledgerReread && caughtUp) AppSettings.setLedgerRereadPending(ctx, false) // поток ledger пуст

        // Новых страниц нет, а отложенное осталось (родитель пришёл в самой
        // последней странице или был применён в прошлом синке) — ещё один проход.
        if (!deferred.isEmpty()) {
            touched.addAll(
                applyPage(
                    db, JSONObject(), JSONObject(), api, log, deferred, false,
                    redirtyAdopted
                )
            )
            deferred.save()
        }
        if (!deferred.isEmpty()) {
            // Поток догнан, а родителя так и нет — на сервере строка ссылается на
            // то, чего нам не прислали. Не теряем: повторим на следующем синке.
            Log.w(TAG, "pull: строки без родителя после полной загрузки — " + deferred.summary())
            log.problem(Texts.get(R.string.sync_problem_deferred_rows, deferred.summary()))
        }

        // Загрузка с нуля догнала оба потока — синкнутые строки, которых сервер
        // не прислал, удаляются (неотправленные, dirty <> 0, остаются). Прервались
        // раньше — ничего не трогаем, следующий проход продолжит с курсора.
        if (reloadPending && caughtUp) {
            val swept = sweepUnseen(db)
            var total = 0
            for ((key, value) in swept) {
                total += value
                if (value > 0) touched.add(key)
            }
            db.delete(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE, null, null)
            AppSettings.clearFullReloadPending(ctx)
            Log.i(TAG, "pull: загрузка с нуля завершена, удалено строк без пары на сервере: $swept")
            if (total > 0) log.healed(Texts.get(R.string.sync_healed_swept, total))
        }

        val touchedAccounts = touched.contains("account") || touched.contains("account_operating") ||
            touched.contains("account_asset") || touched.contains("account_debt") ||
            touched.contains("counterparty")
        val touchedIcons = touched.contains("icon")
        val touchedCurrencies = touched.contains("currency")
        val touchedCurrencyPairs = touched.contains("currency_pair") || touched.contains("currency_pair_rate")

        if (touchedAccounts) {
            // Singleton.accounts — кэш в памяти, сам себя не перечитывает
            // (см. Singleton.reloadAccounts) — без этого счета, пришедшие с
            // другого устройства, не появились бы в UI до перезапуска
            // приложения.
            Singleton.instance.reloadAccounts(ctx)
        }
        if (touchedIcons) {
            Singleton.instance.reloadIconList(ctx)
        }
        // currencyList — ДО currencyPairList: CurrencyPair держит ссылки на
        // объекты Currency, полученные из currencyList на момент загрузки
        // пары (см. WalletDbLoader.LoadCurrencyPairList) — если валюты
        // изменились, пары обязаны перечитаться ПОСЛЕ, иначе будут держать
        // уже устаревшие объекты Currency.
        if (touchedCurrencies) {
            Singleton.instance.reloadCurrencyList(ctx)
        }
        if (touchedCurrencies || touchedCurrencyPairs) {
            Singleton.instance.reloadCurrencyPairList(ctx)
        }
    }

    @Throws(JSONException::class)
    private fun mergeChanges(a: JSONObject, b: JSONObject): Map<String, JSONArray> {
        val merged = LinkedHashMap<String, JSONArray>()
        for (src in arrayOf(a, b)) {
            val keys = src.keys()
            while (keys.hasNext()) {
                val table = keys.next()
                val rows = src.getJSONArray(table)
                val existing = merged[table]
                if (existing == null) {
                    merged[table] = rows
                } else {
                    for (i in 0 until rows.length()) existing.put(rows.get(i))
                }
            }
        }
        return merged
    }

    /**
     * Применяет страницу вместе с отложенными строками прошлых страниц (см.
     * [PullDeferredRows]): внутри каждой таблицы сначала отложенные (они
     * старше), потом строки страницы. Отложенную строку, чью новую версию
     * принесла эта страница, не повторяем — новая пройдёт сама. Что снова не
     * применилось, остаётся в `deferred`; содержимое `deferred`
     * меняется только после коммита, так что упавшая транзакция его не портит.
     *
     * @param markSeen идёт загрузка с нуля (`AppSettings.isFullReloadPending`):
     *                 uuid всех строк страницы — применённых, отложенных, удалений —
     *                 пишутся в `sync_reload_seen` в той же транзакции, см.
     *                 [sweepUnseen]
     * @param redirtyAdopted см. [PullIconGuard.redirtyAdopted]
     * @return таблицы, где хоть одна строка применилась (для перечитывания кэшей
     *         в памяти: счета, валюты, пары)
     */
    @JvmStatic
    @Throws(JSONException::class, IOException::class)
    internal fun applyPage(
        db: SQLiteDatabase, ledgerChanges: JSONObject, referenceChanges: JSONObject,
        api: SyncApiClient?, log: SyncLog, deferred: PullDeferredRows, markSeen: Boolean = false,
        redirtyAdopted: Boolean = false
    ): Set<String> {
        val merged = mergeChanges(ledgerChanges, referenceChanges)
        val stillDeferred = PullDeferredRows.inMemory()
        val appliedTables = HashSet<String>()
        // Скачивание содержимого НОВЫХ иконок — ДО транзакции (см. javadoc
        // класса, "Иконки") — держать sqlite-транзакцию открытой на время
        // сетевого запроса означало бы блокировать доступ к БД для всего
        // остального приложения на непредсказуемое время.
        val newIconBlobs = prefetchNewIconBlobs(db, merged["icon"], api)
        db.beginTransaction()
        try {
            // Страховка поверх TABLE_ORDER, не замена ему (см. javadoc
            // класса) — переносит проверку внешних ключей на COMMIT этой
            // транзакции вместо каждого отдельного INSERT.
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            // Индекс заместителей строится лениво, при первом счёте/валюте —
            // то есть уже после иконок этой страницы (icon первая в TABLE_ORDER).
            val iconGuard = PullIconGuard(db, redirtyAdopted)
            for (table in TABLE_ORDER) {
                val rows = merged[table]
                val queue = ArrayList(deferred.rowsToRetry(table, uuidsOf(rows)))
                if (rows != null) {
                    for (i in 0 until rows.length()) queue.add(rows.getJSONObject(i))
                }
                for (row in queue) {
                    if (applyRow(db, table, row, newIconBlobs, iconGuard, log)) {
                        appliedTables.add(table)
                    } else {
                        stillDeferred.add(table, row)
                    }
                }
            }
            if (markSeen) markSeen(db, merged)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        deferred.replaceWith(stillDeferred)
        return appliedTables
    }

    @Throws(JSONException::class)
    private fun uuidsOf(rows: JSONArray?): Set<String> {
        val uuids = HashSet<String>()
        if (rows == null) return uuids
        for (i in 0 until rows.length()) {
            val uuid = rows.getJSONObject(i).optString("uuid", null)
            if (uuid != null) uuids.add(uuid)
        }
        return uuids
    }

    /**
     * Скачивает содержимое ТОЛЬКО тех иконок из страницы, которых ещё нет
     * локально (найдено по uuid) — для уже существующих байты не
     * перекачиваются (см. javadoc класса, "Иконки"). Дедуп по
     * `content_sha256`: несколько новых иконок в одной странице могут
     * делить одно и то же содержимое, скачивать его больше одного раза
     * незачем. Новую иконку, которая совпадает со своей и по имени, и по
     * картинке, тоже не качаем: [applyIcon] не вставит её, а сделает
     * своей иконкой с сервера.
     */
    @JvmStatic
    @Throws(JSONException::class, IOException::class)
    internal fun prefetchNewIconBlobs(db: SQLiteDatabase, iconRows: JSONArray?, api: SyncApiClient?): Map<String, ByteArray> {
        val cache = HashMap<String, ByteArray>()
        if (iconRows == null) return cache
        for (i in 0 until iconRows.length()) {
            val row = iconRows.getJSONObject(i)
            if (localUuid(db, "icon", row.getString("uuid")) != null) continue
            if (rowIsDeleted(row)) continue // удаление неизвестной иконки не применяется (см. applyRow)
            val sha256 = row.getString("content_sha256")
            if (cache.containsKey(sha256)) continue
            if (!rowIsDeleted(row)) {
                val own = OwnIcon.findByName(db, row.getString("name"), null)
                if (own != null && own.sha256 == sha256) continue
            }
            cache[sha256] = api!!.downloadBlob(sha256)
        }
        return cache
    }

    /**
     * @return `false` — строку надо отложить: её родитель (иконка, валюта,
     *         счёт, пара, проводка) ещё не пришёл (см. [PullDeferredRows]);
     *         `true` — строка обработана (применена или отброшена насовсем).
     */
    @Throws(JSONException::class)
    private fun applyRow(
        db: SQLiteDatabase, table: String, row: JSONObject,
        newIconBlobs: Map<String, ByteArray>, iconGuard: PullIconGuard,
        log: SyncLog
    ): Boolean {
        // Удаление строки, которой здесь нет (загрузка с нуля приносит старые
        // удаления): не создаём строку только затем, чтобы чистка её удалила, и не
        // пишем «Удалено» про то, чего пользователь не видел (purge-synced-tombstones).
        // Обработана, не отложена: родитель удалённой строки может не прийти вовсе.
        if (isSyncTable(table) && rowIsDeleted(row) && localUuid(db, table, row.optString("uuid", null)) == null) {
            return true
        }
        return when (table) {
            "currency" -> applyCurrency(db, row, log, iconGuard)
            "currency_pair" -> applyCurrencyPair(db, row, log)
            "currency_pair_rate" -> applyCurrencyPairRate(db, row, log)
            "currency_conversion" -> applyCurrencyConversion(db, row)
            "account" -> applyAccount(db, row, log, iconGuard)
            "account_operating" -> applyAccountOperating(db, row)
            "account_asset" -> applyAccountAsset(db, row, log)
            "counterparty" -> applyCounterparty(db, row, log)
            "account_debt" -> applyAccountDebt(db, row, log)
            "operation" -> applyOperation(db, row, log)
            "operation_link" -> applyOperationLink(db, row)
            "icon" -> {
                applyIcon(db, row, newIconBlobs, log, iconGuard.redirtyAdopted)
                true
            }
            // Неизвестная клиенту таблица — сервер может быть новее
            // этого релиза (тот же принцип, что у push_batch на
            // сервере для незнакомого имени) — игнор, не ошибка.
            else -> true
        }
    }

    /**
     * Для НОВОЙ (по uuid) иконки содержимое уже должно быть в
     * `newIconBlobs` (см. [prefetchNewIconBlobs]) — если его
     * там почему-то нет (сетевая ошибка при скачивании прервала бы всю
     * страницу раньше, см. [applyPage], так что это защитный, а не
     * ожидаемый путь), строка пропускается. Для уже существующей — байты не
     * перекачиваются вообще, обновляются только метаданные (см. javadoc
     * класса, "Иконки", почему смена содержимого не поддерживается).
     *
     * openspec server-managed-icons (design п. 1, 5): строка с сервера всегда
     * `origin = 0` — и новая, и уже известная по uuid (своя, когда-то
     * ушедшая на сервер, снова становится «с сервера»: сервер главный). Имя
     * занято живой своей иконкой — своя переименовывается в «<имя> (своя)» /
     * «(своя N)» в той же транзакции; если у своей та же картинка, что у
     * новой с сервера, — это одна и та же иконка (seed.json после чистой
     * установки): своя получает uuid и метаданные серверной и становится
     * «с сервера», дубль не заводится. Конфликт с иконкой «с сервера» — как
     * раньше, пропуск с логом.
     *
     * @param redirtyAdopted см. [PullIconGuard.redirtyAdopted]
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun applyIcon(
        db: SQLiteDatabase, row: JSONObject, newIconBlobs: Map<String, ByteArray>,
        log: SyncLog, redirtyAdopted: Boolean = false
    ) {
        val uuid = row.getString("uuid")
        val local = localUuid(db, "icon", uuid)
        val exists = local != null
        val deleted = rowIsDeleted(row)
        val name = row.getString("name")
        val sha256 = row.getString("content_sha256")

        val cv = ContentValues()
        cv.put("name", name)
        cv.put("icon_type", row.getInt("icon_type"))
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_SERVER)
        applySyncColumns(cv, row)

        try {
            // Tombstone имя не занимает (uq_icon_name — частичный, deleted_at IS NULL).
            val own = if (deleted) null else OwnIcon.findByName(db, name, local)
            if (!exists && own != null && own.sha256 == sha256) {
                // Та же иконка (seed.json чистой установки или своя, которую одновременно
                // отправило другое устройство леджера — optional-icon-upload): uuid своей
                // меняется на серверный. Контрагенты хранят icon_uuid строкой — переписать;
                // счета и валюты ссылаются по _id, но ушли с заместителем — снова dirty.
                val ownUuid = uuidById(db, "icon", own.id)
                if (ownUuid != null) {
                    val cp = ContentValues()
                    cp.put("icon_uuid", uuid)
                    db.update("counterparty", cp, "icon_uuid=?", arrayOf(ownUuid))
                }
                if (redirtyAdopted) redirtyIconDependents(db, own.id)
                cv.put("uuid", uuid)
                cv.put("dirty", 2) // два шага через 2 — см. updateFromServer
                db.update("icon", cv, "_id=?", arrayOf(own.id.toString()))
                val clean = ContentValues()
                clean.put("dirty", 0)
                db.update("icon", clean, "_id=?", arrayOf(own.id.toString()))
                log.pull(Texts.get(R.string.sync_type_icon), Texts.get(R.string.sync_action_icon_adopted))
                return
            }
            var image: ByteArray? = null
            if (!exists) {
                image = newIconBlobs[sha256]
                if (image == null) {
                    Log.w(TAG, "icon $uuid: содержимое не предзагружено — строка пропущена")
                    return
                }
            }
            if (own != null) {
                val renamed = OwnIcon.freeOwnName(db, name)
                val rename = ContentValues()
                rename.put("name", renamed)
                db.update("icon", rename, "_id=?", arrayOf(own.id.toString()))
                Log.i(TAG, "icon: своя «$name» переименована в «$renamed» — имя занято иконкой с сервера")
                log.pull(Texts.get(R.string.sync_type_icon), Texts.get(R.string.sync_action_icon_renamed))
            }
            if (exists) {
                updateFromServer(db, "icon", cv, local!!)
            } else {
                cv.put("image", image)
                cv.put("uuid", uuid)
                db.insertOrThrow("icon", null, cv)
            }
            log.pull(Texts.get(R.string.sync_type_icon), pullAction(deleted, !exists))
        } catch (e: SQLiteException) {
            // uq_icon_name с иконкой «с сервера» и т.п. — тот же класс проблемы,
            // что у upsertByUuid (см. javadoc класса, "Что ещё сознательно не решено").
            Log.w(TAG, "upsert icon uuid=" + uuid + " не удался: " + e.message)
        }
    }

    /** Живая своя иконка (`origin = 1`) — для конфликта имени на pull, см. [applyIcon]. */
    internal class OwnIcon private constructor(val id: Long, val sha256: String) {
        companion object {
            /** Своя живая иконка с этим именем (кроме строки `excludeUuid`), или `null`. */
            @JvmStatic
            fun findByName(db: SQLiteDatabase, name: String, excludeUuid: String?): OwnIcon? {
                val c = db.rawQuery(
                    "SELECT _id, image FROM icon WHERE name=? AND deleted_at IS NULL" +
                        " AND origin=" + WalletContract.IconEntry.ORIGIN_LOCAL +
                        " AND uuid<>? LIMIT 1",
                    arrayOf(name, excludeUuid ?: "")
                )
                try {
                    if (!c.moveToFirst()) return null
                    val image = c.getBlob(1)
                    return OwnIcon(c.getLong(0), if (image == null) "" else IconSubstitutes.sha256Hex(image))
                } finally {
                    c.close()
                }
            }

            /** «<имя> (своя)», занято — «<имя> (своя 2)», «(своя 3)», … среди живых иконок. */
            @JvmStatic
            fun freeOwnName(db: SQLiteDatabase, name: String): String =
                OwnIconNames.freeOwnName(db, name)
        }
    }

    /**
     * Своя иконка не затирается заместителем (openspec server-managed-icons,
     * design п. 4): если у счёта/валюты локально своя иконка, а с сервера
     * пришёл тот же `icon_uuid`, что телефон сам отправил бы вместо неё,
     * — `icon_id` не трогаем. Другой `icon_uuid` (иконку сменили
     * на другом устройстве или на сайте) применяется. Индекс заместителей —
     * один на страницу pull, строится при первом вызове.
     */
    internal class PullIconGuard(
        private val db: SQLiteDatabase,
        /**
         * Перенятая своя иконка (applyIcon) помечает dirty свои счета и валюты. `false` —
         * во время подключения к кошельку (`LedgerConnection` удаляет локальное
         * `dirty <> 0`) и в прямых вызовах без контекста.
         */
        val redirtyAdopted: Boolean = false
    ) {
        private var substitutes: IconSubstitutes? = null

        /** `table` — `account` или `currency`. */
        fun keepsOwnIcon(table: String, rowUuid: String, incomingIconUuid: String?): Boolean {
            val local = localUuid(db, table, rowUuid) ?: return false
            val name: String?
            val image: ByteArray?
            val c = db.rawQuery(
                "SELECT i.origin, i.name, i.image FROM " + table + " t" +
                    " JOIN icon i ON i._id = t.icon_id WHERE t.uuid=? LIMIT 1", arrayOf(local)
            )
            try {
                if (!c.moveToFirst() || c.getInt(0) != WalletContract.IconEntry.ORIGIN_LOCAL) return false
                name = c.getString(1)
                image = c.getBlob(2)
            } finally {
                c.close()
            }
            val subs = substitutes ?: IconSubstitutes.load(db).also { substitutes = it }
            return IconSubstitutes.sameUuid(subs.substituteFor(name, image), incomingIconUuid)
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    internal fun applyCurrency(
        db: SQLiteDatabase, row: JSONObject, log: SyncLog,
        iconGuard: PullIconGuard = PullIconGuard(db)
    ): Boolean {
        val uuid = row.getString("uuid")
        val iconUuid = row.getString("icon_uuid")
        val iconId = findIdByUuid(db, "icon", iconUuid)
        if (iconId == null) {
            Log.w(TAG, "currency $uuid: icon_uuid ещё не известен локально — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("name", row.getString("name"))
        cv.put("iso_code", row.getString("iso_code"))
        if (!iconGuard.keepsOwnIcon("currency", uuid, iconUuid)) cv.put("icon_id", iconId)
        cv.put("symbol", row.getString("symbol"))
        cv.put("precision", row.getInt("precision"))
        // Старый сервер ключа не присылает — локальный флаг тогда не трогаем (design D6).
        if (row.has("show_in_equivalent") && !row.isNull("show_in_equivalent")) {
            cv.put("show_in_equivalent", if (row.getBoolean("show_in_equivalent")) 1 else 0)
        }
        cv.put("is_shared", if (isSharedRow(row)) 1 else 0)
        applySyncColumns(cv, row)
        val wasInsert = upsertByUuid(db, "currency", uuid, cv) ?: return true
        log.pull(Texts.get(R.string.sync_type_currency), pullAction(rowIsDeleted(row), wasInsert))
        return true
    }

    /**
     * Общая ли строка справочника: у общих (поток `reference`) `ledger_id`
     * пуст (openspec currency-ui-tweaks, design D1). Строку без ключа вовсе считаем
     * своей — так безопаснее: своя строка допускает правку, а не теряет её.
     */
    @JvmStatic
    internal fun isSharedRow(row: JSONObject): Boolean = row.has("ledger_id") && row.isNull("ledger_id")

    @JvmStatic
    @Throws(JSONException::class)
    internal fun applyCurrencyPair(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val baseId = findIdByUuid(db, "currency", row.getString("base_currency_uuid"))
        val quoteId = findIdByUuid(db, "currency", row.getString("quote_currency_uuid"))
        if (baseId == null || quoteId == null) {
            Log.w(TAG, "currency_pair $uuid: валюта ещё не синхронизирована — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("symbol", row.getString("symbol"))
        cv.put("base_currency_id", baseId)
        cv.put("quote_currency_id", quoteId)
        cv.put("precision", row.getInt("precision"))
        cv.put("is_shared", if (isSharedRow(row)) 1 else 0)
        applySyncColumns(cv, row)
        // Символ занят другой живой парой (общая пара и пара кошелька с одним символом — на
        // сервере два индекса, на телефоне один): openspec sync-pair-symbol-adopt.
        val conflict = resolvePairSymbolConflict(db, uuid, cv, row)
        if (conflict == PairSymbolOutcome.REPLACED) {
            log.pull(Texts.get(R.string.sync_type_pair), pullAction(false, false))
            return true
        }
        if (conflict == PairSymbolOutcome.KEPT_LOCAL) return true
        if (conflict == PairSymbolOutcome.FAILED) return false // не терять — отложить
        val wasInsert = upsertByUuid(db, "currency_pair", uuid, cv)
            ?: return false // другой индекс — строку не объявляем обработанной
        log.pull(Texts.get(R.string.sync_type_pair), pullAction(rowIsDeleted(row), wasInsert))
        return true
    }

    /** Исход [resolvePairSymbolConflict]. */
    internal enum class PairSymbolOutcome {
        /** Конфликта нет — обычная вставка/обновление по uuid. */
        NONE,
        /** Локальная пара взяла uuid и поля пришедшей. */
        REPLACED,
        /** Пришедшая общая пара уступила паре кошелька — вставлять нечего. */
        KEPT_LOCAL,
        /** Заменить не удалось — строка остаётся отложенной. */
        FAILED
    }

    /**
     * Пара кошелька приоритетнее общей: пришедшая живая пара, чей символ занят другой живой парой
     * с другим uuid, заменяет её (локальный `_id` сохраняется, курсы и перерасчёты
     * привязаны по нему и остаются на месте); пришедшая общая уступает синкнутой паре
     * кошелька. Работает при любой загрузке, не только при подключении.
     */
    @JvmStatic
    @Throws(JSONException::class)
    internal fun resolvePairSymbolConflict(
        db: SQLiteDatabase, uuid: String,
        cv: ContentValues, row: JSONObject
    ): PairSymbolOutcome {
        if (rowIsDeleted(row) || localUuid(db, "currency_pair", uuid) != null) {
            return PairSymbolOutcome.NONE
        }
        val occupantUuid: String
        val occupantShared: Boolean
        val occupantUnsent: Boolean
        val c = db.rawQuery(
            "SELECT uuid, is_shared, dirty FROM currency_pair" +
                " WHERE symbol = ? COLLATE NOCASE AND deleted_at IS NULL LIMIT 1",
            arrayOf(cv.getAsString("symbol"))
        )
        try {
            if (!c.moveToFirst()) return PairSymbolOutcome.NONE
            occupantUuid = c.getString(0)
            occupantShared = c.getInt(1) != 0
            occupantUnsent = c.getInt(2) != 0
        } finally {
            c.close()
        }
        val incomingShared = isSharedRow(row)
        if (incomingShared && !occupantShared && !occupantUnsent) {
            return PairSymbolOutcome.KEPT_LOCAL // пара кошелька важнее общей
        }
        val adopted = ContentValues(cv)
        adopted.put("uuid", uuid)
        adopted.put("from_seed", 0)
        try {
            LedgerConnection.replaceUuid(db, "currency_pair", occupantUuid, uuid, adopted)
        } catch (e: SQLiteException) {
            Log.w(TAG, "currency_pair $uuid: замена по символу не удалась: ${e.message}")
            return PairSymbolOutcome.FAILED
        }
        return PairSymbolOutcome.REPLACED
    }

    @Throws(JSONException::class)
    private fun applyCurrencyPairRate(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"))
        if (pairId == null) {
            Log.w(TAG, "currency_pair_rate $uuid: пара ещё не синхронизирована — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("currency_pair_id", pairId)
        // "date" на сервере — тип date (не timestamp), to_jsonb сериализует
        // его уже как "yyyy-MM-dd" — ровно формат, которого ждёт локальная
        // колонка (DateUtils.formatDateOnly), конверсия не нужна.
        cv.put("date", row.getString("date"))
        cv.put("rate", row.getString("rate")) // numeric-как-строка, не парсим в double
        applySyncColumns(cv, row)
        val wasInsert = upsertByUuid(db, "currency_pair_rate", uuid, cv) ?: return true
        log.pull(Texts.get(R.string.sync_type_rate), pullAction(rowIsDeleted(row), wasInsert))
        return true
    }

    @Throws(JSONException::class)
    private fun applyCurrencyConversion(db: SQLiteDatabase, row: JSONObject): Boolean {
        val uuid = row.getString("uuid")
        val fromId = findIdByUuid(db, "currency", row.getString("from_currency_uuid"))
        val toId = findIdByUuid(db, "currency", row.getString("to_currency_uuid"))
        val pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"))
        if (fromId == null || toId == null || pairId == null) {
            Log.w(TAG, "currency_conversion $uuid: валюта/пара ещё не синхронизированы — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("from_currency_id", fromId)
        cv.put("to_currency_id", toId)
        cv.put("num", row.getInt("num"))
        cv.put("currency_pair_id", pairId)
        cv.put("is_reverse", if (row.getBoolean("is_reverse")) 1 else 0)
        applySyncColumns(cv, row)
        upsertByUuid(db, "currency_conversion", uuid, cv)
        return true
    }

    @JvmStatic
    @Throws(JSONException::class)
    internal fun applyAccount(db: SQLiteDatabase, row: JSONObject, log: SyncLog, iconGuard: PullIconGuard): Boolean {
        val uuid = row.getString("uuid")
        val iconUuid = row.getString("icon_uuid")
        val iconId = findIdByUuid(db, "icon", iconUuid)
        if (iconId == null) {
            Log.w(TAG, "account $uuid: icon_uuid ещё не известен локально — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("name", row.getString("name"))
        cv.put("comment", row.getString("comment"))
        if (!iconGuard.keepsOwnIcon("account", uuid, iconUuid)) cv.put("icon_id", iconId)
        cv.put("is_archived", if (row.getBoolean("is_archived")) 1 else 0)
        // system_kind/kind — та же пара колонок, что и в push-стороне (collectDirtyByTable) выше;
        // без них строка «Transfer»/«Transfer commission», пришедшая с другого устройства, теряла
        // бы свою системность локально, а обычный счёт/категория — признак кошелёк/категория.
        cv.put("system_kind", row.getInt("system_kind"))
        cv.put("kind", row.getInt("kind"))
        // category_kind (v92, спека category-kind): вид категории. Поле необязательное — сервер
        // без колонки его не шлёт, и такая строка применяется как «Оба», а не откладывается.
        // Значение вне 0..2 тоже читается как «Оба» (тот же безопасный дефолт, что у fromValue).
        cv.put(
            "category_kind", com.example.wallettransactions.DataItems.Accounts.AccountItem
                .CategoryKind.fromValue(row.optInt("category_kind", 2)).value
        )
        applySyncColumns(cv, row)
        val wasInsert = upsertByUuid(db, "account", uuid, cv) ?: return true
        log.pull(Texts.get(R.string.sync_type_account), pullAction(rowIsDeleted(row), wasInsert))
        return true
    }

    /**
     * Особый случай, не [upsertByUuid]: `_id` НЕ автоинкремент,
     * обязан совпасть с `account._id` того же счёта (общий первичный
     * ключ, см. `WalletDbHelper.CreateAccountOperatingEntry`), а сам
     * `uuid` этой строки равен `account.uuid` (инвариант, см.
     * `WalletDbHelper.backfillAccountOperatingUuid`) — поэтому
     * родительский счёт ищем ПО ТОМУ ЖЕ uuid, что пришёл в этой строке, а не
     * по отдельному полю ссылки (его в протоколе и нет, см.
     * wallet-api/schema/table/account_operating.sql).
     */
    @Throws(JSONException::class)
    private fun applyAccountOperating(db: SQLiteDatabase, row: JSONObject): Boolean {
        val uuid = row.getString("uuid")
        val accountId = findIdByUuid(db, "account", uuid)
        if (accountId == null) {
            Log.w(
                TAG, "account_operating " + uuid + ": account ещё не применён — строка пропущена " + // not UI: лог
                    "(нарушен порядок применения таблиц?)" // not UI: лог
            ) // not UI: лог
            return false
        }
        val currencyId = findIdByUuid(db, "currency", row.getString("currency_uuid"))
        if (currencyId == null) {
            Log.w(TAG, "account_operating $uuid: валюта ещё не синхронизирована — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("currency_id", currencyId)
        applySyncColumns(cv, row)
        try {
            val local = localUuid(db, "account_operating", uuid)
            if (local != null) {
                updateFromServer(db, "account_operating", cv, local)
            } else {
                cv.put("_id", accountId)
                // Инвариант "uuid как у account того же счёта" — в его локальном написании.
                cv.put("uuid", localUuid(db, "account", uuid))
                db.insertOrThrow("account_operating", null, cv)
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "upsert account_operating uuid=" + uuid + " не удался: " + e.message)
        }
        return true
    }

    /**
     * Признак капитального актива (capital-assets, design п. 3) — копия логики
     * [applyAccountOperating]: общий первичный ключ с `account`, родительский
     * счёт ищется по тому же uuid. Счёта ещё нет — строка откладывается до его прихода.
     *
     * В журнал — своим типом, в отличие от `account_operating`: пометка актива меняет
     * только эту строку, и без записи в журнале `SyncRunner` не поднял бы ревизию —
     * открытые табы показывали бы итоги без нового актива (design п. 10, android-wallet#42).
     */
    @Throws(JSONException::class)
    private fun applyAccountAsset(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val accountId = findIdByUuid(db, "account", uuid)
        if (accountId == null) {
            Log.w(TAG, "account_asset $uuid: account ещё не применён — строка отложена")
            return false
        }
        val cv = ContentValues()
        cv.put("asset_type", row.getInt("asset_type"))
        cv.put("include_in_net_worth", if (row.getBoolean("include_in_net_worth")) 1 else 0)
        cv.put("comment", row.optString("comment", ""))
        applySyncColumns(cv, row)
        try {
            val local = localUuid(db, "account_asset", uuid)
            if (local != null) {
                updateFromServer(db, "account_asset", cv, local)
            } else {
                cv.put("_id", accountId)
                cv.put("uuid", localUuid(db, "account", uuid))
                db.insertOrThrow("account_asset", null, cv)
            }
            log.pull(Texts.get(R.string.sync_type_asset), pullAction(rowIsDeleted(row), local == null))
        } catch (e: SQLiteException) {
            Log.w(TAG, "upsert account_asset uuid=" + uuid + " не удался: " + e.message)
        }
        return true
    }

    /**
     * Контрагент (debts, design D3/D12). Пришёл удалённым, а у живого займа здесь он ещё
     * указан — займ не теряется: контрагент остаётся живым и уходит push'ем снова (сервер
     * сам ответит `counterparty_in_use`, если займ уже есть и там).
     */
    @Throws(JSONException::class)
    private fun applyCounterparty(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val cv = ContentValues()
        cv.put("name", row.getString("name"))
        cv.put("comment", row.optString("comment", ""))
        cv.put("icon_uuid", if (row.isNull("icon_uuid")) null else row.optString("icon_uuid", null))
        cv.put("is_archived", if (row.optBoolean("is_archived", false)) 1 else 0)
        applySyncColumns(cv, row)
        val local = localUuid(db, "counterparty", uuid)
        val keepAlive = rowIsDeleted(row) && local != null && hasLiveDebt(db, local)
        if (keepAlive) {
            cv.putNull("deleted_at")
            Log.w(TAG, "counterparty $uuid пришёл удалённым, но у займа он есть — оставлен") // not UI: лог
        }
        try {
            if (local != null) {
                updateFromServer(db, "counterparty", cv, local)
                if (keepAlive) {
                    val dirty = ContentValues()
                    dirty.put("dirty", 1)
                    db.update("counterparty", dirty, "uuid=?", arrayOf(local))
                }
            } else {
                cv.put("uuid", uuid)
                db.insertOrThrow("counterparty", null, cv)
            }
            log.pull(Texts.get(R.string.sync_type_counterparty), pullAction(rowIsDeleted(row) && !keepAlive, local == null))
        } catch (e: SQLiteException) {
            // uq_counterparty_name: свой неотправленный с тем же именем — разрулит push (duplicate_name).
            Log.w(TAG, "upsert counterparty uuid=" + uuid + " не удался: " + e.message)
        }
        return true
    }

    private fun hasLiveDebt(db: SQLiteDatabase, counterpartyUuid: String): Boolean {
        val c = db.rawQuery(
            "SELECT 1 FROM account_debt ad JOIN account a ON a._id = ad._id" +
                " AND a.deleted_at IS NULL WHERE ad.counterparty_uuid = ? AND ad.deleted_at IS NULL LIMIT 1",
            arrayOf(counterpartyUuid)
        )
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /**
     * Признак займа (debts, design D3). Счёт или контрагент ещё не пришли — строка
     * откладывается с логом ([PullDeferredRows]) и применяется, когда родитель придёт.
     * `counterparty_uuid` пишется в местном написании uuid контрагента.
     */
    @Throws(JSONException::class)
    private fun applyAccountDebt(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val accountId = findIdByUuid(db, "account", uuid)
        if (accountId == null) {
            Log.w(TAG, "account_debt $uuid: account ещё не применён — строка отложена")
            return false
        }
        val counterparty = localUuid(db, "counterparty", row.getString("counterparty_uuid"))
        if (counterparty == null) {
            Log.w(
                TAG, "account_debt " + uuid + ": контрагент " + row.getString("counterparty_uuid") + // not UI: лог
                    " ещё не применён — строка отложена" // not UI: лог
            ) // not UI: лог
            return false
        }
        val cv = ContentValues()
        cv.put("counterparty_uuid", counterparty)
        cv.put("comment", row.optString("comment", ""))
        cv.put("due_date", if (row.isNull("due_date")) null else row.optString("due_date", null))
        applySyncColumns(cv, row)
        try {
            val local = localUuid(db, "account_debt", uuid)
            if (local != null) {
                updateFromServer(db, "account_debt", cv, local)
            } else {
                cv.put("_id", accountId)
                cv.put("uuid", localUuid(db, "account", uuid))
                db.insertOrThrow("account_debt", null, cv)
            }
            log.pull(Texts.get(R.string.sync_type_debt), pullAction(rowIsDeleted(row), local == null))
        } catch (e: SQLiteException) {
            Log.w(TAG, "upsert account_debt uuid=" + uuid + " не удался: " + e.message)
        }
        return true
    }

    @Throws(JSONException::class)
    private fun applyOperation(db: SQLiteDatabase, row: JSONObject, log: SyncLog): Boolean {
        val uuid = row.getString("uuid")
        val accFr = findIdByUuid(db, "account", row.getString("acc_fr_uuid"))
        val accTo = findIdByUuid(db, "account", row.getString("acc_to_uuid"))
        if (accFr == null || accTo == null) {
            Log.w(TAG, "operation $uuid: счёт ещё не синхронизирован — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("date", isoTimestampToLocalDbFormat(row.getString("date")))
        cv.put("acc_fr_id", accFr)
        cv.put("acc_to_id", accTo)
        cv.put("amount", row.getString("amount")) // numeric-как-строка
        cv.put("comment", row.getString("comment"))
        applySyncColumns(cv, row)
        val wasInsert = upsertByUuid(db, "operation", uuid, cv) ?: return true
        log.pull(Texts.get(R.string.sync_type_operation), pullAction(rowIsDeleted(row), wasInsert))
        if (wasInsert && !rowIsDeleted(row)) log.newOperation(uuid)
        return true
    }

    @Throws(JSONException::class)
    private fun applyOperationLink(db: SQLiteDatabase, row: JSONObject): Boolean {
        val uuid = row.getString("uuid")
        val mainId = findIdByUuid(db, "operation", row.getString("main_uuid"))
        val linkId = findIdByUuid(db, "operation", row.getString("link_uuid"))
        if (mainId == null || linkId == null) {
            Log.w(TAG, "operation_link $uuid: проводка ещё не синхронизирована — строка пропущена")
            return false
        }
        val cv = ContentValues()
        cv.put("main_id", mainId)
        cv.put("link_id", linkId)
        cv.put("type", row.getInt("type"))
        applySyncColumns(cv, row)
        upsertByUuid(db, "operation_link", uuid, cv)
        return true
    }

    // ── Общие мелочи ─────────────────────────────────────────────────────────

    // ── ЧИСТКА УДАЛЁННЫХ (openspec purge-synced-tombstones) ────────────────────

    /**
     * Порядок физического удаления — от листьев к корням: строка удаляется раньше
     * тех, на которые ссылается, так что в одном проходе удалённая операция
     * освобождает свой удалённый счёт. Тот же состав, что [TABLE_ORDER].
     */
    internal val PURGE_ORDER: List<String> = listOf(
        "operation_link", "operation", "account_asset", "account_debt", "account_operating", "account",
        "counterparty", "currency_conversion",
        "currency_pair_rate", "currency_pair", "currency", "icon"
    )

    @JvmStatic
    internal fun isSyncTable(table: String): Boolean = PURGE_ORDER.contains(table)

    /**
     * Ссылки с `ON DELETE CASCADE` ошибкой внешнего ключа не остановить —
     * удаление молча унесло бы ссылающиеся строки. Проверяем их явно: связка
     * проводок на операцию и шаг шаблона операций (локальная таблица
     * без синка) на счёт. Остальные ссылки — `NO ACTION`, их ловит
     * [SQLiteConstraintException] в [deleteRowByRow].
     */
    private fun cascadeGuard(table: String): String =
        when (table) {
            "operation" ->
                " AND NOT EXISTS (SELECT 1 FROM " + WalletContract.OperationLinkEntry.TABLE_NAME + " l" +
                    " WHERE l." + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + " = t._id" +
                    " OR l." + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " = t._id)"
            "account" ->
                " AND NOT EXISTS (SELECT 1 FROM " + WalletContract.OperationTemplateStepEntry.TABLE_NAME + " r" +
                    " WHERE r." + WalletContract.OperationTemplateStepEntry.COLUMN_FROM + " = t._id" +
                    " OR r." + WalletContract.OperationTemplateStepEntry.COLUMN_TO + " = t._id)"
            else -> ""
        }

    /**
     * Удаляет строки таблицы, подходящие под `where` (алиас `t`),
     * построчно: строка, на которую ещё ссылаются, даёт [SQLiteConstraintException]
     * — откатывается только этот оператор, строка остаётся до следующего раза.
     * `recheck` повторяется в самом DELETE (без алиаса): между выборкой и
     * удалением строку мог изменить UI.
     */
    private fun deleteRowByRow(
        db: SQLiteDatabase, table: String, where: String, args: Array<String>?,
        recheck: String
    ): Int {
        val uuids = ArrayList<String>()
        val c = db.rawQuery("SELECT t.uuid FROM $table t WHERE $where" + cascadeGuard(table), args)
        try {
            while (c.moveToNext()) uuids.add(c.getString(0))
        } finally {
            c.close()
        }
        var deleted = 0
        for (uuid in uuids) {
            try {
                deleted += db.delete(table, "uuid = ? AND $recheck", arrayOf(uuid))
            } catch (e: SQLiteConstraintException) {
                Log.i(TAG, "purge $table uuid=$uuid: на строку ссылаются — остаётся")
            }
        }
        return deleted
    }

    /**
     * Шаг прохода после успешного [pullAll] (см. `SyncRunner`):
     * физически удаляет строки с отметкой удаления и без неотправленных изменений
     * (`dirty = 0`) — удаление уже у сервера, пришедшее — уже применено.
     * Строку, на которую ссылаются, оставляет. Журнал синка не пишет: для
     * пользователя ничего не меняется, удалённое и так не видно.
     *
     * @return сколько строк удалено
     */
    @JvmStatic
    internal fun purgeTombstones(db: SQLiteDatabase): Int {
        var total = 0
        val condition = "deleted_at IS NOT NULL AND dirty = 0"
        for (table in PURGE_ORDER) {
            total += deleteRowByRow(db, table, "t." + condition.replace(" AND ", " AND t."), null, condition)
        }
        if (total > 0) Log.i(TAG, "purge: удалено отправленных удалений: $total")
        return total
    }

    /** uuid в форме отметки `sync_reload_seen`: нижний регистр, без дефисов. */
    @JvmStatic
    internal fun normalizeUuid(uuid: String?): String? =
        uuid?.lowercase(Locale.ROOT)?.replace("-", "")

    /**
     * Отметки загрузки с нуля для строк страницы. Курс у сервера ищется по
     * (пара, дата), а не по uuid (`conflict_columns` в wallet-api): пришедший
     * курс засчитывает и локальную строку той же пары и даты с другим uuid —
     * иначе вычистка удалила бы её, а пришедшая не вставилась бы из-за
     * уникальности (пара, дата).
     */
    @Throws(JSONException::class)
    private fun markSeen(db: SQLiteDatabase, merged: Map<String, JSONArray>) {
        for ((table, rows) in merged) {
            if (!isSyncTable(table)) continue
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                insertSeen(db, table, row.optString("uuid", null))
                if ("currency_pair_rate" == table) {
                    val pairId = findIdByUuid(db, "currency_pair", row.optString("currency_pair_uuid", null)) ?: continue
                    val c = db.rawQuery(
                        "SELECT uuid FROM currency_pair_rate WHERE currency_pair_id = ? AND date = ?",
                        arrayOf(pairId.toString(), row.optString("date", ""))
                    )
                    try {
                        while (c.moveToNext()) insertSeen(db, table, c.getString(0))
                    } finally {
                        c.close()
                    }
                }
            }
        }
    }

    private fun insertSeen(db: SQLiteDatabase, table: String, uuid: String?) {
        if (uuid == null) return
        db.execSQL(
            "INSERT OR IGNORE INTO " + WalletDbHelper.SYNC_RELOAD_SEEN_TABLE + " (tbl, uuid) VALUES (?, ?)",
            arrayOf<Any?>(table, normalizeUuid(uuid))
        )
    }

    /**
     * Иконки не отправляются (server-managed-icons), у своих (`origin = 1`)
     * `dirty = 0` всегда — «синкнутыми» считаются только иконки с сервера.
     */
    private fun syncedOnly(table: String): String =
        if ("icon" == table) " AND t." + WalletContract.IconEntry.COLUMN_ORIGIN + " = " + WalletContract.IconEntry.ORIGIN_SERVER
        else ""

    /** Есть ли строки, уже бывшие на сервере — с ними загрузка с нуля идёт с вычисткой. */
    @JvmStatic
    internal fun hasSyncedRows(db: SQLiteDatabase): Boolean {
        for (table in PURGE_ORDER) {
            val c = db.rawQuery(
                "SELECT 1 FROM " + table + " t WHERE t.dirty = 0" + syncedOnly(table) + " LIMIT 1",
                null
            )
            try {
                if (c.moveToFirst()) return true
            } finally {
                c.close()
            }
        }
        return false
    }

    /** Начало загрузки с нуля: флаг, оба курсора 0, отметки прошлой (прерванной) загрузки — прочь. */
    private fun startFullReload(db: SQLiteDatabase, ctx: Context) {
        db.delete(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE, null, null)
        AppSettings.startFullReload(ctx)
    }

    /**
     * Конец загрузки с нуля (оба потока догнаны): удаляет синкнутые строки
     * (`dirty = 0`), которых не было в загруженных потоках, — сервер их
     * больше не хранит. Неотправленные (`dirty <> 0`) остаются, строка, на
     * которую ссылаются, — тоже.
     *
     * @return сколько строк удалено, по таблицам
     */
    @JvmStatic
    internal fun sweepUnseen(db: SQLiteDatabase): Map<String, Int> {
        val deleted = LinkedHashMap<String, Int>()
        for (table in PURGE_ORDER) {
            val where = "t.dirty = 0" + syncedOnly(table) +
                " AND replace(lower(t.uuid), '-', '') NOT IN (SELECT uuid FROM " +
                WalletDbHelper.SYNC_RELOAD_SEEN_TABLE + " WHERE tbl = ?)"
            deleted[table] = deleteRowByRow(db, table, where, arrayOf(table), "dirty = 0")
        }
        return deleted
    }

    /**
     * Единый upsert "нашли по uuid — обновили, не нашли — вставили" для ВСЕХ
     * синхронизируемых таблиц, кроме `account_operating` (см. её особый
     * случай выше) — включая таблицы с составным первичным ключом
     * (`operation_link`/`currency_pair_rate`/
     * `currency_conversion`): `cv` уже несёт все колонки,
     * образующие их PK, отдельно знать эти колонки здесь не нужно.
     *
     * @return `true` — строка только что вставлена (её раньше не было),
     *         `false` — уже была и обновлена, `null` — упало
     *         (конфликт с чужим уникальным индексом, строка пропущена).
     *         Вызывающие используют это, чтобы отличить в [SyncLog]
     *         "Добавлено" от "Изменено", не гадая заново тем же запросом.
     */
    private fun upsertByUuid(db: SQLiteDatabase, table: String, uuid: String, cv: ContentValues): Boolean? {
        try {
            val local = localUuid(db, table, uuid)
            val exists = local != null
            if (local != null) {
                updateFromServer(db, table, cv, local)
            } else if (adoptLocalByKey && cv.get("deleted_at") == null &&
                LedgerConnection.adoptByKey(db, table, uuid, cv)
            ) {
                return false // своя строка с тем же ключом стала этой — «Изменено»
            } else {
                cv.put("uuid", uuid)
                db.insertOrThrow(table, null, cv)
            }
            return !exists
        } catch (e: SQLiteException) {
            // Столкновение с чужим уникальным индексом (currency.name/
            // iso_code, currency_pair.symbol, составной PK и т.п.) — для
            // этих таблиц (в отличие от account) сервер пока не диагностирует
            // дубль осмысленно и не даёт uuid, на который переклеиться (см.
            // javadoc класса) — пропускаем строку, а не роняем всю страницу.
            Log.w(TAG, "upsert " + table + " uuid=" + uuid + " не удался: " + e.message)
            return null
        }
    }

    /**
     * UPDATE строки данными с сервера так, чтобы она осталась `dirty=0`.
     *
     * Триггер `trg_<table>_dirty` (WalletDbHelper.createDirtyTrigger) срабатывает
     * при `NEW.dirty = OLD.dirty`. Явный `SET dirty=0` по строке, где и так
     * был 0, под это условие попадает — и триггер помечал строку dirty=1. Так каждая
     * обновлённая с сервера строка уходила обратно в следующий push, сервер выдавал ей
     * новый seq, pull приносил её снова — по кругу. Поэтому в два шага через
     * `dirty=2`: и 0/1→2, и 2→0 — смена значения, триггер молчит. Оба шага в
     * транзакции страницы (applyPage), промежуточное 2 наружу не видно.
     */
    @JvmStatic
    internal fun updateFromServer(db: SQLiteDatabase, table: String, cv: ContentValues, localUuid: String) {
        cv.put("dirty", 2)
        db.update(table, cv, "uuid=?", arrayOf(localUuid))
        val clean = ContentValues()
        clean.put("dirty", 0)
        db.update(table, clean, "uuid=?", arrayOf(localUuid))
    }

    /**
     * uuid строки так, как он записан ЗДЕСЬ, или `null`, если строки нет.
     * Локально uuid бывает в двух написаниях: `DEFAULT lower(hex(randomblob(16)))`
     * даёт 32 hex-символа без дефисов (счета, справочники из сида), а
     * `UUID.randomUUID()` — каноническую форму с дефисами. Сервер хранит
     * тип `uuid` и в pull ВСЕГДА отдаёт каноническую форму, поэтому точное
     * сравнение строк не находило, например, счёт без дефисов — и операция,
     * записанная ботом на этот счёт, молча пропускалась. Ищем оба написания;
     * `IN` по колонке uuid по-прежнему идёт по индексу.
     */
    @JvmStatic
    internal fun localUuid(db: SQLiteDatabase, table: String, uuid: String?): String? {
        if (uuid == null) return null
        val plain = uuid.lowercase(Locale.ROOT).replace("-", "")
        val dashed = if (plain.length == 32)
            plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16) +
                "-" + plain.substring(16, 20) + "-" + plain.substring(20)
        else plain
        val c = db.rawQuery(
            "SELECT uuid FROM $table WHERE uuid IN (?, ?, ?) LIMIT 1",
            arrayOf(uuid, plain, dashed)
        )
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    @Throws(JSONException::class)
    private fun applySyncColumns(cv: ContentValues, row: JSONObject) {
        cv.put("dirty", 0) // явно, не полагаясь на DEFAULT — форсит триггер иначе (см. WalletDbHelper.createDirtyTrigger)
        cv.put("deleted_at", parseDeletedAtMillisOrNull(row))
        cv.put("updated_by_device", if (row.isNull("updated_by")) null else row.getString("updated_by"))
    }

    /** Раз-строка ли это ("deleted_at" не null) — для [SyncLog], различить "Удалено" от прочего. */
    @Throws(JSONException::class)
    private fun rowIsDeleted(row: JSONObject): Boolean = row.has("deleted_at") && !row.isNull("deleted_at")

    @Throws(JSONException::class)
    private fun parseDeletedAtMillisOrNull(row: JSONObject): Long? {
        if (!row.has("deleted_at") || row.isNull("deleted_at")) return null
        val iso = row.getString("deleted_at")
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            // Диагностическая колонка (см. SYNC_COLUMNS_DDL в WalletDbHelper) —
            // ни один фильтр в приложении не смотрит на точное значение,
            // только на NULL/NOT NULL, поэтому неразобранный формат не
            // блокирует применение строки, просто теряет точность диагностики.
            Log.w(TAG, "не удалось разобрать deleted_at=$iso, беру текущее время")
            System.currentTimeMillis()
        }
    }

    /**
     * `operation.date` на сервере — `timestamp` БЕЗ часового
     * пояса (см. wallet-api/schema/table/operation.sql), `to_jsonb`
     * сериализует его как `"2026-01-15T10:00:00"` — переводим в
     * `"yyyy-MM-dd HH:mm"`, формат, которого ждёт остальной код
     * приложения ([com.example.wallettransactions.DateUtils.formatDB]).
     * Секунды теряются сознательно — локальный формат их не несёт вообще.
     */
    private fun isoTimestampToLocalDbFormat(iso: String): String {
        val t = iso.indexOf('T')
        if (t < 0) return iso // защитно — уже в ожидаемом виде
        val datePart = iso.substring(0, t)
        val timePart = iso.substring(t + 1)
        val hhmm = if (timePart.length >= 5) timePart.substring(0, 5) else timePart
        return "$datePart $hhmm"
    }

    private fun findIdByUuid(db: SQLiteDatabase, table: String, uuid: String?): Long? {
        val local = localUuid(db, table, uuid) ?: return null
        val c = db.rawQuery("SELECT _id FROM $table WHERE uuid=? LIMIT 1", arrayOf(local))
        try {
            return if (c.moveToFirst()) c.getLong(0) else null
        } finally {
            c.close()
        }
    }

    private fun uuidById(db: SQLiteDatabase, table: String, id: Long): String? =
        columnById(db, table, "uuid", id)

    /**
     * Значение произвольной колонки по локальному `_id` — общий случай
     * [uuidById] (тот же запрос, только колонка "uuid" зашита). Отдельно
     * нужен [SyncLog]: человекочитаемое описание строки (имя счёта, символ пары
     * и т.п.) читается из ДРУГИХ колонок той же таблицы-цели.
     */
    private fun columnById(db: SQLiteDatabase, table: String, column: String, id: Long): String? {
        val c = db.rawQuery(
            "SELECT $column FROM $table WHERE _id=? LIMIT 1",
            arrayOf(id.toString())
        )
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }
}
