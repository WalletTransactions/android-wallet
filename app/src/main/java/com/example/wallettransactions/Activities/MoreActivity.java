package com.example.wallettransactions.Activities;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.R;
import com.example.wallettransactions.security.AppLock;
import com.example.wallettransactions.sync.AiApiClient;
import com.example.wallettransactions.sync.DocsApiClient;

import org.json.JSONException;

import java.io.IOException;

/**
 * Экран «Ещё» — таб нижней панели (navMore). Пункты, раньше жившие в боковом
 * drawer'е и меню Toolbar «Операций», теперь здесь ({@code activity_more.xml}).
 *
 * Блок «Интеграции» (сервер, связь с Google аккаунтом, Telegram, синхронизация)
 * до 2026-10-11 стоял прямо здесь — теперь это отдельный экран
 * {@link IntegrationsActivity}, отсюда на него одна строка; рядом — «Открыть
 * веб-версию». «Импорт из файла» — в «Настройках» → «Данные».
 *
 * Общей шапки (период, фильтр, состояние денег) на этом экране нет — он ничего не
 * показывает за период, — но нижняя панель и её кнопка «+» те же, что на остальных
 * табах, поэтому экран всё равно наследует {@link BaseTabActivity}.
 */
public class MoreActivity extends BaseTabActivity {

    /** ИИ уже подтверждён сервером — на время жизни экрана повторно не спрашиваем. */
    private boolean aiAvailable;

    /** Раздел «Вопрос по приложению» подтверждён сервером ({@code 200} на статусе) — повторно не спрашиваем. */
    private boolean docsAvailable;

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
        refreshDocsRow();
    }

    /**
     * Пункт «ИИ»: без входа — неактивен сразу, без сети; иначе
     * {@code GET /api/ai/status} в фоне (spec ai-expense-entry, «Пункт «ИИ» в «Ещё»»).
     */
    private void refreshAiRow() {
        if (!AiApiClient.hasSession(this)) {
            aiAvailable = false;
            setAiRow(false, getString(R.string.more_ai_sign_in));
            return;
        }
        if (aiAvailable) return;
        setAiRow(false, getString(R.string.more_ai_checking));
        final Context app = getApplicationContext();
        new Thread(() -> {
            String reason;
            boolean ok = false;
            AiApiClient.SessionState session = AiApiClient.refreshSession(app);
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                reason = app.getString(R.string.more_ai_sign_in);
            } else if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                reason = app.getString(R.string.more_ai_no_server);
            } else {
                try {
                    ok = AiApiClient.create(app).status().canParseExpense();
                    reason = app.getString(ok ? R.string.more_ai_available : R.string.more_ai_off);
                } catch (AiApiClient.HttpError e) {
                    reason = app.getString(e.code == 401 ? R.string.more_ai_sign_in : R.string.more_ai_off);
                } catch (IOException e) {
                    reason = app.getString(R.string.more_ai_no_server);
                } catch (JSONException e) {
                    reason = app.getString(R.string.more_ai_off);
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

    /**
     * Пункт «Вопрос по приложению»: скрыт по умолчанию, появляется только после {@code 200}
     * на {@code GET /api/docs/status}; любой другой ответ, сеть или отсутствие входа — скрыт
     * без сообщений. Запрос в фоне — открытие экрана не ждёт (openspec docs-assistant, D1).
     */
    private void refreshDocsRow() {
        if (!AiApiClient.hasSession(this)) {
            docsAvailable = false;
            setDocsRow(false);
            return;
        }
        if (docsAvailable) return;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean visible = false;
            if (AiApiClient.refreshSession(app) == AiApiClient.SessionState.OK) {
                try {
                    visible = DocsApiClient.isVisibleFor(DocsApiClient.create(app).status());
                } catch (IOException e) {
                    // нет сети — пункт скрыт
                }
            }
            final boolean show = visible;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                docsAvailable = show;
                setDocsRow(show);
            });
        }).start();
    }

    private void setDocsRow(boolean visible) {
        findViewById(R.id.rowDocsCard).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void onMoreDocs(View view) {
        startActivity(new Intent(this, DocsAssistantActivity.class));
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
    public void onMoreDebts(View view) {
        startActivity(new Intent(this, DebtsActivity.class));
    }

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

    /**
     * Веб-версия живёт на том же гейтвее, что и синк, — открываем «Адрес сервера»
     * в браузере по умолчанию. До 2026-11-11 пункт стоял на «Интеграциях».
     * Браузера может не быть вовсе (или адрес кривой) — тогда тост, а не падение.
     */
    public void onMoreOpenWeb(View view) {
        String url = AppSettings.getServerBaseUrl(this);
        try {
            AppLock.allowNextLeave();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.integrations_no_browser, url), Toast.LENGTH_LONG).show();
        }
    }

    /** Импорт CSV/JSON — с 2026-11-11 в «Настройках» → «Данные», рядом с экспортом
     *  ({@link SettingsActivity#onImportFile}). */
    public void onMoreSettings(View view) {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
