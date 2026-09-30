package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

public class AccountItemActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ICON = 2;

    private ImageView imageAccView;
    private ImageView imageCurencyView;
    private TextView tvCurrencyLabel;
    private EditText editAccName;
    private EditText editComment;
    private CheckBox checkIsArchive;
    private LinearLayout operatingGroup;
    private Button btnDelete;

    private AccountItem account;
    private int selectedIconId = -1;
    private Currency selectedCurrency;
    private Button btnSave;

    // Original values for change detection
    private String originalName;
    private String originalComment;
    private boolean originalArchived;
    private int originalCurrencyId = -1;
    private int originalIconId = -1;

    public static final String ARG_ACCOUNT_ID = "AccountItemActivity.account_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_item);



        imageAccView    = findViewById(R.id.imageAccView);
        imageCurencyView= findViewById(R.id.imageCurencyView);
        tvCurrencyLabel = findViewById(R.id.tvCurrencyLabel);
        editAccName     = findViewById(R.id.editAccName);
        editComment     = findViewById(R.id.editComment);
        checkIsArchive  = findViewById(R.id.checkIsArchive);
        operatingGroup  = findViewById(R.id.operatingGroup);
        btnDelete       = findViewById(R.id.btnDelete);
        btnSave         = findViewById(R.id.btnSave);

        account = Singleton.instance.getAccounts(this)
                .getAccountById(getIntent().getIntExtra(ARG_ACCOUNT_ID, 0));

        if (account == null) { finish(); return; }

        // Remember originals before populating fields
        originalName     = account.getName();
        originalComment  = account.getComment();
        originalArchived = account.getIsArchived();
        originalIconId   = account.getIcon().getId();
        selectedIconId   = originalIconId;

        imageAccView.setImageBitmap(account.getIcon().getBitmap());
        editAccName.setText(account.getName());
        editComment.setText(account.getComment());
        checkIsArchive.setChecked(account.getIsArchived());

        if (account instanceof AccountOperatingItem) {
            setScreenTitle("Кошелёк");
            selectedCurrency = ((AccountOperatingItem) account).getCurrency();
            originalCurrencyId = selectedCurrency.getId();
            operatingGroup.setVisibility(View.VISIBLE);
            refreshCurrencyUI();
        } else {
            setScreenTitle("Категория");
            operatingGroup.setVisibility(View.GONE);
        }

        SQLiteDatabase db = new WalletDbHelper(this).getReadableDatabase();
        if (!WalletDbCRUD.HasOperations(db, account.getId()) && !account.isSystem()) {
            btnDelete.setVisibility(View.VISIBLE);
        }

        if (account.isSystem()) {
            // Системный счёт опознаётся по имени, поэтому переименование пришлось бы
            // считать удалением: правило обошли бы в один шаг.
            editAccName.setEnabled(false);
            editComment.setHint(account.isSystemCommission()
                    ? "Системная категория — на неё по умолчанию уходит комиссия за перевод, "
                            + "поэтому её нельзя удалить или переименовать"
                    : "Системная категория — переводы между разными валютами "
                            + "проходят через неё, поэтому её нельзя удалить или переименовать");
        }

        // Save is disabled until something changes
        btnSave.setEnabled(false);
        btnSave.setAlpha(0.4f);

        TextWatcher changeWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSaveButton(); }
        };
        editAccName.addTextChangedListener(changeWatcher);
        editComment.addTextChangedListener(changeWatcher);

        checkIsArchive.setOnCheckedChangeListener(
                (CompoundButton b, boolean checked) -> updateSaveButton());
    }

    private void setScreenTitle(String title) {
        ((TextView) findViewById(R.id.screenTitle)).setText(title);
    }

    public void btnUp(View view) {
        finish();
    }

    private void updateSaveButton() {
        boolean changed =
                !editAccName.getText().toString().trim().equals(originalName)
                || !editComment.getText().toString().trim().equals(originalComment)
                || checkIsArchive.isChecked() != originalArchived
                || selectedIconId != originalIconId
                || (selectedCurrency != null && selectedCurrency.getId() != originalCurrencyId);
        btnSave.setEnabled(changed);
        btnSave.setAlpha(changed ? 1f : 0.4f);
    }

    /** Справочник иконок вместо галереи — см. AddCategoryActivity.pickIconClick. */
    public void pickIconClick(View view) {
        String title = account instanceof AccountOperatingItem ? "Иконка кошелька" : "Иконка категории";
        startActivityForResult(
                IconPickerActivity.buildIntent(this, title, Icon.IconType.CATEGORY, selectedIconId),
                REQUEST_PICK_ICON);
    }

    public void pickCurrencyClick(View view) {
        CurrencyList currencies = Singleton.instance.getCurrencyList(this);
        String[] items = new String[currencies.size()];
        for (int i = 0; i < currencies.size(); i++) {
            Currency c = currencies.get(i);
            items[i] = c.getIso() + "  —  " + c.getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите валюту")
                .setItems(items, (dialog, which) -> {
                    selectedCurrency = currencies.get(which);
                    refreshCurrencyUI();
                    updateSaveButton();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return;

        selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId);

        Icon icon = Singleton.instance.getIconList(this).getIconById(selectedIconId);
        if (icon != null) imageAccView.setImageBitmap(icon.getBitmap());
        updateSaveButton();
    }

    public void btnSaveClick(View view) {
        String name = editAccName.getText().toString().trim();
        if (name.isEmpty()) { editAccName.setError("Введите название"); return; }

        // Поле имени у системного счёта отключено, но исходное имя всё равно
        // навязываем: правило опознаёт системность именно по нему.
        if (account.isSystem()) name = originalName;

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();

        int iconId = selectedIconId > 0 ? selectedIconId : account.getIcon().getId();

        WalletDbCRUD.UpdateAccount(db, account.getId(), name,
                editComment.getText().toString().trim(), iconId, checkIsArchive.isChecked());

        if (account instanceof AccountOperatingItem && selectedCurrency != null) {
            WalletDbCRUD.UpdateAccountCurrency(db, account.getId(), selectedCurrency.getId());
        }

        reloadSingleton();
        setResult(RESULT_OK);
        finish();
    }

    /**
     * Кнопка видна, только когда удалить можно, но решение всё равно за
     * {@link WalletDbCRUD#DeleteAccount}: операция по счёту могла появиться, пока экран
     * открыт. Отказ объясняем — причин две, и они разные.
     */
    public void btnDeleteClick(View view) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить")
                .setMessage("Удалить «" + account.getName() + "»?")
                .setPositiveButton("Удалить", (d, w) -> deleteAccount())
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Мягкое удаление ({@link WalletDbCRUD#DeleteAccount}) — долетит до сервера/других устройств следующим синком. */
    private void deleteAccount() {
        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        WalletDbCRUD.DeleteAccountResult result = WalletDbCRUD.DeleteAccount(db, account.getId());

        if (result != WalletDbCRUD.DeleteAccountResult.deleted) {
            Toast.makeText(this, deleteRefusedMessage(result), Toast.LENGTH_LONG).show();
            return;
        }
        reloadSingleton();
        setResult(RESULT_OK);
        finish();
    }

    private String deleteRefusedMessage(WalletDbCRUD.DeleteAccountResult result) {
        // Системным бывает только счёт из двух зашитых по имени, а это категории.
        if (result == WalletDbCRUD.DeleteAccountResult.systemAccount) {
            return "«" + account.getName() + "» — системная категория, её нельзя удалить";
        }
        return "По «" + account.getName() + "» есть операции — удалить нельзя, "
                + "иначе они пропадут вместе с ней";
    }

    private void refreshCurrencyUI() {
        if (selectedCurrency == null) return;
        imageCurencyView.setImageBitmap(selectedCurrency.getIcon().getBitmap());
        tvCurrencyLabel.setText(selectedCurrency.getIso() + "  —  " + selectedCurrency.getName());
    }

    private void reloadSingleton() {
        SQLiteDatabase rdb = new WalletDbHelper(this).getReadableDatabase();
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this));
        WalletDbLoader.LoadAccounts(rdb,
                Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this),
                Singleton.instance.getIconList(this),
                null);
    }
}
