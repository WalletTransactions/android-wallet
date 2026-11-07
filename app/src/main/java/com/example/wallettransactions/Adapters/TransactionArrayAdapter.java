package com.example.wallettransactions.Adapters;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;
import com.example.wallettransactions.DataItems.Operations.ListChanged;
import com.example.wallettransactions.DataItems.Operations.OperationItem;
import com.example.wallettransactions.DataItems.Operations.OperationList;
import com.example.wallettransactions.R;

import java.lang.reflect.Array;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;


public class TransactionArrayAdapter extends ArrayAdapter<OperationItem>  {
    private Context context;
    private OperationList tranList;
    private Currency currencyEq;


    public enum GroupByTime {day, week, month};
    DateComparer dateComparer;

    private GroupedItems groupedItems = new GroupedItems();

    private abstract class DateComparer{
        private Calendar cal1;
        private Calendar cal2;

        public DateComparer(){
            cal1 = Calendar.getInstance();
            cal2 = Calendar.getInstance();
        }

        protected boolean equalsByType(Date dt1, Date dt2, int[] compareBy){
            cal1.setTime(dt1);
            cal2.setTime(dt2);

            boolean res = true;

            for (int itm: compareBy)
            {
                res = res && cal1.get(itm) == cal2.get(itm);
            }
            return res;
        }

        public abstract boolean isTheSameInterval(Date dt1, Date dt2);
        public abstract String getDateName(Date dt);
    }

    private class DateComparerDay extends DateComparer{
        @Override
        public boolean isTheSameInterval(Date dt1, Date dt2) {
            return equalsByType(dt1, dt2, new int[]{Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH});
        }

        @Override
        public String getDateName(Date dt) {
            String dateFormat = "%1$tB";
            int diffDays =  (int) ((new Date().getTime() -  dt.getTime())/ (24 * 60 * 60 * 1000));


            if (diffDays == 1 )
                dateFormat = "Yesterday";
            else
            if (diffDays == 0 )
                dateFormat = "Today";
            else
            if (diffDays < 7 )
                dateFormat = "%1$ta, %1$td " + dateFormat;
            else
                dateFormat = "%1$td "+ dateFormat;

            if (diffDays > 365 )
                dateFormat = dateFormat + " %1$tY";

            return String.format(dateFormat, dt.getTime());
        }
    }

    private class DateComparerMonth extends DateComparer{


        @Override
        public String getDateName(Date dt) {
            String dateFormat = "%1$tb";
            int diffDays =  (int) ((new Date().getTime() -  dt.getTime())/ (24 * 60 * 60 * 1000));

            if (diffDays > 365 )
                dateFormat = " %1$tY "+dateFormat;

            return String.format(dateFormat, dt.getTime());
        }

        @Override
        public boolean isTheSameInterval(Date dt1, Date dt2) {
            return equalsByType(dt1, dt2, new int[]{Calendar.YEAR, Calendar.MONTH});
        }
    }

    private class DateComparerWeek extends DateComparer{

        @Override
        public boolean isTheSameInterval(Date dt1, Date dt2) {

            return equalsByType(dt1, dt2, new int[]{Calendar.YEAR, Calendar.WEEK_OF_YEAR});
        }


        @Override
        public String getDateName(Date dt) {
            String dateFormat = "%1$tB";
            int diffDays =  (int) ((new Date().getTime() -  dt.getTime())/ (24 * 60 * 60 * 1000));


            if (diffDays == 1 )
                dateFormat = "Yesterday";
            else
            if (diffDays == 0 )
                dateFormat = "Today";
            else
            if (diffDays < 7 )
                dateFormat = "%1$ta, %1$td " + dateFormat;
            else
                dateFormat = "%1$td "+ dateFormat;

            if (diffDays > 365 )
                dateFormat = dateFormat + " %1$tY";

            return String.format(dateFormat, dt.getTime());
        }
    }


    private class GroupedItems {
        private Hashtable<OperationItem, EquivalentResult> groupedItems = new Hashtable<OperationItem, EquivalentResult>();

        public EquivalentResult addGroupedItem(OperationItem operationItem){
            // Движение всей операции, а не одной проводки: связанные ноги и комиссия
            // своей строки в ленте не имеют, но в итог дня входить обязаны.
            EquivalentResult res = new EquivalentResult(operationItem.getMovementAmountEq(),
                    operationItem.isApproxEq(currencyEq));
            groupedItems.put(operationItem, res);
            return res;
        }

        public void clear(){
            groupedItems.clear();
        }


        public EquivalentResult getGroupedResult(OperationItem operationItem){
            return groupedItems.get(operationItem);
        }

    }

    public void setCurrencyEq(Currency currencyEq) {
        this.currencyEq = currencyEq;
    }

    public TransactionArrayAdapter(Context context, OperationList values,  Currency currencyEq, GroupByTime groupByTime) {
        super(context, R.layout.transaction_item_layout, values);
        this.context = context;
        this.tranList = values;
        this.currencyEq = currencyEq;

        setGroupByTime(groupByTime);
     //   values.onListChanged.add(this);

    }


    public void setGroupByTime(GroupByTime groupByTime){
        if (groupByTime == groupByTime.month)
            dateComparer = new DateComparerMonth();
        else
        if (groupByTime == groupByTime.week)
            dateComparer = new DateComparerWeek();
        else
            dateComparer = new DateComparerDay();
        prepareGroupedItems();
        super.notifyDataSetChanged();
    }



    @Override
    public void notifyDataSetChanged() {
        prepareGroupedItems();
        super.notifyDataSetInvalidated();
    }

    private void prepareGroupedItems() {
        groupedItems.clear();
        OperationItem operationGroupItem = null;

        EquivalentResult currentGroupedResult = null;

        for (OperationItem item : tranList) {
            if ((operationGroupItem == null) || (!dateComparer.isTheSameInterval(item.Date, operationGroupItem.Date))) {
                currentGroupedResult = groupedItems.addGroupedItem(item);
                operationGroupItem = item;
            } else
            {
                currentGroupedResult.addAmount(item.getMovementAmountEq(), item.isApproxEq(currencyEq));
            }
        }
    }

    public void setGroupByMonth() {
        setGroupByTime(GroupByTime.month);
    }

    public void setGroupByDay() {
        setGroupByTime(GroupByTime.day);
    }

    public void setGroupByWewek() {
        setGroupByTime(GroupByTime.week);
    }

    static class Holder
    {
        TextView tvAccountToName;
        TextView tvAmount ;
        TextView tvComment;
        ImageView imgFrom ;
        ImageView imgAcc ;
        ImageView imgAccTo ;

        LinearLayout layoutDate ;
        TextView tvDate ;
        TextView tvAmountEq ;

        /** Суммы связанных проводок: зачисление во второй валюте и комиссия. */
        TextView tvAmountTo ;
        TextView tvCommission ;

        public Holder (TextView tvAccountToName, TextView tvAmount, TextView tvComment, ImageView imgFrom, ImageView imgAcc,
                       ImageView imgAccTo, LinearLayout layoutDate , TextView tvDate, TextView tvAmountEq,
                       TextView tvAmountTo, TextView tvCommission)
        {
            this.tvAccountToName = tvAccountToName;
            this.tvAmount = tvAmount;
            this.tvComment = tvComment;
            this.imgFrom = imgFrom;
            this.imgAcc = imgAcc;
            this.imgAccTo = imgAccTo;
            this.layoutDate = layoutDate;
            this.tvDate = tvDate;
            this.tvAmountEq = tvAmountEq;
            this.tvAmountTo = tvAmountTo;
            this.tvCommission = tvCommission;
        }
    }

    /**
     * Суммы связанных проводок в той же карточке: зачисление в валюте получателя —
     * зелёным (у валютного перевода оно отличается от списания), ниже — комиссия.
     * Обе строки прячутся, когда связанных проводок нет: строки списка переиспользуются.
     */
    private void bindLinkedAmounts(Holder holder, OperationItem tran) {
        if (tran.LinkedLeg != null) {
            holder.tvAmountTo.setText(formatAmount(tran.LinkedLeg.Amount, tran.LinkedLeg.getCurrency()));
            holder.tvAmountTo.setVisibility(View.VISIBLE);
        } else {
            holder.tvAmountTo.setVisibility(View.GONE);
        }

        if (tran.LinkedCommission != null) {
            holder.tvCommission.setText(getContext().getString(R.string.operation_commission_amount,
                    formatAmount(tran.LinkedCommission.Amount, tran.LinkedCommission.getCurrency())));
            holder.tvCommission.setVisibility(View.VISIBLE);
        } else {
            holder.tvCommission.setVisibility(View.GONE);
        }
    }

    private static String formatAmount(double amount, Currency currency) {
        int precision = currency != null ? currency.getPrecision() : 2;
        String symbol = currency != null ? currency.getSymbol() : "";
        return AdapterUtils.getFormat(precision).format(amount) + " " + symbol;
    }

    // ── Два вида строк: перевод со счёта на счёт и всё остальное ──────────────

    private static final int TYPE_DEFAULT = 0;
    private static final int TYPE_TRANSFER = 1;

    /**
     * Перевод со счёта на счёт: либо обе стороны проводки — кошельки, либо это валютный
     * перевод, который в базе лежит двумя проводками через категорию-мост.
     *
     * Старые импортированные валютные переводы связей не имеют, поэтому под условие не
     * попадают и рисуются обычной строкой — как две отдельные операции, чем они в базе
     * и являются.
     */
    private static boolean isTransfer(OperationItem tran) {
        return tran.isCrossCurrencyTransfer()
                || (tran.isOperatingAccountFrom() && tran.isOperatingAccountTo());
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return isTransfer(tranList.get(position)) ? TYPE_TRANSFER : TYPE_DEFAULT;
    }

    /** Шапка периода: видна только у первой операции группы, у остальных скрыта. */
    private void bindDayHeader(LinearLayout layoutDate, TextView tvDate, TextView tvAmountEq,
                               OperationItem tran) {
        EquivalentResult groupedResult = groupedItems.getGroupedResult(tran);

        if (groupedResult == null) {
            layoutDate.setVisibility(View.GONE);
            return;
        }

        layoutDate.setVisibility(View.VISIBLE);
        tvDate.setText(dateComparer.getDateName(tran.Date));

        // null — не пересчиталось вообще ничего: числа нет, показываем «?».
        // Частично пересчитанный итог печатает setAmountText с пометкой «~».
        if (groupedResult.getTotalAmountEq() == null) {
            tvAmountEq.setTextColor(ContextCompat.getColor(context, R.color.colortransfer));
            tvAmountEq.setText(groupedResult.isIncomplete() ? "~?" : "?");
        } else {
            AdapterUtils.setAmountText(tvAmountEq, groupedResult.getTotalAmountEq(), currencyEq,
                    groupedResult.getApproxEq(), groupedResult.isIncomplete());
        }
    }

    static class TransferHolder {
        final LinearLayout layoutDate;
        final TextView tvDate, tvAmountEq;
        final ImageView imgFrom, imgTo;
        final TextView tvRoute, tvComment, tvFee, tvOut, tvIn;

        TransferHolder(View v) {
            layoutDate = v.findViewById(R.id.LayoutDate);
            tvDate     = v.findViewById(R.id.textDate);
            tvAmountEq = v.findViewById(R.id.textAmountEq);
            imgFrom    = v.findViewById(R.id.transferFromIcon);
            imgTo      = v.findViewById(R.id.transferToIcon);
            tvRoute    = v.findViewById(R.id.transferRoute);
            tvComment  = v.findViewById(R.id.transferComment);
            tvFee      = v.findViewById(R.id.transferFee);
            tvOut      = v.findViewById(R.id.transferOut);
            tvIn       = v.findViewById(R.id.transferIn);
        }
    }

    private View getTransferView(OperationItem tran, View convertView, ViewGroup parent) {
        View listItem = convertView;
        TransferHolder holder;

        if (listItem == null) {
            listItem = LayoutInflater.from(context)
                    .inflate(R.layout.transaction_item_transfer, parent, false);
            holder = new TransferHolder(listItem);
            listItem.setTag(holder);
        } else {
            holder = (TransferHolder) listItem.getTag();
        }

        bindDayHeader(holder.layoutDate, holder.tvDate, holder.tvAmountEq, tran);

        AccountItem accTo = tran.getDestinationAccount();

        holder.imgFrom.setImageBitmap(tran.AccFrom.getIcon().getBitmap());
        holder.imgTo.setImageBitmap(accTo.getIcon().getBitmap());
        holder.tvRoute.setText(tran.AccFrom.getDisplayName() + " → " + accTo.getDisplayName());

        // Пустой комментарий занимал бы целую строку высоты ни за чем.
        boolean hasComment = tran.Comment != null && !tran.Comment.trim().isEmpty();
        holder.tvComment.setText(tran.Comment);
        holder.tvComment.setVisibility(hasComment ? View.VISIBLE : View.GONE);

        AdapterUtils.setAmountExpanseText(holder.tvOut, tran.Amount, tran.getCurrency());

        // Зачисление показываем только когда оно отличается от списания, то есть у
        // перевода между разными валютами: у одновалютного это то же число второй раз.
        if (tran.LinkedLeg != null) {
            AdapterUtils.setAmountDepositText(holder.tvIn, tran.LinkedLeg.Amount,
                    tran.LinkedLeg.getCurrency());
            holder.tvIn.setVisibility(View.VISIBLE);
        } else {
            holder.tvIn.setVisibility(View.GONE);
        }

        if (tran.LinkedCommission != null) {
            holder.tvFee.setText(getContext().getString(R.string.operation_commission_amount,
                    formatAmount(tran.LinkedCommission.Amount, tran.LinkedCommission.getCurrency())));
            holder.tvFee.setVisibility(View.VISIBLE);
        } else {
            holder.tvFee.setVisibility(View.GONE);
        }

        return listItem;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @Nullable ViewGroup parent) {
        View listItem = convertView;

        OperationItem tran = tranList.get(position);

        if (isTransfer(tran)) return getTransferView(tran, convertView, parent);

        Holder holder;

        if (listItem == null) {
            listItem = LayoutInflater.from(context).inflate(R.layout.transaction_item_layout, parent, false);

            holder = new Holder(listItem.findViewById(R.id.accountToName),
                    listItem.findViewById(R.id.amount),
                    listItem.findViewById(R.id.comment),
                    listItem.findViewById(R.id.imageFromView),
                    listItem.findViewById(R.id.imageAccountView),
                    listItem.findViewById(R.id.imageToView),
                    listItem.findViewById(R.id.LayoutDate),
                    listItem.findViewById(R.id.textDate),
                    listItem.findViewById(R.id.textAmountEq),
                    listItem.findViewById(R.id.amountTo),
                    listItem.findViewById(R.id.commission) );
            listItem.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }

        bindDayHeader(holder.layoutDate, holder.tvDate, holder.tvAmountEq, tran);

        holder.tvComment.setText(tran.Comment);
        holder.imgAccTo.setVisibility(View.GONE);

        bindLinkedAmounts(holder, tran);

        // Переводы сюда не доходят — у них своя строка (getTransferView).
        if (tran.isOperatingAccountFrom()) {
            AdapterUtils.setAmountExpanseText(holder.tvAmount, tran.Amount, tran.getCurrency());

            holder.tvAccountToName.setText(tran.AccTo.getDisplayName());
            holder.imgAcc.setImageBitmap(tran.AccTo.getIcon().getBitmap());
            holder.imgFrom.setImageBitmap(tran.AccFrom.getIcon().getBitmap());
        }
        else //if (tran.AccFrom instanceof AccountOperatingItem)
        {
            AdapterUtils.setAmountDepositText(holder.tvAmount, tran.Amount, tran.getCurrency());

            holder.tvAccountToName.setText(tran.AccFrom.getDisplayName());
            holder.imgAcc.setImageBitmap(tran.AccFrom.getIcon().getBitmap());
            holder.imgFrom.setImageBitmap(tran.AccTo.getIcon().getBitmap());
        }

        return listItem;
    }
}
