package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.ai.AiTexts;
import com.example.wallettransactions.ai.DraftReconciler;
import com.example.wallettransactions.ai.ParseExpenseResult.Ref;
import com.example.wallettransactions.ai.StatementDuplicates;
import com.example.wallettransactions.ai.StatementImport;
import com.example.wallettransactions.ai.StatementRowState;
import com.example.wallettransactions.ai.StatementRowState.Kind;
import com.example.wallettransactions.sync.AiApiClient;
import com.example.wallettransactions.sync.SyncRunner;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Экран «Выписка»: импорт банковской выписки через wallet-ai (openspec
 * changes/add-ai-statement-import, spec ai-statement-import; устройство —
 * docs/ARCHITECTURE.md, «Экран «Выписка»»).
 * <p>
 * Файл (выбор или «Поделиться») и кошелёк → синк → {@code POST /api/ai/imports}.
 * Разбор идёт на сервере; экран опрашивает импорт, только пока открыт, и находит
 * незавершённый импорт заново через {@code GET /api/ai/imports}. Строки готового
 * импорта берутся с сервера при каждом открытии и на телефоне не хранятся. Строка
 * записывается операцией с {@code uuid = row_id}: по одной — в окне операции
 * ({@link TransactionActivity#cOpUuid}), пачкой — «Записать готовые». Дубли ищет
 * телефон по полям ({@link StatementDuplicates}). Записанное отмечается на сервере
 * ({@code /committed}); не дошедшая отметка досылается при следующем открытии.
 * <p>
 * Сеть — на голом {@code new Thread}, как у экрана «ИИ»; поздние ответы
 * отсекает {@link #generation}. Суммы и описания строк в logcat не пишутся.
 */
public class StatementImportActivity extends AppCompatActivity {

    /** Открыть сразу системный выбор файла (кнопка «Загрузить выписку» на экране «ИИ»). */
    public static final String EXTRA_PICK_FILE = "StatementImport.PickFile";

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final long POLL_MS = 3_000;

    private static final int REQUEST_FILE = 51;
    private static final int REQUEST_WALLET = 52;
    private static final int REQUEST_ROW = 53;

    private static final String STATE_FILE_URI = "statement.fileUri";
    private static final String STATE_FILE_NAME = "statement.fileName";
    private static final String STATE_FILE_SIZE = "statement.fileSize";
    private static final String STATE_WALLET = "statement.walletId";
    private static final String STATE_IMPORT = "statement.importId";
    private static final String STATE_ROW = "statement.pendingRowId";

    private static final String LOGIN_TEXT = "Войдите, чтобы пользоваться ИИ";
    private static final String NO_NETWORK_TEXT = "Нет связи с сервером";

    /** Строка списка: строка импорта и что с ней на телефоне. */
    static final class RowModel {
        final StatementImport.Row row;
        final Kind kind;
        final StatementDuplicates.Match duplicate;
        final StatementRowState.Readiness readiness;
        /** Категория строки на телефоне (имя — для подписи). */
        final StatementRowState.Category category;

        RowModel(StatementImport.Row row, Kind kind, StatementDuplicates.Match duplicate,
                 StatementRowState.Readiness readiness, StatementRowState.Category category) {
            this.row = row;
            this.kind = kind;
            this.duplicate = duplicate;
            this.readiness = readiness;
            this.category = category;
        }
    }

    private View pickBox;
    private TextView tvFile;
    private TextView tvWallet;
    private TextView tvPickError;
    private Button btnUpload;
    private View progressBox;
    private TextView tvProgress;
    private View messageBox;
    private TextView tvMessage;
    private View listBox;
    private TextView tvListTitle;
    private TextView tvListSummary;
    private TextView tvListNote;
    private Button btnWriteReady;
    private LinearLayout rowsContainer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;

    private Uri fileUri;
    private String fileName;
    private long fileSize;
    private int walletId;
    private String importId;
    /** row_id строки, открытой в окне операции, — ждём её результата. */
    private String pendingRowId;

    /** Последний ответ сервера по импорту и строки списка по нему. */
    private StatementImport current;
    private List<RowModel> models = new ArrayList<>();
    /** Локальный {@code _id} кошелька выписки, если он жив на телефоне; иначе 0. */
    private int importWalletId;

    private volatile int generation;
    private boolean busy;
    private boolean started;
    /** Строки показаны после синка в этом открытии экрана — повторно не синкаем на каждый опрос. */
    private boolean syncedForList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statement_import);

        pickBox = findViewById(R.id.pickBox);
        tvFile = findViewById(R.id.tvFile);
        tvWallet = findViewById(R.id.tvWallet);
        tvPickError = findViewById(R.id.tvPickError);
        btnUpload = findViewById(R.id.btnUpload);
        progressBox = findViewById(R.id.progressBox);
        tvProgress = findViewById(R.id.tvProgress);
        messageBox = findViewById(R.id.messageBox);
        tvMessage = findViewById(R.id.tvMessage);
        listBox = findViewById(R.id.listBox);
        tvListTitle = findViewById(R.id.tvListTitle);
        tvListSummary = findViewById(R.id.tvListSummary);
        tvListNote = findViewById(R.id.tvListNote);
        btnWriteReady = findViewById(R.id.btnWriteReady);
        rowsContainer = findViewById(R.id.rowsContainer);

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString(STATE_FILE_URI);
            fileUri = uri != null ? Uri.parse(uri) : null;
            fileName = savedInstanceState.getString(STATE_FILE_NAME);
            fileSize = savedInstanceState.getLong(STATE_FILE_SIZE);
            walletId = savedInstanceState.getInt(STATE_WALLET);
            importId = savedInstanceState.getString(STATE_IMPORT);
            pendingRowId = savedInstanceState.getString(STATE_ROW);
        } else {
            if (forwardSharedText(getIntent())) return;
            walletId = liveWalletOrZero(AppSettings.getLastWalletId(this));
            takeSharedFile(getIntent());
            if (getIntent().getBooleanExtra(EXTRA_PICK_FILE, false)) onPickFile(null);
        }
        showPick();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeSharedFile(intent)) {
            importId = null;
            current = null;
            showPick();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        syncedForList = false;
        if (pendingRowId != null) return; // ждём результата окна операции
        if (importId != null) refresh();
        else if (fileUri == null) findExisting();
    }

    @Override
    protected void onStop() {
        // Уход с экрана — опрос прекращается, разбор на сервере идёт дальше (решение 9).
        started = false;
        handler.removeCallbacks(poll);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (fileUri != null) out.putString(STATE_FILE_URI, fileUri.toString());
        out.putString(STATE_FILE_NAME, fileName);
        out.putLong(STATE_FILE_SIZE, fileSize);
        out.putInt(STATE_WALLET, walletId);
        out.putString(STATE_IMPORT, importId);
        out.putString(STATE_ROW, pendingRowId);
    }

    public void btnUp(View view) {
        finish();
    }

    // ── Файл и кошелёк ───────────────────────────────────────────────────────

    /** «Поделиться» файлом: экран с этим файлом, загрузка не начата. */
    private boolean takeSharedFile(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return false;
        setFile(uri, intent.getType());
        return true;
    }

    /**
     * В «Выписку» поделились текстом, а не файлом (text/plain без EXTRA_STREAM) —
     * это трата своими словами: текст уходит на экран «ИИ».
     */
    private boolean forwardSharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        if (intent.getParcelableExtra(Intent.EXTRA_STREAM) != null) return false;
        if (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) == null) return false;
        startActivity(new Intent(intent).setClass(this, AiActivity.class));
        finish();
        return true;
    }

    public void onPickFile(View view) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/pdf", "text/csv", "text/comma-separated-values", "text/plain"});
        try {
            startActivityForResult(intent, REQUEST_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения для выбора файла", Toast.LENGTH_SHORT).show();
        }
    }

    public void onPickWallet(View view) {
        startActivityForResult(AccountPickerActivity.buildIntent(this, "Кошелёк выписки",
                AccountPickerActivity.cSelectedTypeOperationAccounts, walletId), REQUEST_WALLET);
    }

    private void setFile(Uri uri, String mime) {
        fileUri = uri;
        fileName = null;
        fileSize = -1;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) fileName = c.getString(0);
                if (!c.isNull(1)) fileSize = c.getLong(1);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // имя и размер узнаем при чтении
        }
        if (mime == null) mime = getContentResolver().getType(uri);
        fileName = withExtension(fileName != null ? fileName : "statement", mime);
        tvPickError.setVisibility(View.GONE);
    }

    /** Сервер узнаёт формат по расширению: у файла из «Поделиться» его может не быть. */
    static String withExtension(String name, String mime) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf") || lower.endsWith(".csv") || lower.endsWith(".txt")) return name;
        if ("application/pdf".equals(mime)) return name + ".pdf";
        if (mime != null && mime.contains("csv")) return name + ".csv";
        if ("text/plain".equals(mime)) return name + ".txt";
        return name;
    }

    private int liveWalletOrZero(int id) {
        if (id <= 0) return 0;
        DraftReconciler.State s = DraftReconciler.stateById(Singleton.instance.getReadableDatabase(this), id);
        AccountItem a = Singleton.instance.getAccounts(this).getAccountById(id);
        return s == DraftReconciler.State.OK && a != null && a.isOperatingAccount() ? id : 0;
    }

    private void showPick() {
        handler.removeCallbacks(poll);
        pickBox.setVisibility(View.VISIBLE);
        progressBox.setVisibility(View.GONE);
        messageBox.setVisibility(View.GONE);
        listBox.setVisibility(View.GONE);
        tvFile.setText(fileUri == null ? "" : fileName + (fileSize >= 0 ? " · " + sizeText(fileSize) : ""));
        AccountItem wallet = walletId > 0 ? Singleton.instance.getAccounts(this).getAccountById(walletId) : null;
        tvWallet.setText(wallet != null ? wallet.getName() : "");
        if (fileSize > MAX_FILE_BYTES) showPickError(AiTexts.STATEMENT_FILE_TOO_BIG);
        updateUploadButton();
    }

    private void updateUploadButton() {
        btnUpload.setEnabled(!busy && fileUri != null && walletId > 0 && fileSize <= MAX_FILE_BYTES);
    }

    private void showPickError(String text) {
        tvPickError.setText(text);
        tvPickError.setVisibility(View.VISIBLE);
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " КБ";
        return String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / 1024.0 / 1024.0).replace('.', ',');
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────

    public void onUpload(View view) {
        if (busy || fileUri == null || walletId <= 0) return;
        if (fileSize > MAX_FILE_BYTES) {
            showPickError(AiTexts.STATEMENT_FILE_TOO_BIG);
            return;
        }
        final int gen = ++generation;
        busy = true;
        tvPickError.setVisibility(View.GONE);
        updateUploadButton();
        showProgress("Синхронизирую…");
        final Context app = getApplicationContext();
        final Uri uri = fileUri;
        final String name = fileName;
        final int wallet = walletId;
        new Thread(() -> {
            // Кошелёк, созданный на телефоне, иначе серверу неизвестен (unknown_wallet).
            SyncRunner.Outcome sync = SyncRunner.runOnceShared(app, AiActivity.SYNC_WAIT_MS);
            if (sync.status == SyncRunner.Status.NO_SESSION || sync.status == SyncRunner.Status.NEEDS_RELINK) {
                uploadFailed(gen, LOGIN_TEXT);
                return;
            }
            runOnUiThread(() -> { if (gen == generation) tvProgress.setText("Загружаю выписку…"); });
            try {
                byte[] bytes = readFile(uri);
                if (bytes == null) {
                    uploadFailed(gen, AiTexts.STATEMENT_FILE_TOO_BIG);
                    return;
                }
                AiApiClient client = sessionClient(app);
                if (client == null) {
                    uploadFailed(gen, LOGIN_TEXT);
                    return;
                }
                String walletUuid = AiActivity.serverUuid(Singleton.instance.getReadableDatabase(app), wallet);
                StatementImport imp = client.createImport(walletUuid, name, bytes);
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    busy = false;
                    if (StatementImport.FAILED.equals(imp.status)) {
                        showPick();
                        showPickError(AiTexts.statementFailure(imp.failure));
                        return;
                    }
                    fileUri = null;
                    importId = imp.id;
                    current = imp;
                    showProgress(AiTexts.statementProgress(imp));
                    schedulePoll();
                });
            } catch (AiApiClient.HttpError e) {
                if (e.code == 429 && "import_in_progress".equals(e.error) && !e.importId.isEmpty()) {
                    // Уже идёт другой импорт — открываем его; файл и кошелёк остаются для «Загрузить другую».
                    runOnUiThread(() -> {
                        if (gen != generation) return;
                        busy = false;
                        importId = e.importId;
                        Toast.makeText(this, AiTexts.statementHttpError(e.code, e.error), Toast.LENGTH_LONG).show();
                        refresh();
                    });
                    return;
                }
                uploadFailed(gen, AiTexts.statementHttpError(e.code, e.error));
            } catch (IOException e) {
                uploadFailed(gen, NO_NETWORK_TEXT);
            } catch (JSONException e) {
                uploadFailed(gen, "Некорректный ответ сервера");
            }
        }).start();
    }

    /** Отказ загрузки: причина под формой, файл и кошелёк не теряются. */
    private void uploadFailed(int gen, String text) {
        runOnUiThread(() -> {
            if (gen != generation) return;
            busy = false;
            showPick();
            showPickError(text);
        });
    }

    /** Файл целиком; null — больше 5 МБ (размер мог быть неизвестен до чтения). */
    private byte[] readFile(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("файл не открылся");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE_BYTES) return null;
            }
            return out.toByteArray();
        }
    }

    /** Клиент со свежим токеном; null — входа нет. Сетевая ошибка — IOException. */
    private static AiApiClient sessionClient(Context app) throws IOException {
        AiApiClient.SessionState s = AiApiClient.refreshSession(app);
        if (s == AiApiClient.SessionState.NO_SESSION || s == AiApiClient.SessionState.NEEDS_RELINK) return null;
        if (s == AiApiClient.SessionState.NETWORK_ERROR) throw new IOException("нет сети");
        return AiApiClient.create(app);
    }

    // ── Ожидание и список ────────────────────────────────────────────────────

    /** Открытие экрана без файла: незавершённый (или готовый к проверке) импорт продолжается. */
    private void findExisting() {
        final int gen = ++generation;
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                AiApiClient client = sessionClient(app);
                if (client == null) return;
                for (StatementImport imp : client.listImports()) {
                    if (!imp.isActive() && !StatementImport.READY.equals(imp.status)) continue;
                    runOnUiThread(() -> {
                        if (gen != generation || fileUri != null || importId != null) return;
                        importId = imp.id;
                        refresh();
                    });
                    return;
                }
            } catch (IOException | JSONException e) {
                // Нет сети — остаётся форма загрузки, ошибку покажет сама загрузка.
            }
        }).start();
    }

    private void schedulePoll() {
        handler.removeCallbacks(poll);
        if (started && importId != null) handler.postDelayed(poll, POLL_MS);
    }

    /** {@code GET /imports/{id}}; у готового — синк (раз за открытие), сверка с телефоном и досылка отметок. */
    private void refresh() {
        if (importId == null || busy) return;
        final int gen = ++generation;
        final String id = importId;
        final boolean needSync = !syncedForList;
        final Context app = getApplicationContext();
        if (current == null || !current.hasRows()) showProgress(current != null ? AiTexts.statementProgress(current) : "Загружаю…");
        new Thread(() -> {
            try {
                AiApiClient client = sessionClient(app);
                if (client == null) {
                    runOnUiThread(() -> { if (gen == generation) showMessage(LOGIN_TEXT); });
                    return;
                }
                StatementImport imp = client.getImport(id);
                if (imp.hasRows()) {
                    if (needSync) {
                        // Операции из веба и бота — чтобы проверка на дубли видела их.
                        runOnUiThread(() -> { if (gen == generation && listBox.getVisibility() != View.VISIBLE) tvProgress.setText("Синхронизирую…"); });
                        SyncRunner.runOnceShared(app, AiActivity.SYNC_WAIT_MS);
                        Singleton.instance.reloadAccounts(app);
                    }
                    imp = resendCommits(client, imp, app);
                }
                final StatementImport done = imp;
                final List<RowModel> built = done.hasRows() ? buildModels(app, done) : null;
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    if (needSync && done.hasRows()) syncedForList = true;
                    onImport(done, built);
                });
            } catch (AiApiClient.HttpError e) {
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    if (e.code == 404) {
                        importId = null;
                        current = null;
                        showPick();
                        showPickError("Импорт не найден — он удалён или истёк");
                    } else {
                        showListOrMessage("Сервер ответил ошибкой (HTTP " + e.code + ")");
                    }
                });
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> { if (gen == generation) showListOrMessage(AiTexts.STATEMENT_NO_NETWORK); });
            }
        }).start();
    }

    private void onImport(StatementImport imp, List<RowModel> built) {
        current = imp;
        if (imp.isActive()) {
            showProgress(AiTexts.statementProgress(imp));
            schedulePoll();
            return;
        }
        if (StatementImport.FAILED.equals(imp.status)) {
            showMessage(AiTexts.statementFailure(imp.failure));
            return;
        }
        if (!imp.hasRows()) {
            showMessage("Импорт отменён");
            return;
        }
        models = built;
        showList();
    }

    /** Без сети: список, если он уже был показан (остаётся рабочим), иначе — сообщение. */
    private void showListOrMessage(String text) {
        if (listBox.getVisibility() == View.VISIBLE) {
            tvListNote.setText(text);
            tvListNote.setVisibility(View.VISIBLE);
        } else if (current != null && current.isActive()) {
            tvProgress.setText(text);
            schedulePoll();
        } else {
            showMessage(text);
        }
    }

    /**
     * Строки «Добавлена» (на телефоне есть операция с их uuid), которые сервер ещё
     * не отметил ({@code committed: false}, например не было сети), — в {@code /committed}.
     */
    private static StatementImport resendCommits(AiApiClient client, StatementImport imp, Context app) {
        if (!StatementImport.READY.equals(imp.status)) return imp;
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(app);
        List<String> pending = new ArrayList<>();
        for (StatementImport.Row r : imp.rows) {
            if (!r.committed && isAdded(db, r.rowId)) pending.add(r.rowId);
        }
        if (pending.isEmpty()) return imp;
        try {
            return client.commitImportRows(imp.id, pending);
        } catch (IOException | JSONException e) {
            return imp; // досылка — в следующее открытие
        }
    }

    private static boolean isAdded(SQLiteDatabase db, String rowId) {
        WalletDbCRUD.UuidMatch m = WalletDbCRUD.FindOperationByUuid(db, rowId);
        return m != null && !m.deleted;
    }

    /** Состояния строк по базе телефона — в фоновом потоке. */
    static List<RowModel> buildModels(Context app, StatementImport imp) {
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(app);
        DraftReconciler.Local wallet = DraftReconciler.lookup(db, new Ref(imp.walletUuid, "", "", "", 2));
        List<String> ids = new ArrayList<>();
        List<StatementImport.Row> candidates = new ArrayList<>();
        java.util.Set<String> added = new java.util.HashSet<>();
        for (StatementImport.Row r : imp.rows) {
            ids.add(r.rowId);
            if (isAdded(db, r.rowId)) added.add(r.rowId);
            else if (r.isExpense()) candidates.add(r);
        }
        Map<String, StatementDuplicates.Match> dups = wallet.state == DraftReconciler.State.OK
                ? StatementDuplicates.find(db, wallet.id, candidates, ids)
                : Collections.emptyMap();
        LocalDate today = LocalDate.now();
        List<RowModel> out = new ArrayList<>();
        for (StatementImport.Row r : imp.rows) {
            StatementDuplicates.Match dup = dups.get(r.rowId);
            Kind kind = StatementRowState.of(r, added.contains(r.rowId), dup);
            out.add(new RowModel(r, kind, dup, StatementRowState.readiness(db, r, kind, wallet.state, today),
                    StatementRowState.category(db, r.categoryUuid)));
        }
        return out;
    }

    private void showProgress(String text) {
        pickBox.setVisibility(View.GONE);
        messageBox.setVisibility(View.GONE);
        listBox.setVisibility(View.GONE);
        tvProgress.setText(text);
        progressBox.setVisibility(View.VISIBLE);
    }

    private void showMessage(String text) {
        handler.removeCallbacks(poll);
        pickBox.setVisibility(View.GONE);
        progressBox.setVisibility(View.GONE);
        listBox.setVisibility(View.GONE);
        tvMessage.setText(text);
        messageBox.setVisibility(View.VISIBLE);
    }

    /** «Загрузить другую выписку»: форма загрузки; текущий импорт остаётся на сервере. */
    public void onAnother(View view) {
        generation++;
        busy = false;
        importId = null;
        current = null;
        models = new ArrayList<>();
        showPick();
        if (fileUri == null) onPickFile(null);
    }

    private void showList() {
        handler.removeCallbacks(poll);
        pickBox.setVisibility(View.GONE);
        progressBox.setVisibility(View.GONE);
        messageBox.setVisibility(View.GONE);
        tvListNote.setVisibility(View.GONE);
        listBox.setVisibility(View.VISIBLE);

        SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
        DraftReconciler.Local wallet = DraftReconciler.lookup(db, new Ref(current.walletUuid, "", "", "", 2));
        importWalletId = wallet.state == DraftReconciler.State.OK ? (int) wallet.id : 0;
        AccountItem walletItem = importWalletId > 0 ? Singleton.instance.getAccounts(this).getAccountById(importWalletId) : null;
        tvListTitle.setText((current.sourceName != null ? current.sourceName : "Выписка")
                + (walletItem != null ? " · " + walletItem.getName() : ""));

        int added = 0;
        int ready = 0;
        for (RowModel m : models) {
            if (m.kind == Kind.ADDED) added++;
            if (m.readiness.ready) ready++;
        }
        tvListSummary.setText("Строк: " + models.size() + " · добавлено: " + added);
        if (importWalletId == 0) {
            tvListNote.setText(AiTexts.STATEMENT_WALLET_MISSING);
            tvListNote.setVisibility(View.VISIBLE);
        }
        btnWriteReady.setText("Записать готовые (" + ready + ")");
        btnWriteReady.setEnabled(ready > 0 && !busy);

        rowsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RowModel m : models) {
            View v = inflater.inflate(R.layout.item_statement_row, rowsContainer, false);
            bindRow(v, m);
            rowsContainer.addView(v);
        }
    }

    private void bindRow(View v, RowModel m) {
        StatementImport.Row r = m.row;
        ((TextView) v.findViewById(R.id.tvRowDate)).setText(dayText(r.date));
        ((TextView) v.findViewById(R.id.tvRowAmount)).setText(
                (r.isExpense() ? "−" : "") + AiTexts.statementAmount(r.amountValue()));
        ((TextView) v.findViewById(R.id.tvRowDescription)).setText(r.description);

        String category;
        if (m.category.state == DraftReconciler.State.OK) category = m.category.name;
        else if (r.categoryUuid != null) category = AiTexts.statementCategoryProblem(m.category.name, m.category.state);
        else if (r.suggestion != null) category = "ИИ предлагает: " + r.suggestion;
        else category = "Без категории";
        ((TextView) v.findViewById(R.id.tvRowCategory)).setText(r.isExpense() ? category : dirText(r.dir));

        TextView source = v.findViewById(R.id.tvRowSource);
        if (m.kind == Kind.NOT_FOR_WRITE || m.kind == Kind.CHECK) {
            source.setText(r.sourceText());
            source.setVisibility(View.VISIBLE);
        }

        String state = AiTexts.statementRowState(m.kind, m.duplicate);
        if (m.kind == Kind.CHECK) state += ": " + AiTexts.statementCheck(r.check).toLowerCase(java.util.Locale.ROOT);
        if (m.kind == Kind.WAITING && m.readiness.reason != null) state += " · " + m.readiness.reason;
        TextView tvState = v.findViewById(R.id.tvRowState);
        tvState.setText(state);
        int color;
        switch (m.kind) {
            case ADDED: color = R.color.colorprofit; break;
            case WAITING: color = m.readiness.ready ? R.color.colorTextPrimary : R.color.colorexpanse; break;
            case NOT_FOR_WRITE: color = R.color.colorTextSecondary; break;
            default: color = R.color.colorexpanse;
        }
        tvState.setTextColor(getColor(color));

        if (m.kind != Kind.NOT_FOR_WRITE) v.setOnClickListener(x -> openRow(m));
    }

    private static String dayText(String date) {
        return date != null && date.length() >= 10 ? date.substring(8, 10) + "." + date.substring(5, 7) + "." + date.substring(0, 4) : "";
    }

    private static String dirText(String dir) {
        if ("income".equals(dir)) return "Доход — не записывается";
        if ("transfer".equals(dir)) return "Перевод — не записывается";
        return "Не расход — не записывается";
    }

    // ── Запись строки через окно операции ────────────────────────────────────

    private void openRow(RowModel m) {
        if (busy || current == null) return;
        StatementImport.Row r = m.row;
        Intent intent = new Intent(this, TransactionActivity.class);
        intent.putExtra(TransactionActivity.cOpUuid, r.rowId);
        intent.putExtra(TransactionActivity.cIsEdit, false);
        intent.putExtra(TransactionActivity.cKind, "expense");
        intent.putExtra(TransactionActivity.cDate, r.date + " 12:00");
        intent.putExtra(TransactionActivity.cAmount, r.amountValue());
        intent.putExtra(TransactionActivity.cComment, r.description);
        intent.putExtra(TransactionActivity.cFrom, importWalletId);
        intent.putExtra(TransactionActivity.cTo,
                m.category.state == DraftReconciler.State.OK ? (int) m.category.id : 0);
        int suggested = suggestedCategoryId(r);
        if (suggested > 0) intent.putExtra(TransactionActivity.cPriorityCategoryIds, new int[]{suggested});
        intent.putExtra(TransactionActivity.cHint, rowHint(m));
        pendingRowId = r.rowId;
        startActivityForResult(intent, REQUEST_ROW);
    }

    /** Подсказка над полями: строка выписки и что с ней не так. */
    private static String rowHint(RowModel m) {
        StringBuilder sb = new StringBuilder("Строка выписки: «").append(m.row.sourceText()).append('»');
        if (m.kind == Kind.CHECK) sb.append('\n').append(AiTexts.statementCheck(m.row.check));
        if (m.kind == Kind.DUPLICATE && m.duplicate != null) sb.append('\n').append(AiTexts.statementDuplicate(m.duplicate));
        if (m.row.categoryUuid == null && m.row.suggestion != null) {
            sb.append("\nИИ предлагает категорию «").append(m.row.suggestion).append('»');
        }
        return sb.toString();
    }

    /** Живая категория с именем {@code suggestion} — первой в пикере категорий. */
    private int suggestedCategoryId(StatementImport.Row r) {
        if (r.suggestion == null) return 0;
        Cursor c = Singleton.instance.getReadableDatabase(this).rawQuery(
                "SELECT _id FROM account WHERE kind <> 1 AND deleted_at IS NULL AND is_archived = 0"
                        + " AND name = ? COLLATE NOCASE LIMIT 1", new String[]{r.suggestion});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                setFile(data.getData(), null);
                importId = null;
                current = null;
            }
            if (importId == null) showPick();
            return;
        }
        if (requestCode == REQUEST_WALLET) {
            if (resultCode == RESULT_OK && data != null) {
                walletId = liveWalletOrZero(data.getIntExtra(AccountPickerActivity.cAccountId, 0));
            }
            if (importId == null) showPick();
            return;
        }
        if (requestCode == REQUEST_ROW) {
            String rowId = pendingRowId;
            pendingRowId = null;
            long id = resultCode == RESULT_OK && data != null
                    ? data.getLongExtra(TransactionActivity.cSavedOperationId, -1) : -1;
            if (id > 0 && rowId != null) onRowSaved(rowId, id);
            else if (importId != null) refresh();
        }
    }

    /**
     * Строка записана в окне: категория, если выбрана другая, — в {@code PATCH rows}
     * (сервер выучит продавца), затем {@code /committed} и фоновый синк. Без сети
     * строка всё равно «Добавлена», отметка уйдёт при следующем открытии.
     */
    private void onRowSaved(String rowId, long operationId) {
        PeriodFilterState.get(this).markDataChanged();
        final StatementImport imp = current;
        if (imp == null) {
            // Экран пересоздан, пока было открыто окно: строку отметит досылка при обновлении.
            refresh();
            return;
        }
        final StatementImport.Row row = imp.row(rowId);
        final int gen = ++generation;
        busy = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            SQLiteDatabase db = Singleton.instance.getReadableDatabase(app);
            String categoryUuid = savedCategoryUuid(db, operationId);
            StatementImport after = imp;
            try {
                AiApiClient client = sessionClient(app);
                if (client != null) {
                    if (row != null && categoryUuid != null && !sameUuid(categoryUuid, row.categoryUuid)) {
                        after = client.patchImportRowCategory(imp.id, rowId, categoryUuid);
                    }
                    after = client.commitImportRows(imp.id, Collections.singletonList(rowId));
                }
            } catch (IOException | JSONException e) {
                // отметка — при следующем открытии списка
            }
            new Thread(() -> SyncRunner.runOnce(app)).start();
            final StatementImport done = after;
            final List<RowModel> built = buildModels(app, done);
            runOnUiThread(() -> {
                if (gen != generation) return;
                busy = false;
                current = done;
                models = built;
                showList();
            });
        }).start();
    }

    /** Серверный uuid категории сохранённого расхода; null — это не расход «кошелёк → категория». */
    private static String savedCategoryUuid(SQLiteDatabase db, long operationId) {
        Cursor c = db.rawQuery("SELECT f.kind, t.kind, t._id FROM operation o"
                        + " JOIN account f ON f._id = o.acc_fr_id JOIN account t ON t._id = o.acc_to_id WHERE o._id=?",
                new String[]{String.valueOf(operationId)});
        try {
            if (!c.moveToFirst() || c.getInt(0) != 1 || c.getInt(1) == 1) return null;
            return AiActivity.serverUuid(db, c.getLong(2));
        } finally {
            c.close();
        }
    }

    private static boolean sameUuid(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.replace("-", "").equalsIgnoreCase(b.replace("-", ""));
    }

    // ── «Записать готовые» ────────────────────────────────────────────────────

    public void onWriteReady(View view) {
        if (busy || current == null) return;
        final List<RowModel> ready = new ArrayList<>();
        for (RowModel m : models) if (m.readiness.ready) ready.add(m);
        if (ready.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Записать готовые")
                .setMessage("Записать " + ready.size() + " " + operationsWord(ready.size())
                        + " в кошелёк? Строки «Нужна категория», «Проверить» и «Похоже, уже есть» не тронутся.")
                .setPositiveButton("Записать", (d, w) -> writeReady(ready))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String operationsWord(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "операцию";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return "операции";
        return "операций";
    }

    /** Одна транзакция SQLite с {@code uuid = row_id}, затем отметка на сервере и фоновый синк. */
    private void writeReady(List<RowModel> ready) {
        final StatementImport imp = current;
        final int wallet = importWalletId;
        if (imp == null || wallet <= 0) return;
        final int gen = ++generation;
        busy = true;
        btnWriteReady.setEnabled(false);
        final Context app = getApplicationContext();
        new Thread(() -> {
            SQLiteDatabase db = Singleton.instance.getWritableDatabase(app);
            List<String> written = new ArrayList<>();
            db.beginTransaction();
            try {
                for (RowModel m : ready) {
                    StatementImport.Row r = m.row;
                    long id = WalletDbCRUD.InsertOperationWithUuid(db, r.rowId, r.date + " 12:00", wallet,
                            (int) m.readiness.categoryId, r.amountValue(), r.description);
                    if (id > 0 || id == WalletDbCRUD.UUID_TAKEN) written.add(r.rowId);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            StatementImport after = imp;
            try {
                AiApiClient client = sessionClient(app);
                if (client != null && !written.isEmpty()) after = client.commitImportRows(imp.id, written);
            } catch (IOException | JSONException e) {
                // отметки — при следующем открытии списка
            }
            new Thread(() -> SyncRunner.runOnce(app)).start();
            final StatementImport done = after;
            final List<RowModel> built = buildModels(app, done);
            runOnUiThread(() -> {
                PeriodFilterState.get(this).markDataChanged();
                if (gen != generation) return;
                busy = false;
                current = done;
                models = built;
                showList();
                Toast.makeText(this, "Записано: " + written.size(), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
}
