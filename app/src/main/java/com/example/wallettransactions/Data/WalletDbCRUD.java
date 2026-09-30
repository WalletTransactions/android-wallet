package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.util.Log;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Operations.OperationItem;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WalletDbCRUD {

    private static final String TAG = "WalletDbCRUD";

    /**
     * Finds and removes logically broken operations.
     * A broken operation is one where both accounts have no currency (both are categories).
     * Returns list of deleted operation IDs for logging/display.
     */
    public static List<Integer> repairDatabase(SQLiteDatabase db) {
        List<Integer> deletedIds = new ArrayList<>();

        // Find transactions where both accounts are categories (not in account_operating)
        Cursor c = db.rawQuery(
                "SELECT o._id, o.date, af.name acc_fr, at.name acc_to, o.amount" +
                " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o" +
                " JOIN account af ON af._id = o.acc_fr_id" +
                " JOIN account at ON at._id = o.acc_to_id" +
                " LEFT JOIN account_operating aof ON aof._id = o.acc_fr_id" +
                " LEFT JOIN account_operating aot ON aot._id = o.acc_to_id" +
                " WHERE aof._id IS NULL AND aot._id IS NULL",
                null);

        try {
            while (c.moveToNext()) {
                int id       = c.getInt(0);
                String date  = c.getString(1);
                String fr    = c.getString(2);
                String to    = c.getString(3);
                double amt   = c.getDouble(4);
                Log.w(TAG, String.format(
                        "Broken operation id=%d  %s  %s→%s  amount=%.2f — will delete",
                        id, date, fr, to, amt));
                deletedIds.add(id);
            }
        } finally {
            c.close();
        }

        for (int id : deletedIds) {
            db.delete(WalletContract.OperationEntry.TABLE_NAME,
                    WalletContract.OperationEntry._ID + "=?",
                    new String[]{String.valueOf(id)});
            Log.i(TAG, "Deleted broken operation id=" + id);
        }

        return deletedIds;
    }

    public static boolean HasOperations(SQLiteDatabase db, int accountId) {
        // deleted_at IS NULL — с v72 (soft-delete, см. DeleteOperation) мягко
        // удалённая проводка не должна больше считаться "занятостью" счёта.
        String id = String.valueOf(accountId);
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + WalletContract.OperationEntry.TABLE_NAME +
                " WHERE (" + WalletContract.OperationEntry.COLUMN_FROM + "=? OR " +
                WalletContract.OperationEntry.COLUMN_TO + "=?)" +
                " AND " + WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{id, id});
        c.moveToFirst();
        boolean has = c.getInt(0) > 0;
        c.close();
        return has;
    }

    // ── Операции и их связи ───────────────────────────────────────────────────

    public static long InsertOperation(SQLiteDatabase db, String date, int accFromId, int accToId,
                                       double amount, String comment) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date);
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId);
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId);
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount);
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment);
        cv.put(WalletContract.OperationEntry.COLUMN_UUID, UUID.randomUUID().toString());
        return db.insert(WalletContract.OperationEntry.TABLE_NAME, null, cv);
    }

    public static int UpdateOperation(SQLiteDatabase db, int operationId, String date,
                                      int accFromId, int accToId, double amount, String comment) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date);
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId);
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId);
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount);
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment);
        return db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry._ID + "=?",
                new String[]{String.valueOf(operationId)});
    }

    /**
     * "Удаляет" проводку — мягко, с v72 (org/docs/TODO_mobile_sync.md, Фаза 3):
     * {@code deleted_at} вместо настоящего {@code DELETE}, иначе удаление
     * никогда не долетело бы до сервера и других устройств — сообщать об
     * исчезнувшей строке через push было бы нечем. {@code dirty=1} тут не
     * нужен явно — форсит триггер {@code trg_operation_dirty}
     * (schema/triggers/trg_sync_dirty.sql), раз этот UPDATE его не
     * трогает. Раньше строки {@code operation_link} уходили сами через
     * {@code ON DELETE CASCADE} — теперь, раз операция физически остаётся,
     * они тоже остаются (то же самое поведение, что и на сервере: внешние
     * ключи остаются целыми, см. README wallet-api/schema, "Побочная выгода").
     */
    public static int DeleteOperation(SQLiteDatabase db, int operationId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        return db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry._ID + "=?",
                new String[]{String.valueOf(operationId)});
    }

    /**
     * Цепляет проводку к главной — списанию с кошелька-источника. Так вторая нога
     * валютного перевода и комиссия находятся по одной операции. {@code type} —
     * явная роль связки (v77, {@code operation_link.type}), см. {@link OperationItem.LinkType}.
     */
    public static void LinkOperations(SQLiteDatabase db, long mainId, long linkId, OperationItem.LinkType type) {
        if (mainId <= 0 || linkId <= 0 || mainId == linkId) return;

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationLinkEntry.COLUMN_MAIN_ID, mainId);
        cv.put(WalletContract.OperationLinkEntry.COLUMN_LINK_ID, linkId);
        cv.put(WalletContract.OperationLinkEntry.COLUMN_TYPE, type.getValue());
        cv.put(WalletContract.OperationLinkEntry.COLUMN_UUID, UUID.randomUUID().toString());
        db.insertWithOnConflict(WalletContract.OperationLinkEntry.TABLE_NAME, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** ID проводок, привязанных к главной, вместе с ролью каждой связки ({@link OperationItem.LinkType}). */
    public static Map<Integer, OperationItem.LinkType> GetLinkedOperations(SQLiteDatabase db, int mainId) {
        Map<Integer, OperationItem.LinkType> links = new LinkedHashMap<>();
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID
                        + ", " + WalletContract.OperationLinkEntry.COLUMN_TYPE +
                " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                " WHERE " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + "=?",
                new String[]{String.valueOf(mainId)});
        try {
            while (c.moveToNext()) links.put(c.getInt(0), OperationItem.LinkType.fromValue(c.getInt(1)));
        } finally {
            c.close();
        }
        return links;
    }

    /** ID счёта по имени или -1. deleted_at IS NULL — не резолвить в мягко удалённый tombstone. */
    public static int GetAccountIdByName(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry._ID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry.COLUMN_NAME + "=? COLLATE NOCASE" +
                " AND " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{name});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    /**
     * Категория с таким именем, при необходимости заведённая на месте. Нужна для служебных
     * категорий («Transfer», «Transfer commission»): в старых базах их может не быть —
     * начальный справочник накатывался один раз, до того как они понадобились.
     *
     * @deprecated для системных категорий («Transfer», «Transfer commission») используйте
     * {@link #GetOrCreateSystemCategory} — тот ищет по {@code system_kind}, а не по имени,
     * и переживает переименование категории пользователем (после v75 имя больше не участвует
     * в определении системности). Этот метод остаётся низкоуровневым инструментом поиска/
     * создания категории ПО ИМЕНИ — на сегодня в коде не осталось мест, где это нужно для
     * НЕ-системной категории, но публичный метод не убран на случай, если понадобится снова.
     */
    public static int GetOrCreateCategory(SQLiteDatabase db, String name, String fallbackIconName) {
        int id = GetAccountIdByName(db, name);
        if (id > 0) return id;
        return createCategory(db, name, fallbackIconName, AccountItem.SystemKind.NONE.getValue());
    }

    /**
     * Находит системную категорию по system_kind (а не по имени — имя пользователь может
     * сменить, incidentally сломав старую логику распознавания, см. {@link AccountItem.SystemKind}).
     * Если не нашлась — заводит новую с заданными name/иконкой и сразу проставляет system_kind.
     */
    public static int GetOrCreateSystemCategory(SQLiteDatabase db, int systemKind,
                                                 String defaultName, String iconSourceName) {
        int id = GetAccountIdBySystemKind(db, systemKind);
        if (id > 0) return id;
        return createCategory(db, defaultName, iconSourceName, systemKind);
    }

    /**
     * Общая часть {@link #GetOrCreateCategory}/{@link #GetOrCreateSystemCategory} —
     * подбор иконки (сначала запрошенная по имени, иначе "unknown") и сама вставка строки,
     * включая её {@code system_kind} (0 — обычная категория, для системных — конкретное
     * значение {@link AccountItem.SystemKind}).
     */
    private static int createCategory(SQLiteDatabase db, String name, String fallbackIconName, int systemKind) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID
                + " FROM " + WalletContract.IconEntry.TABLE_NAME
                + " WHERE " + WalletContract.IconEntry.COLUMN_NAME + " IN (?, 'unknown')"
                + " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL"
                + " ORDER BY CASE WHEN " + WalletContract.IconEntry.COLUMN_NAME + "=? THEN 0 ELSE 1 END"
                + " LIMIT 1", new String[]{fallbackIconName, fallbackIconName});
        int iconId;
        try {
            iconId = c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
        if (iconId <= 0) {
            Log.w(TAG, "No icon to create category " + name);
            return -1;
        }

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name);
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, name);
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 0);
        cv.put(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND, systemKind);
        // Обе категории (обычная и системная) — kind=CATEGORY: createCategory
        // никогда не заводит кошелёк (для этого есть InsertOperatingAccount).
        cv.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.CATEGORY.getValue());
        cv.put(WalletContract.AccountEntry.COLUMN_UUID, UUID.randomUUID().toString());

        long newId = db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv);
        return newId > 0 ? (int) newId : -1;
    }

    /** _id счёта с данным system_kind (deleted_at IS NULL) или -1, если такого нет. */
    private static int GetAccountIdBySystemKind(SQLiteDatabase db, int systemKind) {
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry._ID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + "=?" +
                " AND " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(systemKind)});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    /** Имя счёта или null, если такого счёта уже нет. */
    public static String GetAccountName(SQLiteDatabase db, int accountId) {
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry.COLUMN_NAME +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry._ID + "=?",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * system_kind счёта, или {@link AccountItem.SystemKind#NONE}.getValue(), если такого
     * счёта уже нет — зеркально {@link #GetAccountName}, используется вместо распознавания
     * системности по имени (см. {@link DeleteAccount}).
     */
    public static int GetAccountSystemKind(SQLiteDatabase db, long accountId) {
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry._ID + "=?",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : AccountItem.SystemKind.NONE.getValue();
        } finally {
            c.close();
        }
    }

    /** Чем кончилась попытка удалить счёт: удалили или почему нет. */
    public enum DeleteAccountResult { deleted, systemAccount, hasOperations }

    /**
     * Удаляет счёт — только пустой и только несистемный. Обе проверки стоят здесь, а не
     * в UI, чтобы правило держалось на всех путях удаления:
     *
     * <ul>
     *   <li>системный ({@code system_kind != NONE}, см. {@link AccountItem.SystemKind}) —
     *   его id зашит в код перевода и комиссии (найден через
     *   {@link #GetOrCreateSystemCategory}), так что без него ломается уже проведённое;</li>
     *   <li>с операциями — удаление увело бы их вместе с собой. На случай, если проверку
     *   обойдут, за ней стоит внешний ключ: {@code operation.acc_fr_id/acc_to_id}
     *   ссылаются на {@code account._id}, а {@code PRAGMA foreign_keys} включён в
     *   {@link WalletDbHelper#getWritableDatabase()}, поэтому такой DELETE упал бы с
     *   {@code SQLiteConstraintException}.</li>
     * </ul>
     *
     * Убрать категорию, по которой уже есть операции, можно будет только слиянием —
     * перенести операции в другую категорию и удалить опустевшую (TODO.md).
     */
    public static DeleteAccountResult DeleteAccount(SQLiteDatabase db, int accountId) {
        if (GetAccountSystemKind(db, accountId) != AccountItem.SystemKind.NONE.getValue()) {
            Log.w(TAG, "Refused to delete system account id=" + accountId);
            return DeleteAccountResult.systemAccount;
        }

        if (HasOperations(db, accountId)) {
            Log.w(TAG, "Refused to delete account id=" + accountId + " — it has operations");
            return DeleteAccountResult.hasOperations;
        }

        // Мягкое удаление с v72 (см. javadoc DeleteOperation выше, тот же довод) —
        // dirty=1 форсит триггер, здесь не нужен явно.
        String id = String.valueOf(accountId);
        long now = System.currentTimeMillis();

        ContentValues cvOp = new ContentValues();
        cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cvOp,
                WalletContract.AccountOperatingEntry._ID + "=?", new String[]{id});

        ContentValues cvAcc = new ContentValues();
        cvAcc.put(WalletContract.AccountEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountEntry.TABLE_NAME, cvAcc,
                WalletContract.AccountEntry._ID + "=?", new String[]{id});
        return DeleteAccountResult.deleted;
    }

    public static int UpdateAccountCurrency(SQLiteDatabase db, int accountId, int currencyId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId);
        return db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cv,
                WalletContract.AccountOperatingEntry._ID + "=?",
                new String[]{String.valueOf(accountId)});
    }

    public static long InsertOperatingAccount(SQLiteDatabase db, String name, String comment,
                                              int iconId, int currencyId) {
        String accountUuid = UUID.randomUUID().toString();
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name);
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, comment);
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 0);
        // kind=WALLET — эта строка account тут же получает пару в account_operating
        // (ниже), то есть становится кошельком (см. javadoc AccountItem.Kind).
        cv.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.getValue());
        cv.put(WalletContract.AccountEntry.COLUMN_UUID, accountUuid);
        long newId = db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv);
        if (newId > 0) {
            ContentValues cvOp = new ContentValues();
            cvOp.put(WalletContract.AccountOperatingEntry._ID, newId);
            cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId);
            // uuid — ТОТ ЖЕ, что у только что вставленного account, не свежий
            // случайный (см. wallet-api/schema/table/account_operating.sql:
            // "uuid здесь всегда равен account.uuid" — общий первичный ключ на
            // уровне синка; иначе push такого кошелька упал бы FK-ошибкой
            // fk_account_operating_account на сервере).
            cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_UUID, accountUuid);
            db.insert(WalletContract.AccountOperatingEntry.TABLE_NAME, null, cvOp);
        }
        return newId;
    }

    /**
     * v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — голый {@code INSERT OR
     * REPLACE}, а для SQLite это "удалить старую строку целиком и вставить новую"
     * — заново сгенерированный {@code uuid} на каждую перезагрузку одного и того же
     * курса раздувал бы поток синка дублями вместо одного обновления. Теперь:
     * нашлась строка на эту (пара, дата) — обновляем (uuid не трогаем, dirty
     * форсит триггер {@code trg_currency_pair_rate_dirty}), не нашлась — вставляем
     * с новым uuid.
     */
    public static void InsertOrReplaceRate(SQLiteDatabase db, String pairSymbol, String date, double rate) {
        int pairId = getCurrencyPairIdBySymbol(db, pairSymbol);
        if (pairId <= 0) return;

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_RATE, rate);

        // deleted_at IS NULL — реальный баг, найденный на живом устройстве
        // (2026-09-11, тот же класс в WalletFillData.InsertCurrencyPairRate/
        // RateEditDialog.saveRate): без этого условия matcher "оживлял" мягко
        // удалённую строку, оставляя deleted_at проставленным, — сервер уже
        // не покрывает такую строку частичным индексом uq_currency_pair_rate_
        // ledger (WHERE deleted_at IS NULL), ON CONFLICT по нему не находил
        // "конфликта" и падал на currency_pair_rate_pkey голым INSERT'ом с уже
        // занятым uuid ("duplicate_key").
        int updated = db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND "
                        + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + "=? AND "
                        + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(pairId), date});
        if (updated > 0) return;

        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE, pairId);
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, date);
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_UUID, UUID.randomUUID().toString());
        db.insert(WalletContract.CurrencyPairRateEntry.TABLE_NAME, null, cv);
    }

    private static int getCurrencyPairIdBySymbol(SQLiteDatabase db, String symbol) {
        Cursor c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME
                + " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?"
                + " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{symbol});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    public static long InsertCurrencyPair(SQLiteDatabase db, String baseIso, String quoteIso, int precision) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_SYMBOL, baseIso + quoteIso);
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID,
                getCurrencyIdByIso(db, baseIso));
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID,
                getCurrencyIdByIso(db, quoteIso));
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_PRECISION, precision);
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_UUID, UUID.randomUUID().toString());
        return db.insert(WalletContract.CurrencyPairEntry.TABLE_NAME, null, cv);
    }

    // deleted_at IS NULL — иначе после soft-delete (см. DeleteCurrency) и
    // пересоздания валюты с тем же ISO-кодом (теперь разрешено — см. частичный
    // индекс uq_currency_iso_code) эта функция резолвила бы в СТАРУЮ, удалённую
    // строку вместо новой живой.
    private static int getCurrencyIdByIso(SQLiteDatabase db, String iso) {
        Cursor c = db.rawQuery(
                "SELECT _id FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + "=?" +
                " AND " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{iso});
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return -1;
        } finally {
            c.close();
        }
    }

    public static long InsertIcon(SQLiteDatabase db, String name, int iconType, Bitmap bitmap) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        byte[] bytes = bos.toByteArray();

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, iconType);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, bytes);
        cv.put(WalletContract.IconEntry.COLUMN_UUID, UUID.randomUUID().toString());
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    public static int InsertAccount(SQLiteDatabase db, String accName, String accComment, int iconId, boolean isArchive){
        db.execSQL("insert into account (name, comment, icon_id,is_archived,uuid)\n" +
                        "Values(?,?,?,?,?); \n",
                new String[]{accName, accComment, String.valueOf(iconId), isArchive?"1":"0",
                        UUID.randomUUID().toString()});
        return WalletFillData.GetAffectedRowsCount(db) ;
    }


    public static int UpdateAccount(SQLiteDatabase db, int id, String accName, String accComment, int iconId, boolean isArchive){
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, accName);
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, accComment);
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, isArchive);
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, accName);

        return db.update(WalletContract.AccountEntry.TABLE_NAME, cv, WalletContract.AccountEntry._ID+"="+id, null);
    }

    // ── Валюты (экран «Список валют» → форма валюты) ───────────────────────────

    public static long InsertCurrency(SQLiteDatabase db, String name, String iso,
                                      String symbol, int precision, int iconId) {
        // uuid — только здесь, не в currencyValues(): тот же метод используют
        // Insert и Update, а uuid не должен меняться при правке существующей валюты.
        ContentValues cv = currencyValues(name, iso, symbol, precision, iconId);
        cv.put(WalletContract.CurrencyEntry.COLUMN_UUID, UUID.randomUUID().toString());
        return db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, cv);
    }

    public static int UpdateCurrency(SQLiteDatabase db, int id, String name, String iso,
                                     String symbol, int precision, int iconId) {
        return db.update(WalletContract.CurrencyEntry.TABLE_NAME,
                currencyValues(name, iso, symbol, precision, iconId),
                WalletContract.CurrencyEntry._ID + "=?", new String[]{Integer.toString(id)});
    }

    private static ContentValues currencyValues(String name, String iso, String symbol,
                                                int precision, int iconId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, name);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso);
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol);
        cv.put(WalletContract.CurrencyEntry.COLUMN_PRECISION, precision);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId);
        return cv;
    }

    /**
     * Валюта, на которую ссылается кошелёк или валютная пара, унесла бы за собой
     * счёт без валюты и курс без пары — поэтому такие удалять нельзя.
     */
    // deleted_at IS NULL на обеих проверках — с v72 мягко удалённый кошелёк/пара
    // не должен больше считаться "занятостью" валюты (тот же довод, что у
    // HasOperations выше).
    public static boolean IsCurrencyInUse(SQLiteDatabase db, int currencyId) {
        return countRows(db, "SELECT COUNT(*) FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID + "=?"
                + " AND " + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL", currencyId) > 0
            || countRows(db, "SELECT COUNT(*) FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME
                + " WHERE (" + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID + "=?"
                + " OR " + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID + "=?)"
                + " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                currencyId, currencyId) > 0;
    }

    /** Мягкое удаление с v72 — тот же довод, что у DeleteOperation/DeleteAccount выше. */
    public static int DeleteCurrency(SQLiteDatabase db, int currencyId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        return db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry._ID + "=?", new String[]{Integer.toString(currencyId)});
    }

    private static int countRows(SQLiteDatabase db, String sql, int... args) {
        String[] params = new String[args.length];
        for (int i = 0; i < args.length; i++) params[i] = Integer.toString(args[i]);

        Cursor c = db.rawQuery(sql, params);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }
}
