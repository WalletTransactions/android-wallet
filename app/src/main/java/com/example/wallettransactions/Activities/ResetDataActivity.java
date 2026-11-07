package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.Data.WalletFillData;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

/**
 * «Удалить данные» — скрытый экран, открывается пятью тапами по строке «Версия» в
 * «Настройках» ({@link SettingsActivity#onVersionTap}). Раньше кнопка была видна прямо в
 * «Ещё» и сразу чистила операции/счета за диалогом-подтверждением; теперь — отдельный
 * экран с чекбоксами, что удалить (операции и счета / иконки / валютные пары и курсы —
 * независимо друг от друга), и опцией пересеять дефолтный набор для отмеченного после
 * удаления. Сама логика — в {@code WalletFillData.Reset*}/{@code Seed*}, здесь только сбор
 * флажков и поток.
 * <p>
 * Ниже — второй, отдельный блок «Полная очистка базы» ({@link #onHardWipe}): в отличие от
 * чекбоксов выше (мягкое удаление — {@code UPDATE ... deleted_at}, см. их javadoc в
 * {@code WalletFillData}), это настоящий {@code DELETE} по всем таблицам без разбора —
 * необходим именно ради этого различия (напр. для сброса устройства перед повторным тестом
 * первой синхронизации), но, в отличие от мягкого удаления, никогда не долетит до сервера
 * как удаление: следующий pull может притащить те же данные обратно.
 */
public class ResetDataActivity extends AppCompatActivity {

    /** Все таблицы БД — порядок неважен: {@link #onHardWipe} откладывает проверку внешних
     *  ключей на конец транзакции ({@code PRAGMA defer_foreign_keys}), как и {@code SyncEngine}
     *  при applyRow. */
    private static final String[] ALL_TABLES = {
            WalletContract.OperationEntry.TABLE_NAME,
            WalletContract.OperationLinkEntry.TABLE_NAME,
            WalletContract.AccountOperatingEntry.TABLE_NAME,
            WalletContract.AccountEntry.TABLE_NAME,
            WalletContract.CurrencyConversionEntry.TABLE_NAME,
            WalletContract.CurrencyPairRateEntry.TABLE_NAME,
            WalletContract.CurrencyPairEntry.TABLE_NAME,
            WalletContract.CurrencyEntry.TABLE_NAME,
            WalletContract.IconEntry.TABLE_NAME,
            WalletContract.RecurringOperationEntry.TABLE_NAME,
    };

    private CheckBox chkOperations;
    private CheckBox chkIcons;
    private CheckBox chkCurrencyPairs;
    private CheckBox chkReseed;
    private Button btnConfirmReset;
    private ProgressBar progressReset;
    private TextView tvResetStatus;

    private CheckBox chkHardReseed;
    private Button btnHardWipe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_data);

        ((TextView) findViewById(R.id.screenTitle)).setText("Удалить данные");

        chkOperations    = findViewById(R.id.chkOperations);
        chkIcons         = findViewById(R.id.chkIcons);
        chkCurrencyPairs = findViewById(R.id.chkCurrencyPairs);
        chkReseed        = findViewById(R.id.chkReseed);
        btnConfirmReset  = findViewById(R.id.btnConfirmReset);
        progressReset    = findViewById(R.id.progressReset);
        tvResetStatus    = findViewById(R.id.tvResetStatus);

        chkHardReseed = findViewById(R.id.chkHardReseed);
        btnHardWipe   = findViewById(R.id.btnHardWipe);
    }

    public void btnUp(View view) {
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public void onConfirmReset(View view) {
        boolean opsChecked = chkOperations.isChecked();
        boolean iconsChecked = chkIcons.isChecked();
        boolean pairsChecked = chkCurrencyPairs.isChecked();
        if (!opsChecked && !iconsChecked && !pairsChecked) {
            Toast.makeText(this, "Отметьте хотя бы один пункт", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean reseed = chkReseed.isChecked();
        new AlertDialog.Builder(this)
                .setTitle("Удалить отмеченное?")
                .setMessage("Действие необратимо." + (reseed
                        ? " После удаления будет создан дефолтный набор для отмеченного."
                        : ""))
                .setPositiveButton("Удалить", (d, w) -> runReset(opsChecked, iconsChecked, pairsChecked, reseed))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runReset(boolean ops, boolean icons, boolean pairs, boolean reseed) {
        setBusy(true, "Удаление…");

        new Thread(() -> {
            try {
                SQLiteDatabase db = Singleton.instance.getWritableDatabase(this);
                db.beginTransaction();
                try {
                    if (ops) WalletFillData.ResetOperationsAndAccounts(db, this, reseed);
                    if (icons) WalletFillData.ResetIcons(db, this, reseed);
                    if (pairs) WalletFillData.ResetCurrencyPairsAndRates(db, this, reseed);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                reloadCachesAfterReset(this, ops, icons, pairs);

                runOnUiThread(() -> {
                    setBusy(false, "Готово");
                    Toast.makeText(this, "Удаление завершено", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                String error = "Ошибка: " + e.getMessage();
                runOnUiThread(() -> {
                    setBusy(false, error);
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Кэши переживают удаление в БД и держат призраков — перечитываем то, что реально могло
     * измениться (см. javadoc каждого reload* в Singleton). Иконки — первыми: счета и валюты
     * при перечитывании берут картинку из списка иконок по {@code icon_id}, а пересев иконок
     * выдаёт им новые {@code _id}. Со старым списком категории набора остаются без иконок до
     * перезапуска приложения.
     */
    static void reloadCachesAfterReset(Context context, boolean ops, boolean icons, boolean pairs) {
        if (icons) Singleton.instance.reloadIconList(context);
        if (ops || icons) Singleton.instance.reloadAccounts(context);
        if (icons) Singleton.instance.reloadCurrencyList(context);
        if (pairs) Singleton.instance.reloadCurrencyPairList(context);
    }

    private void setBusy(boolean busy, String statusText) {
        progressReset.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnConfirmReset.setEnabled(!busy);
        btnHardWipe.setEnabled(!busy);
        tvResetStatus.setText(statusText);
    }

    /** Второе, более резкое подтверждение — это необратимо и не проходит через
     *  {@code deleted_at}, см. класс-javadoc. */
    public void onHardWipe(View view) {
        boolean reseed = chkHardReseed.isChecked();
        new AlertDialog.Builder(this)
                .setTitle("Удалить ВСЁ безвозвратно?")
                .setMessage("Все таблицы базы будут физически очищены. Это НЕ обычное " +
                        "\"мягкое\" удаление — изменение не уйдёт на сервер, и следующая " +
                        "синхронизация может вернуть эти же данные обратно. Отменить нельзя.")
                .setPositiveButton("Удалить всё", (d, w) -> runHardWipe(reseed))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runHardWipe(boolean reseed) {
        setBusy(true, "Очистка базы…");

        new Thread(() -> {
            try {
                SQLiteDatabase db = Singleton.instance.getWritableDatabase(this);
                db.beginTransaction();
                try {
                    // Как SyncEngine.applyRow — откладывает проверку FK на COMMIT, поэтому
                    // порядок DELETE по ALL_TABLES ниже не важен.
                    db.execSQL("PRAGMA defer_foreign_keys = ON");
                    for (String table : ALL_TABLES) {
                        db.execSQL("DELETE FROM " + table);
                    }
                    db.execSQL("DELETE FROM sqlite_sequence");
                    if (reseed) WalletFillData.InsertFirstDictionary(db, this);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                // Singleton — процесс-wide кэш (icon/account/currency/currency_pair
                // списки), FLAG_ACTIVITY_CLEAR_TASK ниже чистит только стек активностей,
                // не сам процесс. Без явного reload* кэш держит старые _id (сид выше
                // сбросил sqlite_sequence и завёл все строки заново с _id=1) — тот же
                // баг, что уже описан в WalletFillData.ResetIcons/RepairOrphanedIconLinks,
                // только на всю базу разом: следующий INSERT со ссылкой на кэшированный
                // icon_id/currency_id бьётся об FK на несуществующую строку.
                Singleton.instance.reloadIconList(this);
                Singleton.instance.reloadAccounts(this);
                Singleton.instance.reloadCurrencyList(this);
                Singleton.instance.reloadCurrencyPairList(this);

                runOnUiThread(() -> {
                    Toast.makeText(this, "База полностью очищена", Toast.LENGTH_SHORT).show();
                    // Дальше по стеку (счета, операции, кэши Singleton) могло остаться что
                    // угодно, ссылающееся на теперь несуществующие строки — не перечитываем
                    // кэши на месте, а перезапускаем на чистый MainActivity, как при первом
                    // запуске.
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                });
            } catch (Exception e) {
                String error = "Ошибка: " + e.getMessage();
                runOnUiThread(() -> {
                    setBusy(false, error);
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
