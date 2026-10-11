package com.example.wallettransactions.Activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.R;
import com.example.wallettransactions.TokenStore;
import com.example.wallettransactions.sync.SyncRunner;
import com.example.wallettransactions.sync.SyncScheduler;

/**
 * Экран «Ещё» — таб нижней панели (navMore). Пункты, раньше жившие в боковом
 * drawer'е и меню Toolbar «Операций», теперь здесь ({@code activity_more.xml}).
 *
 * Блок «Синхронизация» — сюда же из «Настроек» (там он раньше стоял пятой
 * группой): статус связки с сервером должен быть на виду сразу на «Ещё», а не
 * спрятан на экране глубже.
 *
 * Общей шапки (период, фильтр, состояние денег) на этом экране нет — он ничего не
 * показывает за период, — но нижняя панель и её кнопка «+» те же, что на остальных
 * табах, поэтому экран всё равно наследует {@link BaseTabActivity}.
 */
public class MoreActivity extends BaseTabActivity {

    /** Разрешённые значения интервала автоподключения — androidx.work не
     *  умеет периодику короче 15 минут, меньшего здесь и не предлагаем. */
    private static final int[] AUTO_CONNECT_INTERVALS_MINUTES = {15, 30, 60, 180, 360};

    private TextView serverUrlValue;
    private TextView linkStatusValue;
    private FrameLayout switchAutoConnect;
    private TextView rowAutoConnectInterval;
    private FrameLayout switchNotifyNewOperations;
    private TextView syncNowStatus;

    /** Не даёт запустить второй проход поверх ещё не завершённого (двойной тап). */
    private boolean syncInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more);
        initTabScreen(R.id.navMore, null);

        serverUrlValue = findViewById(R.id.serverUrlValue);
        linkStatusValue = findViewById(R.id.linkStatusValue);
        switchAutoConnect = findViewById(R.id.switchAutoConnect);
        rowAutoConnectInterval = findViewById(R.id.rowAutoConnectInterval);
        syncNowStatus = findViewById(R.id.syncNowStatus);
        switchNotifyNewOperations = findViewById(R.id.switchNotifyNewOperations);

        serverUrlValue.setText(AppSettings.getServerBaseUrl(this));
        applySwitchState(switchAutoConnect, AppSettings.isAutoConnectEnabled(this));
        applySwitchState(switchNotifyNewOperations, AppSettings.isNotifyNewOperationsEnabled(this));
        rowAutoConnectInterval.setText(intervalLabel(AppSettings.getAutoConnectIntervalMinutes(this)));
        refreshLinkStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Не в onCreate: пользователь мог вернуться сюда из SyncLinkActivity
        // (успешная/неудачная связка) или из фонового SyncWorker
        // (нужна повторная авторизация) — экран уже был создан, onCreate не
        // перезапустится.
        refreshLinkStatus();
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

    // ── Синхронизация ───────────────────────────────────────────────────────

    /**
     * Адрес wallet-gateway (org/docs/TODO_mobile_sync.md, п. 1.4 — LAN-only,
     * ввод вручную). Без валидации схемы/хоста: неверный адрес просто не
     * ответит при следующей синхронизации, это не хуже, чем полагаться на
     * regex, который всё равно не проверит, слушает ли там что-то живое.
     */
    public void onPickServerUrl(View view) {
        EditText input = new EditText(this);
        input.setText(AppSettings.getServerBaseUrl(this));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Адрес сервера")
                .setView(input)
                .setPositiveButton("Сохранить", (d, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    AppSettings.setServerBaseUrl(this, url);
                    serverUrlValue.setText(AppSettings.getServerBaseUrl(this));
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Открывает вход через Google (org/docs/TODO_mobile_sync.md, Фаза 1) —
     *  тот же экран обслуживает и первую связку, и повторную после того, как
     *  {@code SyncWorker} пометил сессию истёкшей. */
    /**
     * Веб-версия живёт на том же гейтвее, что и синк, — открываем «Адрес сервера»
     * в браузере по умолчанию. Браузера может не быть вовсе (или адрес кривой) —
     * тогда тост, а не падение.
     */
    public void onOpenWeb(View view) {
        String url = AppSettings.getServerBaseUrl(this);
        try {
            com.example.wallettransactions.security.AppLock.allowNextLeave();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не найден браузер, чтобы открыть " + url, Toast.LENGTH_LONG).show();
        }
    }

    public void onLinkServer(View view) {
        startActivity(new Intent(this, SyncLinkActivity.class));
    }

    private void refreshLinkStatus() {
        if (AppSettings.isNeedsRelink(this)) {
            linkStatusValue.setText("Сессия истекла");
            linkStatusValue.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse));
            return;
        }
        String email = AppSettings.getLinkedEmail(this);
        boolean linked = email != null && TokenStore.getRefreshToken(this) != null;
        linkStatusValue.setText(linked ? email : "Не подключено");
        linkStatusValue.setTextColor(ContextCompat.getColor(this,
                linked ? R.color.colorTextSecondary : R.color.colorTextHint));
    }

    /**
     * На Android 13+ уведомление SyncWorker'а о просроченной сессии
     * (см. его README-комментарий) не покажется без явного разрешения —
     * запрашиваем в естественный момент, когда пользователь сам включает
     * фоновую активность, а не заранее без повода. Отказ не критичен:
     * автоподключение всё равно работает, просто без уведомления при сбое.
     */
    public void onToggleAutoConnect(View view) {
        boolean on = toggleSwitch(switchAutoConnect);
        AppSettings.setAutoConnectEnabled(this, on);
        SyncScheduler.reschedule(this);

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Само уведомление — sync.NewOperationsNotifier; разрешение то же, что у автоподключения. */
    public void onToggleNotifyNewOperations(View view) {
        boolean on = toggleSwitch(switchNotifyNewOperations);
        AppSettings.setNotifyNewOperationsEnabled(this, on);

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private String intervalLabel(int minutes) {
        if (minutes < 60) return "раз в " + minutes + " мин.";
        int hours = minutes / 60;
        if (hours == 1) return "раз в час";
        return "раз в " + hours + " ч.";
    }

    public void onPickAutoConnectInterval(View view) {
        String[] items = new String[AUTO_CONNECT_INTERVALS_MINUTES.length];
        for (int i = 0; i < items.length; i++) items[i] = intervalLabel(AUTO_CONNECT_INTERVALS_MINUTES[i]);

        new AlertDialog.Builder(this)
                .setTitle("Интервал подключения")
                .setItems(items, (d, which) -> {
                    int minutes = AUTO_CONNECT_INTERVALS_MINUTES[which];
                    AppSettings.setAutoConnectIntervalMinutes(this, minutes);
                    rowAutoConnectInterval.setText(intervalLabel(minutes));
                    SyncScheduler.reschedule(this);
                })
                .show();
    }

    /**
     * Ручной проход синка (в дополнение к фоновому по расписанию — см.
     * {@code SyncScheduler}), по прямому запросу пользователя: "сделать
     * прямо сейчас" + "показать максимум дебаг-информации, что именно
     * поменялось". Общий с {@code SyncWorker} код — {@link SyncRunner},
     * отличие только в том, что здесь результат показывается диалогом, а не
     * решает retry/success для WorkManager.
     * <p>
     * Сеть и SQLite — на фоновом потоке (тот же приём, что импорт/экспорт
     * CSV в {@code ImportActivity}/{@code ExportActivity}: голый
     * {@code new Thread}, без AsyncTask/Executor — простоты ради, для
     * одного нечастого действия по кнопке новая инфраструктура не нужна).
     */
    public void onSyncNow(View view) {
        if (syncInProgress) return;
        syncInProgress = true;
        syncNowStatus.setText("Синхронизация…");

        new Thread(() -> {
            SyncRunner.Outcome outcome = SyncRunner.runOnce(this);
            runOnUiThread(() -> {
                syncInProgress = false;
                syncNowStatus.setText("");
                refreshLinkStatus(); // AuthSession мог обновить токен или пометить needsRelink
                showSyncResultDialog(outcome);
            });
        }).start();
    }

    private void showSyncResultDialog(SyncRunner.Outcome outcome) {
        String title;
        String body;
        switch (outcome.status) {
            case NO_SESSION:
                title = "Не связано с сервером";
                body = "Сначала свяжите приложение с сервером — кнопка «Связать с сервером» выше.";
                break;
            case NEEDS_RELINK:
                title = "Сессия истекла";
                body = "Нужен повторный вход через Google — нажмите «Связать с сервером» выше.";
                break;
            case NETWORK_ERROR:
                title = "Сервер недоступен";
                body = outcome.errorMessage;
                break;
            case ERROR:
                title = "Ошибка синхронизации";
                body = outcome.errorMessage + "\n\n" + outcome.log.toDisplayText();
                break;
            case OK:
            default:
                title = "Синхронизация завершена";
                body = outcome.log.toDisplayText();
                break;
        }

        TextView text = new TextView(this);
        text.setText(body);
        text.setTextIsSelectable(true); // можно выделить/скопировать для отчёта об ошибке
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    /** Flips the pill on/off (bg_switch_on/off) and slides the thumb (start/end), returns the new state. */
    private boolean toggleSwitch(FrameLayout switchView) {
        boolean isOn = switchView.getBackground() != null
                && switchView.getTag() != null && (boolean) switchView.getTag();
        applySwitchState(switchView, !isOn);
        return !isOn;
    }

    private void applySwitchState(FrameLayout switchView, boolean on) {
        switchView.setTag(on);
        switchView.setBackgroundResource(on ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        View thumb = switchView.getChildAt(0);
        if (thumb == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.gravity = on ? android.view.Gravity.END : android.view.Gravity.START;
        thumb.setLayoutParams(lp);
    }
}
