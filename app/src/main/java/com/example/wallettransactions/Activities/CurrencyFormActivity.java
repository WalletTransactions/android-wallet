package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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

import java.util.Locale;

/**
 * Форма валюты (activity_currency_form.xml, прототип 2a — блок curFormOpen).
 *
 * Открывается из «Списка валют»: по ✎ в строке — правка, по «+ Создать валюту» —
 * создание. Возвращает RESULT_OK, если список нужно перечитать.
 */
public class CurrencyFormActivity extends AppCompatActivity {

    public static final String ARG_CURRENCY_ID = "CurrencyForm.CurrencyId";

    private static final int REQUEST_PICK_ICON = 1;

    public static Intent buildIntent(Context ctx, int currencyId) {
        Intent intent = new Intent(ctx, CurrencyFormActivity.class);
        intent.putExtra(ARG_CURRENCY_ID, currencyId);
        return intent;
    }

    private EditText editName;
    private EditText editIso;
    private EditText editSymbol;
    private EditText editPrecision;
    private ImageView imgIcon;

    private int currencyId = -1;
    private int iconId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_form);

        editName = findViewById(R.id.editCurrencyName);
        editIso = findViewById(R.id.editCurrencyIso);
        editSymbol = findViewById(R.id.editCurrencySymbol);
        editPrecision = findViewById(R.id.editCurrencyPrecision);
        imgIcon = findViewById(R.id.imgCurrencyIcon);

        currencyId = getIntent().getIntExtra(ARG_CURRENCY_ID, -1);

        if (currencyId > 0) {
            Currency currency = Singleton.instance.getCurrencyList(this).getCurrencyById(currencyId);
            if (currency != null) fillFrom(currency);
        } else {
            ((TextView) findViewById(R.id.formTitle)).setText("Новая валюта");
            iconId = Singleton.instance.getIconList(this).getUnknownIcon().getId();
        }

        renderIcon();
    }

    private void fillFrom(Currency currency) {
        ((TextView) findViewById(R.id.formTitle)).setText(currency.getName());
        findViewById(R.id.btnDelete).setVisibility(View.VISIBLE);

        editName.setText(currency.getName());
        editIso.setText(currency.getIso());
        editSymbol.setText(currency.getSymbol());
        editPrecision.setText(String.valueOf(currency.getPrecision()));
        iconId = currency.getIcon() != null ? currency.getIcon().getId() : -1;
    }

    private void renderIcon() {
        Icon icon = Singleton.instance.getIconList(this).getIconById(iconId);
        if (icon == null) icon = Singleton.instance.getIconList(this).getUnknownIcon();
        imgIcon.setImageBitmap(icon.getBitmap());
    }

    public void btnUp(View view) {
        finish();
    }

    public void onPickIcon(View view) {
        startActivityForResult(
                IconPickerActivity.buildIntent(this, "Иконка валюты", Icon.IconType.FLAG, iconId),
                REQUEST_PICK_ICON);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return;

        iconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, iconId);
        renderIcon();
    }

    public void onSave(View view) {
        String name = editName.getText().toString().trim();
        String iso = editIso.getText().toString().trim().toUpperCase(Locale.ROOT);
        String symbol = editSymbol.getText().toString().trim();

        if (name.isEmpty()) { editName.setError("Введите название"); return; }
        if (iso.isEmpty()) { editIso.setError("Введите ISO-код"); return; }

        Currency clash = Singleton.instance.getCurrencyList(this).getCurrencyByISO(iso);
        if (clash != null && clash.getId() != currencyId) {
            editIso.setError("Валюта с таким кодом уже есть");
            return;
        }

        if (symbol.isEmpty()) symbol = iso;

        int precision = parsePrecision(editPrecision.getText().toString().trim());

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        boolean ok;
        if (currencyId > 0) {
            ok = WalletDbCRUD.UpdateCurrency(db, currencyId, name, iso, symbol, precision, iconId) > 0;
        } else {
            ok = WalletDbCRUD.InsertCurrency(db, name, iso, symbol, precision, iconId) > 0;
        }

        if (!ok) {
            Toast.makeText(this, "Не удалось сохранить валюту", Toast.LENGTH_SHORT).show();
            return;
        }

        reloadCurrencies(db);
        setResult(RESULT_OK);
        finish();
    }

    /** Пустое поле — 2 знака, как у большинства валют; больше 8 знаков смысла не имеет. */
    private int parsePrecision(String raw) {
        if (raw.isEmpty()) return 2;
        try {
            return Math.max(0, Math.min(8, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    public void onDelete(View view) {
        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();

        if (WalletDbCRUD.IsCurrencyInUse(db, currencyId)) {
            Toast.makeText(this,
                    "Валюта используется кошельком или валютной парой — сначала уберите её оттуда",
                    Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Удалить валюту?")
                .setMessage(editName.getText().toString())
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    WalletDbCRUD.DeleteCurrency(db, currencyId);
                    reloadCurrencies(db);
                    setResult(RESULT_OK);
                    finish();
                })
                .show();
    }

    /** Список валют лежит в синглтоне, поэтому после записи его надо перечитать. */
    private void reloadCurrencies(SQLiteDatabase db) {
        CurrencyList currencies = Singleton.instance.getCurrencyList(this);
        currencies.clear();
        WalletDbLoader.LoadCurrencyList(db, this);
    }
}
