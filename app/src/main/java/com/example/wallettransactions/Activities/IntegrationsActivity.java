package com.example.wallettransactions.Activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
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
import com.example.wallettransactions.sync.TelegramLinkClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Экран «Интеграции» — открывается с «Ещё» (пункт «Интеграции»). До
 * 2026-10-11 этот блок стоял прямо на «Ещё» (а ещё раньше — пятой группой в
 * «Настройках»); вынесен на отдельный экран, чтобы «Ещё» оставалось коротким
 * списком переходов.
 *
 * Здесь: адрес сервера, связь с Google аккаунтом (вход, которым сервер узнаёт
 * пользователя), Telegram-бот для ввода трат, веб-версия, автоматическая
 * синхронизация с уведомлениями и ручной синк.
 *
 * Панель нижней навигации не подключена — это экран «вглубь», как «Настройки».
 */
public class IntegrationsActivity extends AppCompatActivity {

    /** Разрешённые значения интервала автосинхронизации — androidx.work не
     *  умеет периодику короче 15 минут, меньшего здесь и не предлагаем. */
    private static final int[] AUTO_CONNECT_INTERVALS_MINUTES = {15, 30, 60, 180, 360};

    private TextView serverUrlValue;
    private TextView linkStatusValue;
    private FrameLayout switchAutoConnect;
    private TextView rowAutoConnectInterval;
    private FrameLayout switchNotifyNewOperations;
    private TextView syncNowStatus;
    private TextView telegramStatusValue;

    /** Подключённые Telegram-аккаунты; {@code null} — ещё не загрузили (или не связаны с сервером). */
    private List<TelegramLinkClient.Link> telegramLinks;
    /**
     * Ушли в Telegram по выданной ссылке и ждём новую связку: бот обменивает код
     * не мгновенно, поэтому после возврата несколько раз перепроверяем
     * (см. {@link #pollTelegramAfterReturn}). Значение — id связок до ухода.
     */
    private Set<String> telegramIdsBeforeLink;
    private int telegramPollsLeft;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Не даёт запустить второй проход поверх ещё не завершённого (двойной тап). */
    private boolean syncInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_integrations);

        serverUrlValue = findViewById(R.id.serverUrlValue);
        linkStatusValue = findViewById(R.id.linkStatusValue);
        switchAutoConnect = findViewById(R.id.switchAutoConnect);
        rowAutoConnectInterval = findViewById(R.id.rowAutoConnectInterval);
        syncNowStatus = findViewById(R.id.syncNowStatus);
        switchNotifyNewOperations = findViewById(R.id.switchNotifyNewOperations);
        telegramStatusValue = findViewById(R.id.telegramStatusValue);

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
        refreshTelegramStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    public void btnUp(View view) {
        finish();
    }

    // ── Сервер ──────────────────────────────────────────────────────────────

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
                .setTitle(R.string.integrations_server_url)
                .setView(input)
                .setPositiveButton(R.string.caption_btn_save, (d, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    AppSettings.setServerBaseUrl(this, url);
                    serverUrlValue.setText(AppSettings.getServerBaseUrl(this));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

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
            Toast.makeText(this, getString(R.string.integrations_no_browser, url), Toast.LENGTH_LONG).show();
        }
    }

    /** Открывает вход через Google (org/docs/TODO_mobile_sync.md, Фаза 1) —
     *  тот же экран обслуживает и первую связку, и повторную после того, как
     *  {@code SyncWorker} пометил сессию истёкшей. */
    public void onLinkServer(View view) {
        startActivity(new Intent(this, SyncLinkActivity.class));
    }

    private void refreshLinkStatus() {
        if (AppSettings.isNeedsRelink(this)) {
            linkStatusValue.setText(R.string.integrations_session_expired);
            linkStatusValue.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse));
            return;
        }
        String email = AppSettings.getLinkedEmail(this);
        boolean linked = email != null && TokenStore.getRefreshToken(this) != null;
        linkStatusValue.setText(linked ? email : getString(R.string.integrations_not_connected));
        linkStatusValue.setTextColor(ContextCompat.getColor(this,
                linked ? R.color.colorTextSecondary : R.color.colorTextHint));
    }

    // ── Telegram ────────────────────────────────────────────────────────────

    private boolean isServerLinked() {
        return TokenStore.getRefreshToken(this) != null && !AppSettings.isNeedsRelink(this);
    }

    /** Строка справа: подключённые аккаунты, «Не подключено» или что мешает узнать. */
    private void refreshTelegramStatus() {
        if (!isServerLinked()) {
            telegramLinks = null;
            setTelegramStatus(getString(R.string.integrations_need_google), R.color.colorTextHint);
            return;
        }
        if (telegramLinks == null) setTelegramStatus("…", R.color.colorTextHint);
        new Thread(() -> {
            try {
                List<TelegramLinkClient.Link> links = TelegramLinkClient.listLinks(this);
                runOnUiThread(() -> onTelegramLinksLoaded(links));
            } catch (TelegramLinkClient.TelegramException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    telegramLinks = null;
                    setTelegramStatus(e.getMessage(), R.color.colorexpanse);
                });
            }
        }).start();
    }

    private void onTelegramLinksLoaded(List<TelegramLinkClient.Link> links) {
        if (isFinishing() || isDestroyed()) return;
        telegramLinks = links;
        if (links.isEmpty()) {
            setTelegramStatus(getString(R.string.integrations_not_connected), R.color.colorTextHint);
        } else {
            StringBuilder names = new StringBuilder();
            for (TelegramLinkClient.Link l : links) {
                if (names.length() > 0) names.append(", ");
                names.append(l.name);
            }
            setTelegramStatus(names.toString(), R.color.colorTextSecondary);
        }

        if (telegramIdsBeforeLink == null) return;
        for (TelegramLinkClient.Link l : links) {
            if (!telegramIdsBeforeLink.contains(l.tgUserId)) {
                telegramIdsBeforeLink = null;
                handler.removeCallbacksAndMessages(null);
                Toast.makeText(this, getString(R.string.integrations_telegram_connected, l.name), Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    private void setTelegramStatus(String text, int colorRes) {
        telegramStatusValue.setText(text);
        telegramStatusValue.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    /**
     * Не связано — сначала «Связь с Google аккаунтом» (связка Telegram
     * привязывается к пользователю сервера). Нет подключённых — сразу ссылка в
     * Telegram. Есть — диалог: подключить ещё один или отключить.
     */
    public void onTelegram(View view) {
        if (!isServerLinked()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.integrations_telegram_title)
                    .setMessage(R.string.integrations_telegram_need_google)
                    .setPositiveButton(R.string.integrations_sign_in, (d, w) -> onLinkServer(view))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        if (telegramLinks == null) {
            refreshTelegramStatus();
            Toast.makeText(this, R.string.integrations_checking, Toast.LENGTH_SHORT).show();
            return;
        }
        if (telegramLinks.isEmpty()) {
            startTelegramLink();
            return;
        }
        StringBuilder message = new StringBuilder(getString(R.string.integrations_connected_list));
        for (TelegramLinkClient.Link l : telegramLinks) {
            message.append("\n").append(l.createdAt.length() >= 10
                    ? getString(R.string.integrations_connected_since, l.name, l.createdAt.substring(0, 10))
                    : l.name);
        }
        new AlertDialog.Builder(this)
                .setTitle("Telegram")
                .setMessage(message.toString())
                .setPositiveButton(R.string.integrations_connect_more, (d, w) -> startTelegramLink())
                .setNeutralButton(R.string.integrations_disconnect, (d, w) -> pickTelegramToUnlink())
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    /**
     * Одноразовая ссылка {@code t.me/<бот>?start=<код>} (живёт ~10 минут) —
     * открывает Telegram, тот передаёт код боту, бот создаёт связку. Без
     * Telegram ссылка откроется в браузере, там же кнопка «Открыть в Telegram».
     */
    private void startTelegramLink() {
        setTelegramStatus("…", R.color.colorTextHint);
        new Thread(() -> {
            try {
                String deepLink = TelegramLinkClient.createLinkCode(this);
                runOnUiThread(() -> openTelegram(deepLink));
            } catch (TelegramLinkClient.TelegramException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    refreshTelegramStatus();
                });
            }
        }).start();
    }

    private void openTelegram(String deepLink) {
        if (isFinishing() || isDestroyed()) return;
        Set<String> ids = new HashSet<>();
        if (telegramLinks != null) for (TelegramLinkClient.Link l : telegramLinks) ids.add(l.tgUserId);
        telegramIdsBeforeLink = ids;
        telegramPollsLeft = 5;
        try {
            com.example.wallettransactions.security.AppLock.allowNextLeave();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)));
        } catch (ActivityNotFoundException e) {
            telegramIdsBeforeLink = null;
            Toast.makeText(this, getString(R.string.integrations_no_app, deepLink), Toast.LENGTH_LONG).show();
            return;
        }
        // onResume после возврата сам перепроверит; бот может не успеть — добираем опросом.
        handler.postDelayed(this::pollTelegramAfterReturn, 3000);
    }

    private void pollTelegramAfterReturn() {
        if (telegramIdsBeforeLink == null || telegramPollsLeft-- <= 0) return;
        refreshTelegramStatus();
        handler.postDelayed(this::pollTelegramAfterReturn, 3000);
    }

    private void pickTelegramToUnlink() {
        if (telegramLinks == null || telegramLinks.isEmpty()) return;
        if (telegramLinks.size() == 1) {
            confirmTelegramUnlink(telegramLinks.get(0));
            return;
        }
        String[] names = new String[telegramLinks.size()];
        for (int i = 0; i < names.length; i++) names[i] = telegramLinks.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.integrations_which_disconnect)
                .setItems(names, (d, which) -> confirmTelegramUnlink(telegramLinks.get(which)))
                .show();
    }

    private void confirmTelegramUnlink(TelegramLinkClient.Link link) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.integrations_disconnect_question, link.name))
                .setMessage(R.string.integrations_disconnect_message)
                .setPositiveButton(R.string.integrations_disconnect, (d, w) -> new Thread(() -> {
                    try {
                        TelegramLinkClient.unlink(this, link.tgUserId);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, R.string.integrations_telegram_disconnected, Toast.LENGTH_SHORT).show();
                            refreshTelegramStatus();
                        });
                    } catch (TelegramLinkClient.TelegramException e) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }).start())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ── Синхронизация ───────────────────────────────────────────────────────

    /**
     * На Android 13+ уведомление SyncWorker'а о просроченной сессии
     * (см. его README-комментарий) не покажется без явного разрешения —
     * запрашиваем в естественный момент, когда пользователь сам включает
     * фоновую активность, а не заранее без повода. Отказ не критичен:
     * автосинхронизация всё равно работает, просто без уведомления при сбое.
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

    /** Само уведомление — sync.NewOperationsNotifier; разрешение то же, что у автосинхронизации. */
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
        if (minutes < 60) return getString(R.string.integrations_every_minutes, minutes);
        int hours = minutes / 60;
        if (hours == 1) return getString(R.string.integrations_every_hour);
        return getString(R.string.integrations_every_hours, hours);
    }

    public void onPickAutoConnectInterval(View view) {
        String[] items = new String[AUTO_CONNECT_INTERVALS_MINUTES.length];
        for (int i = 0; i < items.length; i++) items[i] = intervalLabel(AUTO_CONNECT_INTERVALS_MINUTES[i]);

        new AlertDialog.Builder(this)
                .setTitle(R.string.integrations_sync_interval)
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
        syncNowStatus.setText(R.string.integrations_syncing);

        new Thread(() -> {
            SyncRunner.Outcome outcome = SyncRunner.runOnce(this);
            runOnUiThread(() -> {
                syncInProgress = false;
                if (isFinishing() || isDestroyed()) return;
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
                title = getString(R.string.sync_result_no_session_title);
                body = getString(R.string.sync_result_no_session_body);
                break;
            case NEEDS_RELINK:
                title = getString(R.string.integrations_session_expired);
                body = getString(R.string.sync_result_relink_body);
                break;
            case NETWORK_ERROR:
                title = getString(R.string.sync_result_unavailable_title);
                body = outcome.errorMessage;
                break;
            case AWAITING_DECISION:
                title = getString(R.string.sync_result_awaiting_title);
                body = getString(R.string.sync_result_awaiting_body);
                break;
            case PARTIAL:
                title = getString(R.string.sync_result_partial_title);
                body = outcome.errorMessage + "\n\n" + outcome.log.toDisplayText();
                break;
            case ERROR:
                title = getString(R.string.sync_result_error_title);
                body = outcome.errorMessage + "\n\n" + outcome.log.toDisplayText();
                break;
            case OK:
            default:
                title = getString(R.string.sync_result_ok_title);
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
                .setPositiveButton(R.string.action_close, null)
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
