package com.example.wallettransactions.Activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.DebtsList;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Карточка контрагента (openspec counterparty-card, спека debts «Карточка контрагента»): иконка,
 * имя, комментарий, контакт телефона, итог по займам и список займов. Тап по займу открывает
 * карточку этого долга-кошелька ({@link AccountItemActivity}), где операции займа доступны
 * кнопкой-фильтром. Всё локальное: без сети карточка работает так же.
 *
 * <p>Имя и комментарий сохраняются при уходе с экрана ({@link #onPause}); дубль имени — отказ и
 * прежнее имя. Контакт привязывается системным {@code ACTION_PICK} без разрешения
 * {@code READ_CONTACTS}, поэтому имя контакта не читается — карточка показывает только факт привязки.
 */
public class CounterpartyCardActivity extends AppCompatActivity {

    public static final String ARG_COUNTERPARTY_ID = "CounterpartyCardActivity.counterparty_id";

    private static final int REQUEST_PICK_ICON = 421;
    private static final int REQUEST_PICK_CONTACT = 422;

    private int counterpartyId;
    private String uuid;
    private boolean archived;
    private String savedName;
    private String savedComment;

    private ImageView iconView;
    private EditText nameEdit;
    private EditText commentEdit;
    private TextView contactStatus;
    private Button btnBind;
    private Button btnOpen;
    private Button btnUnbind;
    private TextView totalView;
    private LinearLayout loansBox;
    private Button btnArchive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counterparty_card);
        ((TextView) findViewById(R.id.screenTitle)).setText(R.string.counterparty_card_title);

        iconView = findViewById(R.id.counterpartyIcon);
        nameEdit = findViewById(R.id.counterpartyName);
        commentEdit = findViewById(R.id.counterpartyComment);
        contactStatus = findViewById(R.id.counterpartyContactStatus);
        btnBind = findViewById(R.id.btnContactBind);
        btnOpen = findViewById(R.id.btnContactOpen);
        btnUnbind = findViewById(R.id.btnContactUnbind);
        totalView = findViewById(R.id.counterpartyTotal);
        loansBox = findViewById(R.id.counterpartyLoans);
        btnArchive = findViewById(R.id.btnCounterpartyArchive);

        counterpartyId = getIntent().getIntExtra(ARG_COUNTERPARTY_ID, 0);
        CounterpartyItem item = load();
        if (item == null) { // контрагента удалили (например, синком), пока список был открыт
            finish();
            return;
        }
        uuid = item.uuid;
        nameEdit.setText(item.name);
        commentEdit.setText(item.comment);
        savedName = item.name;
        savedComment = item.comment;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uuid == null) return;
        // Вернулись из карточки займа или выбора контакта: займы, контакт и иконку перечитываем,
        // поля имени и комментария не трогаем — пользователь мог не закончить правку.
        CounterpartyItem item = load();
        if (item == null) {
            finish();
            return;
        }
        archived = item.isArchived;
        renderIcon(item);
        renderContact();
        renderArchiveButton();
        renderLoans();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveEdits();
    }

    public void btnUp(View view) {
        finish();
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    private CounterpartyItem load() {
        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db())) {
            if (c.id == counterpartyId) return c;
        }
        return null;
    }

    private void changed() {
        PeriodFilterState.get(this).markDataChanged();
    }

    // ── имя и комментарий ─────────────────────────────────────────────────────

    /** Имя с проверкой дубля (прежнее остаётся) и комментарий; вызывается при уходе с экрана. */
    private void saveEdits() {
        if (uuid == null) return;
        SQLiteDatabase db = db();
        String name = WalletDbCRUD.NormalizeCounterpartyName(nameEdit.getText().toString());
        if (!name.isEmpty() && !name.equals(savedName)) {
            if (WalletDbCRUD.RenameCounterparty(db, counterpartyId, name)) {
                savedName = name;
                changed();
            } else {
                Toast.makeText(this, getString(R.string.counterparty_name_taken, name), Toast.LENGTH_LONG).show();
                nameEdit.setText(savedName);
            }
        } else if (name.isEmpty()) {
            nameEdit.setText(savedName);
        }
        String comment = commentEdit.getText().toString().trim();
        if (!comment.equals(savedComment)) {
            WalletDbCRUD.UpdateCounterpartyComment(db, counterpartyId, comment);
            savedComment = comment;
            changed();
        }
    }

    // ── иконка ────────────────────────────────────────────────────────────────

    public void pickIconClick(View view) {
        CounterpartyItem item = load();
        int current = item != null ? WalletDbCRUD.IconIdByUuid(db(), item.iconUuid) : -1;
        if (current <= 0) current = Singleton.instance.getIconList(this).getUnknownIcon().getId();
        startActivityForResult(IconPickerActivity.buildIntent(this,
                getString(R.string.icon_title_counterparty), Icon.IconType.CATEGORY, current), REQUEST_PICK_ICON);
    }

    private void renderIcon(CounterpartyItem item) {
        int iconId = WalletDbCRUD.IconIdByUuid(db(), item.iconUuid);
        Icon icon = Singleton.instance.getIconList(this).getIconByIdOrUnknown(iconId > 0 ? iconId : null);
        iconView.setImageBitmap(icon != null ? icon.getBitmap() : null);
    }

    // ── контакт телефона ──────────────────────────────────────────────────────

    private void renderContact() {
        boolean linked = WalletDbCRUD.GetCounterpartyContact(db(), uuid) != null;
        contactStatus.setText(linked ? R.string.counterparty_contact_linked : R.string.counterparty_contact_none);
        btnBind.setText(linked ? R.string.counterparty_contact_change : R.string.counterparty_contact_bind);
        btnOpen.setVisibility(linked ? View.VISIBLE : View.GONE);
        btnUnbind.setVisibility(linked ? View.VISIBLE : View.GONE);
        // Одна кнопка «Привязать» занимает весь ряд — без зазора справа, как у остальных.
        LinearLayout.LayoutParams bindParams = (LinearLayout.LayoutParams) btnBind.getLayoutParams();
        bindParams.setMarginEnd(linked ? dp(8) : 0);
        btnBind.setLayoutParams(bindParams);
    }

    /** «Привязать» / «Сменить»: системный выбор контакта, без разрешения на чтение контактов. */
    public void onContactBind(View view) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                    REQUEST_PICK_CONTACT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show();
        }
    }

    public void onContactOpen(View view) {
        String lookup = WalletDbCRUD.GetCounterpartyContact(db(), uuid);
        if (lookup == null) return;
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show();
        }
    }

    public void onContactUnbind(View view) {
        WalletDbCRUD.ClearCounterpartyContact(db(), uuid);
        renderContact();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Отмена выбора — ничего не меняем (спека «Связь контрагента с контактом»).
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_PICK_ICON) {
            int iconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, -1);
            if (iconId > 0 && WalletDbCRUD.SetCounterpartyIcon(db(), counterpartyId, iconId)) changed();
        } else if (requestCode == REQUEST_PICK_CONTACT && data.getData() != null) {
            String lookup = null;
            try (Cursor c = getContentResolver().query(data.getData(),
                    new String[]{ContactsContract.Contacts.LOOKUP_KEY}, null, null, null)) {
                if (c != null && c.moveToFirst()) lookup = c.getString(0);
            } catch (SecurityException e) {
                lookup = null;
            }
            if (lookup != null && !lookup.isEmpty()) WalletDbCRUD.SetCounterpartyContact(db(), uuid, lookup);
        }
        // onResume перерисует иконку и контакт.
    }

    // ── займы ─────────────────────────────────────────────────────────────────

    private void renderLoans() {
        SQLiteDatabase db = db();
        Currency eq = PeriodFilterState.get(this).getCurrencyEq(this);
        Map<Integer, AccountOperatingItem> wallets = new HashMap<>();
        List<DebtsList.Loan> loans = DebtsActivity.loadLoans(this, db, eq, DebtsActivity.asOfTomorrow(), wallets);
        DebtsList.Group group = DebtsList.groupFor(loans, DebtsActivity.counterpartyNames(db), uuid);

        loansBox.removeAllViews();
        if (group == null) {
            totalView.setVisibility(View.GONE);
            loansBox.addView(text(getString(R.string.counterparty_no_loans), 14, R.color.colorTextSecondary, false));
            return;
        }
        if (eq != null) {
            totalView.setVisibility(View.VISIBLE);
            AdapterUtils.setAmountText(totalView, group.totalEq, eq, group.approx, group.incomplete);
        } else {
            totalView.setVisibility(View.GONE);
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        for (DebtsList.Loan loan : group.loans) loansBox.addView(loanRow(loan, wallets.get(loan.accountId), today));
    }

    private View loanRow(DebtsList.Loan loan, AccountOperatingItem wallet, String today) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(loan.name, 14, loan.archived ? R.color.colorTextSecondary : R.color.colorTextPrimary, false);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView amount = text("", 14, R.color.colorTextPrimary, true);
        AdapterUtils.setAmountText(amount, loan.balance, wallet != null ? wallet.getCurrency() : null, false);
        line.addView(amount);
        row.addView(line);

        String note = loan.isClosed() ? getString(R.string.debts_closed)
                : loan.isOverdue(today) ? getString(R.string.debts_overdue, loan.dueDate)
                : loan.dueDate != null ? getString(R.string.debts_due, loan.dueDate) : null;
        if (loan.archived) {
            String archivedMark = getString(R.string.counterparty_loan_archived);
            note = note == null ? archivedMark : note + " · " + archivedMark;
        }
        if (!loan.comment.isEmpty()) note = note == null ? loan.comment : note + " · " + loan.comment;
        if (note != null) {
            row.addView(text(note, 12, loan.isOverdue(today) ? R.color.colorexpanse : R.color.colorTextSecondary,
                    loan.isOverdue(today)));
        }

        // Заём — это долг-кошелёк: открываем его карточку, а операции — её кнопкой-фильтром.
        row.setOnClickListener(v -> startActivity(new Intent(this, AccountItemActivity.class)
                .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, loan.accountId)));
        return row;
    }

    // ── архив и удаление ──────────────────────────────────────────────────────

    private void renderArchiveButton() {
        btnArchive.setText(archived ? R.string.counterparty_unarchive : R.string.counterparty_archive);
    }

    public void onToggleArchive(View view) {
        archived = !archived;
        WalletDbCRUD.ArchiveCounterparty(db(), counterpartyId, archived);
        changed();
        renderArchiveButton();
    }

    public void onDelete(View view) {
        // Спека «Удаление контрагента с долгом»: не удаляем, объясняем почему, карточка остаётся.
        WalletDbCRUD.DeleteCounterpartyResult result = WalletDbCRUD.DeleteCounterparty(db(), counterpartyId);
        if (result == WalletDbCRUD.DeleteCounterpartyResult.hasDebts) {
            Toast.makeText(this, getString(R.string.counterparty_delete_has_debts, savedName), Toast.LENGTH_LONG).show();
            return;
        }
        changed();
        uuid = null; // onPause не должен писать правки в удалённого контрагента
        finish();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
