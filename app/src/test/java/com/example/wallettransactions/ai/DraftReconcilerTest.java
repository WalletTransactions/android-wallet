package com.example.wallettransactions.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.ai.ParseExpenseResult.Choice;
import com.example.wallettransactions.ai.ParseExpenseResult.Ref;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

/**
 * {@link DraftReconciler}: состояния «есть / нет / удалён / в архиве» и что из
 * этого видит карточка (spec ai-expense-entry, «Кошелёк или категория пропали
 * с телефона»).
 */
@RunWith(RobolectricTestRunner.class)
public class DraftReconcilerTest {

    private static final String LIVE = "11111111-1111-1111-1111-111111111111";
    private static final String GONE = "22222222-2222-2222-2222-222222222222";
    private static final String ARCH = "33333333-3333-3333-3333-333333333333";
    private static final String DASHLESS = "44444444444444444444444444444444";
    private static final String UNKNOWN = "55555555-5555-5555-5555-555555555555";

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, "
                + "is_archived INTEGER DEFAULT 0, deleted_at INTEGER)");
        db.execSQL("INSERT INTO account (_id, uuid, name) VALUES (1, '" + LIVE + "', 'Тинькофф')");
        db.execSQL("INSERT INTO account (_id, uuid, name, deleted_at) VALUES (2, '" + GONE + "', 'Наличные', 5)");
        db.execSQL("INSERT INTO account (_id, uuid, name, is_archived) VALUES (3, '" + ARCH + "', 'Спорт', 1)");
        db.execSQL("INSERT INTO account (_id, uuid, name) VALUES (4, '" + DASHLESS + "', 'Кафе')");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void lookup_fourStates() {
        assertEquals(DraftReconciler.State.OK, DraftReconciler.lookup(db, ref(LIVE)).state);
        assertEquals(1, DraftReconciler.lookup(db, ref(LIVE)).id);
        assertEquals(DraftReconciler.State.DELETED, DraftReconciler.lookup(db, ref(GONE)).state);
        assertEquals(DraftReconciler.State.ARCHIVED, DraftReconciler.lookup(db, ref(ARCH)).state);
        assertEquals(DraftReconciler.State.MISSING, DraftReconciler.lookup(db, ref(UNKNOWN)).state);
    }

    /** Локальный uuid записан без дефисов, сервер присылает с дефисами — это та же строка. */
    @Test
    public void lookup_dashlessLocalUuid() {
        DraftReconciler.Local local = DraftReconciler.lookup(db, ref("44444444-4444-4444-4444-444444444444"));
        assertEquals(DraftReconciler.State.OK, local.state);
        assertEquals(4, local.id);
    }

    @Test
    public void found_live_isSelected() {
        DraftReconciler.Field f = DraftReconciler.reconcile(db, found(LIVE));
        assertEquals(1, f.selected.id);
        assertNull(f.problem);
    }

    @Test
    public void found_missingDeletedArchived_isProblem() {
        for (String uuid : new String[]{UNKNOWN, GONE, ARCH}) {
            DraftReconciler.Field f = DraftReconciler.reconcile(db, found(uuid));
            assertNull(f.selected);
            assertEquals(uuid, f.problem.ref.uuid);
        }
    }

    @Test
    public void ask_dropsGoneCandidates_noProblemWhileOneLeft() {
        Choice ask = new Choice(Choice.ASK, "ambiguous", null, null,
                Arrays.asList(ref(GONE), ref(LIVE), ref(UNKNOWN)));
        DraftReconciler.Field f = DraftReconciler.reconcile(db, ask);
        assertEquals(1, f.candidates.size());
        assertEquals(LIVE, f.candidates.get(0).ref.uuid);
        assertNull(f.problem);
    }

    /** «Все кандидаты пропали» — ошибка по первому из них. */
    @Test
    public void ask_allCandidatesGone_problemIsFirst() {
        Choice ask = new Choice(Choice.ASK, "ambiguous", null, null, Arrays.asList(ref(UNKNOWN), ref(GONE)));
        DraftReconciler.Field f = DraftReconciler.reconcile(db, ask);
        assertEquals(0, f.candidates.size());
        assertEquals(UNKNOWN, f.problem.ref.uuid);
        assertEquals(DraftReconciler.State.MISSING, f.problem.state);
    }

    @Test
    public void ask_currencyMismatch_liveForceKept() {
        Choice ask = new Choice(Choice.ASK, "currency_mismatch", null, ref(LIVE), Collections.singletonList(ref(UNKNOWN)));
        DraftReconciler.Field f = DraftReconciler.reconcile(db, ask);
        assertEquals(LIVE, f.force.ref.uuid);
        assertNull(f.problem);
    }

    @Test
    public void reject_noProblem() {
        Choice reject = new Choice(Choice.REJECT, "no_wallets", null, null, Collections.emptyList());
        DraftReconciler.Field f = DraftReconciler.reconcile(db, reject);
        assertNull(f.selected);
        assertNull(f.problem);
    }

    @Test
    public void stateById_beforeWrite() {
        assertEquals(DraftReconciler.State.OK, DraftReconciler.stateById(db, 1));
        db.execSQL("UPDATE account SET deleted_at=7 WHERE _id=1");
        assertEquals(DraftReconciler.State.DELETED, DraftReconciler.stateById(db, 1));
        assertEquals(DraftReconciler.State.MISSING, DraftReconciler.stateById(db, 99));
    }

    private static Ref ref(String uuid) {
        return new Ref(uuid, "имя-" + uuid.substring(0, 4), "RUB", "₽", 2);
    }

    private static Choice found(String uuid) {
        return new Choice(Choice.FOUND, "none", ref(uuid), null, Collections.emptyList());
    }
}
