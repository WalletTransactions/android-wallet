package com.example.wallettransactions.Activities

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import com.example.wallettransactions.TokenStore
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.sync.SyncRunner
import com.example.wallettransactions.sync.SyncScheduler
import com.example.wallettransactions.sync.TelegramLinkClient

/**
 * Экран «Интеграции» — открывается с «Ещё» (пункт «Интеграции»). До
 * 2026-10-11 этот блок стоял прямо на «Ещё» (а ещё раньше — пятой группой в
 * «Настройках»); вынесен на отдельный экран, чтобы «Ещё» оставалось коротким
 * списком переходов.
 *
 * Здесь: адрес сервера, связь с Google аккаунтом (вход, которым сервер узнаёт
 * пользователя), Telegram-бот для ввода трат, автоматическая синхронизация с
 * уведомлениями и ручной синк. «Открыть веб-версию» с 2026-11-11 — на «Ещё»
 * ([MoreActivity.onMoreOpenWeb]).
 *
 * Панель нижней навигации не подключена — это экран «вглубь», как «Настройки».
 */
class IntegrationsActivity : AppCompatActivity() {

    companion object {
        /** Разрешённые значения интервала автосинхронизации — androidx.work не
         *  умеет периодику короче 15 минут, меньшего здесь и не предлагаем. */
        private val AUTO_CONNECT_INTERVALS_MINUTES = intArrayOf(15, 30, 60, 180, 360)
    }

    private lateinit var serverUrlValue: TextView
    private lateinit var linkStatusValue: TextView
    private lateinit var switchAutoConnect: FrameLayout
    private lateinit var rowAutoConnectInterval: TextView
    private lateinit var switchNotifyNewOperations: FrameLayout
    private lateinit var syncNowStatus: TextView
    private lateinit var telegramStatusValue: TextView

    /** Подключённые Telegram-аккаунты; `null` — ещё не загрузили (или не связаны с сервером). */
    private var telegramLinks: List<TelegramLinkClient.Link>? = null
    /**
     * Ушли в Telegram по выданной ссылке и ждём новую связку: бот обменивает код
     * не мгновенно, поэтому после возврата несколько раз перепроверяем
     * (см. [pollTelegramAfterReturn]). Значение — id связок до ухода.
     */
    private var telegramIdsBeforeLink: Set<String>? = null
    private var telegramPollsLeft = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Не даёт запустить второй проход поверх ещё не завершённого (двойной тап). */
    private var syncInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_integrations)

        serverUrlValue = findViewById(R.id.serverUrlValue)
        linkStatusValue = findViewById(R.id.linkStatusValue)
        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        rowAutoConnectInterval = findViewById(R.id.rowAutoConnectInterval)
        syncNowStatus = findViewById(R.id.syncNowStatus)
        switchNotifyNewOperations = findViewById(R.id.switchNotifyNewOperations)
        telegramStatusValue = findViewById(R.id.telegramStatusValue)

        serverUrlValue.text = AppSettings.getServerBaseUrl(this)
        applySwitchState(switchAutoConnect, AppSettings.isAutoConnectEnabled(this))
        applySwitchState(switchNotifyNewOperations, AppSettings.isNotifyNewOperationsEnabled(this))
        rowAutoConnectInterval.text = intervalLabel(AppSettings.getAutoConnectIntervalMinutes(this))
        refreshLinkStatus()
    }

    override fun onResume() {
        super.onResume()
        // Не в onCreate: пользователь мог вернуться сюда из SyncLinkActivity
        // (успешная/неудачная связка) или из фонового SyncWorker
        // (нужна повторная авторизация) — экран уже был создан, onCreate не
        // перезапустится.
        refreshLinkStatus()
        refreshTelegramStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    fun btnUp(view: View) {
        finish()
    }

    // ── Сервер ──────────────────────────────────────────────────────────────

    /**
     * Адрес wallet-gateway (org/docs/TODO_mobile_sync.md, п. 1.4 — LAN-only,
     * ввод вручную). Без валидации схемы/хоста: неверный адрес просто не
     * ответит при следующей синхронизации, это не хуже, чем полагаться на
     * regex, который всё равно не проверит, слушает ли там что-то живое.
     */
    fun onPickServerUrl(view: View) {
        val input = EditText(this)
        input.setText(AppSettings.getServerBaseUrl(this))
        input.setSelectAllOnFocus(true)
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI

        AlertDialog.Builder(this)
                .setTitle(R.string.integrations_server_url)
                .setView(buildServerUrlDialogView(input))
                .setPositiveButton(R.string.caption_btn_save) { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.isEmpty()) return@setPositiveButton
                    AppSettings.setServerBaseUrl(this, url)
                    serverUrlValue.text = AppSettings.getServerBaseUrl(this)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /**
     * Поле адреса и под ним — последние адреса, на которых вход прошёл успешно
     * ([AppSettings.getRecentServerUrls]). Нажатие на адрес подставляет его в поле,
     * сохраняет по-прежнему кнопка «Сохранить»: выбор можно поправить перед сохранением.
     */
    private fun buildServerUrlDialogView(input: EditText): View {
        val recent = AppSettings.getRecentServerUrls(this)
        if (recent.isEmpty()) return input

        val pad = dp(20)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, dp(8), pad, 0)
        box.addView(input)

        val caption = TextView(this)
        caption.setText(R.string.integrations_server_url_recent)
        caption.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary))
        caption.setPadding(0, dp(12), 0, dp(4))
        box.addView(caption)

        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        for (url in recent) {
            val row = TextView(this)
            row.text = url
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            row.setPadding(0, dp(10), 0, dp(10))
            row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                input.setText(url)
                input.setSelection(url.length)
            }
            box.addView(row)
        }
        return box
    }

    private fun dp(value: Int): Int =
        Math.round(value * resources.displayMetrics.density)

    /** Открывает вход через Google (org/docs/TODO_mobile_sync.md, Фаза 1) —
     *  тот же экран обслуживает и первую связку, и повторную после того, как
     *  `SyncWorker` пометил сессию истёкшей. */
    fun onLinkServer(view: View) {
        startActivity(Intent(this, SyncLinkActivity::class.java))
    }

    private fun refreshLinkStatus() {
        if (AppSettings.isNeedsRelink(this)) {
            linkStatusValue.setText(R.string.integrations_session_expired)
            linkStatusValue.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse))
            return
        }
        val email = AppSettings.getLinkedEmail(this)
        val linked = email != null && TokenStore.getRefreshToken(this) != null
        linkStatusValue.text = if (linked) email else getString(R.string.integrations_not_connected)
        linkStatusValue.setTextColor(ContextCompat.getColor(this,
                if (linked) R.color.colorTextSecondary else R.color.colorTextHint))
    }

    // ── Telegram ────────────────────────────────────────────────────────────

    private fun isServerLinked(): Boolean {
        return TokenStore.getRefreshToken(this) != null && !AppSettings.isNeedsRelink(this)
    }

    /** Строка справа: подключённые аккаунты, «Не подключено» или что мешает узнать. */
    private fun refreshTelegramStatus() {
        if (!isServerLinked()) {
            telegramLinks = null
            setTelegramStatus(getString(R.string.integrations_need_google), R.color.colorTextHint)
            return
        }
        if (telegramLinks == null) setTelegramStatus("…", R.color.colorTextHint)
        Thread {
            try {
                val links = TelegramLinkClient.listLinks(this)
                runOnUiThread { onTelegramLinksLoaded(links) }
            } catch (e: TelegramLinkClient.TelegramException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    telegramLinks = null
                    setTelegramStatus(e.message, R.color.colorexpanse)
                }
            }
        }.start()
    }

    private fun onTelegramLinksLoaded(links: List<TelegramLinkClient.Link>) {
        if (isFinishing || isDestroyed) return
        telegramLinks = links
        if (links.isEmpty()) {
            setTelegramStatus(getString(R.string.integrations_not_connected), R.color.colorTextHint)
        } else {
            val names = StringBuilder()
            for (l in links) {
                if (names.isNotEmpty()) names.append(", ")
                names.append(l.name)
            }
            setTelegramStatus(names.toString(), R.color.colorTextSecondary)
        }

        val before = telegramIdsBeforeLink ?: return
        for (l in links) {
            if (!before.contains(l.tgUserId)) {
                telegramIdsBeforeLink = null
                handler.removeCallbacksAndMessages(null)
                Toast.makeText(this, getString(R.string.integrations_telegram_connected, l.name), Toast.LENGTH_LONG).show()
                return
            }
        }
    }

    private fun setTelegramStatus(text: String?, colorRes: Int) {
        telegramStatusValue.text = text
        telegramStatusValue.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /**
     * Не связано — сначала «Связь с Google аккаунтом» (связка Telegram
     * привязывается к пользователю сервера). Нет подключённых — сразу ссылка в
     * Telegram. Есть — диалог: подключить ещё один или отключить.
     */
    fun onTelegram(view: View) {
        if (!isServerLinked()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.integrations_telegram_title)
                    .setMessage(R.string.integrations_telegram_need_google)
                    .setPositiveButton(R.string.integrations_sign_in) { _, _ -> onLinkServer(view) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            return
        }
        val links = telegramLinks
        if (links == null) {
            refreshTelegramStatus()
            Toast.makeText(this, R.string.integrations_checking, Toast.LENGTH_SHORT).show()
            return
        }
        if (links.isEmpty()) {
            startTelegramLink()
            return
        }
        val message = StringBuilder(getString(R.string.integrations_connected_list))
        for (l in links) {
            message.append("\n").append(if (l.createdAt.length >= 10)
                getString(R.string.integrations_connected_since, l.name, l.createdAt.substring(0, 10))
            else
                l.name)
        }
        AlertDialog.Builder(this)
                .setTitle("Telegram")
                .setMessage(message.toString())
                .setPositiveButton(R.string.integrations_connect_more) { _, _ -> startTelegramLink() }
                .setNeutralButton(R.string.integrations_disconnect) { _, _ -> pickTelegramToUnlink() }
                .setNegativeButton(R.string.action_close, null)
                .show()
    }

    /**
     * Одноразовая ссылка `t.me/<бот>?start=<код>` (живёт ~10 минут) —
     * открывает Telegram, тот передаёт код боту, бот создаёт связку. Без
     * Telegram ссылка откроется в браузере, там же кнопка «Открыть в Telegram».
     */
    private fun startTelegramLink() {
        setTelegramStatus("…", R.color.colorTextHint)
        Thread {
            try {
                val deepLink = TelegramLinkClient.createLinkCode(this)
                runOnUiThread { openTelegram(deepLink) }
            } catch (e: TelegramLinkClient.TelegramException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    refreshTelegramStatus()
                }
            }
        }.start()
    }

    private fun openTelegram(deepLink: String) {
        if (isFinishing || isDestroyed) return
        val ids = HashSet<String>()
        telegramLinks?.let { for (l in it) ids.add(l.tgUserId) }
        telegramIdsBeforeLink = ids
        telegramPollsLeft = 5
        try {
            AppLock.allowNextLeave()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
        } catch (e: ActivityNotFoundException) {
            telegramIdsBeforeLink = null
            Toast.makeText(this, getString(R.string.integrations_no_app, deepLink), Toast.LENGTH_LONG).show()
            return
        }
        // onResume после возврата сам перепроверит; бот может не успеть — добираем опросом.
        handler.postDelayed({ pollTelegramAfterReturn() }, 3000)
    }

    private fun pollTelegramAfterReturn() {
        if (telegramIdsBeforeLink == null || telegramPollsLeft-- <= 0) return
        refreshTelegramStatus()
        handler.postDelayed({ pollTelegramAfterReturn() }, 3000)
    }

    private fun pickTelegramToUnlink() {
        val links = telegramLinks
        if (links == null || links.isEmpty()) return
        if (links.size == 1) {
            confirmTelegramUnlink(links[0])
            return
        }
        val names = Array(links.size) { i -> links[i].name }
        AlertDialog.Builder(this)
                .setTitle(R.string.integrations_which_disconnect)
                .setItems(names) { _, which -> confirmTelegramUnlink(links[which]) }
                .show()
    }

    private fun confirmTelegramUnlink(link: TelegramLinkClient.Link) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.integrations_disconnect_question, link.name))
                .setMessage(R.string.integrations_disconnect_message)
                .setPositiveButton(R.string.integrations_disconnect) { _, _ ->
                    Thread {
                        try {
                            TelegramLinkClient.unlink(this, link.tgUserId)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(this, R.string.integrations_telegram_disconnected, Toast.LENGTH_SHORT).show()
                                refreshTelegramStatus()
                            }
                        } catch (e: TelegramLinkClient.TelegramException) {
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    // ── Синхронизация ───────────────────────────────────────────────────────

    /**
     * На Android 13+ уведомление SyncWorker'а о просроченной сессии
     * (см. его README-комментарий) не покажется без явного разрешения —
     * запрашиваем в естественный момент, когда пользователь сам включает
     * фоновую активность, а не заранее без повода. Отказ не критичен:
     * автосинхронизация всё равно работает, просто без уведомления при сбое.
     */
    fun onToggleAutoConnect(view: View) {
        val on = toggleSwitch(switchAutoConnect)
        AppSettings.setAutoConnectEnabled(this, on)
        SyncScheduler.reschedule(this)

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Само уведомление — sync.NewOperationsNotifier; разрешение то же, что у автосинхронизации. */
    fun onToggleNotifyNewOperations(view: View) {
        val on = toggleSwitch(switchNotifyNewOperations)
        AppSettings.setNotifyNewOperationsEnabled(this, on)

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun intervalLabel(minutes: Int): String {
        if (minutes < 60) return getString(R.string.integrations_every_minutes, minutes)
        val hours = minutes / 60
        if (hours == 1) return getString(R.string.integrations_every_hour)
        return getString(R.string.integrations_every_hours, hours)
    }

    fun onPickAutoConnectInterval(view: View) {
        val items = Array(AUTO_CONNECT_INTERVALS_MINUTES.size) { i -> intervalLabel(AUTO_CONNECT_INTERVALS_MINUTES[i]) }

        AlertDialog.Builder(this)
                .setTitle(R.string.integrations_sync_interval)
                .setItems(items) { _, which ->
                    val minutes = AUTO_CONNECT_INTERVALS_MINUTES[which]
                    AppSettings.setAutoConnectIntervalMinutes(this, minutes)
                    rowAutoConnectInterval.text = intervalLabel(minutes)
                    SyncScheduler.reschedule(this)
                }
                .show()
    }

    /**
     * Ручной проход синка (в дополнение к фоновому по расписанию — см.
     * `SyncScheduler`), по прямому запросу пользователя: "сделать
     * прямо сейчас" + "показать максимум дебаг-информации, что именно
     * поменялось". Общий с `SyncWorker` код — [SyncRunner],
     * отличие только в том, что здесь результат показывается диалогом, а не
     * решает retry/success для WorkManager.
     *
     * Сеть и SQLite — на фоновом потоке (тот же приём, что импорт/экспорт
     * CSV в `ImportActivity`/`ExportActivity`: голый
     * `Thread`, без AsyncTask/Executor — простоты ради, для
     * одного нечастого действия по кнопке новая инфраструктура не нужна).
     */
    fun onSyncNow(view: View) {
        if (syncInProgress) return
        syncInProgress = true
        syncNowStatus.setText(R.string.integrations_syncing)

        Thread {
            val outcome = SyncRunner.runOnce(this)
            runOnUiThread {
                syncInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                syncNowStatus.text = ""
                refreshLinkStatus() // AuthSession мог обновить токен или пометить needsRelink
                showSyncResultDialog(outcome)
            }
        }.start()
    }

    private fun showSyncResultDialog(outcome: SyncRunner.Outcome) {
        val title: String
        val body: String?
        when (outcome.status) {
            SyncRunner.Status.NO_SESSION -> {
                title = getString(R.string.sync_result_no_session_title)
                body = getString(R.string.sync_result_no_session_body)
            }
            SyncRunner.Status.NEEDS_RELINK -> {
                title = getString(R.string.integrations_session_expired)
                body = getString(R.string.sync_result_relink_body)
            }
            SyncRunner.Status.NETWORK_ERROR -> {
                title = getString(R.string.sync_result_unavailable_title)
                body = outcome.errorMessage
            }
            SyncRunner.Status.AWAITING_DECISION -> {
                title = getString(R.string.sync_result_awaiting_title)
                body = getString(R.string.sync_result_awaiting_body)
            }
            SyncRunner.Status.PARTIAL -> {
                title = getString(R.string.sync_result_partial_title)
                body = outcome.errorMessage + "\n\n" + outcome.log.toDisplayText()
            }
            SyncRunner.Status.ERROR -> {
                title = getString(R.string.sync_result_error_title)
                body = outcome.errorMessage + "\n\n" + outcome.log.toDisplayText()
            }
            else -> {
                title = getString(R.string.sync_result_ok_title)
                body = outcome.log.toDisplayText()
            }
        }

        val text = TextView(this)
        text.text = body
        text.setTextIsSelectable(true) // можно выделить/скопировать для отчёта об ошибке
        val pad = (16 * resources.displayMetrics.density).toInt()
        text.setPadding(pad, pad, pad, pad)

        val scroll = ScrollView(this)
        scroll.addView(text)

        AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.action_close, null)
                .show()
    }

    /** Flips the pill on/off (bg_switch_on/off) and slides the thumb (start/end), returns the new state. */
    private fun toggleSwitch(switchView: FrameLayout): Boolean {
        val isOn = switchView.background != null
                && switchView.tag != null && switchView.tag as Boolean
        applySwitchState(switchView, !isOn)
        return !isOn
    }

    private fun applySwitchState(switchView: FrameLayout, on: Boolean) {
        switchView.tag = on
        switchView.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off)
        val thumb = switchView.getChildAt(0) ?: return
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (on) Gravity.END else Gravity.START
        thumb.layoutParams = lp
    }
}
