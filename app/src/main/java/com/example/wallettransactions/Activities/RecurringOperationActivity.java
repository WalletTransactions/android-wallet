package com.example.wallettransactions.Activities;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.Data.RecurringOperationsDb;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Operations.RecurringOperation;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.sync.RecurringWorker;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Правило повторяющейся операции — новое или правка ({@link #cRecurringId}).
 * <p>
 * Тип операции и счета — по той же схеме, что {@link TransactionActivity}: верхняя строка
 * — «другая сторона» (статья расхода / дохода, для перевода «Откуда»), нижняя — кошелёк.
 * Валютный перевод (кошельки в разных валютах) и комиссия сюда не входят: правило
 * заводит ровно одну проводку, а у валютного перевода их две и сумма во второй валюте
 * зависит от курса на дату — такой перевод по правилу не создать.
 */
public class RecurringOperationActivity extends AppCompatActivity {

    public static final String cRecurringId = "RecurringOperationActivity.Id";

    private static final int AccountSelectTop = 0;
    private static final int AccountSelectBottom = 1;

    private static final int[] INTERVALS = {1, 2, 3, 4};

    private static final DateTimeFormatter DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("ru"));
    private static final DateTimeFormatter TIME_UI = DateTimeFormatter.ofPattern("HH:mm");

    private enum Kind { expense, income, transfer }

    private Kind mKind = Kind.expense;
    private final EnumMap<Kind, AccountItem> mTopByKind = new EnumMap<>(Kind.class);

    private RecurringOperation mRule;
    private boolean mIsEdit;

    private AccountItem mAccFrom;
    private AccountItem mAccTo;

    private ViewGroup mKindExpense;
    private ViewGroup mKindIncome;
    private ViewGroup mKindTransfer;
    private TextView mLabelSlotTop;
    private TextView mLabelSlotBottom;
    private ImageView mImgSlotTop;
    private ImageView mImgSlotBottom;
    private TextView mTvSlotTop;
    private TextView mTvSlotBottom;
    private EditText mEditAmount;
    private TextView mTvCurrencySymbol;
    private EditText mEditComment;
    private TextView mTvFrequency;
    private View mGroupWeekly;
    private ViewGroup mWeekDays;
    private TextView mTvInterval;
    private TextView mTvStartDate;
    private TextView mTvStartTime;
    private FrameLayout mSwitchForever;
    private View mDividerEndDate;
    private View mRowEndDate;
    private TextView mTvEndDate;
    private FrameLayout mSwitchActive;
    private TextView mTvNextOccurrence;

    public static Intent buildIntent(Context ctx, int recurringId) {
        Intent intent = new Intent(ctx, RecurringOperationActivity.class);
        intent.putExtra(cRecurringId, recurringId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recurring_operation);
        assignComponents();

        int id = getIntent().getIntExtra(cRecurringId, 0);
        if (id > 0) mRule = RecurringOperationsDb.load(db(), id);
        mIsEdit = mRule != null;

        if (!mIsEdit) {
            mRule = new RecurringOperation();
            mRule.startDate = LocalDate.now().atTime(12, 0);
            mRule.frequency = RecurringOperation.Frequency.MONTHLY;
        }

        ((TextView) findViewById(R.id.screenTitle)).setText(
                mIsEdit ? "Изменение повтора" : "Новая повторяющаяся");
        findViewById(R.id.btnDelete).setVisibility(mIsEdit ? View.VISIBLE : View.GONE);

        mAccFrom = accountById(mRule.accFromId);
        mAccTo = accountById(mRule.accToId);
        mKind = deriveKind(mAccFrom, mAccTo);

        if (mIsEdit) {
            mEditAmount.setText(formatAmount(mRule.amount, currencyPrecision()));
            mEditComment.setText(mRule.comment);
        }

        for (int i = 0; i < mWeekDays.getChildCount(); i++) {
            mWeekDays.getChildAt(i).setOnClickListener(this::onDayClick);
        }

        renderAll();
    }

    private void assignComponents() {
        mKindExpense = findViewById(R.id.kindExpense);
        mKindIncome = findViewById(R.id.kindIncome);
        mKindTransfer = findViewById(R.id.kindTransfer);
        mLabelSlotTop = findViewById(R.id.labelSlotTop);
        mLabelSlotBottom = findViewById(R.id.labelSlotBottom);
        mImgSlotTop = findViewById(R.id.imgSlotTop);
        mImgSlotBottom = findViewById(R.id.imgSlotBottom);
        mTvSlotTop = findViewById(R.id.tvSlotTop);
        mTvSlotBottom = findViewById(R.id.tvSlotBottom);
        mEditAmount = findViewById(R.id.editAmount);
        mTvCurrencySymbol = findViewById(R.id.tvCurrencySymbol);
        mEditComment = findViewById(R.id.editComment);
        mTvFrequency = findViewById(R.id.tvFrequency);
        mGroupWeekly = findViewById(R.id.groupWeekly);
        mWeekDays = findViewById(R.id.weekDays);
        mTvInterval = findViewById(R.id.tvInterval);
        mTvStartDate = findViewById(R.id.tvStartDate);
        mTvStartTime = findViewById(R.id.tvStartTime);
        mSwitchForever = findViewById(R.id.switchForever);
        mDividerEndDate = findViewById(R.id.dividerEndDate);
        mRowEndDate = findViewById(R.id.rowEndDate);
        mTvEndDate = findViewById(R.id.tvEndDate);
        mSwitchActive = findViewById(R.id.switchActive);
        mTvNextOccurrence = findViewById(R.id.tvNextOccurrence);
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    public void btnUp(View view) {
        finish();
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private void renderAll() {
        renderKind();
        renderSchedule();
    }

    private void renderKind() {
        paintChip(mKindExpense, mKind == Kind.expense);
        paintChip(mKindIncome, mKind == Kind.income);
        paintChip(mKindTransfer, mKind == Kind.transfer);

        switch (mKind) {
            case income:
                mLabelSlotTop.setText("Статья дохода");
                mLabelSlotBottom.setText("Кошелёк");
                break;
            case transfer:
                mLabelSlotTop.setText("Откуда");
                mLabelSlotBottom.setText("Куда");
                break;
            default:
                mLabelSlotTop.setText("Статья расхода");
                mLabelSlotBottom.setText("Кошелёк");
                break;
        }

        bindSlot(mImgSlotTop, mTvSlotTop, getTop());
        bindSlot(mImgSlotBottom, mTvSlotBottom, getBottom());

        AccountOperatingItem wallet = asWallet(mAccFrom);
        if (wallet == null) wallet = asWallet(mAccTo);
        mTvCurrencySymbol.setText(wallet != null ? wallet.getCurrency().getSymbol() : "");
    }

    private void renderSchedule() {
        mTvFrequency.setText(mRule.frequency.getTitle());

        boolean weekly = mRule.frequency == RecurringOperation.Frequency.WEEKLY;
        mGroupWeekly.setVisibility(weekly ? View.VISIBLE : View.GONE);
        if (weekly) {
            for (int i = 0; i < mWeekDays.getChildCount(); i++) {
                TextView chip = (TextView) mWeekDays.getChildAt(i);
                DayOfWeek day = DayOfWeek.of(Integer.parseInt(chip.getTag().toString()));
                paintDayChip(chip, mRule.hasDay(day));
            }
            mTvInterval.setText(intervalLabel(mRule.interval));
        }

        mTvStartDate.setText(mRule.startDate.format(DATE_UI));
        mTvStartTime.setText(mRule.startDate.format(TIME_UI));

        boolean forever = mRule.endDate == null;
        applySwitchState(mSwitchForever, forever);
        mDividerEndDate.setVisibility(forever ? View.GONE : View.VISIBLE);
        mRowEndDate.setVisibility(forever ? View.GONE : View.VISIBLE);
        if (!forever) mTvEndDate.setText(mRule.endDate.format(DATE_UI));

        applySwitchState(mSwitchActive, mRule.isActive);

        renderNextOccurrence();
    }

    private void renderNextOccurrence() {
        if (!mRule.isActive) {
            mTvNextOccurrence.setText("Повтор выключен — операции создаваться не будут.");
            return;
        }
        LocalDateTime next = mRule.nextOccurrence(LocalDateTime.now());
        mTvNextOccurrence.setText(next != null
                ? "Следующая операция: " + next.format(DATE_UI) + ", " + next.format(TIME_UI)
                : "Повторов больше не будет — дата окончания уже прошла.");
    }

    private void paintChip(ViewGroup chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        int color = ContextCompat.getColor(this, active ? R.color.colorSurface : R.color.colorTextSecondary);
        ((ImageView) chip.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        ((TextView) chip.getChildAt(1)).setTextColor(color);
    }

    private void paintDayChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this, active ? R.color.colorSurface : R.color.colorTextSecondary));
    }

    private void bindSlot(ImageView img, TextView text, AccountItem item) {
        if (item != null) {
            img.setImageBitmap(item.getIcon().getBitmap());
            text.setText(item.getName());
            text.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        } else {
            img.setImageBitmap(Singleton.instance.getIconList(this).getUnknownIcon().getBitmap());
            text.setText("Выберите");
            text.setTextColor(ContextCompat.getColor(this, R.color.colorTextHint));
        }
    }

    // ── Тип операции и счета ──────────────────────────────────────────────────

    private boolean topIsFrom() {
        return mKind != Kind.expense;
    }

    private AccountItem getTop() {
        return topIsFrom() ? mAccFrom : mAccTo;
    }

    private AccountItem getBottom() {
        return topIsFrom() ? mAccTo : mAccFrom;
    }

    private void setSlots(AccountItem top, AccountItem bottom) {
        if (topIsFrom()) {
            mAccFrom = top;
            mAccTo = bottom;
        } else {
            mAccFrom = bottom;
            mAccTo = top;
        }
    }

    private Kind deriveKind(AccountItem from, AccountItem to) {
        boolean fromWallet = from != null && from.isOperatingAccount();
        boolean toWallet = to != null && to.isOperatingAccount();
        if (fromWallet && toWallet) return Kind.transfer;
        if (!fromWallet && toWallet) return Kind.income;
        return Kind.expense;
    }

    private AccountItem accountById(int id) {
        return id > 0 ? Singleton.instance.getAccounts(this).getAccountById(id) : null;
    }

    private AccountOperatingItem asWallet(AccountItem item) {
        return item instanceof AccountOperatingItem ? (AccountOperatingItem) item : null;
    }

    private int currencyPrecision() {
        AccountOperatingItem wallet = asWallet(mAccFrom);
        if (wallet == null) wallet = asWallet(mAccTo);
        return wallet != null ? wallet.getCurrency().getPrecision() : 2;
    }

    public void onKindClick(View view) {
        Kind picked;
        int id = view.getId();
        if (id == R.id.kindIncome) picked = Kind.income;
        else if (id == R.id.kindTransfer) picked = Kind.transfer;
        else picked = Kind.expense;
        if (picked == mKind) return;

        // Как на экране операции: кошелёк снизу переезжает, верхняя строка у каждого типа своя.
        mTopByKind.put(mKind, getTop());
        AccountItem wallet = getBottom();
        mKind = picked;

        AccountItem top = mTopByKind.get(mKind);
        if (mKind == Kind.transfer && top != null && wallet != null && top.getId().equals(wallet.getId())) {
            top = null;
        }
        setSlots(top, wallet);
        renderKind();
    }

    public void onPickTop(View view) {
        int type = mKind == Kind.transfer
                ? AccountPickerActivity.cSelectedTypeOperationAccounts
                : AccountPickerActivity.cSelectedTypeChargeAccounts;
        AccountItem current = getTop();
        startActivityForResult(AccountPickerActivity.buildIntent(this, mLabelSlotTop.getText().toString(),
                type, current == null ? 0 : current.getId(), getBottom()), AccountSelectTop);
    }

    public void onPickBottom(View view) {
        AccountItem current = getBottom();
        startActivityForResult(AccountPickerActivity.buildIntent(this, mLabelSlotBottom.getText().toString(),
                AccountPickerActivity.cSelectedTypeOperationAccounts,
                current == null ? 0 : current.getId(), getTop()), AccountSelectBottom);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        int accountId = data.getIntExtra(AccountPickerActivity.cAccountId, -1);
        if (accountId <= 0) return;
        AccountItem picked = accountById(accountId);

        boolean intoFrom = (requestCode == AccountSelectTop) == topIsFrom();
        if (intoFrom) mAccFrom = picked;
        else mAccTo = picked;
        renderKind();
    }

    // ── Расписание ────────────────────────────────────────────────────────────

    /** Выпадающий список частоты повтора. */
    public void onPickFrequency(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        for (RecurringOperation.Frequency f : RecurringOperation.Frequency.values()) {
            popup.getMenu().add(0, f.getValue(), f.ordinal(), f.getTitle());
        }
        popup.setOnMenuItemClickListener(item -> {
            mRule.frequency = RecurringOperation.Frequency.fromValue(item.getItemId());
            if (mRule.frequency == RecurringOperation.Frequency.WEEKLY) {
                // Первый заход в «еженедельно» — отмечаем день недели даты начала, чтобы
                // правило сразу было осмысленным.
                if (mRule.weekDays == 0) mRule.weekDays = RecurringOperation.dayBit(mRule.startDate.getDayOfWeek());
            } else {
                // Шаг «каждые N» — только у еженедельного, см. RecurringOperationEntry.COLUMN_INTERVAL.
                mRule.interval = 1;
            }
            renderSchedule();
            return true;
        });
        popup.show();
    }

    public void onPickInterval(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        for (int i = 0; i < INTERVALS.length; i++) {
            popup.getMenu().add(0, INTERVALS[i], i, intervalLabel(INTERVALS[i]));
        }
        popup.setOnMenuItemClickListener(item -> {
            mRule.interval = item.getItemId();
            renderSchedule();
            return true;
        });
        popup.show();
    }

    private static String intervalLabel(int weeks) {
        switch (weeks) {
            case 1: return "Каждую неделю";
            case 2: return "Каждые 2 недели";
            case 3: return "Каждые 3 недели";
            case 4: return "Каждые 4 недели";
            default: return "Каждые " + weeks + " нед.";
        }
    }

    private void onDayClick(View view) {
        DayOfWeek day = DayOfWeek.of(Integer.parseInt(view.getTag().toString()));
        mRule.weekDays ^= RecurringOperation.dayBit(day);
        renderSchedule();
    }

    public void onPickStartDate(View view) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(mRule.startDate.getYear(), mRule.startDate.getMonthValue() - 1, mRule.startDate.getDayOfMonth());
        CalendarPicker.show(this, cal, (year, month, day) -> {
            mRule.startDate = LocalDate.of(year, month + 1, day).atTime(mRule.startDate.toLocalTime());
            renderSchedule();
        });
    }

    public void onPickStartTime(View view) {
        LocalTime t = mRule.startDate.toLocalTime();
        new TimePickerDialog(this, (picker, hour, minute) -> {
            mRule.startDate = mRule.startDate.toLocalDate().atTime(hour, minute);
            renderSchedule();
        }, t.getHour(), t.getMinute(), true).show();
    }

    public void onToggleForever(View view) {
        if (mRule.endDate == null) {
            // «Всегда» сняли — предлагаем окончание через год, дату сразу можно поменять.
            mRule.endDate = mRule.startDate.toLocalDate().plusYears(1);
            renderSchedule();
            onPickEndDate(view);
        } else {
            mRule.endDate = null;
            renderSchedule();
        }
    }

    public void onPickEndDate(View view) {
        LocalDate end = mRule.endDate != null ? mRule.endDate : LocalDate.now();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(end.getYear(), end.getMonthValue() - 1, end.getDayOfMonth());
        CalendarPicker.show(this, cal, (year, month, day) -> {
            mRule.endDate = LocalDate.of(year, month + 1, day);
            renderSchedule();
        });
    }

    public void onToggleActive(View view) {
        mRule.isActive = !mRule.isActive;
        renderSchedule();
    }

    private void applySwitchState(FrameLayout switchView, boolean on) {
        switchView.setBackgroundResource(on ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        View thumb = switchView.getChildAt(0);
        if (thumb == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.gravity = on ? android.view.Gravity.END : android.view.Gravity.START;
        thumb.setLayoutParams(lp);
    }

    // ── Сохранение и удаление ─────────────────────────────────────────────────

    public void onSaveClick(View view) {
        if (mAccFrom == null) {
            Toast.makeText(this, "Выберите счёт списания средств", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mAccTo == null) {
            Toast.makeText(this, "Выберите счёт зачисления средств", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mAccFrom.getId().equals(mAccTo.getId())) {
            Toast.makeText(this, "Счета списания и зачисления должны различаться", Toast.LENGTH_SHORT).show();
            return;
        }
        AccountOperatingItem from = asWallet(mAccFrom);
        AccountOperatingItem to = asWallet(mAccTo);
        if (from == null && to == null) {
            Toast.makeText(this, "Хотя бы один счёт должен быть кошельком", Toast.LENGTH_LONG).show();
            return;
        }
        if (from != null && to != null && from.getCurrency().getId() != to.getCurrency().getId()) {
            Toast.makeText(this, "Перевод между кошельками в разных валютах повторять нельзя — "
                    + "сумма зачисления зависит от курса на дату", Toast.LENGTH_LONG).show();
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(mEditAmount.getText().toString().replace(',', '.').trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0) {
            Toast.makeText(this, "Сумма должна быть больше нуля", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mRule.frequency == RecurringOperation.Frequency.WEEKLY && mRule.weekDays == 0) {
            Toast.makeText(this, "Отметьте хотя бы один день недели", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mRule.endDate != null && mRule.endDate.isBefore(mRule.startDate.toLocalDate())) {
            Toast.makeText(this, "Дата окончания раньше даты начала", Toast.LENGTH_SHORT).show();
            return;
        }

        mRule.accFromId = mAccFrom.getId();
        mRule.accToId = mAccTo.getId();
        mRule.amount = amount;
        mRule.comment = mEditComment.getText().toString();

        // Дата начала в прошлом (новое правило, перенос даты, включение после паузы):
        // наступившие, но ещё не созданные повторы можно создать сразу или пропустить —
        // решает пользователь, молча досоздавать их нельзя.
        List<LocalDateTime> missed = mRule.isActive
                ? mRule.occurrencesBetween(mRule.lastDate, LocalDateTime.now())
                : java.util.Collections.emptyList();
        if (!missed.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Пропущенные повторы")
                    .setMessage("По этому правилу уже наступило " + missed.size() + " "
                            + operationsWord(missed.size()) + " (с "
                            + missed.get(0).format(DATE_UI) + "). Создать их сейчас?")
                    .setPositiveButton("Создать", (d, w) -> persist())
                    .setNeutralButton("Только будущие", (d, w) -> {
                        mRule.lastDate = missed.get(missed.size() - 1);
                        persist();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        persist();
    }

    private static String operationsWord(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "операций";
        if (mod10 == 1) return "операция";
        if (mod10 >= 2 && mod10 <= 4) return "операции";
        return "операций";
    }

    private void persist() {
        SQLiteDatabase db = db();
        long result = mIsEdit ? RecurringOperationsDb.update(db, mRule) : RecurringOperationsDb.insert(db, mRule);
        if (result <= 0) {
            Toast.makeText(this, "Не удалось сохранить", Toast.LENGTH_SHORT).show();
            return;
        }

        int created = RecurringOperationsDb.generateDue(db, LocalDateTime.now());
        RecurringWorker.kick(this);
        if (created > 0) {
            PeriodFilterState.get(this).markDataChanged();
            Toast.makeText(this, "Создано операций: " + created, Toast.LENGTH_SHORT).show();
        }
        setResult(RESULT_OK);
        finish();
    }

    public void onDeleteClick(View view) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить повтор?")
                .setMessage("Уже созданные операции останутся, новые создаваться не будут.")
                .setPositiveButton("Удалить", (d, w) -> {
                    RecurringOperationsDb.delete(db(), mRule.id);
                    RecurringWorker.kick(this);
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String formatAmount(double value, int precision) {
        return String.format(Locale.US, "%." + precision + "f", value);
    }
}
