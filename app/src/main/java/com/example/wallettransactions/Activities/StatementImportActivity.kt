package com.example.wallettransactions.Activities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.Data.DbInit
import com.example.wallettransactions.Data.DebtAutoArchive
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.ai.AiTexts
import com.example.wallettransactions.ai.DraftReconciler
import com.example.wallettransactions.ai.ParseExpenseResult.Ref
import com.example.wallettransactions.ai.StatementDuplicates
import com.example.wallettransactions.ai.StatementImport
import com.example.wallettransactions.ai.StatementRowState
import com.example.wallettransactions.ai.StatementRowState.Kind
import com.example.wallettransactions.sync.AiApiClient
import com.example.wallettransactions.sync.SyncRunner

import org.json.JSONException

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * Экран «Выписка»: импорт банковской выписки через wallet-ai (openspec
 * changes/add-ai-statement-import, spec ai-statement-import; устройство —
 * docs/ARCHITECTURE.md, «Экран «Выписка»»).
 *
 * Файл (выбор или «Поделиться») и кошелёк → синк → `POST /api/ai/imports`.
 * Разбор идёт на сервере; экран опрашивает импорт, только пока открыт, и находит
 * незавершённый импорт заново через `GET /api/ai/imports`. Строки готового
 * импорта берутся с сервера при каждом открытии и на телефоне не хранятся. Строка
 * записывается операцией с `uuid = row_id`: по одной — в окне операции
 * ([TransactionActivity.cOpUuid]), пачкой — «Записать готовые». Дубли ищет
 * телефон по полям ([StatementDuplicates]). Записанное отмечается на сервере
 * (`/committed`); не дошедшая отметка досылается при следующем открытии.
 *
 * Сеть — на голом `Thread`, как у экрана «ИИ»; поздние ответы
 * отсекает [generation]. Суммы и описания строк в logcat не пишутся.
 */
class StatementImportActivity : AppCompatActivity() {

    companion object {
        /** Открыть сразу системный выбор файла (кнопка «Загрузить выписку» на экране «ИИ»). */
        const val EXTRA_PICK_FILE = "StatementImport.PickFile"

        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        const val POLL_MS = 3_000L

        private const val REQUEST_FILE = 51
        private const val REQUEST_WALLET = 52
        private const val REQUEST_ROW = 53

        private const val STATE_FILE_URI = "statement.fileUri"
        private const val STATE_FILE_NAME = "statement.fileName"
        private const val STATE_FILE_SIZE = "statement.fileSize"
        private const val STATE_WALLET = "statement.walletId"
        private const val STATE_IMPORT = "statement.importId"
        private const val STATE_ROW = "statement.pendingRowId"

        /** Сервер узнаёт формат по расширению: у файла из «Поделиться» его может не быть. */
        @JvmStatic
        fun withExtension(name: String, mime: String?): String {
            val lower = name.lowercase(Locale.ROOT)
            if (lower.endsWith(".pdf") || lower.endsWith(".csv") || lower.endsWith(".txt")) return name
            if ("application/pdf" == mime) return "$name.pdf"
            if (mime != null && mime.contains("csv")) return "$name.csv"
            if ("text/plain" == mime) return "$name.txt"
            return name
        }

        /** Клиент со свежим токеном; null — входа нет. Сетевая ошибка — IOException. */
        @Throws(IOException::class)
        private fun sessionClient(app: Context): AiApiClient? {
            val s = AiApiClient.refreshSession(app)
            if (s == AiApiClient.SessionState.NO_SESSION || s == AiApiClient.SessionState.NEEDS_RELINK) return null
            if (s == AiApiClient.SessionState.NETWORK_ERROR) throw IOException("no network")
            return AiApiClient.create(app)
        }

        /**
         * Строки «Добавлена» (на телефоне есть операция с их uuid), которые сервер ещё
         * не отметил (`committed: false`, например не было сети), — в `/committed`.
         */
        private fun resendCommits(client: AiApiClient, imp: StatementImport, app: Context): StatementImport {
            if (StatementImport.READY != imp.status) return imp
            val db = Singleton.instance.getReadableDatabase(app)
            val pending = ArrayList<String>()
            for (r in imp.rows) {
                if (!r.committed && isAdded(db, r.rowId)) pending.add(r.rowId!!)
            }
            if (pending.isEmpty()) return imp
            return try {
                client.commitImportRows(imp.id!!, pending)
            } catch (e: IOException) {
                imp // досылка — в следующее открытие
            } catch (e: JSONException) {
                imp
            }
        }

        private fun isAdded(db: SQLiteDatabase, rowId: String?): Boolean {
            val m = WalletDbCRUD.FindOperationByUuid(db, rowId!!)
            return m != null && !m.deleted
        }

        /** Состояния строк по базе телефона — в фоновом потоке. */
        @JvmStatic
        fun buildModels(app: Context, imp: StatementImport): List<RowModel> {
            val db = Singleton.instance.getReadableDatabase(app)
            val wallet = DraftReconciler.lookup(db, Ref(imp.walletUuid ?: "", "", "", "", 2))
            val ids = ArrayList<String>()
            val candidates = ArrayList<StatementImport.Row>()
            val added = HashSet<String>()
            for (r in imp.rows) {
                ids.add(r.rowId!!)
                if (isAdded(db, r.rowId)) added.add(r.rowId)
                else if (r.isExpense()) candidates.add(r)
            }
            val dups: Map<String, StatementDuplicates.Match> = if (wallet.state == DraftReconciler.State.OK)
                StatementDuplicates.find(db, wallet.id, candidates, ids)
            else
                emptyMap()
            val today = LocalDate.now()
            val out = ArrayList<RowModel>()
            for (r in imp.rows) {
                val dup = dups[r.rowId]
                val kind = StatementRowState.of(r, added.contains(r.rowId), dup)
                out.add(RowModel(r, kind, dup, StatementRowState.readiness(db, r, kind, wallet.state, today),
                        StatementRowState.category(db, r.categoryUuid)))
            }
            return out
        }

        /** Серверный uuid категории сохранённого расхода; null — это не расход «кошелёк → категория». */
        private fun savedCategoryUuid(db: SQLiteDatabase, operationId: Long): String? {
            val c = db.rawQuery("SELECT f.kind, t.kind, t._id FROM operation o"
                    + " JOIN account f ON f._id = o.acc_fr_id JOIN account t ON t._id = o.acc_to_id WHERE o._id=?",
                    arrayOf(operationId.toString()))
            try {
                if (!c.moveToFirst() || c.getInt(0) != 1 || c.getInt(1) == 1) return null
                return AiActivity.serverUuid(db, c.getLong(2))
            } finally {
                c.close()
            }
        }

        private fun sameUuid(a: String?, b: String?): Boolean {
            if (a == null || b == null) return a === b
            return a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)
        }

        private fun dayText(date: String?): String {
            return if (date != null && date.length >= 10)
                date.substring(8, 10) + "." + date.substring(5, 7) + "." + date.substring(0, 4)
            else ""
        }
    }

    private fun loginText(): String = getString(R.string.more_ai_sign_in)
    private fun noNetworkText(): String = getString(R.string.more_ai_no_server)

    /** Строка списка: строка импорта и что с ней на телефоне. */
    class RowModel(
            @JvmField val row: StatementImport.Row,
            @JvmField val kind: Kind,
            @JvmField val duplicate: StatementDuplicates.Match?,
            @JvmField val readiness: StatementRowState.Readiness,
            /** Категория строки на телефоне (имя — для подписи). */
            @JvmField val category: StatementRowState.Category)

    private lateinit var pickBox: View
    private lateinit var tvFile: TextView
    private lateinit var tvWallet: TextView
    private lateinit var tvPickError: TextView
    private lateinit var btnUpload: Button
    private lateinit var progressBox: View
    private lateinit var tvProgress: TextView
    private lateinit var messageBox: View
    private lateinit var tvMessage: TextView
    private lateinit var listBox: View
    private lateinit var tvListTitle: TextView
    private lateinit var tvListSummary: TextView
    private lateinit var tvListNote: TextView
    private lateinit var btnWriteReady: Button
    private lateinit var rowsContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }

    private var fileUri: Uri? = null
    private var fileName: String? = null
    private var fileSize = 0L
    private var walletId = 0
    private var importId: String? = null
    /** row_id строки, открытой в окне операции, — ждём её результата. */
    private var pendingRowId: String? = null

    /** Последний ответ сервера по импорту и строки списка по нему. */
    private var current: StatementImport? = null
    private var models: List<RowModel> = ArrayList()
    /** Локальный `_id` кошелька выписки, если он жив на телефоне; иначе 0. */
    private var importWalletId = 0

    @Volatile
    private var generation = 0
    private var busy = false
    private var started = false
    /** Строки показаны после синка в этом открытии экрана — повторно не синкаем на каждый опрос. */
    private var syncedForList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Поделиться» сразу после установки: база создаётся в фоне (fix-first-start-anr).
        if (DbInit.redirectIfNeeded(this)) return
        setContentView(R.layout.activity_statement_import)

        pickBox = findViewById(R.id.pickBox)
        tvFile = findViewById(R.id.tvFile)
        tvWallet = findViewById(R.id.tvWallet)
        tvPickError = findViewById(R.id.tvPickError)
        btnUpload = findViewById(R.id.btnUpload)
        progressBox = findViewById(R.id.progressBox)
        tvProgress = findViewById(R.id.tvProgress)
        messageBox = findViewById(R.id.messageBox)
        tvMessage = findViewById(R.id.tvMessage)
        listBox = findViewById(R.id.listBox)
        tvListTitle = findViewById(R.id.tvListTitle)
        tvListSummary = findViewById(R.id.tvListSummary)
        tvListNote = findViewById(R.id.tvListNote)
        btnWriteReady = findViewById(R.id.btnWriteReady)
        rowsContainer = findViewById(R.id.rowsContainer)

        if (savedInstanceState != null) {
            val uri = savedInstanceState.getString(STATE_FILE_URI)
            fileUri = if (uri != null) Uri.parse(uri) else null
            fileName = savedInstanceState.getString(STATE_FILE_NAME)
            fileSize = savedInstanceState.getLong(STATE_FILE_SIZE)
            walletId = savedInstanceState.getInt(STATE_WALLET)
            importId = savedInstanceState.getString(STATE_IMPORT)
            pendingRowId = savedInstanceState.getString(STATE_ROW)
        } else {
            if (forwardSharedText(intent)) return
            walletId = liveWalletOrZero(AppSettings.getLastWalletId(this))
            takeSharedFile(intent)
            if (intent.getBooleanExtra(EXTRA_PICK_FILE, false)) onPickFile(null)
        }
        showPick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (takeSharedFile(intent)) {
            importId = null
            current = null
            showPick()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        syncedForList = false
        if (pendingRowId != null) return // ждём результата окна операции
        if (importId != null) refresh()
        else if (fileUri == null) findExisting()
    }

    override fun onStop() {
        // Уход с экрана — опрос прекращается, разбор на сервере идёт дальше (решение 9).
        started = false
        handler.removeCallbacks(poll)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (fileUri != null) out.putString(STATE_FILE_URI, fileUri.toString())
        out.putString(STATE_FILE_NAME, fileName)
        out.putLong(STATE_FILE_SIZE, fileSize)
        out.putInt(STATE_WALLET, walletId)
        out.putString(STATE_IMPORT, importId)
        out.putString(STATE_ROW, pendingRowId)
    }

    fun btnUp(view: View) {
        finish()
    }

    // ── Файл и кошелёк ───────────────────────────────────────────────────────

    /** «Поделиться» файлом: экран с этим файлом, загрузка не начата. */
    @Suppress("DEPRECATION")
    private fun takeSharedFile(intent: Intent?): Boolean {
        if (intent == null || Intent.ACTION_SEND != intent.action) return false
        val uri: Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: return false
        setFile(uri, intent.type)
        return true
    }

    /**
     * В «Выписку» поделились текстом, а не файлом (text/plain без EXTRA_STREAM) —
     * это трата своими словами: текст уходит на экран «ИИ».
     */
    @Suppress("DEPRECATION")
    private fun forwardSharedText(intent: Intent?): Boolean {
        if (intent == null || Intent.ACTION_SEND != intent.action) return false
        if (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) != null) return false
        if (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) == null) return false
        startActivity(Intent(intent).setClass(this, AiActivity::class.java))
        finish()
        return true
    }

    fun onPickFile(view: View?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/pdf", "text/csv", "text/comma-separated-values", "text/plain"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_FILE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.statement_no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    fun onPickWallet(view: View) {
        @Suppress("DEPRECATION")
        startActivityForResult(AccountPickerActivity.buildIntent(this, getString(R.string.statement_wallet_picker),
                AccountPickerActivity.cSelectedTypeOperationAccounts, walletId)
                .putExtra(AccountPickerActivity.cHideDebts, true), REQUEST_WALLET)
    }

    private fun setFile(uri: Uri, mimeIn: String?) {
        fileUri = uri
        fileName = null
        fileSize = -1
        try {
            contentResolver.query(uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) fileName = c.getString(0)
                    if (!c.isNull(1)) fileSize = c.getLong(1)
                }
            }
        } catch (e: SecurityException) {
            // имя и размер узнаем при чтении
        } catch (e: IllegalArgumentException) {
            // имя и размер узнаем при чтении
        }
        val mime = mimeIn ?: contentResolver.getType(uri)
        fileName = withExtension(fileName ?: "statement", mime)
        tvPickError.visibility = View.GONE
    }

    private fun liveWalletOrZero(id: Int): Int {
        if (id <= 0) return 0
        val s = DraftReconciler.stateById(Singleton.instance.getReadableDatabase(this), id.toLong())
        val a: AccountItem? = Singleton.instance.getAccounts(this).getAccountById(id)
        // Заём (debts, D13) кошельком выписки не предлагается — выписка банка, а не долга.
        val debt = a is AccountOperatingItem && a.isDebt
        return if (s == DraftReconciler.State.OK && a != null && a.isOperatingAccount && !debt) id else 0
    }

    private fun showPick() {
        handler.removeCallbacks(poll)
        pickBox.visibility = View.VISIBLE
        progressBox.visibility = View.GONE
        messageBox.visibility = View.GONE
        listBox.visibility = View.GONE
        tvFile.text = if (fileUri == null) "" else fileName + (if (fileSize >= 0) " · " + sizeText(fileSize) else "")
        val wallet: AccountItem? = if (walletId > 0) Singleton.instance.getAccounts(this).getAccountById(walletId) else null
        tvWallet.text = if (wallet != null) wallet.displayName else ""
        if (fileSize > MAX_FILE_BYTES) showPickError(AiTexts.statementFileTooBig())
        updateUploadButton()
    }

    private fun updateUploadButton() {
        btnUpload.isEnabled = !busy && fileUri != null && walletId > 0 && fileSize <= MAX_FILE_BYTES
    }

    private fun showPickError(text: String) {
        tvPickError.text = text
        tvPickError.visibility = View.VISIBLE
    }

    private fun sizeText(bytes: Long): String {
        if (bytes < 1024 * 1024) return getString(R.string.statement_size_kb, Math.max(1, bytes / 1024))
        return getString(R.string.statement_size_mb,
                String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0).replace('.', ','))
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────

    fun onUpload(view: View) {
        val uri = fileUri
        if (busy || uri == null || walletId <= 0) return
        if (fileSize > MAX_FILE_BYTES) {
            showPickError(AiTexts.statementFileTooBig())
            return
        }
        val gen = ++generation
        busy = true
        tvPickError.visibility = View.GONE
        updateUploadButton()
        showProgress(getString(R.string.ai_syncing))
        val app = applicationContext
        val name = fileName
        val wallet = walletId
        Thread {
            // Кошелёк, созданный на телефоне, иначе серверу неизвестен (unknown_wallet).
            val sync = SyncRunner.runOnceShared(app, AiActivity.SYNC_WAIT_MS)
            if (sync.status == SyncRunner.Status.NO_SESSION || sync.status == SyncRunner.Status.NEEDS_RELINK) {
                uploadFailed(gen, loginText())
                return@Thread
            }
            runOnUiThread { if (gen == generation) tvProgress.setText(R.string.statement_uploading) }
            val bytes: ByteArray?
            try {
                bytes = readFile(uri)
            } catch (e: IOException) {
                // Доступ к URI мог пропасть (процесс пересоздан, источник отозвал право) — не сеть.
                uploadFailed(gen, AiTexts.statementFileUnreadable())
                return@Thread
            } catch (e: SecurityException) {
                uploadFailed(gen, AiTexts.statementFileUnreadable())
                return@Thread
            }
            if (bytes == null) {
                uploadFailed(gen, AiTexts.statementFileTooBig())
                return@Thread
            }
            try {
                val client = sessionClient(app)
                if (client == null) {
                    uploadFailed(gen, loginText())
                    return@Thread
                }
                val walletUuid = AiActivity.serverUuid(Singleton.instance.getReadableDatabase(app), wallet.toLong())
                val imp = client.createImport(walletUuid, name, bytes)
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    busy = false
                    if (StatementImport.FAILED == imp.status) {
                        showPick()
                        showPickError(AiTexts.statementFailure(imp.failure))
                        return@runOnUiThread
                    }
                    fileUri = null
                    importId = imp.id
                    current = imp
                    showProgress(AiTexts.statementProgress(imp))
                    schedulePoll()
                }
            } catch (e: AiApiClient.HttpError) {
                if (e.code == 429 && "import_in_progress" == e.error && e.importId.isNotEmpty()) {
                    // Уже идёт другой импорт — открываем его; файл и кошелёк остаются для «Загрузить другую».
                    runOnUiThread {
                        if (gen != generation) return@runOnUiThread
                        busy = false
                        importId = e.importId
                        Toast.makeText(this, AiTexts.statementHttpError(e.code, e.error), Toast.LENGTH_LONG).show()
                        refresh()
                    }
                    return@Thread
                }
                uploadFailed(gen, AiTexts.statementHttpError(e.code, e.error))
            } catch (e: IOException) {
                uploadFailed(gen, noNetworkText())
            } catch (e: JSONException) {
                uploadFailed(gen, getString(R.string.ai_bad_response))
            }
        }.start()
    }

    /** Отказ загрузки: причина под формой, файл и кошелёк не теряются. */
    private fun uploadFailed(gen: Int, text: String) {
        runOnUiThread {
            if (gen != generation) return@runOnUiThread
            busy = false
            showPick()
            showPickError(text)
        }
    }

    /** Файл целиком; null — больше 5 МБ (размер мог быть неизвестен до чтения). */
    @Throws(IOException::class)
    private fun readFile(uri: Uri): ByteArray? {
        contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) throw IOException("file did not open")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (stream.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE_BYTES) return null
            }
            return out.toByteArray()
        }
    }

    // ── Ожидание и список ────────────────────────────────────────────────────

    /** Открытие экрана без файла: незавершённый (или готовый к проверке) импорт продолжается. */
    private fun findExisting() {
        val gen = ++generation
        val app = applicationContext
        Thread {
            try {
                val client = sessionClient(app) ?: return@Thread
                for (imp in client.listImports()) {
                    if (!imp.isActive() && StatementImport.READY != imp.status) continue
                    runOnUiThread {
                        if (gen != generation || fileUri != null || importId != null) return@runOnUiThread
                        importId = imp.id
                        refresh()
                    }
                    return@Thread
                }
            } catch (e: IOException) {
                // Нет сети — остаётся форма загрузки, ошибку покажет сама загрузка.
            } catch (e: JSONException) {
                // Нет сети — остаётся форма загрузки, ошибку покажет сама загрузка.
            }
        }.start()
    }

    private fun schedulePoll() {
        handler.removeCallbacks(poll)
        if (started && importId != null) handler.postDelayed(poll, POLL_MS)
    }

    /** `GET /imports/{id}`; у готового — синк (раз за открытие), сверка с телефоном и досылка отметок. */
    private fun refresh() {
        val id = importId
        if (id == null || busy) return
        val gen = ++generation
        val needSync = !syncedForList
        val app = applicationContext
        val cur = current
        if (cur == null || !cur.hasRows()) showProgress(if (cur != null) AiTexts.statementProgress(cur) else getString(R.string.statement_loading))
        Thread {
            try {
                val client = sessionClient(app)
                if (client == null) {
                    runOnUiThread { if (gen == generation) showMessage(loginText()) }
                    return@Thread
                }
                var imp = client.getImport(id)
                if (imp.hasRows()) {
                    if (needSync) {
                        // Операции из веба и бота — чтобы проверка на дубли видела их.
                        runOnUiThread { if (gen == generation && listBox.visibility != View.VISIBLE) tvProgress.setText(R.string.ai_syncing) }
                        SyncRunner.runOnceShared(app, AiActivity.SYNC_WAIT_MS)
                        Singleton.instance.reloadAccounts(app)
                    }
                    imp = resendCommits(client, imp, app)
                }
                val done = imp
                val built: List<RowModel>? = if (done.hasRows()) buildModels(app, done) else null
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    if (needSync && done.hasRows()) syncedForList = true
                    onImport(done, built)
                }
            } catch (e: AiApiClient.HttpError) {
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    if (e.code == 404) {
                        importId = null
                        current = null
                        showPick()
                        showPickError(getString(R.string.statement_not_found))
                    } else {
                        showListOrMessage(getString(R.string.ai_http_error, e.code))
                    }
                }
            } catch (e: IOException) {
                runOnUiThread { if (gen == generation) showListOrMessage(AiTexts.statementNoNetwork()) }
            } catch (e: JSONException) {
                runOnUiThread { if (gen == generation) showListOrMessage(AiTexts.statementNoNetwork()) }
            }
        }.start()
    }

    private fun onImport(imp: StatementImport, built: List<RowModel>?) {
        current = imp
        if (imp.isActive()) {
            showProgress(AiTexts.statementProgress(imp))
            schedulePoll()
            return
        }
        if (StatementImport.FAILED == imp.status) {
            showMessage(AiTexts.statementFailure(imp.failure))
            return
        }
        if (!imp.hasRows()) {
            showMessage(getString(R.string.statement_cancelled))
            return
        }
        models = built!!
        showList()
    }

    /** Без сети: список, если он уже был показан (остаётся рабочим), иначе — сообщение. */
    private fun showListOrMessage(text: String) {
        val cur = current
        if (listBox.visibility == View.VISIBLE) {
            tvListNote.text = text
            tvListNote.visibility = View.VISIBLE
        } else if (cur != null && cur.isActive()) {
            tvProgress.text = text
            schedulePoll()
        } else {
            showMessage(text)
        }
    }

    private fun showProgress(text: String) {
        pickBox.visibility = View.GONE
        messageBox.visibility = View.GONE
        listBox.visibility = View.GONE
        tvProgress.text = text
        progressBox.visibility = View.VISIBLE
    }

    private fun showMessage(text: String) {
        handler.removeCallbacks(poll)
        pickBox.visibility = View.GONE
        progressBox.visibility = View.GONE
        listBox.visibility = View.GONE
        tvMessage.text = text
        messageBox.visibility = View.VISIBLE
    }

    /** «Загрузить другую выписку»: форма загрузки; текущий импорт остаётся на сервере. */
    fun onAnother(view: View) {
        generation++
        busy = false
        importId = null
        current = null
        models = ArrayList()
        showPick()
        if (fileUri == null) onPickFile(null)
    }

    private fun showList() {
        val cur = current!!
        handler.removeCallbacks(poll)
        pickBox.visibility = View.GONE
        progressBox.visibility = View.GONE
        messageBox.visibility = View.GONE
        tvListNote.visibility = View.GONE
        listBox.visibility = View.VISIBLE

        val db = Singleton.instance.getReadableDatabase(this)
        val wallet = DraftReconciler.lookup(db, Ref(cur.walletUuid ?: "", "", "", "", 2))
        importWalletId = if (wallet.state == DraftReconciler.State.OK) wallet.id.toInt() else 0
        val walletItem: AccountItem? = if (importWalletId > 0) Singleton.instance.getAccounts(this).getAccountById(importWalletId) else null
        tvListTitle.text = (if (cur.sourceName != null) cur.sourceName else getString(R.string.statement_title_default)) +
                (if (walletItem != null) " · " + walletItem.displayName else "")

        var added = 0
        var ready = 0
        for (m in models) {
            if (m.kind == Kind.ADDED) added++
            if (m.readiness.ready) ready++
        }
        tvListSummary.text = getString(R.string.statement_summary, models.size, added)
        if (importWalletId == 0) {
            tvListNote.text = AiTexts.statementWalletMissing()
            tvListNote.visibility = View.VISIBLE
        }
        btnWriteReady.text = getString(R.string.statement_write_ready, ready)
        btnWriteReady.isEnabled = ready > 0 && !busy

        rowsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (m in models) {
            val v = inflater.inflate(R.layout.item_statement_row, rowsContainer, false)
            bindRow(v, m)
            rowsContainer.addView(v)
        }
    }

    private fun bindRow(v: View, m: RowModel) {
        val r = m.row
        v.findViewById<TextView>(R.id.tvRowDate).text = dayText(r.date)
        v.findViewById<TextView>(R.id.tvRowAmount).text =
                (if (r.isExpense()) "−" else "") + AiTexts.statementAmount(r.amountValue())
        v.findViewById<TextView>(R.id.tvRowDescription).text = r.description

        val category: String = if (m.category.state == DraftReconciler.State.OK) m.category.name
        else if (r.categoryUuid != null) AiTexts.statementCategoryProblem(m.category.name, m.category.state)
        else if (r.suggestion != null) getString(R.string.statement_ai_suggests, r.suggestion)
        else getString(R.string.statement_no_category)
        v.findViewById<TextView>(R.id.tvRowCategory).text = if (r.isExpense()) category else dirText(r.dir)

        val source = v.findViewById<TextView>(R.id.tvRowSource)
        if (m.kind == Kind.NOT_FOR_WRITE || m.kind == Kind.CHECK) {
            source.text = r.sourceText()
            source.visibility = View.VISIBLE
        }

        var state = AiTexts.statementRowState(m.kind, m.duplicate)
        if (m.kind == Kind.CHECK) state += ": " + AiTexts.statementCheck(r.check).lowercase(Locale.ROOT)
        if (m.kind == Kind.WAITING && m.readiness.reason != null) state += " · " + m.readiness.reason
        val tvState = v.findViewById<TextView>(R.id.tvRowState)
        tvState.text = state
        val color = when (m.kind) {
            Kind.ADDED -> R.color.colorprofit
            Kind.WAITING -> if (m.readiness.ready) R.color.colorTextPrimary else R.color.colorexpanse
            Kind.NOT_FOR_WRITE -> R.color.colorTextSecondary
            else -> R.color.colorexpanse
        }
        tvState.setTextColor(getColor(color))

        if (m.kind != Kind.NOT_FOR_WRITE) v.setOnClickListener { openRow(m) }
    }

    private fun dirText(dir: String?): String {
        if ("income" == dir) return getString(R.string.statement_dir_income)
        if ("transfer" == dir) return getString(R.string.statement_dir_transfer)
        return getString(R.string.statement_dir_other)
    }

    // ── Запись строки через окно операции ────────────────────────────────────

    private fun openRow(m: RowModel) {
        if (busy || current == null) return
        val r = m.row
        val intent = Intent(this, TransactionActivity::class.java)
        intent.putExtra(TransactionActivity.cOpUuid, r.rowId)
        intent.putExtra(TransactionActivity.cIsEdit, false)
        intent.putExtra(TransactionActivity.cKind, "expense")
        intent.putExtra(TransactionActivity.cDate, r.date + " 12:00")
        intent.putExtra(TransactionActivity.cAmount, r.amountValue())
        intent.putExtra(TransactionActivity.cComment, r.description)
        intent.putExtra(TransactionActivity.cFrom, importWalletId)
        intent.putExtra(TransactionActivity.cTo,
                if (m.category.state == DraftReconciler.State.OK) m.category.id.toInt() else 0)
        val suggested = suggestedCategoryId(r)
        if (suggested > 0) intent.putExtra(TransactionActivity.cPriorityCategoryIds, intArrayOf(suggested))
        intent.putExtra(TransactionActivity.cHint, rowHint(m))
        pendingRowId = r.rowId
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_ROW)
    }

    /** Подсказка над полями: строка выписки и что с ней не так. */
    private fun rowHint(m: RowModel): String {
        val sb = StringBuilder(getString(R.string.statement_row_source, m.row.sourceText()))
        if (m.kind == Kind.CHECK) sb.append('\n').append(AiTexts.statementCheck(m.row.check))
        if (m.kind == Kind.DUPLICATE && m.duplicate != null) sb.append('\n').append(AiTexts.statementDuplicate(m.duplicate))
        if (m.row.categoryUuid == null && m.row.suggestion != null) {
            sb.append('\n').append(getString(R.string.statement_row_suggestion, m.row.suggestion))
        }
        return sb.toString()
    }

    /** Живая категория с именем `suggestion` — первой в пикере категорий. */
    private fun suggestedCategoryId(r: StatementImport.Row): Int {
        if (r.suggestion == null) return 0
        val c = Singleton.instance.getReadableDatabase(this).rawQuery(
                "SELECT _id FROM account WHERE kind <> 1 AND deleted_at IS NULL AND is_archived = 0"
                        + " AND name = ? COLLATE NOCASE LIMIT 1", arrayOf(r.suggestion))
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            if (resultCode == RESULT_OK && data != null && data.data != null) {
                setFile(data.data!!, null)
                importId = null
                current = null
            }
            if (importId == null) showPick()
            return
        }
        if (requestCode == REQUEST_WALLET) {
            if (resultCode == RESULT_OK && data != null) {
                walletId = liveWalletOrZero(data.getIntExtra(AccountPickerActivity.cAccountId, 0))
            }
            if (importId == null) showPick()
            return
        }
        if (requestCode == REQUEST_ROW) {
            val rowId = pendingRowId
            pendingRowId = null
            val id = if (resultCode == RESULT_OK && data != null)
                data.getLongExtra(TransactionActivity.cSavedOperationId, -1) else -1L
            if (id > 0 && rowId != null) onRowSaved(rowId, id)
            else if (importId != null) refresh()
        }
    }

    /**
     * Строка записана в окне: категория, если выбрана другая, — в `PATCH rows`
     * (сервер выучит продавца), затем `/committed` и фоновый синк. Без сети
     * строка всё равно «Добавлена», отметка уйдёт при следующем открытии.
     */
    private fun onRowSaved(rowId: String, operationId: Long) {
        PeriodFilterState.get(this).markDataChanged()
        val imp = current
        if (imp == null) {
            // Экран пересоздан, пока было открыто окно: строку отметит досылка при обновлении.
            refresh()
            return
        }
        val row = imp.row(rowId)
        val gen = ++generation
        busy = true
        val app = applicationContext
        Thread {
            val db = Singleton.instance.getReadableDatabase(app)
            val categoryUuid = savedCategoryUuid(db, operationId)
            var after: StatementImport = imp
            try {
                val client = sessionClient(app)
                if (client != null) {
                    if (row != null && categoryUuid != null && !sameUuid(categoryUuid, row.categoryUuid)) {
                        after = client.patchImportRowCategory(imp.id!!, rowId, categoryUuid)
                    }
                    after = client.commitImportRows(imp.id!!, listOf(rowId))
                }
            } catch (e: IOException) {
                // отметка — при следующем открытии списка
            } catch (e: JSONException) {
                // отметка — при следующем открытии списка
            }
            Thread { SyncRunner.runOnce(app) }.start()
            val done = after
            val built = buildModels(app, done)
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                busy = false
                current = done
                models = built
                showList()
            }
        }.start()
    }

    // ── «Записать готовые» ────────────────────────────────────────────────────

    fun onWriteReady(view: View) {
        if (busy || current == null) return
        val ready = ArrayList<RowModel>()
        for (m in models) if (m.readiness.ready) ready.add(m)
        if (ready.isEmpty()) return
        AlertDialog.Builder(this)
                .setTitle(R.string.statement_write_title)
                .setMessage(resources.getQuantityString(R.plurals.statement_write_question,
                        ready.size, ready.size))
                .setPositiveButton(R.string.statement_write) { _, _ -> writeReady(ready) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /** Одна транзакция SQLite с `uuid = row_id`, затем отметка на сервере и фоновый синк. */
    private fun writeReady(ready: List<RowModel>) {
        val imp = current
        val wallet = importWalletId
        if (imp == null || wallet <= 0) return
        val gen = ++generation
        busy = true
        btnWriteReady.isEnabled = false
        val app = applicationContext
        Thread {
            val db = Singleton.instance.getWritableDatabase(app)
            val written = ArrayList<String>()
            db.beginTransaction()
            try {
                // Пачка выписки целиком — один сценарий для авто-архива займов (D2).
                val debtsBefore = DebtAutoArchive.begin(db)
                for (m in ready) {
                    val r = m.row
                    val id = WalletDbCRUD.InsertOperationWithUuid(db, r.rowId!!, r.date + " 12:00", wallet,
                            m.readiness.categoryId.toInt(), r.amountValue(), r.description ?: "")
                    if (id > 0 || id == WalletDbCRUD.UUID_TAKEN.toLong()) written.add(r.rowId!!)
                }
                debtsBefore.finish(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            var after: StatementImport = imp
            try {
                val client = sessionClient(app)
                if (client != null && written.isNotEmpty()) after = client.commitImportRows(imp.id!!, written)
            } catch (e: IOException) {
                // отметки — при следующем открытии списка
            } catch (e: JSONException) {
                // отметки — при следующем открытии списка
            }
            Thread { SyncRunner.runOnce(app) }.start()
            val done = after
            val built = buildModels(app, done)
            runOnUiThread {
                PeriodFilterState.get(this).markDataChanged()
                if (gen != generation) return@runOnUiThread
                busy = false
                current = done
                models = built
                showList()
                Toast.makeText(this, getString(R.string.statement_written, written.size), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
