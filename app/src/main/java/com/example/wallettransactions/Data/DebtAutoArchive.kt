package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.Singleton

/**
 * Авто-архив погашенного займа (openspec wallets-filter-and-debt-autoarchive, спека debts
 * «Авто-архив погашенного займа», design D2, D3).
 *
 * Использование — вокруг записи сценария целиком, а не внутри `InsertOperation`:
 * ```
 *   DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
 *   ... записать операции сценария ...
 *   before.finish(db);
 * ```
 * Архивируется кошелёк, у которого остаток (с точностью валюты) был ненулевым в `begin`,
 * а в `finish` стал нулевым. Промежуточный ноль внутри сценария не в счёт; ноль с самого
 * начала и вручную разархивированный заём не архивируются (в снимок они не попадают: ноль —
 * не «ненулевой», архивные — не «открытые»). Обратно ничего не разархивируется. Флаг ставится
 * обычным `UPDATE account`: триггер синка поднимает dirty, и правка уходит на сервер как
 * любая другая.
 *
 * Не вызывается из `WalletImportData` (восстановление из файла несёт свои флаги) и из
 * `SyncEngine` (флаг приходит с сервера).
 */
object DebtAutoArchive {

    /** Живые неархивные долговые кошельки, у которых остаток был ненулевым на момент [begin]. */
    class Snapshot internal constructor(private val openLoans: Set<Int>) {

        /** Нет открытых займов — [finish] ничего не делает. */
        fun isEmpty(): Boolean = openLoans.isEmpty()

        /**
         * Архивирует займы из снимка, чей остаток теперь нулевой.
         *
         * @return сколько кошельков заархивировано
         */
        fun finish(db: SQLiteDatabase): Int {
            val archivedIds = ArrayList<Int>()
            for (id in openLoans) {
                if (isSettled(db, id)) {
                    val cv = ContentValues()
                    cv.put(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED, 1)
                    val n = db.update(
                        WalletContract.AccountEntry.TABLE_NAME, cv,
                        WalletContract.AccountEntry._ID + "=? AND "
                                + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " = 0",
                        arrayOf(id.toString())
                    )
                    if (n > 0) archivedIds.add(id)
                }
            }
            // Флаг записан мимо AccountList — кэш счетов в памяти подправляем сами, иначе экраны
            // «Долги» и «Кошельки» до перезапуска показывали бы погашенный заём как живой.
            // (Если транзакция вызывающего откатится — кэш и БД разойдутся до перечитывания;
            // вызывающие зовут finish последним шагом перед setTransactionSuccessful.)
            if (archivedIds.isNotEmpty()) Singleton.instance.markAccountsArchived(archivedIds)
            return archivedIds.size
        }
    }

    /** Снимок «до»: какие займы сейчас с остатком. */
    @JvmStatic
    fun begin(db: SQLiteDatabase): Snapshot {
        val open = HashSet<Int>()
        db.rawQuery(LIVE_LOANS_SQL, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                if (roundedBalance(db, id, c.getInt(1)) != 0.0) open.add(id)
            }
        }
        return Snapshot(open)
    }

    /** _id и precision валюты каждого живого неархивного долгового кошелька. */
    private const val LIVE_LOANS_SQL = "SELECT a._id, c." + WalletContract.CurrencyEntry.COLUMN_PRECISION +
            " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a" +
            " JOIN " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad ON ad._id = a._id AND ad.deleted_at IS NULL" +
            " JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao ON ao._id = a._id AND ao.deleted_at IS NULL" +
            " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c ON c._id = ao." +
            WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID +
            " WHERE a.deleted_at IS NULL AND a." + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " = 0"

    private fun isSettled(db: SQLiteDatabase, walletId: Int): Boolean {
        db.rawQuery(
            "SELECT c." + WalletContract.CurrencyEntry.COLUMN_PRECISION +
                    " FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao" +
                    " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c ON c._id = ao." +
                    WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID +
                    " WHERE ao._id = ?", arrayOf(walletId.toString())
        ).use { c ->
            return c.moveToFirst() && roundedBalance(db, walletId, c.getInt(0)) == 0.0
        }
    }

    private fun roundedBalance(db: SQLiteDatabase, walletId: Int, precision: Int): Double {
        return WalletDbCRUD.RoundedWalletBalance(db, walletId, precision)
    }
}
