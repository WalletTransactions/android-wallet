package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.DataItems.Operations.TemplateSchedule
import com.example.wallettransactions.DataItems.Operations.TemplateStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Шаблоны операций на настоящей схеме БД ([OperationTemplatesDb], openspec operation-templates):
 * запись запуска ([OperationTemplatesDb.apply]) с комиссией и откатом, генератор по
 * расписанию (повторы ровно один раз, `last_date`, выключенные, мягко удалённый счёт,
 * ошибочная раскладка), правка, удаление и каскад при физическом удалении счёта.
 */
@RunWith(RobolectricTestRunner::class)
class OperationTemplatesDbTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var walletId: Int = 0
    private var cashId: Int = 0
    private var categoryId: Int = 0
    private var commissionId: Int = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase

        val iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0),
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        val currencyId = insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, "Test Euro",
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TST",
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T",
                WalletContract.CurrencyEntry.COLUMN_PRECISION, 2)
        walletId = wallet("Test card", iconId, currencyId)
        cashId = wallet("Test cash", iconId, currencyId)
        categoryId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test rent",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId).toInt()
        commissionId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test commission",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId).toInt()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun wallet(name: String, iconId: Long, currencyId: Long): Int {
        val id = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, name,
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                WalletContract.AccountEntry.COLUMN_KIND, 1).toInt()
        insert(WalletContract.AccountOperatingEntry.TABLE_NAME,
                WalletContract.AccountOperatingEntry._ID, id,
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId)
        return id
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val v = kv[i + 1]
            if (v is ByteArray) cv.put(kv[i] as String, v)
            else if (v is Number) cv.put(kv[i] as String, v.toLong())
            else cv.put(kv[i] as String, v as String)
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }

    private fun step(num: Int, from: Int, to: Int, mode: TemplateStep.AmountMode, value: String?,
                     commissionOf: Int? = null): TemplateStep {
        val s = TemplateStep()
        s.num = num
        s.accFromId = from
        s.accToId = to
        s.amountMode = mode
        s.amountValue = value?.let { BigDecimal(it) }
        s.commissionOfNum = commissionOf
        return s
    }

    private fun monthlyRent(): OperationTemplate {
        val t = OperationTemplate()
        t.name = "Аренда"
        t.comment = "Аренда"
        t.totalAmount = BigDecimal("500")
        t.steps.add(step(1, walletId, categoryId, TemplateStep.AmountMode.REST, null))
        val s = TemplateSchedule()
        s.frequency = TemplateSchedule.Frequency.MONTHLY
        s.startDate = LocalDateTime.of(2026, 1, 5, 10, 0)
        t.schedule = s
        return t
    }

    /** «Снятие наличных»: карта → наличные (остаток) + комиссия фикс 50 к шагу 1. */
    private fun cashWithdrawal(): OperationTemplate {
        val t = OperationTemplate()
        t.name = "Снятие наличных"
        t.totalAmount = BigDecimal("1550")
        t.steps.add(step(1, walletId, cashId, TemplateStep.AmountMode.REST, null))
        t.steps.add(step(2, walletId, commissionId, TemplateStep.AmountMode.FIXED, "50", commissionOf = 1))
        return t
    }

    private fun countOperations(from: Int = walletId, to: Int = categoryId): Int {
        db.rawQuery("SELECT COUNT(*) FROM operation WHERE acc_fr_id = ? AND acc_to_id = ?",
                arrayOf(from.toString(), to.toString())).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun scalar(sql: String): String? {
        db.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    // ── Хранение ──────────────────────────────────────────────────────────────

    @Test
    fun insertAndLoad_roundTripsHeaderStepsAndSchedule() {
        val t = cashWithdrawal()
        assertEquals(true, OperationTemplatesDb.insert(db, t) > 0)

        val loaded = OperationTemplatesDb.load(db, t.id)!!
        assertEquals("Снятие наличных", loaded.name)
        assertEquals(0, BigDecimal("1550").compareTo(loaded.totalAmount))
        assertNull("без расписания", loaded.schedule)
        assertEquals(2, loaded.steps.size)
        assertEquals(TemplateStep.AmountMode.REST, loaded.steps[0].amountMode)
        assertEquals(1, loaded.steps[1].commissionOfNum)
        assertEquals(0, BigDecimal("50").compareTo(loaded.steps[1].amountValue))
        assertEquals(t.uuid, loaded.uuid)
        assertEquals(t.steps[1].uuid, loaded.steps[1].uuid)
    }

    @Test
    fun update_keepsStepUuidsByNumAndDropsRemovedSteps() {
        val t = cashWithdrawal()
        OperationTemplatesDb.insert(db, t)
        val mainUuid = t.steps[0].uuid

        val edited = OperationTemplatesDb.load(db, t.id)!!
        edited.steps.removeAt(1)
        edited.schedule = monthlyRent().schedule
        assertEquals(1, OperationTemplatesDb.update(db, edited))

        val loaded = OperationTemplatesDb.load(db, t.id)!!
        assertEquals(1, loaded.steps.size)
        assertEquals(mainUuid, loaded.steps[0].uuid)
        assertNotNull(loaded.schedule)

        // «Не повторять» — расписание убирается целиком, шаги остаются.
        loaded.schedule = null
        OperationTemplatesDb.update(db, loaded)
        val manual = OperationTemplatesDb.load(db, t.id)!!
        assertNull(manual.schedule)
        assertEquals(1, manual.steps.size)
    }

    @Test
    fun delete_keepsCreatedOperations() {
        val t = cashWithdrawal()
        OperationTemplatesDb.insert(db, t)
        OperationTemplatesDb.apply(db, t, listOf(BigDecimal("1500.00"), BigDecimal("50.00")),
                LocalDateTime.of(2026, 5, 1, 9, 0))

        OperationTemplatesDb.delete(db, t.id)

        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
        assertEquals("0", scalar("SELECT COUNT(*) FROM operation_template_step"))
        assertEquals(1, countOperations(walletId, cashId))
    }

    @Test
    fun hardDeletedAccount_removesWholeTemplate() {
        OperationTemplatesDb.insert(db, cashWithdrawal())
        OperationTemplatesDb.insert(db, monthlyRent())
        // Комиссия — второй шаг: шаблон без неё был бы уже другим, он удаляется целиком.
        db.execSQL("DELETE FROM account WHERE _id = $commissionId")

        val left = OperationTemplatesDb.loadAll(db)
        assertEquals(1, left.size)
        assertEquals("Аренда", left[0].name)
    }

    // ── Запись запуска ────────────────────────────────────────────────────────

    @Test
    fun apply_createsAllPostingsWithCommissionLink() {
        val t = cashWithdrawal()
        OperationTemplatesDb.insert(db, t)

        val ids = OperationTemplatesDb.apply(db, t, listOf(BigDecimal("1500.00"), BigDecimal("50.00")),
                LocalDateTime.of(2026, 5, 1, 9, 30))!!

        assertEquals(2, ids.size)
        assertEquals("2026-05-01 09:30", scalar("SELECT date FROM operation WHERE _id = " + ids[0]))
        assertEquals("2026-05-01 09:30", scalar("SELECT date FROM operation WHERE _id = " + ids[1]))
        assertEquals(1500.0, scalar("SELECT amount FROM operation WHERE _id = " + ids[0])!!.toDouble(), 0.001)
        assertEquals(50.0, scalar("SELECT amount FROM operation WHERE _id = " + ids[1])!!.toDouble(), 0.001)
        assertEquals("1", scalar("SELECT type FROM operation_link WHERE main_id = ${ids[0]} AND link_id = ${ids[1]}"))
        // Расписания нет — ручной запуск его не трогает.
        assertNull(OperationTemplatesDb.load(db, t.id)!!.schedule)
    }

    @Test
    fun apply_stepsWithoutCommissionRole_areNotLinked() {
        val t = cashWithdrawal()
        t.steps[1].commissionOfNum = null
        OperationTemplatesDb.insert(db, t)

        OperationTemplatesDb.apply(db, t, listOf(BigDecimal("1500"), BigDecimal("50")), LocalDateTime.of(2026, 5, 1, 9, 0))

        assertEquals("0", scalar("SELECT COUNT(*) FROM operation_link"))
    }

    @Test
    fun apply_usesStepCommentOrTemplateComment() {
        val t = cashWithdrawal()
        t.comment = "Банкомат"
        t.steps[0].comment = "Наличные"
        OperationTemplatesDb.insert(db, t)

        val ids = OperationTemplatesDb.apply(db, t, listOf(BigDecimal("1500"), BigDecimal("50")),
                LocalDateTime.of(2026, 5, 1, 9, 0))!!

        assertEquals("Наличные", scalar("SELECT comment FROM operation WHERE _id = " + ids[0]))
        assertEquals("Банкомат", scalar("SELECT comment FROM operation WHERE _id = " + ids[1]))
    }

    @Test
    fun apply_failureOfSecondPosting_rollsBackFirst() {
        val t = cashWithdrawal()
        OperationTemplatesDb.insert(db, t)
        // Счёт второй проводки исчез из памяти шаблона — вставка нарушит внешний ключ.
        t.steps[1].accToId = 999_999

        val ids = OperationTemplatesDb.apply(db, t, listOf(BigDecimal("1500"), BigDecimal("50")),
                LocalDateTime.of(2026, 5, 1, 9, 0))

        assertNull(ids)
        assertEquals("0", scalar("SELECT COUNT(*) FROM operation"))
    }

    // ── Генератор ─────────────────────────────────────────────────────────────

    @Test
    fun createsDueOccurrencesOnceAndAdvancesLastDate() {
        val t = monthlyRent()
        OperationTemplatesDb.insert(db, t)

        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        assertEquals(3, OperationTemplatesDb.generateDue(db, now))
        assertEquals(0, OperationTemplatesDb.generateDue(db, now))
        assertEquals(3, countOperations())

        val stored = OperationTemplatesDb.load(db, t.id)!!
        assertEquals(LocalDateTime.of(2026, 3, 5, 10, 0), stored.schedule!!.lastDate)

        assertEquals(1, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 4, 5, 10, 0)))
        assertEquals(4, countOperations())
    }

    @Test
    fun manualTemplates_areNotGenerated() {
        OperationTemplatesDb.insert(db, cashWithdrawal())
        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 12, 1, 0, 0)))
    }

    @Test
    fun respectsEndDateAndInactiveTemplates() {
        val t = monthlyRent()
        t.schedule!!.endDate = LocalDate.of(2026, 2, 10)
        OperationTemplatesDb.insert(db, t)

        val off = monthlyRent()
        off.schedule!!.isActive = false
        OperationTemplatesDb.insert(db, off)

        assertEquals(2, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 12, 1, 0, 0)))
    }

    @Test
    fun stopsAfterEndDateEvenWhenTemplateStaysActive() {
        val t = monthlyRent()
        val s = t.schedule!!
        s.frequency = TemplateSchedule.Frequency.DAILY
        s.startDate = LocalDateTime.of(2026, 9, 1, 10, 0)
        s.endDate = LocalDate.of(2026, 9, 3)
        OperationTemplatesDb.insert(db, t)

        // Приложение открывают каждый день: и до, и после «даты по».
        for (day in 1..10) {
            OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 9, day, 12, 0))
        }
        assertEquals(3, countOperations())

        val stored = OperationTemplatesDb.load(db, t.id)!!.schedule!!
        assertEquals(LocalDateTime.of(2026, 9, 3, 10, 0), stored.lastDate)
        assertEquals(false, stored.isRunning(LocalDateTime.of(2026, 9, 10, 12, 0)))
    }

    @Test
    fun endedTemplateStillCatchesUpOccurrencesBeforeEndDate() {
        val t = monthlyRent()
        t.schedule!!.endDate = LocalDate.of(2026, 3, 5)
        OperationTemplatesDb.insert(db, t)

        // Приложение не открывали с января — повторы до «даты по» досоздаются, после — нет.
        assertEquals(3, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 9, 30, 12, 0)))
        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 10, 30, 12, 0)))
    }

    @Test
    fun skipsTemplateOnSoftDeletedAccount() {
        val t = monthlyRent()
        OperationTemplatesDb.insert(db, t)
        db.execSQL("UPDATE account SET deleted_at = 1 WHERE _id = $categoryId")

        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 3, 20, 12, 0)))
        assertEquals("шаблон остаётся в списке", 1, OperationTemplatesDb.loadAll(db).size)
        assertNull(OperationTemplatesDb.load(db, t.id)!!.schedule!!.lastDate)
    }

    @Test
    fun scheduledTemplateWithCommission_createsLinkedPairPerOccurrence() {
        val t = cashWithdrawal()
        t.schedule = monthlyRent().schedule
        OperationTemplatesDb.insert(db, t)

        assertEquals(2, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 1, 20, 12, 0)))

        assertEquals(1, countOperations(walletId, cashId))
        assertEquals(1, countOperations(walletId, commissionId))
        assertEquals(1500.0, scalar("SELECT amount FROM operation WHERE acc_to_id = $cashId")!!.toDouble(), 0.001)
        assertEquals("2026-01-05 10:00", scalar("SELECT date FROM operation WHERE acc_to_id = $commissionId"))
        assertEquals("1", scalar("SELECT type FROM operation_link"))
    }

    @Test
    fun overflowingLayout_isSkippedWithoutMovingLastDate() {
        val t = cashWithdrawal()
        t.totalAmount = BigDecimal("40")
        t.schedule = monthlyRent().schedule
        OperationTemplatesDb.insert(db, t)

        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 3, 20, 12, 0)))
        assertNull(OperationTemplatesDb.load(db, t.id)!!.schedule!!.lastDate)
    }

    // ── Включение и выключение ────────────────────────────────────────────────

    @Test
    fun reenabling_doesNotCatchUpPausedOccurrences() {
        val t = monthlyRent()
        t.schedule!!.startDate = LocalDateTime.now().minusMonths(3)
        t.schedule!!.isActive = false
        OperationTemplatesDb.insert(db, t)

        OperationTemplatesDb.setActive(db, t, true)

        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.now()))
        assertEquals(true, OperationTemplatesDb.load(db, t.id)!!.schedule!!.isActive)
    }
}
