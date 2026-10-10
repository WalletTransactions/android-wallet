package com.example.wallettransactions.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Движок push/pull (org/docs/TODO_mobile_sync.md, Фаза 3, п. 4) — переливает
 * dirty-строки локальной SQLite в {@code POST /sync/push} и применяет
 * {@code GET /sync/pull} обратно. Протокол — README wallet-api,
 * "Синхронизация"; вызывается из {@link SyncWorker}, транспорт — в
 * {@link SyncApiClient}.
 *
 * <h3>Перевод id ↔ uuid</h3>
 * Локальная схема ссылается на связанные строки целочисленным {@code _id}
 * (свой на каждом устройстве), протокол сервера — {@code uuid} (общий для
 * всех устройств). Push переводит {@code _id -> uuid} через {@link #uuidById},
 * pull — {@code uuid -> _id} через {@link #findIdByUuid}, каждый раз читая
 * таблицу-цель напрямую (батчи небольшие, кэш не нужен).
 *
 * <h3>Порядок применения pull (см. {@link #TABLE_ORDER})</h3>
 * Строка со ссылкой (например, {@code account.icon_uuid}) обязана применяться
 * ПОСЛЕ строки, на которую она ссылается, иначе перевод uuid->_id не найдёт
 * локальную запись. Оба потока pull (ledger/reference, см. README wallet-api)
 * сливаются в одну карту ПЕРЕД применением ({@link #mergeChanges}) и
 * обходятся в фиксированном порядке зависимостей — это безопасно, потому что
 * все строки одной страницы одного push-батча делят один и тот же
 * {@code seq}, а ссылающаяся строка не может быть создана раньше той, на
 * которую она ссылается (то есть её {@code seq} не может быть МЕНЬШЕ) —
 * см. README wallet-api, "Курсор и страницы".
 *
 * <h3>Порции push (см. {@link #pushDirty})</h3>
 * Один вызов {@link #pushDirty} может отправить НЕСКОЛЬКО {@code POST
 * /sync/push} подряд — по {@link #MAX_PUSH_BATCH_ROWS} строк на порцию, а не
 * весь dirty-набор одним запросом. Порядок ТАБЛИЦ между порциями — тот же
 * {@link #TABLE_ORDER}, что и у pull (см. выше) и {@code kApplyOrder} на
 * сервере (sync_repo.cpp) — обязателен по той же причине: строка-ссылка не
 * должна уйти в порции РАНЬШЕ строки, на которую ссылается.
 *
 * <h3>Иконки — метаданные и содержимое раздельно (org/docs/TODO_mobile_sync.md, "Иконки")</h3>
 * {@code icon} синхронизируется как обычная таблица (метаданные —
 * name/icon_type/content_sha256), но САМИ БАЙТЫ изображения через
 * push/pull не идут — отдельные {@code POST}/{@code GET /sync/blobs}
 * (см. {@link SyncApiClient#uploadBlob}/{@link SyncApiClient#downloadBlob}).
 * На push — сначала заливаем байты, получаем {@code content_sha256},
 * потом строим строку {@code icon} (см. {@link #collectDirtyIcons}). На
 * pull — для НОВОЙ (по uuid) иконки сначала скачиваем содержимое, для уже
 * существующей — только обновляем метаданные, байты не перекачиваем (см.
 * {@link #applyIcon}); все нужные для страницы скачивания делаются ДО
 * {@code db.beginTransaction()} (см. {@link #prefetchNewIconBlobs}), не
 * внутри неё — держать sqlite-транзакцию открытой на время сетевого
 * запроса означало бы блокировать доступ к БД для всего остального
 * приложения (один общий connection, см. {@code Singleton}) на
 * непредсказуемое время.
 * <p>
 * Известный, сознательно не закрытый в этом заходе пробел: локальное
 * удаление иконки ({@code IconEditDialog.deleteIcon}) остаётся жёстким
 * {@code DELETE}, не soft-delete — раньше это было безобидно ("синку всё
 * равно нечего терять", план, Фаза 3, п. 1), теперь, когда push icon
 * реализован, это означает, что удаление иконки НЕ долетает до других
 * устройств. Не исправлено намеренно — перевод на soft-delete снял бы и
 * защиту от удаления ИСПОЛЬЗУЕМОЙ иконки (сейчас — обычный FK
 * {@code ON DELETE RESTRICT}, при soft-delete внешний ключ перестаёт
 * видеть конфликт), это смена пользовательской гарантии, не механическая
 * правка — решение за пользователем (см. org/docs/TODO_mobile_sync.md,
 * "Открытые вопросы").
 *
 * <h3>Что ещё сознательно не решено в этом заходе</h3>
 * <ul>
 *   <li>Конфликт дублирующегося имени/кода диагностируется сервером
 *   осмысленно (409 + {@code existing_uuid}) только для {@code account}
 *   (см. {@link #resolveAccountConflicts}) — для остальных уникальных
 *   индексов (currency.name/iso_code, currency_pair.symbol, icon.name,
 *   currency_pair_rate/currency_conversion PK) столкновение на pull-вставке
 *   просто пропускает строку с предупреждением в лог (см.
 *   {@link #upsertByUuid}), не пытаясь слить дубли — тот же класс проблемы,
 *   что и с account, но без протокола разрешения (см.
 *   org/docs/TODO_mobile_sync.md, "Открытые вопросы").</li>
 * </ul>
 */
final class SyncEngine {

    private static final String TAG = "SyncEngine";

    /** См. javadoc класса, "Порядок применения pull" — icon первым: на него ссылаются account/currency. */
    private static final String[] TABLE_ORDER = {
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link",
    };

    /**
     * Верхняя граница числа строк в ОДНОЙ порции push (см. {@link #pushDirty},
     * "Порции push" в javadoc класса) — не протокольное ограничение (сервер
     * принял бы и больше, см. README wallet-api, "Синхронизация": размер
     * батча там нигде не проверяется), а разумный практический предел: после
     * долгого офлайна dirty-строк может накопиться десятки тысяч (например,
     * импорт истории курсов), и заливать их одним HTTP-телом/одной
     * Postgres-транзакцией — рискованно по памяти на телефоне и по времени
     * удержания транзакции на сервере. 1000 — округлый порядок, подобранный
     * "на глаз", не из измерений.
     */
    private static final int MAX_PUSH_BATCH_ROWS = 1000;

    /**
     * Верхняя граница числа РАУНДОВ самолечения в ОДНОМ вызове {@link
     * #pushDirty} (см. её javadoc, "Цикл самолечения") — не ожидаемый предел
     * (каждый раунд чинит минимум одну РАЗЛИЧНУЮ строку — {@code redirty()}/
     * {@code resolveAccountConflicts} не тронули бы её повторно, будь она уже
     * dirty=1 либо уже переклеена, см. их javadoc, — так что раундов не может
     * быть больше, чем протухших строк, а это конечное число), а защита от
     * пока не виданного бага — например, сервер как-то умудрился отвечать
     * missing_reference на одну и ту же строку раз за разом. 200 — с большим
     * запасом (реальный инцидент 2026-09-14 чинился за десятки раундов, не
     * за сотни).
     */
    private static final int MAX_SELF_HEAL_ROUNDS = 200;

    private SyncEngine() {}

    // ── PUSH ─────────────────────────────────────────────────────────────────

    /**
     * Порции push — НЕ один HTTP-вызов на весь dirty-набор (как было раньше),
     * а последовательность вызовов {@code POST /sync/push} по НЕ БОЛЕЕ
     * {@link #MAX_PUSH_BATCH_ROWS} строк каждый (см. {@link #chunkIntoPortions}) —
     * свой {@code batch_id}, своя Postgres-транзакция на КАЖДУЮ порцию (сервер
     * про порции ничего не знает, это чисто клиентское разбиение одного и
     * того же протокола, см. README wallet-api, "Синхронизация").
     * <p>
     * Порции идут строго по {@link #TABLE_ORDER} (порядок собирает
     * {@link #collectDirtyByTable}) и отправляются ПОСЛЕДОВАТЕЛЬНО, дожидаясь
     * ответа на предыдущую перед отправкой следующей — не параллельно и не
     * заранее все разом: строка-ссылка (например, {@code account.icon_uuid})
     * не может уйти раньше строки, на которую ссылается (тот же принцип, что
     * и {@code kApplyOrder} внутри одного батча на сервере, см. sync_repo.cpp),
     * а тут это порядок между ОТДЕЛЬНЫМИ HTTP-вызовами, а не строк внутри
     * одного. Сетевое исключение прерывает весь метод — дальнейшие порции
     * этого вызова не пробуем (нечем: соединения с сервером больше нет).
     * Уже успешно отправленные порции при этом остаются применёнными (dirty
     * у них уже сброшен) — повторный раунд/цикл синка соберёт заново только
     * то, что не прошло.
     * <p>
     * {@code log} получает запись "Отправлено" ТОЛЬКО за уже подтверждённую
     * (HTTP 200) порцию (см. {@link #logPortionSent}) — раньше эти записи
     * добавлялись во время СБОРА строк (до самого HTTP-вызова, см. историю
     * {@link #collectDirtyByTable}), и провал push всё равно оставлял в
     * журнале "Отправлено" для строк, которые на самом деле не долетели.
     * Любой отказ, кроме самовосстанавливающихся конфликта имени счёта и
     * {@code missing_reference} со связанным table/uuid (см. ниже,
     * {@link #parseMissingReference}), пробрасывается
     * {@link PushRejectedException} — раньше тихо проглатывался (только
     * {@code Log.w} в системный лог) и {@link SyncRunner#runOnce} возвращал
     * {@code Status.OK}, будто всё прошло (реальный инцидент 2026-09-09 —
     * см. README wallet-api, "system_kind/kind").
     * <p>
     * <h3>Цикл самолечения (инцидент 2026-09-14)</h3>
     * Раньше самовосстановление одной порции (конфликт имени счёта ИЛИ
     * {@code missing_reference}) заканчивалось {@code return} из ВСЕГО
     * метода — расчёт был "почини одну ссылку, следующий клик «Синхронизировать»
     * повторит попытку". На практике это означало, что при НЕСКОЛЬКИХ
     * протухших ссылках сразу (например, локальная база считает десятки
     * строк уже отправленными {@code dirty=0}, а сервер, на который они
     * ссылаются, успел потерять данные — типичный случай на dev-стенде после
     * пересборки контейнера БД с чистым volume) пользователю приходилось
     * вручную жать «Синхронизировать» заново на КАЖДУЮ ссылку по отдельности,
     * а диалог каждый раз показывал пустой журнал "Изменений нет", будто
     * ничего не произошло.
     * <p>
     * Теперь {@link #pushDirty} — внешний цикл ПО РАУНДАМ: раунд заново
     * собирает {@code byTable} (см. {@link #collectDirtyByTable}) и шлёт
     * порции, пока не наткнётся либо на конец (всё отправлено — выходим),
     * либо на самовосстанавливающийся отказ. В последнем случае чиним ОДНУ
     * ссылку ({@code resolveAccountConflicts}/{@code redirty}) и СРАЗУ ЖЕ,
     * без ожидания следующего клика, начинаем СЛЕДУЮЩИЙ раунд — заново
     * собираем {@code byTable} (уже без только что вылеченной строки —
     * {@code redirty()} требует {@code dirty<>1}, второй раз её не тронет,
     * так что раундов не может быть больше, чем протухших строк) и пробуем
     * снова, теперь и порции ПОСЛЕ бывшей сбойной (например, {@code operation})
     * тоже в деле. {@link #MAX_SELF_HEAL_ROUNDS} — защитный потолок на случай
     * ещё не виданного бага (см. её javadoc), не ожидаемый предел.
     * {@code log.healed(...)} — чтобы каждая починка была ВИДНА в диалоге
     * (см. {@link SyncLog#healed}), а не тонула в logcat как раньше: иначе
     * синк с пустым журналом выглядит как "ничего не произошло", хотя на
     * самом деле часть данных застряла и чинится раунд за раундом прямо
     * сейчас, одним нажатием кнопки.
     */
    static void pushDirty(SQLiteDatabase db, SyncApiClient api, SyncLog log) throws IOException, JSONException {
        for (int round = 1; ; round++) {
            Map<String, JSONArray> byTable = collectDirtyByTable(db, api);
            if (byTable.isEmpty()) return; // нечего отправлять — либо с самого начала, либо всё уже вылечено

            boolean healed = false;
            for (JSONObject portion : chunkIntoPortions(byTable)) {
                String batchId = UUID.randomUUID().toString();
                SyncApiClient.HttpResult result = api.push(batchId, portion);

                if (result.status == 200) {
                    clearDirty(db, portion);
                    logPortionSent(portion, log);
                    continue; // следующая порция этого же раунда
                }

                JSONArray conflicts = result.status == 409 ? result.body.optJSONArray("conflicts") : null;
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
                    resolveAccountConflicts(db, conflicts);
                    String detail = "конфликт имени счёта — переклеено, отправляю повторно";
                    Log.w(TAG, "push: " + detail + " (раунд " + round + "): " + result.body);
                    log.healed(detail);
                    healed = true;
                    break;
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
                MissingReference missing = parseMissingReference(result.status, result.body);
                if (missing != null && redirty(db, missing.table, missing.uuid) > 0) {
                    String detail = "нет на сервере: " + missing.table + "/" + missing.uuid
                            + " — помечено заново, отправляю повторно";
                    Log.w(TAG, "push: missing_reference (" + missing.table + "/" + missing.uuid
                            + "), заново dirty (раунд " + round + "), повторяю прямо сейчас: " + result.body);
                    log.healed(detail);
                    healed = true;
                    break;
                }

                // Любой другой отказ — missing_reference без table/uuid (или без
                // соответствующей локальной строки)/duplicate_key (409),
                // invalid_row (400 — например, версия клиента новее/старше
                // протокола сервера), 500 и т.п. — не самовосстанавливающийся:
                // пользователь должен узнать, что часть изменений не долетела,
                // а не увидеть "Синхронизация завершена" с журналом, будто всё
                // отправлено (см. javadoc метода выше).
                throw new PushRejectedException(result.status, result.body);
            }

            if (!healed) return; // все порции этого раунда прошли — отправлять больше нечего

            if (round >= MAX_SELF_HEAL_ROUNDS) {
                // Не должно происходить (см. javadoc MAX_SELF_HEAL_ROUNDS) —
                // но зависнуть в цикле навсегда хуже, чем показать пользователю
                // честную ошибку. RuntimeException (не PushRejectedException,
                // у которой есть настоящий httpStatus/body с сервера — здесь
                // отказа СЕРВЕРА нет, это клиент сам себя останавливает) —
                // SyncRunner.runOnce ловит её тем же "максимум дебаг-информации"
                // путём, что и любой прочий неожиданный сбой.
                throw new IllegalStateException("push: самолечение не сошлось за " + MAX_SELF_HEAL_ROUNDS
                        + " раундов — похоже на зацикленный конфликт, останавливаюсь");
            }
        }
    }

    /** Раскрывает "как есть" в {@link SyncRunner#runOnce} — см. её javadoc. */
    static final class PushRejectedException extends RuntimeException {
        final int httpStatus;

        PushRejectedException(int httpStatus, JSONObject body) {
            super("HTTP " + httpStatus + ": " + body);
            this.httpStatus = httpStatus;
        }
    }

    /** См. {@link #parseMissingReference}. */
    static final class MissingReference {
        final String table;
        final String uuid;

        MissingReference(String table, String uuid) {
            this.table = table;
            this.uuid = uuid;
        }
    }

    /**
     * Чистая разборка тела 409-ответа {@code missing_reference} (см.
     * sync_repo.cpp, push_batch) — без обращения к БД/сети, только JSON, ради
     * юнит-тестируемости (см. SyncEngineMissingReferenceTest). {@code null},
     * если тело не такой формы и самовосстановиться нечем:
     * <ul>
     *   <li>статус не 409 или {@code error} не {@code "missing_reference"} —
     *   это вообще другой отказ (см. вызывающий код, {@code pushDirty});</li>
     *   <li>{@code table}/{@code uuid} отсутствуют — сервер не смог разобрать
     *   свою же ошибку (например, составной внешний ключ, см.
     *   sync_repo.cpp, {@code parse_missing_reference}) — старое поведение,
     *   без изменений, до этой правки сервер вообще не присылал эти поля;</li>
     *   <li>{@code table} — не одна из {@link #TABLE_ORDER} — сервер (или
     *   кто угодно между ним и клиентом) прислал имя, для которого клиент
     *   НЕ должен выполнять {@code UPDATE <имя> SET dirty=1} — тут нет
     *   доверенного соединения с базой данных сервера, а есть только текст
     *   HTTP-ответа, который нельзя подставлять в SQL не проверив.</li>
     * </ul>
     */
    static MissingReference parseMissingReference(int httpStatus, JSONObject body) {
        if (httpStatus != 409 || body == null) return null;
        if (!"missing_reference".equals(body.optString("error", null))) return null;
        String table = body.optString("table", null);
        String uuid = body.optString("uuid", null);
        if (table == null || uuid == null || uuid.isEmpty()) return null;
        for (String known : TABLE_ORDER) {
            if (known.equals(table)) return new MissingReference(table, uuid);
        }
        return null;
    }

    /**
     * Возвращает эту одну строку в очередь push — {@code table} уже сверена
     * со {@link #TABLE_ORDER} в {@link #parseMissingReference}, так что имя
     * таблицы здесь безопасно подставлять напрямую. {@code AND dirty<>1} —
     * не обязателен для корректности (WHERE по uuid и так задевает не более
     * одной строки), но держит поведение симметричным {@link #createDirtyTrigger}
     * в WalletDbHelper: UPDATE, который реально ничего не меняет, не должен
     * молча идти в лог как "нашли и починили". Возвращает число задетых
     * строк — 0 означает "такого uuid в этой таблице нет вообще", тогда
     * вызывающий код не считает случай самовосстановившимся.
     */
    private static int redirty(SQLiteDatabase db, String table, String uuid) {
        ContentValues cv = new ContentValues();
        cv.put("dirty", 1);
        String local = localUuid(db, table, uuid);
        if (local == null) return 0;
        return db.update(table, cv, "uuid=? AND dirty<>1", new String[]{local});
    }

    /**
     * Записывает в {@code log} "Отправлено"/"Отправлено (удаление)" по
     * КАЖДОЙ строке уже подтверждённой (HTTP 200) порции — вызывать только
     * после успеха, не во время сборки строк (см. javadoc
     * {@link #pushDirty}, почему). Тип строки — по имени таблицы (тот же
     * человекочитаемый набор, что раньше проставляли лямбды
     * {@link #collectDirtyByTable}); {@code account_operating}/
     * {@code operation_link}/{@code currency_conversion} сознательно не
     * упомянуты — слишком технические, всегда сопровождают сиблинга (та же
     * причина, что и раньше).
     */
    private static void logPortionSent(JSONObject portion, SyncLog log) throws JSONException {
        Map<String, String> displayType = new HashMap<>();
        displayType.put("icon", "Иконка");
        displayType.put("currency", "Валюта");
        displayType.put("currency_pair", "Валютная пара");
        displayType.put("currency_pair_rate", "Курс");
        displayType.put("account", "Счёт");
        displayType.put("operation", "Операция");

        Iterator<String> tables = portion.keys();
        while (tables.hasNext()) {
            String table = tables.next();
            String type = displayType.get(table);
            if (type == null) continue;
            JSONArray rows = portion.getJSONArray(table);
            for (int i = 0; i < rows.length(); i++) {
                boolean deleted = rows.getJSONObject(i).optBoolean("deleted", false);
                log.push(type, deleted ? "Отправлено (удаление)" : "Отправлено");
            }
        }
    }

    private interface RowBuilder {
        JSONObject build(Cursor c) throws JSONException;
    }

    private static JSONArray collect(SQLiteDatabase db, String sql, RowBuilder builder) throws JSONException {
        JSONArray rows = new JSONArray();
        Cursor c = db.rawQuery(sql, null);
        try {
            while (c.moveToNext()) rows.put(builder.build(c));
        } finally {
            c.close();
        }
        return rows;
    }

    private static void putOrdered(Map<String, JSONArray> byTable, String table, JSONArray rows) {
        if (rows.length() > 0) byTable.put(table, rows);
    }

    /**
     * Режет {@code byTable} (уже в порядке {@link #TABLE_ORDER} — так его
     * строит {@link #collectDirtyByTable}, {@code LinkedHashMap} сохраняет
     * порядок вставки) на порции по НЕ БОЛЕЕ {@link #MAX_PUSH_BATCH_ROWS}
     * строк суммарно. Таблица крупнее одной порции режется сама по себе на
     * несколько подряд идущих порций; соседние МЕЛКИЕ таблицы, наоборот,
     * могут ужиться в одной порции — граница режется по факту, а не заранее
     * "одна порция — одна таблица". Порядок таблиц между порциями не
     * перемешивается: следующая таблица начинает заполняться (в текущей ИЛИ
     * в новой порции, если места не осталось) только когда ВСЕ строки
     * предыдущей уже распределены — см. {@link #pushDirty}, почему порядок
     * между порциями важен так же, как порядок таблиц внутри одной.
     */
    private static List<JSONObject> chunkIntoPortions(Map<String, JSONArray> byTable) throws JSONException {
        List<JSONObject> portions = new ArrayList<>();
        JSONObject current = new JSONObject();
        int currentRows = 0;

        for (Map.Entry<String, JSONArray> entry : byTable.entrySet()) {
            String table = entry.getKey();
            JSONArray rows = entry.getValue();
            int offset = 0;
            while (offset < rows.length()) {
                if (currentRows >= MAX_PUSH_BATCH_ROWS) {
                    portions.add(current);
                    current = new JSONObject();
                    currentRows = 0;
                }
                int take = Math.min(MAX_PUSH_BATCH_ROWS - currentRows, rows.length() - offset);
                current.put(table, sliceArray(rows, offset, take));
                currentRows += take;
                offset += take;
            }
        }
        if (currentRows > 0) portions.add(current);
        return portions;
    }

    private static JSONArray sliceArray(JSONArray rows, int offset, int count) throws JSONException {
        JSONArray slice = new JSONArray();
        for (int i = 0; i < count; i++) slice.put(rows.get(offset + i));
        return slice;
    }

    /**
     * Собирает все dirty-строки по 8 синхронизируемым таблицам (без
     * {@code icon} — он собирается тоже, просто отдельно, см. ниже) в
     * порядке {@link #TABLE_ORDER} — {@code LinkedHashMap} сохраняет именно
     * этот порядок вставки, на нём строится нарезка на порции (см.
     * {@link #chunkIntoPortions}). Раньше это была одна {@code changes} на
     * один push-батч (README wallet-api, "Вся changes — одна транзакция") —
     * теперь так же одна транзакция, но НА КАЖДУЮ ПОРЦИЮ, не на весь
     * dirty-набор разом (см. {@link #pushDirty}).
     */
    private static Map<String, JSONArray> collectDirtyByTable(SQLiteDatabase db, SyncApiClient api)
            throws JSONException, IOException {
        Map<String, JSONArray> byTable = new LinkedHashMap<>();

        putOrdered(byTable, "icon", collectDirtyIcons(db, api));

        putOrdered(byTable, "currency", collect(db,
                "SELECT uuid, name, iso_code, icon_id, symbol, precision, deleted_at FROM currency WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("name", c.getString(1));
                    row.put("iso_code", c.getString(2));
                    row.put("icon_uuid", uuidById(db, "icon", c.getLong(3)));
                    row.put("symbol", c.getString(4));
                    row.put("precision", c.getInt(5));
                    row.put("deleted", !c.isNull(6));
                    return row;
                }));

        putOrdered(byTable, "currency_pair", collect(db,
                "SELECT uuid, symbol, base_currency_id, quote_currency_id, precision, deleted_at "
                        + "FROM currency_pair WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("symbol", c.getString(1));
                    row.put("base_currency_uuid", uuidById(db, "currency", c.getLong(2)));
                    row.put("quote_currency_uuid", uuidById(db, "currency", c.getLong(3)));
                    row.put("precision", c.getInt(4));
                    row.put("deleted", !c.isNull(5));
                    return row;
                }));

        putOrdered(byTable, "currency_pair_rate", collect(db,
                "SELECT uuid, currency_pair_id, date, rate, deleted_at FROM currency_pair_rate WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("currency_pair_uuid", uuidById(db, "currency_pair", c.getLong(1)));
                    // Локальная колонка — DATETIME (первые 10 символов "yyyy-MM-dd",
                    // время дальше не пишется никаким актуальным кодом — см.
                    // DateUtils.DateToDateStr/WalletFillData.InsertCurrencyPairRate),
                    // но на старых строках (до этой правки) время могло сохраниться.
                    // Сервер хранит "date" без времени вообще (см.
                    // wallet-api/schema/table/currency_pair_rate.sql) — обрезаем здесь
                    // тоже, а не только полагаемся на неявный CAST на стороне
                    // Postgres: два локальных дубля одного дня с разным временем
                    // иначе бьются об uq_currency_pair_rate_ledger при push.
                    String rawDate = c.getString(2);
                    row.put("date", rawDate.length() > 10 ? rawDate.substring(0, 10) : rawDate);
                    row.put("rate", c.getString(3)); // текстом, тот же довод, что у amount выше
                    row.put("deleted", !c.isNull(4));
                    return row;
                }));

        putOrdered(byTable, "currency_conversion", collect(db,
                "SELECT uuid, from_currency_id, to_currency_id, num, currency_pair_id, is_reverse, deleted_at "
                        + "FROM currency_conversion WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("from_currency_uuid", uuidById(db, "currency", c.getLong(1)));
                    row.put("to_currency_uuid", uuidById(db, "currency", c.getLong(2)));
                    row.put("num", c.getInt(3));
                    row.put("currency_pair_uuid", uuidById(db, "currency_pair", c.getLong(4)));
                    row.put("is_reverse", c.getInt(5) != 0);
                    row.put("deleted", !c.isNull(6));
                    return row;
                }));

        putOrdered(byTable, "account", collect(db,
                // system_kind/kind — v75/v76 (см. WalletContract.AccountEntry) — добавлены в серверный
                // TableSpec/схему коммитом "wallet share step1" вместе с этим SELECT'ом; сервер читает
                // каждую колонку spec.columns через json::at() без дефолта (wallet-api/src/
                // sync_repo.cpp), поэтому отсутствие ключа здесь — не "0 по умолчанию", а падение
                // "key 'system_kind' not found" на push.
                "SELECT uuid, name, comment, icon_id, is_archived, system_kind, kind, deleted_at FROM account WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("name", c.getString(1));
                    row.put("comment", c.getString(2));
                    row.put("icon_uuid", uuidById(db, "icon", c.getLong(3)));
                    row.put("is_archived", c.getInt(4) != 0);
                    row.put("system_kind", c.getInt(5));
                    row.put("kind", c.getInt(6));
                    row.put("deleted", !c.isNull(7));
                    return row;
                }));

        putOrdered(byTable, "account_operating", collect(db,
                "SELECT uuid, currency_id, deleted_at FROM account_operating WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    // uuid — тот же, что у account того же счёта (инвариант,
                    // см. WalletDbHelper.backfillAccountOperatingUuid).
                    row.put("uuid", c.getString(0));
                    row.put("currency_uuid", uuidById(db, "currency", c.getLong(1)));
                    row.put("deleted", !c.isNull(2));
                    return row;
                }));

        putOrdered(byTable, "operation", collect(db,
                "SELECT uuid, date, acc_fr_id, acc_to_id, amount, comment, deleted_at FROM operation WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("date", c.getString(1));
                    row.put("acc_fr_uuid", uuidById(db, "account", c.getLong(2)));
                    row.put("acc_to_uuid", uuidById(db, "account", c.getLong(3)));
                    // getString, не getDouble — текстовое представление
                    // SQLite (сохраняет то, что реально лежит в колонке),
                    // а не Double.toString (см. README wallet-api/schema,
                    // "Деньги — numeric" — суммы протокол несёт строками).
                    row.put("amount", c.getString(4));
                    row.put("comment", c.getString(5));
                    row.put("deleted", !c.isNull(6));
                    return row;
                }));

        putOrdered(byTable, "operation_link", collect(db,
                // type (v77, OperationItem.LinkType) — тот же класс пропуска, что был у account.system_kind/
                // kind (см. комментарий там): сервер требует ключ через json::at() без дефолта, DEFAULT 0
                // в самой колонке для push/pull JSON значения не имеет.
                "SELECT uuid, main_id, link_id, type, deleted_at FROM operation_link WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("main_uuid", uuidById(db, "operation", c.getLong(1)));
                    row.put("link_uuid", uuidById(db, "operation", c.getLong(2)));
                    row.put("type", c.getInt(3));
                    row.put("deleted", !c.isNull(4));
                    return row;
                }));

        return byTable;
    }

    /**
     * Отдельно от {@link #collect} — иконке нужна сеть (заливка содержимого,
     * см. javadoc класса, "Иконки") ПРЕЖДЕ чем строка попадёт в {@code
     * changes}. Сырые поля читаются и КУРСОР ЗАКРЫВАЕТСЯ первым же циклом —
     * сетевые вызовы идут уже после, не с открытым {@code Cursor} поверх
     * {@code db} (тот же довод, что и у {@link #prefetchNewIconBlobs} на
     * pull-стороне, только про SELECT, а не про целую транзакцию).
     */
    private static JSONArray collectDirtyIcons(SQLiteDatabase db, SyncApiClient api)
            throws JSONException, IOException {
        List<Object[]> rows = new ArrayList<>(); // {uuid, name, image, iconType, deleted}
        Cursor c = db.rawQuery(
                "SELECT uuid, name, image, icon_type, deleted_at FROM icon WHERE dirty=1", null);
        try {
            while (c.moveToNext()) {
                rows.add(new Object[]{c.getString(0), c.getString(1), c.getBlob(2), c.getInt(3),
                        !c.isNull(4)});
            }
        } finally {
            c.close();
        }

        JSONArray result = new JSONArray();
        for (Object[] r : rows) {
            SyncApiClient.HttpResult uploaded = api.uploadBlob((byte[]) r[2]);
            if (uploaded.status != 200) {
                throw new IOException("POST /sync/blobs -> HTTP " + uploaded.status);
            }
            JSONObject row = new JSONObject();
            row.put("uuid", r[0]);
            row.put("name", r[1]);
            row.put("content_sha256", uploaded.body.getString("sha256"));
            row.put("icon_type", r[3]);
            row.put("deleted", (Boolean) r[4]);
            result.put(row);
        }
        return result;
    }

    /**
     * Сбрасывает {@code dirty} только у строк, реально вошедших в успешно
     * применённый батч (по их {@code uuid} из уже отправленного {@code
     * changes}) — не бланковым {@code WHERE dirty=1}: пока ответ шёл обратно,
     * пользователь мог успеть отредактировать что-то ещё, и такая строка
     * обязана остаться dirty для следующего цикла.
     */
    private static void clearDirty(SQLiteDatabase db, JSONObject changes) throws JSONException {
        db.beginTransaction();
        try {
            Iterator<String> tables = changes.keys();
            while (tables.hasNext()) {
                String table = tables.next();
                JSONArray rows = changes.getJSONArray(table);
                for (int i = 0; i < rows.length(); i++) {
                    String uuid = rows.getJSONObject(i).getString("uuid");
                    ContentValues cv = new ContentValues();
                    cv.put("dirty", 0);
                    db.update(table, cv, "uuid=?", new String[]{uuid});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 409 duplicate_name (см. README wallet-api, "Конфликт имени счёта") —
     * переклеивает identity локального счёта на выжившую строку.
     * Единственный сценарий переписывания локальных ссылок
     * (org/docs/TODO_mobile_sync.md, Фаза 3, п. 5): локальный {@code _id} и
     * всё, что на него ссылается ({@code operation.acc_fr_id/acc_to_id} и
     * т.п.), не трогаем — они и так указывают на верную строку, меняется
     * только то, каким {@code uuid} она назовётся при следующей отправке.
     */
    private static void resolveAccountConflicts(SQLiteDatabase db, JSONArray conflicts) throws JSONException {
        for (int i = 0; i < conflicts.length(); i++) {
            JSONObject conflict = conflicts.getJSONObject(i);
            if (!"account".equals(conflict.optString("table"))
                    || !"duplicate_name".equals(conflict.optString("reason"))) {
                continue; // сегодня единственная осмысленная причина, см. sync_repo.cpp
            }
            String ourUuid = conflict.optString("uuid", null);
            String survivorUuid = conflict.optString("existing_uuid", null);
            // Защитно: старый сервер без поля "uuid" в конфликте (до фикса
            // sync_repo.hpp/Conflict) — нечем понять, какую строку переклеивать.
            if (ourUuid == null || survivorUuid == null) continue;

            ContentValues cv = new ContentValues();
            cv.put("uuid", survivorUuid);
            int updated = db.update("account", cv, "uuid=?", new String[]{ourUuid});
            if (updated == 0) {
                Log.w(TAG, "resolveAccountConflicts: локальный account uuid=" + ourUuid
                        + " не найден (уже переклеен раньше?)");
                continue;
            }

            // account_operating.uuid обязан остаться равным account.uuid
            // (см. WalletDbHelper.backfillAccountOperatingUuid) — переклеиваем
            // тем же ключом, пока он ещё ищется по старому uuid.
            ContentValues cvOp = new ContentValues();
            cvOp.put("uuid", survivorUuid);
            db.update("account_operating", cvOp, "uuid=?", new String[]{ourUuid});

            Log.i(TAG, "account uuid " + ourUuid + " переклеен на существующий " + survivorUuid
                    + " (конфликт имени счёта)");
        }
    }

    // ── PULL ─────────────────────────────────────────────────────────────────

    /**
     * Тянет {@code GET /sync/pull} постранично, пока оба потока не догонят
     * сервер (см. условие выхода ниже), применяя каждую страницу отдельной
     * транзакцией — курсоры сохраняются ТОЛЬКО после применения (см. javadoc
     * {@code AppSettings.getLedgerSyncSeq}), обрыв посреди страницы не теряет
     * её тихо, просто повторит на следующий вызов.
     */
    static void pullAll(SQLiteDatabase db, SyncApiClient api, Context ctx, SyncLog log)
            throws IOException, JSONException {
        // Строки, пропущенные до localUuid (ссылка на счёт с uuid без дефисов),
        // уже позади курсора — один раз тянем оба потока заново. Upsert по uuid
        // идемпотентен, а push уже прошёл, так что локальные правки не теряются.
        if (!AppSettings.isUuidFormatResyncDone(ctx)) {
            AppSettings.setLedgerSyncSeq(ctx, 0L);
            AppSettings.setReferenceSyncSeq(ctx, 0L);
            AppSettings.setUuidFormatResyncDone(ctx);
            log.healed("Повторная загрузка с сервера — подтягиваю пропущенные операции");
        }
        long ledgerSeq = AppSettings.getLedgerSyncSeq(ctx);
        long referenceSeq = AppSettings.getReferenceSyncSeq(ctx);
        boolean touchedAccounts = false;
        boolean touchedIcons = false;
        boolean touchedCurrencies = false;
        boolean touchedCurrencyPairs = false;

        // Кап на число страниц — защита от бага в курсорах/ответе сервера,
        // который иначе крутил бы цикл вечно (условие выхода ниже опирается
        // на то, что курсор реально продвигается).
        for (int page = 0; page < 10_000; page++) {
            SyncApiClient.PullResult result = api.pull(ledgerSeq, referenceSeq, 500);

            // Леджер сменился (другой user_id при том же email, инвайт) —
            // курсор от старого леджера может оказаться и МЕНЬШЕ головы
            // нового, тогда сервер reset не поставит и часть истории молча
            // пропадёт. Страницу выбрасываем и тянем поток с нуля.
            String ledgerId = result.ledger.ledgerId;
            if (ledgerId != null && !ledgerId.equals(AppSettings.getSyncLedgerId(ctx))) {
                boolean knownBefore = AppSettings.getSyncLedgerId(ctx) != null;
                AppSettings.setSyncLedgerId(ctx, ledgerId);
                if (knownBefore && ledgerSeq != 0) {
                    Log.w(TAG, "pull: леджер сменился на " + ledgerId + " — курсор " + ledgerSeq + " сброшен");
                    log.healed("Сервер сменил кошелёк — загружаю его заново");
                    ledgerSeq = 0;
                    AppSettings.setLedgerSyncSeq(ctx, 0L);
                    continue;
                }
            }
            if (result.ledger.reset || result.reference.reset) {
                // Курсор был дальше головы потока (реальный случай 2026-09-30:
                // 146 при голове 13 — записи бота не доезжали). Сервер уже
                // отдал поток с нуля — применяем как обычно.
                Log.w(TAG, "pull: курсор впереди сервера (ledger=" + ledgerSeq + ", reference="
                        + referenceSeq + ") — поток начат заново");
                log.healed("Позиция синхронизации разошлась с сервером — загружаю данные заново");
            }

            boolean advanced = result.ledger.nextSeq != ledgerSeq || result.reference.nextSeq != referenceSeq;
            if (!advanced) break; // догнали сервер — оба потока пусты

            if (touchesAccounts(result.ledger.changes) || touchesAccounts(result.reference.changes)) {
                touchedAccounts = true;
            }
            if (result.ledger.changes.has("icon") || result.reference.changes.has("icon")) {
                touchedIcons = true;
            }
            if (result.ledger.changes.has("currency") || result.reference.changes.has("currency")) {
                touchedCurrencies = true;
            }
            if (touchesCurrencyPairs(result.ledger.changes) || touchesCurrencyPairs(result.reference.changes)) {
                touchedCurrencyPairs = true;
            }

            applyPage(db, result.ledger.changes, result.reference.changes, api, log);

            ledgerSeq = result.ledger.nextSeq;
            referenceSeq = result.reference.nextSeq;
            AppSettings.setLedgerSyncSeq(ctx, ledgerSeq);
            AppSettings.setReferenceSyncSeq(ctx, referenceSeq);
        }

        if (touchedAccounts) {
            // Singleton.accounts — кэш в памяти, сам себя не перечитывает
            // (см. Singleton.reloadAccounts) — без этого счета, пришедшие с
            // другого устройства, не появились бы в UI до перезапуска
            // приложения.
            Singleton.instance.reloadAccounts(ctx);
        }
        if (touchedIcons) {
            Singleton.instance.reloadIconList(ctx);
        }
        // currencyList — ДО currencyPairList: CurrencyPair держит ссылки на
        // объекты Currency, полученные из currencyList на момент загрузки
        // пары (см. WalletDbLoader.LoadCurrencyPairList) — если валюты
        // изменились, пары обязаны перечитаться ПОСЛЕ, иначе будут держать
        // уже устаревшие объекты Currency.
        if (touchedCurrencies) {
            Singleton.instance.reloadCurrencyList(ctx);
        }
        if (touchedCurrencies || touchedCurrencyPairs) {
            Singleton.instance.reloadCurrencyPairList(ctx);
        }
    }

    private static boolean touchesAccounts(JSONObject changes) {
        return changes.has("account") || changes.has("account_operating");
    }

    private static boolean touchesCurrencyPairs(JSONObject changes) {
        return changes.has("currency_pair") || changes.has("currency_pair_rate");
    }

    private static Map<String, JSONArray> mergeChanges(JSONObject a, JSONObject b) throws JSONException {
        Map<String, JSONArray> merged = new LinkedHashMap<>();
        for (JSONObject src : new JSONObject[]{a, b}) {
            Iterator<String> keys = src.keys();
            while (keys.hasNext()) {
                String table = keys.next();
                JSONArray rows = src.getJSONArray(table);
                JSONArray existing = merged.get(table);
                if (existing == null) {
                    merged.put(table, rows);
                } else {
                    for (int i = 0; i < rows.length(); i++) existing.put(rows.get(i));
                }
            }
        }
        return merged;
    }

    private static void applyPage(SQLiteDatabase db, JSONObject ledgerChanges, JSONObject referenceChanges,
                                   SyncApiClient api, SyncLog log) throws JSONException, IOException {
        Map<String, JSONArray> merged = mergeChanges(ledgerChanges, referenceChanges);
        // Скачивание содержимого НОВЫХ иконок — ДО транзакции (см. javadoc
        // класса, "Иконки") — держать sqlite-транзакцию открытой на время
        // сетевого запроса означало бы блокировать доступ к БД для всего
        // остального приложения на непредсказуемое время.
        Map<String, byte[]> newIconBlobs = prefetchNewIconBlobs(db, merged.get("icon"), api);
        db.beginTransaction();
        try {
            // Страховка поверх TABLE_ORDER, не замена ему (см. javadoc
            // класса) — переносит проверку внешних ключей на COMMIT этой
            // транзакции вместо каждого отдельного INSERT.
            db.execSQL("PRAGMA defer_foreign_keys = ON");
            for (String table : TABLE_ORDER) {
                JSONArray rows = merged.get(table);
                if (rows == null) continue;
                for (int i = 0; i < rows.length(); i++) {
                    applyRow(db, table, rows.getJSONObject(i), newIconBlobs, log);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Скачивает содержимое ТОЛЬКО тех иконок из страницы, которых ещё нет
     * локально (найдено по uuid) — для уже существующих байты не
     * перекачиваются (см. javadoc класса, "Иконки"). Дедуп по
     * {@code content_sha256}: несколько новых иконок в одной странице могут
     * делить одно и то же содержимое, скачивать его больше одного раза
     * незачем.
     */
    private static Map<String, byte[]> prefetchNewIconBlobs(SQLiteDatabase db, JSONArray iconRows,
                                                              SyncApiClient api)
            throws JSONException, IOException {
        Map<String, byte[]> cache = new HashMap<>();
        if (iconRows == null) return cache;
        for (int i = 0; i < iconRows.length(); i++) {
            JSONObject row = iconRows.getJSONObject(i);
            if (localUuid(db, "icon", row.getString("uuid")) != null) continue;
            String sha256 = row.getString("content_sha256");
            if (cache.containsKey(sha256)) continue;
            cache.put(sha256, api.downloadBlob(sha256));
        }
        return cache;
    }

    private static void applyRow(SQLiteDatabase db, String table, JSONObject row,
                                  Map<String, byte[]> newIconBlobs, SyncLog log) throws JSONException {
        switch (table) {
            case "currency": applyCurrency(db, row, log); break;
            case "currency_pair": applyCurrencyPair(db, row, log); break;
            case "currency_pair_rate": applyCurrencyPairRate(db, row, log); break;
            case "currency_conversion": applyCurrencyConversion(db, row); break;
            case "account": applyAccount(db, row, log); break;
            case "account_operating": applyAccountOperating(db, row); break;
            case "operation": applyOperation(db, row, log); break;
            case "operation_link": applyOperationLink(db, row); break;
            case "icon": applyIcon(db, row, newIconBlobs, log); break;
            default:
                // Неизвестная клиенту таблица — сервер может быть новее
                // этого релиза (тот же принцип, что у push_batch на
                // сервере для незнакомого имени) — игнор, не ошибка.
        }
    }

    /**
     * Для НОВОЙ (по uuid) иконки содержимое уже должно быть в
     * {@code newIconBlobs} (см. {@link #prefetchNewIconBlobs}) — если его
     * там почему-то нет (сетевая ошибка при скачивании прервала бы всю
     * страницу раньше, см. {@link #applyPage}, так что это защитный, а не
     * ожидаемый путь), строка пропускается. Для уже существующей — байты не
     * перекачиваются вообще, обновляются только метаданные (см. javadoc
     * класса, "Иконки", почему смена содержимого не поддерживается).
     */
    private static void applyIcon(SQLiteDatabase db, JSONObject row, Map<String, byte[]> newIconBlobs,
                                   SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        String local = localUuid(db, "icon", uuid);
        boolean exists = local != null;

        ContentValues cv = new ContentValues();
        cv.put("name", row.getString("name"));
        cv.put("icon_type", row.getInt("icon_type"));
        applySyncColumns(cv, row);

        try {
            if (exists) {
                updateFromServer(db, "icon", cv, local);
            } else {
                byte[] image = newIconBlobs.get(row.getString("content_sha256"));
                if (image == null) {
                    Log.w(TAG, "icon " + uuid + ": содержимое не предзагружено — строка пропущена");
                    return;
                }
                cv.put("image", image);
                cv.put("uuid", uuid);
                db.insertOrThrow("icon", null, cv);
            }
            log.pull("Иконка", rowIsDeleted(row) ? "Удалено" : (exists ? "Изменено" : "Добавлено"));
        } catch (SQLiteException e) {
            // uq_icon_name и т.п. — тот же класс проблемы, что у upsertByUuid
            // (см. javadoc класса, "Что ещё сознательно не решено").
            Log.w(TAG, "upsert icon uuid=" + uuid + " не удался: " + e.getMessage());
        }
    }

    private static void applyCurrency(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long iconId = findIdByUuid(db, "icon", row.getString("icon_uuid"));
        if (iconId == null) {
            Log.w(TAG, "currency " + uuid + ": icon_uuid ещё не известен локально — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("name", row.getString("name"));
        cv.put("iso_code", row.getString("iso_code"));
        cv.put("icon_id", iconId);
        cv.put("symbol", row.getString("symbol"));
        cv.put("precision", row.getInt("precision"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "currency", uuid, cv);
        if (wasInsert == null) return;
        log.pull("Валюта", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
    }

    private static void applyCurrencyPair(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long baseId = findIdByUuid(db, "currency", row.getString("base_currency_uuid"));
        Long quoteId = findIdByUuid(db, "currency", row.getString("quote_currency_uuid"));
        if (baseId == null || quoteId == null) {
            Log.w(TAG, "currency_pair " + uuid + ": валюта ещё не синхронизирована — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("symbol", row.getString("symbol"));
        cv.put("base_currency_id", baseId);
        cv.put("quote_currency_id", quoteId);
        cv.put("precision", row.getInt("precision"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "currency_pair", uuid, cv);
        if (wasInsert == null) return;
        log.pull("Валютная пара", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
    }

    private static void applyCurrencyPairRate(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"));
        if (pairId == null) {
            Log.w(TAG, "currency_pair_rate " + uuid + ": пара ещё не синхронизирована — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("currency_pair_id", pairId);
        // "date" на сервере — тип date (не timestamp), to_jsonb сериализует
        // его уже как "yyyy-MM-dd" — ровно формат, которого ждёт локальная
        // колонка (DateUtils.formatDateOnly), конверсия не нужна.
        cv.put("date", row.getString("date"));
        cv.put("rate", row.getString("rate")); // numeric-как-строка, не парсим в double
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "currency_pair_rate", uuid, cv);
        if (wasInsert == null) return;
        log.pull("Курс", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
    }

    private static void applyCurrencyConversion(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long fromId = findIdByUuid(db, "currency", row.getString("from_currency_uuid"));
        Long toId = findIdByUuid(db, "currency", row.getString("to_currency_uuid"));
        Long pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"));
        if (fromId == null || toId == null || pairId == null) {
            Log.w(TAG, "currency_conversion " + uuid + ": валюта/пара ещё не синхронизированы — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("from_currency_id", fromId);
        cv.put("to_currency_id", toId);
        cv.put("num", row.getInt("num"));
        cv.put("currency_pair_id", pairId);
        cv.put("is_reverse", row.getBoolean("is_reverse") ? 1 : 0);
        applySyncColumns(cv, row);
        upsertByUuid(db, "currency_conversion", uuid, cv);
    }

    private static void applyAccount(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long iconId = findIdByUuid(db, "icon", row.getString("icon_uuid"));
        if (iconId == null) {
            Log.w(TAG, "account " + uuid + ": icon_uuid ещё не известен локально (push иконок не "
                    + "реализован, см. wallet-api/README.md) — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("name", row.getString("name"));
        cv.put("comment", row.getString("comment"));
        cv.put("icon_id", iconId);
        cv.put("is_archived", row.getBoolean("is_archived") ? 1 : 0);
        // system_kind/kind — та же пара колонок, что и в push-стороне (collectDirtyByTable) выше;
        // без них строка «Transfer»/«Transfer commission», пришедшая с другого устройства, теряла
        // бы свою системность локально, а обычный счёт/категория — признак кошелёк/категория.
        cv.put("system_kind", row.getInt("system_kind"));
        cv.put("kind", row.getInt("kind"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "account", uuid, cv);
        if (wasInsert == null) return;
        log.pull("Счёт", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
    }

    /**
     * Особый случай, не {@link #upsertByUuid}: {@code _id} НЕ автоинкремент,
     * обязан совпасть с {@code account._id} того же счёта (общий первичный
     * ключ, см. {@code WalletDbHelper.CreateAccountOperatingEntry}), а сам
     * {@code uuid} этой строки равен {@code account.uuid} (инвариант, см.
     * {@code WalletDbHelper.backfillAccountOperatingUuid}) — поэтому
     * родительский счёт ищем ПО ТОМУ ЖЕ uuid, что пришёл в этой строке, а не
     * по отдельному полю ссылки (его в протоколе и нет, см.
     * wallet-api/schema/table/account_operating.sql).
     */
    private static void applyAccountOperating(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long accountId = findIdByUuid(db, "account", uuid);
        if (accountId == null) {
            Log.w(TAG, "account_operating " + uuid + ": account ещё не применён — строка пропущена "
                    + "(нарушен порядок применения таблиц?)");
            return;
        }
        Long currencyId = findIdByUuid(db, "currency", row.getString("currency_uuid"));
        if (currencyId == null) {
            Log.w(TAG, "account_operating " + uuid + ": валюта ещё не синхронизирована — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("currency_id", currencyId);
        applySyncColumns(cv, row);
        try {
            String local = localUuid(db, "account_operating", uuid);
            if (local != null) {
                updateFromServer(db, "account_operating", cv, local);
            } else {
                cv.put("_id", accountId);
                // Инвариант "uuid как у account того же счёта" — в его локальном написании.
                cv.put("uuid", localUuid(db, "account", uuid));
                db.insertOrThrow("account_operating", null, cv);
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "upsert account_operating uuid=" + uuid + " не удался: " + e.getMessage());
        }
    }

    private static void applyOperation(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long accFr = findIdByUuid(db, "account", row.getString("acc_fr_uuid"));
        Long accTo = findIdByUuid(db, "account", row.getString("acc_to_uuid"));
        if (accFr == null || accTo == null) {
            Log.w(TAG, "operation " + uuid + ": счёт ещё не синхронизирован — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("date", isoTimestampToLocalDbFormat(row.getString("date")));
        cv.put("acc_fr_id", accFr);
        cv.put("acc_to_id", accTo);
        cv.put("amount", row.getString("amount")); // numeric-как-строка
        cv.put("comment", row.getString("comment"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "operation", uuid, cv);
        if (wasInsert == null) return;
        log.pull("Операция", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
    }

    private static void applyOperationLink(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long mainId = findIdByUuid(db, "operation", row.getString("main_uuid"));
        Long linkId = findIdByUuid(db, "operation", row.getString("link_uuid"));
        if (mainId == null || linkId == null) {
            Log.w(TAG, "operation_link " + uuid + ": проводка ещё не синхронизирована — строка пропущена");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("main_id", mainId);
        cv.put("link_id", linkId);
        cv.put("type", row.getInt("type"));
        applySyncColumns(cv, row);
        upsertByUuid(db, "operation_link", uuid, cv);
    }

    // ── Общие мелочи ─────────────────────────────────────────────────────────

    /**
     * Единый upsert "нашли по uuid — обновили, не нашли — вставили" для ВСЕХ
     * синхронизируемых таблиц, кроме {@code account_operating} (см. её особый
     * случай выше) — включая таблицы с составным первичным ключом
     * ({@code operation_link}/{@code currency_pair_rate}/
     * {@code currency_conversion}): {@code cv} уже несёт все колонки,
     * образующие их PK, отдельно знать эти колонки здесь не нужно.
     *
     * @return {@code true} — строка только что вставлена (её раньше не было),
     *         {@code false} — уже была и обновлена, {@code null} — упало
     *         (конфликт с чужим уникальным индексом, строка пропущена).
     *         Вызывающие используют это, чтобы отличить в {@link SyncLog}
     *         "Добавлено" от "Изменено", не гадая заново тем же запросом.
     */
    private static Boolean upsertByUuid(SQLiteDatabase db, String table, String uuid, ContentValues cv) {
        try {
            String local = localUuid(db, table, uuid);
            boolean exists = local != null;
            if (exists) {
                updateFromServer(db, table, cv, local);
            } else {
                cv.put("uuid", uuid);
                db.insertOrThrow(table, null, cv);
            }
            return !exists;
        } catch (SQLiteException e) {
            // Столкновение с чужим уникальным индексом (currency.name/
            // iso_code, currency_pair.symbol, составной PK и т.п.) — для
            // этих таблиц (в отличие от account) сервер пока не диагностирует
            // дубль осмысленно и не даёт uuid, на который переклеиться (см.
            // javadoc класса) — пропускаем строку, а не роняем всю страницу.
            Log.w(TAG, "upsert " + table + " uuid=" + uuid + " не удался: " + e.getMessage());
            return null;
        }
    }

    /**
     * UPDATE строки данными с сервера так, чтобы она осталась {@code dirty=0}.
     * <p>
     * Триггер {@code trg_<table>_dirty} (WalletDbHelper.createDirtyTrigger) срабатывает
     * при {@code NEW.dirty = OLD.dirty}. Явный {@code SET dirty=0} по строке, где и так
     * был 0, под это условие попадает — и триггер помечал строку dirty=1. Так каждая
     * обновлённая с сервера строка уходила обратно в следующий push, сервер выдавал ей
     * новый seq, pull приносил её снова — по кругу. Поэтому в два шага через
     * {@code dirty=2}: и 0/1→2, и 2→0 — смена значения, триггер молчит. Оба шага в
     * транзакции страницы (applyPage), промежуточное 2 наружу не видно.
     */
    static void updateFromServer(SQLiteDatabase db, String table, ContentValues cv, String localUuid) {
        cv.put("dirty", 2);
        db.update(table, cv, "uuid=?", new String[]{localUuid});
        ContentValues clean = new ContentValues();
        clean.put("dirty", 0);
        db.update(table, clean, "uuid=?", new String[]{localUuid});
    }

    /**
     * uuid строки так, как он записан ЗДЕСЬ, или {@code null}, если строки нет.
     * Локально uuid бывает в двух написаниях: {@code DEFAULT lower(hex(randomblob(16)))}
     * даёт 32 hex-символа без дефисов (счета, справочники из сида), а
     * {@code UUID.randomUUID()} — каноническую форму с дефисами. Сервер хранит
     * тип {@code uuid} и в pull ВСЕГДА отдаёт каноническую форму, поэтому точное
     * сравнение строк не находило, например, счёт без дефисов — и операция,
     * записанная ботом на этот счёт, молча пропускалась. Ищем оба написания;
     * {@code IN} по колонке uuid по-прежнему идёт по индексу.
     */
    static String localUuid(SQLiteDatabase db, String table, String uuid) {
        if (uuid == null) return null;
        String plain = uuid.toLowerCase(Locale.ROOT).replace("-", "");
        String dashed = plain.length() == 32
                ? plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16)
                        + "-" + plain.substring(16, 20) + "-" + plain.substring(20)
                : plain;
        Cursor c = db.rawQuery("SELECT uuid FROM " + table + " WHERE uuid IN (?, ?, ?) LIMIT 1",
                new String[]{uuid, plain, dashed});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    private static void applySyncColumns(ContentValues cv, JSONObject row) throws JSONException {
        cv.put("dirty", 0); // явно, не полагаясь на DEFAULT — форсит триггер иначе (см. WalletDbHelper.createDirtyTrigger)
        cv.put("deleted_at", parseDeletedAtMillisOrNull(row));
        cv.put("updated_by_device", row.isNull("updated_by") ? null : row.getString("updated_by"));
    }

    /** Раз-строка ли это ("deleted_at" не null) — для {@link SyncLog}, различить "Удалено" от прочего. */
    private static boolean rowIsDeleted(JSONObject row) throws JSONException {
        return row.has("deleted_at") && !row.isNull("deleted_at");
    }

    private static Long parseDeletedAtMillisOrNull(JSONObject row) throws JSONException {
        if (!row.has("deleted_at") || row.isNull("deleted_at")) return null;
        String iso = row.getString("deleted_at");
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            // Диагностическая колонка (см. SYNC_COLUMNS_DDL в WalletDbHelper) —
            // ни один фильтр в приложении не смотрит на точное значение,
            // только на NULL/NOT NULL, поэтому неразобранный формат не
            // блокирует применение строки, просто теряет точность диагностики.
            Log.w(TAG, "не удалось разобрать deleted_at=" + iso + ", беру текущее время");
            return System.currentTimeMillis();
        }
    }

    /**
     * {@code operation.date} на сервере — {@code timestamp} БЕЗ часового
     * пояса (см. wallet-api/schema/table/operation.sql), {@code to_jsonb}
     * сериализует его как {@code "2026-01-15T10:00:00"} — переводим в
     * {@code "yyyy-MM-dd HH:mm"}, формат, которого ждёт остальной код
     * приложения ({@link com.example.wallettransactions.DateUtils#formatDB}).
     * Секунды теряются сознательно — локальный формат их не несёт вообще.
     */
    private static String isoTimestampToLocalDbFormat(String iso) {
        int t = iso.indexOf('T');
        if (t < 0) return iso; // защитно — уже в ожидаемом виде
        String datePart = iso.substring(0, t);
        String timePart = iso.substring(t + 1);
        String hhmm = timePart.length() >= 5 ? timePart.substring(0, 5) : timePart;
        return datePart + " " + hhmm;
    }

    private static Long findIdByUuid(SQLiteDatabase db, String table, String uuid) {
        String local = localUuid(db, table, uuid);
        if (local == null) return null;
        Cursor c = db.rawQuery("SELECT _id FROM " + table + " WHERE uuid=? LIMIT 1", new String[]{local});
        try {
            return c.moveToFirst() ? c.getLong(0) : null;
        } finally {
            c.close();
        }
    }

    private static String uuidById(SQLiteDatabase db, String table, long id) {
        return columnById(db, table, "uuid", id);
    }

    /**
     * Значение произвольной колонки по локальному {@code _id} — общий случай
     * {@link #uuidById} (тот же запрос, только колонка "uuid" зашита). Отдельно
     * нужен {@link SyncLog}: человекочитаемое описание строки (имя счёта,
     * символ пары и т.п.) читается из ДРУГИХ колонок той же таблицы-цели.
     */
    private static String columnById(SQLiteDatabase db, String table, String column, long id) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id=? LIMIT 1",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }
}
