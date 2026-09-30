package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.DataItems.Operations.OperationItem;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class WalletImportData {

    private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");

    /** Итог импорта: строки, не попавшие в БД, раньше терялись — счётчик показывал только успехи. */
    public static class ImportResult {
        public int imported;
        public final List<String> skipped = new ArrayList<>();

        private static final int MAX_REPORTED = 10;

        /** @param rowName что считаем, напр. «операций» */
        public String describe(String rowName) {
            StringBuilder sb = new StringBuilder();
            sb.append("Импортировано ").append(imported).append(" ").append(rowName);

            if (skipped.isEmpty())
                return sb.toString();

            sb.append(", пропущено ").append(skipped.size()).append(":\n");
            for (int i = 0; i < Math.min(skipped.size(), MAX_REPORTED); i++)
                sb.append("• ").append(skipped.get(i)).append("\n");
            if (skipped.size() > MAX_REPORTED)
                sb.append("…и ещё ").append(skipped.size() - MAX_REPORTED);

            return sb.toString();
        }
    }

    /** Strips UTF-8 BOM (﻿) and trims whitespace. */
    private static String normalizeHeader(String line) {
        if (line == null) return null;
        if (line.startsWith("﻿")) line = line.substring(1);
        return line.trim();
    }

    private static String headerMismatchMessage(String expected, String actual) {
        StringBuilder sb = new StringBuilder();
        sb.append("Неверный заголовок файла.\n");
        sb.append("Ожидается: ").append(expected).append("\n");
        sb.append("В файле:   ").append(actual).append("\n");
        if (actual != null && !actual.isEmpty()) {
            char first = actual.charAt(0);
            if (first != expected.charAt(0)) {
                sb.append(String.format(
                        "Первый символ в файле: U+%04X (код %d) — возможно BOM или другой разделитель\n",
                        (int) first, (int) first));
            }
            // Detect semicolon delimiter
            if (actual.contains(";") && !actual.contains(",")) {
                sb.append("Похоже, файл использует ';' как разделитель (региональные настройки Excel).\n");
                sb.append("Откройте файл в Excel и сохраните как CSV UTF-8 (с разделителями-запятыми).");
            }
        }
        return sb.toString();
    }


    /**
     * {@code _id} счёта по имени или null — источник правды здесь только БД, не кэш.
     * deleted_at IS NULL — мягко удалённый счёт для импорта не существует: имя
     * теперь можно переиспользовать (см. частичный индекс uq_account_name), и
     * импорт обязан завести НОВУЮ строку, а не тихо "воскресить" старую правкой.
     */
    private static Integer FindAccountIdByName(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery("select " + WalletContract.AccountEntry._ID
                + " from " + WalletContract.AccountEntry.TABLE_NAME
                + " where " + WalletContract.AccountEntry.COLUMN_NAME + " = ?"
                + " and " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " is null", new String[]{name});
        try {
            return c.moveToFirst() ? c.getInt(0) : null;
        } finally {
            c.close();
        }
    }

    private static int InsertOperationRecord(SQLiteDatabase db, Date date, String accFrom, String accTo, double amount,  String comment)
    {
        // uuid — v72 (org/docs/TODO_mobile_sync.md, Фаза 3): эта вставка работает на
        // уже существующей (возможно, апгрейженной) базе, где у колонки uuid нет
        // DEFAULT-выражения (SQLite не разрешает его в ALTER TABLE ADD COLUMN) —
        // значение обязано прийти явно.
        String sql = "insert into operation(date, acc_fr_id, acc_to_id, amount,  comment, uuid)	select dt, af._id , act._id , amount, t.comment, t.uuid from (" +
                "select	'"+ DateUtils.DateToStr(date)+"' dt, ? acc_fr, ? acc_to, ? amount,  ? comment, ? uuid	) t join account af on af.name  = acc_fr join account act on act.name  = acc_to";


        db.execSQL(sql, new String[]{accFrom, accTo, Double.toString(amount), comment, UUID.randomUUID().toString() });
        Cursor res = db.rawQuery("select changes() 'affected_rows'", null);
        res.moveToFirst();
        return res.getInt(res.getColumnIndex("affected_rows"));
    }

    /** @return null, если строка вставлена, иначе причина пропуска для отчёта пользователю. */
    private static String ParseAndInsertOperation(SQLiteDatabase db, String line) throws ParseException {
        String comment;

        String[] vals = line.split(",");
        Date dt = format.parse(vals[0]);
        String accFrom = vals[1];
        String accTo = vals[2];
        double amount = Double.parseDouble(vals[3]);
        if (vals.length < 5)
            comment = "";
        else
            comment = vals[4];

        if (InsertOperationRecord(db, dt, accFrom, accTo, amount, comment) > 0)
            return null;

        // INSERT ... JOIN account молча вставляет 0 строк, если счёта нет в справочнике,
        // поэтому недостающее имя ищем сами — иначе пользователь видит только итоговый ноль.
        StringBuilder missing = new StringBuilder();
        if (FindAccountIdByName(db, accFrom) == null)
            missing.append(accFrom);
        if (FindAccountIdByName(db, accTo) == null) {
            if (missing.length() > 0) missing.append(", ");
            missing.append(accTo);
        }

        if (missing.length() == 0)
            return line + " — строка не вставлена";

        return line + " — нет счёта: " + missing;
    }



    /**
     * Бэкфилл {@code system_kind} по имени — см. вызов в {@link #ParseAndInsertAccount} для
     * объяснения, почему это легитимно здесь. Идемпотентно: на уже корректной строке просто
     * перезапишет то же самое значение.
     */
    private static void stampIfSystemCategory(SQLiteDatabase db, String accName) {
        AccountItem.SystemKind kind;
        if (AccountItem.SYSTEM_TRANSFER_NAME.equalsIgnoreCase(accName == null ? null : accName.trim()))
            kind = AccountItem.SystemKind.TRANSFER;
        else if (AccountItem.SYSTEM_COMMISSION_NAME.equalsIgnoreCase(accName == null ? null : accName.trim()))
            kind = AccountItem.SystemKind.TRANSFER_COMMISSION;
        else
            return;

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND, kind.getValue());
        db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                WalletContract.AccountEntry.COLUMN_NAME + " = ? COLLATE NOCASE", new String[]{accName});
    }

    /**
     * Общее ядро для CSV- и JSON-путей импорта счёта (см. {@link #ParseAndInsertAccount}
     * и {@link #ParseAndInsertJsonAccount}) — раньше было продублировано, теперь один
     * источник правды для вставки счёта + (опционально) его валюты.
     *
     * @param curName валюта не задана (счёт-категория, без своего баланса) — {@code null}
     *                или пустая строка; curIso/curIcon в этом случае не читаются.
     */
    private static int InsertOrUpdateAccount(SQLiteDatabase db, IconList icons, String accName,
            String accComment, String iconName, String curName, String curIso, String curIcon) {
        boolean added;

        // Ищем счёт в самой БД, а не в кэше AccountList: кэш может держать удалённые
        // счета («Ещё → Удалить все операции»), и тогда UPDATE уходил по мёртвому id,
        // INSERT не выполнялся, а импорт возвращал 0 без единой ошибки.
        Integer accId = FindAccountIdByName(db, accName);
        int iconId;
        Icon icon = icons.getIconByName(iconName);
        if (icon == null)
            iconId = 1;
        else
            iconId = icon.getId();

        if (accId != null)
            added = WalletDbCRUD.UpdateAccount(db, accId, accName, accComment, iconId, false)>0;
        else
            added = WalletDbCRUD.InsertAccount(db, accName, accComment, iconId, false)>0;

        // Формат этого импорта не несёт system_kind — если строка "Transfer"/
        // "Transfer commission" только что заведена заново (например, восстановление
        // на базе, где системная категория почему-то отсутствовала), WalletDbCRUD.
        // InsertAccount оставит её обычной (system_kind=0), и весь код перевода
        // перестанет находить её как системную.
        // ТО ЖЕ САМОЕ, что бэкфилл WalletDbHelper v75 / WalletFillData.stampSystemCategories —
        // третье (и последнее) легитимное место сравнения по имени.
        stampIfSystemCategory(db, accName);

        String sql;

        if (curName != null && !curName.isEmpty()) {
            sql = "insert into currency (name, iso_code, icon_id, uuid) \n" +
                    "select t.name, t.iso, COALESCE (i._id,1), t.uuid from (select ? name, ? iso, ? icon_name, ? uuid) t\n" +
                    "left join  icon i \n" +
                    "on i.name  = t.icon_name\n"+
                    "where not exists (select 1 from currency c where t.iso  = c.iso_code ) ";
            db.execSQL(sql, new String[]{curName, curIso, curIcon, UUID.randomUUID().toString()});

            if (!added)
                added = WalletFillData.GetAffectedRowsCount(db) > 0;

            // uuid — a.uuid (ТОТ ЖЕ, что у самого account), не свежий случайный:
            // schema/server/table/account_operating.sql требует
            // account_operating.uuid = account.uuid того же счёта (общий
            // первичный ключ на уровне синка) — иначе push такого кошелька
            // упал бы FK-ошибкой fk_account_operating_account на сервере.
            sql = "insert into account_operating (_id , currency_id, uuid) " +
                    "select a._id, c._id, a.uuid from (\n" +
                    "select ? name, ? cur_name) t\n" +
                    "join account a\n" +
                    "on a.name  = t.name\n" +
                    "join currency c\n" +
                    "on c.iso_code = t.cur_name\n" +
                    "where not exists (select 1 from account_operating ao where ao._id = a._id and ao.currency_id=c._id)";
            db.execSQL(sql, new String[]{accName, curIso});

            if (!added)
                added = WalletFillData.GetAffectedRowsCount(db) > 0;

            // WalletDbCRUD.InsertAccount выше всегда заводит строку как категорию
            // (kind по умолчанию 0) — в кошелёк её превращает именно вставка в
            // account_operating чуть выше, см. javadoc AccountItem.Kind. ТО ЖЕ САМОЕ,
            // что stampIfSystemCategory: формат этого импорта не несёт kind, так что
            // его выводим из факта наличия валюты в самой записи.
            ContentValues cvKind = new ContentValues();
            cvKind.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.getValue());
            db.update(WalletContract.AccountEntry.TABLE_NAME, cvKind,
                    WalletContract.AccountEntry.COLUMN_NAME + " = ? COLLATE NOCASE", new String[]{accName});
        }

        return added?1:0;
    }

    private static int ParseAndInsertAccount(SQLiteDatabase db, IconList icons, String line) throws ParseException {
        String[] vals = line.split(",");

        String accName = vals[0];
        String accComment = vals[1];
        String iconName = vals[2];
        String curName = vals.length > 3 ? vals[3] : null;
        String curIso = (curName != null && !curName.isEmpty()) ? vals[4] : null;
        String curIcon = vals.length > 5 ? vals[5] : "";

        return InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon);
    }

    /**
     * JSON-эквивалент {@link #ParseAndInsertAccount} — см. {@link #FillOperationDataFromJson}.
     * Ожидаемый элемент массива "accounts":
     * {@code {"name": "...", "comment": "...", "icon": "...", "currency": {"name": "...", "iso": "...", "icon": "..."}}}
     * "comment"/"icon" опциональны (по умолчанию — имя счёта и "unknown"); "currency" опциональна —
     * без неё это счёт-категория без своего баланса, как строка без валюты в CSV.
     */
    private static int ParseAndInsertJsonAccount(SQLiteDatabase db, IconList icons, JSONObject obj) throws JSONException {
        String accName = obj.getString("name");
        String accComment = obj.optString("comment", accName);
        String iconName = obj.optString("icon", "unknown");

        JSONObject currency = obj.optJSONObject("currency");
        String curName = null, curIso = null, curIcon = "";
        if (currency != null) {
            // порядок важен: curName должно стать непустым (иначе InsertOrUpdateAccount
            // решит, что валюты нет вовсе), а по умолчанию у валюты обычно и есть только iso
            curIso = currency.getString("iso");
            curName = currency.optString("name", curIso);
            curIcon = currency.optString("icon", "");
        }

        return InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon);
    }

    public static ImportResult FillOperationDataFromFile(Context context, InputStream fileStream) throws ParseException, IOException {
        ImportResult res = new ImportResult();
        final String firstLineHasToBe = "Date,AccountFr,AccountTo,Amount,Comment";
        String line;
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(context);


        db.beginTransaction();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(fileStream));

            final String firstLine = normalizeHeader(reader.readLine());
            if (!firstLineHasToBe.equals(firstLine))
                throw new IOException(headerMismatchMessage(firstLineHasToBe, firstLine));

            while ((line = reader.readLine()) != null){
                if (line.trim().isEmpty()) continue;

                String skipReason = ParseAndInsertOperation(db, line);
                if (skipReason == null)
                    res.imported++;
                else
                    res.skipped.add(skipReason);
            }

            db.setTransactionSuccessful();
            return res;
        } finally {
            db.endTransaction();
        }
    }

    public static int FillAccountDataFromFile(Context context, InputStream fileStream)  throws ParseException, IOException, Exception
    {
        int res = 0;
        String line;

        SQLiteDatabase db = Singleton.instance.getReadableDatabase(context);

        final String firstLineHasToBe = "AccountName,AccountComment,AccountIconName,CurrencyName,CurrencyIso,CurrencyIconName";
        db.beginTransaction();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(fileStream));

            final String firstLine = normalizeHeader(br.readLine());
            if (!firstLineHasToBe.equals(firstLine))
                throw new IOException(headerMismatchMessage(firstLineHasToBe, firstLine));

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    res += ParseAndInsertAccount(db, Singleton.instance.getIconList(context), line);
                } catch (Exception e) {
                    throw new Exception("Cant parse line:"+line +"/n"+ e);
                }
            }
            db.setTransactionSuccessful();
            return res;
        } finally {
            db.endTransaction();
        }
    }

    private static int ParseAndInsertCurrencyPairRate(SQLiteDatabase db, String line) throws ParseException {
        String[] vals = line.split(",");
        String sumbol = vals[0];
        Date dt = format.parse(vals[1]);
        Double rt = Double.parseDouble(vals[2]);

        return WalletFillData.InsertCurrencyPairRate(db, sumbol, dt, rt);
    }

    public static int FillCurrencyPairRateDataFromStream(Context context, InputStream fileStream)  throws ParseException, IOException, Exception
    {
        int res = 0;
        String line;
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(context);

        db.beginTransaction();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(fileStream));

            final String firstLineHasToBe = "Symbol,Date,Rate";
            final String firstLine = normalizeHeader(br.readLine());
            if (!firstLineHasToBe.equals(firstLine))
                throw new IOException(headerMismatchMessage(firstLineHasToBe, firstLine));

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    res += ParseAndInsertCurrencyPairRate(db, line);
                } catch (Exception e) {
                    throw new Exception("Cant parse line:"+line +"/n"+ e);
                }
            }
            db.setTransactionSuccessful();
            return res;
        } finally {
            db.endTransaction();
        }
    }

    // ── Импорт из JSON — план "Б" по docs/TODO_transfers.md, п.2 ───────────────
    //
    // Плоский CSV операций (см. FillOperationDataFromFile выше) не может выразить,
    // что несколько его строк — части одной пользовательской операции: у операции
    // с разными валютами сторон нужны ДВЕ суммы (см. operation_link.sql), а у CSV
    // нет места для такой связи. Отсюда переводы, пришедшие импортом (в т.ч. из
    // tools/moneylover), раньше не имели записей в operation_link вовсе.
    //
    // Формат — JSON-объект с четырьмя опциональными разделами (пустой/отсутствующий
    // раздел просто пропускается); tools/moneylover теперь пишет все четыре в один
    // trans.json, т.к. отдельного SQL/DB-пути для счетов/пар/курсов больше нет:
    //
    //   {
    //     "accounts": [
    //       {"name": "Тинькофф Usd", "comment": "...", "icon": "Tinkoff",
    //        "currency": {"name": "USD", "iso": "USD", "icon": ""}},
    //       {"name": "Groceries", "comment": "Groceries", "icon": "Groceries"}
    //     ],
    //     "currency_pairs": [
    //       {"symbol": "EURUSD", "base": "EUR", "quote": "USD"}
    //     ],
    //     "rates": [
    //       {"symbol": "EURUSD", "date": "2020-08-20", "rate": 1.18023}
    //     ],
    //     "operations": [
    //       {"date": "2020-08-26", "from": "Тинькофф", "to": "Тинькофф Usd",
    //        "amount": 5000, "comment": "...",
    //        "leg": {"amount": 65.4},
    //        "commission": {"amount": 50, "from": "source", "category": "Transfer commission"}},
    //       {"date": "2020-08-26", "from": "Groceries", "to": "Тинькофф",
    //        "amount": 1200, "comment": ""}
    //     ]
    //   }
    //
    // Разделы применяются в этом порядке (accounts → currency_pairs → rates →
    // operations), а не в порядке ключей в файле — счета заводят валюты, нужные
    // парам; пары нужны курсам по symbol; счета нужны операциям по имени.
    //
    // Обратная совместимость: старые файлы — голый JSONArray одних операций (без
    // обёртки) — читаются как раньше, эквивалент {"operations": [...]}.
    //
    // Для operations: "leg"/"commission" опциональны. Без них строка — обычная
    // проводка (расход/доход/одновалютный перевод), ровно как строка CSV. С "leg" —
    // валютный перевод: "from"/"to" — реальные счета отправителя/получателя, а сама
    // проводка в базе идёт через мост "Transfer" (см. TransactionActivity.saveTransfer,
    // тот же путь: InsertOperation дважды + LinkOperations типом LEG). "commission"
    // — третья проводка со стороны "from" (source, по умолчанию) или "to" (dest)
    // на категорию по имени ("category", по умолчанию — системная "Transfer commission"),
    // связанная типом COMMISSION.
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * @return null при успехе, иначе причина пропуска этого элемента массива —
     * тот же принцип отчёта, что и у {@link #ParseAndInsertOperation}.
     */
    private static String ParseAndInsertJsonOperation(SQLiteDatabase db, JSONObject obj)
            throws JSONException, ParseException {
        String dateStr = obj.getString("date");
        Date dt = format.parse(dateStr);
        String accFromName = obj.getString("from");
        String accToName = obj.getString("to");
        double amount = obj.getDouble("amount");
        String comment = obj.optString("comment", "");
        String dtStr = DateUtils.DateToStr(dt);

        Integer fromId = FindAccountIdByName(db, accFromName);
        Integer toId = FindAccountIdByName(db, accToName);
        StringBuilder missing = new StringBuilder();
        if (fromId == null) missing.append(accFromName);
        if (toId == null) {
            if (missing.length() > 0) missing.append(", ");
            missing.append(accToName);
        }
        if (missing.length() > 0)
            return dateStr + " " + accFromName + "→" + accToName + " — нет счёта: " + missing;

        JSONObject leg = obj.optJSONObject("leg");
        long mainId;

        if (leg != null) {
            // Та же категория-мост, тот же способ её найти/завести, что и в
            // TransactionActivity.saveTransfer — обе стороны (интерактивный ввод и
            // импорт) обязаны сходиться к одной и той же строке account.
            int bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                    AccountItem.SystemKind.TRANSFER.getValue(),
                    AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME);
            if (bridgeId <= 0) return dateStr + " — не удалось завести категорию Transfer";

            mainId = WalletDbCRUD.InsertOperation(db, dtStr, fromId, bridgeId, amount, comment);
            long legId = WalletDbCRUD.InsertOperation(db, dtStr, bridgeId, toId, leg.getDouble("amount"), comment);
            if (mainId <= 0 || legId <= 0)
                return dateStr + " " + accFromName + "→" + accToName + " — нога не вставлена";
            WalletDbCRUD.LinkOperations(db, mainId, legId, OperationItem.LinkType.LEG);
        } else {
            mainId = WalletDbCRUD.InsertOperation(db, dtStr, fromId, toId, amount, comment);
            if (mainId <= 0)
                return dateStr + " " + accFromName + "→" + accToName + " — строка не вставлена";
        }

        JSONObject commission = obj.optJSONObject("commission");
        if (commission != null) {
            boolean fromDest = "dest".equals(commission.optString("from", "source"));
            Integer sideId = fromDest ? toId : fromId;
            String categoryName = commission.optString("category", AccountItem.SYSTEM_COMMISSION_NAME);

            // Категория по умолчанию — системная (см. AccountItemActivity/TransactionActivity):
            // находится по system_kind, а не по имени. Своя категория, которую пользователь
            // мог выбрать для конкретного перевода, — обычная, ищется/заводится по имени.
            int categoryId = AccountItem.SYSTEM_COMMISSION_NAME.equalsIgnoreCase(categoryName)
                    ? WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.getValue(),
                            AccountItem.SYSTEM_COMMISSION_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
                    : WalletDbCRUD.GetOrCreateCategory(db, categoryName, AccountItem.SYSTEM_TRANSFER_NAME);
            if (categoryId <= 0) return dateStr + " — не удалось завести категорию комиссии";

            long commissionId = WalletDbCRUD.InsertOperation(db, dtStr, sideId, categoryId,
                    commission.getDouble("amount"), comment);
            if (commissionId <= 0) return dateStr + " — комиссия не вставлена";
            WalletDbCRUD.LinkOperations(db, mainId, commissionId, OperationItem.LinkType.COMMISSION);
        }

        return null;
    }

    /**
     * JSON-эквивалент {@link #ParseAndInsertCurrencyPairRate} для раздела "currency_pairs" —
     * заводит саму пару (символ + база/квота по ISO), а не курс к ней (тот — раздел "rates",
     * см. {@link #ParseAndInsertJsonRate}). Идемпотентно: уже существующий symbol
     * {@link WalletFillData#InsertCurrencyPair} тихо пропускает (см. её javadoc).
     */
    private static String ParseAndInsertJsonCurrencyPair(SQLiteDatabase db, JSONObject obj) throws JSONException {
        String symbol = obj.getString("symbol");
        String base = obj.getString("base");
        String quote = obj.getString("quote");

        WalletFillData.InsertCurrencyPair(db, symbol, base, quote);
        return null;
    }

    /**
     * JSON-эквивалент {@link #ParseAndInsertCurrencyPairRate} (CSV, раздел "Symbol,Date,Rate")
     * для раздела "rates". {@link WalletFillData#InsertCurrencyPairRate} возвращает 0, если
     * валютной пары с таким symbol ещё нет — тогда отчитываемся как о пропуске, а не о тихом нуле.
     */
    private static String ParseAndInsertJsonRate(SQLiteDatabase db, JSONObject obj)
            throws JSONException, ParseException {
        String symbol = obj.getString("symbol");
        String dateStr = obj.getString("date");
        Date dt = format.parse(dateStr);
        double rate = obj.getDouble("rate");

        int affected = WalletFillData.InsertCurrencyPairRate(db, symbol, dt, rate);
        if (affected == 0)
            return symbol + " " + dateStr + " — нет валютной пары с symbol=" + symbol;
        return null;
    }

    public static ImportResult FillOperationDataFromJson(Context context, InputStream fileStream)
            throws IOException, JSONException, ParseException {
        ImportResult res = new ImportResult();
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(context);

        // Обратная совместимость: старый формат — голый JSONArray одних операций.
        // Новый — JSONObject с опциональными "accounts"/"currency_pairs"/"rates"/
        // "operations" (см. комментарий-схему выше FillOperationDataFromJson).
        String text = readAll(fileStream).trim();
        JSONArray accountsArr = null, pairsArr = null, ratesArr = null, opsArr;
        if (text.startsWith("[")) {
            opsArr = new JSONArray(text);
        } else {
            JSONObject root = new JSONObject(text);
            accountsArr = root.optJSONArray("accounts");
            pairsArr = root.optJSONArray("currency_pairs");
            ratesArr = root.optJSONArray("rates");
            opsArr = root.optJSONArray("operations");
            if (opsArr == null) opsArr = new JSONArray();
        }

        IconList icons = accountsArr != null ? Singleton.instance.getIconList(context) : null;

        db.beginTransaction();
        try {
            // Порядок разделов принципиален: счета заводят валюты, нужные парам;
            // пары нужны курсам по symbol; счета нужны операциям по имени.
            if (accountsArr != null) {
                for (int i = 0; i < accountsArr.length(); i++) {
                    try {
                        res.imported += ParseAndInsertJsonAccount(db, icons, accountsArr.getJSONObject(i));
                    } catch (JSONException e) {
                        res.skipped.add("accounts#" + i + " — " + e.getMessage());
                    }
                }
            }
            if (pairsArr != null) {
                for (int i = 0; i < pairsArr.length(); i++) {
                    String skipReason;
                    try {
                        skipReason = ParseAndInsertJsonCurrencyPair(db, pairsArr.getJSONObject(i));
                    } catch (JSONException e) {
                        skipReason = "currency_pairs#" + i + " — " + e.getMessage();
                    }
                    if (skipReason == null) res.imported++;
                    else res.skipped.add(skipReason);
                }
            }
            if (ratesArr != null) {
                for (int i = 0; i < ratesArr.length(); i++) {
                    String skipReason;
                    try {
                        skipReason = ParseAndInsertJsonRate(db, ratesArr.getJSONObject(i));
                    } catch (JSONException e) {
                        skipReason = "rates#" + i + " — " + e.getMessage();
                    }
                    if (skipReason == null) res.imported++;
                    else res.skipped.add(skipReason);
                }
            }
            for (int i = 0; i < opsArr.length(); i++) {
                JSONObject obj = opsArr.getJSONObject(i);
                String skipReason;
                try {
                    skipReason = ParseAndInsertJsonOperation(db, obj);
                } catch (JSONException e) {
                    skipReason = "#" + i + " — " + e.getMessage();
                }
                if (skipReason == null) res.imported++;
                else res.skipped.add(skipReason);
            }
            db.setTransactionSuccessful();
            return res;
        } finally {
            db.endTransaction();
        }
    }
}
