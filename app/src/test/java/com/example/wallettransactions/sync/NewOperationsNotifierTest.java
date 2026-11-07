package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link NewOperationsNotifier}: какие из пришедших в pull операций попадают в
 * уведомление и как склоняется заголовок.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "ru") // русские формы plurals (few/many) выбираются только в русской локали
public class NewOperationsNotifierTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE operation (_id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, "
                + "date TEXT, acc_fr_id INTEGER, acc_to_id INTEGER, amount TEXT, comment TEXT, deleted_at INTEGER)");
        db.execSQL("CREATE TABLE operation_link (main_id INTEGER, link_id INTEGER, type INTEGER, deleted_at INTEGER)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    /** Валютный перевод с комиссией — три проводки, но в уведомлении одна строка. */
    @Test
    public void linkedLegsAndDeleted_areSkipped() {
        db.execSQL("INSERT INTO operation (_id, uuid, date, acc_fr_id, acc_to_id, amount, comment) VALUES "
                + "(1, 'main', '2026-10-10 10:00', 1, 2, '100', 'перевод'),"
                + "(2, 'leg', '2026-10-10 10:00', 2, 3, '90', ''),"
                + "(3, 'fee', '2026-10-10 10:00', 1, 4, '1', ''),"
                + "(4, 'plain', '2026-10-10 09:00', 1, 5, '350', 'обед')");
        db.execSQL("INSERT INTO operation (_id, uuid, date, acc_fr_id, acc_to_id, amount, comment, deleted_at) "
                + "VALUES (5, 'gone', '2026-10-10 11:00', 1, 5, '10', '', 1)");
        db.execSQL("INSERT INTO operation_link (main_id, link_id, type) VALUES (1, 2, 0), (1, 3, 1)");

        List<String> comments = new ArrayList<>();
        Cursor c = NewOperationsNotifier.queryMainOperations(db,
                Arrays.asList("main", "leg", "fee", "plain", "gone"), Collections.emptyList());
        try {
            while (c.moveToNext()) comments.add(c.getString(3));
        } finally {
            c.close();
        }

        // По дате: сначала «обед» (09:00), потом перевод (10:00).
        assertEquals(Arrays.asList("обед", "перевод"), comments);
    }

    /** Удалённая связка ногу больше не прячет — это уже самостоятельная проводка. */
    @Test
    public void deletedLink_doesNotHideOperation() {
        db.execSQL("INSERT INTO operation (_id, uuid, date, acc_fr_id, acc_to_id, amount) VALUES "
                + "(1, 'a', '2026-10-10', 1, 2, '1'), (2, 'b', '2026-10-10', 2, 3, '1')");
        db.execSQL("INSERT INTO operation_link (main_id, link_id, type, deleted_at) VALUES (1, 2, 0, 1)");

        Cursor c = NewOperationsNotifier.queryMainOperations(db, Arrays.asList("a", "b"), Collections.emptyList());
        try {
            assertEquals(2, c.getCount());
        } finally {
            c.close();
        }
    }

    /** Периодические приходят по _id, пришедшие с сервера — по uuid; списки могут быть пустыми. */
    @Test
    public void recurringIds_andEmptyLists() {
        db.execSQL("INSERT INTO operation (_id, uuid, date, acc_fr_id, acc_to_id, amount) VALUES "
                + "(1, 'a', '2026-10-10', 1, 2, '1'), (2, 'b', '2026-10-10', 1, 2, '1'), (3, 'c', '2026-10-10', 1, 2, '1')");

        assertEquals(Arrays.asList(1L, 3L), ids(NewOperationsNotifier.queryMainOperations(db,
                Collections.singletonList("a"), Collections.singletonList(3L))));
        assertEquals(Collections.singletonList(2L), ids(NewOperationsNotifier.queryMainOperations(db,
                Collections.emptyList(), Collections.singletonList(2L))));
        assertEquals(Collections.emptyList(), ids(NewOperationsNotifier.queryMainOperations(db,
                Collections.emptyList(), Collections.emptyList())));
    }

    private static List<Long> ids(Cursor c) {
        List<Long> ids = new ArrayList<>();
        try {
            while (c.moveToNext()) ids.add(c.getLong(4));
        } finally {
            c.close();
        }
        return ids;
    }

    @Test
    public void plural() {
        assertEquals("новая операция", NewOperationsNotifier.plural(1));
        assertEquals("новая операция", NewOperationsNotifier.plural(21));
        assertEquals("новые операции", NewOperationsNotifier.plural(3));
        assertEquals("новые операции", NewOperationsNotifier.plural(22));
        assertEquals("новых операций", NewOperationsNotifier.plural(5));
        assertEquals("новых операций", NewOperationsNotifier.plural(11));
        assertEquals("новых операций", NewOperationsNotifier.plural(12));
        assertEquals("новых операций", NewOperationsNotifier.plural(114));
    }
}
