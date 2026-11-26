package com.example.wallettransactions.Activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.sync.AiApiClient
import com.example.wallettransactions.sync.DocsApiClient

import org.json.JSONException

import java.io.IOException

/**
 * Экран «Ещё» — таб нижней панели (navMore). Пункты, раньше жившие в боковом
 * drawer'е и меню Toolbar «Операций», теперь здесь (`activity_more.xml`).
 *
 * Блок «Интеграции» (сервер, связь с Google аккаунтом, Telegram, синхронизация)
 * до 2026-10-11 стоял прямо здесь — теперь это отдельный экран
 * [IntegrationsActivity], отсюда на него одна строка; рядом — «Открыть
 * веб-версию». «Импорт из файла» — в «Настройках» → «Данные».
 *
 * Общей шапки (период, фильтр, состояние денег) на этом экране нет — он ничего не
 * показывает за период, — но нижняя панель и её кнопка «+» те же, что на остальных
 * табах, поэтому экран всё равно наследует [BaseTabActivity].
 */
class MoreActivity : BaseTabActivity() {

    /** ИИ уже подтверждён сервером — на время жизни экрана повторно не спрашиваем. */
    private var aiAvailable = false

    /** Раздел «Вопрос по приложению» подтверждён сервером (`200` на статусе) — повторно не спрашиваем. */
    private var docsAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)
        initTabScreen(R.id.navMore, null)
    }

    override fun onResume() {
        super.onResume()
        refreshAiRow()
        refreshDocsRow()
    }

    /**
     * Пункт «ИИ»: без входа — неактивен сразу, без сети; иначе
     * `GET /api/ai/status` в фоне (spec ai-expense-entry, «Пункт «ИИ» в «Ещё»»).
     */
    private fun refreshAiRow() {
        if (!AiApiClient.hasSession(this)) {
            aiAvailable = false
            setAiRow(false, getString(R.string.more_ai_sign_in))
            return
        }
        if (aiAvailable) return
        setAiRow(false, getString(R.string.more_ai_checking))
        val app = applicationContext
        Thread {
            var reason: String
            var ok = false
            val session = AiApiClient.refreshSession(app)
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                reason = app.getString(R.string.more_ai_sign_in)
            } else if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                reason = app.getString(R.string.more_ai_no_server)
            } else {
                try {
                    ok = AiApiClient.create(app).status().canParseExpense()
                    reason = app.getString(if (ok) R.string.more_ai_available else R.string.more_ai_off)
                } catch (e: AiApiClient.HttpError) {
                    reason = app.getString(if (e.code == 401) R.string.more_ai_sign_in else R.string.more_ai_off)
                } catch (e: IOException) {
                    reason = app.getString(R.string.more_ai_no_server)
                } catch (e: JSONException) {
                    reason = app.getString(R.string.more_ai_off)
                }
            }
            val available = ok
            val hint = reason
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                aiAvailable = available
                setAiRow(available, hint)
            }
        }.start()
    }

    private fun setAiRow(enabled: Boolean, hint: String) {
        val row = findViewById<View>(R.id.rowAi)
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
        findViewById<TextView>(R.id.rowAiHint).text = hint
    }

    /**
     * Пункт «Вопрос по приложению»: скрыт по умолчанию, появляется только после `200`
     * на `GET /api/docs/status`; любой другой ответ, сеть или отсутствие входа — скрыт
     * без сообщений. Запрос в фоне — открытие экрана не ждёт (openspec docs-assistant, D1).
     */
    private fun refreshDocsRow() {
        if (!AiApiClient.hasSession(this)) {
            docsAvailable = false
            setDocsRow(false)
            return
        }
        if (docsAvailable) return
        val app = applicationContext
        Thread {
            var visible = false
            if (AiApiClient.refreshSession(app) == AiApiClient.SessionState.OK) {
                try {
                    visible = DocsApiClient.isVisibleFor(DocsApiClient.create(app).status())
                } catch (e: IOException) {
                    // нет сети — пункт скрыт
                }
            }
            val show = visible
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                docsAvailable = show
                setDocsRow(show)
            }
        }.start()
    }

    private fun setDocsRow(visible: Boolean) {
        findViewById<View>(R.id.rowDocsCard).visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun onMoreDocs(view: View) {
        startActivity(Intent(this, DocsAssistantActivity::class.java))
    }

    fun onMoreAi(view: View) {
        startActivity(Intent(this, AiActivity::class.java))
    }

    override fun reloadTabData() {
        // Список пунктов статичен — перезагружать нечего.
    }

    /** Справочник счетов: тот же список, что при вводе операции, но с правкой по тапу. */
    fun onMoreAccounts(view: View) {
        startActivity(AccountPickerActivity.buildCatalogIntent(this))
    }

    /** Правила, по которым операции заводятся сами — ежедневно/еженедельно/ежемесячно/ежегодно. */
    fun onMoreDebts(view: View) {
        startActivity(Intent(this, DebtsActivity::class.java))
    }

    /** Шаблоны операций, в том числе повторяющиеся (шаблон с расписанием). */
    fun onMoreTemplates(view: View) {
        startActivity(Intent(this, OperationTemplateListActivity::class.java))
    }

    fun onMoreIntegrations(view: View) {
        startActivity(Intent(this, IntegrationsActivity::class.java))
    }

    fun onMoreRates(view: View) {
        startActivity(Intent(this, CurrencyPairListActivity::class.java))
    }

    fun onMoreIcons(view: View) {
        startActivity(Intent(this, IconManagerActivity::class.java))
    }

    /**
     * Веб-версия живёт на том же гейтвее, что и синк, — открываем «Адрес сервера»
     * в браузере по умолчанию. До 2026-11-11 пункт стоял на «Интеграциях».
     * Браузера может не быть вовсе (или адрес кривой) — тогда тост, а не падение.
     */
    fun onMoreOpenWeb(view: View) {
        val url = AppSettings.getServerBaseUrl(this)
        try {
            AppLock.allowNextLeave()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.integrations_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    /** Импорт CSV/JSON — с 2026-11-11 в «Настройках» → «Данные», рядом с экспортом
     *  ([SettingsActivity.onImportFile]). */
    fun onMoreSettings(view: View) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
