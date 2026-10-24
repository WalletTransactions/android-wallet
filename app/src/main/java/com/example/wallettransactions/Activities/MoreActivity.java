package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.example.wallettransactions.R;
import com.example.wallettransactions.sync.AiApiClient;

import org.json.JSONException;

import java.io.IOException;

/**
 * Экран «Ещё» — таб нижней панели (navMore). Пункты, раньше жившие в боковом
 * drawer'е и меню Toolbar «Операций», теперь здесь ({@code activity_more.xml}).
 *
 * Блок «Интеграции» (сервер, связь с Google аккаунтом, Telegram, синхронизация)
 * до 2026-10-11 стоял прямо здесь — теперь это отдельный экран
 * {@link IntegrationsActivity}, отсюда на него одна строка.
 *
 * Общей шапки (период, фильтр, состояние денег) на этом экране нет — он ничего не
 * показывает за период, — но нижняя панель и её кнопка «+» те же, что на остальных
 * табах, поэтому экран всё равно наследует {@link BaseTabActivity}.
 */
public class MoreActivity extends BaseTabActivity {

    /** ИИ уже подтверждён сервером — на время жизни экрана повторно не спрашиваем. */
    private boolean aiAvailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more);
        initTabScreen(R.id.navMore, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAiRow();
    }

    /**
     * Пункт «ИИ»: без входа — неактивен сразу, без сети; иначе
     * {@code GET /api/ai/status} в фоне (spec ai-expense-entry, «Пункт «ИИ» в «Ещё»»).
     */
    private void refreshAiRow() {
        if (!AiApiClient.hasSession(this)) {
            aiAvailable = false;
            setAiRow(false, "Войдите, чтобы пользоваться ИИ");
            return;
        }
        if (aiAvailable) return;
        setAiRow(false, "Проверяю…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String reason;
            boolean ok = false;
            AiApiClient.SessionState session = AiApiClient.refreshSession(app);
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                reason = "Войдите, чтобы пользоваться ИИ";
            } else if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                reason = "Нет связи с сервером";
            } else {
                try {
                    ok = AiApiClient.create(app).status().canParseExpense();
                    reason = ok ? "Трата своими словами" : "ИИ сейчас выключен";
                } catch (AiApiClient.HttpError e) {
                    reason = e.code == 401 ? "Войдите, чтобы пользоваться ИИ" : "ИИ сейчас выключен";
                } catch (IOException e) {
                    reason = "Нет связи с сервером";
                } catch (JSONException e) {
                    reason = "ИИ сейчас выключен";
                }
            }
            final boolean available = ok;
            final String hint = reason;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                aiAvailable = available;
                setAiRow(available, hint);
            });
        }).start();
    }

    private void setAiRow(boolean enabled, String hint) {
        View row = findViewById(R.id.rowAi);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.5f);
        ((TextView) findViewById(R.id.rowAiHint)).setText(hint);
    }

    public void onMoreAi(View view) {
        startActivity(new Intent(this, AiActivity.class));
    }

    @Override
    protected void reloadTabData() {
        // Список пунктов статичен — перезагружать нечего.
    }

    /** Справочник счетов: тот же список, что при вводе операции, но с правкой по тапу. */
    public void onMoreAccounts(View view) {
        startActivity(AccountPickerActivity.buildCatalogIntent(this));
    }

    /** Правила, по которым операции заводятся сами — ежедневно/еженедельно/ежемесячно/ежегодно. */
    public void onMoreRecurring(View view) {
        startActivity(new Intent(this, RecurringOperationListActivity.class));
    }

    public void onMoreIntegrations(View view) {
        startActivity(new Intent(this, IntegrationsActivity.class));
    }

    public void onMoreRates(View view) {
        startActivity(new Intent(this, CurrencyPairListActivity.class));
    }

    public void onMoreIcons(View view) {
        startActivity(new Intent(this, IconManagerActivity.class));
    }

    /** Импорт CSV/JSON (счета, операции, курсы валют) — на уровень выше «Настроек»:
     *  нужен сразу, не спрятанным на отдельном экране вглубь. Экспорт остался там же
     *  ({@link SettingsActivity#onExportDatabase}) — его вызывают заметно реже. */
    public void onMoreImport(View view) {
        startActivity(new Intent(this, ImportActivity.class));
    }

    public void onMoreSettings(View view) {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
