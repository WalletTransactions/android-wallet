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

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

public class AddCategoryActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ICON = 1;

    private ImageView imgIconPreview;
    private View iconPickerHint;
    private EditText editName;
    private EditText editComment;

    /** -1 — не выбрана: при сохранении подставим «неизвестную». */
    private int selectedIconId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_category);

        ((TextView) findViewById(R.id.screenTitle)).setText(R.string.account_new_category);

        imgIconPreview = findViewById(R.id.imgIconPreview);
        iconPickerHint = findViewById(R.id.iconPickerHint);
        editName       = findViewById(R.id.editCategoryName);
        editComment    = findViewById(R.id.editCategoryComment);
    }

    /**
     * Открываем справочник иконок, а не галерею: у категории должна быть одна из
     * иконок приложения. Новую картинку заводят там же кнопкой «+ Новая иконка» —
     * тогда она попадает в справочник, а не остаётся дублем на одну категорию.
     */
    public void pickIconClick(View view) {
        int current = selectedIconId > 0 ? selectedIconId
                : Singleton.instance.getIconList(this).getUnknownIcon().getId();
        startActivityForResult(
                IconPickerActivity.buildIntent(this, getString(R.string.icon_title_category), Icon.IconType.CATEGORY, current),
                REQUEST_PICK_ICON);
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
        if (name.isEmpty()) {
            editName.setError(getString(R.string.account_error_name_empty));
            return;
        }

        SQLiteDatabase db = new WalletDbHelper(this).getWritableDatabase();
        if (WalletDbCRUD.IsAccountNameTaken(db, name, -1)) {
            editName.setError(getString(R.string.account_error_name_taken));
            return;
        }

        int iconId = selectedIconId > 0 ? selectedIconId
                : Singleton.instance.getIconList(this).getUnknownIcon().getId();

        WalletDbCRUD.InsertAccount(db, name, editComment.getText().toString().trim(), iconId, false);

        // Reload Singleton so the new icon and account are visible immediately
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
