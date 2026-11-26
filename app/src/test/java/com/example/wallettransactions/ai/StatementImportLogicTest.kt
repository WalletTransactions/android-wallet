package com.example.wallettransactions.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.ai.StatementRowState.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Экран «Выписка» без UI (openspec add-ai-statement-import): дубли по полям на
 * настоящей схеме SQLite ([StatementDuplicates]), состояния строк и отбор
 * «готовых» ([StatementRowState]), тексты из спеки ([AiTexts]).
 */
@RunWith(RobolectricTestRunner::class)
class StatementImportLogicTest {

    companion object {
        private val TODAY = LocalDate.of(2026, 10, 26)

        private fun progress(pausedSince: String?): StatementImport =
            StatementImport(
                "i", StatementImport.EXTRACTING, null, "w", "s.pdf", 2, 6, pausedSince,
                ArrayList()
            )

        private fun row(id: String, group: String, date: String, amount: String, category: String?): StatementImport.Row =
            StatementImport.Row(
                id, group, ArrayList(), date, amount, "expense", "DESC", category,
                null, null, false
            )

        private fun withDir(r: StatementImport.Row, dir: String): StatementImport.Row =
            StatementImport.Row(
                r.rowId, r.group, r.lines, r.date, r.amount, dir, r.description,
                r.categoryUuid, r.suggestion, r.check, r.committed
            )

        private fun withCheck(r: StatementImport.Row, check: String): StatementImport.Row =
            StatementImport.Row(
                r.rowId, r.group, r.lines, r.date, r.amount, r.dir, r.description,
                r.categoryUuid, r.suggestion, check, r.committed
            )
    }

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var walletId = 0L
    private var otherWalletId = 0L
    private var taxiId = 0L
    private var iconId = 0L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        iconId = insert(
            WalletContract.IconEntry.TABLE_NAME,
            WalletContract.IconEntry.COLUMN_NAME, "test_icon",
            WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0),
            WalletContract.IconEntry.COLUMN_ICON_TYPE, 1
        )
        val currencyId = insert(
            WalletContract.CurrencyEntry.TABLE_NAME,
            WalletContract.CurrencyEntry.COLUMN_NAME, "Test Rub",
            WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TSR",
            WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
            WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T"
        )
        walletId = wallet("Test Tinkoff", currencyId)
        otherWalletId = wallet("Test Cash", currencyId)
        taxiId = category("Test Такси", "c-taxi-0000-4000-8000-000000000001")
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── StatementDuplicates ────────────────────────────────────────────────────

    @Test
    fun duplicates_enteredByHand_nextDay() {
        val op = operation(walletId, taxiId, "2026-10-12 09:30", 450.0, "manual-1")
        val rows = listOf(row("r1", "ready", "2026-10-13", "450.00", "c-taxi"))

        val m = StatementDuplicates.find(db, walletId, rows, listOf("r1"))

        assertEquals(op, m["r1"]!!.operationId)
        assertEquals("Похоже, уже есть: 12.10 450,00 Test Такси", AiTexts.statementDuplicate(m["r1"]!!))
    }

    @Test
    fun duplicates_twoCoffeesOneEntered() {
        operation(walletId, taxiId, "2026-10-12 08:00", 200.0, "manual-2")
        val rows = listOf(
            row("c1", "ready", "2026-10-12", "200.00", "c-taxi"),
            row("c2", "ready", "2026-10-12", "200.00", "c-taxi")
        )

        val m = StatementDuplicates.find(db, walletId, rows, listOf("c1", "c2"))

        assertEquals(1, m.size)
        assertTrue(m.containsKey("c1"))
    }

    @Test
    fun duplicates_secondUploadMatchesFirstImportOps() {
        // Первая загрузка записана с uuid = row_id первого импорта.
        operation(walletId, taxiId, "2026-09-03 12:00", 450.0, "aaaa1111-0000-4000-8000-000000000001")
        operation(walletId, taxiId, "2026-09-04 12:00", 300.0, "aaaa1111-0000-4000-8000-000000000002")
        val rows = listOf(
            row("bbbb2222-0000-4000-8000-000000000001", "imported", "2026-09-03", "450.00", "c-taxi"),
            row("bbbb2222-0000-4000-8000-000000000002", "imported", "2026-09-04", "300.00", null)
        )
        val ids = listOf(rows[0].rowId!!, rows[1].rowId!!)

        assertEquals(2, StatementDuplicates.find(db, walletId, rows, ids).size)
    }

    @Test
    fun duplicates_ignoreOtherWalletDeletedFarAndOwnImport() {
        operation(otherWalletId, taxiId, "2026-10-12 12:00", 450.0, "x-other")
        val deleted = operation(walletId, taxiId, "2026-10-12 12:00", 450.0, "x-deleted")
        db.execSQL("UPDATE operation SET deleted_at = 1 WHERE _id = $deleted")
        operation(walletId, taxiId, "2026-10-15 12:00", 450.0, "x-far")
        operation(walletId, taxiId, "2026-10-12 12:00", 451.0, "x-amount")
        // Операция, записанная из этого же импорта (другая его строка), — не дубль.
        operation(walletId, taxiId, "2026-10-12 12:00", 450.0, "R2-0000-4000-8000-000000000002")
        val rows = listOf(row("r1", "ready", "2026-10-12", "450.00", "c-taxi"))

        val m = StatementDuplicates.find(
            db, walletId, rows,
            listOf("r1", "r2-0000-4000-8000-000000000002")
        )

        assertTrue(m.isEmpty())
    }

    @Test
    fun duplicates_nearestDateWins() {
        operation(walletId, taxiId, "2026-10-11 12:00", 450.0, "near-before")
        val same = operation(walletId, taxiId, "2026-10-12 12:00", 450.0, "same-day")
        val rows = listOf(row("r1", "ready", "2026-10-12", "450.00", null))
        assertEquals(same, StatementDuplicates.find(db, walletId, rows, listOf("r1"))["r1"]!!.operationId)
    }

    // ── StatementRowState ─────────────────────────────────────────────────────

    @Test
    fun states_inOrder() {
        val dup = StatementDuplicates.Match(1, "2026-10-12", 450.0, "Такси")
        val ready = row("r", "ready", "2026-10-12", "450.00", "c-taxi")
        assertEquals(Kind.ADDED, StatementRowState.of(ready, true, dup))
        assertEquals(
            Kind.NOT_FOR_WRITE, StatementRowState.of(
                withDir(row("r", "income_transfer", "2026-10-12", "1.00", null), "income"), false, null
            )
        )
        assertEquals(Kind.NOT_FOR_WRITE, StatementRowState.of(row("r", "ignored", "2026-10-12", "1.00", null), false, null))
        assertEquals(Kind.CHECK, StatementRowState.of(withCheck(ready, "amount_not_in_text"), false, dup))
        assertEquals(Kind.DUPLICATE, StatementRowState.of(ready, false, dup))
        assertEquals(Kind.NEEDS_CATEGORY, StatementRowState.of(row("r", "needs_category", "2026-10-12", "1.00", null), false, null))
        assertEquals(Kind.WAITING, StatementRowState.of(ready, false, null))
    }

    /** spec: «Сервер считает дублем, телефон нет» — группа exists не мешает «Ждёт». */
    @Test
    fun states_serverDuplicateGroupsIgnored() {
        for (g in listOf("exists", "imported", "possible_duplicate")) {
            assertEquals(g, Kind.WAITING, StatementRowState.of(row("r", g, "2026-10-12", "450.00", "c-taxi"), false, null))
        }
    }

    @Test
    fun readiness_waitingWithLiveCategory() {
        val r = row("r", "ready", "2026-10-12", "450.00", "c-taxi-0000-4000-8000-000000000001")
        val ok = StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.OK, TODAY)
        assertTrue(ok.ready)
        assertEquals(taxiId, ok.categoryId)
        assertNull(ok.reason)

        assertFalse(StatementRowState.readiness(db, r, Kind.DUPLICATE, DraftReconciler.State.OK, TODAY).ready)
        assertFalse(StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.MISSING, TODAY).ready)
        val future = row("r", "ready", "2026-10-27", "450.00", "c-taxi-0000-4000-8000-000000000001")
        assertFalse(StatementRowState.readiness(db, future, Kind.WAITING, DraftReconciler.State.OK, TODAY).ready)
        val zero = row("r", "ready", "2026-10-12", "0.00", "c-taxi-0000-4000-8000-000000000001")
        assertFalse(StatementRowState.readiness(db, zero, Kind.WAITING, DraftReconciler.State.OK, TODAY).ready)
    }

    /** spec: «Категории нет на телефоне». */
    @Test
    fun readiness_deletedCategory() {
        db.execSQL("UPDATE account SET deleted_at = 1 WHERE _id = $taxiId")
        val r = row("r", "ready", "2026-10-12", "450.00", "c-taxi-0000-4000-8000-000000000001")
        val rd = StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.OK, TODAY)
        assertFalse(rd.ready)
        assertEquals("Категории «Test Такси» нет на телефоне", rd.reason)
    }

    // ── AiTexts ──────────────────────────────────────────────────────────────

    @Test
    fun texts_fromSpec() {
        assertEquals("Файл больше 5 МБ — разбейте выписку по месяцам", AiTexts.statementFileTooBig())
        assertEquals("В PDF нет текста — это скан. Нужна выписка с текстовым слоем", AiTexts.statementFailure("no_text"))
        assertEquals("В файле не нашлось операций", AiTexts.statementFailure("no_operations"))
        assertEquals("Уже разбирается другая выписка", AiTexts.statementHttpError(429, "import_in_progress"))
        assertEquals("ИИ сейчас выключен", AiTexts.statementHttpError(503, "unavailable"))
        assertEquals("Нет связи с сервером — список недоступен", AiTexts.statementNoNetwork())
        assertEquals("Разбираю выписку: 2 из 6", AiTexts.statementProgress(progress(null)))
        assertEquals("Разбор на паузе: ИИ недоступен", AiTexts.statementProgress(progress("2026-10-24T10:00:00Z")))
        assertEquals("Добавлена", AiTexts.statementRowState(Kind.ADDED, null))
        assertEquals("Не для записи", AiTexts.statementRowState(Kind.NOT_FOR_WRITE, null))
        assertEquals("Проверить", AiTexts.statementRowState(Kind.CHECK, null))
        assertEquals("Нужна категория", AiTexts.statementRowState(Kind.NEEDS_CATEGORY, null))
        assertEquals("Ждёт", AiTexts.statementRowState(Kind.WAITING, null))
        assertEquals(
            "Похоже, уже есть: 12.10 450,00 Такси", AiTexts.statementRowState(
                Kind.DUPLICATE,
                StatementDuplicates.Match(1, "2026-10-12", 450.0, "Такси")
            )
        )
        assertEquals("1 290,00", AiTexts.statementAmount(1290.0))
    }

    private fun wallet(name: String, currencyId: Long): Long {
        val id = insert(
            WalletContract.AccountEntry.TABLE_NAME,
            WalletContract.AccountEntry.COLUMN_NAME, name,
            WalletContract.AccountEntry.COLUMN_COMMENT, "",
            WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
            WalletContract.AccountEntry.COLUMN_KIND, 1
        )
        insert(
            WalletContract.AccountOperatingEntry.TABLE_NAME,
            WalletContract.AccountOperatingEntry._ID, id,
            WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId
        )
        return id
    }

    private fun category(name: String, uuid: String): Long =
        insert(
            WalletContract.AccountEntry.TABLE_NAME,
            WalletContract.AccountEntry.COLUMN_NAME, name,
            WalletContract.AccountEntry.COLUMN_COMMENT, "",
            WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
            "uuid", uuid
        )

    private fun operation(from: Long, to: Long, date: String, amount: Double, uuid: String): Long =
        insert(
            WalletContract.OperationEntry.TABLE_NAME,
            WalletContract.OperationEntry.COLUMN_DATE, date,
            WalletContract.OperationEntry.COLUMN_FROM, from,
            WalletContract.OperationEntry.COLUMN_TO, to,
            WalletContract.OperationEntry.COLUMN_AMOUNT, amount,
            WalletContract.OperationEntry.COLUMN_COMMENT, "",
            WalletContract.OperationEntry.COLUMN_UUID, uuid
        )

    private fun insert(table: String, vararg kv: Any?): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            when (val value = kv[i + 1]) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String?)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
