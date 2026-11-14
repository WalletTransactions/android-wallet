package com.example.wallettransactions.Data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Operations.OperationItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Экспорт БД в тот же JSON, что понимает {@link WalletImportData#FillOperationDataFromJson}
 * ("counterparties"/"accounts"/"currency_pairs"/"rates"/"operations" — см. javadoc-схему там). Симметрично
 * тулу tools/moneylover, который пишет trans.json в этом же формате.
 * <p>
 * Операции реконструируются из operation + operation_link обратно в форму "leg"/"commission"
 * (см. {@link WalletImportData#ParseAndInsertJsonOperation} — main_id всегда проводка списания
 * с источника, type=0 — вторая нога перевода, type=1 — комиссия), а не выгружаются построчно,
 * как в CSV — иначе валютный перевод/комиссия после реимпорта развалились бы обратно на
 * несвязанные проводки.
 */
public class WalletExportData {

    /** Единственный источник правды для формы полей — см. её же в {@link WalletImportData}. */
    private static JSONObject buildSchemaComment() throws JSONException {
        JSONObject c = new JSONObject();
        c.put("_", "Разделы опциональны, но присутствуют всегда (даже пустыми) — ниже форма их элементов."); // not UI: описание формата внутри файла
        c.put("counterparties", "{\"name\": str, \"comment\"?: str, \"icon\"?: str (=\"unknown\"), " +
                "\"archived\"?: bool (=false)} — контрагенты долгов; применяются раньше счетов."); // not UI
        c.put("accounts", "{\"name\": str, \"comment\"?: str (=name), \"icon\"?: str (=\"unknown\"), " +
                "\"currency\"?: {\"name\"?: str (=iso), \"iso\": str, \"icon\"?: str}, " +
                "\"debt\"?: {\"counterparty\": str (имя), \"comment\"?: str, \"due_date\"?: \"YYYY-MM-DD\"}, " + // not UI
                "\"asset\"?: {\"type\": \"real_estate\"|\"vehicle\"|\"other\", \"include_in_net_worth\"?: bool (=true), " +
                "\"comment\"?: str}, \"system\"?: \"revaluation\"} " +
                "— без \"currency\" это счёт-категория (без своего баланса); \"debt\" и \"asset\" — " + // not UI
                "только у кошелька и взаимоисключающие; \"system\" — у системной категории переоценки."); // not UI
        c.put("currency_pairs", "{\"symbol\": str, \"base\": str (ISO валюты), \"quote\": str (ISO валюты)}"); // not UI
        c.put("rates", "{\"symbol\": str (как в currency_pairs), \"date\": \"YYYY-MM-DD\", \"rate\": number}"); // not UI
        c.put("operations", "{\"date\": \"YYYY-MM-DD\", \"from\": str (имя счёта), \"to\": str (имя счёта), " + // not UI
                "\"amount\": number, \"comment\"?: str, " +
                "\"leg\"?: {\"amount\": number} — вторая нога разновалютного перевода, " + // not UI
                "\"commission\"?: {\"amount\": number, \"from\"?: \"source\"|\"dest\" (=\"source\"), " +
                "\"category\"?: str (=\"Transfer commission\")}}");
        return c;
    }

    public static JSONObject BuildExportJson(SQLiteDatabase db) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("_comment", buildSchemaComment());
        root.put("counterparties", buildCounterparties(db));
        root.put("accounts", buildAccounts(db));
        root.put("currency_pairs", buildCurrencyPairs(db));
        root.put("rates", buildRates(db));
        root.put("operations", buildOperations(db));
        return root;
    }

    private static JSONArray buildAccounts(SQLiteDatabase db) throws JSONException {
        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery(
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
                        " aa." + WalletContract.AccountAssetEntry.COLUMN_COMMENT + " asset_comment" +
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
                        " ORDER BY a." + WalletContract.AccountEntry._ID, null);
        try {
            while (c.moveToNext()) {
                JSONObject acc = new JSONObject();
                acc.put("name", c.getString(0));
                acc.put("comment", c.getString(1));
                acc.put("icon", c.isNull(2) ? "unknown" : c.getString(2));

                String curIso = c.getString(4);
                if (curIso != null) {
                    JSONObject currency = new JSONObject();
                    currency.put("name", c.getString(3));
                    currency.put("iso", curIso);
                    currency.put("icon", c.isNull(5) ? "" : c.getString(5));
                    acc.put("currency", currency);
                }
                if (c.getInt(6) == AccountItem.SystemKind.REVALUATION.getValue())
                    acc.put("system", "revaluation");
                if (!c.isNull(7)) {
                    JSONObject debt = new JSONObject();
                    debt.put("counterparty", c.getString(7));
                    debt.put("comment", c.isNull(8) ? "" : c.getString(8));
                    if (!c.isNull(9)) debt.put("due_date", c.getString(9));
                    acc.put("debt", debt);
                }
                if (!c.isNull(10)) {
                    JSONObject asset = new JSONObject();
                    asset.put("type", assetTypeName(c.getInt(10)));
                    asset.put("include_in_net_worth", c.getInt(11) != 0);
                    asset.put("comment", c.isNull(12) ? "" : c.getString(12));
                    acc.put("asset", asset);
                }
                arr.put(acc);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    /** Имя типа актива в файле; зеркало {@link WalletImportData#assetTypeFromName}. */
    static String assetTypeName(int type) {
        switch (type) {
            case WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE: return "real_estate";
            case WalletContract.AccountAssetEntry.TYPE_VEHICLE: return "vehicle";
            default: return "other";
        }
    }

    /** Живые контрагенты по порядку создания. Привязка к контакту телефона (локальная) не выгружается. */
    private static JSONArray buildCounterparties(SQLiteDatabase db) throws JSONException {
        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery(
                "SELECT cp." + WalletContract.CounterpartyEntry.COLUMN_NAME + "," +
                        " cp." + WalletContract.CounterpartyEntry.COLUMN_COMMENT + "," +
                        " ic." + WalletContract.IconEntry.COLUMN_NAME + "," +
                        " cp." + WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED +
                        " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME + " cp" +
                        " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                        "   ON ic." + WalletContract.IconEntry.COLUMN_UUID + " = cp." + WalletContract.CounterpartyEntry.COLUMN_ICON_UUID +
                        "  AND ic." + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " WHERE cp." + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CounterpartyEntry._ID, null);
        try {
            while (c.moveToNext()) {
                JSONObject cp = new JSONObject();
                cp.put("name", c.getString(0));
                cp.put("comment", c.isNull(1) ? "" : c.getString(1));
                cp.put("icon", c.isNull(2) ? "unknown" : c.getString(2));
                cp.put("archived", c.getInt(3) != 0);
                arr.put(cp);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    private static JSONArray buildCurrencyPairs(SQLiteDatabase db) throws JSONException {
        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery(
                "SELECT cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " symbol," +
                        " cb." + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " base_iso," +
                        " cq." + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " quote_iso" +
                        " FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp" +
                        " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " cb" +
                        "   ON cb." + WalletContract.CurrencyEntry._ID + " = cp." + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID +
                        " JOIN " + WalletContract.CurrencyEntry.TABLE_NAME + " cq" +
                        "   ON cq." + WalletContract.CurrencyEntry._ID + " = cp." + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID +
                        " WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CurrencyPairEntry._ID, null);
        try {
            while (c.moveToNext()) {
                JSONObject pair = new JSONObject();
                pair.put("symbol", c.getString(0));
                pair.put("base", c.getString(1));
                pair.put("quote", c.getString(2));
                arr.put(pair);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    private static JSONArray buildRates(SQLiteDatabase db) throws JSONException {
        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery(
                "SELECT cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " symbol," +
                        " substr(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10) date," +
                        " cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_RATE + " rate" +
                        " FROM " + WalletContract.CurrencyPairRateEntry.TABLE_NAME + " cpr" +
                        " JOIN " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp" +
                        "   ON cp." + WalletContract.CurrencyPairEntry._ID + " = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                        " WHERE cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL" +
                        "   AND cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL" +
                        " ORDER BY cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL +
                        ", cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE, null);
        try {
            while (c.moveToNext()) {
                JSONObject rate = new JSONObject();
                rate.put("symbol", c.getString(0));
                rate.put("date", c.getString(1));
                rate.put("rate", c.getDouble(2));
                arr.put(rate);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    /** Одна строка operation, уже разрешённая до имён счетов (см. buildOperations). */
    private static class OpRow {
        String date, fromName, toName, comment;
        double amount;
    }

    private static JSONArray buildOperations(SQLiteDatabase db) throws JSONException {
        // Порядок вставки в LinkedHashMap = порядок id = хронологический порядок ввода —
        // тот же порядок, в котором операции появятся в экспортированном массиве.
        Map<Long, OpRow> ops = new LinkedHashMap<>();
        Cursor c = db.rawQuery(
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
                        " ORDER BY o." + WalletContract.OperationEntry._ID, null);
        try {
            while (c.moveToNext()) {
                OpRow row = new OpRow();
                row.date = c.getString(1);
                row.fromName = c.getString(2);
                row.toName = c.getString(3);
                row.amount = c.getDouble(4);
                row.comment = c.isNull(5) ? "" : c.getString(5);
                ops.put(c.getLong(0), row);
            }
        } finally {
            c.close();
        }

        // main_id -> [(link_id, type), ...] — см. OperationLinkEntry.COLUMN_TYPE
        // (0=LinkType.LEG, 1=LinkType.COMMISSION, OperationItem.LinkType).
        Map<Long, List<long[]>> linksByMain = new HashMap<>();
        Set<Long> linkedIds = new HashSet<>(); // проводки-«ноги»/комиссии — не самостоятельные записи
        Cursor lc = db.rawQuery(
                "SELECT " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + "," +
                        " " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + "," +
                        " " + WalletContract.OperationLinkEntry.COLUMN_TYPE +
                        " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                        " WHERE " + WalletContract.OperationLinkEntry.COLUMN_DELETED_AT + " IS NULL", null);
        try {
            while (lc.moveToNext()) {
                long mainId = lc.getLong(0);
                long linkId = lc.getLong(1);
                long type = lc.getLong(2);
                linksByMain.computeIfAbsent(mainId, k -> new ArrayList<>()).add(new long[]{linkId, type});
                linkedIds.add(linkId);
            }
        } finally {
            lc.close();
        }

        JSONArray arr = new JSONArray();
        for (Map.Entry<Long, OpRow> e : ops.entrySet()) {
            long id = e.getKey();
            if (linkedIds.contains(id)) continue; // уже войдёт как "leg"/"commission" своего main_id

            OpRow main = e.getValue();
            JSONObject entry = new JSONObject();
            entry.put("date", main.date);
            entry.put("from", main.fromName);
            entry.put("amount", main.amount);
            entry.put("comment", main.comment);

            String realTo = main.toName;
            List<long[]> links = linksByMain.get(id);
            OpRow legOp = null;
            if (links != null) {
                // Сперва "нога" — она определяет реальный "to" (при переводе main.to — мост
                // "Transfer", а не настоящий получатель), нужный второй проход
                // (комиссия) для source/dest уже видит.
                for (long[] link : links) {
                    if (link[1] == OperationItem.LinkType.LEG.getValue()) {
                        legOp = ops.get(link[0]);
                        if (legOp != null) {
                            realTo = legOp.toName;
                            JSONObject leg = new JSONObject();
                            leg.put("amount", legOp.amount);
                            entry.put("leg", leg);
                        }
                        break;
                    }
                }
                for (long[] link : links) {
                    if (link[1] != OperationItem.LinkType.COMMISSION.getValue()) continue;
                    OpRow commOp = ops.get(link[0]);
                    if (commOp == null) continue;
                    JSONObject commission = new JSONObject();
                    commission.put("amount", commOp.amount);
                    commission.put("from", commOp.fromName.equals(realTo) ? "dest" : "source");
                    commission.put("category", commOp.toName);
                    entry.put("commission", commission);
                    break;
                }
            }
            entry.put("to", realTo);

            arr.put(entry);
        }
        return arr;
    }
}
