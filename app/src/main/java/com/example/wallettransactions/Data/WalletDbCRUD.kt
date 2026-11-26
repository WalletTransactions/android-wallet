package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Operations.OperationItem

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

object WalletDbCRUD {

    private const val TAG = "WalletDbCRUD"

    /**
     * Finds and removes logically broken operations.
     * A broken operation is one where both accounts have no currency (both are categories).
     * Returns list of deleted operation IDs for logging/display.
     */
    @JvmStatic
    fun repairDatabase(db: SQLiteDatabase): List<Int> {
        val deletedIds = ArrayList<Int>()

        // Find transactions where both accounts are categories (not in account_operating)
        val c = db.rawQuery(
                "SELECT o._id, o.date, af.name acc_fr, at.name acc_to, o.amount" +
                " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o" +
                " JOIN account af ON af._id = o.acc_fr_id" +
                " JOIN account at ON at._id = o.acc_to_id" +
                " LEFT JOIN account_operating aof ON aof._id = o.acc_fr_id" +
                " LEFT JOIN account_operating aot ON aot._id = o.acc_to_id" +
                " WHERE aof._id IS NULL AND aot._id IS NULL",
                null)

        try {
            while (c.moveToNext()) {
                val id = c.getInt(0)
                val date = c.getString(1)
                val fr = c.getString(2)
                val to = c.getString(3)
                val amt: Double = c.getDouble(4)
                Log.w(TAG, String.format(
                        "Broken operation id=%d  %s  %s→%s  amount=%.2f — will delete",
                        id, date, fr, to, amt))
                deletedIds.add(id)
            }
        } finally {
            c.close()
        }

        for (id in deletedIds) {
            db.delete(WalletContract.OperationEntry.TABLE_NAME,
                    WalletContract.OperationEntry._ID + "=?",
                    arrayOf<String>(id.toString()))
            Log.i(TAG, "Deleted broken operation id=" + id)
        }

        return deletedIds
    }

    @JvmStatic
    fun HasOperations(db: SQLiteDatabase, accountId: Int): Boolean {
        // deleted_at IS NULL — с v72 (soft-delete, см. DeleteOperation) мягко
        // удалённая проводка не должна больше считаться "занятостью" счёта.
        val id = accountId.toString()
        val c = db.rawQuery(
                "SELECT COUNT(*) FROM " + WalletContract.OperationEntry.TABLE_NAME +
                " WHERE (" + WalletContract.OperationEntry.COLUMN_FROM + "=? OR " +
                WalletContract.OperationEntry.COLUMN_TO + "=?)" +
                " AND " + WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(id, id))
        c.moveToFirst()
        val has = c.getInt(0) > 0
        c.close()
        return has
    }

    // ── Операции и их связи ───────────────────────────────────────────────────

    @JvmStatic
    fun InsertOperation(db: SQLiteDatabase, date: String, accFromId: Int, accToId: Int, amount: Double, comment: String): Long {
        val cv = ContentValues()
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date)
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId)
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId)
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount)
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment)
        cv.put(WalletContract.OperationEntry.COLUMN_UUID, UUID.randomUUID().toString())
        return db.insert(WalletContract.OperationEntry.TABLE_NAME, null, cv)
    }

    /** [InsertOperationWithUuid]: живая операция с этим uuid уже есть — вставки не было. */
    const val UUID_TAKEN: Long = -2

    /** Операция, найденная по uuid: локальный `_id` и удалена ли она. */
    class UuidMatch(@JvmField val id: Long, @JvmField val deleted: Boolean)

    /**
     * Операция с этим uuid в любом написании (с дефисами и без, любой регистр — как
     * `ux_operation_uuid`, v83), живая или удалённая; `null` — такой нет.
     */
    @JvmStatic
    fun FindOperationByUuid(db: SQLiteDatabase, uuid: String): UuidMatch? {
        val key = uuid.toLowerCase(Locale.ROOT).replace("-", "")
        val c = db.rawQuery("SELECT _id, deleted_at FROM " + WalletContract.OperationEntry.TABLE_NAME +
                " WHERE lower(replace(uuid, '-', '')) = ? LIMIT 1", arrayOf<String>(key))
        try {
            return if (c.moveToFirst()) UuidMatch(c.getLong(0), !c.isNull(1)) else null
        } finally {
            c.close()
        }
    }

    /**
     * Проводка с заданным заранее uuid (openspec add-ai-statement-import, operation-identity:
     * строка импорта выписки пишется с `uuid = row_id`). Живая с этим uuid уже
     * есть — [UUID_TAKEN], вторая не создаётся. Удалённая есть — та же строка
     * получает новые поля и снова живая (dirty поднимет триггер синка).
     *
     * @return `_id` новой или восстановленной строки, [UUID_TAKEN] или -1
     */
    @JvmStatic
    fun InsertOperationWithUuid(db: SQLiteDatabase, uuid: String, date: String, accFromId: Int, accToId: Int, amount: Double, comment: String): Long {
        val existing = FindOperationByUuid(db, uuid)
        if (existing != null && !existing.deleted) return UUID_TAKEN

        val cv = ContentValues()
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date)
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId)
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId)
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount)
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment)
        if (existing != null) {
            cv.putNull(WalletContract.OperationEntry.COLUMN_DELETED_AT)
            val n = db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                    WalletContract.OperationEntry._ID + "=?", arrayOf<String>(existing.id.toString()))
            return if (n == 1) existing.id else -1
        }
        cv.put(WalletContract.OperationEntry.COLUMN_UUID, uuid)
        return db.insert(WalletContract.OperationEntry.TABLE_NAME, null, cv)
    }

    @JvmStatic
    fun UpdateOperation(db: SQLiteDatabase, operationId: Int, date: String, accFromId: Int, accToId: Int, amount: Double, comment: String): Int {
        val cv = ContentValues()
        cv.put(WalletContract.OperationEntry.COLUMN_DATE, date)
        cv.put(WalletContract.OperationEntry.COLUMN_FROM, accFromId)
        cv.put(WalletContract.OperationEntry.COLUMN_TO, accToId)
        cv.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount)
        cv.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment)
        return db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry._ID + "=?",
                arrayOf<String>(operationId.toString()))
    }

    /**
     * "Удаляет" проводку — мягко, с v72 (org/docs/TODO_mobile_sync.md, Фаза 3):
     * `deleted_at` вместо настоящего `DELETE`, иначе удаление
     * никогда не долетело бы до сервера и других устройств — сообщать об
     * исчезнувшей строке через push было бы нечем. `dirty=1` тут не
     * нужен явно — форсит триггер `trg_operation_dirty`
     * (schema/triggers/trg_sync_dirty.sql), раз этот UPDATE его не
     * трогает. Раньше строки `operation_link` уходили сами через
     * `ON DELETE CASCADE` — теперь, раз операция физически остаётся,
     * они тоже остаются (то же самое поведение, что и на сервере: внешние
     * ключи остаются целыми, см. README wallet-api/schema, "Побочная выгода").
     */
    @JvmStatic
    fun DeleteOperation(db: SQLiteDatabase, operationId: Int): Int {
        val cv = ContentValues()
        cv.put(WalletContract.OperationEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        return db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry._ID + "=?",
                arrayOf<String>(operationId.toString()))
    }

    /**
     * Цепляет проводку к главной — списанию с кошелька-источника. Так вторая нога
     * валютного перевода и комиссия находятся по одной операции. `type` —
     * явная роль связки (v77, `operation_link.type`), см. [OperationItem.LinkType].
     */
    @JvmStatic
    fun LinkOperations(db: SQLiteDatabase, mainId: Long, linkId: Long, type: OperationItem.LinkType) {
        if (mainId <= 0 || linkId <= 0 || mainId == linkId) return

        val cv = ContentValues()
        cv.put(WalletContract.OperationLinkEntry.COLUMN_MAIN_ID, mainId)
        cv.put(WalletContract.OperationLinkEntry.COLUMN_LINK_ID, linkId)
        cv.put(WalletContract.OperationLinkEntry.COLUMN_TYPE, type.value)
        cv.put(WalletContract.OperationLinkEntry.COLUMN_UUID, UUID.randomUUID().toString())
        db.insertWithOnConflict(WalletContract.OperationLinkEntry.TABLE_NAME, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** ID проводок, привязанных к главной, вместе с ролью каждой связки ([OperationItem.LinkType]). */
    @JvmStatic
    fun GetLinkedOperations(db: SQLiteDatabase, mainId: Int): Map<Int, OperationItem.LinkType> {
        val links = LinkedHashMap<Int, OperationItem.LinkType>()
        val c = db.rawQuery(
                "SELECT " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID +
                        ", " + WalletContract.OperationLinkEntry.COLUMN_TYPE +
                " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                " WHERE " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + "=?",
                arrayOf<String>(mainId.toString()))
        try {
            while (c.moveToNext()) links.put(c.getInt(0), OperationItem.LinkType.fromValue(c.getInt(1)))
        } finally {
            c.close()
        }
        return links
    }

    /**
     * Занято ли имя другим живым счётом (правило [AccountNames]) — проверка до
     * записи, чтобы экран показал ошибку, а не упёрся в uq_account_name.
     * exceptId — сам редактируемый счёт (-1 при создании): «кафе» → «Кафе» — не конфликт.
     */
    @JvmStatic
    fun IsAccountNameTaken(db: SQLiteDatabase, name: String, exceptId: Int): Boolean {
        val id = GetAccountIdByName(db, name)
        return id > 0 && id != exceptId
    }

    /**
     * ID счёта по имени (правило [AccountNames]) или -1. deleted_at IS NULL — не
     * резолвить в мягко удалённый tombstone; то же условие у индекса uq_account_name.
     */
    @JvmStatic
    fun GetAccountIdByName(db: SQLiteDatabase, name: String): Int {
        val c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry._ID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME) +
                " AND " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(name))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    /**
     * Категория с таким именем, при необходимости заведённая на месте. Нужна для служебных
     * категорий («Transfer», «Transfer commission»): в старых базах их может не быть —
     * начальный справочник накатывался один раз, до того как они понадобились.
     *
     * @deprecated для системных категорий («Transfer», «Transfer commission») используйте
     * [GetOrCreateSystemCategory] — тот ищет по `system_kind`, а не по имени,
     * и переживает переименование категории пользователем (после v75 имя больше не участвует
     * в определении системности). Этот метод остаётся низкоуровневым инструментом поиска/
     * создания категории ПО ИМЕНИ — на сегодня в коде не осталось мест, где это нужно для
     * НЕ-системной категории, но публичный метод не убран на случай, если понадобится снова.
     */
    @JvmStatic
    fun GetOrCreateCategory(db: SQLiteDatabase, name: String, fallbackIconName: String): Int {
        val id = GetAccountIdByName(db, name)
        if (id > 0) return id
        return createCategory(db, name, fallbackIconName, AccountItem.SystemKind.NONE.value)
    }

    /**
     * Находит системную категорию по system_kind (а не по имени — имя пользователь может
     * сменить, incidentally сломав старую логику распознавания, см. [AccountItem.SystemKind]).
     * Если не нашлась — заводит новую с заданными name/иконкой и сразу проставляет system_kind.
     */
    @JvmStatic
    fun GetOrCreateSystemCategory(db: SQLiteDatabase, systemKind: Int, defaultName: String, iconSourceName: String): Int {
        val id = GetAccountIdBySystemKind(db, systemKind)
        if (id > 0) return id
        return createCategory(db, defaultName, iconSourceName, systemKind)
    }

    /**
     * Общая часть [GetOrCreateCategory]/[GetOrCreateSystemCategory] —
     * подбор иконки (сначала запрошенная по имени, иначе "unknown") и сама вставка строки,
     * включая её `system_kind` (0 — обычная категория, для системных — конкретное
     * значение [AccountItem.SystemKind]).
     */
    private fun createCategory(db: SQLiteDatabase, name: String, fallbackIconName: String, systemKind: Int): Int {
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry.COLUMN_NAME + " IN (?, 'unknown')" +
                " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                " ORDER BY CASE WHEN " + WalletContract.IconEntry.COLUMN_NAME + "=? THEN 0 ELSE 1 END" +
                " LIMIT 1", arrayOf<String>(fallbackIconName, fallbackIconName))
        var iconId: Int
        try {
            iconId = if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
        if (iconId <= 0) {
            Log.w(TAG, "No icon to create category " + name)
            return -1
        }

        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name)
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, name)
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId)
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 0)
        cv.put(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND, systemKind)
        // Обе категории (обычная и системная) — kind=CATEGORY: createCategory
        // никогда не заводит кошелёк (для этого есть InsertOperatingAccount).
        cv.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.CATEGORY.value)
        cv.put(WalletContract.AccountEntry.COLUMN_UUID, UUID.randomUUID().toString())

        val newId: Long = db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv)
        return if (newId > 0) newId.toInt() else -1
    }

    /** _id счёта с данным system_kind (deleted_at IS NULL) или -1, если такого нет. */
    private fun GetAccountIdBySystemKind(db: SQLiteDatabase, systemKind: Int): Int {
        val c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry._ID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + "=?" +
                " AND " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(systemKind.toString()))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    /** Имя счёта или null, если такого счёта уже нет. */
    @JvmStatic
    fun GetAccountName(db: SQLiteDatabase, accountId: Int): String? {
        val c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry.COLUMN_NAME +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry._ID + "=?",
                arrayOf<String>(accountId.toString()))
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    /**
     * system_kind счёта, или [AccountItem.SystemKind.NONE].getValue(), если такого
     * счёта уже нет — зеркально [GetAccountName], используется вместо распознавания
     * системности по имени (см. [DeleteAccount]).
     */
    @JvmStatic
    fun GetAccountSystemKind(db: SQLiteDatabase, accountId: Long): Int {
        val c = db.rawQuery(
                "SELECT " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry._ID + "=?",
                arrayOf<String>(accountId.toString()))
        try {
            return if (c.moveToFirst()) c.getInt(0) else AccountItem.SystemKind.NONE.value
        } finally {
            c.close()
        }
    }

    /** Чем кончилась попытка удалить счёт: удалили или почему нет. */
    enum class DeleteAccountResult { deleted, systemAccount, hasOperations }

    /**
     * Удаляет счёт — только пустой и только несистемный. Обе проверки стоят здесь, а не
     * в UI, чтобы правило держалось на всех путях удаления:
     *
     * <ul>
     *   <li>системный (`system_kind != NONE`, см. [AccountItem.SystemKind]) —
     *   его id зашит в код перевода и комиссии (найден через
     *   [GetOrCreateSystemCategory]), так что без него ломается уже проведённое;</li>
     *   <li>с операциями — удаление увело бы их вместе с собой. На случай, если проверку
     *   обойдут, за ней стоит внешний ключ: `operation.acc_fr_id/acc_to_id`
     *   ссылаются на `account._id`, а `PRAGMA foreign_keys` включён в
     *   [WalletDbHelper.getWritableDatabase()], поэтому такой DELETE упал бы с
     *   `SQLiteConstraintException`.</li>
     * </ul>
     *
     * Убрать категорию, по которой уже есть операции, можно будет только слиянием —
     * перенести операции в другую категорию и удалить опустевшую (TODO.md).
     */
    @JvmStatic
    fun DeleteAccount(db: SQLiteDatabase, accountId: Int): DeleteAccountResult {
        if (GetAccountSystemKind(db, accountId.toLong()) != AccountItem.SystemKind.NONE.value) {
            Log.w(TAG, "Refused to delete system account id=" + accountId)
            return DeleteAccountResult.systemAccount
        }

        if (HasOperations(db, accountId)) {
            Log.w(TAG, "Refused to delete account id=" + accountId + " — it has operations")
            return DeleteAccountResult.hasOperations
        }

        // Мягкое удаление с v72 (см. javadoc DeleteOperation выше, тот же довод) —
        // dirty=1 форсит триггер, здесь не нужен явно.
        val id = accountId.toString()
        val now: Long = System.currentTimeMillis()

        val cvOp = ContentValues()
        cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cvOp,
                WalletContract.AccountOperatingEntry._ID + "=?", arrayOf<String>(id))

        // Признак актива уходит вместе с кошельком (capital-assets) — живой строки
        // account_asset у удалённого кошелька быть не должно.
        val cvAsset = ContentValues()
        cvAsset.put(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cvAsset,
                WalletContract.AccountAssetEntry._ID + "=? AND " +
                        WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(id))
        // То же для признака долга (debts): займ без кошелька не живёт.
        val cvDebt = ContentValues()
        cvDebt.put(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cvDebt,
                WalletContract.AccountDebtEntry._ID + "=? AND " +
                        WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(id))

        val cvAcc = ContentValues()
        cvAcc.put(WalletContract.AccountEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountEntry.TABLE_NAME, cvAcc,
                WalletContract.AccountEntry._ID + "=?", arrayOf<String>(id))
        return DeleteAccountResult.deleted
    }

    // ── Капитальные активы (openspec capital-assets) ───────────────────────────

    /**
     * Помечает кошелёк капитальным активом или меняет тип/«Учитывать во „Всего с
     * капиталом“» (design п. 1). Строка одна на счёт: снятая раньше (мягко удалённая)
     * оживает с тем же uuid, второй строки не появляется. dirty поднимает триггер или
     * DEFAULT. Только для кошелька с живой `account_operating`.
     *
     * @return false — счёт не кошелёк (категория или удалён), ничего не записано
     */
    @JvmStatic
    fun SetAccountAsset(db: SQLiteDatabase, accountId: Int, assetType: Int, includeInNetWorth: Boolean): Boolean {
        val id = accountId.toString()
        var uuid: String? = null
        val c = db.rawQuery("SELECT a." + WalletContract.AccountEntry.COLUMN_UUID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a" +
                " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao." +
                WalletContract.AccountOperatingEntry._ID + " = a." + WalletContract.AccountEntry._ID +
                " AND ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL" +
                " WHERE a." + WalletContract.AccountEntry._ID + " = ? AND a." +
                WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(id))
        try {
            if (!c.moveToFirst()) {
                Log.w(TAG, "SetAccountAsset: account id=" + accountId + " is not a live wallet")
                return false
            }
            uuid = c.getString(0)
        } finally {
            c.close()
        }
        // Кошелёк и актив, и долг — не предлагается (debts, сценарий «Кошелёк уже актив»).
        if (IsDebtWallet(db, accountId)) {
            Log.w(TAG, "SetAccountAsset: account id=" + accountId + " is a debt wallet")
            return false
        }

        val cv = ContentValues()
        cv.put(WalletContract.AccountAssetEntry.COLUMN_ASSET_TYPE, assetType)
        cv.put(WalletContract.AccountAssetEntry.COLUMN_INCLUDE_IN_NET_WORTH, if (includeInNetWorth) 1 else 0)
        cv.putNull(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT)
        val updated = db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cv,
                WalletContract.AccountAssetEntry._ID + "=?", arrayOf<String>(id))
        if (updated > 0) return true

        cv.put(WalletContract.AccountAssetEntry._ID, accountId)
        // uuid = account.uuid — тот же инвариант, что у account_operating.
        cv.put(WalletContract.AccountAssetEntry.COLUMN_UUID, uuid)
        return db.insert(WalletContract.AccountAssetEntry.TABLE_NAME, null, cv) > 0
    }

    /** Снимает признак актива — мягкое удаление строки, операции кошелька не трогаются. */
    @JvmStatic
    fun ClearAccountAsset(db: SQLiteDatabase, accountId: Int) {
        val cv = ContentValues()
        cv.put(WalletContract.AccountAssetEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cv,
                WalletContract.AccountAssetEntry._ID + "=? AND " +
                        WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(accountId.toString()))
    }

    /** Есть ли на телефоне хоть один живой актив — для действия «Перенести операции в актив». */
    @JvmStatic
    fun HasAssets(db: SQLiteDatabase): Boolean {
        val c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountAssetEntry.TABLE_NAME + " aa" +
                " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a ON a._id = aa._id AND a.deleted_at IS NULL" +
                " WHERE aa." + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL LIMIT 1", null)
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    // ── Долги (openspec debts) ─────────────────────────────────────────────────

    /** Результат [DeleteCounterparty]. */
    enum class DeleteCounterpartyResult { deleted, hasDebts, notFound }

    /** Имя контрагента для записи: пробелы по краям обрезаются (design D1). */
    @JvmStatic
    fun NormalizeCounterpartyName(name: String?): String {
        return if (name == null) "" else name.trim { it <= ' ' }
    }

    /**
     * _id живого контрагента с тем же именем (регистр, «ё»/«е», пробелы по краям — правило
     * [AccountNames], индекс `uq_counterparty_name`) или -1.
     */
    @JvmStatic
    fun GetCounterpartyIdByName(db: SQLiteDatabase, name: String): Int {
        val c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry._ID +
                " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME +
                " WHERE " + AccountNames.sameSql(WalletContract.CounterpartyEntry.COLUMN_NAME) +
                " AND " + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(NormalizeCounterpartyName(name)))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    /**
     * Заводит контрагента. Имя занято (сценарии «Дубль имени», «Дубль имени через „ё“») или
     * пустое — ничего не пишет и возвращает -1: экран предлагает выбрать существующего
     * через [GetCounterpartyIdByName].
     */
    @JvmStatic
    fun CreateCounterparty(db: SQLiteDatabase, name: String, comment: String?): Int {
        val clean = NormalizeCounterpartyName(name)
        if (clean.isEmpty() || GetCounterpartyIdByName(db, clean) > 0) return -1
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyEntry.COLUMN_NAME, clean)
        cv.put(WalletContract.CounterpartyEntry.COLUMN_COMMENT, if (comment == null) "" else comment)
        cv.put(WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED, 0)
        cv.put(WalletContract.CounterpartyEntry.COLUMN_UUID, UUID.randomUUID().toString())
        val id: Long = db.insert(WalletContract.CounterpartyEntry.TABLE_NAME, null, cv)
        return if (id > 0) id.toInt() else -1
    }

    /** Переименование; false — имя пустое или занято другим живым контрагентом. */
    @JvmStatic
    fun RenameCounterparty(db: SQLiteDatabase, id: Int, name: String): Boolean {
        val clean = NormalizeCounterpartyName(name)
        if (clean.isEmpty()) return false
        val other = GetCounterpartyIdByName(db, clean)
        if (other > 0 && other != id) return false
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyEntry.COLUMN_NAME, clean)
        return db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=? AND " +
                        WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(id.toString())) > 0
    }

    /** Комментарий контрагента. */
    @JvmStatic
    fun UpdateCounterpartyComment(db: SQLiteDatabase, id: Int, comment: String?) {
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyEntry.COLUMN_COMMENT, if (comment == null) "" else comment)
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", arrayOf<String>(id.toString()))
    }

    /** Архив и разархив: займы остаются, контрагент пропадает из выбора при вводе. */
    @JvmStatic
    fun ArchiveCounterparty(db: SQLiteDatabase, id: Int, archived: Boolean) {
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED, if (archived) 1 else 0)
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", arrayOf<String>(id.toString()))
    }

    /**
     * Мягкое удаление контрагента — только без живых займов (сценарий «Удаление контрагента
     * с долгом»; design D12). Локальная привязка к контакту уходит вместе с ним.
     */
    @JvmStatic
    fun DeleteCounterparty(db: SQLiteDatabase, id: Int): DeleteCounterpartyResult {
        val uuid = counterpartyUuid(db, id)
        if (uuid == null) return DeleteCounterpartyResult.notFound
        if (CountLiveDebts(db, uuid) > 0) {
            Log.w(TAG, "Refused to delete counterparty id=" + id + " — it has live debts")
            return DeleteCounterpartyResult.hasDebts
        }
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", arrayOf<String>(id.toString()))
        db.delete(WalletContract.CounterpartyContactEntry.TABLE_NAME,
                WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID + "=?", arrayOf<String>(uuid))
        return DeleteCounterpartyResult.deleted
    }

    /** Привязка к контакту телефона (design D7) — только локальная, без синка. */
    @JvmStatic
    fun SetCounterpartyContact(db: SQLiteDatabase, counterpartyUuid: String?, lookupKey: String) {
        val cv = ContentValues()
        cv.put(WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID, counterpartyUuid)
        cv.put(WalletContract.CounterpartyContactEntry.COLUMN_LOOKUP_KEY, lookupKey)
        db.insertWithOnConflict(WalletContract.CounterpartyContactEntry.TABLE_NAME, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Отвязать контакт от контрагента (карточка контрагента); имя контрагента не меняется. */
    @JvmStatic
    fun ClearCounterpartyContact(db: SQLiteDatabase, counterpartyUuid: String) {
        db.delete(WalletContract.CounterpartyContactEntry.TABLE_NAME,
                WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID + " = ?",
                arrayOf<String>(counterpartyUuid))
    }

    /**
     * Иконка контрагента (карточка контрагента, design D5): форма отдаёт `_id` иконки, в
     * `counterparty.icon_uuid` хранится её uuid — он переживает синк между телефонами.
     * `iconId <= 0` или неизвестная/удалённая иконка — «без иконки» (`NULL`).
     *
     * @return false, если контрагента нет
     */
    @JvmStatic
    fun SetCounterpartyIcon(db: SQLiteDatabase, counterpartyId: Int, iconId: Int): Boolean {
        val uuid: String? = if (iconId > 0) iconUuidById(db, iconId) else null
        val cv = ContentValues()
        if (uuid == null) cv.putNull(WalletContract.CounterpartyEntry.COLUMN_ICON_UUID)
        else cv.put(WalletContract.CounterpartyEntry.COLUMN_ICON_UUID, uuid)
        return db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=? AND " +
                        WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(counterpartyId.toString())) > 0
    }

    /** `_id` живой иконки по uuid или -1 (иконки нет на этом телефоне или не задана). */
    @JvmStatic
    fun IconIdByUuid(db: SQLiteDatabase, iconUuid: String?): Int {
        if (iconUuid == null) return -1
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry.COLUMN_UUID + " = ?" +
                " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL LIMIT 1",
                arrayOf<String>(iconUuid))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    private fun iconUuidById(db: SQLiteDatabase, iconId: Int): String? {
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry.COLUMN_UUID +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry._ID + " = ?" +
                " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(iconId.toString()))
        try {
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    /** `LOOKUP_KEY` контакта контрагента или null. */
    @JvmStatic
    fun GetCounterpartyContact(db: SQLiteDatabase, counterpartyUuid: String): String? {
        val c = db.rawQuery("SELECT " + WalletContract.CounterpartyContactEntry.COLUMN_LOOKUP_KEY +
                " FROM " + WalletContract.CounterpartyContactEntry.TABLE_NAME +
                " WHERE " + WalletContract.CounterpartyContactEntry.COLUMN_COUNTERPARTY_UUID + " = ?",
                arrayOf<String>(counterpartyUuid))
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    /** Сколько живых долговых кошельков у контрагента (живая строка `account_debt` у живого счёта). */
    @JvmStatic
    fun CountLiveDebts(db: SQLiteDatabase, counterpartyUuid: String): Int {
        val c = db.rawQuery("SELECT count(*) FROM " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad" +
                " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a ON a._id = ad._id AND a.deleted_at IS NULL" +
                " WHERE ad." + WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID + " = ?" +
                " AND ad." + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(counterpartyUuid))
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    /** uuid живого контрагента по _id или null — для экранов вне пакета Data. */
    @JvmStatic
    fun CounterpartyUuid(db: SQLiteDatabase, id: Int): String? {
        return counterpartyUuid(db, id)
    }

    /**
     * Имя контрагента по имени кошелька-займа: хвост « · <ISO>» и « · <ISO> · N» шаблона
     * [DebtWalletName] отбрасывается («Александ Кулигин · EUR» → «Александ Кулигин»),
     * другое имя — как есть, без пробелов по краям.
     */
    @JvmStatic
    fun SuggestCounterpartyName(walletName: String?): String {
        if (walletName == null) return ""
        // Kotlin: replaceFirst(String, String) — буквальная подстановка, регулярка нужна явно через Regex.
        return walletName.replaceFirst(Regex("\\s*·\\s*[A-Z]{3}(\\s*·\\s*\\d+)?\\s*\$"), "").trim { it <= ' ' } // not UI: pattern
    }

    /** uuid живого контрагента по _id или null. */
    @JvmStatic
    fun counterpartyUuid(db: SQLiteDatabase, id: Int): String? {
        val c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry.COLUMN_UUID +
                " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CounterpartyEntry._ID + "=? AND " +
                WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(id.toString()))
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    /** Есть ли у счёта живая строка `account_debt`. */
    @JvmStatic
    fun IsDebtWallet(db: SQLiteDatabase, accountId: Int): Boolean {
        val c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountDebtEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountDebtEntry._ID + "=? AND " +
                WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(accountId.toString()))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /** Есть ли у счёта живая строка `account_asset`. */
    @JvmStatic
    fun isAssetWallet(db: SQLiteDatabase, accountId: Int): Boolean {
        val c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountAssetEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountAssetEntry._ID + "=? AND " +
                WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(accountId.toString()))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /**
     * Помечает кошелёк займом контрагента или меняет комментарий/срок (design D1). Строка
     * одна на счёт: снятая раньше оживает с тем же uuid. Отказ (false, ничего не записано):
     * счёт не живой кошелёк (категория, удалён), кошелёк — капитальный актив, контрагент
     * не найден среди живых.
     *
     * @param dueDate срок `yyyy-MM-dd` или null
     */
    @JvmStatic
    fun SetAccountDebt(db: SQLiteDatabase, accountId: Int, counterpartyUuid: String?, comment: String?, dueDate: String?): Boolean {
        val id = accountId.toString()
        var uuid: String? = null
        val c = db.rawQuery("SELECT a." + WalletContract.AccountEntry.COLUMN_UUID +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a" +
                " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao." +
                WalletContract.AccountOperatingEntry._ID + " = a." + WalletContract.AccountEntry._ID +
                " AND ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL" +
                " WHERE a." + WalletContract.AccountEntry._ID + " = ? AND a." +
                WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(id))
        try {
            if (!c.moveToFirst()) {
                Log.w(TAG, "SetAccountDebt: account id=" + accountId + " is not a live wallet")
                return false
            }
            uuid = c.getString(0)
        } finally {
            c.close()
        }
        if (isAssetWallet(db, accountId)) {
            Log.w(TAG, "SetAccountDebt: account id=" + accountId + " is an asset")
            return false
        }
        if (counterpartyUuid == null || countRowsByUuid(db, WalletContract.CounterpartyEntry.TABLE_NAME, counterpartyUuid) == 0) {
            Log.w(TAG, "SetAccountDebt: unknown counterparty " + counterpartyUuid)
            return false
        }

        val cv = ContentValues()
        cv.put(WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID, counterpartyUuid)
        cv.put(WalletContract.AccountDebtEntry.COLUMN_COMMENT, if (comment == null) "" else comment)
        if (dueDate == null) cv.putNull(WalletContract.AccountDebtEntry.COLUMN_DUE_DATE)
        else cv.put(WalletContract.AccountDebtEntry.COLUMN_DUE_DATE, dueDate)
        cv.putNull(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT)
        val updated = db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cv,
                WalletContract.AccountDebtEntry._ID + "=?", arrayOf<String>(id))
        if (updated > 0) return true

        cv.put(WalletContract.AccountDebtEntry._ID, accountId)
        // uuid = account.uuid — тот же инвариант, что у account_operating и account_asset.
        cv.put(WalletContract.AccountDebtEntry.COLUMN_UUID, uuid)
        return db.insert(WalletContract.AccountDebtEntry.TABLE_NAME, null, cv) > 0
    }

    /** Снимает признак долга — мягкое удаление строки, операции кошелька не трогаются. */
    @JvmStatic
    fun ClearAccountDebt(db: SQLiteDatabase, accountId: Int) {
        val cv = ContentValues()
        cv.put(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cv,
                WalletContract.AccountDebtEntry._ID + "=? AND " +
                        WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(accountId.toString()))
    }

    /**
     * «Новый заём» (сценарии «Новый заём», «Второй заём в той же валюте»): кошелёк
     * «<имя> · <ISO>» в валюте займа с живой строкой `account_debt`; имя занято любым
     * живым счётом — « · 2», « · 3»… Иконка — иконка контрагента, если она есть на телефоне,
     * иначе «unknown». Вызывать в транзакции: при отказе на середине кошелёк без признака
     * долга не должен остаться.
     *
     * @return _id нового кошелька или -1 (контрагент не найден, нет иконки, валюта не найдена)
     */
    @JvmStatic
    fun CreateDebtWallet(db: SQLiteDatabase, counterpartyId: Int, currencyId: Int, comment: String, dueDate: String?): Int {
        var cpUuid: String? = null
        var cpName: String? = null
        var cpIcon: String? = null
        var c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry.COLUMN_UUID +
                ", " + WalletContract.CounterpartyEntry.COLUMN_NAME +
                ", " + WalletContract.CounterpartyEntry.COLUMN_ICON_UUID +
                " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CounterpartyEntry._ID + "=? AND " +
                WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(counterpartyId.toString()))
        try {
            if (c.moveToFirst()) {
                cpUuid = c.getString(0)
                cpName = c.getString(1)
                cpIcon = if (c.isNull(2)) null else c.getString(2)
            }
        } finally {
            c.close()
        }
        if (cpUuid == null) return -1

        var iso: String? = null
        c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME +
                " FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyEntry._ID + "=? AND " +
                WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(currencyId.toString()))
        try {
            if (c.moveToFirst()) iso = c.getString(0)
        } finally {
            c.close()
        }
        if (iso == null) return -1

        val iconId = debtWalletIconId(db, cpIcon)
        if (iconId <= 0) {
            Log.w(TAG, "CreateDebtWallet: no icon")
            return -1
        }

        val base = DebtWalletName(cpName, iso)
        var name = base
        var n = 2
        while (GetAccountIdByName(db, name) > 0) {
            name = base + " · " + n // not UI: name pattern
            n++
        }

        val walletId: Long = InsertOperatingAccount(db, name, "", iconId, currencyId)
        if (walletId <= 0) return -1
        if (!SetAccountDebt(db, walletId.toInt(), cpUuid, comment, dueDate)) return -1
        return walletId.toInt()
    }

    /** Имя кошелька займа «<имя контрагента> · <код валюты>» (спека, «Долговой кошелёк»). */
    @JvmStatic
    fun DebtWalletName(counterpartyName: String?, iso: String): String {
        return counterpartyName + " · " + iso // not UI: name pattern
    }

    private fun debtWalletIconId(db: SQLiteDatabase, iconUuid: String?): Int {
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE (" + WalletContract.IconEntry.COLUMN_UUID + " = ? OR " +
                WalletContract.IconEntry.COLUMN_NAME + " = 'unknown')" +
                " AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                " ORDER BY CASE WHEN " + WalletContract.IconEntry.COLUMN_UUID + " = ? THEN 0 ELSE 1 END LIMIT 1",
                arrayOf<String>(if (iconUuid == null) "" else iconUuid, if (iconUuid == null) "" else iconUuid))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    private fun countRowsByUuid(db: SQLiteDatabase, table: String, uuid: String): Int {
        val c = db.rawQuery("SELECT count(*) FROM " + table + " WHERE uuid = ? AND deleted_at IS NULL",
                arrayOf<String>(uuid))
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    /**
     * Системная категория «Переоценка» (design п. 4): уже есть (в том числе пришла синком с
     * другого устройства) — она; нет — заводится с `system_kind = 3`. Имя
     * «Переоценка» занято обычной категорией — «Переоценка активов» (чужую категорию в
     * системную не превращаем: её прошлые проводки ушли бы из расходов); занято и оно —
     * с номером « (2)», « (3)»…
     */
    @JvmStatic
    fun GetOrCreateRevaluationCategory(db: SQLiteDatabase): Int {
        val systemKind = AccountItem.SystemKind.REVALUATION.value
        val id = GetAccountIdBySystemKind(db, systemKind)
        if (id > 0) return id
        var name = AccountItem.SYSTEM_REVALUATION_NAME
        if (GetAccountIdByName(db, name) > 0) {
            name = AccountItem.SYSTEM_REVALUATION_FALLBACK_NAME
            var n = 2
            while (GetAccountIdByName(db, name) > 0) {
                name = AccountItem.SYSTEM_REVALUATION_FALLBACK_NAME + " (" + n + ")"
                n++
            }
        }
        return createCategory(db, name, "Percentage", systemKind)
    }

    /** Итог [WriteRevaluation]. */
    enum class RevaluationResult { written, unchanged, negative, failed }

    /**
     * Переоценка актива (openspec capital-assets, asset-revaluation): одна проводка на |Δ|,
     * Δ = новая оценка − баланс актива по всем его проводкам до конца выбранного дня
     * включительно. Δ &gt; 0 — «Переоценка» → актив, Δ &lt; 0 — актив → «Переоценка». Пишется
     * на конец дня (`yyyy-MM-dd 23:59`), чтобы встать после всех операций того дня.
     * Δ округляется до `precision` валюты актива; Δ = 0 — ничего не пишется.
     *
     * @param day      дата в формате `yyyy-MM-dd`
     * @param newValue новая оценка в валюте актива
     */
    @JvmStatic
    fun WriteRevaluation(db: SQLiteDatabase, assetId: Int, day: String, newValue: Double, precision: Int, comment: String): RevaluationResult {
        if (newValue < 0) return RevaluationResult.negative
        val endOfDay = day + " 23:59"
        val current: Double = WalletDbLoader.LoadAccountBalanceAt(db, assetId, endOfDay)
        val delta: Double = java.math.BigDecimal.valueOf(newValue - current)
                .setScale(precision, java.math.RoundingMode.HALF_UP).toDouble()
        if (delta == 0.0) return RevaluationResult.unchanged

        db.beginTransaction()
        try {
            val categoryId = GetOrCreateRevaluationCategory(db)
            if (categoryId <= 0) return RevaluationResult.failed
            val id: Long = if (delta > 0) InsertOperation(db, endOfDay, categoryId, assetId, delta, comment) else InsertOperation(db, endOfDay, assetId, categoryId, -delta, comment)
            if (id <= 0) return RevaluationResult.failed
            db.setTransactionSuccessful()
            return RevaluationResult.written
        } finally {
            db.endTransaction()
        }
    }

    /** Итог [WriteOffBalance]. */
    enum class WriteOffResult { written, zero, failed }

    /**
     * Остаток кошелька, округлённый до `precision` его валюты (HALF_UP): итоговый, по
     * всем живым проводкам (archive-wallet-writeoff, D2, D3). 0 — списывать нечего.
     */
    @JvmStatic
    fun RoundedWalletBalance(db: SQLiteDatabase, walletId: Int, precision: Int): Double {
        return java.math.BigDecimal.valueOf(WalletDbLoader.LoadAccountBalance(db, walletId))
                .setScale(precision, java.math.RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Списание остатка кошелька на категорию при архивации (openspec archive-wallet-writeoff):
     * остаток &gt; 0 — «кошелёк → категория» на остаток, &lt; 0 — «категория → кошелёк» на
     * |остаток|. Остаток считается здесь же ([RoundedWalletBalance]), а не берётся из
     * диалога: за время диалога мог прийти синк. Пишется на конец дня
     * (`yyyy-MM-dd 23:59`), как переоценка. Вызывается внутри транзакции сохранения
     * экрана «Кошелёк» — вложенная транзакция откатит всё вместе с ней.
     *
     * @param day дата в формате `yyyy-MM-dd`
     */
    @JvmStatic
    fun WriteOffBalance(db: SQLiteDatabase, walletId: Int, categoryId: Int, day: String, precision: Int, comment: String): WriteOffResult {
        val balance: Double = RoundedWalletBalance(db, walletId, precision)
        if (balance == 0.0) return WriteOffResult.zero
        if (categoryId <= 0) return WriteOffResult.failed
        val endOfDay = day + " 23:59"
        val id: Long = if (balance > 0) InsertOperation(db, endOfDay, walletId, categoryId, balance, comment) else InsertOperation(db, endOfDay, categoryId, walletId, -balance, comment)
        return if (id > 0) WriteOffResult.written else WriteOffResult.failed
    }

    /**
     * «Простить» (openspec debts, D8): проводка «заём → категория» (расход) при остатке
     * &gt; 0 и «категория → заём» (доход) при &lt; 0, на `amount` в валюте займа, на конец
     * дня — как списание при архивации. Категория обычная: прощение — настоящий расход или
     * доход и попадает в отчёты.
     *
     * @return zero — остаток нулевой, прощать нечего; failed — нет категории или суммы
     */
    @JvmStatic
    fun ForgiveDebt(db: SQLiteDatabase, loanId: Int, categoryId: Int, day: String, amount: Double, precision: Int, comment: String): WriteOffResult {
        val balance: Double = RoundedWalletBalance(db, loanId, precision)
        if (balance == 0.0) return WriteOffResult.zero
        if (categoryId <= 0 || amount <= 0) return WriteOffResult.failed
        val `when` = ForgiveDateTime(day, java.util.Date())
        // Прощение всего остатка гасит заём — авто-архив (openspec wallets-filter-and-debt-autoarchive).
        db.beginTransaction()
        try {
            val debtsBefore = DebtAutoArchive.begin(db)
            val id: Long = if (balance > 0) InsertOperation(db, `when`, loanId, categoryId, amount, comment) else InsertOperation(db, `when`, categoryId, loanId, amount, comment)
            if (id <= 0) return WriteOffResult.failed
            debtsBefore.finish(db)
            db.setTransactionSuccessful()
            return WriteOffResult.written
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Момент проводки прощения: за сегодня — текущее время (`yyyy-MM-dd HH:mm`), иначе
     * карточка периода, считающая долги до «сейчас», не видела бы прощение до полуночи, а
     * экран «Долги» (на завтра 00:00) — видел; за любой другой день — конец дня
     * (`yyyy-MM-dd 23:59`).
     */
    @JvmStatic
    fun ForgiveDateTime(day: String, now: java.util.Date): String {
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        if (!dayFmt.format(now).equals(day)) return day + " 23:59"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(now)
    }

    /**
     * Категория прощения по умолчанию — обычная (не системная, `system_kind = 0`) с этим
     * именем: есть — она, нет — заводится при первом использовании (спека «Прощение долга»).
     */
    @JvmStatic
    fun GetOrCreateDebtWriteOffCategory(db: SQLiteDatabase, name: String): Int {
        val id = GetAccountIdByName(db, name)
        if (id > 0) return id
        return createCategory(db, name, "unknown", AccountItem.SystemKind.NONE.value) // not UI: icon name
    }

    /** Итог [MoveCategoryToAsset]: сколько перенесено и сколько пропущено без курса. */
    class MoveResult(@JvmField val moved: Int, @JvmField val skippedNoRate: Int, @JvmField val archived: Boolean)

    /** Живые операции категории с кошельком на другой стороне — сколько перенесёт [MoveCategoryToAsset]. */
    @JvmStatic
    fun CountCategoryWalletOperations(db: SQLiteDatabase, categoryId: Int): Int {
        val id = categoryId.toString()
        val c = db.rawQuery("SELECT count(*) FROM " + WalletContract.OperationEntry.TABLE_NAME + " o" +
                " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " w ON w.deleted_at IS NULL" +
                " AND w._id = CASE WHEN o.acc_fr_id = ? THEN o.acc_to_id ELSE o.acc_fr_id END" +
                " WHERE (o.acc_fr_id = ? OR o.acc_to_id = ?) AND o.deleted_at IS NULL", arrayOf<String>(id, id, id))
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
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
     *   операции, как `TransactionActivity.saveTransfer`: исходная проводка
     *   становится «кошелёк → Transfer», новая нога «Transfer → актив» на сумму × курс
     *   (округление до `precision` актива), связка `LEG`. Возврат — зеркально:
     *   новая «актив → Transfer», исходная становится «Transfer → кошелёк». Нет курса на
     *   дату — операция пропускается и считается; уже связанная разновалютная операция
     *   (нога чужого перевода) тоже пропускается.</li>
     *   <li>Правила повторяющихся операций (локальные) с этой категорией и кошельком в
     *   валюте актива перенацеливаются на актив; разновалютные остаются как есть.</li>
     *   <li>Не осталось живых операций — категория уходит в архив.</li>
     * </ul>
     * Ошибка посреди переноса откатывает всё (исключение пробрасывается).
     */
    @JvmStatic
    fun MoveCategoryToAsset(db: SQLiteDatabase, categoryId: Int, assetId: Int): MoveResult {
        val assetCurrency = walletCurrencyId(db, assetId)
        if (assetCurrency <= 0) throw IllegalArgumentException("asset " + assetId + " is not a wallet")
        val assetPrecision = currencyPrecision(db, assetCurrency)
        val cat = categoryId.toString()

        db.beginTransaction()
        try {
            var moved = 0
            var skipped = 0
            var bridgeId = -1
            val ops = ArrayList<LongArray>()      // _id, wallet, walletCurrency, isRefund(0/1), linked(0/1)
            val dates = ArrayList<String>()
            val amounts = ArrayList<Double>()
            val c = db.rawQuery("SELECT o._id, o.date, o.amount, o.acc_fr_id, o.acc_to_id, w.currency_id," +
                    " EXISTS (SELECT 1 FROM " + WalletContract.OperationLinkEntry.TABLE_NAME + " l" +
                    "   WHERE (l.main_id = o._id OR l.link_id = o._id) AND l.deleted_at IS NULL)" +
                    " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o" +
                    " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " w ON w.deleted_at IS NULL" +
                    "   AND w._id = CASE WHEN o.acc_fr_id = ? THEN o.acc_to_id ELSE o.acc_fr_id END" +
                    " WHERE (o.acc_fr_id = ? OR o.acc_to_id = ?) AND o.deleted_at IS NULL",
                    arrayOf<String>(cat, cat, cat))
            try {
                while (c.moveToNext()) {
                    val refund = c.getInt(3) == categoryId
                    val wallet: Long = if (refund) c.getLong(4) else c.getLong(3)
                    ops.add(longArrayOf(c.getLong(0), wallet, c.getLong(5), if (refund) 1L else 0L, c.getLong(6)))
                    dates.add(c.getString(1))
                    amounts.add(c.getDouble(2))
                }
            } finally {
                c.close()
            }

            for (i in 0 until ops.size) {
                val op: LongArray = ops.get(i)
                val opId = (op[0]).toInt()
                val wallet = (op[1]).toInt()
                val refund = op[3] == 1L
                if (wallet == assetId) { skipped++; continue; } // проводка уже на самом активе — переводом в себя не станет
                if (op[2] == assetCurrency.toLong()) {
                    val cv = ContentValues()
                    cv.put(if (refund) WalletContract.OperationEntry.COLUMN_FROM else WalletContract.OperationEntry.COLUMN_TO, assetId)
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", arrayOf<String>(opId.toString()))
                    moved++
                    continue
                }
                if (op[4] == 1L) { skipped++; continue; }
                val rate = WalletDbLoader.LoadRateOnDate(db, (op[2]).toInt(), assetCurrency,
                        com.example.wallettransactions.DateUtils.StrToDate(dates.get(i)))
                if (rate == null) { skipped++; continue; }
                if (bridgeId <= 0) {
                    bridgeId = GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER.value,
                            AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
                    if (bridgeId <= 0) throw IllegalStateException("no Transfer category")
                }
                val assetAmount: Double = java.math.BigDecimal.valueOf(amounts.get(i) * rate.rate)
                        .setScale(assetPrecision, java.math.RoundingMode.HALF_UP).toDouble()
                val comment = GetOperationComment(db, opId)
                val cv = ContentValues()
                if (!refund) {
                    cv.put(WalletContract.OperationEntry.COLUMN_TO, bridgeId)
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", arrayOf<String>(opId.toString()))
                    val leg: Long = InsertOperation(db, dates.get(i), bridgeId, assetId, assetAmount, comment)
                    if (leg <= 0) throw IllegalStateException("leg insert failed for operation " + opId)
                    LinkOperations(db, opId.toLong(), leg, OperationItem.LinkType.LEG)
                } else {
                    cv.put(WalletContract.OperationEntry.COLUMN_FROM, bridgeId)
                    db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                            WalletContract.OperationEntry._ID + "=?", arrayOf<String>(opId.toString()))
                    val main: Long = InsertOperation(db, dates.get(i), assetId, bridgeId, assetAmount, comment)
                    if (main <= 0) throw IllegalStateException("leg insert failed for operation " + opId)
                    LinkOperations(db, main, opId.toLong(), OperationItem.LinkType.LEG)
                }
                moved++
            }

            // Правила повторяющихся операций: только одновалютные (кошелёк в валюте актива).
            val sameCurrencyWallet = "SELECT _id FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME +
                    " WHERE deleted_at IS NULL AND currency_id = " + assetCurrency + " AND _id <> " + assetId
            val rt = WalletContract.RecurringOperationEntry.TABLE_NAME
            db.execSQL("UPDATE " + rt + " SET " + WalletContract.RecurringOperationEntry.COLUMN_TO + " = ?" +
                    " WHERE " + WalletContract.RecurringOperationEntry.COLUMN_TO + " = ? AND " +
                    WalletContract.RecurringOperationEntry.COLUMN_FROM + " IN (" + sameCurrencyWallet + ")",
                    arrayOf<Any?>(assetId, categoryId))
            db.execSQL("UPDATE " + rt + " SET " + WalletContract.RecurringOperationEntry.COLUMN_FROM + " = ?" +
                    " WHERE " + WalletContract.RecurringOperationEntry.COLUMN_FROM + " = ? AND " +
                    WalletContract.RecurringOperationEntry.COLUMN_TO + " IN (" + sameCurrencyWallet + ")",
                    arrayOf<Any?>(assetId, categoryId))

            var archived = false
            if (!HasOperations(db, categoryId)) {
                val cv = ContentValues()
                cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 1)
                db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                        WalletContract.AccountEntry._ID + "=?", arrayOf<String>(cat))
                archived = true
            }
            db.setTransactionSuccessful()
            return MoveResult(moved, skipped, archived)
        } finally {
            db.endTransaction()
        }
    }

    private fun GetOperationComment(db: SQLiteDatabase, operationId: Int): String {
        val c = db.rawQuery("SELECT " + WalletContract.OperationEntry.COLUMN_COMMENT + " FROM " +
                WalletContract.OperationEntry.TABLE_NAME + " WHERE _id = ?", arrayOf<String>(operationId.toString()))
        try {
            return if (c.moveToFirst()) c.getString(0) else ""
        } finally {
            c.close()
        }
    }

    /** Валюта кошелька (живая `account_operating`) или -1. */
    @JvmStatic
    fun walletCurrencyId(db: SQLiteDatabase, accountId: Int): Int {
        val c = db.rawQuery("SELECT " + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID + " FROM " +
                WalletContract.AccountOperatingEntry.TABLE_NAME + " WHERE _id = ? AND deleted_at IS NULL",
                arrayOf<String>(accountId.toString()))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    private fun currencyPrecision(db: SQLiteDatabase, currencyId: Int): Int {
        val c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_PRECISION + " FROM " +
                WalletContract.CurrencyEntry.TABLE_NAME + " WHERE _id = ?", arrayOf<String>(currencyId.toString()))
        try {
            return if (c.moveToFirst()) c.getInt(0) else 2
        } finally {
            c.close()
        }
    }

    @JvmStatic
    fun UpdateAccountCurrency(db: SQLiteDatabase, accountId: Int, currencyId: Int): Int {
        val cv = ContentValues()
        cv.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId)
        return db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cv,
                WalletContract.AccountOperatingEntry._ID + "=?",
                arrayOf<String>(accountId.toString()))
    }

    @JvmStatic
    fun InsertOperatingAccount(db: SQLiteDatabase, name: String, comment: String, iconId: Int, currencyId: Int): Long {
        val accountUuid = UUID.randomUUID().toString()
        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name)
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, comment)
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId)
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 0)
        // kind=WALLET — эта строка account тут же получает пару в account_operating
        // (ниже), то есть становится кошельком (см. javadoc AccountItem.Kind).
        cv.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.value)
        cv.put(WalletContract.AccountEntry.COLUMN_UUID, accountUuid)
        val newId: Long = db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv)
        if (newId > 0) {
            val cvOp = ContentValues()
            cvOp.put(WalletContract.AccountOperatingEntry._ID, newId)
            cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId)
            // uuid — ТОТ ЖЕ, что у только что вставленного account, не свежий
            // случайный (см. wallet-api/schema/table/account_operating.sql:
            // "uuid здесь всегда равен account.uuid" — общий первичный ключ на
            // уровне синка; иначе push такого кошелька упал бы FK-ошибкой
            // fk_account_operating_account на сервере).
            cvOp.put(WalletContract.AccountOperatingEntry.COLUMN_UUID, accountUuid)
            db.insert(WalletContract.AccountOperatingEntry.TABLE_NAME, null, cvOp)
        }
        return newId
    }

    /**
     * v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — голый `INSERT OR
     * REPLACE`, а для SQLite это "удалить старую строку целиком и вставить новую"
     * — заново сгенерированный `uuid` на каждую перезагрузку одного и того же
     * курса раздувал бы поток синка дублями вместо одного обновления. Теперь:
     * нашлась строка на эту (пара, дата) — обновляем (uuid не трогаем, dirty
     * форсит триггер `trg_currency_pair_rate_dirty`), не нашлась — вставляем
     * с новым uuid.
     */
    @JvmStatic
    fun InsertOrReplaceRate(db: SQLiteDatabase, pairSymbol: String, date: String, rate: Double) {
        val pairId = getCurrencyPairIdBySymbol(db, pairSymbol)
        if (pairId <= 0) return

        val cv = ContentValues()
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_RATE, rate)

        // deleted_at IS NULL — реальный баг, найденный на живом устройстве
        // (2026-09-11, тот же класс в WalletFillData.InsertCurrencyPairRate/
        // RateEditDialog.saveRate): без этого условия matcher "оживлял" мягко
        // удалённую строку, оставляя deleted_at проставленным, — сервер уже
        // не покрывает такую строку частичным индексом uq_currency_pair_rate_
        // ledger (WHERE deleted_at IS NULL), ON CONFLICT по нему не находил
        // "конфликта" и падал на currency_pair_rate_pkey голым INSERT'ом с уже
        // занятым uuid ("duplicate_key").
        val updated = db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND " +
                        WalletContract.CurrencyPairRateEntry.COLUMN_DATE + "=? AND " +
                        WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(pairId.toString(), date))
        if (updated > 0) return

        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE, pairId)
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, date)
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_UUID, UUID.randomUUID().toString())
        db.insert(WalletContract.CurrencyPairRateEntry.TABLE_NAME, null, cv)
    }

    private fun getCurrencyPairIdBySymbol(db: SQLiteDatabase, symbol: String): Int {
        val c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?" +
                " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(symbol))
        try {
            return if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    @JvmStatic
    fun InsertCurrencyPair(db: SQLiteDatabase, baseIso: String, quoteIso: String, precision: Int): Long {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_SYMBOL, baseIso + quoteIso)
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID,
                getCurrencyIdByIso(db, baseIso))
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID,
                getCurrencyIdByIso(db, quoteIso))
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_PRECISION, precision)
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_UUID, UUID.randomUUID().toString())
        return db.insert(WalletContract.CurrencyPairEntry.TABLE_NAME, null, cv)
    }

    // deleted_at IS NULL — иначе после soft-delete (см. DeleteCurrency) и
    // пересоздания валюты с тем же ISO-кодом (теперь разрешено — см. частичный
    // индекс uq_currency_iso_code) эта функция резолвила бы в СТАРУЮ, удалённую
    // строку вместо новой живой.
    private fun getCurrencyIdByIso(db: SQLiteDatabase, iso: String): Int {
        val c = db.rawQuery(
                "SELECT _id FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + "=?" +
                " AND " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(iso))
        try {
            if (c.moveToFirst()) return c.getInt(0)
            return -1
        } finally {
            c.close()
        }
    }

    @JvmStatic
    fun InsertIcon(db: SQLiteDatabase, name: String, iconType: Int, bitmap: Bitmap): Long {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val bytes: ByteArray = bos.toByteArray()

        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, iconType)
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, bytes)
        cv.put(WalletContract.IconEntry.COLUMN_UUID, UUID.randomUUID().toString())
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL)
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    @JvmStatic
    fun InsertAccount(db: SQLiteDatabase, accName: String, accComment: String, iconId: Int, isArchive: Boolean): Int {
        return InsertAccount(db, accName, accComment, iconId, isArchive, AccountItem.CategoryKind.BOTH)
    }

    /**
     * Категория с явным видом (openspec category-kind-income-expense). Пять-аргументная
     * перегрузка заводит вид «Оба» — умолчание колонки; кошельки и системные категории
     * этим путём не создаются, у них вид всегда «Оба».
     */
    @JvmStatic
    fun InsertAccount(db: SQLiteDatabase, accName: String, accComment: String, iconId: Int, isArchive: Boolean, categoryKind: AccountItem.CategoryKind?): Int {
        val kind = categoryKind ?: AccountItem.CategoryKind.BOTH
        db.execSQL("insert into account (name, comment, icon_id,is_archived,uuid,category_kind)\n" +
                        "Values(?,?,?,?,?,?); \n",
                arrayOf<String>(accName, accComment, iconId.toString(), if (isArchive) "1" else "0",
                        UUID.randomUUID().toString(), kind.value.toString()))
        return WalletFillData.GetAffectedRowsCount(db) 
    }


    @JvmStatic
    fun UpdateAccount(db: SQLiteDatabase, id: Int, accName: String, accComment: String, iconId: Int, isArchive: Boolean): Int {
        return UpdateAccount(db, id, accName, accComment, iconId, isArchive, null)
    }

    /**
     * Правка счёта вместе с видом категории. `categoryKind == null` — вид не трогаем
     * (кошельки, системные категории, прежние вызовы); иначе пишем его и тем самым помечаем
     * строку изменённой для синка, как и любую другую правку.
     */
    @JvmStatic
    fun UpdateAccount(db: SQLiteDatabase, id: Int, accName: String, accComment: String, iconId: Int, isArchive: Boolean, categoryKind: AccountItem.CategoryKind?): Int {
        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, accName)
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, accComment)
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId)
        cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, isArchive)
        if (categoryKind != null) cv.put(WalletContract.AccountEntry.COLUMN_CATEGORY_KIND, categoryKind.value)
        // Новое имя или иконка — строка больше не «из набора» (new-user-default-set, design D3).
        if (rowDiffers(db, WalletContract.AccountEntry.TABLE_NAME, id,
                arrayOf<String>(WalletContract.AccountEntry.COLUMN_NAME, WalletContract.AccountEntry.COLUMN_ICON_ID),
                arrayOf<String>(accName, Integer.toString(iconId)))) {
            cv.put(WalletContract.AccountEntry.COLUMN_FROM_SEED, 0)
        }

        return db.update(WalletContract.AccountEntry.TABLE_NAME, cv, WalletContract.AccountEntry._ID+"="+id, null)
    }

    /**
     * Отличается ли хоть одна из `columns` строки `id` от `values`
     * (сравнение в SQLite, `IS NOT`: тот же тип, что в колонке). Нет строки — `false`.
     */
    @JvmStatic
    fun rowDiffers(db: SQLiteDatabase, table: String, id: Int, columns: Array<String>, values: Array<String>): Boolean {
        val where = StringBuilder()
        val args = arrayOfNulls<String>(values.size + 1)
        for (i in 0 until columns.size) {
            if (i > 0) where.append(" OR ")
            where.append(columns[i]).append(" IS NOT ?")
            args[i] = values[i]
        }
        args[values.size] = Integer.toString(id)
        val c = db.rawQuery("SELECT 1 FROM " + table + " WHERE (" + where + ") AND _id = ? LIMIT 1", args)
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    // ── Валюты (экран «Список валют» → форма валюты) ───────────────────────────

    @JvmStatic
    fun InsertCurrency(db: SQLiteDatabase, name: String, iso: String, symbol: String, precision: Int, iconId: Int, showInEquivalent: Boolean): Long {
        // uuid — только здесь, не в currencyValues(): тот же метод используют
        // Insert и Update, а uuid не должен меняться при правке существующей валюты.
        val cv = currencyValues(name, iso, symbol, precision, iconId, showInEquivalent)
        cv.put(WalletContract.CurrencyEntry.COLUMN_UUID, UUID.randomUUID().toString())
        return db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, cv)
    }

    /**
     * Общая валюта (is_shared = 1) не правится: сервер такой push молча пропускает,
     * и телефон разошёлся бы с ним (openspec currency-ui-tweaks, design D7). Форма её
     * уже не даёт править — условие здесь страхует обход формы.
     */
    @JvmStatic
    fun UpdateCurrency(db: SQLiteDatabase, id: Int, name: String, iso: String, symbol: String, precision: Int, iconId: Int, showInEquivalent: Boolean): Int {
        val cv = currencyValues(name, iso, symbol, precision, iconId, showInEquivalent)
        // Новые имя, код, символ или иконка — валюта больше не «из набора» (design D3).
        if (rowDiffers(db, WalletContract.CurrencyEntry.TABLE_NAME, id,
                arrayOf<String>(WalletContract.CurrencyEntry.COLUMN_NAME, WalletContract.CurrencyEntry.COLUMN_ISO_NAME,
                        WalletContract.CurrencyEntry.COLUMN_SYMBOL, WalletContract.CurrencyEntry.COLUMN_ICON_ID),
                arrayOf<String>(name, iso, symbol, Integer.toString(iconId)))) {
            cv.put(WalletContract.CurrencyEntry.COLUMN_FROM_SEED, 0)
        }
        return db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry._ID + "=? AND " +
                        WalletContract.CurrencyEntry.COLUMN_IS_SHARED + "=0",
                arrayOf<String>(Integer.toString(id)))
    }

    private fun currencyValues(name: String, iso: String, symbol: String, precision: Int, iconId: Int, showInEquivalent: Boolean): ContentValues {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, name)
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso)
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol)
        cv.put(WalletContract.CurrencyEntry.COLUMN_PRECISION, precision)
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId)
        cv.put(WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT, if (showInEquivalent) 1 else 0)
        return cv
    }

    /**
     * Сменить ISO-код валюты, входящей в живую пару, нельзя (design D8): символ пары —
     * склейка кодов, и пара осталась бы со старым символом. То же правило, что
     * `409 currency_in_pair` в REST wallet-api. Код не меняется (с точностью до
     * регистра) — правка разрешена, даже если валюта в паре.
     */
    @JvmStatic
    fun IsIsoChangeBlocked(db: SQLiteDatabase, currencyId: Int, newIso: String): Boolean {
        if (currencyId <= 0) return false
        val c = db.rawQuery("SELECT " + WalletContract.CurrencyEntry.COLUMN_ISO_NAME +
                        " FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                        " WHERE " + WalletContract.CurrencyEntry._ID + "=?",
                arrayOf<String>(Integer.toString(currencyId)))
        var oldIso: String? = null
        try {
            if (!c.moveToFirst()) return false
            oldIso = c.getString(0)
        } finally {
            c.close()
        }
        if (oldIso != null && oldIso.equals(newIso, ignoreCase = true)) return false
        return IsCurrencyInPair(db, currencyId)
    }

    /** Валюта — база или котировка хотя бы одной живой пары. */
    @JvmStatic
    fun IsCurrencyInPair(db: SQLiteDatabase, currencyId: Int): Boolean {
        return countRows(db, "SELECT COUNT(*) FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME +
                " WHERE (" + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID + "=?" +
                " OR " + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID + "=?)" +
                " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                currencyId, currencyId) > 0
    }

    /**
     * Мягко удаляет свою пару и все её живые курсы одной транзакцией (design D3) —
     * тот же каскад, что `DELETE /api/currency-pairs/{uuid`} на сервере. dirty
     * ставит триггер, push отправит и пару, и курсы tombstone'ами. Общую пару
     * (is_shared = 1) не трогает и возвращает 0 — проверка здесь, а не только в UI.
     * Маршруты пересчёта (currency-conversion-routes), шаги которых используют пару, гасятся
     * целиком (все шаги маршрута) в той же транзакции — как на сервере: без этого после
     * пересоздания пары (новый uuid) старый маршрут остался бы оборванным навсегда.
     *
     * @return 1 — пара удалена, 0 — не найдена, уже удалена или общая.
     */
    @JvmStatic
    fun DeleteCurrencyPair(db: SQLiteDatabase, pairId: Int): Int {
        val now: Long = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val pairCv = ContentValues()
            pairCv.put(WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT, now)
            val pairs = db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, pairCv,
                    WalletContract.CurrencyPairEntry._ID + "=? AND " +
                            WalletContract.CurrencyPairEntry.COLUMN_IS_SHARED + "=0 AND " +
                            WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                    arrayOf<String>(Integer.toString(pairId)))
            if (pairs == 0) return 0

            val rateCv = ContentValues()
            rateCv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT, now)
            db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, rateCv,
                    WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND " +
                            WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                    arrayOf<String>(Integer.toString(pairId)))

            // Маршруты с шагом по этой паре — целиком (все шаги), тем же моментом.
            db.execSQL("UPDATE " + WalletContract.CurrencyConversionEntry.TABLE_NAME +
                    " SET deleted_at = ? WHERE deleted_at IS NULL AND (from_currency_id, to_currency_id) IN (" +
                    "SELECT from_currency_id, to_currency_id FROM " + WalletContract.CurrencyConversionEntry.TABLE_NAME +
                    " WHERE currency_pair_id = ? AND deleted_at IS NULL)",
                    arrayOf<Any?>(now, pairId))
            db.setTransactionSuccessful()
            return pairs
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Валюта, на которую ссылается кошелёк или валютная пара, унесла бы за собой
     * счёт без валюты и курс без пары — поэтому такие удалять нельзя.
     */
    // deleted_at IS NULL на обеих проверках — с v72 мягко удалённый кошелёк/пара
    // не должен больше считаться "занятостью" валюты (тот же довод, что у
    // HasOperations выше).
    @JvmStatic
    fun IsCurrencyInUse(db: SQLiteDatabase, currencyId: Int): Boolean {
        return countRows(db, "SELECT COUNT(*) FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID + "=?" +
                " AND " + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL", currencyId) > 0
            || IsCurrencyInPair(db, currencyId)
    }

    /** Мягкое удаление с v72 — тот же довод, что у DeleteOperation/DeleteAccount выше. */
    @JvmStatic
    fun DeleteCurrency(db: SQLiteDatabase, currencyId: Int): Int {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        return db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry._ID + "=?", arrayOf<String>(Integer.toString(currencyId)))
    }

    private fun countRows(db: SQLiteDatabase, sql: String, vararg args: Int): Int {
        val params = Array(args.size) { Integer.toString(args[it]) }

        val c = db.rawQuery(sql, params)
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }
}
