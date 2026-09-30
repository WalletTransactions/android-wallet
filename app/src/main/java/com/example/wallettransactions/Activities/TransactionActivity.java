package com.example.wallettransactions.Activities;

import android.app.TimePickerDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.google.android.material.snackbar.Snackbar;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.DataItems.Operations.OperationItem;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;


/**
 * Экран операции (activity_transaction.xml, прототип 2a — блок isAdd).
 *
 * В базе операция хранится двойной записью: списание {@code From} и зачисление
 * {@code To}. На экране вместо двух безымянных счетов — чип типа операции и две
 * строки, где верхняя (slotTop) — «другая сторона», нижняя (slotBottom) — кошелёк:
 *
 * <ul>
 *   <li>Расход  — slotTop = To (статья расхода), slotBottom = From (кошелёк)</li>
 *   <li>Доход   — slotTop = From (статья дохода), slotBottom = To (кошелёк)</li>
 *   <li>Перевод — slotTop = From (откуда), slotBottom = To (куда)</li>
 * </ul>
 *
 * Всё сопоставление собрано в {@link #topIsFrom()}, чтобы не размазывать его по коду.
 */
public class TransactionActivity extends AppCompatActivity {


    private WalletDbHelper mDbHelper;

    public final static String cTranId = "TransactionActivity.TranId";
    public final static String cDate = "TransactionActivity.Date";
    public final static String cComment = "TransactionActivity.Comment";
    public final static String cAmount = "TransactionActivity.Amount";
    public final static String cFrom = "TransactionActivity.AccountFromId";
    public final static String cTo = "TransactionActivity.AccountToId";

    public final static String cIsEdit = "TransactionActivity.isEdit";

    /**
     * Имя ПО УМОЛЧАНИЮ для категории комиссии за перевод — используется только если её
     * приходится завести с нуля ({@link WalletDbCRUD#GetOrCreateSystemCategory}). Системность
     * (запрет на удаление/переименование) с v75 определяет {@code system_kind}
     * ({@link AccountItem.SystemKind#TRANSFER_COMMISSION}), а не это имя.
     */
    private final static String cCommissionCategoryName = AccountItem.SYSTEM_COMMISSION_NAME;

    /** Столько же, сколько DEFAULT у currency_pair.precision — на случай, если пары нет. */
    private final static int cDefaultRatePrecision = 5;

    static final private int AccountSelectTop = 0;
    static final private int AccountSelectBottom = 1;
    static final private int AccountSelectCommission = 2;

    private enum Kind { expense, income, transfer }

    private Kind mKind = Kind.expense;

    private boolean mIsEdit;
    private EditText mEditDate;
    private EditText mEditTime;

    private EditText mComment;
    private EditText mAmount;
    /** Кнопка сохранения — значок дискеты в колонке действий, см. activity_transaction.xml. */
    private ImageButton mBtnSave;
    /** Кнопка удаления — значок в шапке, виден только при правке (mIsEdit). */
    private ImageButton mBtnDelete;

    private TextView mScreenTitle;
    private TextView mAmountHint;

    /** Подпись под суммой для текущего типа операции: пока в поле выражение, вместо неё результат. */
    private String mAmountHintText = "";

    private EditText mEditSlotTop;
    private EditText mEditSlotBottom;

    private ImageView mImgSlotTop;
    private ImageView mImgSlotBottom;
    private TextView mCurrencySymbol;
    /** Знак «-» перед суммой расхода: только показывается, в поле суммы его нет. */
    private TextView mAmountSign;

    // Чип типа операции — контейнер с иконкой и подписью, а не TextView: см. paintChip.
    private ViewGroup mKindExpense;
    private ViewGroup mKindIncome;
    private ViewGroup mKindTransfer;

    private AccountItem mAccFrom;
    private AccountItem mAccTo;

    /**
     * Верхняя строка каждого типа операции — своя сущность (статья расхода, статья
     * дохода, кошелёк «Откуда»), поэтому при переключении вкладок она не переносится,
     * а откладывается сюда и возвращается при возврате на ту же вкладку.
     */
    private final EnumMap<Kind, AccountItem> mTopByKind = new EnumMap<>(Kind.class);

    private int mTranId;
    private int mCurrencyPrecision = 2;

    /** Поле, которое сейчас набирает наша клавиатура. Системную мы не показываем нигде. */
    private EditText mKeypadTarget;

    // ── Перевод между разновалютными счетами: сумма во второй валюте ───────────
    // Не пишем в базу отдельно — операция хранит одну amount (см. TODO_transfers.md).
    private View mRowAmountTo;
    private EditText mEditAmountTo;
    private TextView mTvCurrencySymbolTo;
    private TextView mTvRateInfo;
    private int mCurrencyPrecisionTo = 2;
    private boolean mAmountToUserEdited;
    private boolean mUpdatingAmountToProgrammatically;

    // ── Комиссия за перевод: только UI, в базу пока не пишем (TODO_transfers.md) ─
    private View mRowCommissionToggle;
    private FrameLayout mSwitchCommission;
    private View mGroupCommissionDetails;
    private ImageView mImgCommissionAccount;
    private EditText mEditCommissionAccount;
    private EditText mEditCommissionAmount;
    private TextView mTvCurrencySymbolCommission;
    private RadioButton mRadioCommissionFromSource;
    private RadioButton mRadioCommissionFromDest;
    private AccountItem mAccCommission;
    private boolean mCommissionEnabled;
    private int mCommissionPrecision = 2;

    private Calendar mCalDateTime;

    // originals for dirty-check (edit mode)
    private int    mOrigAccFromId;
    private int    mOrigAccToId;
    private String mOrigAmount;
    private String mOrigComment;
    private String mOrigDate;
    private String mOrigTime;
    private String mOrigAmountTo;
    private boolean mOrigCommissionOn;
    private int     mOrigCommissionAccId;
    private String  mOrigCommissionAmount;
    private boolean mOrigCommissionFromDest;

    /** Проводки этой же операции в базе; -1 — такой части нет. */
    private int mLegId = -1;
    private int mCommissionId = -1;


    private void AssignComponents() {
        mComment = findViewById(R.id.editComment);
        mAmount  = findViewById(R.id.editAmount);
        mBtnSave = findViewById(R.id.buttonSave);
        mBtnDelete = findViewById(R.id.btnDelete);

        mScreenTitle = findViewById(R.id.screenTitle);
        mAmountHint  = findViewById(R.id.amountHint);

        mEditDate = findViewById(R.id.editDate);
        mEditTime = findViewById(R.id.editTime);

        mEditSlotTop    = findViewById(R.id.editSlotTop);
        mEditSlotBottom = findViewById(R.id.editSlotBottom);

        mImgSlotTop     = findViewById(R.id.imgSlotTop);
        mImgSlotBottom  = findViewById(R.id.imgSlotBottom);
        mCurrencySymbol = findViewById(R.id.tvCurrencySymbol);
        mAmountSign     = findViewById(R.id.tvAmountSign);

        mKindExpense  = findViewById(R.id.kindExpense);
        mKindIncome   = findViewById(R.id.kindIncome);
        mKindTransfer = findViewById(R.id.kindTransfer);

        mRowAmountTo      = findViewById(R.id.rowAmountTo);
        mEditAmountTo     = findViewById(R.id.editAmountTo);
        mTvCurrencySymbolTo = findViewById(R.id.tvCurrencySymbolTo);
        mTvRateInfo       = findViewById(R.id.tvRateInfo);

        mRowCommissionToggle    = findViewById(R.id.rowCommissionToggle);
        mSwitchCommission       = findViewById(R.id.switchCommission);
        mGroupCommissionDetails = findViewById(R.id.groupCommissionDetails);
        mImgCommissionAccount   = findViewById(R.id.imgCommissionAccount);
        mEditCommissionAccount  = findViewById(R.id.editCommissionAccount);
        mEditCommissionAmount   = findViewById(R.id.editCommissionAmount);
        mTvCurrencySymbolCommission = findViewById(R.id.tvCurrencySymbolCommission);
        mRadioCommissionFromSource  = findViewById(R.id.radioCommissionFromSource);
        mRadioCommissionFromDest    = findViewById(R.id.radioCommissionFromDest);

        mEditDate.setFocusable(false);
        mEditTime.setFocusable(false);
        mEditSlotTop.setFocusable(false);
        mEditSlotBottom.setFocusable(false);
        mEditCommissionAccount.setFocusable(false);

        // Все суммы набираются нашей клавиатурой. Крупная сумма — цель по умолчанию.
        bindKeypadTarget(mAmount);
        bindKeypadTarget(mEditAmountTo);
        bindKeypadTarget(mEditCommissionAmount);
        // По крупной сумме удобнее попадать в строку целиком, а не в само поле.
        findViewById(R.id.rowAmount).setOnClickListener(v -> setKeypadTarget(mAmount));
        setKeypadTarget(mAmount);

        // Долгое нажатие на ⌫ очищает поле целиком, а не по одному знаку.
        findViewById(R.id.keypadDel).setOnLongClickListener(v -> {
            mKeypadTarget.getText().clear();
            return true;
        });
    }

    /**
     * Отдаёт поле нашей клавиатуре: системная не поднимается, курсора нет,
     * тап только переключает на него ввод.
     */
    private void bindKeypadTarget(EditText field) {
        field.setShowSoftInputOnFocus(false);
        field.setFocusable(false);
        field.setClickable(true);
        field.setCursorVisible(false);
        field.setOnClickListener(v -> setKeypadTarget(field));
    }

    private void setKeypadTarget(EditText field) {
        // Оставляем в поле результат, а не выражение: следующий ввод продолжается уже
        // с него. Отдельной клавиши «=» нет — её роль играет тап по полю, в том числе
        // по тому же самому.
        if (mKeypadTarget != null) commitExpression(mKeypadTarget);

        mKeypadTarget = field;

        // Активное поле в полную силу, остальные приглушены — иначе не видно,
        // куда именно уйдёт следующая цифра.
        mAmount.setAlpha(field == mAmount ? 1f : 0.4f);
        mEditAmountTo.setAlpha(field == mEditAmountTo ? 1f : 0.4f);
        mEditCommissionAmount.setAlpha(field == mEditCommissionAmount ? 1f : 0.4f);
    }

    /** Заменяет выражение в поле его значением. Обычное число не трогает. */
    private void commitExpression(EditText field) {
        String text = field.getText().toString();
        if (!hasOperator(text)) return;
        field.setText(formatAmount(parseAmount(text), precisionOf(field)));
    }

    /** Точность — валюты того счёта, к которому привязано активное поле. */
    private int targetPrecision() {
        return precisionOf(mKeypadTarget);
    }

    private int precisionOf(EditText field) {
        if (field == mEditAmountTo) return mCurrencyPrecisionTo;
        if (field == mEditCommissionAmount) return mCommissionPrecision;
        return mCurrencyPrecision;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_transaction);

        AssignComponents();

        mDbHelper = new WalletDbHelper(this);

        mTranId  = getIntent().getIntExtra(cTranId, -1);
        mIsEdit  = getIntent().getBooleanExtra(cIsEdit, false);

        mAccFrom = accountById(getIntent().getIntExtra(cFrom, 0));
        mAccTo   = accountById(getIntent().getIntExtra(cTo, 0));
        mKind    = deriveKind(mAccFrom, mAccTo);

        double amount = getIntent().getDoubleExtra(cAmount, 0);
        mAmount.setText(amount == 0 ? "" : String.valueOf(amount));
        mComment.setText(getIntent().getStringExtra(cComment));

        // Экран получает одну проводку, а операция может состоять из нескольких.
        if (mIsEdit) loadLinkedParts();

        Date mDateTime = DateUtils.StrToDate(getIntent().getStringExtra(cDate));

        mScreenTitle.setText(mIsEdit ? "Изменение операции" : "Новая операция");
        // Удалить можно только уже существующую операцию.
        mBtnDelete.setVisibility(mIsEdit ? View.VISIBLE : View.GONE);

        mCalDateTime = Calendar.getInstance();
        mCalDateTime.setTime(mDateTime);
        ShowCalendarDateTime();

        renderKind();

        // Disable save until something changes
        mBtnSave.setEnabled(false);
        mBtnSave.setAlpha(0.4f);

        if (mIsEdit) {
            // Capture originals after all setters
            mOrigAccFromId = mAccFrom != null ? mAccFrom.getId() : 0;
            mOrigAccToId   = mAccTo   != null ? mAccTo.getId()   : 0;
            mOrigAmount    = mAmount.getText().toString();
            mOrigComment   = mComment.getText().toString();
            mOrigDate      = mEditDate.getText().toString();
            mOrigTime      = mEditTime.getText().toString();

            // Связанные части тоже участвуют в проверке «что-то поменялось»: иначе
            // правка одной комиссии не включала бы «Сохранить».
            mOrigAmountTo         = mEditAmountTo.getText().toString();
            mOrigCommissionOn     = mCommissionEnabled;
            mOrigCommissionAccId  = mAccCommission != null ? mAccCommission.getId() : 0;
            mOrigCommissionAmount = mEditCommissionAmount.getText().toString();
            mOrigCommissionFromDest = mRadioCommissionFromDest.isChecked();
        }

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { updateSaveButton(); }
        };
        mAmount.addTextChangedListener(watcher);
        mComment.addTextChangedListener(watcher);
        mEditAmountTo.addTextChangedListener(watcher);
        mEditCommissionAmount.addTextChangedListener(watcher);

        // Сумма изменилась — пересчитываем и вторую валюту, и подпись с результатом.
        mAmount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                recomputeAmountTo();
                updateAmountPreview();
            }
        });

        // Как только пользователь тронул поле сам — курс больше не переписывает его значение.
        mEditAmountTo.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (!mUpdatingAmountToProgrammatically) mAmountToUserEdited = true;
            }
        });
    }

    public void btnUp(View view) {
        finish();
    }

    /**
     * Кнопка видна только при правке (mIsEdit) — см. onCreate. Одиночную операцию
     * подтверждаем как раньше; операцию с ногой и/или комиссией — через
     * {@link #showDeleteGroupDialog()}.
     */
    public void btnDeleteClick(View view) {
        if (mLegId <= 0 && mCommissionId <= 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Удалить")
                    .setMessage("Удалить операцию?")
                    .setPositiveButton("Удалить", (d, w) -> deleteOperation(new int[]{mTranId}))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        showDeleteGroupDialog();
    }

    /**
     * Операция состоит из нескольких проводок ({@link #mLegId}/{@link #mCommissionId}) —
     * но произвольный выбор, какие из них удалить, не имеет смысла: mTranId/mLegId — это
     * два конца одного и того же перевода (дебет/кредит), удалить один и оставить другой
     * нельзя. Осмысленных вариантов ровно два: снять только комиссию (перевод остаётся
     * слинкован сам с собой) или удалить операцию целиком — этот выбор и даём.
     * <p>
     * Если ноги нет (mLegId <= 0, обычная операция с комиссией), «целиком» — это
     * mTranId+mCommissionId, комиссию всё ещё можно снять отдельно. Если комиссии нет
     * (валютный перевод без неё) — делить вообще не на что, сразу спрашиваем про всё,
     * как для одиночной операции.
     */
    private void showDeleteGroupDialog() {
        int[] group = mLegId > 0
                ? new int[]{mTranId, mLegId, mCommissionId}
                : new int[]{mTranId, mCommissionId};
        final int[] all = Arrays.stream(group).filter(id -> id > 0).toArray();

        if (mCommissionId <= 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Удалить")
                    .setMessage("Удалить операцию?")
                    .setPositiveButton("Удалить", (d, w) -> deleteOperation(all))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        String[] items = {
                "Удалить комиссию",
                "Удалить всю операцию",
        };
        new AlertDialog.Builder(this)
                .setTitle("Удалить операцию")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: deleteOperation(new int[]{mCommissionId}); break;
                        case 1: deleteOperation(all); break;
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Удаляет перечисленные проводки — либо только комиссию, либо всю группу целиком
     * (см. {@link #showDeleteGroupDialog()} — других вариантов оттуда не приходит).
     * <p>
     * Удаление мягкое ({@link WalletDbCRUD#DeleteOperation}): строки {@code operation_link}
     * не трогаем, они переживают soft-delete операции, само удаление доедет до сервера
     * и других устройств следующим синком.
     */
    private void deleteOperation(int[] ids) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int id : ids) WalletDbCRUD.DeleteOperation(db, id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra(cTranId, mTranId);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    /**
     * Достаёт связанные проводки и разбирает операцию обратно в поля экрана.
     *
     * Экран открывают по одной проводке, а валютный перевод лежит двумя: главная идёт с
     * кошелька на категорию-мост, и без второй ноги её не отличить от обычного расхода.
     * Поэтому получателя, вторую сумму и комиссию берём из связанных проводок, а тип
     * операции ставим переводом уже по факту наличия ноги, а не по {@code deriveKind}.
     */
    private void loadLinkedParts() {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Currency currencyEq = PeriodFilterState.get(this).getCurrencyEq(this);

        if (mAccTo == null) return;

        Map<Integer, OperationItem.LinkType> linkTypes = WalletDbCRUD.GetLinkedOperations(db, mTranId);
        Map<Integer, OperationItem> children = new LinkedHashMap<>();
        for (Integer childId : linkTypes.keySet()) {
            OperationItem child = WalletDbLoader.LoadSingleOperation(db, childId,
                    Singleton.instance.getAccounts(this), currencyEq);
            if (child != null) children.put(childId, child);
        }
        if (children.isEmpty()) return;

        // Ногу разбираем первой: она подменяет получателя, а сторону списания комиссии
        // сверяем уже с настоящим кошельком, а не с категорией-мостом.
        //
        // Нога опознаётся по явному operation_link.type (v77, AccountItem... то есть
        // OperationItem.LinkType) — раньше это был вывод по system_kind счёта-источника
        // (а до него — по случайному совпадению id с уже загруженным mAccTo), теперь
        // факт хранится в самой строке связи, а не вычисляется на каждой загрузке.
        for (Map.Entry<Integer, OperationItem> e : children.entrySet()) {
            OperationItem child = e.getValue();
            if (linkTypes.get(e.getKey()) != OperationItem.LinkType.LEG) continue;

            mLegId = e.getKey();
            mAccTo = child.AccTo;
            mKind  = Kind.transfer;

            AccountOperatingItem wallet = asWallet(mAccTo);
            setAmountToProgrammatically(formatAmount(child.Amount,
                    wallet != null ? wallet.getCurrency().getPrecision() : 2));
            // Сумма уже согласована пользователем — курс её не переписывает.
            mAmountToUserEdited = true;
            break;
        }

        for (Map.Entry<Integer, OperationItem> e : children.entrySet()) {
            if (e.getKey() == mLegId) continue;

            OperationItem child = e.getValue();
            mCommissionId      = e.getKey();
            mCommissionEnabled = true;
            mAccCommission     = child.AccTo;
            mEditCommissionAmount.setText(formatAmount(child.Amount,
                    child.getCurrency() != null ? child.getCurrency().getPrecision() : 2));

            // Комиссию списывали с той стороны перевода, с которой ушла её проводка.
            boolean fromDest = child.AccFrom.getId().equals(mAccTo.getId());
            mRadioCommissionFromDest.setChecked(fromDest);
            mRadioCommissionFromSource.setChecked(!fromDest);

            bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission);
            break;
        }
    }

    // ── Тип операции ──────────────────────────────────────────────────────────

    /** Верхняя строка держит From для дохода и перевода, To — для расхода. */
    private boolean topIsFrom() {
        return mKind != Kind.expense;
    }

    private AccountItem accountById(int id) {
        return id > 0 ? Singleton.instance.getAccounts(this).getAccountById(id) : null;
    }

    /** Тип операции по счетам: у кошелька isOperatingAccount() == true. */
    private Kind deriveKind(AccountItem from, AccountItem to) {
        boolean fromWallet = from != null && from.isOperatingAccount();
        boolean toWallet   = to   != null && to.isOperatingAccount();

        if (fromWallet && toWallet) return Kind.transfer;
        if (!fromWallet && toWallet) return Kind.income;
        return Kind.expense;
    }

    /** Счёт верхней строки — «другая сторона» операции. */
    private AccountItem getTop() {
        return topIsFrom() ? mAccFrom : mAccTo;
    }

    /** Счёт нижней строки — кошелёк (для перевода «Куда»). */
    private AccountItem getBottom() {
        return topIsFrom() ? mAccTo : mAccFrom;
    }

    /** Раскладывает пару «верх/низ» в From/To согласно текущему типу операции. */
    private void setSlots(AccountItem top, AccountItem bottom) {
        if (topIsFrom()) {
            mAccFrom = top;
            mAccTo   = bottom;
        } else {
            mAccFrom = bottom;
            mAccTo   = top;
        }
    }

    public void onKindClick(View view) {
        Kind picked;
        int id = view.getId();
        if (id == R.id.kindIncome)        picked = Kind.income;
        else if (id == R.id.kindTransfer) picked = Kind.transfer;
        else                              picked = Kind.expense;

        if (picked == mKind) return;

        // Нижняя строка у всех трёх типов — кошелёк, поэтому переезжает вместе с суммой.
        // Верхняя у каждого своя (статья расхода / дохода / «Откуда») — запоминаем её,
        // чтобы вернуть, если пользователь придёт на эту вкладку обратно.
        mTopByKind.put(mKind, getTop());
        AccountItem wallet = getBottom();

        mKind = picked;

        AccountItem top = mTopByKind.get(mKind);
        // Перевод сам на себя смысла не имеет — а запомненное «Откуда» вполне может
        // совпасть с кошельком, который приехал снизу.
        if (mKind == Kind.transfer && top != null && wallet != null
                && top.getId().equals(wallet.getId())) {
            top = null;
        }

        setSlots(top, wallet);

        renderKind();
        updateSaveButton();
    }

    /** Красит чипы, подписи строк и сумму под текущий тип операции. */
    private void renderKind() {
        paintChip(mKindExpense,  mKind == Kind.expense);
        paintChip(mKindIncome,   mKind == Kind.income);
        paintChip(mKindTransfer, mKind == Kind.transfer);

        switch (mKind) {
            case income:
                mEditSlotTop.setHint("Статья дохода");
                mEditSlotBottom.setHint("Кошелёк");
                mAmountHintText = "Сколько получили";
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colorprofit));
                break;
            case transfer:
                mEditSlotTop.setHint("Откуда");
                mEditSlotBottom.setHint("Куда");
                mAmountHintText = "Сколько переводим";
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colortransfer));
                break;
            default:
                mEditSlotTop.setHint("Статья расхода");
                mEditSlotBottom.setHint("Кошелёк");
                mAmountHintText = "Сколько потратили";
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse));
                break;
        }

        // Подпись под суммой занята результатом, пока там выражение.
        updateAmountPreview();

        // Минус — только у расхода, и только как подпись: сама сумма всегда без знака.
        mAmountSign.setVisibility(mKind == Kind.expense ? View.VISIBLE : View.GONE);

        updateCommissionVisibility();
        renderSlots();
    }

    private void paintChip(ViewGroup chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);

        int color = ContextCompat.getColor(this,
                active ? R.color.colorSurface : R.color.colorTextSecondary);
        // Иконка типа операции живёт в самом чипе, поэтому красится вместе с подписью.
        // Порядок вьюх задан разметкой: сначала иконка, потом подпись.
        ((ImageView) chip.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        ((TextView) chip.getChildAt(1)).setTextColor(color);
    }

    /** Раскладывает mAccFrom/mAccTo по строкам согласно типу операции. */
    private void renderSlots() {
        bindSlot(mImgSlotTop, mEditSlotTop, getTop());
        bindSlot(mImgSlotBottom, mEditSlotBottom, getBottom());

        // Валюта суммы — та же, что выведет OperationItem: кошелёк списания,
        // а если списывают с категории (доход) — кошелёк зачисления.
        AccountOperatingItem wallet = asWallet(mAccFrom);
        if (wallet == null) wallet = asWallet(mAccTo);

        if (wallet != null) {
            Currency cur = wallet.getCurrency();
            mCurrencySymbol.setText(cur.getSymbol());
            mCurrencyPrecision = cur.getPrecision();
            updateAmountFilter();
        } else {
            mCurrencySymbol.setText("");
        }

        updateCrossCurrencyUI();
        updateCommissionCurrency();
    }

    private AccountOperatingItem asWallet(AccountItem item) {
        return item instanceof AccountOperatingItem ? (AccountOperatingItem) item : null;
    }


    private void bindSlot(ImageView img, EditText edit, AccountItem item) {
        if (item != null) {
            img.setImageBitmap(item.getIcon().getBitmap());
            edit.setText(item.getName());
        } else {
            IconList iconList = Singleton.instance.getIconList(this);
            img.setImageBitmap(iconList.getUnknownIcon().getBitmap());
            edit.setText("");
        }
    }

    // ── Цифровая клавиатура ───────────────────────────────────────────────────

    /**
     * Операторы в том виде, в каком они лежат в поле. На клавишах подписи покрасивее
     * (× ÷ −), но в текст суммы идёт ASCII: шрифт суммы юникодных знаков может не знать.
     */
    private static final String cOperators = "+-*/";

    public void onKeypadKey(View view) {
        Object tag = view.getTag();
        if (tag == null) return;
        String key = tag.toString();

        Editable text = mKeypadTarget.getText();
        int len = text.length();

        if ("del".equals(key)) {
            if (len > 0) text.delete(len - 1, len);
            return;
        }

        if ("clear".equals(key)) {
            text.clear();
            return;
        }

        if (isOperator(key)) {
            // Знак у суммы свой — тип операции, поэтому выражение начинается с числа.
            if (len == 0) return;
            // Оператор за оператором не копится, а заменяет предыдущий: так исправляют
            // промах, не стирая набранное.
            if (isOperator(text.charAt(len - 1))) text.replace(len - 1, len, key);
            else text.append(key);
            return;
        }

        // Точка и незначащий ноль относятся к числу, которое набирают прямо сейчас,
        // а не ко всей строке: в «120+3» дробная часть у каждого слагаемого своя.
        String operand = currentOperand(text.toString());

        if (".".equals(key)) {
            // Дробная часть либо уже начата, либо не нужна вовсе.
            if (targetPrecision() == 0 || operand.indexOf('.') >= 0) return;
            if (operand.isEmpty()) text.append("0");
            text.append(".");
            return;
        }

        if ("000".equals(key)) {
            // Тысячи дописывают к уже набранному числу: «000» само по себе не сумма.
            // В дробную часть они не влезут — лишние знаки отсечёт фильтр точности.
            if (operand.isEmpty() || "0".equals(operand)) return;
            text.append(key);
            return;
        }

        // Незначащий ноль в начале числа заменяем первой набранной цифрой.
        if ("0".equals(operand)) {
            text.replace(len - 1, len, key);
            return;
        }

        // Фильтр точности отсечёт лишние знаки после запятой сам.
        text.append(key);
    }

    private static boolean isOperator(String key) {
        return key.length() == 1 && isOperator(key.charAt(0));
    }

    private static boolean isOperator(char symbol) {
        return cOperators.indexOf(symbol) >= 0;
    }

    private static boolean hasOperator(String expression) {
        for (int i = 0; i < expression.length(); i++) {
            if (isOperator(expression.charAt(i))) return true;
        }
        return false;
    }

    /** Число, которое набирают прямо сейчас, — всё, что идёт после последнего оператора. */
    private static String currentOperand(String expression) {
        for (int i = expression.length() - 1; i >= 0; i--) {
            if (isOperator(expression.charAt(i))) return expression.substring(i + 1);
        }
        return expression;
    }

    private void updateSaveButton() {
        if (mBtnSave == null) return;
        boolean enabled;
        if (mIsEdit) {
            int curFromId = mAccFrom != null ? mAccFrom.getId() : 0;
            int curToId   = mAccTo   != null ? mAccTo.getId()   : 0;
            int curCommissionId = mAccCommission != null ? mAccCommission.getId() : 0;
            enabled = curFromId != mOrigAccFromId
                    || curToId   != mOrigAccToId
                    || !mAmount.getText().toString().equals(mOrigAmount)
                    || !mComment.getText().toString().equals(mOrigComment)
                    || !mEditDate.getText().toString().equals(mOrigDate)
                    || !mEditTime.getText().toString().equals(mOrigTime)
                    // Связанные части — тоже часть операции.
                    || !mEditAmountTo.getText().toString().equals(mOrigAmountTo)
                    || mCommissionEnabled != mOrigCommissionOn
                    || curCommissionId != mOrigCommissionAccId
                    || !mEditCommissionAmount.getText().toString().equals(mOrigCommissionAmount)
                    || mRadioCommissionFromDest.isChecked() != mOrigCommissionFromDest;
        } else {
            enabled = mAccFrom != null && mAccTo != null
                    && !mAmount.getText().toString().isEmpty();
        }
        mBtnSave.setEnabled(enabled);
        mBtnSave.setAlpha(enabled ? 1f : 0.4f);
    }

    private void ShowCalendarDateTime() {
        mEditDate.setText(String.format("%1$td %1$tb %1$tY", mCalDateTime));
        mEditTime.setText(String.format("%1$tH:%1$tM", mCalDateTime));
        updateSaveButton();
        recomputeAmountTo(); // курс ищется на дату операции — дата сменилась, курс обновляем
    }

    public void btnDateClick(View view) {
        // Время операции задаётся отдельным полем — календарь его не трогает.
        CalendarPicker.show(this, mCalDateTime, (year, month, day) -> {
            mCalDateTime.set(Calendar.YEAR, year);
            mCalDateTime.set(Calendar.MONTH, month);
            mCalDateTime.set(Calendar.DAY_OF_MONTH, day);

            ShowCalendarDateTime();
        });
    }


    public void btnTimeClick(View view) {
        // Launch Time Picker Dialog

        int mHour = mCalDateTime.get(Calendar.HOUR);
        int mMinute = mCalDateTime.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog = new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {

                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay,
                                          int minute) {
                        mCalDateTime.set(Calendar.MINUTE, minute);
                        mCalDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);

                        ShowCalendarDateTime();
                    }
                }, mHour, mMinute, true);
        timePickerDialog.show();
    }


    public void SaveClick(View view) {

        if (mAccFrom == null)  {
           Toast.makeText(this, "Выберите счет списывания средств", Toast.LENGTH_SHORT).show();
           return;
        }

        if (mAccTo == null) {
            Toast.makeText(this, "Выберите счет зачисления средств", Toast.LENGTH_SHORT).show();
            return;
        }

        // Both accounts being categories means no currency → broken data
        if (!(mAccFrom instanceof AccountOperatingItem) && !(mAccTo instanceof AccountOperatingItem)) {
            Toast.makeText(this,
                    "Нельзя создать транзакцию между двумя категориями — хотя бы один счёт должен быть кошельком",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Вторая нога валютного перевода — отдельная проводка, и сумма у неё своя:
        // из курса её только предлагают, подтвердить должен пользователь.
        if (isCrossCurrencyActive() && parseAmount(mEditAmountTo.getText().toString()) <= 0) {
            Toast.makeText(this, "Укажите сумму в валюте счёта-получателя", Toast.LENGTH_LONG).show();
            return;
        }

        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();

        String dateStr = DateUtils.DateToStr(new Date(mCalDateTime.getTimeInMillis()));
        String comment = mComment.getText().toString();
        double amount  = parseAmount(mAmount.getText().toString());

        // На клавиатуре есть вычитание и деление, так что выражение легко сводится
        // к нулю или к минусу — такую сумму в базу пускать нельзя.
        if (amount <= 0) {
            Toast.makeText(this, "Сумма операции должна быть больше нуля", Toast.LENGTH_SHORT).show();
            return;
        }

        values.put(WalletContract.OperationEntry.COLUMN_DATE, dateStr);
        values.put(WalletContract.OperationEntry.COLUMN_FROM, mAccFrom.getId());
        values.put(WalletContract.OperationEntry.COLUMN_TO, mAccTo.getId());
        values.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment);
        values.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount);

        long newRowId = -1;

        if (mIsEdit) {
            newRowId = saveEdit(db, dateStr, comment, amount);

        } else if (mKind == Kind.transfer) {
            newRowId = saveTransfer(db, dateStr, comment, amount);

        } else {
            // uuid — v72 (org/docs/TODO_mobile_sync.md, Фаза 3): единственный прямой
            // db.insert на operation в этом файле, остальные пути (saveTransfer/
            // saveEdit) уже идут через WalletDbCRUD.InsertOperation.
            values.put(WalletContract.OperationEntry.COLUMN_UUID, java.util.UUID.randomUUID().toString());
            newRowId = db.insert(WalletContract.OperationEntry.TABLE_NAME, null, values);
        }

        if (newRowId == -1) {
            // Если ID  -1, значит произошла ошибка
            Toast.makeText(this, "Произошла ошибка при обновлении", Toast.LENGTH_SHORT).show();
        } else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(cTranId, mTranId);
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }

    /**
     * Перевод: одна операция на экране — одна или несколько проводок в базе, связанных
     * через {@code operation_link}.
     *
     * <ul>
     *   <li>одинаковые валюты — одна проводка кошелёк→кошелёк;</li>
     *   <li>разные — две ноги через категорию «Transfer»: у операции одна сумма, а тут
     *   их две, в разных валютах, и вторая берётся из поля по курсу;</li>
     *   <li>комиссия, если включена, — отдельная проводка с кошелька выбранной стороны
     *   на категорию комиссии.</li>
     * </ul>
     *
     * Главная проводка ({@code main_id}) — всегда списание с кошелька-источника; и вторая
     * нога, и комиссия цепляются к ней.
     *
     * @return id главной проводки или -1
     */
    private long saveTransfer(SQLiteDatabase db, String dateStr, String comment, double amount) {
        db.beginTransaction();
        try {
            long mainId;

            if (isCrossCurrencyActive()) {
                int bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                        AccountItem.SystemKind.TRANSFER.getValue(),
                        AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME);
                if (bridgeId <= 0) return -1;

                double amountTo = parseAmount(mEditAmountTo.getText().toString());

                mainId = WalletDbCRUD.InsertOperation(db, dateStr,
                        mAccFrom.getId(), bridgeId, amount, comment);
                long legId = WalletDbCRUD.InsertOperation(db, dateStr,
                        bridgeId, mAccTo.getId(), amountTo, comment);
                if (mainId <= 0 || legId <= 0) return -1;

                WalletDbCRUD.LinkOperations(db, mainId, legId, OperationItem.LinkType.LEG);
            } else {
                mainId = WalletDbCRUD.InsertOperation(db, dateStr,
                        mAccFrom.getId(), mAccTo.getId(), amount, comment);
                if (mainId <= 0) return -1;
            }

            long commissionId = saveCommission(db, dateStr, comment);
            if (commissionId == -1) return -1;
            WalletDbCRUD.LinkOperations(db, mainId, commissionId, OperationItem.LinkType.COMMISSION);

            db.setTransactionSuccessful();
            return mainId;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Сохранение правки. Операция может состоять из нескольких проводок, и правка меняет
     * их все разом: сумму и счета главной, вторую ногу и комиссию — включая случаи, когда
     * нога или комиссия появляются впервые либо, наоборот, больше не нужны (перевод
     * перестал быть валютным, комиссию выключили, тип операции сменили на расход).
     *
     * @return id главной проводки или -1
     */
    private long saveEdit(SQLiteDatabase db, String dateStr, String comment, double amount) {
        db.beginTransaction();
        try {
            boolean cross = mKind == Kind.transfer && isCrossCurrencyActive();

            int bridgeId = -1;
            if (cross) {
                bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                        AccountItem.SystemKind.TRANSFER.getValue(),
                        AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME);
                if (bridgeId <= 0) return -1;
            }

            // У валютного перевода главная проводка идёт на мост, а не на кошелёк-получатель.
            int mainToId = cross ? bridgeId : mAccTo.getId();
            WalletDbCRUD.UpdateOperation(db, mTranId, dateStr, mAccFrom.getId(), mainToId, amount, comment);

            if (cross) {
                double amountTo = parseAmount(mEditAmountTo.getText().toString());
                if (mLegId > 0) {
                    WalletDbCRUD.UpdateOperation(db, mLegId, dateStr, bridgeId, mAccTo.getId(),
                            amountTo, comment);
                } else {
                    long legId = WalletDbCRUD.InsertOperation(db, dateStr, bridgeId, mAccTo.getId(),
                            amountTo, comment);
                    if (legId <= 0) return -1;
                    WalletDbCRUD.LinkOperations(db, mTranId, legId, OperationItem.LinkType.LEG);
                    mLegId = (int) legId;
                }
            } else if (mLegId > 0) {
                // Валюты сравнялись или это уже не перевод — второй ноге больше не место.
                WalletDbCRUD.DeleteOperation(db, mLegId);
                mLegId = -1;
            }

            if (!updateCommission(db, dateStr, comment)) return -1;

            db.setTransactionSuccessful();
            return mTranId;
        } finally {
            db.endTransaction();
        }
    }

    /** @return false при ошибке. Заводит, правит или убирает проводку комиссии. */
    private boolean updateCommission(SQLiteDatabase db, String dateStr, String comment) {
        double commission = parseAmount(mEditCommissionAmount.getText().toString());
        boolean fromDest = mRadioCommissionFromDest.isChecked();
        AccountOperatingItem side = asWallet(fromDest ? mAccTo : mAccFrom);

        boolean wanted = mKind == Kind.transfer && mCommissionEnabled && commission > 0 && side != null;

        if (!wanted) {
            if (mCommissionId > 0) {
                WalletDbCRUD.DeleteOperation(db, mCommissionId);
                mCommissionId = -1;
            }
            return true;
        }

        int categoryId = mAccCommission != null
                ? mAccCommission.getId()
                : WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.getValue(),
                        cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME);
        if (categoryId <= 0) return false;

        if (mCommissionId > 0) {
            WalletDbCRUD.UpdateOperation(db, mCommissionId, dateStr, side.getId(), categoryId,
                    commission, comment);
            return true;
        }

        long id = WalletDbCRUD.InsertOperation(db, dateStr, side.getId(), categoryId, commission, comment);
        if (id <= 0) return false;

        WalletDbCRUD.LinkOperations(db, mTranId, id, OperationItem.LinkType.COMMISSION);
        mCommissionId = (int) id;
        return true;
    }

    /** @return id проводки комиссии, 0 — комиссии нет, -1 — ошибка. */
    private long saveCommission(SQLiteDatabase db, String dateStr, String comment) {
        if (!mCommissionEnabled) return 0;

        double commission = parseAmount(mEditCommissionAmount.getText().toString());
        if (commission <= 0) return 0;

        // Комиссию платит та сторона перевода, которую выбрал пользователь.
        boolean fromDest = mRadioCommissionFromDest.isChecked();
        AccountOperatingItem side = asWallet(fromDest ? mAccTo : mAccFrom);
        if (side == null) return 0;

        int categoryId = mAccCommission != null
                ? mAccCommission.getId()
                : WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.getValue(),
                        cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME);
        if (categoryId <= 0) return -1;

        long id = WalletDbCRUD.InsertOperation(db, dateStr, side.getId(), categoryId,
                commission, comment);
        return id > 0 ? id : -1;
    }

    // ── Выбор счетов ──────────────────────────────────────────────────────────

    public void onPickTop(View view) {
        AccountItem current = topIsFrom() ? mAccFrom : mAccTo;
        // Для перевода сверху кошелёк «Откуда», иначе — статья расхода/дохода.
        int type = mKind == Kind.transfer
                ? AccountPickerActivity.cSelectedTypeOperationAccounts
                : AccountPickerActivity.cSelectedTypeChargeAccounts;
        openAccountPicker(mEditSlotTop.getHint().toString(), current, type, AccountSelectTop);
    }

    public void onPickBottom(View view) {
        AccountItem current = topIsFrom() ? mAccTo : mAccFrom;
        openAccountPicker(mEditSlotBottom.getHint().toString(), current,
                AccountPickerActivity.cSelectedTypeOperationAccounts, AccountSelectBottom);
    }

    /**
     * Заголовок пикера — подпись самого слота («Статья расхода», «Куда», …): тип счёта
     * там уже задан, и переключателя категорий/кошельков на экране выбора не будет.
     */
    private void openAccountPicker(String title, AccountItem current, int selectedType, int requestCode) {
        startActivityForResult(AccountPickerActivity.buildIntent(this, title, selectedType,
                current == null ? 0 : current.getId()), requestCode);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) return;

        int accountId = data.getIntExtra(AccountPickerActivity.cAccountId, -1);
        if (accountId == -1) {
            Snackbar.make(this.getWindow().getDecorView().getRootView(), "Счет не был получен", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
            return;
        }

        AccountItem picked = accountById(accountId);

        if (requestCode == AccountSelectCommission) {
            mAccCommission = picked;
            bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission);
            return;
        }

        boolean intoFrom = (requestCode == AccountSelectTop) == topIsFrom();

        if (intoFrom) mAccFrom = picked;
        else          mAccTo = picked;

        // Счёт поменяли — курс нужно предложить заново, а не держать старое ручное значение.
        mAmountToUserEdited = false;

        renderSlots();
        updateSaveButton();
    }

    // ── Комиссия за перевод ───────────────────────────────────────────────────

    public void onToggleCommission(View view) {
        mCommissionEnabled = !mCommissionEnabled;
        applySwitchState(mSwitchCommission, mCommissionEnabled);
        mGroupCommissionDetails.setVisibility(mCommissionEnabled ? View.VISIBLE : View.GONE);

        if (mCommissionEnabled) {
            // Категорию подставляем сразу, но менять её можно — см. onPickCommissionAccount.
            if (mAccCommission == null) {
                mAccCommission = defaultCommissionAccount();
                bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission);
            }
            updateCommissionCurrency();
        } else if (mKeypadTarget == mEditCommissionAmount) {
            setKeypadTarget(mAmount);
        }
    }

    /**
     * Категория комиссии по умолчанию. В старых базах её может не быть — начальный
     * справочник накатывался до того, как комиссия появилась, — поэтому заводим на месте
     * и обновляем справочник в памяти, иначе строка осталась бы без имени и иконки.
     */
    private AccountItem defaultCommissionAccount() {
        AccountList accounts = Singleton.instance.getAccounts(this);

        AccountItem existing = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER_COMMISSION);
        if (existing != null) return existing;

        int id = WalletDbCRUD.GetOrCreateSystemCategory(mDbHelper.getWritableDatabase(),
                AccountItem.SystemKind.TRANSFER_COMMISSION.getValue(),
                cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME);
        if (id <= 0) return null;

        WalletDbLoader.LoadAccounts(mDbHelper.getReadableDatabase(), accounts,
                Singleton.instance.getCurrencyList(this),
                Singleton.instance.getIconList(this), null);
        return accounts.getAccountById(id);
    }

    public void onPickCommissionAccount(View view) {
        openAccountPicker("Счёт списания комиссии", mAccCommission,
                AccountPickerActivity.cSelectedTypeChargeAccounts, AccountSelectCommission);
    }

    public void onCommissionSideChanged(View view) {
        updateCommissionCurrency();
    }

    /**
     * Блок комиссии есть только у перевода. На других вкладках он просто сворачивается:
     * набранное не стираем — вернётся вместе со вкладкой, как сумма и счета.
     */
    private void updateCommissionVisibility() {
        boolean isTransfer = mKind == Kind.transfer;
        mRowCommissionToggle.setVisibility(isTransfer ? View.VISIBLE : View.GONE);
        mGroupCommissionDetails.setVisibility(isTransfer && mCommissionEnabled ? View.VISIBLE : View.GONE);

        // Поля спрятали — клавиатуре нужна другая цель, иначе цифры уходят в никуда.
        if (!isTransfer && mKeypadTarget == mEditCommissionAmount) setKeypadTarget(mAmount);
    }

    /** Комиссия снимается с того же кошелька, что выбран как сторона списания. */
    private void updateCommissionCurrency() {
        boolean fromDest = mRadioCommissionFromDest != null && mRadioCommissionFromDest.isChecked();
        AccountOperatingItem side = asWallet(fromDest ? mAccTo : mAccFrom);
        mTvCurrencySymbolCommission.setText(side != null ? side.getCurrency().getSymbol() : "");
        if (side != null) {
            mCommissionPrecision = side.getCurrency().getPrecision();
            applyDecimalFilter(mEditCommissionAmount, mCommissionPrecision);
        }
    }

    /** Копия переключателя из SettingsActivity: двигает бегунок и красит фон пилюли. */
    private void applySwitchState(FrameLayout switchView, boolean on) {
        switchView.setTag(on);
        switchView.setBackgroundResource(on ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        View thumb = switchView.getChildAt(0);
        if (thumb == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.gravity = on ? android.view.Gravity.END : android.view.Gravity.START;
        thumb.setLayoutParams(lp);
    }

    // ── Перевод между разновалютными счетами: сумма во второй валюте ───────────

    private boolean isCrossCurrencyActive() {
        AccountOperatingItem from = asWallet(mAccFrom);
        AccountOperatingItem to   = asWallet(mAccTo);
        return mKind == Kind.transfer && from != null && to != null
                && from.getCurrency().getId() != to.getCurrency().getId();
    }

    /** Показывает/скрывает второе поле суммы и подбирает под него точность/символ валюты. */
    private void updateCrossCurrencyUI() {
        if (!isCrossCurrencyActive()) {
            // Только прячем: набранную вручную сумму не стираем, иначе она пропадала бы от
            // одного переключения вкладки. Скрытое поле никуда не идёт — ни в базу, ни в
            // расчёты, — а сбрасывает его смена кошелька (см. onActivityResult).
            mRowAmountTo.setVisibility(View.GONE);
            // Поле спрятали — клавиатуре нужна другая цель, иначе цифры уходят в никуда.
            if (mKeypadTarget == mEditAmountTo) setKeypadTarget(mAmount);
            return;
        }

        Currency curTo = asWallet(mAccTo).getCurrency();
        mCurrencyPrecisionTo = curTo.getPrecision();
        mTvCurrencySymbolTo.setText(curTo.getSymbol());
        mEditAmountTo.setHint(zeroHint(mCurrencyPrecisionTo));
        applyDecimalFilter(mEditAmountTo, mCurrencyPrecisionTo);
        mRowAmountTo.setVisibility(View.VISIBLE);

        recomputeAmountTo();
    }

    /**
     * Ищет курс на дату операции и, если пользователь ещё не тронул поле сам,
     * подставляет туда сумму по курсу. Дата/сумма/счета изменились — курс и подсказка
     * обновляются в любом случае, а значение поля — только пока оно не введено вручную.
     */
    private void recomputeAmountTo() {
        if (!isCrossCurrencyActive()) return;

        Currency curFrom = asWallet(mAccFrom).getCurrency();
        Currency curTo   = asWallet(mAccTo).getCurrency();

        WalletDbLoader.RateOnDate rate = WalletDbLoader.LoadRateOnDate(mDbHelper.getReadableDatabase(),
                curFrom.getId(), curTo.getId(), mCalDateTime.getTime());

        if (rate == null) {
            mTvRateInfo.setText("Курс на " + String.format("%1$td.%1$tm.%1$tY", mCalDateTime)
                    + " не найден — введите сумму сами");
            // Без курса подставить нечего, а прежнее значение осталось от другой пары
            // валют — показывать его как сумму зачисления нельзя.
            if (!mAmountToUserEdited) setAmountToProgrammatically("");
            return;
        }

        // Дата — та, на которую курс реально стоит: ближайшая не позже даты операции,
        // а не сама дата операции. Точность — инструмента, а не валюты: у пары она
        // своя (currency_pair.precision), и двух знаков курсу обычно мало.
        mTvRateInfo.setText("Курс на " + String.format("%1$td.%1$tm.%1$tY", rate.date)
                + ": 1 " + curFrom.getIso() + " = "
                + formatAmount(rate.rate, ratePrecision(curFrom, curTo)) + " " + curTo.getIso());

        if (mAmountToUserEdited) return;

        double fromAmount = parseAmount(mAmount.getText().toString());
        setAmountToProgrammatically(fromAmount > 0 ? formatAmount(fromAmount * rate.rate, mCurrencyPrecisionTo) : "");
    }

    /**
     * Точность курса — свойство валютной пары ({@code currency_pair.precision}), а не
     * валют: курс сам по себе не сумма, и округление до копеек его портит.
     *
     * Пара ищется в любом направлении: обратный курс считает {@code v_currency_conversion_rate},
     * а точность у пары одна. Если прямой пары нет — курс пришёл обходным путём через
     * {@code currency_conversion}, и остаётся значение по умолчанию, как у колонки.
     */
    private int ratePrecision(Currency from, Currency to) {
        CurrencyPairList pairs = Singleton.instance.getCurrencyPairList(this);

        for (int i = 0; i < pairs.size(); i++) {
            CurrencyPair pair = pairs.getItem(i);
            Currency base = pair.getBaseCurrency();
            Currency quote = pair.getQuoteCurrency();
            if (base == null || quote == null) continue;

            boolean direct  = base.getId() == from.getId() && quote.getId() == to.getId();
            boolean reverse = base.getId() == to.getId() && quote.getId() == from.getId();
            if (direct || reverse) return pair.getPrecision();
        }
        return cDefaultRatePrecision;
    }

    private void setAmountToProgrammatically(String text) {
        mUpdatingAmountToProgrammatically = true;
        mEditAmountTo.setText(text);
        mUpdatingAmountToProgrammatically = false;
    }

    /**
     * В поле суммы лежит не обязательно число: клавиатура умеет + − × ÷, и там может
     * быть выражение вида {@code 1200+35*2}. Незаконченный хвост («1200+») отбрасываем —
     * пользователь просто ещё не дописал, а деление на ноль и мусор дают 0.
     */
    private double parseAmount(String raw) {
        if (raw == null) return 0;

        String expression = raw.replace(",", "").replace(" ", "");
        while (!expression.isEmpty() && isOperator(expression.charAt(expression.length() - 1))) {
            expression = expression.substring(0, expression.length() - 1);
        }
        if (expression.isEmpty()) return 0;

        double value = evaluate(expression);
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
    }

    /**
     * Значение выражения из чисел и операторов {@link #cOperators}, приоритет обычный:
     * умножение и деление раньше сложения и вычитания. Разбор в один проход — цепочка
     * × ÷ копится в {@code term}, а в {@code total} слагаемое уходит только на + или −.
     *
     * @return NaN, если строка не выражение или делят на ноль
     */
    private double evaluate(String expression) {
        double total = 0;
        double term = 0;
        char addOp = '+';
        char mulOp = 0;

        int i = 0;
        while (i < expression.length()) {
            int start = i;
            while (i < expression.length() && !isOperator(expression.charAt(i))) i++;

            double value;
            try {
                value = Double.parseDouble(expression.substring(start, i));
            } catch (NumberFormatException e) {
                return Double.NaN;
            }

            if (mulOp == '*')      term = term * value;
            else if (mulOp == '/') term = value == 0 ? Double.NaN : term / value;
            else                   term = value;

            if (i == expression.length()) break;

            char op = expression.charAt(i++);
            if (op == '*' || op == '/') {
                mulOp = op;
            } else {
                total = addOp == '+' ? total + term : total - term;
                addOp = op;
                mulOp = 0;
            }
        }

        return addOp == '+' ? total + term : total - term;
    }

    private String formatAmount(double value, int precision) {
        return String.format(Locale.US, "%." + precision + "f", value);
    }

    private void updateAmountFilter() {
        mAmount.setHint(zeroHint(mCurrencyPrecision));
        applyDecimalFilter(mAmount, mCurrencyPrecision);
        updateAmountPreview(); // точность сменилась — результат под суммой тоже
    }

    /**
     * Пока в сумме выражение, подпись под ней показывает, что получится: иначе
     * результат виден только после сохранения.
     */
    private void updateAmountPreview() {
        String text = mAmount.getText().toString();
        // Выражение длиннее числа, а поле по ширине не тянется — иначе обрежет (sp, как в разметке).
        mAmount.setTextSize(hasOperator(text) ? 30 : 40);
        mAmountHint.setText(hasOperator(text)
                ? "= " + formatAmount(parseAmount(text), mCurrencyPrecision)
                : mAmountHintText);
    }

    private String zeroHint(int precision) {
        StringBuilder hint = new StringBuilder("0");
        if (precision > 0) {
            hint.append(".");
            for (int i = 0; i < precision; i++) hint.append("0");
        }
        return hint.toString();
    }

    private void applyDecimalFilter(EditText target, int precision) {
        target.setFilters(new InputFilter[]{new InputFilter() {
            @Override
            public CharSequence filter(CharSequence src, int start, int end,
                                       Spanned dst, int dstart, int dend) {
                // Build the resulting string after the proposed edit
                String result = dst.subSequence(0, dstart).toString()
                        + src.subSequence(start, end)
                        + dst.subSequence(dend, dst.length());
                if (result.isEmpty() || result.equals(".")) return null;
                // Точность проверяем у каждого числа выражения отдельно: «10.50+2.25»
                // целиком под правило «не больше precision знаков» не подходит.
                for (String operand : result.split("[+\\-*/]", -1)) {
                    int dotIdx = operand.indexOf('.');
                    if (dotIdx >= 0) {
                        int decimals = operand.length() - dotIdx - 1;
                        if (decimals > precision) return "";
                    }
                }
                return null; // accept
            }
        }});
    }
}
