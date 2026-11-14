package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.HashSet;
import java.util.Set;

/**
 * Авто-архив погашенного займа (openspec wallets-filter-and-debt-autoarchive, спека debts
 * «Авто-архив погашенного займа», design D2, D3).
 *
 * <p>Использование — вокруг записи сценария целиком, а не внутри {@code InsertOperation}:
 * <pre>
 *   DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
 *   ... записать операции сценария ...
 *   before.finish(db);
 * </pre>
 * Архивируется кошелёк, у которого остаток (с точностью валюты) был ненулевым в {@code begin},
 * а в {@code finish} стал нулевым. Промежуточный ноль внутри сценария не в счёт; ноль с самого
 * начала и вручную разархивированный заём не архивируются (в снимок они не попадают: ноль —
 * не «ненулевой», архивные — не «открытые»). Обратно ничего не разархивируется. Флаг ставится
 * обычным {@code UPDATE account}: триггер синка поднимает dirty, и правка уходит на сервер как
 * любая другая.
 *
 * <p>Не вызывается из {@code WalletImportData} (восстановление из файла несёт свои флаги) и из
 * {@code SyncEngine} (флаг приходит с сервера).
 */
public final class DebtAutoArchive {
    private DebtAutoArchive() {}

    /** Живые неархивные долговые кошельки, у которых остаток был ненулевым на момент {@link #begin}. */
    public static final class Snapshot {
        private final Set<Integer> openLoans;

        Snapshot(Set<Integer> openLoans) {
            this.openLoans = openLoans;
        }

        /** Нет открытых займов — {@link #finish} ничего не делает. */
        public boolean isEmpty() {
            return openLoans.isEmpty();
        }

        /**
         * Архивирует займы из снимка, чей остаток теперь нулевой.
         *
         * @return сколько кошельков заархивировано
         */
        public int finish(SQLiteDatabase db) {
            java.util.List<Integer> archivedIds = new java.util.ArrayList<>();
            for (int id : openLoans) {
                if (isSettled(db, id)) {
                    ContentValues cv = new ContentValues();
                    cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 1);
                    int n = db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                            WalletContract.AccountEntry._ID + "=? AND "
                                    + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " = 0",
                            new String[]{String.valueOf(id)});
                    if (n > 0) archivedIds.add(id);
                }
            }
            // Флаг записан мимо AccountList — кэш счетов в памяти подправляем сами, иначе экраны
            // «Долги» и «Кошельки» до перезапуска показывали бы погашенный заём как живой.
            // (Если транзакция вызывающего откатится — кэш и БД разойдутся до перечитывания;
            // вызывающие зовут finish последним шагом перед setTransactionSuccessful.)
            if (!archivedIds.isEmpty()) com.example.wallettransactions.Singleton.instance.markAccountsArchived(archivedIds);
            return archivedIds.size();
        }
    }

    /** Снимок «до»: какие займы сейчас с остатком. */
    public static Snapshot begin(SQLiteDatabase db) {
        Set<Integer> open = new HashSet<>();
        Cursor c = db.rawQuery(LIVE_LOANS_SQL, null);
        try {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (RoundedBalance(db, id, c.getInt(1)) != 0) open.add(id);
            }
        } finally {
            c.close();
        }
        return new Snapshot(open);
    }

    /** _id и precision валюты каждого живого неархивного долгового кошелька. */
    private static final String LIVE_LOANS_SQL = "SELECT a._id, c." + WalletContract.CurrencyEntry.COLUMN_PRECISION
            + " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a"
            + " JOIN " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad ON ad._id = a._id AND ad.deleted_at IS NULL"
            + " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao._id = a._id AND ao.deleted_at IS NULL"
            + " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c ON c._id = ao."
            + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID
            + " WHERE a.deleted_at IS NULL AND a." + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " = 0";

    private static boolean isSettled(SQLiteDatabase db, int walletId) {
        Cursor c = db.rawQuery("SELECT c." + WalletContract.CurrencyEntry.COLUMN_PRECISION
                + " FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao"
                + " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c ON c._id = ao."
                + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID
                + " WHERE ao._id = ?", new String[]{String.valueOf(walletId)});
        try {
            return c.moveToFirst() && RoundedBalance(db, walletId, c.getInt(0)) == 0;
        } finally {
            c.close();
        }
    }

    private static double RoundedBalance(SQLiteDatabase db, int walletId, int precision) {
        return WalletDbCRUD.RoundedWalletBalance(db, walletId, precision);
    }
}
