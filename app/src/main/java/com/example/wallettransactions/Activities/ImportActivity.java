package com.example.wallettransactions.Activities;

import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.example.wallettransactions.Activities.Fragments.FilePickerFragment;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.Data.WalletImportData;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImportActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private EditText tvError;
    private ProgressBar progressBar;
    private Button btnImportAccount;
    private Button btnImportOperation;
    private Button btnImportOperationJson;
    private Button btnImportRate;

    /** Короткая подсказка вместо файла — полное описание формата переехало
     *  в диалог «Формат» (см. wireFormatInfo/FilePickerFragment.showFormatDialog),
     *  здесь остаётся только статус текущего импорта. */
    private static final String cErrorMessage = "Пожалуйста, выберите файл";

    private static final String ACCOUNT_CSV_HINT =
            "CSV для счетов:\n" +
            "AccountName,AccountComment,AccountIconName,CurrencyName,CurrencyIso,CurrencyIconName\n" +
            "Electronics,Electronics,unknown,,,\n" +
            "Втб Евро,Банк Втб в еврах,unknown,EUR,EUR,unknown";

    private static final String OPERATION_CSV_HINT =
            "CSV для операций:\n" +
            "Date,AccountFr,AccountTo,Amount,Comment\n" +
            "2020-09-26,Cash,Café,7,Попили чай в кафе \"Тарантино\"\n" +
            "2020-09-01,Salary,Cash,1000000,Зарплата";

    private static final String RATE_CSV_HINT =
            "CSV для курсов:\n" +
            "Symbol,Date,Rate\n" +
            "EURRUB,2020-09-12,88.5101\n" +
            "EURUSD,2020-08-20,1.18023\n" +
            "EURUSD,2020-08-22,1.17544";

    private static final String JSON_HINT =
            "Умеет переводы и комиссию — CSV этого не может, — а также счета\n" +
            "с валютой, валютные пары и курсы одним файлом; все разделы опциональны:\n" +
            "{\n" +
            "  \"accounts\": [\n" +
            "    {\"name\": \"Тинькофф Usd\", \"comment\": \"...\", \"icon\": \"Tinkoff\",\n" +
            "     \"currency\": {\"name\": \"USD\", \"iso\": \"USD\", \"icon\": \"\"}}\n" +
            "  ],\n" +
            "  \"currency_pairs\": [{\"symbol\": \"EURUSD\", \"base\": \"EUR\", \"quote\": \"USD\"}],\n" +
            "  \"rates\": [{\"symbol\": \"EURUSD\", \"date\": \"2020-08-20\", \"rate\": 1.18023}],\n" +
            "  \"operations\": [\n" +
            "    {\"date\": \"2020-08-26\", \"from\": \"Тинькофф\", \"to\": \"Тинькофф Usd\",\n" +
            "     \"amount\": 5000, \"comment\": \"\",\n" +
            "     \"leg\": {\"amount\": 65.4},\n" +
            "     \"commission\": {\"amount\": 50, \"from\": \"source\", \"category\": \"Transfer commission\"}},\n" +
            "    {\"date\": \"2020-08-26\", \"from\": \"Groceries\", \"to\": \"Тинькофф\",\n" +
            "     \"amount\": 1200, \"comment\": \"\"}\n" +
            "  ]\n" +
            "}\n\n" +
            "Старый формат (голый массив — только операции) тоже читается.\n" +
            "\"leg\"/\"commission\" опциональны; без них — обычная проводка, как в CSV.\n" +
            "\"commission.from\" — \"source\" или \"dest\", по умолчанию \"source\".";

    /** Ключи вида "имя": — только они, не строковые значения — подсвечиваются
     *  акцентным цветом, чтобы структура JSON читалась с одного взгляда. */
    private static final Pattern JSON_KEY = Pattern.compile("\"[A-Za-z_][A-Za-z0-9_.]*\"(?=\\s*:)");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import);
        ((TextView) findViewById(R.id.screenTitle)).setText("Импорт из файла");
        tvError = (EditText) findViewById(R.id.error);
        tvError.setTextIsSelectable(true);
        tvError.setFocusableInTouchMode(false);
        progressBar = (ProgressBar) findViewById(R.id.importProgressBar);
        btnImportAccount       = (Button) findViewById(R.id.btnImportAccount);
        btnImportOperation     = (Button) findViewById(R.id.btnImportOperation);
        btnImportOperationJson = (Button) findViewById(R.id.btnImportOperationJson);
        btnImportRate          = (Button) findViewById(R.id.btnImportRate);

        int accent = ContextCompat.getColor(this, R.color.colorexpanse);
        wireFormatInfo(R.id.fragmentCSVFileAccount, "Формат — счета", highlightHeader(ACCOUNT_CSV_HINT, accent));
        wireFormatInfo(R.id.fragmentCSVFileOperation, "Формат — операции", highlightHeader(OPERATION_CSV_HINT, accent));
        wireFormatInfo(R.id.fragmentCSVFileCurrencyPairRate, "Формат — курсы", highlightHeader(RATE_CSV_HINT, accent));
        wireFormatInfo(R.id.fragmentJSONFileOperation, "Формат — JSON", highlightJsonKeys(JSON_HINT, accent));
    }

    private void wireFormatInfo(int fragmentId, String title, CharSequence content) {
        FilePickerFragment fl = (FilePickerFragment) getSupportFragmentManager().findFragmentById(fragmentId);
        fl.setFormatInfo(title, content);
    }

    /** Первая строка (заголовок столбцов CSV) — акцентным жирным, остальное как есть. */
    private static CharSequence highlightHeader(String text, int color) {
        SpannableString ss = new SpannableString(text);
        int headerStart = text.indexOf('\n') + 1; // сразу после "CSV для ...:"
        int headerEnd = text.indexOf('\n', headerStart);
        if (headerEnd < 0) headerEnd = text.length();
        ss.setSpan(new ForegroundColorSpan(color), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(Typeface.BOLD), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** Каждое `"ключ":` — акцентным жирным; значения и структура — обычным текстом. */
    private static CharSequence highlightJsonKeys(String text, int color) {
        SpannableString ss = new SpannableString(text);
        Matcher m = JSON_KEY.matcher(text);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public void ImportOperationClick(View view) {
        FilePickerFragment fl = (FilePickerFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragmentCSVFileOperation);
        if (fl.GetFileName() == null) { tvError.setText(cErrorMessage); return; }
        if (!checkPermission()) { requestPermission(); return; }

        final android.net.Uri uri = fl.GetFileUri();
        setImporting(true, "Importing operations…");
        new Thread(() -> {
            String result;
            try {
                result = WalletImportData.FillOperationDataFromFile(this,
                        getContentResolver().openInputStream(uri)).describe("операций");
            } catch (Exception e) {
                result = e.toString();
            }
            final String msg = result;
            runOnUiThread(() -> {
                setImporting(false, msg);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /**
     * JSON-импорт — умеет то, чего не может плоский CSV операций (см.
     * {@link WalletImportData#FillOperationDataFromJson}): валютный перевод и
     * комиссию одной структурой со связью в {@code operation_link}, а также, одним
     * файлом, счета с валютой, валютные пары и курсы (разделы опциональны).
     */
    public void ImportOperationJsonClick(View view) {
        FilePickerFragment fl = (FilePickerFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragmentJSONFileOperation);
        if (fl.GetFileName() == null) { tvError.setText(cErrorMessage); return; }
        if (!checkPermission()) { requestPermission(); return; }

        final android.net.Uri uri = fl.GetFileUri();
        setImporting(true, "Importing (JSON)…");
        new Thread(() -> {
            String result;
            try {
                // Теперь может нести не только операции, но и счета/валютные пары/курсы
                // (см. WalletImportData.FillOperationDataFromJson) — счёт "элементов" общий.
                result = WalletImportData.FillOperationDataFromJson(this,
                        getContentResolver().openInputStream(uri)).describe("элементов");
                // JSON мог завести/обновить счета (раздел "accounts") — тот же кэш-рефреш,
                // что и после ImportAccountClick, иначе новые счета не видны до перезапуска.
                Singleton.instance.reloadAccounts(this);
            } catch (Exception e) {
                result = e.toString();
            }
            final String msg = result;
            runOnUiThread(() -> {
                setImporting(false, msg);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    public void ImportAccountClick(View view) {
        FilePickerFragment fl = (FilePickerFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragmentCSVFileAccount);
        if (fl.GetFileName() == null) { tvError.setText(cErrorMessage); return; }
        if (!checkPermission()) { requestPermission(); return; }

        final android.net.Uri uri = fl.GetFileUri();
        setImporting(true, "Importing accounts…");
        new Thread(() -> {
            String result;
            try {
                int count = WalletImportData.FillAccountDataFromFile(this,
                        getContentResolver().openInputStream(uri));
                result = "Импортировано/обновлено " + count + " счетов";
                Singleton.instance.reloadAccounts(this);
            } catch (Exception e) {
                result = e.toString();
            }
            final String msg = result;
            runOnUiThread(() -> {
                setImporting(false, msg);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /*select symbol||','|| cpr.date||','|| cpr.rate from currency_pair_rate cpr
join currency_pair cp
on cp."_id" =cpr.currency_pair_id */
    public void ImportCurrencyPairRateClick(View view) {
        FilePickerFragment fl = (FilePickerFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragmentCSVFileCurrencyPairRate);
        if (fl.GetFileName() == null) { tvError.setText(cErrorMessage); return; }
        if (!checkPermission()) { requestPermission(); return; }

        final android.net.Uri uri = fl.GetFileUri();
        setImporting(true, "Importing rates…");
        new Thread(() -> {
            String result;
            try {
                int count = WalletImportData.FillCurrencyPairRateDataFromStream(this,
                        getContentResolver().openInputStream(uri));
                result = "Imported/updated " + count + " rate rows";
            } catch (Exception e) {
                result = e.toString();
            }
            final String msg = result;
            runOnUiThread(() -> {
                setImporting(false, msg);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void setImporting(boolean active, String statusText) {
        progressBar.setVisibility(active ? View.VISIBLE : View.GONE);
        btnImportAccount.setEnabled(!active);
        btnImportOperation.setEnabled(!active);
        btnImportOperationJson.setEnabled(!active);
        btnImportRate.setEnabled(!active);
        tvError.setText(statusText);
        // Импорт закончился (и при ошибке: часть строк могла записаться) — табы
        // перечитают остатки и ленту, а не покажут то, что было до импорта.
        if (!active) PeriodFilterState.get(this).markDataChanged();
    }


    private boolean checkPermission() {
        // SAF (file picker via content URI) does not require READ_EXTERNAL_STORAGE on API 29+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Toast.makeText(this, "Write External Storage permission allows us to read files. Please allow this permission in App Settings.",
                    Toast.LENGTH_LONG).show();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }

    public void btnUp(View view) {
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.e("value", "Permission Granted, Now you can use local drive.");
            } else {
                Log.e("value", "Permission Denied, You cannot use local drive.");
            }
            break;
        }
    }



}