package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Environment;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletExportData;
import com.example.wallettransactions.Singleton;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ExportActivity extends AppCompatActivity {

    private Button btnExportDb;
    private Button btnExportJson;
    private Button btnShare;
    private ProgressBar progressExport;
    private TextView tvExportStatus;
    private File lastExportedFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        ((TextView) findViewById(R.id.screenTitle)).setText(R.string.export_title);

        btnExportDb     = findViewById(R.id.btnExportDb);
        btnExportJson   = findViewById(R.id.btnExportJson);
        btnShare        = findViewById(R.id.btnShare);
        progressExport  = findViewById(R.id.progressExport);
        tvExportStatus  = findViewById(R.id.tvExportStatus);

        btnShare.setEnabled(false);
    }

    public void btnUp(View view) {
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public void ExportDatabaseClick(View view) {
        btnExportDb.setEnabled(false);
        btnShare.setEnabled(false);
        progressExport.setVisibility(View.VISIBLE);
        tvExportStatus.setText(R.string.export_busy);

        new Thread(() -> {
            try {
                File exportedFile = exportDatabase();
                lastExportedFile = exportedFile;
                String result = getString(R.string.export_done_path, exportedFile.getAbsolutePath());

                runOnUiThread(() -> {
                    progressExport.setVisibility(View.GONE);
                    tvExportStatus.setText(result);
                    btnExportDb.setEnabled(true);
                    btnShare.setEnabled(true);
                    Toast.makeText(ExportActivity.this, R.string.export_db_done, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                String error = getString(R.string.error_with_reason, e.getMessage());
                runOnUiThread(() -> {
                    progressExport.setVisibility(View.GONE);
                    tvExportStatus.setText(error);
                    btnExportDb.setEnabled(true);
                    Toast.makeText(ExportActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Экспорт в тот же JSON, что понимает импорт (см. {@link WalletExportData} и
     * {@code WalletImportData.FillOperationDataFromJson}) — счета/валюты, валютные пары,
     * курсы и операции (с восстановленными "leg"/"commission") одним читаемым файлом,
     * в отличие от бинарного wallet.db выше.
     */
    public void ExportJsonClick(View view) {
        btnExportDb.setEnabled(false);
        btnExportJson.setEnabled(false);
        btnShare.setEnabled(false);
        progressExport.setVisibility(View.VISIBLE);
        tvExportStatus.setText(R.string.export_busy);

        new Thread(() -> {
            try {
                File exportedFile = exportJson();
                lastExportedFile = exportedFile;
                String result = getString(R.string.export_done_path, exportedFile.getAbsolutePath());

                runOnUiThread(() -> {
                    progressExport.setVisibility(View.GONE);
                    tvExportStatus.setText(result);
                    btnExportDb.setEnabled(true);
                    btnExportJson.setEnabled(true);
                    btnShare.setEnabled(true);
                    Toast.makeText(ExportActivity.this, R.string.export_json_done, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                String error = getString(R.string.error_with_reason, e.getMessage());
                runOnUiThread(() -> {
                    progressExport.setVisibility(View.GONE);
                    tvExportStatus.setText(error);
                    btnExportDb.setEnabled(true);
                    btnExportJson.setEnabled(true);
                    Toast.makeText(ExportActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    public void ShareDatabaseClick(View view) {
        if (lastExportedFile == null || !lastExportedFile.exists()) {
            Toast.makeText(this, R.string.export_first, Toast.LENGTH_SHORT).show();
            return;
        }

        // Get shareable URI using FileProvider
        Uri fileUri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider",
                lastExportedFile);

        boolean isJson = lastExportedFile.getName().endsWith(".json");

        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.setType(isJson ? "application/json" : "application/octet-stream");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        com.example.wallettransactions.security.AppLock.allowNextLeave();
        startActivity(Intent.createChooser(shareIntent, getString(isJson ? R.string.export_share_json : R.string.export_share_db)));
    }

    /**
     * Папка приложения {@code Android/data/<пакет>/files/Download} на всех версиях: писать
     * туда можно без разрешений. Общая {@code Download/} на Android 8–9 требовала
     * {@code WRITE_EXTERNAL_STORAGE}, которое никто не запрашивал, — экспорт падал (#38).
     */
    static File exportDir(Context ctx) throws IOException {
        File downloadsDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null || (!downloadsDir.exists() && !downloadsDir.mkdirs())) {
            throw new IOException(ctx.getString(R.string.export_no_downloads));
        }
        return downloadsDir;
    }

    private File getDownloadsDir() throws IOException {
        return exportDir(this);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
    }

    private File exportDatabase() throws IOException {
        // Source: app's database file
        File dbFile = getDatabasePath("wallet.db");
        if (!dbFile.exists()) {
            throw new IOException(getString(R.string.export_no_db_file));
        }

        File exportFile = new File(getDownloadsDir(), "wallet_android_" + timestamp() + ".db");

        // Copy file using FileChannels
        try (FileInputStream inStream = new FileInputStream(dbFile);
             FileOutputStream outStream = new FileOutputStream(exportFile)) {

            FileChannel inChannel  = inStream.getChannel();
            FileChannel outChannel = outStream.getChannel();

            inChannel.transferTo(0, inChannel.size(), outChannel);
        }

        return exportFile;
    }

    private File exportJson() throws IOException, org.json.JSONException {
        JSONObject json;
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
        json = WalletExportData.BuildExportJson(db);

        File exportFile = new File(getDownloadsDir(), "wallet_export_" + timestamp() + ".json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
            w.write(json.toString(2));
        }
        return exportFile;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
