package com.example.wallettransactions.Data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Operations.OperationItem

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


/**
 * Экспорт БД в тот же JSON, что понимает [WalletImportData.FillOperationDataFromJson]
 * ("counterparties"/"accounts"/"currency_pairs"/"rates"/"operations" — см. javadoc-схему там). Симметрично
 * тулу tools/moneylover, который пишет trans.json в этом же формате.
 *
 * Операции реконструируются из operation + operation_link обратно в форму "leg"/"commission"
 * (см. [WalletImportData.ParseAndInsertJsonOperation] — main_id всегда проводка списания
 * с источника, type=0 — вторая нога перевода, type=1 — комиссия), а не выгружаются построчно,
 * как в CSV — иначе валютный перевод/комиссия после реимпорта развалились бы обратно на
 * несвязанные проводки.
 */
object WalletExportData {

    /** Единственный источник правды для формы полей — см. её же в [WalletImportData]. */
    @Throws(JSONException::class)
    private fun buildSchemaComment(): JSONObject {
        val c = JSONObject()
        c.put("_", "Разделы опциональны, но присутствуют всегда (даже пустыми) — ниже форма их элементов.") // not UI: описание формата внутри файла
        c.put("counterparties", "{\"name\": str, \"comment\"?: str, \"icon\"?: str (=\"unknown\"), " +
                "\"archived\"?: bool (=false)} — контрагенты долгов; применяются раньше счетов.") // not UI
        c.put("accounts", "{\"name\": str, \"comment\"?: str (=name), \"icon\"?: str (=\"unknown\"), " +
                "\"currency\"?: {\"name\"?: str (=iso), \"iso\": str, \"icon\"?: str}, " +
                "\"debt\"?: {\"counterparty\": str (имя), \"comment\"?: str, \"due_date\"?: \"YYYY-MM-DD\"}, " + // not UI
                "\"asset\"?: {\"type\": \"real_estate\"|\"vehicle\"|\"other\", \"include_in_net_worth\"?: bool (=true), " +
                "\"comment\"?: str}, \"system\"?: \"revaluation\", " +
                "\"category_kind\"?: 0|1|2 (0 расход, 1 доход, 2 оба; =2), только у обычной категории, " + // not UI
                "\"archived\"?: bool (нет ключа — архивность не трогаем), " + // not UI
                "\"icon_image\"?: str (base64 PNG/JPEG/WEBP — своя картинка, любой размер → 256×256; " + // not UI
                "имя иконки — \"icon\", без него имя счёта)} " + // not UI
                "— без \"currency\" это счёт-категория (без своего баланса); \"debt\" и \"asset\" — " + // not UI
                "только у кошелька и взаимоисключающие; \"system\" — у системной категории переоценки.") // not UI
        c.put("currency_pairs", "{\"symbol\": str, \"base\": str (ISO валюты), \"quote\": str (ISO валюты)}") // not UI
        c.put("rates", "{\"symbol\": str (как в currency_pairs), \"date\": \"YYYY-MM-DD\", \"rate\": number}") // not UI
        c.put("operations", "{\"date\": \"YYYY-MM-DD\", \"from\": str (имя счёта), \"to\": str (имя счёта), " + // not UI
                "\"amount\": number, \"comment\"?: str, " +
                "\"leg\"?: {\"amount\": number} — вторая нога разновалютного перевода, " + // not UI
                "\"commission\"?: {\"amount\": number, \"from\"?: \"source\"|\"dest\" (=\"source\"), " +
                "\"category\"?: str (=\"Transfer commission\")}}")
        return c
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun BuildExportJson(db: SQLiteDatabase): JSONObject {
        val root = JSONObject()
        root.put("_comment", buildSchemaComment())
        root.put("counterparties", buildCounterparties(db))
        root.put("accounts", buildAccounts(db))
        root.put("currency_pairs", buildCurrencyPairs(db))
        root.put("rates", buildRates(db))
        root.put("operations", buildOperations(db))
        return root
    }

    @Throws(JSONException::class)
    private fun buildAccounts(db: SQLiteDatabase): JSONArray {
        val arr = JSONArray()
        val c = db.rawQuery(
                "SELECT a." + WalletContract.AccountEntry.COLUMN_NAME + " name," +
                        " a." + WalletContract.AccountEntry.COLUMN_COMMENT + " comment," +
                        " ic." + WalletContract.IconEntry.COLUMN_NAME + " icon_name," +
                        " c." + WalletContract.CurrencyEntry.COLUMN_NAME + " cur_name," +
                        " c." + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " cur_iso," +
                        " cic." + WalletContract.IconEntry.COLUMN_NAME + " cur_icon_name," +
                        " a." + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " system_kind," +
                        " cp." + WalletContract.CounterpartyEntry.COLUMN_NAME + " debt_cp," +
                        " ad." + WalletContract.AccountDebtEntry.COLUMN_COMMENT + " debt_comment," +
                        " ad." + WalletContract.AccountDebtEntry.COLUMN_DUE_DATE + " debt_due," +
                        " aa." + WalletContract.AccountAssetEntry.COLUMN_ASSET_TYPE + " asset_type," +
                        " aa." + WalletContract.AccountAssetEntry.COLUMN_INCLUDE_IN_NET_WORTH + " asset_nw," +
                        " aa." + WalletContract.AccountAssetEntry.COLUMN_COMMENT + " asset_comment," +
                        " ic." + WalletContract.IconEntry.COLUMN_IMAGE + " icon_image," +
                        " ic." + WalletContract.IconEntry.COLUMN_ORIGIN + " icon_origin," +
                        " a." + WalletContract.AccountEntry.COLUMN_KIND + " account_kind," +
                        " a." + WalletContract.AccountEntry.COLUMN_CATEGORY_KIND + " category_kind," +
                        " a." + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " is_archived" +
                        " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a" +
                        " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                        "   ON ic." + WalletContract.IconEntry._ID + " = a." + WalletContract.AccountEntry.COLUMN_ICON_ID +
                        " LEFT JOIN " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao" +
                        "   ON ao." + WalletContract.AccountOperatingEntry._ID + " = a." + WalletContract.AccountEntry._ID +
                        "  AND ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " LEFT JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " c" +
                        "   ON c." + WalletContract.CurrencyEntry._ID + " = ao." + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID +
                        " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " cic" +
                        "   ON cic." + WalletContract.IconEntry._ID + " = c." + WalletContract.CurrencyEntry.COLUMN_ICON_ID +
                        " LEFT JOIN " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad" +
                        "   ON ad." + WalletContract.AccountDebtEntry._ID + " = a." + WalletContract.AccountEntry._ID +
                        "  AND ad." + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " LEFT JOIN " + WalletContract.CounterpartyEntry.TABLE_NAME + " cp" +
                        "   ON cp." + WalletContract.CounterpartyEntry.COLUMN_UUID + " = ad." + WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID +
                        "  AND cp." + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " LEFT JOIN " + WalletContract.AccountAssetEntry.TABLE_NAME + " aa" +
                        "   ON aa." + WalletContract.AccountAssetEntry._ID + " = a." + WalletContract.AccountEntry._ID +
                        "  AND aa." + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " WHERE a." + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY a." + WalletContract.AccountEntry._ID, null)
        try {
            while (c.moveToNext()) {
                val acc = JSONObject()
                acc.put("name", c.getString(0))
                acc.put("comment", c.getString(1))
                acc.put("icon", if (c.isNull(2)) "unknown" else c.getString(2))
                // Картинку пишем только у своей иконки: серверная восстанавливается по имени, а
                // встроенная заглушка и вовсе не картинка из базы.
                if (!c.isNull(13) && !c.isNull(14) && c.getInt(14) == WalletContract.IconEntry.ORIGIN_LOCAL)
                    acc.put("icon_image", android.util.Base64.encodeToString(c.getBlob(13), android.util.Base64.NO_WRAP))

                val curIso = c.getString(4)
                if (curIso != null) {
                    val currency = JSONObject()
                    currency.put("name", c.getString(3))
                    currency.put("iso", curIso)
                    currency.put("icon", if (c.isNull(5)) "" else c.getString(5))
                    acc.put("currency", currency)
                }
                if (c.getInt(6) == AccountItem.SystemKind.REVALUATION.value)
                    acc.put("system", "revaluation")
                // Вид категории (category-kind): только у обычной категории; у кошелька и
                // системной категории он всегда «Оба», ключа нет.
                if (c.getInt(15) == AccountItem.Kind.CATEGORY.value
                        && c.getInt(6) == AccountItem.SystemKind.NONE.value) {
                    acc.put("category_kind", AccountItem.CategoryKind.fromValue(c.getInt(16)).value)
                }
                // Архив: пишем только true — нет ключа значит «не в архиве» (и при импорте не трогаем).
                if (c.getInt(17) != 0) acc.put("archived", true)
                if (!c.isNull(7)) {
                    val debt = JSONObject()
                    debt.put("counterparty", c.getString(7))
                    debt.put("comment", if (c.isNull(8)) "" else c.getString(8))
                    if (!c.isNull(9)) debt.put("due_date", c.getString(9))
                    acc.put("debt", debt)
                }
                if (!c.isNull(10)) {
                    val asset = JSONObject()
                    asset.put("type", assetTypeName(c.getInt(10)))
                    asset.put("include_in_net_worth", c.getInt(11) != 0)
                    asset.put("comment", if (c.isNull(12)) "" else c.getString(12))
                    acc.put("asset", asset)
                }
                arr.put(acc)
            }
        } finally {
            c.close()
        }
        return arr
    }

    /** Имя типа актива в файле; зеркало [WalletImportData.assetTypeFromName]. */
    @JvmStatic
    fun assetTypeName(type: Int): String {
        when (type) {
            WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE -> return "real_estate"
            WalletContract.AccountAssetEntry.TYPE_VEHICLE -> return "vehicle"
            else -> return "other"
        }
    }

    /** Живые контрагенты по порядку создания. Привязка к контакту телефона (локальная) не выгружается. */
    @Throws(JSONException::class)
    private fun buildCounterparties(db: SQLiteDatabase): JSONArray {
        val arr = JSONArray()
        val c = db.rawQuery(
                "SELECT cp." + WalletContract.CounterpartyEntry.COLUMN_NAME + "," +
                        " cp." + WalletContract.CounterpartyEntry.COLUMN_COMMENT + "," +
                        " ic." + WalletContract.IconEntry.COLUMN_NAME + "," +
                        " cp." + WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED +
                        " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME + " cp" +
                        " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                        "   ON ic." + WalletContract.IconEntry.COLUMN_UUID + " = cp." + WalletContract.CounterpartyEntry.COLUMN_ICON_UUID +
                        "  AND ic." + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " WHERE cp." + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CounterpartyEntry._ID, null)
        try {
            while (c.moveToNext()) {
                val cp = JSONObject()
                cp.put("name", c.getString(0))
                cp.put("comment", if (c.isNull(1)) "" else c.getString(1))
                cp.put("icon", if (c.isNull(2)) "unknown" else c.getString(2))
                cp.put("archived", c.getInt(3) != 0)
                arr.put(cp)
            }
        } finally {
            c.close()
        }
        return arr
    }

    @Throws(JSONException::class)
    private fun buildCurrencyPairs(db: SQLiteDatabase): JSONArray {
        val arr = JSONArray()
        val c = db.rawQuery(
                "SELECT cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " symbol," +
                        " cb." + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " base_iso," +
                        " cq." + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " quote_iso" +
                        " FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp" +
                        " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " cb" +
                        "   ON cb." + WalletContract.CurrencyEntry._ID + " = cp." + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID +
                        " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " cq" +
                        "   ON cq." + WalletContract.CurrencyEntry._ID + " = cp." + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID +
                        " WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CurrencyPairEntry._ID, null)
        try {
            while (c.moveToNext()) {
                val pair = JSONObject()
                pair.put("symbol", c.getString(0))
                pair.put("base", c.getString(1))
                pair.put("quote", c.getString(2))
                arr.put(pair)
            }
        } finally {
            c.close()
        }
        return arr
    }

    @Throws(JSONException::class)
    private fun buildRates(db: SQLiteDatabase): JSONArray {
        val arr = JSONArray()
        val c = db.rawQuery(
                "SELECT cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " symbol," +
                        " substr(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10) date," +
                        " cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_RATE + " rate" +
                        " FROM " + WalletContract.CurrencyPairRateEntry.TABLE_NAME + " cpr" +
                        " JOIN " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp" +
                        "   ON cp." + WalletContract.CurrencyPairEntry._ID + " = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                        " WHERE cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL" +
                        "   AND cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL +
                        ", cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE, null)
        try {
            while (c.moveToNext()) {
                val rate = JSONObject()
                rate.put("symbol", c.getString(0))
                rate.put("date", c.getString(1))
                rate.put("rate", c.getDouble(2))
                arr.put(rate)
            }
        } finally {
            c.close()
        }
        return arr
    }

    /** Одна строка operation, уже разрешённая до имён счетов (см. buildOperations). */
    private class OpRow {
        var date: String = ""
        var fromName: String = ""
        var toName: String = ""
        var comment: String = ""
        @JvmField var amount: Double = 0.0
    }

    @Throws(JSONException::class)
    private fun buildOperations(db: SQLiteDatabase): JSONArray {
        // Порядок вставки в LinkedHashMap = порядок id = хронологический порядок ввода —
        // тот же порядок, в котором операции появятся в экспортированном массиве.
        val ops = LinkedHashMap<Long, OpRow>()
        val c = db.rawQuery(
                "SELECT o." + WalletContract.OperationEntry._ID + " id," +
                        " substr(o." + WalletContract.OperationEntry.COLUMN_DATE + ", 1, 10) date," +
                        " fa." + WalletContract.AccountEntry.COLUMN_NAME + " from_name," +
                        " ta." + WalletContract.AccountEntry.COLUMN_NAME + " to_name," +
                        " o." + WalletContract.OperationEntry.COLUMN_AMOUNT + " amount," +
                        " o." + WalletContract.OperationEntry.COLUMN_COMMENT + " comment" +
                        " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o" +
                        " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " fa" +
                        "   ON fa." + WalletContract.AccountEntry._ID + " = o." + WalletContract.OperationEntry.COLUMN_FROM +
                        " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " ta" +
                        "   ON ta." + WalletContract.AccountEntry._ID + " = o." + WalletContract.OperationEntry.COLUMN_TO +
                        " WHERE o." + WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY o." + WalletContract.OperationEntry._ID, null)
        try {
            while (c.moveToNext()) {
                val row = OpRow()
                row.date = c.getString(1)
                row.fromName = c.getString(2)
                row.toName = c.getString(3)
                row.amount = c.getDouble(4)
                row.comment = if (c.isNull(5)) "" else c.getString(5)
                ops.put(c.getLong(0), row)
            }
        } finally {
            c.close()
        }

        // main_id -> [(link_id, type), ...] — см. OperationLinkEntry.COLUMN_TYPE
        // (0=LinkType.LEG, 1=LinkType.COMMISSION, OperationItem.LinkType).
        val linksByMain = HashMap<Long, MutableList<LongArray>>()
        val linkedIds = HashSet<Long>() // проводки-«ноги»/комиссии — не самостоятельные записи
        val lc = db.rawQuery(
                "SELECT " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + "," +
                        " " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + "," +
                        " " + WalletContract.OperationLinkEntry.COLUMN_TYPE +
                        " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                        " WHERE " + WalletContract.OperationLinkEntry.COLUMN_DELETED_AT + " IS NULL", null)
        try {
            while (lc.moveToNext()) {
                val mainId: Long = lc.getLong(0)
                val linkId: Long = lc.getLong(1)
                val type: Long = lc.getLong(2)
                linksByMain.computeIfAbsent(mainId, { k -> ArrayList() }).add(longArrayOf(linkId, type))
                linkedIds.add(linkId)
            }
        } finally {
            lc.close()
        }

        val arr = JSONArray()
        for (e in ops.entries) {
            val id: Long = e.key
            if (linkedIds.contains(id)) continue // уже войдёт как "leg"/"commission" своего main_id

            val main = e.value
            val entry = JSONObject()
            entry.put("date", main.date)
            entry.put("from", main.fromName)
            entry.put("amount", main.amount)
            entry.put("comment", main.comment)

            var realTo = main.toName
            val links = linksByMain.get(id)
            var legOp: OpRow? = null
            if (links != null) {
                // Сперва "нога" — она определяет реальный "to" (при переводе main.to — мост
                // "Transfer", а не настоящий получатель), нужный второй проход
                // (комиссия) для source/dest уже видит.
                for (link in links) {
                    if (link[1] == OperationItem.LinkType.LEG.value.toLong()) {
                        legOp = ops.get(link[0])
                        if (legOp != null) {
                            realTo = legOp.toName
                            val leg = JSONObject()
                            leg.put("amount", legOp.amount)
                            entry.put("leg", leg)
                        }
                        break
                    }
                }
                for (link in links) {
                    if (link[1] != OperationItem.LinkType.COMMISSION.value.toLong()) continue
                    val commOp = ops.get(link[0])
                    if (commOp == null) continue
                    val commission = JSONObject()
                    commission.put("amount", commOp.amount)
                    commission.put("from", if (commOp.fromName.equals(realTo)) "dest" else "source")
                    commission.put("category", commOp.toName)
                    entry.put("commission", commission)
                    break
                }
            }
            entry.put("to", realTo)

            arr.put(entry)
        }
        return arr
    }
}
