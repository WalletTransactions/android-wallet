package com.example.wallettransactions.sync

import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.Data.WalletContract
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/**
 * Иконка-заместитель для своей иконки (openspec server-managed-icons, design п. 3).
 *
 * Свои иконки (`icon.origin = 1`) на сервер не уходят, а строки `account`/
 * `currency` на сервере обязаны ссылаться на существующую иконку
 * (`409 missing_reference`, wallet-api). Поэтому в push вместо своей иконки
 * подставляется иконка «с сервера» (`origin = 0`, живая), по порядку:
 *  1. та же картинка — SHA-256 байт совпадает (хеш картинки иконки с сервера равен её
 *     `content_sha256`, хранить его отдельно не нужно);
 *  2. то же имя без учёта регистра;
 *  3. иконка `unknown` (на телефоне общие и свои серверные иконки не различаются —
 *     это просто иконка с сервера с таким именем).
 *
 * Нет ни одного — `null`: строку откладывает вызывающий код.
 *
 * Индекс строится один раз — на раунд push или на страницу pull ([load]).
 * ~150 иконок по ~10 КиБ хешируются за миллисекунды.
 */
internal class IconSubstitutes {

    private val bySha256 = HashMap<String, String>()
    private val byName = HashMap<String, String>()

    /**
     * Иконка с сервера как кандидат в заместители. При совпадении ключа выигрывает
     * добавленная первой — [load] идёт по `_id`, выбор детерминирован.
     */
    fun addServerIcon(uuid: String?, name: String?, sha256: String?) {
        if (uuid == null) return
        if (sha256 != null) bySha256.putIfAbsent(sha256, uuid)
        if (name != null) byName.putIfAbsent(nameKey(name), uuid)
    }

    /** uuid заместителя для своей иконки с этим именем и хешем картинки, или `null`. */
    fun substituteFor(name: String?, sha256: String?): String? {
        if (sha256 != null) {
            val same = bySha256[sha256]
            if (same != null) return same
        }
        if (name != null) {
            val sameName = byName[nameKey(name)]
            if (sameName != null) return sameName
        }
        return byName[UNKNOWN_NAME]
    }

    /**
     * Есть ли среди иконок с сервера та же картинка — только по SHA-256, без запасных
     * вариантов по имени и `unknown`. Такую свою иконку не отправляют (openspec
     * optional-icon-upload, design п. 6): её роль уже играет заместитель.
     */
    fun hasServerImage(sha256: String?): Boolean = sha256 != null && bySha256.containsKey(sha256)

    fun substituteFor(name: String?, image: ByteArray?): String? =
        substituteFor(name, if (image == null) null else sha256Hex(image))

    companion object {
        const val UNKNOWN_NAME = "unknown"

        /**
         * Индекс по иконкам с сервера из локальной базы: живым и удалённым только на телефоне
         * (android-wallet#50). Иконки в push не уходят, поэтому `deleted_at` с
         * `dirty = 1` — локальное удаление (прежняя версия «Удалить данные» удаляла и
         * иконки с сервера), на сервере иконка жива и ссылка на неё проходит FK. Tombstone из
         * pull приходит с `dirty = 0` — такая иконка на сервере удалена и не берётся.
         */
        @JvmStatic
        fun load(db: SQLiteDatabase): IconSubstitutes {
            val subs = IconSubstitutes()
            val c = db.rawQuery(
                "SELECT " + WalletContract.IconEntry.COLUMN_UUID +
                    ", " + WalletContract.IconEntry.COLUMN_NAME +
                    ", " + WalletContract.IconEntry.COLUMN_IMAGE +
                    " FROM " + WalletContract.IconEntry.TABLE_NAME +
                    " WHERE " + WalletContract.IconEntry.COLUMN_ORIGIN + " = " + WalletContract.IconEntry.ORIGIN_SERVER +
                    " AND (" + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                    " OR " + WalletContract.IconEntry.COLUMN_DIRTY + " = 1)" +
                    " ORDER BY " + WalletContract.IconEntry._ID, null
            )
            try {
                while (c.moveToNext()) {
                    val image = c.getBlob(2)
                    subs.addServerIcon(c.getString(0), c.getString(1), if (image == null) null else sha256Hex(image))
                }
            } finally {
                c.close()
            }
            return subs
        }

        /** Один и тот же uuid в разных написаниях (с дефисами и без, см. `SyncEngine.localUuid`). */
        @JvmStatic
        fun sameUuid(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return a.lowercase(Locale.ROOT).replace("-", "") == b.lowercase(Locale.ROOT).replace("-", "")
        }

        /** Тот же SHA-256, что считает сервер для `content_sha256` (wallet-api, blob.cpp). */
        @JvmStatic
        fun sha256Hex(bytes: ByteArray): String {
            val md: MessageDigest = try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw AssertionError(e) // SHA-256 гарантирован на любой Android
            }
            val sb = StringBuilder(64)
            for (b in md.digest(bytes)) sb.append(String.format(Locale.ROOT, "%02x", b))
            return sb.toString()
        }

        private fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
