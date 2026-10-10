package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.example.wallettransactions.DataItems.Operations.RecurringOperation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение/запись правил повторяющихся операций ({@link WalletContract.RecurringOperationEntry})
 * и генератор, который по ним заводит обычные операции.
 */
public final class RecurringOperationsDb {

    private static final String TAG = "RecurringOperations";

    /** Тот же формат, что у {@code operation.date} (см. {@code DateUtils.formatDB}). */
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private RecurringOperationsDb() { }

    // ── Чтение ────────────────────────────────────────────────────────────────

    public static List<RecurringOperation> loadAll(SQLiteDatabase db) {
        return query(db, null, null);
    }

    public static RecurringOperation load(SQLiteDatabase db, int id) {
        List<RecurringOperation> list = query(db, WalletContract.RecurringOperationEntry._ID + " = ?",
                new String[]{String.valueOf(id)});
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<RecurringOperation> query(SQLiteDatabase db, String where, String[] args) {
        List<RecurringOperation> result = new ArrayList<>();
        Cursor c = db.query(WalletContract.RecurringOperationEntry.TABLE_NAME, null, where, args,
                null, null, WalletContract.RecurringOperationEntry._ID);
        try {
            while (c.moveToNext()) result.add(fromCursor(c));
        } finally {
            c.close();
        }
        return result;
    }

    private static RecurringOperation fromCursor(Cursor c) {
        RecurringOperation r = new RecurringOperation();
        r.id = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry._ID));
        r.accFromId = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_FROM));
        r.accToId = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_TO));
        r.amount = c.getDouble(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_AMOUNT));
        r.comment = c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_COMMENT));
        r.frequency = RecurringOperation.Frequency.fromValue(
                c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY)));
        r.interval = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_INTERVAL));
        r.weekDays = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_WEEK_DAYS));
        r.startDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_START_DATE)));
        r.endDate = parseDate(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_END_DATE)));
        r.isActive = c.getInt(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_IS_ACTIVE)) != 0;
        r.lastDate = parseDateTime(c.getString(c.getColumnIndexOrThrow(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE)));
        return r;
    }

    // ── Запись ────────────────────────────────────────────────────────────────

    /** @return id новой строки или -1. */
    public static long insert(SQLiteDatabase db, RecurringOperation r) {
        long id = db.insert(WalletContract.RecurringOperationEntry.TABLE_NAME, null, toValues(r));
        if (id > 0) r.id = (int) id;
        return id;
    }

    public static int update(SQLiteDatabase db, RecurringOperation r) {
        return db.update(WalletContract.RecurringOperationEntry.TABLE_NAME, toValues(r),
                WalletContract.RecurringOperationEntry._ID + " = ?", new String[]{String.valueOf(r.id)});
    }

    /** Уже созданные по правилу операции остаются — удаляется только само правило. */
    public static int delete(SQLiteDatabase db, int id) {
        return db.delete(WalletContract.RecurringOperationEntry.TABLE_NAME,
                WalletContract.RecurringOperationEntry._ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Включение/выключение повтора. Выключенное правило — пауза: при повторном включении
     * пропущенные за время паузы повторы не досоздаются, счёт идёт с текущего момента.
     */
    public static void setActive(SQLiteDatabase db, RecurringOperation r, boolean active) {
        if (active && !r.isActive) {
            LocalDateTime now = LocalDateTime.now();
            if (r.lastDate == null || r.lastDate.isBefore(now)) {
                // Правило ещё не начиналось — ничего не пропущено, досоздавать нечего.
                if (r.startDate != null && r.startDate.isBefore(now)) r.lastDate = now;
            }
        }
        r.isActive = active;
        update(db, r);
    }

    private static ContentValues toValues(RecurringOperation r) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FROM, r.accFromId);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_TO, r.accToId);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_AMOUNT, r.amount);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_COMMENT, r.comment != null ? r.comment : "");
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY, r.frequency.getValue());
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_INTERVAL, Math.max(1, r.interval));
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_WEEK_DAYS, r.weekDays);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_START_DATE, r.startDate.format(DATE_TIME));
        if (r.endDate != null) cv.put(WalletContract.RecurringOperationEntry.COLUMN_END_DATE, r.endDate.format(DATE));
        else cv.putNull(WalletContract.RecurringOperationEntry.COLUMN_END_DATE);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_IS_ACTIVE, r.isActive ? 1 : 0);
        if (r.lastDate != null) cv.put(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE, r.lastDate.format(DATE_TIME));
        else cv.putNull(WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE);
        return cv;
    }

    // ── Генератор ─────────────────────────────────────────────────────────────

    /**
     * Заводит операции по всем активным правилам — за каждый наступивший, но ещё не
     * созданный повтор (после {@code last_date}, не позже {@code now}). Пропущенные, пока
     * приложение не открывали, досоздаются все разом, каждая своей датой.
     * <p>
     * Правило, у которого хоть один счёт мягко удалён, пропускается: операция на
     * удалённый счёт в ленте не нужна, а {@code last_date} не сдвигается.
     *
     * @return сколько операций создано
     */
    public static int generateDue(SQLiteDatabase db, LocalDateTime now) {
        return generateDue(db, now, null);
    }

    /** @param createdIds если не {@code null} — сюда складываются _id созданных операций (для уведомления). */
    public static int generateDue(SQLiteDatabase db, LocalDateTime now, List<Long> createdIds) {
        int created = 0;
        List<Long> ids = new ArrayList<>();
        db.beginTransaction();
        try {
            for (RecurringOperation r : loadAll(db)) {
                if (!r.isRunning(now)) continue;
                if (!isLiveAccount(db, r.accFromId) || !isLiveAccount(db, r.accToId)) continue;

                List<LocalDateTime> due = r.occurrencesBetween(r.lastDate, now);
                // «Дата по» включительно — повтор позже неё не заводим ни при каком расписании.
                if (r.endDate != null) due.removeIf(at -> at.toLocalDate().isAfter(r.endDate));
                if (due.isEmpty()) continue;

                for (LocalDateTime at : due) {
                    long id = WalletDbCRUD.InsertOperation(db, at.format(DATE_TIME),
                            r.accFromId, r.accToId, r.amount, r.comment != null ? r.comment : "");
                    if (id <= 0) throw new IllegalStateException("insert operation failed, rule " + r.id);
                    ids.add(id);
                    created++;
                }
                r.lastDate = due.get(due.size() - 1);
                update(db, r);
            }
            db.setTransactionSuccessful();
            // Только после успеха: при откате этих _id в базе нет.
            if (createdIds != null) createdIds.addAll(ids);
        } catch (RuntimeException e) {
            Log.e(TAG, "generateDue failed", e);
            created = 0;
        } finally {
            db.endTransaction();
        }
        if (created > 0) Log.i(TAG, "generateDue: создано операций: " + created);
        return created;
    }

    private static boolean isLiveAccount(SQLiteDatabase db, int accountId) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountEntry.TABLE_NAME
                        + " WHERE " + WalletContract.AccountEntry._ID + " = ? AND "
                        + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(accountId)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    // ── Даты ──────────────────────────────────────────────────────────────────

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.length() == 10) return LocalDate.parse(s, DATE).atStartOfDay();
            return LocalDateTime.parse(s, DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
