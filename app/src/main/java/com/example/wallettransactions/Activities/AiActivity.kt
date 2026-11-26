package com.example.wallettransactions.Activities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.DbInit
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.ai.AiTexts
import com.example.wallettransactions.ai.DraftReconciler
import com.example.wallettransactions.ai.OperationPrefill
import com.example.wallettransactions.ai.ParseExpenseResult
import com.example.wallettransactions.sync.AiApiClient
import com.example.wallettransactions.sync.SyncLog
import com.example.wallettransactions.sync.SyncRunner
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Экран «ИИ»: трата своими словами через wallet-ai (openspec changes/add-ai-client,
 * spec ai-expense-entry; устройство — docs/ARCHITECTURE.md, «Экран «ИИ»»).
 *
 * «Разобрать» — синк ([SyncRunner.runOnceShared], до [SYNC_WAIT_MS]),
 * чтобы resolver решал по тому же справочнику, что на телефоне; затем задание
 * `parse_expense` и long-poll. Черновик правится и записывается в обычном
 * окне операции ([TransactionActivity], openspec changes/ai-expense-in-operation-window):
 * поля и подсказки собирает [OperationPrefill]. Если кошелька или категории
 * из черновика нет на телефоне, окно сразу не открывается — сначала блок ошибки.
 * На сервер операцию отвезёт обычный синк.
 *
 * Сеть — на голом `new Thread`, как у «Интеграций»; поздние ответы
 * отменённого разбора отсекает [generation]. Текст траты в logcat не пишется.
 */
class AiActivity : AppCompatActivity() {

    private fun loginText(): String = getString(R.string.more_ai_sign_in)
    private fun noNetworkText(): String = getString(R.string.more_ai_no_server)

    private lateinit var editText: EditText
    private lateinit var tvCounter: TextView
    private lateinit var btnMic: View
    private lateinit var btnParse: Button
    private lateinit var tvWarning: TextView
    private lateinit var progressBox: View
    private lateinit var tvProgress: TextView
    private lateinit var messageBox: View
    private lateinit var tvMessage: TextView
    private lateinit var tvDone: TextView
    private lateinit var problemBox: View
    private lateinit var tvProblem: TextView
    private lateinit var tvProblemDetails: TextView
    private lateinit var pendingBox: View

    /** Номер текущего разбора: ответ потока с другим номером уже никому не нужен. */
    @Volatile
    private var generation = 0

    @Volatile
    private var jobId: String? = null
    private var busy = false
    private var sessionMissing = false

    /** Исход синка перед текущим разбором — для деталей блока ошибки. */
    private var syncBeforeParse: SyncRunner.Outcome? = null

    /** Последний черновик — живёт до записи или нового «Разобрать» («Открыть снова»). */
    private var draft: ParseExpenseResult? = null
    private var sourceText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Поделиться» сразу после установки: база создаётся в фоне (fix-first-start-anr).
        if (DbInit.redirectIfNeeded(this)) return
        setContentView(R.layout.activity_ai)

        editText = findViewById(R.id.editText)
        tvCounter = findViewById(R.id.tvCounter)
        btnMic = findViewById(R.id.btnMic)
        btnParse = findViewById(R.id.btnParse)
        tvWarning = findViewById(R.id.tvWarning)
        progressBox = findViewById(R.id.progressBox)
        tvProgress = findViewById(R.id.tvProgress)
        messageBox = findViewById(R.id.messageBox)
        tvMessage = findViewById(R.id.tvMessage)
        tvDone = findViewById(R.id.tvDone)
        problemBox = findViewById(R.id.problemBox)
        tvProblem = findViewById(R.id.tvProblem)
        tvProblemDetails = findViewById(R.id.tvProblemDetails)
        pendingBox = findViewById(R.id.pendingBox)

        editText.addTextChangedListener(SimpleWatcher { updateInputState() })

        // Системное распознавание речи; нет его на устройстве — нет и кнопки.
        val canSpeak = speechIntent().resolveActivity(packageManager) != null
        btnMic.visibility = if (canSpeak) View.VISIBLE else View.GONE

        if (savedInstanceState != null) restoreDraft(savedInstanceState)
        takeSharedText(intent)
        sessionMissing = !AiApiClient.hasSession(this)
        if (sessionMissing) showMessage(loginText(), true)
        updateInputState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedText(intent)
    }

    override fun onDestroy() {
        if (isFinishing) cancelRunning()
        super.onDestroy()
    }

    fun btnUp(view: View) {
        finish()
    }

    // ── Ввод ──────────────────────────────────────────────────────────────────

    /** «Поделиться» → текст в поле; разбор сам не запускается (spec, «Текст из «Поделиться»»). */
    private fun takeSharedText(intent: Intent?) {
        if (intent == null || Intent.ACTION_SEND != intent.action) return
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: return
        editText.setText(shared.toString().trim())
        editText.setSelection(editText.length())
    }

    private fun updateInputState() {
        val len = editText.length()
        tvCounter.text = "$len / $MAX_TEXT"
        tvCounter.setTextColor(getColor(if (len > MAX_TEXT) R.color.colorexpanse else R.color.colorTextSecondary))
        btnParse.isEnabled = !busy && !sessionMissing && len > 0 && len <= MAX_TEXT
                && editText.text.toString().trim().isNotEmpty()
    }

    private fun speechIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_speech_prompt))
    }

    fun onMic(view: View) {
        try {
            startActivityForResult(speechIntent(), REQUEST_SPEECH)
        } catch (e: ActivityNotFoundException) {
            btnMic.visibility = View.GONE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPERATION) {
            onOperationResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK || data == null) return
        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (results == null || results.isEmpty()) return
        val heard = results[0]
        val current = editText.text.toString().trim()
        editText.setText(if (current.isEmpty()) heard else "$current $heard")
        editText.setSelection(editText.length())
    }

    // ── Разбор ────────────────────────────────────────────────────────────────

    fun onParse(view: View) {
        val text = editText.text.toString().trim()
        if (busy || text.isEmpty() || text.length > MAX_TEXT) return

        val gen = ++generation
        jobId = null
        busy = true
        sourceText = text
        clearDraft()
        messageBox.visibility = View.GONE
        tvDone.visibility = View.GONE
        tvWarning.visibility = View.GONE
        showProgress(getString(R.string.ai_syncing))
        updateInputState()

        val app = applicationContext
        Thread {
            val sync = SyncRunner.runOnceShared(app, SYNC_WAIT_MS)
            if (gen != generation) return@Thread
            if (sync.status == SyncRunner.Status.NO_SESSION || sync.status == SyncRunner.Status.NEEDS_RELINK) {
                runOnUiThread { finishWithMessage(gen, loginText(), true) }
                return@Thread
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                syncBeforeParse = sync
                if (sync.status != SyncRunner.Status.OK) {
                    tvWarning.text = AiTexts.syncWarning(sync)
                    tvWarning.visibility = View.VISIBLE
                }
                tvProgress.setText(R.string.ai_parsing)
            }
            parse(gen, app, text)
        }.start()
    }

    /** Фоновый поток: задание и long-poll до конечного статуса. */
    private fun parse(gen: Int, app: Context, text: String) {
        try {
            val session = AiApiClient.refreshSession(app)
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                runOnUiThread { finishWithMessage(gen, loginText(), true) }
                return
            }
            if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                runOnUiThread { finishWithMessage(gen, noNetworkText(), true) }
                return
            }
            val client = AiApiClient.create(app)
            var job = client.createJob(
                text, LocalDateTime.now(), liveDefaultWalletUuid(app),
                "android:" + UUID.randomUUID()
            )
            jobId = job.id
            while (job.isPending()) {
                if (gen != generation) return
                job = client.getJob(job.id, AiApiClient.WAIT_SECS)
            }
            val done = job
            runOnUiThread { onJobFinished(gen, done) }
        } catch (e: AiApiClient.HttpError) {
            runOnUiThread { finishWithMessage(gen, httpErrorText(e), e.code != 429 && e.code != 404) }
        } catch (e: IOException) {
            runOnUiThread { finishWithMessage(gen, noNetworkText(), true) }
        } catch (e: JSONException) {
            runOnUiThread { finishWithMessage(gen, getString(R.string.ai_bad_response), true) }
        }
    }

    private fun httpErrorText(e: AiApiClient.HttpError): String {
        return when (e.code) {
            429 -> getString(R.string.ai_busy)
            503 -> getString(R.string.more_ai_off)
            404 -> getString(R.string.ai_result_lost)
            401 -> loginText()
            403 -> getString(R.string.ai_http_forbidden)
            else -> getString(R.string.ai_http_error, e.code)
        }
    }

    private fun onJobFinished(gen: Int, job: AiApiClient.Job) {
        if (gen != generation) return
        endBusy()
        if ("cancelled" == job.status) return
        val r = job.result
        if ("failed" == job.status || r == null) {
            showMessage(getString(R.string.ai_timeout), true)
            return
        }
        if (r.isDraft() && r.expense != null && r.resolution != null) {
            onDraft(r)
        } else if (ParseExpenseResult.REJECTED == r.outcome) {
            showMessage(AiTexts.rejected(r.reasons), true)
        } else {
            showMessage(getString(R.string.ai_no_expense), true)
        }
    }

    private fun finishWithMessage(gen: Int, text: String, offerManual: Boolean) {
        if (gen != generation) return
        endBusy()
        showMessage(text, offerManual)
    }

    fun onCancel(view: View) {
        cancelRunning()
        endBusy()
    }

    /** Новый номер разбора — поздние ответы игнорируются; задание на сервере снимается. */
    private fun cancelRunning() {
        generation++
        val id = jobId
        jobId = null
        if (id == null) return
        val app = applicationContext
        Thread {
            try {
                AiApiClient.create(app).cancelJob(id)
            } catch (ignored: IOException) {
                // Не отменилось — сервер сам выбросит результат по TTL.
            }
        }.start()
    }

    private fun endBusy() {
        busy = false
        progressBox.visibility = View.GONE
        updateInputState()
    }

    private fun showProgress(text: String) {
        tvProgress.text = text
        progressBox.visibility = View.VISIBLE
    }

    private fun showMessage(text: String, offerManual: Boolean) {
        tvMessage.text = text
        findViewById<View>(R.id.btnManual).visibility = if (offerManual) View.VISIBLE else View.GONE
        messageBox.visibility = View.VISIBLE
    }

    /** Обычный экран новой операции с исходным текстом в комментарии. */
    fun onManual(view: View) {
        val text = sourceText ?: editText.text.toString().trim()
        val intent = Intent(this, TransactionActivity::class.java)
        intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(Date()))
        intent.putExtra(TransactionActivity.cComment, text)
        intent.putExtra(TransactionActivity.cAmount, 0.0)
        intent.putExtra(TransactionActivity.cIsEdit, false)
        intent.putExtra(TransactionActivity.cKind, "expense")
        startActivity(intent)
    }

    /** «Загрузить выписку»: экран «Выписка» сразу с системным выбором файла. */
    fun onStatementImport(view: View) {
        startActivity(
            Intent(this, StatementImportActivity::class.java)
                .putExtra(StatementImportActivity.EXTRA_PICK_FILE, true)
        )
    }

    // ── Черновик → окно операции ─────────────────────────────────────────────

    private fun onDraft(r: ParseExpenseResult) {
        draft = r
        // Синк перед разбором мог принести новые счета — пикеры окна читают кэш.
        Singleton.instance.reloadAccounts(this)
        openOrExplain()
    }

    /** Пропавших счетов нет — сразу окно; есть — блок ошибки на экране «ИИ». */
    private fun openOrExplain() {
        val prefill = prefill()
        if (prefill.hasProblem()) {
            showProblem(prefill)
        } else {
            problemBox.visibility = View.GONE
            openOperation(prefill)
        }
    }

    private fun prefill(): OperationPrefill {
        return OperationPrefill.build(Singleton.instance.getReadableDatabase(this), draft!!, sourceText)
    }

    private fun openOperation(p: OperationPrefill) {
        pendingBox.visibility = View.GONE
        val intent = Intent(this, TransactionActivity::class.java)
        intent.putExtra(TransactionActivity.cDate, p.date ?: DateUtils.DateToStr(Date()))
        intent.putExtra(TransactionActivity.cAmount, p.amount)
        intent.putExtra(TransactionActivity.cComment, p.comment)
        intent.putExtra(TransactionActivity.cFrom, p.walletId)
        intent.putExtra(TransactionActivity.cTo, p.categoryId)
        intent.putExtra(TransactionActivity.cIsEdit, false)
        intent.putExtra(TransactionActivity.cKind, "expense")
        intent.putExtra(TransactionActivity.cHint, p.hint)
        intent.putExtra(TransactionActivity.cPriorityWalletIds, p.priorityWalletIds)
        intent.putExtra(TransactionActivity.cPriorityCategoryIds, p.priorityCategoryIds)
        startActivityForResult(intent, REQUEST_OPERATION)
    }

    /** «Открыть операцию» (блок ошибки) и «Открыть снова» — тот же черновик, без запроса к ИИ. */
    fun onOpenOperation(view: View) {
        if (draft == null) return
        problemBox.visibility = View.GONE
        openOperation(prefill())
    }

    private fun showProblem(p: OperationPrefill) {
        val lines = ArrayList<String>()
        val walletProblem = p.walletProblem
        val categoryProblem = p.categoryProblem
        if (walletProblem != null) {
            lines.add(AiTexts.problem(true, walletProblem.ref.name, walletProblem.state))
        }
        if (categoryProblem != null) {
            lines.add(AiTexts.problem(false, categoryProblem.ref.name, categoryProblem.state))
        }
        val uuid = if (walletProblem != null) walletProblem.ref.uuid else categoryProblem!!.ref.uuid
        tvProblem.text = lines.joinToString("\n")
        tvProblemDetails.text = AiTexts.details(
            syncBeforeParse,
            AppSettings.getLastSyncSuccessAt(this), uuid, Date()
        )
        pendingBox.visibility = View.GONE
        problemBox.visibility = View.VISIBLE
    }

    /** «Синхронизировать и повторить»: синк и сверка того же черновика, без нового задания. */
    fun onSyncRetry(view: View) {
        if (busy || draft == null) return
        busy = true
        val gen = generation
        showProgress(getString(R.string.ai_syncing))
        updateInputState()
        val app = applicationContext
        Thread {
            val sync = SyncRunner.runOnceShared(app, SYNC_WAIT_MS)
            runOnUiThread {
                if (gen != generation || isFinishing) return@runOnUiThread
                syncBeforeParse = sync
                endBusy()
                if (draft == null) return@runOnUiThread
                Singleton.instance.reloadAccounts(this)
                openOrExplain()
            }
        }.start()
    }

    /** Возврат из окна: сохранено — «Записано: …» по операции из базы; нет — «Разбор не записан». */
    private fun onOperationResult(resultCode: Int, data: Intent?) {
        val id = if (resultCode == RESULT_OK && data != null)
            data.getLongExtra(TransactionActivity.cSavedOperationId, -1) else -1L
        if (id <= 0) {
            if (draft != null) pendingBox.visibility = View.VISIBLE
            return
        }

        val db = Singleton.instance.getReadableDatabase(this)
        val done = savedLine(db, id)
        PeriodFilterState.get(this).markDataChanged()
        // Отвезти трату на сервер сразу, не дожидаясь фонового синка; ждать его экрану незачем.
        val app = applicationContext
        Thread { SyncRunner.runOnce(app) }.start()

        tvDone.text = done
        tvDone.visibility = if (done != null) View.VISIBLE else View.GONE
        clearDraft()
        editText.setText("")
        sourceText = null
        tvWarning.visibility = View.GONE
    }

    /**
     * «Записано: 450 ₽ · Такси · Тинькофф» по сохранённой операции — пользователь мог
     * сменить в окне сумму, счета и вид. Заодно запоминает кошелёк для default_wallet_uuid.
     */
    private fun savedLine(db: SQLiteDatabase, id: Long): String? {
        val c = db.rawQuery(
            "SELECT amount, acc_fr_id, acc_to_id FROM operation WHERE _id=? LIMIT 1",
            arrayOf(id.toString())
        )
        try {
            if (!c.moveToFirst()) return null
            val amount = BigDecimal.valueOf(c.getDouble(0)).stripTrailingZeros()
            Singleton.instance.reloadAccounts(this)
            val accounts = Singleton.instance.getAccounts(this)
            val from = accounts.getAccountById(c.getInt(1))
            val to = accounts.getAccountById(c.getInt(2))
            val wallet = if (from != null && from.isOperatingAccount) from else to
            val other = if (wallet === from) to else from
            if (wallet != null) AppSettings.setAiLastWalletUuid(this, serverUuid(db, wallet.id.toLong()))
            val symbol = if (wallet is AccountOperatingItem) " " + wallet.currency!!.symbol else ""
            return getString(
                R.string.ai_written, amount.toPlainString() + symbol
                        + " · " + (other?.displayName ?: "")
                        + " · " + (wallet?.displayName ?: "")
            )
        } finally {
            c.close()
        }
    }

    private fun clearDraft() {
        draft = null
        problemBox.visibility = View.GONE
        pendingBox.visibility = View.GONE
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val d = draft ?: return
        out.putString(STATE_DRAFT, d.json)
        out.putString(STATE_SOURCE, sourceText)
        val sync = syncBeforeParse
        if (sync != null) {
            out.putString(STATE_SYNC_STATUS, sync.status.name)
            out.putString(STATE_SYNC_MESSAGE, sync.errorMessage)
        }
    }

    /** Черновик после пересоздания экрана: окно операции могло быть открыто поверх. */
    private fun restoreDraft(`in`: Bundle) {
        val json = `in`.getString(STATE_DRAFT) ?: return
        try {
            draft = ParseExpenseResult.fromJson(JSONObject(json))
        } catch (e: JSONException) {
            return
        }
        sourceText = `in`.getString(STATE_SOURCE)
        val status = `in`.getString(STATE_SYNC_STATUS)
        if (status != null) {
            syncBeforeParse = SyncRunner.Outcome(
                SyncRunner.Status.valueOf(status), SyncLog(),
                `in`.getString(STATE_SYNC_MESSAGE)
            )
        }
        pendingBox.visibility = View.VISIBLE
    }

    /** TextWatcher одним методом. */
    private class SimpleWatcher(private val onChange: Runnable) : TextWatcher {
        constructor(block: () -> Unit) : this(Runnable { block() })

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            onChange.run()
        }
    }

    companion object {
        const val MAX_TEXT = 500
        const val SYNC_WAIT_MS = 10_000L
        private const val REQUEST_SPEECH = 41
        private const val REQUEST_OPERATION = 42

        private const val STATE_DRAFT = "ai.draft"
        private const val STATE_SOURCE = "ai.source"
        private const val STATE_SYNC_STATUS = "ai.syncStatus"
        private const val STATE_SYNC_MESSAGE = "ai.syncMessage"

        /** default_wallet_uuid — только если этот кошелёк жив на телефоне. */
        private fun liveDefaultWalletUuid(ctx: Context): String? {
            val uuid = AppSettings.getAiLastWalletUuid(ctx) ?: return null
            val ref = ParseExpenseResult.Ref(uuid, "", "", "", 2)
            val db = Singleton.instance.getReadableDatabase(ctx)
            return if (DraftReconciler.lookup(db, ref).state == DraftReconciler.State.OK) uuid else null
        }

        /** uuid строки в серверном написании (с дефисами, строчными): локально бывает и без дефисов. */
        @JvmStatic
        fun serverUuid(db: SQLiteDatabase, id: Long): String? {
            val c = db.rawQuery("SELECT uuid FROM account WHERE _id=? LIMIT 1", arrayOf(id.toString()))
            try {
                if (!c.moveToFirst() || c.isNull(0)) return null
                val plain = c.getString(0).lowercase(Locale.ROOT).replace("-", "")
                if (plain.length != 32) return c.getString(0)
                return (plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16)
                        + "-" + plain.substring(16, 20) + "-" + plain.substring(20))
            } finally {
                c.close()
            }
        }
    }
}
