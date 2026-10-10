package com.example.wallettransactions.Data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.Pair;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount;
import com.example.wallettransactions.DataItems.Operations.OperationItem;
import com.example.wallettransactions.DataItems.Operations.OperationList;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class WalletDbLoader {

    public static int  LoadIcons(SQLiteDatabase db, IconList iconList) {
        // deleted_at IS NULL — с v72 ничто в этом коде пока не выставляет его
        // локально (удаление иконки — жёсткий DELETE, см. IconEditDialog.deleteIcon),
        // но входящая с сервера pull-строка (когда синк появится) может прийти уже
        // помеченной удалённой — фильтр на будущее не мешает уже сейчас.
        String sql_select = "SELECT " + WalletContract.IconEntry._ID
                +", "+ WalletContract.IconEntry.COLUMN_NAME
          //      +", "+ WalletContract.IconsEntry.COLUMN_RESOURCE
                +", "+ WalletContract.IconEntry.COLUMN_IMAGE
                +", "+ WalletContract.IconEntry.COLUMN_ICON_TYPE
                +" FROM "+ WalletContract.IconEntry.TABLE_NAME
                +" WHERE " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL";

        int res =0;
        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            int idColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry._ID);
            int nameColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_NAME);
         //   int resourceColumnIndex = cursor.getColumnIndex(WalletContract.IconsEntry.COLUMN_RESOURCE);
            int imageColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_IMAGE);
            int typeColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_ICON_TYPE);

            while (cursor.moveToNext()) {
                int currentID = cursor.getInt(idColumnIndex);
                String currentName = cursor.getString(nameColumnIndex);

             //   Integer currentResource = cursor.getInt(resourceColumnIndex);
                byte[] currentImage = cursor.getBlob(imageColumnIndex);
                Icon.IconType currentType = Icon.IconType.fromValue(cursor.getInt(typeColumnIndex));

                iconList.AddIcon(currentID, currentName,
                        BitmapFactory.decodeByteArray(currentImage, 0, currentImage.length),
                        currentType);
            }
            return res;
        } finally {
            cursor.close();
        }
    }

    final static String getBalancesToDate = "select vao._id, vao.name,  c._id currency_id, c.iso_code currency_iso_code,\n" +
            "opt.amount_to, \n" +
            "coalesce(opf.amount_fr,0) amount_fr,\n" +
            "round(coalesce(opt.amount_to,0) - coalesce(opf.amount_fr,0),2) as balance,\n" +
            "round(vccr.rate * (coalesce(opt.amount_to,0) - coalesce(opf.amount_fr,0) ),2) as balance_eq,\n" +
            "vccr.to_currency_id\n" +
            "from v_account_operating vao \n" +
            "join currency c \n" +
            "  on vao.currency_id = c._id  \n" +
            "left join (\n" +
            "select o.acc_fr_id, round(sum(amount),2) amount_fr\n" +
            "from operation o\n" +
            "WHERE date < ? \n" +
            "group by o.acc_fr_id\n" +
            ") opf\n" +
            "on vao._id = opf.acc_fr_id \n" +
            "left join (\n" +
            "  select o.acc_to_id, round(sum(amount),2) amount_to\n" +
            "from operation o\n" +
            "   WHERE date < ? \n" +
            "   group by o.acc_to_id\n" +
            ") opt\n" +
            "on vao._id = opt.acc_to_id \n" +
            "join (\n" +
            "select from_currency_id , to_currency_id , max(date) max_date \n" +
            "from v_currency_conversion_rate vccr\n" +
            "where date <=? \n" +
            "group by from_currency_id , to_currency_id \n" +
            ") mx\n" +
            "on mx.from_currency_id =c._id and mx.to_currency_id = ? \n" +
            "join v_currency_conversion_rate vccr\n" +
            "on vccr.from_currency_id =mx.from_currency_id and vccr.to_currency_id = mx.to_currency_id and vccr.date = mx.max_date\n" +
            "where (opt.acc_to_id is not null or opf.acc_fr_id is not null)";

    public static class WalletBalanceRow {
        public final int    accountId;
        public final String name;
        public final double balance;
        public final String currencySymbol;
        public final double balanceEq;
        /** False when no rate to the target currency exists — balanceEq is then meaningless. */
        public final boolean hasEq;
        /** True whenever a conversion was applied, i.e. the wallet is not already in the target currency. */
        public final boolean approx;

        public WalletBalanceRow(int accountId, String name, double balance,
                                String currencySymbol, double balanceEq,
                                boolean hasEq, boolean approx) {
            this.accountId      = accountId;
            this.name           = name;
            this.balance        = balance;
            this.currencySymbol = currencySymbol;
            this.balanceEq      = balanceEq;
            this.hasEq          = hasEq;
            this.approx         = approx;
        }
    }

    /**
     * Per-wallet balances at a given date, sorted by balanceEq descending.
     *
     * Archived wallets are in, deliberately: archiving is a UI flag (see
     * {@link com.example.wallettransactions.DataItems.Accounts.AccountItem#getIsArchived()}),
     * so their money is still money and their history still counts. Filtering them out here
     * would silently change the totals for periods already closed.
     */
    public static List<WalletBalanceRow> LoadBalancesPerWallet(SQLiteDatabase db, Date dateFrom,
                                                                Date dateTo, Currency currencyEq) {
        String sql = "SELECT vao._id, vao.name, c._id currency_id, c.symbol, c.precision, " +
                "round(coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0), c.precision) balance, " +
                // No coalesce on the rate: a missing rate must leave balance_eq NULL rather
                // than silently converting 1:1.
                "round(vccr.rate * (coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0)), 2) balance_eq " +
                "FROM v_account_operating vao " +
                "JOIN currency c ON vao.currency_id = c._id " +
                // deleted_at IS NULL — с v72 WalletDbCRUD.DeleteOperation стал мягким;
                // без фильтра баланс продолжал бы учитывать удалённые проводки.
                "LEFT JOIN (SELECT acc_to_id, round(sum(amount), 2) amount_to FROM operation " +
                "  WHERE date < ? AND deleted_at IS NULL GROUP BY acc_to_id) opt ON vao._id = opt.acc_to_id " +
                "LEFT JOIN (SELECT acc_fr_id, round(sum(amount), 2) amount_fr FROM operation " +
                "  WHERE date < ? AND deleted_at IS NULL GROUP BY acc_fr_id) opf ON vao._id = opf.acc_fr_id " +
                "LEFT JOIN (SELECT from_currency_id, to_currency_id, max(date) max_date " +
                "  FROM v_currency_conversion_rate WHERE date <= ? " +
                "  GROUP BY from_currency_id, to_currency_id) mx " +
                "  ON mx.from_currency_id = c._id AND mx.to_currency_id = ? " +
                "LEFT JOIN v_currency_conversion_rate vccr " +
                "  ON vccr.from_currency_id = mx.from_currency_id " +
                "  AND vccr.to_currency_id = mx.to_currency_id AND vccr.date = mx.max_date " +
                "WHERE (opt.acc_to_id IS NOT NULL OR opf.acc_fr_id IS NOT NULL) " +
                "ORDER BY balance_eq DESC";

        String dateFromStr = DateUtils.DateToStr(dateFrom);
        String dateToStr   = DateUtils.DateToStr(dateTo);
        String[] params = new String[]{dateFromStr, dateFromStr, dateToStr, String.valueOf(currencyEq.getId())};

        android.util.Log.d("WalletDbLoader", "LoadBalancesPerWallet SQL: " + sql);
        android.util.Log.d("WalletDbLoader", "Params: dateFromStr=" + dateFromStr + ", dateToStr=" + dateToStr + ", currencyEqId=" + currencyEq.getId());

        Cursor cursor = db.rawQuery(sql, params);

        List<WalletBalanceRow> result = new ArrayList<>();
        try {
            int colId         = cursor.getColumnIndex("_id");
            int colName       = cursor.getColumnIndex("name");
            int colBalance    = cursor.getColumnIndex("balance");
            int colSymbol     = cursor.getColumnIndex("symbol");
            int colEq         = cursor.getColumnIndex("balance_eq");
            int colCurrencyId = cursor.getColumnIndex("currency_id");

            while (cursor.moveToNext()) {
                boolean hasEq  = !cursor.isNull(colEq);
                boolean approx = cursor.getInt(colCurrencyId) != currencyEq.getId();

                result.add(new WalletBalanceRow(
                        cursor.getInt(colId),
                        cursor.getString(colName),
                        cursor.getDouble(colBalance),
                        cursor.getString(colSymbol),
                        hasEq ? cursor.getDouble(colEq) : 0,
                        hasEq,
                        approx
                ));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** Сколько живых операций у счёта и когда была последняя — всего и в паре с одним счётом. */
    public static class AccountUsage {
        public int    count;
        /** Дата в формате базы ({@link DateUtils#DateToStr}) — строки сравниваются как даты. */
        public String lastDate;
        public int    pairCount;
        public String pairLastDate;
    }

    /**
     * Частота использования счетов для сортировки экрана выбора. Считается на лету по
     * {@code operation}, а не отдельным счётчиком: так она сама учитывает операции,
     * пришедшие синком с других устройств, импорт, правку и удаление.
     *
     * @param pairAccountId счёт из второго слота операции (0 — нет): для каждого счёта
     *        дополнительно считаются операции именно с ним. Валютный перевод лежит двумя
     *        ногами через мост «Transfer» (связка {@code operation_link.type = 0}), поэтому
     *        пара «кошелёк → кошелёк» собирается и по ногам, иначе такие переводы с
     *        кошельком-партнёром не встречались бы никогда.
     */
    public static Map<Integer, AccountUsage> LoadAccountUsage(SQLiteDatabase db, int pairAccountId) {
        Map<Integer, AccountUsage> result = new HashMap<>();

        String total = "SELECT acc, count(*) cnt, max(date) last_date FROM (" +
                "  SELECT acc_fr_id acc, date FROM operation WHERE deleted_at IS NULL" +
                "  UNION ALL" +
                "  SELECT acc_to_id acc, date FROM operation WHERE deleted_at IS NULL" +
                ") GROUP BY acc";
        Cursor cursor = db.rawQuery(total, null);
        try {
            while (cursor.moveToNext()) {
                AccountUsage usage = new AccountUsage();
                usage.count = cursor.getInt(1);
                usage.lastDate = cursor.getString(2);
                result.put(cursor.getInt(0), usage);
            }
        } finally {
            cursor.close();
        }

        if (pairAccountId <= 0) return result;

        String legs = " FROM operation_link k" +
                "  JOIN operation m ON m._id = k.main_id" +
                "  JOIN operation l ON l._id = k.link_id" +
                "  WHERE k.type = " + OperationItem.LinkType.LEG.getValue() +
                "  AND k.deleted_at IS NULL AND m.deleted_at IS NULL AND l.deleted_at IS NULL";
        String pair = "SELECT acc, count(*) cnt, max(date) last_date FROM (" +
                "  SELECT acc_to_id acc, date FROM operation WHERE deleted_at IS NULL AND acc_fr_id = ?" +
                "  UNION ALL" +
                "  SELECT acc_fr_id acc, date FROM operation WHERE deleted_at IS NULL AND acc_to_id = ?" +
                "  UNION ALL" +
                "  SELECT l.acc_to_id acc, m.date date" + legs + " AND m.acc_fr_id = ?" +
                "  UNION ALL" +
                "  SELECT m.acc_fr_id acc, m.date date" + legs + " AND l.acc_to_id = ?" +
                ") GROUP BY acc";
        String id = String.valueOf(pairAccountId);
        cursor = db.rawQuery(pair, new String[]{id, id, id, id});
        try {
            while (cursor.moveToNext()) {
                AccountUsage usage = result.get(cursor.getInt(0));
                if (usage == null) continue;   // в общем счёте есть всё, что есть в паре
                usage.pairCount = cursor.getInt(1);
                usage.pairLastDate = cursor.getString(2);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public static EquivalentResult GenerateReportGetBalanceToDate(SQLiteDatabase db, Date date, Currency currencyEqId, DataFilter dataFilter)
    {
        // Same per-wallet query the «Кошельки» list is built from, so the card total and the
        // rows below it can never disagree.
        List<WalletBalanceRow> rows = LoadBalancesPerWallet(db, date, date, currencyEqId);

        double totalEq = 0;
        boolean hasAprox = false, incomplete = false;
        for (WalletBalanceRow row : rows) {
            if (!row.hasEq) {
                // No rate at all: converting 1:1 would inflate the total, so the wallet is
                // left out and the sum is reported as incomplete rather than merely rounded.
                incomplete = true;
                continue;
            }
            totalEq += row.balanceEq;
            if (row.approx) hasAprox = true;
        }

        return new EquivalentResult(totalEq, hasAprox, incomplete);
    }


    /**
     * Net движение через мост «Transfer» за период, в валюте эквивалента: credit-нога
     * (Transfer → кошелёк) минус debit-нога (кошелёк → Transfer) одного и того же
     * валютного перевода. Обе ноги гасят друг друга — остаётся курсовая разница
     * (обычно она и есть весь «net»), см. п.3 docs/TODO_transfers.md.
     *
     * Привязка через {@code operation_link.type = 0} (нога, см. {@code OperationItem.LinkType}),
     * а не по совпадению счёта с системной категорией «Transfer» — иначе сюда попал бы
     * и разовый ручной расход/доход, который пользователь мог завести прямо на эту
     * категорию (пикер счетов её не прячет).
     *
     * @return null, если валютных переводов за период не было (сумма пуста).
     */
    private static Double GetTransferNetEq(SQLiteDatabase db, Date dateFrom, Date dateTo, Currency currencyEq, DataFilter dataFilter) {
        String sql = "select round(sum(\n" +
                "  case when ol.link_id = o._id then o.amount_eq else -o.amount_eq end\n" +
                "),2) net\n" +
                "from v_operation_eq o\n" +
                "join operation_link ol on (ol.main_id = o._id or ol.link_id = o._id)\n" +
                "  and ol.type = 0 and ol.deleted_at is null\n" +
                "where "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "and (nullIf(o.to_currency_id,"+currencyEq.getId()+") is null)\n" +
                // Каждую ногу фильтр проверяет отдельно: при фильтре по кошельку в net
                // попадёт только его нога — так же, как в ленте «Операций».
                reportFilterSql(dataFilter);

        Cursor c = db.rawQuery(sql, null);
        try {
            return (c.moveToFirst() && !c.isNull(0)) ? c.getDouble(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Условие общего фильтра шапки для отчётов по {@code v_operation_eq}; {@code null} —
     * без фильтра (карточка состояния денег считает итоги по всем операциям).
     */
    private static String reportFilterSql(DataFilter dataFilter) {
        return dataFilter == null ? "" : dataFilter.getSQLFilter(
                new String[]{WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO});
    }

    public static ReportByAccount GenerateIncomeReportByAccount(SQLiteDatabase db, Date dateFrom, Date dateTo, AccountList accounts, CurrencyList currencyList, Currency currencyEq, DataFilter dataFilter)
    {
        ReportByAccount res = new ReportByAccount();
        String sql_select = "select o.acc_fr_id, o.currency_id, round(sum(amount),2) amount,  round(sum(amount_eq),2) amount_eq\n" +
                "from v_operation_eq o\n" +
                "WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "and (nullIf(to_currency_id,"+currencyEq.getId()+") is null) and acc_to_is_operating = 1 and acc_from_is_operating =0\n"
                + reportFilterSql(dataFilter)
                // Credit-нога валютного перевода (Transfer → кошелёк) исключена: она не
                // самостоятельный доход, а половина перевода, см. GetTransferNetEq выше и
                // п.3 docs/TODO_transfers.md.
                +"and not exists (select 1 from operation_link ol where ol.link_id = o._id and ol.type = 0 and ol.deleted_at is null)\n"
                +"group by o.acc_fr_id, o.currency_id" ;

        // Net положительный — перевод в этой валюте эквивалента обошёлся «в плюс»
        // (или просто отражает курсовую разницу в пользу получателя); показываем
        // это одной строкой на счёте «Transfer», а не гросс-суммой каждой ноги.
        Double net = GetTransferNetEq(db, dateFrom, dateTo, currencyEq, dataFilter);
        if (net != null && net > 0) {
            AccountItem transfer = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER);
            if (transfer != null) {
                sql_select += "\nunion all select " + transfer.getId() + ", " + currencyEq.getId()
                        + ", " + net + ", " + net;
            }
        }

        Cursor cursor = db.rawQuery(sql_select, null);

        try {
            int accIdColumnIndex = cursor.getColumnIndex("acc_fr_id");
            int curIdColumnIndex = cursor.getColumnIndex("currency_id");
            int amountColumnIndex = cursor.getColumnIndex("amount");
            int amountRcColumnIndex = cursor.getColumnIndex("amount_eq");

            while (cursor.moveToNext()) {
                int accID = cursor.getInt(accIdColumnIndex);
                int curID = cursor.getInt(curIdColumnIndex);
                Double amount = cursor.getDouble(amountColumnIndex);
                Double amountRc=null;
                if (!cursor.isNull(amountRcColumnIndex))
                    amountRc = cursor.getDouble(amountRcColumnIndex);

                res.AddSubOper(accounts.getAccountById(accID),
                        currencyList.getCurrencyById(curID),
                        amount,
                        amountRc
                );

            }
        } finally {
            cursor.close();
        }
        return  res;
    }

    public static ReportByAccount GenerateExpanseReportByAccount(SQLiteDatabase db, Date dateFrom, Date dateTo, AccountList accounts, CurrencyList currencyList, Currency currencyEq, DataFilter dataFilter)
    {
        ReportByAccount res = new ReportByAccount();
        String sql_select = "select o.acc_to_id, o.currency_id, round(sum(amount),2) amount,  round(sum(amount_eq),2) amount_eq\n" +
                "from v_operation_eq o\n" +
                "WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "AND (nullIf(to_currency_id,"+currencyEq.getId()+") is null) and acc_to_is_operating = 0 and acc_from_is_operating =1\n" +
                reportFilterSql(dataFilter) +
                // Debit-нога валютного перевода (кошелёк → Transfer) исключена: она не
                // самостоятельный расход, а половина перевода, см. GetTransferNetEq выше и
                // п.3 docs/TODO_transfers.md.
                "and not exists (select 1 from operation_link ol where ol.main_id = o._id and ol.type = 0 and ol.deleted_at is null)\n" +
                "group by o.acc_to_id,  o.currency_id";

        // Net отрицательный — перевод обошёлся «в минус» (курсовая разница/спред);
        // показываем это одной строкой на счёте «Transfer», а не гросс-суммой каждой ноги.
        Double net = GetTransferNetEq(db, dateFrom, dateTo, currencyEq, dataFilter);
        if (net != null && net < 0) {
            AccountItem transfer = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER);
            if (transfer != null) {
                sql_select += "\nunion all select " + transfer.getId() + ", " + currencyEq.getId()
                        + ", " + (-net) + ", " + (-net);
            }
        }
        sql_select += "\norder by  amount_eq desc";

        Cursor cursor = db.rawQuery(sql_select, null);

        try {
            int accIdColumnIndex = cursor.getColumnIndex("acc_to_id");
            int amountColumnIndex = cursor.getColumnIndex("amount");
            int amountRcColumnIndex = cursor.getColumnIndex("amount_eq");
            int curIdColumnIndex = cursor.getColumnIndex("currency_id");

            while (cursor.moveToNext()) {
                int accID = cursor.getInt(accIdColumnIndex);
                int curID = cursor.getInt(curIdColumnIndex);
                Double amount = cursor.getDouble(amountColumnIndex);
                Double amountRc=null;
                if (!cursor.isNull(amountRcColumnIndex))
                    amountRc = cursor.getDouble(amountRcColumnIndex);

                res.AddSubOper(accounts.getAccountById(accID),
                        currencyList.getCurrencyById(curID),
                        amount,
                        amountRc
                        );

            }
        } finally {
            cursor.close();
        }
        return  res;
    }

    public static void UpdateAccountsOperatingBalance(SQLiteDatabase db, AccountList accounts) {
        final String cFromSumAcc = "fr_amount";
        final String cToSumAcc = "to_amount";
        final String cTotal = "cTotal";
        // deleted_at IS NULL на обеих суммах — с v72 WalletDbCRUD.DeleteOperation
        // стал мягким; без фильтра баланс продолжал бы учитывать удалённые
        // проводки. a.deleted_at/ao.deleted_at — ТОЖЕ обязаны фильтроваться
        // здесь, а не только "выше по стеку" (был реальный краш на живом
        // устройстве: LoadAccounts строит accounts БЕЗ мягко удалённых
        // счетов (см. её WHERE a.deleted_at IS NULL), а этот JOIN — без
        // фильтра вовсе, значит мог отдать _id мягко удалённого счёта,
        // которого в accounts уже нет; accounts.getAccountById(currentID)
        // возвращал null, и (AccountOperatingItem)null.CurrentBalance = ...
        // падал NullPointerException'ом прямо на старте приложения, в
        // MainActivity.onCreate → LoadAccounts → эту функцию).
        String sql_select = "SELECT a." + WalletContract.AccountEntry._ID
                +", tf."+cFromSumAcc+", tt."+cToSumAcc+", round(ifNull(tt."+cToSumAcc+",0)-ifNull(tf."+cFromSumAcc+",0),2) "+cTotal
                +" FROM "+ WalletContract.AccountEntry.TABLE_NAME
                +" a JOIN "   + WalletContract.AccountOperatingEntry.TABLE_NAME
                +" ao on ao." + WalletContract.AccountOperatingEntry._ID +"=a."+WalletContract.AccountEntry._ID
                +" and ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " is null"

                +" left join (select tf."+ WalletContract.OperationEntry.COLUMN_FROM
                +", round(sum(tf."+ WalletContract.OperationEntry.COLUMN_AMOUNT
                +"),2) "+cFromSumAcc
                +" from "+ WalletContract.OperationEntry.TABLE_NAME
                +" tf" //where tf.date between FROM and TO
                +" where tf." + WalletContract.OperationEntry.COLUMN_DELETED_AT + " is null"
                +" group by " + WalletContract.OperationEntry.COLUMN_FROM
                +") tf on a."+WalletContract.AccountEntry._ID+"=tf."+ WalletContract.OperationEntry.COLUMN_FROM

                +" left join (select tt."+ WalletContract.OperationEntry.COLUMN_TO
                +", round(sum(tt."+ WalletContract.OperationEntry.COLUMN_AMOUNT
                +"),2) "+cToSumAcc
                +" from "+ WalletContract.OperationEntry.TABLE_NAME
                +" tt" //where tf.date between FROM and TO
                +" where tt." + WalletContract.OperationEntry.COLUMN_DELETED_AT + " is null"
                +" group by " + WalletContract.OperationEntry.COLUMN_TO
                +") tt on a."+WalletContract.AccountEntry._ID+"=tt."+ WalletContract.OperationEntry.COLUMN_TO

                +" WHERE a." + WalletContract.AccountEntry.COLUMN_DELETED_AT + " is null";
        //where isVisible = 1
        Cursor cursor = db.rawQuery(sql_select, null);

        try {
            int idColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry._ID);
            int totalColumnIndex = cursor.getColumnIndex(cTotal);

            while (cursor.moveToNext()) {
                int currentID = cursor.getInt(idColumnIndex);
                double currentTotal = cursor.getDouble(totalColumnIndex);

                ((AccountOperatingItem)accounts.getAccountById(currentID)).CurrentBalance = currentTotal;
            }
        } finally {
            cursor.close();
        }

    }


    public static void LoadCurrencyList(SQLiteDatabase db, Context context)
    {
        // c.deleted_at IS NULL — с v72 WalletDbCRUD.DeleteCurrency стал мягким
        // (см. её javadoc); без фильтра "удалённая" валюта продолжала бы
        // появляться в списке выбора.
        String sql_select = "SELECT c." + WalletContract.CurrencyEntry._ID
                +", c."+ WalletContract.CurrencyEntry.COLUMN_NAME
                +", c."+ WalletContract.CurrencyEntry.COLUMN_ISO_NAME
                +", c."+ WalletContract.CurrencyEntry.COLUMN_ICON_ID
                +", c."+ WalletContract.CurrencyEntry.COLUMN_SYMBOL
                +", c."+ WalletContract.CurrencyEntry.COLUMN_PRECISION
                +" FROM "+ WalletContract.CurrencyEntry.TABLE_NAME +" c"
                +" WHERE c." + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL";


        Cursor cursor = db.rawQuery(sql_select, null);
        CurrencyList currencyList = Singleton.instance.getCurrencyList(context);
        try {
            int idColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry._ID);
            int nameColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_ISO_NAME);
            int isoNameColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_NAME);
            int iconIdColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_ICON_ID);
            int symbolColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_SYMBOL);
            int precisionColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_PRECISION);

            while (cursor.moveToNext()) {
                int currentID = cursor.getInt(idColumnIndex);
                String currentName = cursor.getString(nameColumnIndex);
                String currentIsoName = cursor.getString(isoNameColumnIndex);
                Integer currentIconId = cursor.getInt(iconIdColumnIndex);
                String currentSymbol = cursor.getString(symbolColumnIndex);
                int currentPrecision = precisionColumnIndex >= 0 ? cursor.getInt(precisionColumnIndex) : 2;

                currencyList.addCurrency(currentID, currentName, currentIsoName,
                                        Singleton.instance.getIconList(context).getIconByIdOrUnknown(currentIconId),
                                        currentSymbol, currentPrecision);
            }
        } finally {
            cursor.close();
        }
    }

    public static void LoadCurrencyPairList(SQLiteDatabase db, CurrencyList currencyList, CurrencyPairList currencyPairList)
    {
        // deleted_at IS NULL — с v72, на cp (мягко удалённая пара) и внутри
        // подзапроса max-даты по cpr (мягко удалённый курс не должен считаться
        // "последним известным"). Сегодня ничего в коде не удаляет currency_pair,
        // но фильтр не мешает и на будущее.
        String sql_select = "SELECT cp." + WalletContract.CurrencyPairEntry._ID
                +", cp."+ WalletContract.CurrencyPairEntry.COLUMN_SYMBOL
                +", cp."+ WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID
                +", cp."+ WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID
                +", cp."+ WalletContract.CurrencyPairEntry.COLUMN_PRECISION
                +", cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_RATE
                +", cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_DATE
                +"\n FROM "+ WalletContract.CurrencyPairEntry.TABLE_NAME
                +" cp\n left join (select cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +", max("+WalletContract.CurrencyPairRateEntry.COLUMN_DATE+") dt_max \n" +
                " from "+WalletContract.CurrencyPairRateEntry.TABLE_NAME
                +" cpr\n where cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " is null"
                +"\n group by cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +") t\n"
                +" on cp."+WalletContract.CurrencyPairEntry._ID+"=t." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +" left join " + WalletContract.CurrencyPairRateEntry.TABLE_NAME
                +" cpr\n on cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE+"=t."
                +WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +" and cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE+"=t.dt_max"
                +" WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL";

       /*"select cp._id, cp.base_currency_id , cp.quote_currency_id , cp.symbol , cpr.rate \n" +
                "from currency_pair cp \n" +
                "left join (\n" +
                "select cpr.currency_pair_id, max(date) dt_max\n" +
                "  from currency_pair_rate cpr \n" +
                " group by cpr.currency_pair_id\n" +
                ") t\n" +
                "on cp._id =t.currency_pair_id\n" +
                "left join currency_pair_rate cpr \n" +
                "on cpr.currency_pair_id = t.currency_pair_id and t.dt_max = cpr.date ";*/

        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            int idColumnIndex        = cursor.getColumnIndex(WalletContract.CurrencyPairEntry._ID);
            int symbolColumnIndex    = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_SYMBOL);
            int baseCurColumnIndex   = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID);
            int quoteCurColumnIndex  = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID);
            int precisionColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_PRECISION);

            while (cursor.moveToNext()) {
                int currentID       = cursor.getInt(idColumnIndex);
                String currentSymbol = cursor.getString(symbolColumnIndex);
                int currentBaseCur  = cursor.getInt(baseCurColumnIndex);
                int currentQuoteCur = cursor.getInt(quoteCurColumnIndex);
                int currentPrecision = precisionColumnIndex >= 0 ? cursor.getInt(precisionColumnIndex) : 5;

                currencyPairList.AddCurrencyPair(currentID, currentSymbol,
                        currencyList.getCurrencyById(currentBaseCur),
                        currencyList.getCurrencyById(currentQuoteCur),
                        currentPrecision);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Курс на конкретную дату (или ближайший более ранний), напрямую по валютам —
     * без привязки к символу пары и без учёта направления (использует
     * {@code v_currency_conversion_rate}, где обратный курс уже посчитан). Нужен для
     * автоподстановки суммы во второй валюте при переводе между разновалютными счетами.
     * Возвращает {@code null}, если курса на эту дату (или раньше) ещё не завели.
     */
    /**
     * Курс и дата, на которую он реально стоит. Дата почти никогда не равна запрошенной:
     * берётся ближайший курс не позже неё, — поэтому её и надо показывать, а не дату
     * операции, иначе подпись утверждает, что курс свежий.
     */
    public static class RateOnDate {
        public final double rate;
        public final Date date;

        public RateOnDate(double rate, Date date) {
            this.rate = rate;
            this.date = date;
        }
    }

    public static RateOnDate LoadRateOnDate(SQLiteDatabase db, int fromCurrencyId, int toCurrencyId, Date date) {
        if (fromCurrencyId == toCurrencyId) return new RateOnDate(1.0, date);

        Cursor cursor = db.rawQuery(
                "SELECT rate, date FROM v_currency_conversion_rate " +
                "WHERE from_currency_id = ? AND to_currency_id = ? AND date <= ? " +
                "ORDER BY date DESC LIMIT 1",
                new String[]{String.valueOf(fromCurrencyId), String.valueOf(toCurrencyId), DateUtils.DateToDateStr(date)});
        try {
            if (cursor.moveToFirst())
                return new RateOnDate(cursor.getDouble(0), DateUtils.StrToDate(cursor.getString(1)));
            return null;
        } finally {
            cursor.close();
        }
    }

    public static Date LoadLastRateDate(SQLiteDatabase db, String currencyPair) {
        // deleted_at IS NULL — с v72 мягко удалённый курс не должен считаться
        // "последним известным" (см. RateEditDialog.deleteRate).
        String sql = "SELECT MAX(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ") max_date"
                + " FROM " + WalletContract.CurrencyPairRateEntry.TABLE_NAME + " cpr"
                + " JOIN " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp"
                + " ON cp." + WalletContract.CurrencyPairEntry._ID
                + " = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                + " WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " = ?"
                + " AND cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + " <= ?"
                + " AND cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL";
        Cursor cursor = db.rawQuery(sql, new String[]{currencyPair, DateUtils.DateToStr(new Date())});
        try {
            if (cursor.moveToFirst() && !cursor.isNull(0))
                return DateUtils.StrToDate(cursor.getString(0));
            return new Date();
        } finally {
            cursor.close();
        }
    }

    public static ArrayList<Pair<Date, Double>> LoadCurrencyPairRateList(SQLiteDatabase db, String currencyPair) {
        return LoadCurrencyPairRateList(db, currencyPair, null, null);
    }

    public static ArrayList<Pair<Date, Double>> LoadCurrencyPairRateList(SQLiteDatabase db, String currencyPair, Date dateFrom, Date dateTo)
    {
        ArrayList<Pair<Date, Double>> res = new ArrayList<Pair<Date, Double>>();

        String dateFilter = "";
        if (dateFrom != null && dateTo != null)
            // Rates are stored as plain 'yyyy-MM-dd' (no time), so comparing against a
            // 'yyyy-MM-dd HH:mm' bound would exclude the exact boundary day (e.g. selecting
            // "from 2009-07-30" must still include the 2009-07-30 rate). Compare on the date
            // part only so both ends of the range are inclusive by calendar day.
            dateFilter = " AND substr(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10)"
                    + " BETWEEN '" + DateUtils.DateToDateStr(dateFrom) + "' AND '" + DateUtils.DateToDateStr(dateTo) + "'";

        // deleted_at IS NULL — с v72 мягко удалённый курс не должен появляться в
        // списке (см. RateEditDialog.deleteRate).
        String sql_select = "SELECT " + WalletContract.CurrencyPairRateEntry.COLUMN_DATE
                +", "+ WalletContract.CurrencyPairRateEntry.COLUMN_RATE
                +" FROM "+ WalletContract.CurrencyPairRateEntry.TABLE_NAME
                +" cpr \n JOIN "+ WalletContract.CurrencyPairEntry.TABLE_NAME
                +" cp\n on cp."+ WalletContract.CurrencyPairEntry._ID
                +" = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +" and cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL +" = '" + currencyPair
                +"'" + dateFilter
                +" and cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " is null"
                +" Order by "+ WalletContract.CurrencyPairRateEntry.COLUMN_DATE +" desc";

        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            int idColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry._ID);
            int dateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_DATE);
            int rateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_RATE);

            while (cursor.moveToNext()) {
                //int currentID = cursor.getInt(idColumnIndex);
                Date dt = DateUtils.StrToDate(cursor.getString(dateColumnIndex));
                Double rate = cursor.getDouble(rateColumnIndex);

                res.add(new Pair(dt, rate));
            }
            return res;
        } finally {
            cursor.close();
        }
    }

  /*  public static void LoadCurrencyConversionRates(SQLiteDatabase db, List<Currency> fromCurrency, Currency toCurrency,
                                                                     Date dateFrom, Date dateTo, CurrencyConversionList currencyConversionList)
    {
        ArrayList<Pair<Date, Double>> res = new ArrayList<Pair<Date, Double>>();


        String fromCurParam = "";

        for (Currency frCur:fromCurrency)
        {
            fromCurParam = fromCurParam + "," + String.valueOf(frCur.getId());
        }


        String sql_select = "SELECT from_currency_id, to_currency_id, date, rate  \n"
                +"FROM v_currency_conversion_rate\n" +
                "where date between '"+String.valueOf(dateFrom.getTime())
                +"' and '"+String.valueOf(dateTo.getTime())+"'\n" +
                "and from_currency_id in ("+fromCurParam+") and to_currency_id =" + String.valueOf(toCurrency.getId());


        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            int fromCurrencyColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID);
            int toCurrencyColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID);
            int dateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_DATE);
            int rateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_RATE);

            while (cursor.moveToNext()) {
            //    int fromCurrency = cursor.getInt(fromCurrencyColumnIndex);
           //     int toCurrency = cursor.getInt(toCurrencyColumnIndex);
                Date dt = new Date(cursor.getLong(dateColumnIndex));
                Double rate = cursor.getDouble(rateColumnIndex);

                currencyConversionList.AddConversionRate();
                res.add(new Pair(dt, rate));
            }
       //     return res;
        } finally {
            cursor.close();
        }
    }*/

    public static void LoadAccounts(SQLiteDatabase db, AccountList accounts, CurrencyList currencyList, IconList iconList, Integer specificId) {

        // a.deleted_at IS NULL — с v72 WalletDbCRUD.DeleteAccount стал мягким (см.
        // её javadoc); без фильтра "удалённый" счёт продолжал бы появляться везде,
        // где используется этот список (пикеры, категории, лента). ao.deleted_at —
        // аналогично снятая "операционность" не должна больше давать валюту счёту.
        String sql_select = "SELECT a." + WalletContract.AccountEntry._ID
                +", a."+ WalletContract.AccountEntry.COLUMN_NAME
                +", a."+ WalletContract.AccountEntry.COLUMN_COMMENT
                +", a."+ WalletContract.AccountEntry.COLUMN_ICON_ID
                +", a."+ WalletContract.AccountEntry.COLUMN_IS_ARCHIVED
                +", a."+ WalletContract.AccountEntry.COLUMN_SYSTEM_KIND
                +", a."+ WalletContract.AccountEntry.COLUMN_KIND

                +", ao."+ WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID

                +" FROM "+ WalletContract.AccountEntry.TABLE_NAME
                +" a left join "+ WalletContract.AccountOperatingEntry.TABLE_NAME
                +" ao on a." +WalletContract.AccountEntry._ID + "=ao."+WalletContract.AccountOperatingEntry._ID
                +" and ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " is null"
                +" left join "+ WalletContract.CurrencyEntry.TABLE_NAME
                +" c on c." +WalletContract.CurrencyEntry._ID + "=ao."+WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID+"\n"
                +" WHERE a." + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL";

        if (specificId != null)
            sql_select = sql_select + " AND a."+WalletContract.AccountEntry._ID+"="+specificId.toString();

        Cursor cursor = db.rawQuery(sql_select, null);

        try {
            int idColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry._ID);
            int nameColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_NAME);
            int commentColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_COMMENT);
            int icoIdColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_ICON_ID);
            int isArchivedColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED);
            int systemKindColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND);
            int kindColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_KIND);

            int curIdColumnIndex = cursor.getColumnIndex(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID);


            while (cursor.moveToNext()) {
                int currentID = cursor.getInt(idColumnIndex);
                String currentName = cursor.getString(nameColumnIndex);
                String currentComment = cursor.getString(commentColumnIndex);
                Integer currentIconId = cursor.getInt(icoIdColumnIndex);

                Integer curId = cursor.getInt(curIdColumnIndex);

                Icon icon = iconList.getIconByIdOrUnknown(currentIconId);
                Boolean currentIsArchived = cursor.getInt(isArchivedColumnIndex)==1;
                AccountItem.SystemKind currentSystemKind =
                        AccountItem.SystemKind.fromValue(cursor.getInt(systemKindColumnIndex));
                AccountItem.Kind currentKind =
                        AccountItem.Kind.fromValue(cursor.getInt(kindColumnIndex));


                AccountItem account = accounts.getAccountById(currentID);

                if (account == null) {
                    if (curId != 0)
                        accounts.put(currentID, new AccountOperatingItem(currentID, currentName, currentComment, icon, currentIsArchived, currencyList.getCurrencyById(curId), currentSystemKind, currentKind));
                     else
                        accounts.put(currentID, new AccountItem(currentID, currentName, currentComment, icon, currentIsArchived, currentSystemKind, currentKind));
                }
                else {
                    account.update(currentName, currentComment, icon, currentIsArchived, currentSystemKind, currentKind);
                }
            }
            accounts.notifyObservers();
        } finally {
            cursor.close();
        }
        WalletDbLoader.UpdateAccountsOperatingBalance(db, accounts);

    }

    /**
     * Сколько операций отдаст {@link #LoadOperations} с этим же фильтром и периодом —
     * для кнопки «Показать N» на экране «Фильтр» (FilterActivity), пересчитывается на
     * лету, пока пользователь ещё не применил выбор.
     *
     * Условие "category→category" исключено тем же способом, каким LoadOperations
     * отбрасывает такие строки на Java-стороне (см. её тело) — иначе счётчик обещал бы
     * больше строк, чем реально попадёт в ленту.
     */
    public static int CountOperations(SQLiteDatabase db, Date dateFrom, Date dateTo, Currency toCurrency, DataFilter dataFilter) {
        String accountsSQL = dataFilter.getSQLFilter(new String[]{WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO});

        String sql_select = "SELECT COUNT(*)"
                + " FROM v_operation_eq t"
                + " WHERE " + WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo)
                + "' AND (nullIf(to_currency_id," + toCurrency.getId() + ") is null)\n"
                + " AND NOT (acc_from_is_operating = 0 AND acc_to_is_operating = 0)\n"
                + accountsSQL;

        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * Кошельки, которые участвуют хотя бы в одной операции периода, прошедшей фильтр —
     * те же условия, что у {@link #LoadOperations}. По ним «Кошельки» решают, какие строки
     * показать при включённом фильтре (см. AccountActivity.applyFilter).
     */
    public static java.util.Set<Integer> LoadWalletIdsWithOperations(SQLiteDatabase db, Date dateFrom, Date dateTo,
                                                                   Currency toCurrency, DataFilter dataFilter) {
        String where = " FROM v_operation_eq"
                + " WHERE " + WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo)
                + "' AND (nullIf(to_currency_id," + toCurrency.getId() + ") is null)\n"
                + reportFilterSql(dataFilter);

        String sql = "SELECT acc_fr_id" + where + " AND acc_from_is_operating = 1\n"
                + "UNION SELECT acc_to_id" + where + " AND acc_to_is_operating = 1";

        java.util.Set<Integer> ids = new java.util.HashSet<>();
        Cursor cursor = db.rawQuery(sql, null);
        try {
            while (cursor.moveToNext()) ids.add(cursor.getInt(0));
        } finally {
            cursor.close();
        }
        return ids;
    }

    public static void LoadOperations(SQLiteDatabase db, OperationList transactions, AccountList accounts, Date dateFrom, Date dateTo, Currency toCurrency, DataFilter dataFilter) {
        transactions.clear();

        String accountsSQL = dataFilter.getSQLFilter(new String[]{WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO});


        String sql_select = "SELECT "
                + "t." + WalletContract.OperationEntry._ID
                +", "+ WalletContract.OperationEntry.COLUMN_DATE
                +", "+ WalletContract.OperationEntry.COLUMN_FROM
                +", "+ WalletContract.OperationEntry.COLUMN_TO
                +", "+ WalletContract.OperationEntry.COLUMN_AMOUNT
                +", amount_eq"
                +", t."+ WalletContract.OperationEntry.COLUMN_COMMENT
                +" FROM v_operation_eq  t"
                +" WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)
                +"' AND (nullIf(to_currency_id,"+toCurrency.getId()+") is null)\n"
                + accountsSQL
                +"\nORDER BY "+ WalletContract.OperationEntry.COLUMN_DATE +" DESC";

        // Делаем запрос
        Cursor cursor = db.rawQuery(sql_select, null);                   // порядок сортировки

        try {
            // Узнаем индекс каждого столбца
            int idColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry._ID);
            int dateColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_DATE);
            int accFromColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_FROM);
            int accToColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_TO);

            int amountRCColumnIndex = cursor.getColumnIndex("amount_eq");
            int amountColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_AMOUNT);
            int commentColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_COMMENT);

            Double amEq;
            while (cursor.moveToNext()) {
                int rowId = cursor.getInt(idColumnIndex);
                try {
                    if (!cursor.isNull(amountRCColumnIndex))
                        amEq = cursor.getDouble(amountRCColumnIndex);
                    else
                        amEq = null;

                    AccountItem accFrom = accounts.getAccountById(cursor.getInt(accFromColumnIndex));
                    AccountItem accTo   = accounts.getAccountById(cursor.getInt(accToColumnIndex));

                    if (accFrom == null || accTo == null) {
                        Log.w("WalletDbLoader", "LoadOperations: skipping op id=" + rowId
                                + " — accFrom=" + accFrom + " accTo=" + accTo);
                        continue;
                    }

                    if (!(accFrom instanceof com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem)
                            && !(accTo instanceof com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem)) {
                        Log.w("WalletDbLoader", "LoadOperations: skipping category→category op id=" + rowId
                                + " (" + accFrom.getName() + "→" + accTo.getName() + ")");
                        continue;
                    }

                    transactions.add(new OperationItem(rowId,
                            DateUtils.StrToDate(cursor.getString(dateColumnIndex)),
                            accFrom, accTo,
                            cursor.getDouble(amountColumnIndex),
                            amEq,
                            cursor.getString(commentColumnIndex)));

                } catch (Exception e) {
                    Log.e("WalletDbLoader", "LoadOperations: error parsing op id=" + rowId
                            + " — " + e.getMessage() + ". Scheduling repair.");
                    WalletDbCRUD.repairDatabase(db);
                }
            }
        } finally {
            cursor.close();
        }
        AttachLinkedOperations(db, transactions, accounts, toCurrency);
        transactions.notifyObservers();
    }

    /**
     * Схлопывает связанные проводки в одну строку ленты: вторая нога валютного перевода и
     * комиссия уходят внутрь главной проводки и перестают быть отдельными операциями.
     *
     * Дочерние проводки догружаются отдельно, а не берутся из уже загруженного списка:
     * фильтр по счетам мог их отсечь (нога «Transfer → кошелёк» не проходит фильтр по
     * кошельку-источнику), а показать операцию целиком всё равно нужно.
     */
    private static void AttachLinkedOperations(SQLiteDatabase db, OperationList transactions,
                                               AccountList accounts, Currency toCurrency) {
        if (transactions.isEmpty()) return;

        StringBuilder ids = new StringBuilder();
        for (OperationItem op : transactions) {
            if (ids.length() > 0) ids.append(",");
            ids.append(op.IdTran);
        }

        // Проводка, привязанная к другой, своей строки в ленте не получает.
        Set<Integer> childIds = new HashSet<>();
        Cursor cursor = db.rawQuery("SELECT " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID
                + " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME
                + " WHERE " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " IN (" + ids + ")"
                // Ничто в коде сегодня не удаляет operation_link мягко (нет метода
                // DeleteOperationLink) — фильтр на будущее, не меняет поведение сейчас.
                + " AND " + WalletContract.OperationLinkEntry.COLUMN_DELETED_AT + " IS NULL", null);
        try {
            while (cursor.moveToNext()) childIds.add(cursor.getInt(0));
        } finally {
            cursor.close();
        }
        if (!childIds.isEmpty()) {
            transactions.removeIf(op -> childIds.contains(op.IdTran));
        }

        for (OperationItem op : transactions) AttachLinksTo(db, op, accounts, toCurrency);
    }

    /**
     * Подвешивает связанные проводки к одной операции. Нужно и при точечном обновлении
     * ленты после правки: перезагруженная операция иначе теряет вторую сумму и комиссию.
     */
    public static void AttachLinksTo(SQLiteDatabase db, OperationItem op,
                                     AccountList accounts, Currency toCurrency) {
        op.LinkedLeg = null;
        op.LinkedCommission = null;

        Map<Integer, OperationItem.LinkType> linkTypes = WalletDbCRUD.GetLinkedOperations(db, op.IdTran);
        for (Map.Entry<Integer, OperationItem.LinkType> e : linkTypes.entrySet()) {
            OperationItem child = LoadSingleOperation(db, e.getKey(), accounts, toCurrency);
            if (child == null) continue;

            // Роль связанной проводки — явный operation_link.type (v77), а не вывод по
            // system_kind счёта-источника (а до него — по совпадению id с op.AccTo), см.
            // javadoc OperationItem.LinkType.
            if (e.getValue() == OperationItem.LinkType.LEG) op.LinkedLeg = child;
            else op.LinkedCommission = child;
        }
    }

    public static OperationItem LoadSingleOperation(SQLiteDatabase db, int tranId, AccountList accounts, Currency toCurrency) {
        String sql_select = "SELECT t." + WalletContract.OperationEntry._ID
                + ", " + WalletContract.OperationEntry.COLUMN_DATE
                + ", " + WalletContract.OperationEntry.COLUMN_FROM
                + ", " + WalletContract.OperationEntry.COLUMN_TO
                + ", " + WalletContract.OperationEntry.COLUMN_AMOUNT
                + ", amount_eq"
                + ", t." + WalletContract.OperationEntry.COLUMN_COMMENT
                + " FROM v_operation_eq t"
                + " WHERE t." + WalletContract.OperationEntry._ID + " = ?"
                + " AND (nullIf(to_currency_id, " + toCurrency.getId() + ") is null)";

        Cursor cursor = db.rawQuery(sql_select, new String[]{String.valueOf(tranId)});
        try {
            if (!cursor.moveToFirst()) return null;
            int amountEqIdx = cursor.getColumnIndex("amount_eq");
            Double amEq = cursor.isNull(amountEqIdx) ? null : cursor.getDouble(amountEqIdx);
            return new OperationItem(
                    cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry._ID)),
                    DateUtils.StrToDate(cursor.getString(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_DATE))),
                    accounts.getAccountById(cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_FROM))),
                    accounts.getAccountById(cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_TO))),
                    cursor.getDouble(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_AMOUNT)),
                    amEq,
                    cursor.getString(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_COMMENT)));
        } finally {
            cursor.close();
        }
    }
}
