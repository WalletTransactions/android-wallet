package com.example.wallettransactions.sync;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.example.wallettransactions.Data.AccountNames;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;

/**
 * Первое подключение телефона к кошельку на сервере (openspec new-user-default-set, spec
 * ledger-connection, design D4–D8). Строки, заведённые без входа, сводятся с данными
 * кошелька: серверная строка с тем же ключом заменяет неотправленную локальную, нетронутые
 * строки встроенного набора в непустой кошелёк не уходят, об операциях без входа приложение
 * спрашивает один раз.
 * <p>
 * Связи на телефоне идут по {@code _id}, а наружу уходит {@code uuid}, поэтому «принять
 * серверную строку» — это сменить у своей строки {@code uuid} и поля, без переклейки
 * операций, кошельков и пар.
 */
public final class LedgerConnection {

    private static final String TAG = "LedgerConnection";

    private LedgerConnection() {}

    /** Исход шага подключения после загрузки. */
    enum Step {
        /** Можно отправлять. */
        PUSH,
        /** Нужен ответ на вопрос об операциях без входа — отправки нет. */
        ASK
    }

    // ── Принятие серверной строки по ключу (design D6) ────────────────────────

    /**
     * Ищет неотправленную живую локальную строку с тем же ключом, что у пришедшей, и, если
     * она есть, превращает её в пришедшую: {@code cv} (поля с сервера, {@code dirty = 0})
     * плюс серверный {@code uuid}, метка «из набора» снимается.
     *
     * @return {@code true} — строка принята, вставлять пришедшую не нужно
     */
    static boolean adoptByKey(SQLiteDatabase db, String table, String serverUuid, ContentValues cv) {
        String localUuid = findByKey(db, table, cv);
        if (localUuid == null) return false;
        Long localId = idByUuid(db, table, localUuid);
        ContentValues adopted = new ContentValues(cv);
        adopted.put("uuid", serverUuid);
        adopted.put("from_seed", 0);
        try {
            replaceUuid(db, table, localUuid, serverUuid, adopted);
            if ("account".equals(table) && localId != null) {
                adoptAccountOperating(db, localId, serverUuid, cv);
            }
        } catch (SQLiteException e) {
            // Другой уникальный индекс (например, имя валюты занято третьей строкой) —
            // как раньше: пришедшая строка пропускается, локальная остаётся своей.
            Log.w(TAG, "adopt " + table + " " + serverUuid + " не удалось: " + e.getMessage());
            return false;
        }
        return true;
    }

    private static String findByKey(SQLiteDatabase db, String table, ContentValues cv) {
        String where;
        String key;
        switch (table) {
            case "currency":
                where = "iso_code = ? COLLATE NOCASE";
                key = cv.getAsString("iso_code");
                break;
            case "currency_pair":
                where = "symbol = ? COLLATE NOCASE";
                key = cv.getAsString("symbol");
                break;
            case "account":
                where = AccountNames.sameSql("name");
                key = cv.getAsString("name");
                break;
            default:
                return null;
        }
        if (key == null) return null;
        Cursor c = db.rawQuery("SELECT uuid FROM " + table + " WHERE " + where
                + " AND deleted_at IS NULL AND dirty <> 0 LIMIT 1", new String[]{key});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Как {@code SyncEngine.updateFromServer}, но со сменой uuid: через {@code dirty = 2},
     * чтобы триггер {@code trg_<table>_dirty} не вернул строку в push; второй шаг ищет
     * строку уже по новому uuid.
     */
    private static void replaceUuid(SQLiteDatabase db, String table, String oldUuid, String newUuid,
                                    ContentValues values) {
        values.put("dirty", 2);
        db.update(table, values, "uuid = ?", new String[]{oldUuid});
        ContentValues clean = new ContentValues();
        clean.put("dirty", 0);
        db.update(table, clean, "uuid = ?", new String[]{newUuid});
    }

    /**
     * {@code account_operating.uuid} обязан совпадать с uuid счёта. Если сервер считает
     * счёт кошельком — своя строка кошелька берёт новый uuid и ждёт серверную (валюта
     * придёт с ней же); если категорией — своя строка кошелька убирается.
     */
    private static void adoptAccountOperating(SQLiteDatabase db, long accountId, String serverUuid,
                                              ContentValues accountCv) {
        String aoUuid = uuidById(db, "account_operating", accountId);
        if (aoUuid == null) return;
        Integer kind = accountCv.getAsInteger("kind");
        if (kind != null && kind == AccountItem.Kind.WALLET.getValue()) {
            ContentValues ao = new ContentValues();
            ao.put("uuid", serverUuid);
            replaceUuid(db, "account_operating", aoUuid, serverUuid, ao);
            // Признак актива (capital-assets) — тот же uuid, что у счёта. Он свой,
            // неотправленный: uuid меняем, dirty остаётся (поднимет триггер) — уйдёт push'ем.
            ContentValues asset = new ContentValues();
            asset.put("uuid", serverUuid);
            db.update("account_asset", asset, "_id = ?", new String[]{Long.toString(accountId)});
            // Признак займа (debts) — тот же инвариант uuid.
            db.update("account_debt", asset, "_id = ?", new String[]{Long.toString(accountId)});
        } else {
            db.delete("account_asset", "_id = ?", new String[]{Long.toString(accountId)});
            db.delete("account_debt", "_id = ?", new String[]{Long.toString(accountId)});
            db.delete("account_operating", "_id = ?", new String[]{Long.toString(accountId)});
        }
    }

    // ── Шаг после загрузки (design D7, D8) ────────────────────────────────────

    /**
     * Шаг подключения после загрузки. Кошелёк на сервере не пуст — нетронутые строки
     * набора удаляются; если остались операции без входа и «Перенести» ещё не нажато —
     * {@link Step#ASK}.
     */
    static Step afterPull(SQLiteDatabase db, boolean transferChosen) {
        if (!ledgerHasAccounts(db)) return Step.PUSH;
        dropUntouchedSeed(db);
        if (!transferChosen && offlineOperationCount(db) > 0) return Step.ASK;
        return Step.PUSH;
    }

    /** На телефоне есть живой счёт, пришедший с сервера (или принятый по ключу). */
    static boolean ledgerHasAccounts(SQLiteDatabase db) {
        return exists(db, "SELECT 1 FROM account WHERE dirty = 0 AND deleted_at IS NULL LIMIT 1");
    }

    /** Неотправленные живые операции — их судьбу решает пользователь. */
    public static int offlineOperationCount(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT count(*) FROM operation WHERE dirty <> 0 AND deleted_at IS NULL", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Удаляет неотправленные строки «из набора», на которые ничего не ссылается: категории
     * (кроме системных), затем пары, затем валюты. Удаление жёсткое — на сервере этих строк
     * нет, tombstone не нужен.
     *
     * @return сколько строк удалено
     */
    static int dropUntouchedSeed(SQLiteDatabase db) {
        int deleted = 0;
        db.beginTransaction();
        try {
            // Кошельки набора («Наличные», «Кредитная карта», design D2) — сначала строка
            // account_operating, иначе ниже счёт удерживало бы условие «не кошелёк».
            // Нетронутый — без операций, не актив и не заём; такой в живой кошелёк с сервера
            // не уходит, иначе человек получал бы две лишние пустые карточки.
            deleted += db.delete("account_operating", "dirty <> 0"
                    + " AND _id IN (SELECT _id FROM account WHERE from_seed = 1 AND dirty <> 0"
                    + " AND system_kind = " + AccountItem.SystemKind.NONE.getValue() + ")"
                    + " AND _id NOT IN (SELECT acc_fr_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_fr_id FROM recurring_operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM recurring_operation)"
                    + " AND _id NOT IN (SELECT _id FROM account_asset)"
                    + " AND _id NOT IN (SELECT _id FROM account_debt)", null);
            deleted += db.delete("account", "from_seed = 1 AND dirty <> 0 AND system_kind = "
                    + AccountItem.SystemKind.NONE.getValue()
                    + " AND _id NOT IN (SELECT acc_fr_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_fr_id FROM recurring_operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM recurring_operation)"
                    + " AND _id NOT IN (SELECT _id FROM account_operating)", null);
            deleted += db.delete("currency_pair", "from_seed = 1 AND dirty <> 0"
                    + " AND _id NOT IN (SELECT currency_pair_id FROM currency_pair_rate)"
                    + " AND _id NOT IN (SELECT currency_pair_id FROM currency_conversion"
                    + " WHERE currency_pair_id IS NOT NULL)", null);
            deleted += db.delete("currency", "from_seed = 1 AND dirty <> 0"
                    + " AND _id NOT IN (SELECT currency_id FROM account_operating)"
                    + " AND _id NOT IN (SELECT base_currency_id FROM currency_pair)"
                    + " AND _id NOT IN (SELECT quote_currency_id FROM currency_pair)"
                    + " AND _id NOT IN (SELECT from_currency_id FROM currency_conversion)"
                    + " AND _id NOT IN (SELECT to_currency_id FROM currency_conversion)", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (deleted > 0) Log.i(TAG, "нетронутых строк набора удалено: " + deleted);
        return deleted;
    }

    /**
     * «Удалить с телефона» (spec ledger-connection): неотправленные операции и
     * неотправленные счета, валюты, пары, курсы, конвертации удаляются без отправки; свои
     * иконки остаются. Строки, на которые ещё ссылаются синкнутые, не трогаются.
     */
    public static void discardOffline(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("PRAGMA defer_foreign_keys = ON");
            db.delete("operation", "dirty <> 0", null);
            // Неотправленные признаки активов (capital-assets) — раньше счетов: account_asset
            // ссылается на account, и удаление счёта упёрлось бы во внешний ключ.
            db.delete("account_asset", "dirty <> 0", null);
            // Займы (debts) — так же раньше счетов; контрагенты — только без ссылок от
            // оставшихся (синкнутых) займов.
            db.delete("account_debt", "dirty <> 0", null);
            db.delete("counterparty", "dirty <> 0"
                    + " AND uuid NOT IN (SELECT counterparty_uuid FROM account_debt)", null);
            db.delete("account_operating", "dirty <> 0"
                    + " AND _id NOT IN (SELECT acc_fr_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM operation)", null);
            db.delete("account", "dirty <> 0"
                    + " AND _id NOT IN (SELECT acc_fr_id FROM operation)"
                    + " AND _id NOT IN (SELECT acc_to_id FROM operation)"
                    + " AND _id NOT IN (SELECT _id FROM account_operating)", null);
            db.delete("currency_conversion", "dirty <> 0", null);
            db.delete("currency_pair_rate", "dirty <> 0", null);
            db.delete("currency_pair", "dirty <> 0"
                    + " AND _id NOT IN (SELECT currency_pair_id FROM currency_pair_rate)"
                    + " AND _id NOT IN (SELECT currency_pair_id FROM currency_conversion"
                    + " WHERE currency_pair_id IS NOT NULL)", null);
            db.delete("currency", "dirty <> 0"
                    + " AND _id NOT IN (SELECT currency_id FROM account_operating)"
                    + " AND _id NOT IN (SELECT base_currency_id FROM currency_pair)"
                    + " AND _id NOT IN (SELECT quote_currency_id FROM currency_pair)"
                    + " AND _id NOT IN (SELECT from_currency_id FROM currency_conversion)"
                    + " AND _id NOT IN (SELECT to_currency_id FROM currency_conversion)", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ── Тексты диалога (spec ledger-connection) — ресурсы strings_sync.xml ────

    /** «На телефоне 7 операций, внесённых до входа. Перенести их в кошелёк на сервере?» */
    public static String questionText(int n) {
        return com.example.wallettransactions.Texts.plural(
                com.example.wallettransactions.R.plurals.connection_question, n);
    }

    /** «Удалить 7 операций с телефона? Отменить это нельзя.» */
    public static String confirmDiscardText(int n) {
        return com.example.wallettransactions.Texts.plural(
                com.example.wallettransactions.R.plurals.connection_confirm_discard, n);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean exists(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private static Long idByUuid(SQLiteDatabase db, String table, String uuid) {
        Cursor c = db.rawQuery("SELECT _id FROM " + table + " WHERE uuid = ? LIMIT 1", new String[]{uuid});
        try {
            return c.moveToFirst() ? c.getLong(0) : null;
        } finally {
            c.close();
        }
    }

    private static String uuidById(SQLiteDatabase db, String table, long id) {
        Cursor c = db.rawQuery("SELECT uuid FROM " + table + " WHERE _id = ? LIMIT 1",
                new String[]{Long.toString(id)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }
}
