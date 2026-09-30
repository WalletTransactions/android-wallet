package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
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
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

public class AddWalletActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ICON = 1;

    private ImageView imgIconPreview;
    private View iconPickerHint;
    private ImageView imgCurrencyFlag;
    private android.widget.TextView tvCurrencyLabel;
    private EditText editName;
    private EditText editComment;

    /** -1 — не выбрана: при сохранении подставим «неизвестную». */
    private int selectedIconId = -1;
    private Currency selectedCurrency;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_wallet);

        ((TextView) findViewById(R.id.screenTitle)).setText("Новый кошелёк");

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
                IconPickerActivity.buildIntent(this, "Иконка кошелька", Icon.IconType.CATEGORY, current),
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
                    imgCurrencyFlag.setImageBitmap(selectedCurrency.getIcon().getBitmap());
                    imgCurrencyFlag.setVisibility(View.VISIBLE);
                    tvCurrencyLabel.setText(selectedCurrency.getIso() + "  —  " + selectedCurrency.getName());
                    tvCurrencyLabel.setTextColor(getResources().getColor(R.color.colorTextPrimary));
                })
                .show();
    }

    public void btnUp(View view) {
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return;

        selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId);
        renderIcon();
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
        if (name.isEmpty()) { editName.setError("Введите название"); return; }
        if (selectedCurrency == null) {
            Toast.makeText(this, "Выберите валюту", Toast.LENGTH_SHORT).show();
            return;
        }

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();

        int iconId = selectedIconId > 0 ? selectedIconId
                : Singleton.instance.getIconList(this).getUnknownIcon().getId();

        WalletDbCRUD.InsertOperatingAccount(db, name,
                editComment.getText().toString().trim(), iconId, selectedCurrency.getId());

        SQLiteDatabase rdb = new WalletDbHelper(this).getReadableDatabase();
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this));
        WalletDbLoader.LoadAccounts(rdb,
                Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this),
                Singleton.instance.getIconList(this),
                null);

        setResult(RESULT_OK);
        finish();
    }
}
