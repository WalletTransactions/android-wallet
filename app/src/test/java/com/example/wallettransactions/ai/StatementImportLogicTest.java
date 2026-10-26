package com.example.wallettransactions.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.ai.StatementRowState.Kind;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Экран «Выписка» без UI (openspec add-ai-statement-import): дубли по полям на
 * настоящей схеме SQLite ({@link StatementDuplicates}), состояния строк и отбор
 * «готовых» ({@link StatementRowState}), тексты из спеки ({@link AiTexts}).
 */
@RunWith(RobolectricTestRunner.class)
public class StatementImportLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long walletId;
    private long otherWalletId;
    private long taxiId;
    private long iconId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0},
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        long currencyId = insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, "Test Rub",
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TSR",
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T");
        walletId = wallet("Test Tinkoff", currencyId);
        otherWalletId = wallet("Test Cash", currencyId);
        taxiId = category("Test Такси", "c-taxi-0000-4000-8000-000000000001");
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── StatementDuplicates ────────────────────────────────────────────────────

    @Test
    public void duplicates_enteredByHand_nextDay() {
        long op = operation(walletId, taxiId, "2026-10-12 09:30", 450, "manual-1");
        List<StatementImport.Row> rows = Collections.singletonList(row("r1", "ready", "2026-10-13", "450.00", "c-taxi"));

        Map<String, StatementDuplicates.Match> m = StatementDuplicates.find(db, walletId, rows, Arrays.asList("r1"));

        assertEquals(op, m.get("r1").operationId);
        assertEquals("Похоже, уже есть: 12.10 450,00 Test Такси", AiTexts.statementDuplicate(m.get("r1")));
    }

    @Test
    public void duplicates_twoCoffeesOneEntered() {
        operation(walletId, taxiId, "2026-10-12 08:00", 200, "manual-2");
        List<StatementImport.Row> rows = Arrays.asList(
                row("c1", "ready", "2026-10-12", "200.00", "c-taxi"),
                row("c2", "ready", "2026-10-12", "200.00", "c-taxi"));

        Map<String, StatementDuplicates.Match> m = StatementDuplicates.find(db, walletId, rows, Arrays.asList("c1", "c2"));

        assertEquals(1, m.size());
        assertTrue(m.containsKey("c1"));
    }

    @Test
    public void duplicates_secondUploadMatchesFirstImportOps() {
        // Первая загрузка записана с uuid = row_id первого импорта.
        operation(walletId, taxiId, "2026-09-03 12:00", 450, "aaaa1111-0000-4000-8000-000000000001");
        operation(walletId, taxiId, "2026-09-04 12:00", 300, "aaaa1111-0000-4000-8000-000000000002");
        List<StatementImport.Row> rows = Arrays.asList(
                row("bbbb2222-0000-4000-8000-000000000001", "imported", "2026-09-03", "450.00", "c-taxi"),
                row("bbbb2222-0000-4000-8000-000000000002", "imported", "2026-09-04", "300.00", null));
        List<String> ids = Arrays.asList(rows.get(0).rowId, rows.get(1).rowId);

        assertEquals(2, StatementDuplicates.find(db, walletId, rows, ids).size());
    }

    @Test
    public void duplicates_ignoreOtherWalletDeletedFarAndOwnImport() {
        operation(otherWalletId, taxiId, "2026-10-12 12:00", 450, "x-other");
        long deleted = operation(walletId, taxiId, "2026-10-12 12:00", 450, "x-deleted");
        db.execSQL("UPDATE operation SET deleted_at = 1 WHERE _id = " + deleted);
        operation(walletId, taxiId, "2026-10-15 12:00", 450, "x-far");
        operation(walletId, taxiId, "2026-10-12 12:00", 451, "x-amount");
        // Операция, записанная из этого же импорта (другая его строка), — не дубль.
        operation(walletId, taxiId, "2026-10-12 12:00", 450, "R2-0000-4000-8000-000000000002");
        List<StatementImport.Row> rows = Collections.singletonList(row("r1", "ready", "2026-10-12", "450.00", "c-taxi"));

        Map<String, StatementDuplicates.Match> m = StatementDuplicates.find(db, walletId, rows,
                Arrays.asList("r1", "r2-0000-4000-8000-000000000002"));

        assertTrue(m.isEmpty());
    }

    @Test
    public void duplicates_nearestDateWins() {
        operation(walletId, taxiId, "2026-10-11 12:00", 450, "near-before");
        long same = operation(walletId, taxiId, "2026-10-12 12:00", 450, "same-day");
        List<StatementImport.Row> rows = Collections.singletonList(row("r1", "ready", "2026-10-12", "450.00", null));
        assertEquals(same, StatementDuplicates.find(db, walletId, rows, Arrays.asList("r1")).get("r1").operationId);
    }

    // ── StatementRowState ─────────────────────────────────────────────────────

    @Test
    public void states_inOrder() {
        StatementDuplicates.Match dup = new StatementDuplicates.Match(1, "2026-10-12", 450, "Такси");
        StatementImport.Row ready = row("r", "ready", "2026-10-12", "450.00", "c-taxi");
        assertEquals(Kind.ADDED, StatementRowState.of(ready, true, dup));
        assertEquals(Kind.NOT_FOR_WRITE, StatementRowState.of(
                withDir(row("r", "income_transfer", "2026-10-12", "1.00", null), "income"), false, null));
        assertEquals(Kind.NOT_FOR_WRITE, StatementRowState.of(row("r", "ignored", "2026-10-12", "1.00", null), false, null));
        assertEquals(Kind.CHECK, StatementRowState.of(withCheck(ready, "amount_not_in_text"), false, dup));
        assertEquals(Kind.DUPLICATE, StatementRowState.of(ready, false, dup));
        assertEquals(Kind.NEEDS_CATEGORY, StatementRowState.of(row("r", "needs_category", "2026-10-12", "1.00", null), false, null));
        assertEquals(Kind.WAITING, StatementRowState.of(ready, false, null));
    }

    /** spec: «Сервер считает дублем, телефон нет» — группа exists не мешает «Ждёт». */
    @Test
    public void states_serverDuplicateGroupsIgnored() {
        for (String g : Arrays.asList("exists", "imported", "possible_duplicate")) {
            assertEquals(g, Kind.WAITING, StatementRowState.of(row("r", g, "2026-10-12", "450.00", "c-taxi"), false, null));
        }
    }

    @Test
    public void readiness_waitingWithLiveCategory() {
        StatementImport.Row r = row("r", "ready", "2026-10-12", "450.00", "c-taxi-0000-4000-8000-000000000001");
        StatementRowState.Readiness ok = StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.OK, TODAY);
        assertTrue(ok.ready);
        assertEquals(taxiId, ok.categoryId);
        assertNull(ok.reason);

        assertFalse(StatementRowState.readiness(db, r, Kind.DUPLICATE, DraftReconciler.State.OK, TODAY).ready);
        assertFalse(StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.MISSING, TODAY).ready);
        StatementImport.Row future = row("r", "ready", "2026-10-27", "450.00", "c-taxi-0000-4000-8000-000000000001");
        assertFalse(StatementRowState.readiness(db, future, Kind.WAITING, DraftReconciler.State.OK, TODAY).ready);
        StatementImport.Row zero = row("r", "ready", "2026-10-12", "0.00", "c-taxi-0000-4000-8000-000000000001");
        assertFalse(StatementRowState.readiness(db, zero, Kind.WAITING, DraftReconciler.State.OK, TODAY).ready);
    }

    /** spec: «Категории нет на телефоне». */
    @Test
    public void readiness_deletedCategory() {
        db.execSQL("UPDATE account SET deleted_at = 1 WHERE _id = " + taxiId);
        StatementImport.Row r = row("r", "ready", "2026-10-12", "450.00", "c-taxi-0000-4000-8000-000000000001");
        StatementRowState.Readiness rd = StatementRowState.readiness(db, r, Kind.WAITING, DraftReconciler.State.OK, TODAY);
        assertFalse(rd.ready);
        assertEquals("Категории «Test Такси» нет на телефоне", rd.reason);
    }

    // ── AiTexts ──────────────────────────────────────────────────────────────

    @Test
    public void texts_fromSpec() {
        assertEquals("Файл больше 5 МБ — разбейте выписку по месяцам", AiTexts.STATEMENT_FILE_TOO_BIG);
        assertEquals("В PDF нет текста — это скан. Нужна выписка с текстовым слоем", AiTexts.statementFailure("no_text"));
        assertEquals("В файле не нашлось операций", AiTexts.statementFailure("no_operations"));
        assertEquals("Уже разбирается другая выписка", AiTexts.statementHttpError(429, "import_in_progress"));
        assertEquals("ИИ сейчас выключен", AiTexts.statementHttpError(503, "unavailable"));
        assertEquals("Нет связи с сервером — список недоступен", AiTexts.STATEMENT_NO_NETWORK);
        assertEquals("Разбираю выписку: 2 из 6", AiTexts.statementProgress(progress(null)));
        assertEquals("Разбор на паузе: ИИ недоступен", AiTexts.statementProgress(progress("2026-10-24T10:00:00Z")));
        assertEquals("Добавлена", AiTexts.statementRowState(Kind.ADDED, null));
        assertEquals("Не для записи", AiTexts.statementRowState(Kind.NOT_FOR_WRITE, null));
        assertEquals("Проверить", AiTexts.statementRowState(Kind.CHECK, null));
        assertEquals("Нужна категория", AiTexts.statementRowState(Kind.NEEDS_CATEGORY, null));
        assertEquals("Ждёт", AiTexts.statementRowState(Kind.WAITING, null));
        assertEquals("Похоже, уже есть: 12.10 450,00 Такси", AiTexts.statementRowState(Kind.DUPLICATE,
                new StatementDuplicates.Match(1, "2026-10-12", 450, "Такси")));
        assertEquals("1 290,00", AiTexts.statementAmount(1290));
    }

    private static StatementImport progress(String pausedSince) {
        return new StatementImport("i", StatementImport.EXTRACTING, null, "w", "s.pdf", 2, 6, pausedSince,
                new ArrayList<>());
    }

    private static StatementImport.Row row(String id, String group, String date, String amount, String category) {
        return new StatementImport.Row(id, group, new ArrayList<>(), date, amount, "expense", "DESC", category,
                null, null, false);
    }

    private static StatementImport.Row withDir(StatementImport.Row r, String dir) {
        return new StatementImport.Row(r.rowId, r.group, r.lines, r.date, r.amount, dir, r.description,
                r.categoryUuid, r.suggestion, r.check, r.committed);
    }

    private static StatementImport.Row withCheck(StatementImport.Row r, String check) {
        return new StatementImport.Row(r.rowId, r.group, r.lines, r.date, r.amount, r.dir, r.description,
                r.categoryUuid, r.suggestion, check, r.committed);
    }

    private long wallet(String name, long currencyId) {
        long id = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, name,
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                WalletContract.AccountEntry.COLUMN_KIND, 1);
        insert(WalletContract.AccountOperatingEntry.TABLE_NAME,
                WalletContract.AccountOperatingEntry._ID, id,
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId);
        return id;
    }

    private long category(String name, String uuid) {
        return insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, name,
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                "uuid", uuid);
    }

    private long operation(long from, long to, String date, double amount, String uuid) {
        return insert(WalletContract.OperationEntry.TABLE_NAME,
                WalletContract.OperationEntry.COLUMN_DATE, date,
                WalletContract.OperationEntry.COLUMN_FROM, from,
                WalletContract.OperationEntry.COLUMN_TO, to,
                WalletContract.OperationEntry.COLUMN_AMOUNT, amount,
                WalletContract.OperationEntry.COLUMN_COMMENT, "",
                WalletContract.OperationEntry.COLUMN_UUID, uuid);
    }

    private long insert(String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            if (value instanceof Integer) cv.put(key, (Integer) value);
            else if (value instanceof Long) cv.put(key, (Long) value);
            else if (value instanceof Double) cv.put(key, (Double) value);
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
