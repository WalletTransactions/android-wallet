package com.example.wallettransactions.Data

import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.R
import com.example.wallettransactions.Texts

/**
 * Свободное имя для своей иконки, уступающей имя иконке с сервера (icon-sync, «Конфликт
 * имени при загрузке»): «<имя> (своя)», занято — «<имя> (своя 2)», «(своя 3)», … среди
 * живых иконок. Общий код pull (`SyncEngine.applyIcon`) и починки v90
 * ([WalletDbHelper.reviveServerIconsV90]).
 */
object OwnIconNames {
    @JvmStatic
    fun freeOwnName(db: SQLiteDatabase, name: String?): String {
        var candidate = Texts.get(R.string.icon_own_suffix, name)
        var n = 2
        while (nameTaken(db, candidate)) {
            candidate = Texts.get(R.string.icon_own_suffix_n, name, n)
            n++
        }
        return candidate
    }

    private fun nameTaken(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM icon WHERE name=? AND deleted_at IS NULL LIMIT 1",
            arrayOf(name)
        ).use { c ->
            return c.moveToFirst()
        }
    }
}
