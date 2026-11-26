package com.example.wallettransactions.ai

import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.ai.ParseExpenseResult.Choice
import com.example.wallettransactions.ai.ParseExpenseResult.Ref
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DraftReconciler]: состояния «есть / нет / удалён / в архиве» и что из
 * этого видит карточка (spec ai-expense-entry, «Кошелёк или категория пропали
 * с телефона»).
 */
@RunWith(RobolectricTestRunner::class)
class DraftReconcilerTest {

    companion object {
        private const val LIVE = "11111111-1111-1111-1111-111111111111"
        private const val GONE = "22222222-2222-2222-2222-222222222222"
        private const val ARCH = "33333333-3333-3333-3333-333333333333"
        private const val DASHLESS = "44444444444444444444444444444444"
        private const val UNKNOWN = "55555555-5555-5555-5555-555555555555"
    }

    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE account (_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, " +
                "is_archived INTEGER DEFAULT 0, deleted_at INTEGER)"
        )
        db.execSQL("INSERT INTO account (_id, uuid, name) VALUES (1, '$LIVE', 'Тинькофф')")
        db.execSQL("INSERT INTO account (_id, uuid, name, deleted_at) VALUES (2, '$GONE', 'Наличные', 5)")
        db.execSQL("INSERT INTO account (_id, uuid, name, is_archived) VALUES (3, '$ARCH', 'Спорт', 1)")
        db.execSQL("INSERT INTO account (_id, uuid, name) VALUES (4, '$DASHLESS', 'Кафе')")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun lookup_fourStates() {
        assertEquals(DraftReconciler.State.OK, DraftReconciler.lookup(db, ref(LIVE)).state)
        assertEquals(1L, DraftReconciler.lookup(db, ref(LIVE)).id)
        assertEquals(DraftReconciler.State.DELETED, DraftReconciler.lookup(db, ref(GONE)).state)
        assertEquals(DraftReconciler.State.ARCHIVED, DraftReconciler.lookup(db, ref(ARCH)).state)
        assertEquals(DraftReconciler.State.MISSING, DraftReconciler.lookup(db, ref(UNKNOWN)).state)
    }

    /** Локальный uuid записан без дефисов, сервер присылает с дефисами — это та же строка. */
    @Test
    fun lookup_dashlessLocalUuid() {
        val local = DraftReconciler.lookup(db, ref("44444444-4444-4444-4444-444444444444"))
        assertEquals(DraftReconciler.State.OK, local.state)
        assertEquals(4L, local.id)
    }

    @Test
    fun found_live_isSelected() {
        val f = DraftReconciler.reconcile(db, found(LIVE))
        assertEquals(1L, f.selected!!.id)
        assertNull(f.problem)
    }

    @Test
    fun found_missingDeletedArchived_isProblem() {
        for (uuid in arrayOf(UNKNOWN, GONE, ARCH)) {
            val f = DraftReconciler.reconcile(db, found(uuid))
            assertNull(f.selected)
            assertEquals(uuid, f.problem!!.ref.uuid)
        }
    }

    @Test
    fun ask_dropsGoneCandidates_noProblemWhileOneLeft() {
        val ask = Choice(
            Choice.ASK, "ambiguous", null, null,
            listOf(ref(GONE), ref(LIVE), ref(UNKNOWN))
        )
        val f = DraftReconciler.reconcile(db, ask)
        assertEquals(1, f.candidates.size)
        assertEquals(LIVE, f.candidates[0].ref.uuid)
        assertNull(f.problem)
    }

    /** «Все кандидаты пропали» — ошибка по первому из них. */
    @Test
    fun ask_allCandidatesGone_problemIsFirst() {
        val ask = Choice(Choice.ASK, "ambiguous", null, null, listOf(ref(UNKNOWN), ref(GONE)))
        val f = DraftReconciler.reconcile(db, ask)
        assertEquals(0, f.candidates.size)
        assertEquals(UNKNOWN, f.problem!!.ref.uuid)
        assertEquals(DraftReconciler.State.MISSING, f.problem!!.state)
    }

    @Test
    fun ask_currencyMismatch_liveForceKept() {
        val ask = Choice(Choice.ASK, "currency_mismatch", null, ref(LIVE), listOf(ref(UNKNOWN)))
        val f = DraftReconciler.reconcile(db, ask)
        assertEquals(LIVE, f.force!!.ref.uuid)
        assertNull(f.problem)
    }

    @Test
    fun reject_noProblem() {
        val reject = Choice(Choice.REJECT, "no_wallets", null, null, emptyList())
        val f = DraftReconciler.reconcile(db, reject)
        assertNull(f.selected)
        assertNull(f.problem)
    }

    @Test
    fun stateById_beforeWrite() {
        assertEquals(DraftReconciler.State.OK, DraftReconciler.stateById(db, 1))
        db.execSQL("UPDATE account SET deleted_at=7 WHERE _id=1")
        assertEquals(DraftReconciler.State.DELETED, DraftReconciler.stateById(db, 1))
        assertEquals(DraftReconciler.State.MISSING, DraftReconciler.stateById(db, 99))
    }

    private fun ref(uuid: String): Ref = Ref(uuid, "имя-" + uuid.substring(0, 4), "RUB", "₽", 2)

    private fun found(uuid: String): Choice = Choice(Choice.FOUND, "none", ref(uuid), null, emptyList())
}
