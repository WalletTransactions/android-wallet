package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.example.wallettransactions.R;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more);
        initTabScreen(R.id.navMore, null);
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
