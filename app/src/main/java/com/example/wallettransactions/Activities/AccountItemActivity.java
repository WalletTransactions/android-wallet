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

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

public class AccountItemActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ICON = 2;
    private static final int REQUEST_PICK_WRITEOFF_CATEGORY = 3;

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
    /** Признак актива на входе (capital-assets); null — обычный кошелёк или категория. */
    private AccountOperatingItem.AssetInfo originalAsset;
    private AssetFields assetFields;
    /** Лист «Списать остаток?», пока открыт (archive-wallet-writeoff). */
    private ArchiveWriteOffDialog writeOffDialog;

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
            setScreenTitle(getString(R.string.account_title_wallet));
            selectedCurrency = ((AccountOperatingItem) account).getCurrency();
            originalCurrencyId = selectedCurrency.getId();
            operatingGroup.setVisibility(View.VISIBLE);
            refreshCurrencyUI();
            originalAsset = ((AccountOperatingItem) account).getAsset();
            assetFields = new AssetFields(this, originalAsset, true, this::updateSaveButton);
            // «Переоценить» — только у актива, сохранённого как актив (не у только что отмеченного).
            findViewById(R.id.btnRevaluate).setVisibility(originalAsset != null ? View.VISIBLE : View.GONE);
        } else {
            setScreenTitle(getString(R.string.account_title_category));
            operatingGroup.setVisibility(View.GONE);
        }

        SQLiteDatabase db = new WalletDbHelper(this).getReadableDatabase();
        if (!WalletDbCRUD.HasOperations(db, account.getId()) && !account.isSystem()) {
            btnDelete.setVisibility(View.VISIBLE);
        }
        // Перенос операций в актив — у обычной категории, если активы вообще есть.
        boolean canMove = !account.isOperatingAccount() && !account.isSystem() && WalletDbCRUD.HasAssets(db);
        findViewById(R.id.btnMoveToAsset).setVisibility(canMove ? View.VISIBLE : View.GONE);

        if (account.isSystem()) {
            // Системный счёт опознаётся по имени, поэтому переименование пришлось бы
            // считать удалением: правило обошли бы в один шаг.
            editAccName.setEnabled(false);
            editComment.setHint(account.isSystemCommission()
                    ? R.string.account_system_commission_hint
                    : account.isSystemRevaluation()
                    ? R.string.account_system_revaluation_hint
                    : R.string.account_system_transfer_hint);
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
                || (selectedCurrency != null && selectedCurrency.getId() != originalCurrencyId)
                || (assetFields != null && assetFields.differsFrom(originalAsset));
        btnSave.setEnabled(changed);
        btnSave.setAlpha(changed ? 1f : 0.4f);
    }

    /** Справочник иконок вместо галереи — см. AddCategoryActivity.pickIconClick. */
    public void pickIconClick(View view) {
        String title = getString(account instanceof AccountOperatingItem
                ? R.string.icon_title_wallet : R.string.icon_title_category);
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
                .setTitle(R.string.account_choose_currency)
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
        if (requestCode == REQUEST_PICK_WRITEOFF_CATEGORY) {
            if (resultCode != RESULT_OK || data == null || writeOffDialog == null) return;
            int id = data.getIntExtra(AccountPickerActivity.cAccountId, 0);
            writeOffDialog.setCategory(Singleton.instance.getAccounts(this).getAccountById(id));
            return;
        }
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return;

        selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId);

        Icon icon = Singleton.instance.getIconList(this).getIconById(selectedIconId);
        if (icon != null) imageAccView.setImageBitmap(icon.getBitmap());
        updateSaveButton();
    }

    public void btnSaveClick(View view) {
        String name = editAccName.getText().toString().trim();
        if (name.isEmpty()) { editAccName.setError(getString(R.string.account_error_name_empty)); return; }

        // Поле имени у системного счёта отключено, но исходное имя всё равно
        // навязываем: правило опознаёт системность именно по нему.
        if (account.isSystem()) name = originalName;

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        if (WalletDbCRUD.IsAccountNameTaken(db, name, account.getId())) {
            editAccName.setError(getString(R.string.account_error_name_taken));
            return;
        }

        // Архивация кошелька с остатком — сначала лист «Списать остаток?»
        // (archive-wallet-writeoff); сохранение — по его кнопке.
        double balance = account instanceof AccountOperatingItem
                ? WalletDbCRUD.RoundedWalletBalance(db, account.getId(), precision()) : 0;
        if (ArchiveWriteOffDialog.isOffered(account instanceof AccountOperatingItem, originalArchived,
                checkIsArchive.isChecked(), originalAsset != null,
                assetFields != null && assetFields.isAsset(), balance)) {
            showWriteOffDialog(name, balance);
            return;
        }
        save(name, 0, null);
    }

    /** Валюта кошелька: выбранная на экране или сохранённая; null — категория. */
    private Currency walletCurrency() {
        return selectedCurrency != null ? selectedCurrency
                : account instanceof AccountOperatingItem ? ((AccountOperatingItem) account).getCurrency() : null;
    }

    private int precision() {
        Currency c = walletCurrency();
        return c != null ? c.getPrecision() : 2;
    }

    private void showWriteOffDialog(String name, double balance) {
        Currency c = walletCurrency();
        String money = AdapterUtils.getFormat(precision()).format(balance) + " " + (c != null ? c.getSymbol() : "");
        writeOffDialog = new ArchiveWriteOffDialog(this,
                getString(R.string.archive_writeoff_message, account.getDisplayName(), money),
                new ArchiveWriteOffDialog.Listener() {
                    @Override public void onWriteOff(int categoryId, String day) { save(name, categoryId, day); }
                    @Override public void onArchiveWithoutWriteOff() { save(name, 0, null); }
                    @Override public void onPickCategory(int currentId) {
                        Intent pick = AccountPickerActivity.buildIntent(AccountItemActivity.this,
                                getString(R.string.archive_writeoff_picker_title),
                                AccountPickerActivity.cSelectedTypeChargeAccounts, currentId, account);
                        pick.putExtra(AccountPickerActivity.cHideSystem, true);
                        startActivityForResult(pick, REQUEST_PICK_WRITEOFF_CATEGORY);
                    }
                });
        writeOffDialog.show();
    }

    /**
     * Запись экрана. {@code writeOffCategoryId} &gt; 0 — в той же транзакции списать остаток
     * кошелька на эту категорию датой {@code writeOffDay} (archive-wallet-writeoff, D9):
     * не вышло — откатывается всё, экран остаётся открытым.
     */
    private void save(String name, int writeOffCategoryId, String writeOffDay) {
        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        int iconId = selectedIconId > 0 ? selectedIconId : account.getIcon().getId();
        boolean wroteOff = false;

        // Счёт, валюта и признак актива — одной транзакцией (capital-assets). Снятие
        // признака операции кошелька не трогает.
        db.beginTransaction();
        try {
            WalletDbCRUD.UpdateAccount(db, account.getId(), name,
                    editComment.getText().toString().trim(), iconId, checkIsArchive.isChecked());

            if (account instanceof AccountOperatingItem && selectedCurrency != null) {
                WalletDbCRUD.UpdateAccountCurrency(db, account.getId(), selectedCurrency.getId());
            }
            if (assetFields != null && assetFields.differsFrom(originalAsset)) {
                if (assetFields.isAsset()) {
                    WalletDbCRUD.SetAccountAsset(db, account.getId(), assetFields.type(), assetFields.includeInNetWorth());
                } else {
                    WalletDbCRUD.ClearAccountAsset(db, account.getId());
                }
            }
            if (writeOffCategoryId > 0) {
                WalletDbCRUD.WriteOffResult r = WalletDbCRUD.WriteOffBalance(db, account.getId(),
                        writeOffCategoryId, writeOffDay, precision(), getString(R.string.archive_writeoff_comment));
                if (r == WalletDbCRUD.WriteOffResult.failed) {
                    Toast.makeText(this, R.string.archive_writeoff_failed, Toast.LENGTH_LONG).show();
                    return; // без setTransactionSuccessful — откат правок вместе с проводкой
                }
                wroteOff = r == WalletDbCRUD.WriteOffResult.written;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        reloadSingleton();
        if (wroteOff) {
            PeriodFilterState.get(this).markDataChanged();
            android.content.Context app = getApplicationContext();
            new Thread(() -> com.example.wallettransactions.sync.SyncRunner.runOnce(app)).start();
            Toast.makeText(this, R.string.archive_writeoff_done, Toast.LENGTH_SHORT).show();
        }
        if (writeOffDialog != null) writeOffDialog.dismiss();
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
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.account_delete_question, account.getDisplayName()))
                .setPositiveButton(R.string.action_delete, (d, w) -> deleteAccount())
                .setNegativeButton(R.string.action_cancel, null)
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
            return getString(R.string.account_delete_refused_system, account.getDisplayName());
        }
        return getString(R.string.account_delete_refused_operations, account.getDisplayName());
    }

    /** Диалог «Переоценка» (capital-assets, asset-revaluation). */
    public void btnRevaluateClick(View view) {
        new RevaluationDialog(this, (AccountOperatingItem) account, () -> {
            setResult(RESULT_OK);
            Toast.makeText(this, R.string.revaluation_saved, Toast.LENGTH_SHORT).show();
        }).show();
    }

    /**
     * «Перенести операции в актив» (capital-assets): выбор актива, подтверждение с числом
     * операций, перенос одной транзакцией ({@link WalletDbCRUD#MoveCategoryToAsset}), синк в
     * фоне. Пропущенные без курса — отдельным сообщением, категория тогда остаётся.
     */
    public void btnMoveToAssetClick(View view) {
        java.util.List<AccountOperatingItem> assets = new java.util.ArrayList<>();
        for (AccountItem a : Singleton.instance.getAccounts(this).getAllAccounts()) {
            if (a instanceof AccountOperatingItem && ((AccountOperatingItem) a).isAsset()
                    && !Boolean.TRUE.equals(a.getIsArchived())) {
                assets.add((AccountOperatingItem) a);
            }
        }
        if (assets.isEmpty()) return;
        String[] names = new String[assets.size()];
        for (int i = 0; i < assets.size(); i++) names[i] = assets.get(i).getDisplayName();
        new AlertDialog.Builder(this)
                .setTitle(R.string.move_pick_asset)
                .setItems(names, (d, which) -> confirmMove(assets.get(which)))
                .show();
    }

    private void confirmMove(AccountOperatingItem asset) {
        SQLiteDatabase db = Singleton.instance.getWritableDatabase(this);
        int count = WalletDbCRUD.CountCategoryWalletOperations(db, account.getId());
        new AlertDialog.Builder(this)
                .setTitle(R.string.move_confirm_title)
                .setMessage(getString(R.string.move_confirm_message, count, asset.getDisplayName(), account.getDisplayName()))
                .setPositiveButton(R.string.move_confirm_ok, (d, w) -> moveTo(asset))
                .setNegativeButton(R.string.move_confirm_cancel, null)
                .show();
    }

    private void moveTo(AccountOperatingItem asset) {
        SQLiteDatabase db = Singleton.instance.getWritableDatabase(this);
        WalletDbCRUD.MoveResult result;
        try {
            result = WalletDbCRUD.MoveCategoryToAsset(db, account.getId(), asset.getId());
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.move_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        reloadSingleton();
        com.example.wallettransactions.PeriodFilterState.get(this).markDataChanged();
        android.content.Context app = getApplicationContext();
        new Thread(() -> com.example.wallettransactions.sync.SyncRunner.runOnce(app)).start();
        setResult(RESULT_OK);
        if (result.skippedNoRate > 0) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.move_skipped, result.skippedNoRate))
                    .setPositiveButton(R.string.move_skipped_ok, (d, w) -> finish())
                    .show();
        } else {
            Toast.makeText(this, getString(R.string.move_done, result.moved), Toast.LENGTH_SHORT).show();
            finish();
        }
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
