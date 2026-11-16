package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

public class AddWalletActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ICON = 1;
    private static final int REQUEST_PICK_CURRENCY = 2;

    private ImageView imgIconPreview;
    private View iconPickerHint;
    private ImageView imgCurrencyFlag;
    private android.widget.TextView tvCurrencyLabel;
    private EditText editName;
    private EditText editComment;

    /** -1 — не выбрана: при сохранении подставим «неизвестную». */
    private int selectedIconId = -1;
    private Currency selectedCurrency;
    private AssetFields assetFields;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_wallet);
        assetFields = new AssetFields(this, null, false, null);

        ((TextView) findViewById(R.id.screenTitle)).setText(R.string.account_new_wallet);

        imgIconPreview  = findViewById(R.id.imgIconPreview);
        iconPickerHint  = findViewById(R.id.iconPickerHint);
        imgCurrencyFlag = findViewById(R.id.imgCurrencyFlag);
        tvCurrencyLabel = findViewById(R.id.tvCurrencyLabel);
        editName        = findViewById(R.id.editWalletName);
        editComment     = findViewById(R.id.editWalletComment);
    }

    /** Справочник иконок вместо галереи — см. AddCategoryActivity.pickIconClick. */
    public void pickIconClick(View view) {
        int current = selectedIconId > 0 ? selectedIconId
                : Singleton.instance.getIconList(this).getUnknownIcon().getId();
        startActivityForResult(
                IconPickerActivity.buildIntent(this, getString(R.string.icon_title_wallet), Icon.IconType.CATEGORY, current),
                REQUEST_PICK_ICON);
    }

    /**
     * Валюта выбирается тем же экраном «Список валют», что и в «Валютных парах»
     * (openspec wallet-currency-picker): поиск, флаги, текущая отмечена.
     */
    public void pickCurrencyClick(View view) {
        startActivityForResult(
                CurrencyPickerActivity.buildIntent(this, getString(R.string.account_choose_currency),
                        selectedCurrency != null ? selectedCurrency.getIso() : null),
                REQUEST_PICK_CURRENCY);
    }

    private void renderCurrency() {
        imgCurrencyFlag.setImageBitmap(selectedCurrency.getIcon().getBitmap());
        imgCurrencyFlag.setVisibility(View.VISIBLE);
        tvCurrencyLabel.setText(selectedCurrency.getIso() + "  —  " + selectedCurrency.getName());
        tvCurrencyLabel.setTextColor(getResources().getColor(R.color.colorTextPrimary));
    }

    public void btnUp(View view) {
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_PICK_ICON) {
            selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId);
            renderIcon();
        } else if (requestCode == REQUEST_PICK_CURRENCY) {
            Currency picked = Singleton.instance.getCurrencyList(this)
                    .getCurrencyByISO(data.getStringExtra(CurrencyPickerActivity.RESULT_ISO));
            if (picked == null) return;
            selectedCurrency = picked;
            renderCurrency();
        }
    }

    private void renderIcon() {
        Icon icon = Singleton.instance.getIconList(this).getIconById(selectedIconId);
        if (icon == null) return;

        imgIconPreview.setImageBitmap(icon.getBitmap());
        imgIconPreview.setVisibility(View.VISIBLE);
        iconPickerHint.setVisibility(View.GONE);
    }

    public void btnSaveClick(View view) {
        String name = editName.getText().toString().trim();
        if (name.isEmpty()) { editName.setError(getString(R.string.account_error_name_empty)); return; }
        if (selectedCurrency == null) {
            Toast.makeText(this, R.string.account_choose_currency, Toast.LENGTH_SHORT).show();
            return;
        }

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        if (WalletDbCRUD.IsAccountNameTaken(db, name, -1)) {
            editName.setError(getString(R.string.account_error_name_taken));
            return;
        }

        int iconId = selectedIconId > 0 ? selectedIconId
                : Singleton.instance.getIconList(this).getUnknownIcon().getId();

        // Кошелёк и признак актива — одной транзакцией (capital-assets).
        db.beginTransaction();
        try {
            long id = WalletDbCRUD.InsertOperatingAccount(db, name,
                    editComment.getText().toString().trim(), iconId, selectedCurrency.getId());
            if (id > 0 && assetFields.isAsset()) {
                WalletDbCRUD.SetAccountAsset(db, (int) id, assetFields.type(), assetFields.includeInNetWorth());
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        SQLiteDatabase rdb = new WalletDbHelper(this).getReadableDatabase();
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this));
        WalletDbLoader.LoadAccounts(rdb,
                Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this),
                Singleton.instance.getIconList(this),
                null);
        // Новый актив сразу даёт строку «Капитал» в карточке периода — табы в стеке
        // перечитываются по ревизии (capital-assets, design п. 10, android-wallet#42).
        PeriodFilterState.get(this).markDataChanged();

        setResult(RESULT_OK);
        finish();
    }
}
