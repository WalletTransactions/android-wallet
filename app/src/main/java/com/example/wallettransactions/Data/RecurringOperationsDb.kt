package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.wallettransactions.DataItems.Operations.RecurringOperation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Чтение/запись правил повторяющихся операций ([WalletContract.RecurringOperationEntry])
 * и генератор, который по ним заводит обычные операции.
 */
object RecurringOperationsDb {

    private const val TAG = "RecurringOperations"

    /** Тот же формат, что у `operation.date` (см. `DateUtils.formatDB`). */
    @JvmField
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @JvmField
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ── Чтение ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun loadAll(db: SQLiteDatabase): List<RecurringOperation> {
        return query(db, null, null)
    }

    @JvmStatic
    fun load(db: SQLiteDatabase, id: Int): RecurringOperation? {
        val list = query(
            db, WalletContract.RecurringOperationEntry._ID + " = ?",
            arrayOf(id.toString())
        )
        return if (list.isEmpty()) null else list[0]
    }

    private fun query(db: SQLiteDatabase, where: String?, args: Array<String>?): List<RecurringOperation> {
        val result = ArrayList<RecurringOperation>()
        db.query(
            WalletContract.RecurringOperationEntry.TABLE_NAME, null, where, args,
            null, null, WalletContract.RecurringOperationEntry._ID
        ).use { c ->
            while (c.moveToNext()) result.add(fromCursor(c))
        }
        return result
    }

    private fun fromCursor(c: Cursor): RecurringOperation {
        val r = RecurringOperation()
        r.id = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry._ID))
        r.accFromId = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_FROM))
        r.accToId = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_TO))
        r.amount = c.getDouble(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_AMOUNT))
        r.comment = c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_COMMENT))
        r.frequency = RecurringOperation.Frequency.fromValue(
            c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY))
        )
        r.interval = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_INTERVAL))
        r.weekDays = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_WEEK_DAYS))
        r.startDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_START_DATE)))
        r.endDate = parseDate(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_END_DATE)))
        r.isActive = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_IS_ACTIVE)) != 0
        r.lastDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE)))
        return r
    }

    // ── Запись ────────────────────────────────────────────────────────────────

    /** @return id новой строки или -1. */
    @JvmStatic
    fun insert(db: SQLiteDatabase, r: RecurringOperation): Long {
        val id = db.insert(WalletContract.RecurringOperationEntry.TABLE_NAME, null, toValues(r))
        if (id > 0) r.id = id.toInt()
        return id
    }

    @JvmStatic
    fun update(db: SQLiteDatabase, r: RecurringOperation): Int {
        return db.update(
            WalletContract.RecurringOperationEntry.TABLE_NAME, toValues(r),
            WalletContract.RecurringOperationEntry._ID + " = ?", arrayOf(r.id.toString())
        )
    }

    /** Уже созданные по правилу операции остаются — удаляется только само правило. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, id: Int): Int {
        return db.delete(
            WalletContract.RecurringOperationEntry.TABLE_NAME,
            WalletContract.RecurringOperationEntry._ID + " = ?", arrayOf(id.toString())
        )
    }

    /**
     * Включение/выключение повтора. Выключенное правило — пауза: при повторном включении
     * пропущенные за время паузы повторы не досоздаются, счёт идёт с текущего момента.
     */
    @JvmStatic
    fun setActive(db: SQLiteDatabase, r: RecurringOperation, active: Boolean) {
        if (active && !r.isActive) {
            val now = LocalDateTime.now()
            val last = r.lastDate
            if (last == null || last.isBefore(now)) {
                // Правило ещё не начиналось — ничего не пропущено, досоздавать нечего.
                val start = r.startDate
                if (start != null && start.isBefore(now)) r.lastDate = now
            }
        }
        r.isActive = active
        update(db, r)
    }

    private fun toValues(r: RecurringOperation): ContentValues {
        val cv = ContentValues()
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FROM, r.accFromId)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_TO, r.accToId)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_AMOUNT, r.amount)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_COMMENT, r.comment ?: "")
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY, r.frequency.value)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_INTERVAL, maxOf(1, r.interval))
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_WEEK_DAYS, r.weekDays)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_START_DATE, r.startDate!!.format(DATE_TIME))
        val end = r.endDate
        if (end != null) cv.put(WalletContract.RecurringOperationEntry.COLUMN_END_DATE, end.format(DATE))
        else cv.putNull(WalletContract.RecurringOperationEntry.COLUMN_END_DATE)
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_IS_ACTIVE, if (r.isActive) 1 else 0)
        val last = r.lastDate
        if (last != null) cv.put(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE, last.format(DATE_TIME))
        else cv.putNull(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE)
        return cv
    }

    // ── Генератор ─────────────────────────────────────────────────────────────

    /**
     * Заводит операции по всем активным правилам — за каждый наступивший, но ещё не
     * созданный повтор (после `last_date`, не позже `now`). Пропущенные, пока
     * приложение не открывали, досоздаются все разом, каждая своей датой.
     *
     * Правило, у которого хоть один счёт мягко удалён, пропускается: операция на
     * удалённый счёт в ленте не нужна, а `last_date` не сдвигается.
     *
     * @param createdIds если не `null` — сюда складываются _id созданных операций (для уведомления).
     * @return сколько операций создано
     */
    @JvmStatic
    @JvmOverloads
    fun generateDue(db: SQLiteDatabase, now: LocalDateTime, createdIds: MutableList<Long>? = null): Int {
        var created = 0
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            // Повтор мог погасить заём — авто-архив (openspec wallets-filter-and-debt-autoarchive).
            val debtsBefore = DebtAutoArchive.begin(db)
            for (r in loadAll(db)) {
                if (!r.isRunning(now)) continue
                if (!isLiveAccount(db, r.accFromId) || !isLiveAccount(db, r.accToId)) continue

                val due = r.occurrencesBetween(r.lastDate, now)
                // «Дата по» включительно — повтор позже неё не заводим ни при каком расписании.
                val end = r.endDate
                if (end != null) due.removeIf { at -> at.toLocalDate().isAfter(end) }
                if (due.isEmpty()) continue

                for (at in due) {
                    val id = WalletDbCRUD.InsertOperation(
                        db, at.format(DATE_TIME),
                        r.accFromId, r.accToId, r.amount, r.comment ?: ""
                    )
                    if (id <= 0) throw IllegalStateException("insert operation failed, rule " + r.id)
                    ids.add(id)
                    created++
                }
                r.lastDate = due[due.size - 1]
                update(db, r)
            }
            debtsBefore.finish(db)
            db.setTransactionSuccessful()
            // Только после успеха: при откате этих _id в базе нет.
            createdIds?.addAll(ids)
        } catch (e: RuntimeException) {
            Log.e(TAG, "generateDue failed", e)
            created = 0
        } finally {
            db.endTransaction()
        }
        if (created > 0) Log.i(TAG, "generateDue: создано операций: $created")
        return created
    }

    private fun isLiveAccount(db: SQLiteDatabase, accountId: Int): Boolean {
        db.rawQuery(
            "SELECT 1 FROM " + WalletContract.AccountEntry.TABLE_NAME
                    + " WHERE " + WalletContract.AccountEntry._ID + " = ? AND "
                    + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
            arrayOf(accountId.toString())
        ).use { c ->
            return c.moveToFirst()
        }
    }

    // ── Даты ──────────────────────────────────────────────────────────────────

    private fun parseDateTime(s: String?): LocalDateTime? {
        if (s.isNullOrEmpty()) return null
        return try {
            if (s.length == 10) LocalDate.parse(s, DATE).atStartOfDay()
            else LocalDateTime.parse(s, DATE_TIME)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(if (s.length > 10) s.substring(0, 10) else s, DATE)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
