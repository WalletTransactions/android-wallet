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
import java.util.Locale;
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

    /** {@link #InsertOperationWithUuid}: живая операция с этим uuid уже есть — вставки не было. */
    public static final long UUID_TAKEN = -2;

    /** Операция, найденная по uuid: локальный {@code _id} и удалена ли она. */
    public static final class UuidMatch {
        public final long id;
        public final boolean deleted;

        UuidMatch(long id, boolean deleted) {
            this.id = id;
            this.deleted = deleted;
        }
    }

    /**
     * Операция с этим uuid в любом написании (с дефисами и без, любой регистр — как
     * {@code ux_operation_uuid}, v83), живая или удалённая; {@code null} — такой нет.
     */
    public static UuidMatch FindOperationByUuid(SQLiteDatabase db, String uuid) {
        String key = uuid.toLowerCase(Locale.ROOT).replace("-", "");
        Cursor c = db.rawQuery("SELECT _id, deleted_at FROM " + WalletContract.OperationEntry.TABLE_NAME
                + " WHERE lower(replace(uuid, '-', '')) = ? LIMIT 1", new String[]{key});
        try {
            return c.moveToFirst() ? new UuidMatch(c.getLong(0), !c.isNull(1)) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Проводка с заданным заранее uuid (openspec add-ai-statement-import, operation-identity:
     * строка импорта выписки пишется с {@code uuid = row_id}). Живая с этим uuid уже
     * есть — {@link #UUID_TAKEN}, вторая не создаётся. Удалённая есть — та же строка
     * получает новые поля и снова живая (dirty поднимет триггер синка).
     *
     * @return {@code _id} новой или восстановленной строки, {@link #UUID_TAKEN} или -1
     */
    public static long InsertOperationWithUuid(SQLiteDatabase db, String uuid, String date, int accFromId,
                                               int accToId, double amount, String comment) {
        UuidMatch existing = FindOperationByUuid(db, uuid);
        if (existing != null && !existing.deleted) return UUID_TAKEN;

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date);
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId);
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId);
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount);
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment);
        if (existing != null) {
            cv.putNull(WalletContract.OperationEntry.COLUMN_DELETED_AT);
            int n = db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                    WalletContract.OperationEntry._ID + "=?", new String[]{String.valueOf(existing.id)});
            return n == 1 ? existing.id : -1;
        }
        cv.put(WalletContract.OperationEntry.COLUMN_UUID, uuid);
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

    /**
     * Занято ли имя другим живым счётом (правило {@link AccountNames}) — проверка до
     * записи, чтобы экран показал ошибку, а не упёрся в uq_account_name.
     * exceptId — сам редактируемый счёт (-1 при создании): «кафе» → «Кафе» — не конфликт.
     */
    public static boolean IsAccountNameTaken(SQLiteDatabase db, String name, int exceptId) {
        int id = GetAccountIdByName(db, name);
        return id > 0 && id != exceptId;
    }

    /**
     * ID счёта по имени (правило {@link AccountNames}) или -1. deleted_at IS NULL — не
     * резолвить в мягко удалённый tombstone; то же условие у индекса uq_account_name.
     */
    public static int GetAccountIdByName(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry._ID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME) +
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

        // Признак актива уходит вместе с кошельком (capital-assets) — живой строки
        // account_asset у удалённого кошелька быть не должно.
        ContentValues cvAsset = new ContentValues();
        cvAsset.put(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cvAsset,
                WalletContract.AccountAssetEntry._ID + "=? AND "
                        + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL", new String[]{id});
        // То же для признака долга (debts): займ без кошелька не живёт.
        ContentValues cvDebt = new ContentValues();
        cvDebt.put(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cvDebt,
                WalletContract.AccountDebtEntry._ID + "=? AND "
                        + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL", new String[]{id});

        ContentValues cvAcc = new ContentValues();
        cvAcc.put(WalletContract.AccountEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountEntry.TABLE_NAME, cvAcc,
                WalletContract.AccountEntry._ID + "=?", new String[]{id});
        return DeleteAccountResult.deleted;
    }

    // ── Капитальные активы (openspec capital-assets) ───────────────────────────

    /**
     * Помечает кошелёк капитальным активом или меняет тип/«Учитывать во „Всего с
     * капиталом“» (design п. 1). Строка одна на счёт: снятая раньше (мягко удалённая)
     * оживает с тем же uuid, второй строки не появляется. dirty поднимает триггер или
     * DEFAULT. Только для кошелька с живой {@code account_operating}.
     *
     * @return false — счёт не кошелёк (категория или удалён), ничего не записано
     */
    public static boolean SetAccountAsset(SQLiteDatabase db, int accountId, int assetType, boolean includeInNetWorth) {
        String id = String.valueOf(accountId);
        String uuid;
        Cursor c = db.rawQuery("SELECT a." + WalletContract.AccountEntry.COLUMN_UUID
                + " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a"
                + " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao."
                + WalletContract.AccountOperatingEntry._ID + " = a." + WalletContract.AccountEntry._ID
                + " AND ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL"
                + " WHERE a." + WalletContract.AccountEntry._ID + " = ? AND a."
                + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", new String[]{id});
        try {
            if (!c.moveToFirst()) {
                Log.w(TAG, "SetAccountAsset: account id=" + accountId + " is not a live wallet");
                return false;
            }
            uuid = c.getString(0);
        } finally {
            c.close();
        }
        // Кошелёк и актив, и долг — не предлагается (debts, сценарий «Кошелёк уже актив»).
        if (IsDebtWallet(db, accountId)) {
            Log.w(TAG, "SetAccountAsset: account id=" + accountId + " is a debt wallet");
            return false;
        }

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountAssetEntry.COLUMN_ASSET_TYPE, assetType);
        cv.put(WalletContract.AccountAssetEntry.COLUMN_INCLUDE_IN_NET_WORTH, includeInNetWorth ? 1 : 0);
        cv.putNull(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT);
        int updated = db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cv,
                WalletContract.AccountAssetEntry._ID + "=?", new String[]{id});
        if (updated > 0) return true;

        cv.put(WalletContract.AccountAssetEntry._ID, accountId);
        // uuid = account.uuid — тот же инвариант, что у account_operating.
        cv.put(WalletContract.AccountAssetEntry.COLUMN_UUID, uuid);
        return db.insert(WalletContract.AccountAssetEntry.TABLE_NAME, null, cv) > 0;
    }

    /** Снимает признак актива — мягкое удаление строки, операции кошелька не трогаются. */
    public static void ClearAccountAsset(SQLiteDatabase db, int accountId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cv,
                WalletContract.AccountAssetEntry._ID + "=? AND "
                        + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(accountId)});
    }

    /** Есть ли на телефоне хоть один живой актив — для действия «Перенести операции в актив». */
    public static boolean HasAssets(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountAssetEntry.TABLE_NAME + " aa"
                + " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a ON a._id = aa._id AND a.deleted_at IS NULL"
                + " WHERE aa." + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL LIMIT 1", null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    // ── Долги (openspec debts) ─────────────────────────────────────────────────

    /** Результат {@link #DeleteCounterparty}. */
    public enum DeleteCounterpartyResult { deleted, hasDebts, notFound }

    /** Имя контрагента для записи: пробелы по краям обрезаются (design D1). */
    public static String NormalizeCounterpartyName(String name) {
        return name == null ? "" : name.trim();
    }

    /**
     * _id живого контрагента с тем же именем (регистр, «ё»/«е», пробелы по краям — правило
     * {@link AccountNames}, индекс {@code uq_counterparty_name}) или -1.
     */
    public static int GetCounterpartyIdByName(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry._ID
                + " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME
                + " WHERE " + AccountNames.sameSql(WalletContract.CounterpartyEntry.COLUMN_NAME)
                + " AND " + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{NormalizeCounterpartyName(name)});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    /**
     * Заводит контрагента. Имя занято (сценарии «Дубль имени», «Дубль имени через „ё“») или
     * пустое — ничего не пишет и возвращает -1: экран предлагает выбрать существующего
     * через {@link #GetCounterpartyIdByName}.
     */
    public static int CreateCounterparty(SQLiteDatabase db, String name, String comment) {
        String clean = NormalizeCounterpartyName(name);
        if (clean.isEmpty() || GetCounterpartyIdByName(db, clean) > 0) return -1;
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyEntry.COLUMN_NAME, clean);
        cv.put(WalletContract.CounterpartyEntry.COLUMN_COMMENT, comment == null ? "" : comment);
        cv.put(WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED, 0);
        cv.put(WalletContract.CounterpartyEntry.COLUMN_UUID, UUID.randomUUID().toString());
        long id = db.insert(WalletContract.CounterpartyEntry.TABLE_NAME, null, cv);
        return id > 0 ? (int) id : -1;
    }

    /** Переименование; false — имя пустое или занято другим живым контрагентом. */
    public static boolean RenameCounterparty(SQLiteDatabase db, int id, String name) {
        String clean = NormalizeCounterpartyName(name);
        if (clean.isEmpty()) return false;
        int other = GetCounterpartyIdByName(db, clean);
        if (other > 0 && other != id) return false;
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyEntry.COLUMN_NAME, clean);
        return db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=? AND "
                        + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(id)}) > 0;
    }

    /** Комментарий контрагента. */
    public static void UpdateCounterpartyComment(SQLiteDatabase db, int id, String comment) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyEntry.COLUMN_COMMENT, comment == null ? "" : comment);
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Архив и разархив: займы остаются, контрагент пропадает из выбора при вводе. */
    public static void ArchiveCounterparty(SQLiteDatabase db, int id, boolean archived) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED, archived ? 1 : 0);
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", new String[]{String.valueOf(id)});
    }

    /**
     * Мягкое удаление контрагента — только без живых займов (сценарий «Удаление контрагента
     * с долгом»; design D12). Локальная привязка к контакту уходит вместе с ним.
     */
    public static DeleteCounterpartyResult DeleteCounterparty(SQLiteDatabase db, int id) {
        String uuid = counterpartyUuid(db, id);
        if (uuid == null) return DeleteCounterpartyResult.notFound;
        if (CountLiveDebts(db, uuid) > 0) {
            Log.w(TAG, "Refused to delete counterparty id=" + id + " — it has live debts");
            return DeleteCounterpartyResult.hasDebts;
        }
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", new String[]{String.valueOf(id)});
        db.delete(WalletContract.CounterpartyContactEntry.TABLE_NAME,
                WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID + "=?", new String[]{uuid});
        return DeleteCounterpartyResult.deleted;
    }

    /** Привязка к контакту телефона (design D7) — только локальная, без синка. */
    public static void SetCounterpartyContact(SQLiteDatabase db, String counterpartyUuid, String lookupKey) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID, counterpartyUuid);
        cv.put(WalletContract.CounterpartyContactEntry.COLUMN_LOOKUP_KEY, lookupKey);
        db.insertWithOnConflict(WalletContract.CounterpartyContactEntry.TABLE_NAME, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** {@code LOOKUP_KEY} контакта контрагента или null. */
    public static String GetCounterpartyContact(SQLiteDatabase db, String counterpartyUuid) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.CounterpartyContactEntry.COLUMN_LOOKUP_KEY
                + " FROM " + WalletContract.CounterpartyContactEntry.TABLE_NAME
                + " WHERE " + WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID + " = ?",
                new String[]{counterpartyUuid});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** Сколько живых долговых кошельков у контрагента (живая строка {@code account_debt} у живого счёта). */
    public static int CountLiveDebts(SQLiteDatabase db, String counterpartyUuid) {
        Cursor c = db.rawQuery("SELECT count(*) FROM " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad"
                + " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a ON a._id = ad._id AND a.deleted_at IS NULL"
                + " WHERE ad." + WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID + " = ?"
                + " AND ad." + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{counterpartyUuid});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** uuid живого контрагента по _id или null — для экранов вне пакета Data. */
    public static String CounterpartyUuid(SQLiteDatabase db, int id) {
        return counterpartyUuid(db, id);
    }

    /**
     * Имя контрагента по имени кошелька-займа: хвост « · <ISO>» и « · <ISO> · N» шаблона
     * {@link #DebtWalletName} отбрасывается («Александ Кулигин · EUR» → «Александ Кулигин»),
     * другое имя — как есть, без пробелов по краям.
     */
    public static String SuggestCounterpartyName(String walletName) {
        if (walletName == null) return "";
        return walletName.replaceFirst("\\s*·\\s*[A-Z]{3}(\\s*·\\s*\\d+)?\\s*$", "").trim(); // not UI: pattern
    }

    /** uuid живого контрагента по _id или null. */
    static String counterpartyUuid(SQLiteDatabase db, int id) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry.COLUMN_UUID
                + " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME
                + " WHERE " + WalletContract.CounterpartyEntry._ID + "=? AND "
                + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** Есть ли у счёта живая строка {@code account_debt}. */
    public static boolean IsDebtWallet(SQLiteDatabase db, int accountId) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountDebtEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountDebtEntry._ID + "=? AND "
                + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** Есть ли у счёта живая строка {@code account_asset}. */
    static boolean isAssetWallet(SQLiteDatabase db, int accountId) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountAssetEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountAssetEntry._ID + "=? AND "
                + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /**
     * Помечает кошелёк займом контрагента или меняет комментарий/срок (design D1). Строка
     * одна на счёт: снятая раньше оживает с тем же uuid. Отказ (false, ничего не записано):
     * счёт не живой кошелёк (категория, удалён), кошелёк — капитальный актив, контрагент
     * не найден среди живых.
     *
     * @param dueDate срок {@code yyyy-MM-dd} или null
     */
    public static boolean SetAccountDebt(SQLiteDatabase db, int accountId, String counterpartyUuid,
                                         String comment, String dueDate) {
        String id = String.valueOf(accountId);
        String uuid;
        Cursor c = db.rawQuery("SELECT a." + WalletContract.AccountEntry.COLUMN_UUID
                + " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a"
                + " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao."
                + WalletContract.AccountOperatingEntry._ID + " = a." + WalletContract.AccountEntry._ID
                + " AND ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL"
                + " WHERE a." + WalletContract.AccountEntry._ID + " = ? AND a."
                + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", new String[]{id});
        try {
            if (!c.moveToFirst()) {
                Log.w(TAG, "SetAccountDebt: account id=" + accountId + " is not a live wallet");
                return false;
            }
            uuid = c.getString(0);
        } finally {
            c.close();
        }
        if (isAssetWallet(db, accountId)) {
            Log.w(TAG, "SetAccountDebt: account id=" + accountId + " is an asset");
            return false;
        }
        if (counterpartyUuid == null || countRowsByUuid(db, WalletContract.CounterpartyEntry.TABLE_NAME, counterpartyUuid) == 0) {
            Log.w(TAG, "SetAccountDebt: unknown counterparty " + counterpartyUuid);
            return false;
        }

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID, counterpartyUuid);
        cv.put(WalletContract.AccountDebtEntry.COLUMN_COMMENT, comment == null ? "" : comment);
        if (dueDate == null) cv.putNull(WalletContract.AccountDebtEntry.COLUMN_DUE_DATE);
        else cv.put(WalletContract.AccountDebtEntry.COLUMN_DUE_DATE, dueDate);
        cv.putNull(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT);
        int updated = db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cv,
                WalletContract.AccountDebtEntry._ID + "=?", new String[]{id});
        if (updated > 0) return true;

        cv.put(WalletContract.AccountDebtEntry._ID, accountId);
        // uuid = account.uuid — тот же инвариант, что у account_operating и account_asset.
        cv.put(WalletContract.AccountDebtEntry.COLUMN_UUID, uuid);
        return db.insert(WalletContract.AccountDebtEntry.TABLE_NAME, null, cv) > 0;
    }

    /** Снимает признак долга — мягкое удаление строки, операции кошелька не трогаются. */
    public static void ClearAccountDebt(SQLiteDatabase db, int accountId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cv,
                WalletContract.AccountDebtEntry._ID + "=? AND "
                        + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(accountId)});
    }

    /**
     * «Новый заём» (сценарии «Новый заём», «Второй заём в той же валюте»): кошелёк
     * «<имя> · <ISO>» в валюте займа с живой строкой {@code account_debt}; имя занято любым
     * живым счётом — « · 2», « · 3»… Иконка — иконка контрагента, если она есть на телефоне,
     * иначе «unknown». Вызывать в транзакции: при отказе на середине кошелёк без признака
     * долга не должен остаться.
     *
     * @return _id нового кошелька или -1 (контрагент не найден, нет иконки, валюта не найдена)
     */
    public static int CreateDebtWallet(SQLiteDatabase db, int counterpartyId, int currencyId,
                                       String comment, String dueDate) {
        String cpUuid = null, cpName = null, cpIcon = null;
        Cursor c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry.COLUMN_UUID
                + ", " + WalletContract.CounterpartyEntry.COLUMN_NAME
                + ", " + WalletContract.CounterpartyEntry.COLUMN_ICON_UUID
                + " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME
                + " WHERE " + WalletContract.CounterpartyEntry._ID + "=? AND "
                + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(counterpartyId)});
        try {
            if (c.moveToFirst()) {
                cpUuid = c.getString(0);
                cpName = c.getString(1);
                cpIcon = c.isNull(2) ? null : c.getString(2);
            }
        } finally {
            c.close();
        }
        if (cpUuid == null) return -1;

        String iso = null;
        c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME
                + " FROM " + WalletContract.CurrencyEntry.TABLE_NAME
                + " WHERE " + WalletContract.CurrencyEntry._ID + "=? AND "
                + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(currencyId)});
        try {
            if (c.moveToFirst()) iso = c.getString(0);
        } finally {
            c.close();
        }
        if (iso == null) return -1;

        int iconId = debtWalletIconId(db, cpIcon);
        if (iconId <= 0) {
            Log.w(TAG, "CreateDebtWallet: no icon");
            return -1;
        }

        String base = DebtWalletName(cpName, iso);
        String name = base;
        for (int n = 2; GetAccountIdByName(db, name) > 0; n++) name = base + " · " + n; // not UI: name pattern

        long walletId = InsertOperatingAccount(db, name, "", iconId, currencyId);
        if (walletId <= 0) return -1;
        if (!SetAccountDebt(db, (int) walletId, cpUuid, comment, dueDate)) return -1;
        return (int) walletId;
    }

    /** Имя кошелька займа «<имя контрагента> · <код валюты>» (спека, «Долговой кошелёк»). */
    public static String DebtWalletName(String counterpartyName, String iso) {
        return counterpartyName + " · " + iso; // not UI: name pattern
    }

    private static int debtWalletIconId(SQLiteDatabase db, String iconUuid) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID
                + " FROM " + WalletContract.IconEntry.TABLE_NAME
                + " WHERE (" + WalletContract.IconEntry.COLUMN_UUID + " = ? OR "
                + WalletContract.IconEntry.COLUMN_NAME + " = 'unknown')"
                + " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL"
                + " ORDER BY CASE WHEN " + WalletContract.IconEntry.COLUMN_UUID + " = ? THEN 0 ELSE 1 END LIMIT 1",
                new String[]{iconUuid == null ? "" : iconUuid, iconUuid == null ? "" : iconUuid});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    private static int countRowsByUuid(SQLiteDatabase db, String table, String uuid) {
        Cursor c = db.rawQuery("SELECT count(*) FROM " + table + " WHERE uuid = ? AND deleted_at IS NULL",
                new String[]{uuid});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Системная категория «Переоценка» (design п. 4): уже есть (в том числе пришла синком с
     * другого устройства) — она; нет — заводится с {@code system_kind = 3}. Имя
     * «Переоценка» занято обычной категорией — «Переоценка активов» (чужую категорию в
     * системную не превращаем: её прошлые проводки ушли бы из расходов); занято и оно —
     * с номером « (2)», « (3)»…
     */
    public static int GetOrCreateRevaluationCategory(SQLiteDatabase db) {
        int systemKind = AccountItem.SystemKind.REVALUATION.getValue();
        int id = GetAccountIdBySystemKind(db, systemKind);
        if (id > 0) return id;
        String name = AccountItem.SYSTEM_REVALUATION_NAME;
        if (GetAccountIdByName(db, name) > 0) {
            name = AccountItem.SYSTEM_REVALUATION_FALLBACK_NAME;
            for (int n = 2; GetAccountIdByName(db, name) > 0; n++) {
                name = AccountItem.SYSTEM_REVALUATION_FALLBACK_NAME + " (" + n + ")";
            }
        }
        return createCategory(db, name, "Percentage", systemKind);
    }

    /** Итог {@link #WriteRevaluation}. */
    public enum RevaluationResult { written, unchanged, negative, failed }

    /**
     * Переоценка актива (openspec capital-assets, asset-revaluation): одна проводка на |Δ|,
     * Δ = новая оценка − баланс актива по всем его проводкам до конца выбранного дня
     * включительно. Δ &gt; 0 — «Переоценка» → актив, Δ &lt; 0 — актив → «Переоценка». Пишется
     * на конец дня ({@code yyyy-MM-dd 23:59}), чтобы встать после всех операций того дня.
     * Δ округляется до {@code precision} валюты актива; Δ = 0 — ничего не пишется.
     *
     * @param day      дата в формате {@code yyyy-MM-dd}
     * @param newValue новая оценка в валюте актива
     */
    public static RevaluationResult WriteRevaluation(SQLiteDatabase db, int assetId, String day,
                                                     double newValue, int precision, String comment) {
        if (newValue < 0) return RevaluationResult.negative;
        String endOfDay = day + " 23:59";
        double current = WalletDbLoader.LoadAccountBalanceAt(db, assetId, endOfDay);
        double delta = java.math.BigDecimal.valueOf(newValue - current)
                .setScale(precision, java.math.RoundingMode.HALF_UP).doubleValue();
        if (delta == 0) return RevaluationResult.unchanged;

        db.beginTransaction();
        try {
            int categoryId = GetOrCreateRevaluationCategory(db);
            if (categoryId <= 0) return RevaluationResult.failed;
            long id = delta > 0
                    ? InsertOperation(db, endOfDay, categoryId, assetId, delta, comment)
                    : InsertOperation(db, endOfDay, assetId, categoryId, -delta, comment);
            if (id <= 0) return RevaluationResult.failed;
            db.setTransactionSuccessful();
            return RevaluationResult.written;
        } finally {
            db.endTransaction();
        }
    }

    /** Итог {@link #WriteOffBalance}. */
    public enum WriteOffResult { written, zero, failed }

    /**
     * Остаток кошелька, округлённый до {@code precision} его валюты (HALF_UP): итоговый, по
     * всем живым проводкам (archive-wallet-writeoff, D2, D3). 0 — списывать нечего.
     */
    public static double RoundedWalletBalance(SQLiteDatabase db, int walletId, int precision) {
        return java.math.BigDecimal.valueOf(WalletDbLoader.LoadAccountBalance(db, walletId))
                .setScale(precision, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Списание остатка кошелька на категорию при архивации (openspec archive-wallet-writeoff):
     * остаток &gt; 0 — «кошелёк → категория» на остаток, &lt; 0 — «категория → кошелёк» на
     * |остаток|. Остаток считается здесь же ({@link #RoundedWalletBalance}), а не берётся из
     * диалога: за время диалога мог прийти синк. Пишется на конец дня
     * ({@code yyyy-MM-dd 23:59}), как переоценка. Вызывается внутри транзакции сохранения
     * экрана «Кошелёк» — вложенная транзакция откатит всё вместе с ней.
     *
     * @param day дата в формате {@code yyyy-MM-dd}
     */
    public static WriteOffResult WriteOffBalance(SQLiteDatabase db, int walletId, int categoryId, String day,
                                                 int precision, String comment) {
        double balance = RoundedWalletBalance(db, walletId, precision);
        if (balance == 0) return WriteOffResult.zero;
        if (categoryId <= 0) return WriteOffResult.failed;
        String endOfDay = day + " 23:59";
        long id = balance > 0
                ? InsertOperation(db, endOfDay, walletId, categoryId, balance, comment)
                : InsertOperation(db, endOfDay, categoryId, walletId, -balance, comment);
        return id > 0 ? WriteOffResult.written : WriteOffResult.failed;
    }

    /**
     * «Простить» (openspec debts, D8): проводка «заём → категория» (расход) при остатке
     * &gt; 0 и «категория → заём» (доход) при &lt; 0, на {@code amount} в валюте займа, на конец
     * дня — как списание при архивации. Категория обычная: прощение — настоящий расход или
     * доход и попадает в отчёты.
     *
     * @return zero — остаток нулевой, прощать нечего; failed — нет категории или суммы
     */
    public static WriteOffResult ForgiveDebt(SQLiteDatabase db, int loanId, int categoryId, String day,
                                             double amount, int precision, String comment) {
        double balance = RoundedWalletBalance(db, loanId, precision);
        if (balance == 0) return WriteOffResult.zero;
        if (categoryId <= 0 || amount <= 0) return WriteOffResult.failed;
        String endOfDay = day + " 23:59";
        long id = balance > 0
                ? InsertOperation(db, endOfDay, loanId, categoryId, amount, comment)
                : InsertOperation(db, endOfDay, categoryId, loanId, amount, comment);
        return id > 0 ? WriteOffResult.written : WriteOffResult.failed;
    }

    /**
     * Категория прощения по умолчанию — обычная (не системная, {@code system_kind = 0}) с этим
     * именем: есть — она, нет — заводится при первом использовании (спека «Прощение долга»).
     */
    public static int GetOrCreateDebtWriteOffCategory(SQLiteDatabase db, String name) {
        int id = GetAccountIdByName(db, name);
        if (id > 0) return id;
        return createCategory(db, name, "unknown", AccountItem.SystemKind.NONE.getValue()); // not UI: icon name
    }

    /** Итог {@link #MoveCategoryToAsset}: сколько перенесено и сколько пропущено без курса. */
    public static final class MoveResult {
        public final int moved;
        public final int skippedNoRate;
        public final boolean archived;

        MoveResult(int moved, int skippedNoRate, boolean archived) {
            this.moved = moved;
            this.skippedNoRate = skippedNoRate;
            this.archived = archived;
        }
    }

    /** Живые операции категории с кошельком на другой стороне — сколько перенесёт {@link #MoveCategoryToAsset}. */
    public static int CountCategoryWalletOperations(SQLiteDatabase db, int categoryId) {
        String id = String.valueOf(categoryId);
        Cursor c = db.rawQuery("SELECT count(*) FROM " + WalletContract.OperationEntry.TABLE_NAME + " o"
                + " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " w ON w.deleted_at IS NULL"
                + " AND w._id = CASE WHEN o.acc_fr_id = ? THEN o.acc_to_id ELSE o.acc_fr_id END"
                + " WHERE (o.acc_fr_id = ? OR o.acc_to_id = ?) AND o.deleted_at IS NULL", new String[]{id, id, id});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Переносит все операции категории в капитальный актив одной транзакцией (openspec
     * capital-assets, design п. 9). Делает клиент, а не сервер: офлайн-устройство с
     * неотправленными операциями на старую категорию после серверной миграции привезло бы
     * их обратно.
     * <ul>
     *   <li>Кошелёк операции в валюте актива — сторона-категория заменяется активом:
     *   расход «кошелёк → категория» становится переводом «кошелёк → актив», возврат
     *   «категория → кошелёк» — «актив → кошелёк». Суммы и даты прежние.</li>
     *   <li>Другая валюта — валютный перевод в две ноги через «Transfer» по курсу на дату
     *   операции, как {@code TransactionActivity.saveTransfer}: исходная проводка
     *   становится «кошелёк → Transfer», новая нога «Transfer → актив» на сумму × курс
     *   (округление до {@code precision} актива), связка {@code LEG}. Возврат — зеркально:
     *   новая «актив → Transfer», исходная становится «Transfer → кошелёк». Нет курса на
     *   дату — операция пропускается и считается; уже связанная разновалютная операция
     *   (нога чужого перевода) тоже пропускается.</li>
     *   <li>Правила повторяющихся операций (локальные) с этой категорией и кошельком в
     *   валюте актива перенацеливаются на актив; разновалютные остаются как есть.</li>
     *   <li>Не осталось живых операций — категория уходит в архив.</li>
     * </ul>
     * Ошибка посреди переноса откатывает всё (исключение пробрасывается).
     */
    public static MoveResult MoveCategoryToAsset(SQLiteDatabase db, int categoryId, int assetId) {
        int assetCurrency = walletCurrencyId(db, assetId);
        if (assetCurrency <= 0) throw new IllegalArgumentException("asset " + assetId + " is not a wallet");
        int assetPrecision = currencyPrecision(db, assetCurrency);
        String cat = String.valueOf(categoryId);

        db.beginTransaction();
        try {
            int moved = 0, skipped = 0;
            int bridgeId = -1;
            List<long[]> ops = new ArrayList<>();      // _id, wallet, walletCurrency, isRefund(0/1), linked(0/1)
            List<String> dates = new ArrayList<>();
            List<Double> amounts = new ArrayList<>();
            Cursor c = db.rawQuery("SELECT o._id, o.date, o.amount, o.acc_fr_id, o.acc_to_id, w.currency_id,"
                    + " EXISTS (SELECT 1 FROM " + WalletContract.OperationLinkEntry.TABLE_NAME + " l"
                    + "   WHERE (l.main_id = o._id OR l.link_id = o._id) AND l.deleted_at IS NULL)"
                    + " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o"
                    + " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " w ON w.deleted_at IS NULL"
                    + "   AND w._id = CASE WHEN o.acc_fr_id = ? THEN o.acc_to_id ELSE o.acc_fr_id END"
                    + " WHERE (o.acc_fr_id = ? OR o.acc_to_id = ?) AND o.deleted_at IS NULL",
                    new String[]{cat, cat, cat});
            try {
                while (c.moveToNext()) {
                    boolean refund = c.getInt(3) == categoryId;
                    long wallet = refund ? c.getLong(4) : c.getLong(3);
                    ops.add(new long[]{c.getLong(0), wallet, c.getLong(5), refund ? 1 : 0, c.getInt(6)});
                    dates.add(c.getString(1));
                    amounts.add(c.getDouble(2));
                }
            } finally {
                c.close();
            }

            for (int i = 0; i < ops.size(); i++) {
                long[] op = ops.get(i);
                int opId = (int) op[0];
                int wallet = (int) op[1];
                boolean refund = op[3] == 1;
                if (wallet == assetId) { skipped++; continue; } // проводка уже на самом активе — переводом в себя не станет
                if (op[2] == assetCurrency) {
                    ContentValues cv = new ContentValues();
                    cv.put(refund ? WalletContract.OperationEntry.COLUMN_FROM : WalletContract.OperationEntry.COLUMN_TO, assetId);
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", new String[]{String.valueOf(opId)});
                    moved++;
                    continue;
                }
                if (op[4] == 1) { skipped++; continue; }
                WalletDbLoader.RateOnDate rate = WalletDbLoader.LoadRateOnDate(db, (int) op[2], assetCurrency,
                        com.example.wallettransactions.DateUtils.StrToDate(dates.get(i)));
                if (rate == null) { skipped++; continue; }
                if (bridgeId <= 0) {
                    bridgeId = GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER.getValue(),
                            AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME);
                    if (bridgeId <= 0) throw new IllegalStateException("no Transfer category");
                }
                double assetAmount = java.math.BigDecimal.valueOf(amounts.get(i) * rate.rate)
                        .setScale(assetPrecision, java.math.RoundingMode.HALF_UP).doubleValue();
                String comment = GetOperationComment(db, opId);
                ContentValues cv = new ContentValues();
                if (!refund) {
                    cv.put(WalletContract.OperationEntry.COLUMN_TO, bridgeId);
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", new String[]{String.valueOf(opId)});
                    long leg = InsertOperation(db, dates.get(i), bridgeId, assetId, assetAmount, comment);
                    if (leg <= 0) throw new IllegalStateException("leg insert failed for operation " + opId);
                    LinkOperations(db, opId, leg, OperationItem.LinkType.LEG);
                } else {
                    cv.put(WalletContract.OperationEntry.COLUMN_FROM, bridgeId);
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", new String[]{String.valueOf(opId)});
                    long main = InsertOperation(db, dates.get(i), assetId, bridgeId, assetAmount, comment);
                    if (main <= 0) throw new IllegalStateException("leg insert failed for operation " + opId);
                    LinkOperations(db, main, opId, OperationItem.LinkType.LEG);
                }
                moved++;
            }

            // Правила повторяющихся операций: только одновалютные (кошелёк в валюте актива).
            String sameCurrencyWallet = "SELECT _id FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME
                    + " WHERE deleted_at IS NULL AND currency_id = " + assetCurrency + " AND _id <> " + assetId;
            String rt = WalletContract.RecurringOperationEntry.TABLE_NAME;
            db.execSQL("UPDATE " + rt + " SET " + WalletContract.RecurringOperationEntry.COLUMN_TO + " = ?"
                    + " WHERE " + WalletContract.RecurringOperationEntry.COLUMN_TO + " = ? AND "
                    + WalletContract.RecurringOperationEntry.COLUMN_FROM + " IN (" + sameCurrencyWallet + ")",
                    new Object[]{assetId, categoryId});
            db.execSQL("UPDATE " + rt + " SET " + WalletContract.RecurringOperationEntry.COLUMN_FROM + " = ?"
                    + " WHERE " + WalletContract.RecurringOperationEntry.COLUMN_FROM + " = ? AND "
                    + WalletContract.RecurringOperationEntry.COLUMN_TO + " IN (" + sameCurrencyWallet + ")",
                    new Object[]{assetId, categoryId});

            boolean archived = false;
            if (!HasOperations(db, categoryId)) {
                ContentValues cv = new ContentValues();
                cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 1);
                db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                        WalletContract.AccountEntry._ID + "=?", new String[]{cat});
                archived = true;
            }
            db.setTransactionSuccessful();
            return new MoveResult(moved, skipped, archived);
        } finally {
            db.endTransaction();
        }
    }

    private static String GetOperationComment(SQLiteDatabase db, int operationId) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.OperationEntry.COLUMN_COMMENT + " FROM "
                + WalletContract.OperationEntry.TABLE_NAME + " WHERE _id = ?", new String[]{String.valueOf(operationId)});
        try {
            return c.moveToFirst() ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    /** Валюта кошелька (живая {@code account_operating}) или -1. */
    static int walletCurrencyId(SQLiteDatabase db, int accountId) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID + " FROM "
                + WalletContract.AccountOperatingEntry.TABLE_NAME + " WHERE _id = ? AND deleted_at IS NULL",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    private static int currencyPrecision(SQLiteDatabase db, int currencyId) {
        Cursor c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_PRECISION + " FROM "
                + WalletContract.CurrencyEntry.TABLE_NAME + " WHERE _id = ?", new String[]{String.valueOf(currencyId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 2;
        } finally {
            c.close();
        }
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
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL);
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
        // Новое имя или иконка — строка больше не «из набора» (new-user-default-set, design D3).
        if (rowDiffers(db, WalletContract.AccountEntry.TABLE_NAME, id,
                new String[]{WalletContract.AccountEntry.COLUMN_NAME, WalletContract.AccountEntry.COLUMN_ICON_ID},
                new String[]{accName, Integer.toString(iconId)})) {
            cv.put(WalletContract.AccountEntry.COLUMN_FROM_SEED, 0);
        }

        return db.update(WalletContract.AccountEntry.TABLE_NAME, cv, WalletContract.AccountEntry._ID+"="+id, null);
    }

    /**
     * Отличается ли хоть одна из {@code columns} строки {@code id} от {@code values}
     * (сравнение в SQLite, {@code IS NOT}: тот же тип, что в колонке). Нет строки — {@code false}.
     */
    static boolean rowDiffers(SQLiteDatabase db, String table, int id, String[] columns, String[] values) {
        StringBuilder where = new StringBuilder();
        String[] args = new String[values.length + 1];
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) where.append(" OR ");
            where.append(columns[i]).append(" IS NOT ?");
            args[i] = values[i];
        }
        args[values.length] = Integer.toString(id);
        Cursor c = db.rawQuery("SELECT 1 FROM " + table + " WHERE (" + where + ") AND _id = ? LIMIT 1", args);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    // ── Валюты (экран «Список валют» → форма валюты) ───────────────────────────

    public static long InsertCurrency(SQLiteDatabase db, String name, String iso,
                                      String symbol, int precision, int iconId,
                                      boolean showInEquivalent) {
        // uuid — только здесь, не в currencyValues(): тот же метод используют
        // Insert и Update, а uuid не должен меняться при правке существующей валюты.
        ContentValues cv = currencyValues(name, iso, symbol, precision, iconId, showInEquivalent);
        cv.put(WalletContract.CurrencyEntry.COLUMN_UUID, UUID.randomUUID().toString());
        return db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, cv);
    }

    /**
     * Общая валюта (is_shared = 1) не правится: сервер такой push молча пропускает,
     * и телефон разошёлся бы с ним (openspec currency-ui-tweaks, design D7). Форма её
     * уже не даёт править — условие здесь страхует обход формы.
     */
    public static int UpdateCurrency(SQLiteDatabase db, int id, String name, String iso,
                                     String symbol, int precision, int iconId,
                                     boolean showInEquivalent) {
        ContentValues cv = currencyValues(name, iso, symbol, precision, iconId, showInEquivalent);
        // Новые имя, код, символ или иконка — валюта больше не «из набора» (design D3).
        if (rowDiffers(db, WalletContract.CurrencyEntry.TABLE_NAME, id,
                new String[]{WalletContract.CurrencyEntry.COLUMN_NAME, WalletContract.CurrencyEntry.COLUMN_ISO_NAME,
                        WalletContract.CurrencyEntry.COLUMN_SYMBOL, WalletContract.CurrencyEntry.COLUMN_ICON_ID},
                new String[]{name, iso, symbol, Integer.toString(iconId)})) {
            cv.put(WalletContract.CurrencyEntry.COLUMN_FROM_SEED, 0);
        }
        return db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry._ID + "=? AND "
                        + WalletContract.CurrencyEntry.COLUMN_IS_SHARED + "=0",
                new String[]{Integer.toString(id)});
    }

    private static ContentValues currencyValues(String name, String iso, String symbol,
                                                int precision, int iconId, boolean showInEquivalent) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, name);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso);
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol);
        cv.put(WalletContract.CurrencyEntry.COLUMN_PRECISION, precision);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId);
        cv.put(WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT, showInEquivalent ? 1 : 0);
        return cv;
    }

    /**
     * Сменить ISO-код валюты, входящей в живую пару, нельзя (design D8): символ пары —
     * склейка кодов, и пара осталась бы со старым символом. То же правило, что
     * {@code 409 currency_in_pair} в REST wallet-api. Код не меняется (с точностью до
     * регистра) — правка разрешена, даже если валюта в паре.
     */
    public static boolean IsIsoChangeBlocked(SQLiteDatabase db, int currencyId, String newIso) {
        if (currencyId <= 0) return false;
        Cursor c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME
                        + " FROM " + WalletContract.CurrencyEntry.TABLE_NAME
                        + " WHERE " + WalletContract.CurrencyEntry._ID + "=?",
                new String[]{Integer.toString(currencyId)});
        String oldIso;
        try {
            if (!c.moveToFirst()) return false;
            oldIso = c.getString(0);
        } finally {
            c.close();
        }
        if (oldIso != null && oldIso.equalsIgnoreCase(newIso)) return false;
        return IsCurrencyInPair(db, currencyId);
    }

    /** Валюта — база или котировка хотя бы одной живой пары. */
    public static boolean IsCurrencyInPair(SQLiteDatabase db, int currencyId) {
        return countRows(db, "SELECT COUNT(*) FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME
                + " WHERE (" + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID + "=?"
                + " OR " + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID + "=?)"
                + " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                currencyId, currencyId) > 0;
    }

    /**
     * Мягко удаляет свою пару и все её живые курсы одной транзакцией (design D3) —
     * тот же каскад, что {@code DELETE /api/currency-pairs/{uuid}} на сервере. dirty
     * ставит триггер, push отправит и пару, и курсы tombstone'ами. Общую пару
     * (is_shared = 1) не трогает и возвращает 0 — проверка здесь, а не только в UI.
     * {@code currency_conversion}, ссылающаяся на пару, не меняется (сервер её тоже
     * не трогает): эквивалент через пару становится неизвестным.
     *
     * @return 1 — пара удалена, 0 — не найдена, уже удалена или общая.
     */
    public static int DeleteCurrencyPair(SQLiteDatabase db, int pairId) {
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            ContentValues pairCv = new ContentValues();
            pairCv.put(WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT, now);
            int pairs = db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, pairCv,
                    WalletContract.CurrencyPairEntry._ID + "=? AND "
                            + WalletContract.CurrencyPairEntry.COLUMN_IS_SHARED + "=0 AND "
                            + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                    new String[]{Integer.toString(pairId)});
            if (pairs == 0) return 0;

            ContentValues rateCv = new ContentValues();
            rateCv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT, now);
            db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, rateCv,
                    WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND "
                            + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                    new String[]{Integer.toString(pairId)});
            db.setTransactionSuccessful();
            return pairs;
        } finally {
            db.endTransaction();
        }
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
            || IsCurrencyInPair(db, currencyId);
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
