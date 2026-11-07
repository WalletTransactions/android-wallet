package com.example.wallettransactions.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <h3>Иконки — только скачиваются (openspec server-managed-icons)</h3>
 * Общий набор иконок ведёт администратор на сайте; телефон иконки на сервер
 * НЕ отправляет — ни строк {@code icon} в push, ни байт в {@code POST
 * /sync/blobs}. Иконка на телефоне — либо «с сервера» ({@code icon.origin
 * = 0}), либо «своя» ({@code origin = 1}, из галереи или seed.json). У счёта
 * или валюты со своей иконкой в push уходит {@code icon_uuid} заместителя с
 * сервера ({@link IconSubstitutes}, {@link PushIconRefs}); нет заместителя —
 * строка и всё, что на неё ссылается, откладываются ({@link #deferDependents}).
 * Pull с тем же заместителем свою иконку не затирает ({@link PullIconGuard}).
 * <p>
 * На pull метаданные иконки идут строкой {@code icon}, а байты — отдельным
 * {@code GET /sync/blobs} ({@link SyncApiClient#downloadBlob}): для НОВОЙ
 * (по uuid) иконки сначала скачиваем содержимое, для уже существующей —
 * только обновляем метаданные, байты не перекачиваем (см. {@link #applyIcon});
 * все нужные для страницы скачивания делаются ДО {@code db.beginTransaction()}
 * (см. {@link #prefetchNewIconBlobs}), не внутри неё — держать
 * sqlite-транзакцию открытой на время сетевого запроса означало бы
 * блокировать доступ к БД для всего остального приложения (один общий
 * connection, см. {@code Singleton}) на непредсказуемое время.
 * <p>
 * Локальное удаление своей иконки ({@code IconEditDialog.deleteIcon}) —
 * жёсткий {@code DELETE} (защиту от удаления ИСПОЛЬЗУЕМОЙ даёт FK); иконки с
 * сервера на телефоне не правятся и не удаляются.
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
            "account", "account_operating", "account_asset", "operation", "operation_link",
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
        // Строки, отложенные из-за своей иконки без заместителя (см. PushIconRefs,
        // deferDependents) — пересобираются каждым раундом заново, в журнал
        // попадает состояние последнего. finally — чтобы пользователь узнал о
        // них и тогда, когда push упал по другой причине.
        List<String> problems = new ArrayList<>();
        try {
            pushDirtyRounds(db, api, log, problems);
        } finally {
            for (String p : problems) log.problem(p);
        }
    }

    private static void pushDirtyRounds(SQLiteDatabase db, SyncApiClient api, SyncLog log, List<String> problems)
            throws IOException, JSONException {
        for (int round = 1; ; round++) {
            problems.clear();
            Map<String, JSONArray> byTable = collectDirtyByTable(db, problems);
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

    /**
     * Dirty-строки {@code currency} для push. {@code show_in_equivalent} шлётся всегда
     * (openspec currency-ui-tweaks, design D6): сервер без колонки строит SQL по своему
     * списку колонок и лишний ключ игнорирует. {@code icon_uuid} своей иконки — заместитель
     * ({@link PushIconRefs}).
     */
    static JSONArray collectDirtyCurrencies(SQLiteDatabase db, PushIconRefs icons) throws JSONException {
        return collect(db,
                "SELECT uuid, name, iso_code, icon_id, symbol, precision, deleted_at, show_in_equivalent "
                        + "FROM currency WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("name", c.getString(1));
                    row.put("iso_code", c.getString(2));
                    row.put("icon_uuid", icons.uuidFor(c.getLong(3)));
                    row.put("symbol", c.getString(4));
                    row.put("precision", c.getInt(5));
                    row.put("deleted", !c.isNull(6));
                    row.put("show_in_equivalent", c.getInt(7) != 0);
                    return row;
                });
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
     * Собирает все dirty-строки по 8 синхронизируемым таблицам ({@code icon}
     * в push не уходит, см. javadoc класса, "Иконки") в
     * порядке {@link #TABLE_ORDER} — {@code LinkedHashMap} сохраняет именно
     * этот порядок вставки, на нём строится нарезка на порции (см.
     * {@link #chunkIntoPortions}). Раньше это была одна {@code changes} на
     * один push-батч (README wallet-api, "Вся changes — одна транзакция") —
     * теперь так же одна транзакция, но НА КАЖДУЮ ПОРЦИЮ, не на весь
     * dirty-набор разом (см. {@link #pushDirty}).
     */
    static Map<String, JSONArray> collectDirtyByTable(SQLiteDatabase db, List<String> problems)
            throws JSONException {
        Map<String, JSONArray> byTable = new LinkedHashMap<>();

        Set<String> deferred = new HashSet<>();
        PushIconRefs icons = new PushIconRefs(db, deferred, problems);

        putOrdered(byTable, "currency", collectDirtyCurrencies(db, icons));

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
                "SELECT uuid, currency_pair_id, date, CAST(rate AS TEXT), deleted_at FROM currency_pair_rate WHERE dirty=1",
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
                    row.put("rate", c.getString(3)); // текстом и через CAST — тот же довод, что у amount ниже
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
                    row.put("icon_uuid", icons.uuidFor(c.getLong(3)));
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

        putOrdered(byTable, "account_asset", collect(db,
                // capital-assets: ключи — по TableSpec wallet-api (asset_type, include_in_net_worth,
                // comment), сервер читает каждую колонку без дефолта. uuid — тот же, что у счёта.
                "SELECT uuid, asset_type, include_in_net_worth, comment, deleted_at FROM account_asset WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("asset_type", c.getInt(1));
                    row.put("include_in_net_worth", c.getInt(2) != 0);
                    row.put("comment", c.getString(3));
                    row.put("deleted", !c.isNull(4));
                    return row;
                }));

        putOrdered(byTable, "operation", collect(db,
                "SELECT uuid, date, acc_fr_id, acc_to_id, CAST(amount AS TEXT), comment, deleted_at FROM operation WHERE dirty=1",
                c -> {
                    JSONObject row = new JSONObject();
                    row.put("uuid", c.getString(0));
                    row.put("date", c.getString(1));
                    row.put("acc_fr_uuid", uuidById(db, "account", c.getLong(2)));
                    row.put("acc_to_uuid", uuidById(db, "account", c.getLong(3)));
                    // Строкой, не getDouble (см. README wallet-api/schema, "Деньги —
                    // numeric" — суммы протокол несёт строками). CAST в самом SELECT
                    // обязателен: getString() по REAL-колонке форматирует Android
                    // (CursorWindow, "%g") — 6 значащих цифр, 73791.98 уходил как
                    // "73792". CAST AS TEXT форматирует SQLite — 15 цифр, без потерь.
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

        if (!deferred.isEmpty()) deferDependents(byTable, deferred, problems);
        return byTable;
    }

    /**
     * Убирает из {@code byTable} строки, которые ссылаются (через любое поле
     * {@code *_uuid}) на uuid из {@code deferred} — свою иконку, для которой
     * нет заместителя с сервера (см. {@link PushIconRefs}), — и, по
     * цепочке, всё, что ссылается уже на них: счёт с такой иконкой → его
     * {@code account_operating} (тот же uuid, что у счёта) → операции по
     * нему → их {@code operation_link}. Иначе сервер ответил бы {@code
     * missing_reference} на иконку, которой у него нет и не будет, — и весь
     * push упал бы. Один проход по {@link #TABLE_ORDER}
     * достаточен: ссылка всегда указывает на таблицу РАНЬШЕ по порядку.
     * Отложенные строки остаются dirty и уйдут следующим синком (после pull
     * иконок с сервера заместитель обычно находится).
     */
    static void deferDependents(Map<String, JSONArray> byTable, Set<String> deferred,
                                        List<String> problems) throws JSONException {
        int skipped = 0;
        Iterator<Map.Entry<String, JSONArray>> tables = byTable.entrySet().iterator();
        while (tables.hasNext()) {
            Map.Entry<String, JSONArray> entry = tables.next();
            JSONArray kept = new JSONArray();
            JSONArray rows = entry.getValue();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (referencesDeferred(row, deferred)) {
                    deferred.add(row.getString("uuid"));
                    skipped++;
                } else {
                    kept.put(row);
                }
            }
            if (kept.length() == 0) tables.remove();
            else entry.setValue(kept);
        }
        if (skipped > 0) {
            problems.add("отложено до следующего синка (нет иконки-заместителя): " + skipped + " строк");
        }
    }

    private static boolean referencesDeferred(JSONObject row, Set<String> deferred) {
        Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("uuid") && !key.endsWith("_uuid")) continue;
            String value = row.optString(key, null);
            if (value != null && deferred.contains(value)) return true;
        }
        return false;
    }

    /**
     * {@code icon_id} → {@code icon_uuid} для push {@code currency}/{@code account}
     * (openspec server-managed-icons, design п. 3). Иконка с сервера — её uuid. Своя —
     * uuid заместителя ({@link IconSubstitutes}, индекс строится один раз за раунд и
     * только если в раунде есть своя иконка). Нет заместителя — uuid своей иконки
     * кладётся в {@code deferred}: {@link #deferDependents} уберёт строку и всё, что на
     * неё ссылается, а в журнал — «нет иконки-заместителя для «<имя>»: строка отложена»
     * (один раз на иконку).
     */
    static final class PushIconRefs {
        private final SQLiteDatabase db;
        private final Set<String> deferred;
        private final List<String> problems;
        private final Map<Long, String> resolved = new HashMap<>();
        private IconSubstitutes substitutes;

        PushIconRefs(SQLiteDatabase db, Set<String> deferred, List<String> problems) {
            this.db = db;
            this.deferred = deferred;
            this.problems = problems;
        }

        String uuidFor(long iconId) {
            if (resolved.containsKey(iconId)) return resolved.get(iconId);
            String uuid = resolve(iconId);
            resolved.put(iconId, uuid);
            return uuid;
        }

        private String resolve(long iconId) {
            String uuid;
            String name;
            byte[] image;
            Cursor c = db.rawQuery("SELECT uuid, origin, name, image FROM icon WHERE _id=? LIMIT 1",
                    new String[]{String.valueOf(iconId)});
            try {
                if (!c.moveToFirst()) return null; // как uuidById: ссылка в никуда — пусть разбирается сервер
                if (c.getInt(1) != WalletContract.IconEntry.ORIGIN_LOCAL) return c.getString(0);
                uuid = c.getString(0);
                name = c.getString(2);
                image = c.getBlob(3);
            } finally {
                c.close();
            }
            if (substitutes == null) substitutes = IconSubstitutes.load(db);
            String substitute = substitutes.substituteFor(name, image);
            if (substitute != null) return substitute;
            Log.w(TAG, "push: нет иконки-заместителя для " + uuid + " (" + name + ") — строка отложена");
            deferred.add(uuid);
            problems.add("нет иконки-заместителя для «" + name + "»: строка отложена");
            return uuid;
        }
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
    static void resolveAccountConflicts(SQLiteDatabase db, JSONArray conflicts) throws JSONException {
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
            // account_asset.uuid — тот же инвариант (capital-assets), иначе признак актива
            // ушёл бы со старым uuid несуществующего на сервере счёта.
            db.update("account_asset", cvOp, "uuid=?", new String[]{ourUuid});

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
        // То же для строк, чей родитель пришёл на более поздней странице (см.
        // PullDeferredRows): до неё они терялись — например, account_operating
        // кошелька, чью валюту недавно правили, и кошелёк оставался без валюты.
        if (!AppSettings.isUuidFormatResyncDone(ctx) || !AppSettings.isDeferredRowsResyncDone(ctx)) {
            startFullReload(db, ctx);
            AppSettings.setUuidFormatResyncDone(ctx);
            AppSettings.setDeferredRowsResyncDone(ctx);
            log.healed("Повторная загрузка с сервера — подтягиваю пропущенные данные");
        }
        // Однократный перечит ledger после шага БД v88 (capital-assets, design п. 2):
        // прежняя версия пропускала строки account_asset, а курсор уходил дальше. Только
        // ledger — reference и ledger_id не трогаем; флаг снимается после первой
        // применённой страницы, дальше обрыв продолжает с сохранённого курсора. Это не
        // загрузка с вычисткой: upsert по uuid идемпотентен, удалять нечего.
        boolean ledgerReread = AppSettings.isLedgerRereadPending(ctx);
        if (ledgerReread) {
            AppSettings.setLedgerSyncSeq(ctx, 0);
            log.healed("Повторная загрузка кошелька с сервера — подтягиваю признаки активов");
        }
        long ledgerSeq = AppSettings.getLedgerSyncSeq(ctx);
        long referenceSeq = AppSettings.getReferenceSyncSeq(ctx);
        // Поток с нуля при уже синкнутых строках (вход в другой аккаунт,
        // resetSyncCursors) — тоже загрузка с вычисткой (purge-synced-tombstones,
        // design п. 4): иначе строки, которых на сервере больше нет, остались бы.
        // Оба курсора с нуля — локально не различить, какой поток принёс строку.
        if (!ledgerReread && !AppSettings.isFullReloadPending(ctx) && (ledgerSeq == 0 || referenceSeq == 0)
                && hasSyncedRows(db)) {
            startFullReload(db, ctx);
            ledgerSeq = 0;
            referenceSeq = 0;
        }
        boolean reloadPending = AppSettings.isFullReloadPending(ctx);
        boolean caughtUp = false;
        if (ledgerSeq == 0) log.fullReload();
        PullDeferredRows deferred = PullDeferredRows.load(ctx);
        Set<String> touched = new HashSet<>();
        // Сброс от сервера за проход — не больше одного: после него оба курсора
        // идут с нуля, и второй сброс значит, что сервер не даёт догрузить поток
        // (ручная проверка на test 2026-11-04: граница страницы ниже purged_seq
        // -> "purged" -> снова 0 — 2170 pull за 20 минут). Проход кончается
        // ошибкой, а не крутит сеть до капа на страницы.
        boolean serverResetSeen = false;

        // Кап на число страниц — защита от бага в курсорах/ответе сервера,
        // который иначе крутил бы цикл вечно (условие выхода ниже опирается
        // на то, что курсор реально продвигается).
        for (int page = 0; page < 10_000; page++) {
            SyncApiClient.PullResult result = api.pull(ledgerSeq, referenceSeq, 500,
                    AppSettings.getSyncLedgerId(ctx));

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
                    log.fullReload();
                    // Отложенное ждало строк старого леджера — их уже не будет.
                    deferred.clear();
                    deferred.save();
                    // Строки старого кошелька, которых нет в новом, уйдут при вычистке.
                    startFullReload(db, ctx);
                    reloadPending = true;
                    ledgerSeq = 0;
                    referenceSeq = 0;
                    continue;
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
                boolean purged = "purged".equals(result.ledger.resetReason)
                        || "purged".equals(result.reference.resetReason);
                Log.w(TAG, "pull: сброс курсора (ledger=" + ledgerSeq + " " + result.ledger.resetReason
                        + ", reference=" + referenceSeq + " " + result.reference.resetReason + ") — поток начат заново");
                log.healed(purged
                        ? "Сервер очистил старые удаления — загружаю данные заново"
                        : "Позиция синхронизации разошлась с сервером — загружаю данные заново");
                if (ledgerSeq != 0 || referenceSeq != 0) {
                    if (serverResetSeen) {
                        throw new JSONException("сервер повторно сбросил курсор за один проход (ledger="
                                + ledgerSeq + " " + result.ledger.resetReason + ") — загрузка с нуля не идёт");
                    }
                    serverResetSeen = true;
                    log.fullReload();
                    startFullReload(db, ctx);
                    reloadPending = true;
                    ledgerSeq = 0;
                    referenceSeq = 0;
                    continue;
                }
            }

            boolean advanced = result.ledger.nextSeq != ledgerSeq || result.reference.nextSeq != referenceSeq;
            if (!advanced) {
                caughtUp = true;
                break; // догнали сервер — оба потока пусты
            }

            touched.addAll(applyPage(db, result.ledger.changes, result.reference.changes, api, log, deferred,
                    reloadPending));

            // Сначала отложенное, потом курсоры: умрём между ними — страница
            // придёт снова, и её строки просто заменят свои отложенные копии.
            deferred.save();
            ledgerSeq = result.ledger.nextSeq;
            referenceSeq = result.reference.nextSeq;
            AppSettings.setLedgerSyncSeq(ctx, ledgerSeq);
            AppSettings.setReferenceSyncSeq(ctx, referenceSeq);
            if (ledgerReread) {
                // Страница перечита применена и курсор сохранён — дальше обычная позиция.
                AppSettings.setLedgerRereadPending(ctx, false);
                ledgerReread = false;
            }
        }
        if (ledgerReread && caughtUp) AppSettings.setLedgerRereadPending(ctx, false); // поток ledger пуст

        // Новых страниц нет, а отложенное осталось (родитель пришёл в самой
        // последней странице или был применён в прошлом синке) — ещё один проход.
        if (!deferred.isEmpty()) {
            touched.addAll(applyPage(db, new JSONObject(), new JSONObject(), api, log, deferred));
            deferred.save();
        }
        if (!deferred.isEmpty()) {
            // Поток догнан, а родителя так и нет — на сервере строка ссылается на
            // то, чего нам не прислали. Не теряем: повторим на следующем синке.
            Log.w(TAG, "pull: строки без родителя после полной загрузки — " + deferred.summary());
            log.problem("Часть данных ждёт связанных записей с сервера (" + deferred.summary() + ")");
        }

        // Загрузка с нуля догнала оба потока — синкнутые строки, которых сервер
        // не прислал, удаляются (неотправленные, dirty <> 0, остаются). Прервались
        // раньше — ничего не трогаем, следующий проход продолжит с курсора.
        if (reloadPending && caughtUp) {
            Map<String, Integer> swept = sweepUnseen(db);
            int total = 0;
            for (Map.Entry<String, Integer> e : swept.entrySet()) {
                total += e.getValue();
                if (e.getValue() > 0) touched.add(e.getKey());
            }
            db.delete(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE, null, null);
            AppSettings.clearFullReloadPending(ctx);
            Log.i(TAG, "pull: загрузка с нуля завершена, удалено строк без пары на сервере: " + swept);
            if (total > 0) log.healed("Удалено записей, которых больше нет на сервере: " + total);
        }

        boolean touchedAccounts = touched.contains("account") || touched.contains("account_operating")
                || touched.contains("account_asset");
        boolean touchedIcons = touched.contains("icon");
        boolean touchedCurrencies = touched.contains("currency");
        boolean touchedCurrencyPairs = touched.contains("currency_pair") || touched.contains("currency_pair_rate");

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

    /**
     * Применяет страницу вместе с отложенными строками прошлых страниц (см.
     * {@link PullDeferredRows}): внутри каждой таблицы сначала отложенные (они
     * старше), потом строки страницы. Отложенную строку, чью новую версию
     * принесла эта страница, не повторяем — новая пройдёт сама. Что снова не
     * применилось, остаётся в {@code deferred}; содержимое {@code deferred}
     * меняется только после коммита, так что упавшая транзакция его не портит.
     *
     * @return таблицы, где хоть одна строка применилась (для перечитывания кэшей
     *         в памяти: счета, валюты, пары)
     */
    static Set<String> applyPage(SQLiteDatabase db, JSONObject ledgerChanges, JSONObject referenceChanges,
                                  SyncApiClient api, SyncLog log, PullDeferredRows deferred)
            throws JSONException, IOException {
        return applyPage(db, ledgerChanges, referenceChanges, api, log, deferred, false);
    }

    /**
     * @param markSeen идёт загрузка с нуля ({@code AppSettings.isFullReloadPending}):
     *                 uuid всех строк страницы — применённых, отложенных, удалений —
     *                 пишутся в {@code sync_reload_seen} в той же транзакции, см.
     *                 {@link #sweepUnseen}
     */
    static Set<String> applyPage(SQLiteDatabase db, JSONObject ledgerChanges, JSONObject referenceChanges,
                                  SyncApiClient api, SyncLog log, PullDeferredRows deferred, boolean markSeen)
            throws JSONException, IOException {
        Map<String, JSONArray> merged = mergeChanges(ledgerChanges, referenceChanges);
        PullDeferredRows stillDeferred = PullDeferredRows.inMemory();
        Set<String> appliedTables = new HashSet<>();
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
            // Индекс заместителей строится лениво, при первом счёте/валюте —
            // то есть уже после иконок этой страницы (icon первая в TABLE_ORDER).
            PullIconGuard iconGuard = new PullIconGuard(db);
            for (String table : TABLE_ORDER) {
                JSONArray rows = merged.get(table);
                List<JSONObject> queue = new ArrayList<>(
                        deferred.rowsToRetry(table, uuidsOf(rows)));
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) queue.add(rows.getJSONObject(i));
                }
                for (JSONObject row : queue) {
                    if (applyRow(db, table, row, newIconBlobs, iconGuard, log)) {
                        appliedTables.add(table);
                    } else {
                        stillDeferred.add(table, row);
                    }
                }
            }
            if (markSeen) markSeen(db, merged);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        deferred.replaceWith(stillDeferred);
        return appliedTables;
    }

    private static Set<String> uuidsOf(JSONArray rows) throws JSONException {
        Set<String> uuids = new HashSet<>();
        if (rows == null) return uuids;
        for (int i = 0; i < rows.length(); i++) {
            String uuid = rows.getJSONObject(i).optString("uuid", null);
            if (uuid != null) uuids.add(uuid);
        }
        return uuids;
    }

    /**
     * Скачивает содержимое ТОЛЬКО тех иконок из страницы, которых ещё нет
     * локально (найдено по uuid) — для уже существующих байты не
     * перекачиваются (см. javadoc класса, "Иконки"). Дедуп по
     * {@code content_sha256}: несколько новых иконок в одной странице могут
     * делить одно и то же содержимое, скачивать его больше одного раза
     * незачем. Новую иконку, которая совпадает со своей и по имени, и по
     * картинке, тоже не качаем: {@link #applyIcon} не вставит её, а сделает
     * своей иконкой с сервера.
     */
    static Map<String, byte[]> prefetchNewIconBlobs(SQLiteDatabase db, JSONArray iconRows,
                                                              SyncApiClient api)
            throws JSONException, IOException {
        Map<String, byte[]> cache = new HashMap<>();
        if (iconRows == null) return cache;
        for (int i = 0; i < iconRows.length(); i++) {
            JSONObject row = iconRows.getJSONObject(i);
            if (localUuid(db, "icon", row.getString("uuid")) != null) continue;
            if (rowIsDeleted(row)) continue; // удаление неизвестной иконки не применяется (см. applyRow)
            String sha256 = row.getString("content_sha256");
            if (cache.containsKey(sha256)) continue;
            if (!rowIsDeleted(row)) {
                OwnIcon own = OwnIcon.findByName(db, row.getString("name"), null);
                if (own != null && own.sha256.equals(sha256)) continue;
            }
            cache.put(sha256, api.downloadBlob(sha256));
        }
        return cache;
    }

    /**
     * @return {@code false} — строку надо отложить: её родитель (иконка, валюта,
     *         счёт, пара, проводка) ещё не пришёл (см. {@link PullDeferredRows});
     *         {@code true} — строка обработана (применена или отброшена насовсем).
     */
    private static boolean applyRow(SQLiteDatabase db, String table, JSONObject row,
                                     Map<String, byte[]> newIconBlobs, PullIconGuard iconGuard,
                                     SyncLog log) throws JSONException {
        // Удаление строки, которой здесь нет (загрузка с нуля приносит старые
        // удаления): не создаём строку только затем, чтобы чистка её удалила, и не
        // пишем «Удалено» про то, чего пользователь не видел (purge-synced-tombstones).
        // Обработана, не отложена: родитель удалённой строки может не прийти вовсе.
        if (isSyncTable(table) && rowIsDeleted(row) && localUuid(db, table, row.optString("uuid", null)) == null) {
            return true;
        }
        switch (table) {
            case "currency": return applyCurrency(db, row, log, iconGuard);
            case "currency_pair": return applyCurrencyPair(db, row, log);
            case "currency_pair_rate": return applyCurrencyPairRate(db, row, log);
            case "currency_conversion": return applyCurrencyConversion(db, row);
            case "account": return applyAccount(db, row, log, iconGuard);
            case "account_operating": return applyAccountOperating(db, row);
            case "account_asset": return applyAccountAsset(db, row);
            case "operation": return applyOperation(db, row, log);
            case "operation_link": return applyOperationLink(db, row);
            case "icon": applyIcon(db, row, newIconBlobs, log); return true;
            default:
                // Неизвестная клиенту таблица — сервер может быть новее
                // этого релиза (тот же принцип, что у push_batch на
                // сервере для незнакомого имени) — игнор, не ошибка.
                return true;
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
     * <p>
     * openspec server-managed-icons (design п. 1, 5): строка с сервера всегда
     * {@code origin = 0} — и новая, и уже известная по uuid (своя, когда-то
     * ушедшая на сервер, снова становится «с сервера»: сервер главный). Имя
     * занято живой своей иконкой — своя переименовывается в «<имя> (своя)» /
     * «(своя N)» в той же транзакции; если у своей та же картинка, что у
     * новой с сервера, — это одна и та же иконка (seed.json после чистой
     * установки): своя получает uuid и метаданные серверной и становится
     * «с сервера», дубль не заводится. Конфликт с иконкой «с сервера» — как
     * раньше, пропуск с логом.
     */
    static void applyIcon(SQLiteDatabase db, JSONObject row, Map<String, byte[]> newIconBlobs,
                          SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        String local = localUuid(db, "icon", uuid);
        boolean exists = local != null;
        boolean deleted = rowIsDeleted(row);
        String name = row.getString("name");
        String sha256 = row.getString("content_sha256");

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("icon_type", row.getInt("icon_type"));
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_SERVER);
        applySyncColumns(cv, row);

        try {
            // Tombstone имя не занимает (uq_icon_name — частичный, deleted_at IS NULL).
            OwnIcon own = deleted ? null : OwnIcon.findByName(db, name, local);
            if (!exists && own != null && own.sha256.equals(sha256)) {
                cv.put("uuid", uuid);
                cv.put("dirty", 2); // два шага через 2 — см. updateFromServer
                db.update("icon", cv, "_id=?", new String[]{String.valueOf(own.id)});
                ContentValues clean = new ContentValues();
                clean.put("dirty", 0);
                db.update("icon", clean, "_id=?", new String[]{String.valueOf(own.id)});
                log.pull("Иконка", "Своя стала иконкой с сервера");
                return;
            }
            byte[] image = null;
            if (!exists) {
                image = newIconBlobs.get(sha256);
                if (image == null) {
                    Log.w(TAG, "icon " + uuid + ": содержимое не предзагружено — строка пропущена");
                    return;
                }
            }
            if (own != null) {
                String renamed = OwnIcon.freeOwnName(db, name);
                ContentValues rename = new ContentValues();
                rename.put("name", renamed);
                db.update("icon", rename, "_id=?", new String[]{String.valueOf(own.id)});
                Log.i(TAG, "icon: своя «" + name + "» переименована в «" + renamed + "» — имя занято иконкой с сервера");
                log.pull("Иконка", "Своя переименована");
            }
            if (exists) {
                updateFromServer(db, "icon", cv, local);
            } else {
                cv.put("image", image);
                cv.put("uuid", uuid);
                db.insertOrThrow("icon", null, cv);
            }
            log.pull("Иконка", deleted ? "Удалено" : (exists ? "Изменено" : "Добавлено"));
        } catch (SQLiteException e) {
            // uq_icon_name с иконкой «с сервера» и т.п. — тот же класс проблемы,
            // что у upsertByUuid (см. javadoc класса, "Что ещё сознательно не решено").
            Log.w(TAG, "upsert icon uuid=" + uuid + " не удался: " + e.getMessage());
        }
    }

    /** Живая своя иконка ({@code origin = 1}) — для конфликта имени на pull, см. {@link #applyIcon}. */
    static final class OwnIcon {
        final long id;
        final String sha256;

        private OwnIcon(long id, String sha256) {
            this.id = id;
            this.sha256 = sha256;
        }

        /** Своя живая иконка с этим именем (кроме строки {@code excludeUuid}), или {@code null}. */
        static OwnIcon findByName(SQLiteDatabase db, String name, String excludeUuid) {
            Cursor c = db.rawQuery("SELECT _id, image FROM icon WHERE name=? AND deleted_at IS NULL"
                            + " AND origin=" + WalletContract.IconEntry.ORIGIN_LOCAL
                            + " AND uuid<>? LIMIT 1",
                    new String[]{name, excludeUuid == null ? "" : excludeUuid});
            try {
                if (!c.moveToFirst()) return null;
                byte[] image = c.getBlob(1);
                return new OwnIcon(c.getLong(0), image == null ? "" : IconSubstitutes.sha256Hex(image));
            } finally {
                c.close();
            }
        }

        /** «<имя> (своя)», занято — «<имя> (своя 2)», «(своя 3)», … среди живых иконок. */
        static String freeOwnName(SQLiteDatabase db, String name) {
            String candidate = name + " (своя)";
            for (int n = 2; nameTaken(db, candidate); n++) candidate = name + " (своя " + n + ")";
            return candidate;
        }

        private static boolean nameTaken(SQLiteDatabase db, String name) {
            Cursor c = db.rawQuery("SELECT 1 FROM icon WHERE name=? AND deleted_at IS NULL LIMIT 1",
                    new String[]{name});
            try {
                return c.moveToFirst();
            } finally {
                c.close();
            }
        }
    }

    /**
     * Своя иконка не затирается заместителем (openspec server-managed-icons,
     * design п. 4): если у счёта/валюты локально своя иконка, а с сервера
     * пришёл тот же {@code icon_uuid}, что телефон сам отправил бы вместо неё,
     * — {@code icon_id} не трогаем. Другой {@code icon_uuid} (иконку сменили
     * на другом устройстве или на сайте) применяется. Индекс заместителей —
     * один на страницу pull, строится при первом вызове.
     */
    static final class PullIconGuard {
        private final SQLiteDatabase db;
        private IconSubstitutes substitutes;

        PullIconGuard(SQLiteDatabase db) {
            this.db = db;
        }

        /** {@code table} — {@code account} или {@code currency}. */
        boolean keepsOwnIcon(String table, String rowUuid, String incomingIconUuid) {
            String local = localUuid(db, table, rowUuid);
            if (local == null) return false;
            String name;
            byte[] image;
            Cursor c = db.rawQuery("SELECT i.origin, i.name, i.image FROM " + table + " t"
                    + " JOIN icon i ON i._id = t.icon_id WHERE t.uuid=? LIMIT 1", new String[]{local});
            try {
                if (!c.moveToFirst() || c.getInt(0) != WalletContract.IconEntry.ORIGIN_LOCAL) return false;
                name = c.getString(1);
                image = c.getBlob(2);
            } finally {
                c.close();
            }
            if (substitutes == null) substitutes = IconSubstitutes.load(db);
            return IconSubstitutes.sameUuid(substitutes.substituteFor(name, image), incomingIconUuid);
        }
    }

    static boolean applyCurrency(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        return applyCurrency(db, row, log, new PullIconGuard(db));
    }

    static boolean applyCurrency(SQLiteDatabase db, JSONObject row, SyncLog log, PullIconGuard iconGuard)
            throws JSONException {
        String uuid = row.getString("uuid");
        String iconUuid = row.getString("icon_uuid");
        Long iconId = findIdByUuid(db, "icon", iconUuid);
        if (iconId == null) {
            Log.w(TAG, "currency " + uuid + ": icon_uuid ещё не известен локально — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("name", row.getString("name"));
        cv.put("iso_code", row.getString("iso_code"));
        if (!iconGuard.keepsOwnIcon("currency", uuid, iconUuid)) cv.put("icon_id", iconId);
        cv.put("symbol", row.getString("symbol"));
        cv.put("precision", row.getInt("precision"));
        // Старый сервер ключа не присылает — локальный флаг тогда не трогаем (design D6).
        if (row.has("show_in_equivalent") && !row.isNull("show_in_equivalent")) {
            cv.put("show_in_equivalent", row.getBoolean("show_in_equivalent") ? 1 : 0);
        }
        cv.put("is_shared", isSharedRow(row) ? 1 : 0);
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "currency", uuid, cv);
        if (wasInsert == null) return true;
        log.pull("Валюта", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
        return true;
    }

    /**
     * Общая ли строка справочника: у общих (поток {@code reference}) {@code ledger_id}
     * пуст (openspec currency-ui-tweaks, design D1). Строку без ключа вовсе считаем
     * своей — так безопаснее: своя строка допускает правку, а не теряет её.
     */
    static boolean isSharedRow(JSONObject row) {
        return row.has("ledger_id") && row.isNull("ledger_id");
    }

    static boolean applyCurrencyPair(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long baseId = findIdByUuid(db, "currency", row.getString("base_currency_uuid"));
        Long quoteId = findIdByUuid(db, "currency", row.getString("quote_currency_uuid"));
        if (baseId == null || quoteId == null) {
            Log.w(TAG, "currency_pair " + uuid + ": валюта ещё не синхронизирована — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("symbol", row.getString("symbol"));
        cv.put("base_currency_id", baseId);
        cv.put("quote_currency_id", quoteId);
        cv.put("precision", row.getInt("precision"));
        cv.put("is_shared", isSharedRow(row) ? 1 : 0);
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "currency_pair", uuid, cv);
        if (wasInsert == null) return true;
        log.pull("Валютная пара", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
        return true;
    }

    private static boolean applyCurrencyPairRate(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"));
        if (pairId == null) {
            Log.w(TAG, "currency_pair_rate " + uuid + ": пара ещё не синхронизирована — строка пропущена");
            return false;
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
        if (wasInsert == null) return true;
        log.pull("Курс", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
        return true;
    }

    private static boolean applyCurrencyConversion(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long fromId = findIdByUuid(db, "currency", row.getString("from_currency_uuid"));
        Long toId = findIdByUuid(db, "currency", row.getString("to_currency_uuid"));
        Long pairId = findIdByUuid(db, "currency_pair", row.getString("currency_pair_uuid"));
        if (fromId == null || toId == null || pairId == null) {
            Log.w(TAG, "currency_conversion " + uuid + ": валюта/пара ещё не синхронизированы — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("from_currency_id", fromId);
        cv.put("to_currency_id", toId);
        cv.put("num", row.getInt("num"));
        cv.put("currency_pair_id", pairId);
        cv.put("is_reverse", row.getBoolean("is_reverse") ? 1 : 0);
        applySyncColumns(cv, row);
        upsertByUuid(db, "currency_conversion", uuid, cv);
        return true;
    }

    static boolean applyAccount(SQLiteDatabase db, JSONObject row, SyncLog log, PullIconGuard iconGuard)
            throws JSONException {
        String uuid = row.getString("uuid");
        String iconUuid = row.getString("icon_uuid");
        Long iconId = findIdByUuid(db, "icon", iconUuid);
        if (iconId == null) {
            Log.w(TAG, "account " + uuid + ": icon_uuid ещё не известен локально — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("name", row.getString("name"));
        cv.put("comment", row.getString("comment"));
        if (!iconGuard.keepsOwnIcon("account", uuid, iconUuid)) cv.put("icon_id", iconId);
        cv.put("is_archived", row.getBoolean("is_archived") ? 1 : 0);
        // system_kind/kind — та же пара колонок, что и в push-стороне (collectDirtyByTable) выше;
        // без них строка «Transfer»/«Transfer commission», пришедшая с другого устройства, теряла
        // бы свою системность локально, а обычный счёт/категория — признак кошелёк/категория.
        cv.put("system_kind", row.getInt("system_kind"));
        cv.put("kind", row.getInt("kind"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "account", uuid, cv);
        if (wasInsert == null) return true;
        log.pull("Счёт", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
        return true;
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
    private static boolean applyAccountOperating(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long accountId = findIdByUuid(db, "account", uuid);
        if (accountId == null) {
            Log.w(TAG, "account_operating " + uuid + ": account ещё не применён — строка пропущена "
                    + "(нарушен порядок применения таблиц?)");
            return false;
        }
        Long currencyId = findIdByUuid(db, "currency", row.getString("currency_uuid"));
        if (currencyId == null) {
            Log.w(TAG, "account_operating " + uuid + ": валюта ещё не синхронизирована — строка пропущена");
            return false;
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
        return true;
    }

    /**
     * Признак капитального актива (capital-assets, design п. 3) — копия логики
     * {@link #applyAccountOperating}: общий первичный ключ с {@code account}, родительский
     * счёт ищется по тому же uuid. Счёта ещё нет — строка откладывается до его прихода.
     */
    private static boolean applyAccountAsset(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long accountId = findIdByUuid(db, "account", uuid);
        if (accountId == null) {
            Log.w(TAG, "account_asset " + uuid + ": account ещё не применён — строка отложена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("asset_type", row.getInt("asset_type"));
        cv.put("include_in_net_worth", row.getBoolean("include_in_net_worth") ? 1 : 0);
        cv.put("comment", row.optString("comment", ""));
        applySyncColumns(cv, row);
        try {
            String local = localUuid(db, "account_asset", uuid);
            if (local != null) {
                updateFromServer(db, "account_asset", cv, local);
            } else {
                cv.put("_id", accountId);
                cv.put("uuid", localUuid(db, "account", uuid));
                db.insertOrThrow("account_asset", null, cv);
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "upsert account_asset uuid=" + uuid + " не удался: " + e.getMessage());
        }
        return true;
    }

    private static boolean applyOperation(SQLiteDatabase db, JSONObject row, SyncLog log) throws JSONException {
        String uuid = row.getString("uuid");
        Long accFr = findIdByUuid(db, "account", row.getString("acc_fr_uuid"));
        Long accTo = findIdByUuid(db, "account", row.getString("acc_to_uuid"));
        if (accFr == null || accTo == null) {
            Log.w(TAG, "operation " + uuid + ": счёт ещё не синхронизирован — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("date", isoTimestampToLocalDbFormat(row.getString("date")));
        cv.put("acc_fr_id", accFr);
        cv.put("acc_to_id", accTo);
        cv.put("amount", row.getString("amount")); // numeric-как-строка
        cv.put("comment", row.getString("comment"));
        applySyncColumns(cv, row);
        Boolean wasInsert = upsertByUuid(db, "operation", uuid, cv);
        if (wasInsert == null) return true;
        log.pull("Операция", rowIsDeleted(row) ? "Удалено" : (wasInsert ? "Добавлено" : "Изменено"));
        if (wasInsert && !rowIsDeleted(row)) log.newOperation(uuid);
        return true;
    }

    private static boolean applyOperationLink(SQLiteDatabase db, JSONObject row) throws JSONException {
        String uuid = row.getString("uuid");
        Long mainId = findIdByUuid(db, "operation", row.getString("main_uuid"));
        Long linkId = findIdByUuid(db, "operation", row.getString("link_uuid"));
        if (mainId == null || linkId == null) {
            Log.w(TAG, "operation_link " + uuid + ": проводка ещё не синхронизирована — строка пропущена");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("main_id", mainId);
        cv.put("link_id", linkId);
        cv.put("type", row.getInt("type"));
        applySyncColumns(cv, row);
        upsertByUuid(db, "operation_link", uuid, cv);
        return true;
    }

    // ── Общие мелочи ─────────────────────────────────────────────────────────

    // ── ЧИСТКА УДАЛЁННЫХ (openspec purge-synced-tombstones) ────────────────────

    /**
     * Порядок физического удаления — от листьев к корням: строка удаляется раньше
     * тех, на которые ссылается, так что в одном проходе удалённая операция
     * освобождает свой удалённый счёт. Тот же состав, что {@link #TABLE_ORDER}.
     */
    static final List<String> PURGE_ORDER = Arrays.asList(
            "operation_link", "operation", "account_asset", "account_operating", "account", "currency_conversion",
            "currency_pair_rate", "currency_pair", "currency", "icon");

    static boolean isSyncTable(String table) {
        return PURGE_ORDER.contains(table);
    }

    /**
     * Ссылки с {@code ON DELETE CASCADE} ошибкой внешнего ключа не остановить —
     * удаление молча унесло бы ссылающиеся строки. Проверяем их явно: связка
     * проводок на операцию и правило повторяющейся операции (локальная таблица
     * без синка) на счёт. Остальные ссылки — {@code NO ACTION}, их ловит
     * {@link SQLiteConstraintException} в {@link #deleteRowByRow}.
     */
    private static String cascadeGuard(String table) {
        switch (table) {
            case "operation":
                return " AND NOT EXISTS (SELECT 1 FROM " + WalletContract.OperationLinkEntry.TABLE_NAME + " l"
                        + " WHERE l." + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + " = t._id"
                        + " OR l." + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " = t._id)";
            case "account":
                return " AND NOT EXISTS (SELECT 1 FROM " + WalletContract.RecurringOperationEntry.TABLE_NAME + " r"
                        + " WHERE r." + WalletContract.RecurringOperationEntry.COLUMN_FROM + " = t._id"
                        + " OR r." + WalletContract.RecurringOperationEntry.COLUMN_TO + " = t._id)";
            default:
                return "";
        }
    }

    /**
     * Удаляет строки таблицы, подходящие под {@code where} (алиас {@code t}),
     * построчно: строка, на которую ещё ссылаются, даёт {@link SQLiteConstraintException}
     * — откатывается только этот оператор, строка остаётся до следующего раза.
     * {@code recheck} повторяется в самом DELETE (без алиаса): между выборкой и
     * удалением строку мог изменить UI.
     */
    private static int deleteRowByRow(SQLiteDatabase db, String table, String where, String[] args,
                                      String recheck) {
        List<String> uuids = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT t.uuid FROM " + table + " t WHERE " + where + cascadeGuard(table), args);
        try {
            while (c.moveToNext()) uuids.add(c.getString(0));
        } finally {
            c.close();
        }
        int deleted = 0;
        for (String uuid : uuids) {
            try {
                deleted += db.delete(table, "uuid = ? AND " + recheck, new String[]{uuid});
            } catch (SQLiteConstraintException e) {
                Log.i(TAG, "purge " + table + " uuid=" + uuid + ": на строку ссылаются — остаётся");
            }
        }
        return deleted;
    }

    /**
     * Шаг прохода после успешного {@link #pullAll} (см. {@code SyncRunner}):
     * физически удаляет строки с отметкой удаления и без неотправленных изменений
     * ({@code dirty = 0}) — удаление уже у сервера, пришедшее — уже применено.
     * Строку, на которую ссылаются, оставляет. Журнал синка не пишет: для
     * пользователя ничего не меняется, удалённое и так не видно.
     *
     * @return сколько строк удалено
     */
    static int purgeTombstones(SQLiteDatabase db) {
        int total = 0;
        String condition = "deleted_at IS NOT NULL AND dirty = 0";
        for (String table : PURGE_ORDER) {
            total += deleteRowByRow(db, table, "t." + condition.replace(" AND ", " AND t."), null, condition);
        }
        if (total > 0) Log.i(TAG, "purge: удалено отправленных удалений: " + total);
        return total;
    }

    /** uuid в форме отметки {@code sync_reload_seen}: нижний регистр, без дефисов. */
    static String normalizeUuid(String uuid) {
        return uuid == null ? null : uuid.toLowerCase(Locale.ROOT).replace("-", "");
    }

    /**
     * Отметки загрузки с нуля для строк страницы. Курс у сервера ищется по
     * (пара, дата), а не по uuid ({@code conflict_columns} в wallet-api): пришедший
     * курс засчитывает и локальную строку той же пары и даты с другим uuid —
     * иначе вычистка удалила бы её, а пришедшая не вставилась бы из-за
     * уникальности (пара, дата).
     */
    private static void markSeen(SQLiteDatabase db, Map<String, JSONArray> merged) throws JSONException {
        for (Map.Entry<String, JSONArray> e : merged.entrySet()) {
            String table = e.getKey();
            if (!isSyncTable(table)) continue;
            JSONArray rows = e.getValue();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                insertSeen(db, table, row.optString("uuid", null));
                if ("currency_pair_rate".equals(table)) {
                    Long pairId = findIdByUuid(db, "currency_pair", row.optString("currency_pair_uuid", null));
                    if (pairId == null) continue;
                    Cursor c = db.rawQuery("SELECT uuid FROM currency_pair_rate WHERE currency_pair_id = ? AND date = ?",
                            new String[]{String.valueOf(pairId), row.optString("date", "")});
                    try {
                        while (c.moveToNext()) insertSeen(db, table, c.getString(0));
                    } finally {
                        c.close();
                    }
                }
            }
        }
    }

    private static void insertSeen(SQLiteDatabase db, String table, String uuid) {
        if (uuid == null) return;
        db.execSQL("INSERT OR IGNORE INTO " + WalletDbHelper.SYNC_RELOAD_SEEN_TABLE + " (tbl, uuid) VALUES (?, ?)",
                new Object[]{table, normalizeUuid(uuid)});
    }

    /**
     * Иконки не отправляются (server-managed-icons), у своих ({@code origin = 1})
     * {@code dirty = 0} всегда — «синкнутыми» считаются только иконки с сервера.
     */
    private static String syncedOnly(String table) {
        return "icon".equals(table)
                ? " AND t." + WalletContract.IconEntry.COLUMN_ORIGIN + " = " + WalletContract.IconEntry.ORIGIN_SERVER
                : "";
    }

    /** Есть ли строки, уже бывшие на сервере — с ними загрузка с нуля идёт с вычисткой. */
    static boolean hasSyncedRows(SQLiteDatabase db) {
        for (String table : PURGE_ORDER) {
            Cursor c = db.rawQuery("SELECT 1 FROM " + table + " t WHERE t.dirty = 0" + syncedOnly(table) + " LIMIT 1",
                    null);
            try {
                if (c.moveToFirst()) return true;
            } finally {
                c.close();
            }
        }
        return false;
    }

    /** Начало загрузки с нуля: флаг, оба курсора 0, отметки прошлой (прерванной) загрузки — прочь. */
    private static void startFullReload(SQLiteDatabase db, Context ctx) {
        db.delete(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE, null, null);
        AppSettings.startFullReload(ctx);
    }

    /**
     * Конец загрузки с нуля (оба потока догнаны): удаляет синкнутые строки
     * ({@code dirty = 0}), которых не было в загруженных потоках, — сервер их
     * больше не хранит. Неотправленные ({@code dirty <> 0}) остаются, строка, на
     * которую ссылаются, — тоже.
     *
     * @return сколько строк удалено, по таблицам
     */
    static Map<String, Integer> sweepUnseen(SQLiteDatabase db) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String table : PURGE_ORDER) {
            String where = "t.dirty = 0" + syncedOnly(table)
                    + " AND replace(lower(t.uuid), '-', '') NOT IN (SELECT uuid FROM "
                    + WalletDbHelper.SYNC_RELOAD_SEEN_TABLE + " WHERE tbl = ?)";
            deleted.put(table, deleteRowByRow(db, table, where, new String[]{table}, "dirty = 0"));
        }
        return deleted;
    }

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
