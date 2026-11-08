package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.Data.DbInit;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

/**
 * «Готовим кошелёк…» — первое открытие базы в фоне (openspec fix-first-start-anr),
 * см. {@link DbInit}. Когда база открыта, открывает исходный экран из
 * {@link DbInit#EXTRA_NEXT} и закрывается.
 */
public class DbInitActivity extends AppCompatActivity {

    private static final String TAG = "DbInit";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_db_init);

        // После поворота — новый поток: Singleton/SQLiteOpenHelper открывают базу один раз,
        // второй поток просто дождётся первого.
        final android.content.Context app = getApplicationContext();
        Thread worker = new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                Singleton.instance.getWritableDatabase(app);
                Singleton.instance.getReadableDatabase(app);
                DbInit.markReady();
                Log.i(TAG, "база готова за " + (System.currentTimeMillis() - started) + " мс");
                runOnUiThread(this::proceed);
            } catch (RuntimeException e) {
                Log.e(TAG, "база не открылась", e);
                runOnUiThread(() -> showError(e));
            }
        }, "db-init");
        worker.start();
    }

    private void proceed() {
        if (isFinishing() || isDestroyed()) return;
        Intent next = getIntent().getParcelableExtra(DbInit.EXTRA_NEXT);
        if (next == null) next = new Intent(this, MainActivity.class);
        startActivity(next);
        finish();
        overridePendingTransition(0, 0);
    }

    private void showError(RuntimeException e) {
        if (isFinishing() || isDestroyed()) return;
        findViewById(R.id.progressDbInit).setVisibility(View.GONE);
        String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        ((TextView) findViewById(R.id.tvDbInit)).setText(getString(R.string.db_init_failed, why));
    }
}
