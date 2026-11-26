package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.DataItems.Operations.TemplateLayout
import com.example.wallettransactions.DataItems.Operations.TemplateSchedule
import com.example.wallettransactions.DataItems.Operations.TemplateStep
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Чтение/запись шаблонов операций ([WalletContract.OperationTemplateEntry] и
 * [WalletContract.OperationTemplateStepEntry]), запись проводок по шаблону ([apply]) и
 * генератор по расписанию ([generateDue]) — openspec operation-templates.
 *
 * Ручной запуск и генератор пишут проводки одним кодом ([insertPostings]), поэтому
 * результат у них одинаковый: все шаги, связки комиссии, комментарий шага или шаблона.
 */
object OperationTemplatesDb {

    private const val TAG = "OperationTemplates"

    /** Тот же формат, что у `operation.date` (см. `DateUtils.formatDB`). */
    @JvmField
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @JvmField
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val T = WalletContract.OperationTemplateEntry
    private val S = WalletContract.OperationTemplateStepEntry

    // ── Чтение ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun loadAll(db: SQLiteDatabase): List<OperationTemplate> = query(db, null, null)

    @JvmStatic
    fun load(db: SQLiteDatabase, id: Int): OperationTemplate? {
        val list = query(db, T._ID + " = ?", arrayOf(id.toString()))
        return if (list.isEmpty()) null else list[0]
    }

    private fun query(db: SQLiteDatabase, where: String?, args: Array<String>?): List<OperationTemplate> {
        val result = ArrayList<OperationTemplate>()
        val byId = HashMap<Int, OperationTemplate>()
        db.query(T.TABLE_NAME, null, where, args, null, null, T._ID).use { c ->
            while (c.moveToNext()) {
                val t = templateFromCursor(c)
                result.add(t)
                byId[t.id] = t
            }
        }
        if (result.isEmpty()) return result
        db.query(S.TABLE_NAME, null,
                S.COLUMN_TEMPLATE_ID + " IN (" + byId.keys.joinToString(",") + ")", null,
                null, null, S.COLUMN_TEMPLATE_ID + ", " + S.COLUMN_NUM).use { c ->
            while (c.moveToNext()) {
                val templateId = c.getInt(c.getColumnIndexOrThrow(S.COLUMN_TEMPLATE_ID))
                byId[templateId]?.steps?.add(stepFromCursor(c))
            }
        }
        return result
    }

    private fun templateFromCursor(c: Cursor): OperationTemplate {
        val t = OperationTemplate()
        t.id = c.getInt(c.getColumnIndexOrThrow(T._ID))
        t.uuid = c.getString(c.getColumnIndexOrThrow(T.COLUMN_UUID))
        t.name = c.getString(c.getColumnIndexOrThrow(T.COLUMN_NAME)) ?: ""
        t.comment = c.getString(c.getColumnIndexOrThrow(T.COLUMN_COMMENT)) ?: ""
        t.totalAmount = decimal(c, T.COLUMN_TOTAL_AMOUNT)
        val freq = c.getColumnIndexOrThrow(T.COLUMN_FREQUENCY)
        if (!c.isNull(freq)) {
            val s = TemplateSchedule()
            s.frequency = TemplateSchedule.Frequency.fromValue(c.getInt(freq))
            s.interval = c.getInt(c.getColumnIndexOrThrow(T.COLUMN_INTERVAL))
            s.weekDays = c.getInt(c.getColumnIndexOrThrow(T.COLUMN_WEEK_DAYS))
            s.startDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(T.COLUMN_START_DATE)))
            s.endDate = parseDate(c.getString(c.getColumnIndexOrThrow(T.COLUMN_END_DATE)))
            s.isActive = c.getInt(c.getColumnIndexOrThrow(T.COLUMN_IS_ACTIVE)) != 0
            s.lastDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(T.COLUMN_LAST_DATE)))
            t.schedule = s
        }
        return t
    }

    private fun stepFromCursor(c: Cursor): TemplateStep {
        val s = TemplateStep()
        s.id = c.getInt(c.getColumnIndexOrThrow(S._ID))
        s.uuid = c.getString(c.getColumnIndexOrThrow(S.COLUMN_UUID))
        s.num = c.getInt(c.getColumnIndexOrThrow(S.COLUMN_NUM))
        s.accFromId = c.getInt(c.getColumnIndexOrThrow(S.COLUMN_FROM))
        s.accToId = c.getInt(c.getColumnIndexOrThrow(S.COLUMN_TO))
        s.amountMode = TemplateStep.AmountMode.fromValue(c.getInt(c.getColumnIndexOrThrow(S.COLUMN_AMOUNT_MODE)))
        s.amountValue = decimal(c, S.COLUMN_AMOUNT_VALUE)
        s.comment = c.getString(c.getColumnIndexOrThrow(S.COLUMN_COMMENT)) ?: ""
        val of = c.getColumnIndexOrThrow(S.COLUMN_COMMISSION_OF_NUM)
        s.commissionOfNum = if (c.isNull(of)) null else c.getInt(of)
        return s
    }

    private fun decimal(c: Cursor, column: String): BigDecimal? {
        val i = c.getColumnIndexOrThrow(column)
        if (c.isNull(i)) return null
        return try {
            BigDecimal(c.getString(i))
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Точность валюты кошелька шагов (все кошельки шаблона в одной валюте); 2 — если
     * кошелька не нашлось.
     */
    @JvmStatic
    fun precisionOf(db: SQLiteDatabase, t: OperationTemplate): Int {
        val ids = t.steps.flatMap { listOf(it.accFromId, it.accToId) }.distinct()
        if (ids.isEmpty()) return 2
        db.rawQuery("SELECT c." + WalletContract.CurrencyEntry.COLUMN_PRECISION +
                " FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME + " o" +
                " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c ON c._id = o." +
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID +
                " WHERE o._id IN (" + ids.joinToString(",") + ") LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 2
        }
    }

    // ── Запись шаблона ────────────────────────────────────────────────────────

    /** Шаблон вместе с шагами одной транзакцией. @return id нового шаблона или -1. */
    @JvmStatic
    fun insert(db: SQLiteDatabase, t: OperationTemplate): Long {
        db.beginTransaction()
        try {
            val id = db.insert(T.TABLE_NAME, null, headerValues(t, withUuid = true))
            if (id <= 0) return -1
            t.id = id.toInt()
            for (s in t.steps) {
                val sid = db.insert(S.TABLE_NAME, null, stepValues(t.id, s))
                if (sid <= 0) return -1
                s.id = sid.toInt()
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Заголовок, расписание и шаги одной транзакцией. Шаги сопоставляются по номеру:
     * существующий номер обновляется на месте (uuid шага сохраняется), новые вставляются,
     * лишние удаляются. @return 1 — записано, 0 — ошибка.
     */
    @JvmStatic
    fun update(db: SQLiteDatabase, t: OperationTemplate): Int {
        db.beginTransaction()
        try {
            if (db.update(T.TABLE_NAME, headerValues(t, withUuid = false),
                            T._ID + " = ?", arrayOf(t.id.toString())) != 1) return 0
            val existing = HashMap<Int, String>()
            db.query(S.TABLE_NAME, arrayOf(S.COLUMN_NUM, S.COLUMN_UUID),
                    S.COLUMN_TEMPLATE_ID + " = ?", arrayOf(t.id.toString()), null, null, null).use { c ->
                while (c.moveToNext()) existing[c.getInt(0)] = c.getString(1)
            }
            for (s in t.steps) {
                val uuid = existing[s.num]
                if (uuid != null) {
                    s.uuid = uuid
                    db.update(S.TABLE_NAME, stepValues(t.id, s),
                            S.COLUMN_TEMPLATE_ID + " = ? AND " + S.COLUMN_NUM + " = ?",
                            arrayOf(t.id.toString(), s.num.toString()))
                } else {
                    val sid = db.insert(S.TABLE_NAME, null, stepValues(t.id, s))
                    if (sid <= 0) return 0
                    s.id = sid.toInt()
                }
            }
            val keep = t.steps.map { it.num }
            db.delete(S.TABLE_NAME, S.COLUMN_TEMPLATE_ID + " = ?" +
                    (if (keep.isEmpty()) "" else " AND " + S.COLUMN_NUM + " NOT IN (" + keep.joinToString(",") + ")"),
                    arrayOf(t.id.toString()))
            db.setTransactionSuccessful()
            return 1
        } finally {
            db.endTransaction()
        }
    }

    /** Уже созданные по шаблону операции остаются — удаляются только шаблон и его шаги. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, id: Int): Int {
        // Шаги уходят каскадом (ON DELETE CASCADE на template_id).
        return db.delete(T.TABLE_NAME, T._ID + " = ?", arrayOf(id.toString()))
    }

    /**
     * Включение/выключение повтора. Выключенный шаблон — пауза: при повторном включении
     * пропущенные за время паузы повторы не досоздаются, счёт идёт с текущего момента.
     */
    @JvmStatic
    fun setActive(db: SQLiteDatabase, t: OperationTemplate, active: Boolean) {
        val s = t.schedule ?: return
        if (active && !s.isActive) {
            val now = LocalDateTime.now()
            val last = s.lastDate
            if (last == null || last.isBefore(now)) {
                // Расписание ещё не начиналось — ничего не пропущено, досоздавать нечего.
                val start = s.startDate
                if (start != null && start.isBefore(now)) s.lastDate = now
            }
        }
        s.isActive = active
        updateSchedule(db, t)
    }

    /** Только колонки расписания — генератор и переключатель не трогают шаги. */
    private fun updateSchedule(db: SQLiteDatabase, t: OperationTemplate): Int {
        val cv = ContentValues()
        putSchedule(cv, t.schedule)
        return db.update(T.TABLE_NAME, cv, T._ID + " = ?", arrayOf(t.id.toString()))
    }

    private fun headerValues(t: OperationTemplate, withUuid: Boolean): ContentValues {
        val cv = ContentValues()
        if (withUuid) cv.put(T.COLUMN_UUID, t.uuid)
        cv.put(T.COLUMN_NAME, t.name)
        cv.put(T.COLUMN_COMMENT, t.comment)
        val total = t.totalAmount
        if (total != null) cv.put(T.COLUMN_TOTAL_AMOUNT, total.toPlainString())
        else cv.putNull(T.COLUMN_TOTAL_AMOUNT)
        putSchedule(cv, t.schedule)
        return cv
    }

    private fun putSchedule(cv: ContentValues, s: TemplateSchedule?) {
        if (s == null) {
            // «Всё или ничего» (CHECK в схеме): без частоты нет ни дат, ни отметки повтора.
            for (col in arrayOf(T.COLUMN_FREQUENCY, T.COLUMN_START_DATE, T.COLUMN_END_DATE, T.COLUMN_LAST_DATE)) {
                cv.putNull(col)
            }
            cv.put(T.COLUMN_INTERVAL, 1)
            cv.put(T.COLUMN_WEEK_DAYS, 0)
            cv.put(T.COLUMN_IS_ACTIVE, 1)
            return
        }
        cv.put(T.COLUMN_FREQUENCY, s.frequency.value)
        cv.put(T.COLUMN_INTERVAL, maxOf(1, s.interval))
        cv.put(T.COLUMN_WEEK_DAYS, s.weekDays)
        cv.put(T.COLUMN_START_DATE, s.startDate!!.format(DATE_TIME))
        val end = s.endDate
        if (end != null) cv.put(T.COLUMN_END_DATE, end.format(DATE)) else cv.putNull(T.COLUMN_END_DATE)
        cv.put(T.COLUMN_IS_ACTIVE, if (s.isActive) 1 else 0)
        val last = s.lastDate
        if (last != null) cv.put(T.COLUMN_LAST_DATE, last.format(DATE_TIME)) else cv.putNull(T.COLUMN_LAST_DATE)
    }

    private fun stepValues(templateId: Int, s: TemplateStep): ContentValues {
        val cv = ContentValues()
        cv.put(S.COLUMN_UUID, s.uuid)
        cv.put(S.COLUMN_TEMPLATE_ID, templateId)
        cv.put(S.COLUMN_NUM, s.num)
        cv.put(S.COLUMN_FROM, s.accFromId)
        cv.put(S.COLUMN_TO, s.accToId)
        cv.put(S.COLUMN_AMOUNT_MODE, s.amountMode.value)
        val v = s.amountValue
        if (v != null && s.amountMode != TemplateStep.AmountMode.REST) cv.put(S.COLUMN_AMOUNT_VALUE, v.toPlainString())
        else cv.putNull(S.COLUMN_AMOUNT_VALUE)
        cv.put(S.COLUMN_COMMENT, s.comment)
        val of = s.commissionOfNum
        if (of != null) cv.put(S.COLUMN_COMMISSION_OF_NUM, of) else cv.putNull(S.COLUMN_COMMISSION_OF_NUM)
        return cv
    }

    // ── Запись проводок ───────────────────────────────────────────────────────

    /**
     * Ручной запуск (спека «Запись запуска»): все проводки шаблона одной транзакцией —
     * все или ни одной. Расписание шаблона не трогает.
     *
     * @param amounts суммы по шагам в порядке [OperationTemplate.steps] (раскладка, возможно поправленная)
     * @return _id созданных проводок или null — ничего не записано.
     */
    @JvmStatic
    fun apply(db: SQLiteDatabase, t: OperationTemplate, amounts: List<BigDecimal>, at: LocalDateTime): List<Long>? {
        db.beginTransaction()
        try {
            // Проводка могла погасить заём — авто-архив, как у генератора.
            val debtsBefore = DebtAutoArchive.begin(db)
            val ids = insertPostings(db, t, amounts, at)
            debtsBefore.finish(db)
            db.setTransactionSuccessful()
            return ids
        } catch (e: RuntimeException) {
            Log.e(TAG, "apply failed, template " + t.id, e)
            return null
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Проводки по шагам и связки комиссии — без своей транзакции, её держит вызывающий.
     * Сбой любой вставки — исключение, вызывающий откатывает всё.
     */
    private fun insertPostings(db: SQLiteDatabase, t: OperationTemplate, amounts: List<BigDecimal>, at: LocalDateTime): List<Long> {
        require(amounts.size == t.steps.size) { "amounts do not match steps" }
        val date = at.format(DATE_TIME)
        val ids = ArrayList<Long>()
        val idByNum = HashMap<Int, Long>()
        for ((i, s) in t.steps.withIndex()) {
            val id = WalletDbCRUD.InsertOperation(db, date, s.accFromId, s.accToId,
                    amounts[i].toDouble(), t.commentFor(s))
            if (id <= 0) throw IllegalStateException("insert operation failed, template " + t.id + " step " + s.num)
            ids.add(id)
            idByNum[s.num] = id
        }
        for (s in t.steps) {
            val of = s.commissionOfNum ?: continue
            val mainId = idByNum[of] ?: throw IllegalStateException("commission of missing step $of")
            WalletDbCRUD.LinkOperations(db, mainId, idByNum[s.num]!!, OperationItem.LinkType.COMMISSION)
        }
        return ids
    }

    // ── Генератор ─────────────────────────────────────────────────────────────

    /**
     * Заводит операции по всем включённым шаблонам с расписанием — за каждый наступивший,
     * но ещё не созданный повтор (после `last_date`, не позже `now`): все шаги шаблона с
     * его общей суммой, как ручной запуск. Пропущенные досоздаются разом, каждый своей датой.
     *
     * Шаблон пропускается, а `last_date` не сдвигается, если хоть один счёт шага мягко
     * удалён или раскладка ошибочна (комиссия больше общей суммы, суммы нет).
     *
     * @param createdIds если не `null` — сюда складываются _id созданных проводок (для уведомления).
     * @return сколько проводок создано
     */
    @JvmStatic
    @JvmOverloads
    fun generateDue(db: SQLiteDatabase, now: LocalDateTime, createdIds: MutableList<Long>? = null): Int {
        var created = 0
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            val debtsBefore = DebtAutoArchive.begin(db)
            for (t in loadAll(db)) {
                val s = t.schedule ?: continue
                if (!s.isRunning(now)) continue
                if (t.steps.isEmpty() || t.steps.any { !isLiveAccount(db, it.accFromId) || !isLiveAccount(db, it.accToId) }) continue
                val total = t.totalAmount ?: continue
                val layout = TemplateLayout.expand(t.steps, total, precisionOf(db, t))
                if (layout !is TemplateLayout.Result.Ok) {
                    Log.w(TAG, "template " + t.id + " skipped: layout " + layout.javaClass.simpleName)
                    continue
                }

                val due = s.occurrencesBetween(s.lastDate, now)
                // «Дата по» включительно — повтор позже неё не заводим ни при каком расписании.
                val end = s.endDate
                if (end != null) due.removeIf { at -> at.toLocalDate().isAfter(end) }
                if (due.isEmpty()) continue

                for (at in due) {
                    val got = insertPostings(db, t, layout.amounts, at)
                    ids.addAll(got)
                    created += got.size
                }
                s.lastDate = due[due.size - 1]
                updateSchedule(db, t)
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
