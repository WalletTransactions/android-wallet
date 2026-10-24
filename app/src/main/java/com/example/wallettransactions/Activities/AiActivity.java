package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.ai.AiTexts;
import com.example.wallettransactions.ai.DraftReconciler;
import com.example.wallettransactions.ai.OperationPrefill;
import com.example.wallettransactions.ai.ParseExpenseResult;
import com.example.wallettransactions.ai.ParseExpenseResult.Ref;
import com.example.wallettransactions.sync.AiApiClient;
import com.example.wallettransactions.sync.SyncRunner;

import com.example.wallettransactions.sync.SyncLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Экран «ИИ»: трата своими словами через wallet-ai (openspec changes/add-ai-client,
 * spec ai-expense-entry; устройство — docs/ARCHITECTURE.md, «Экран «ИИ»»).
 * <p>
 * «Разобрать» — синк ({@link SyncRunner#runOnceShared}, до {@link #SYNC_WAIT_MS}),
 * чтобы resolver решал по тому же справочнику, что на телефоне; затем задание
 * {@code parse_expense} и long-poll. Черновик правится и записывается в обычном
 * окне операции ({@link TransactionActivity}, openspec changes/ai-expense-in-operation-window):
 * поля и подсказки собирает {@link OperationPrefill}. Если кошелька или категории
 * из черновика нет на телефоне, окно сразу не открывается — сначала блок ошибки.
 * На сервер операцию отвезёт обычный синк.
 * <p>
 * Сеть — на голом {@code new Thread}, как у «Интеграций»; поздние ответы
 * отменённого разбора отсекает {@link #generation}. Текст траты в logcat не пишется.
 */
public class AiActivity extends AppCompatActivity {

    static final int MAX_TEXT = 500;
    static final long SYNC_WAIT_MS = 10_000;
    private static final int REQUEST_SPEECH = 41;
    private static final int REQUEST_OPERATION = 42;

    private static final String STATE_DRAFT = "ai.draft";
    private static final String STATE_SOURCE = "ai.source";
    private static final String STATE_SYNC_STATUS = "ai.syncStatus";
    private static final String STATE_SYNC_MESSAGE = "ai.syncMessage";

    private static final String LOGIN_TEXT = "Войдите, чтобы пользоваться ИИ";
    private static final String NO_NETWORK_TEXT = "Нет связи с сервером";

    private EditText editText;
    private TextView tvCounter;
    private View btnMic;
    private Button btnParse;
    private TextView tvWarning;
    private View progressBox;
    private TextView tvProgress;
    private View messageBox;
    private TextView tvMessage;
    private TextView tvDone;
    private View problemBox;
    private TextView tvProblem;
    private TextView tvProblemDetails;
    private View pendingBox;

    /** Номер текущего разбора: ответ потока с другим номером уже никому не нужен. */
    private volatile int generation;
    private volatile String jobId;
    private boolean busy;
    private boolean sessionMissing;

    /** Исход синка перед текущим разбором — для деталей блока ошибки. */
    private SyncRunner.Outcome syncBeforeParse;

    /** Последний черновик — живёт до записи или нового «Разобрать» («Открыть снова»). */
    private ParseExpenseResult draft;
    private String sourceText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai);

        editText = findViewById(R.id.editText);
        tvCounter = findViewById(R.id.tvCounter);
        btnMic = findViewById(R.id.btnMic);
        btnParse = findViewById(R.id.btnParse);
        tvWarning = findViewById(R.id.tvWarning);
        progressBox = findViewById(R.id.progressBox);
        tvProgress = findViewById(R.id.tvProgress);
        messageBox = findViewById(R.id.messageBox);
        tvMessage = findViewById(R.id.tvMessage);
        tvDone = findViewById(R.id.tvDone);
        problemBox = findViewById(R.id.problemBox);
        tvProblem = findViewById(R.id.tvProblem);
        tvProblemDetails = findViewById(R.id.tvProblemDetails);
        pendingBox = findViewById(R.id.pendingBox);

        editText.addTextChangedListener(new SimpleWatcher(this::updateInputState));

        // Системное распознавание речи; нет его на устройстве — нет и кнопки.
        boolean canSpeak = speechIntent().resolveActivity(getPackageManager()) != null;
        btnMic.setVisibility(canSpeak ? View.VISIBLE : View.GONE);

        if (savedInstanceState != null) restoreDraft(savedInstanceState);
        takeSharedText(getIntent());
        sessionMissing = !AiApiClient.hasSession(this);
        if (sessionMissing) showMessage(LOGIN_TEXT, true);
        updateInputState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeSharedText(intent);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) cancelRunning();
        super.onDestroy();
    }

    public void btnUp(View view) {
        finish();
    }

    // ── Ввод ──────────────────────────────────────────────────────────────────

    /** «Поделиться» → текст в поле; разбор сам не запускается (spec, «Текст из «Поделиться»»). */
    private void takeSharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        editText.setText(shared.toString().trim());
        editText.setSelection(editText.length());
    }

    private void updateInputState() {
        int len = editText.length();
        tvCounter.setText(len + " / " + MAX_TEXT);
        tvCounter.setTextColor(getColor(len > MAX_TEXT ? R.color.colorexpanse : R.color.colorTextSecondary));
        btnParse.setEnabled(!busy && !sessionMissing && len > 0 && len <= MAX_TEXT
                && !editText.getText().toString().trim().isEmpty());
    }

    private static Intent speechIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите трату");
    }

    public void onMic(View view) {
        try {
            startActivityForResult(speechIntent(), REQUEST_SPEECH);
        } catch (android.content.ActivityNotFoundException e) {
            btnMic.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPERATION) {
            onOperationResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        String heard = results.get(0);
        String current = editText.getText().toString().trim();
        editText.setText(current.isEmpty() ? heard : current + " " + heard);
        editText.setSelection(editText.length());
    }

    // ── Разбор ────────────────────────────────────────────────────────────────

    public void onParse(View view) {
        String text = editText.getText().toString().trim();
        if (busy || text.isEmpty() || text.length() > MAX_TEXT) return;

        final int gen = ++generation;
        jobId = null;
        busy = true;
        sourceText = text;
        clearDraft();
        messageBox.setVisibility(View.GONE);
        tvDone.setVisibility(View.GONE);
        tvWarning.setVisibility(View.GONE);
        showProgress("Синхронизирую…");
        updateInputState();

        final Context app = getApplicationContext();
        new Thread(() -> {
            SyncRunner.Outcome sync = SyncRunner.runOnceShared(app, SYNC_WAIT_MS);
            if (gen != generation) return;
            if (sync.status == SyncRunner.Status.NO_SESSION || sync.status == SyncRunner.Status.NEEDS_RELINK) {
                runOnUiThread(() -> finishWithMessage(gen, LOGIN_TEXT, true));
                return;
            }
            runOnUiThread(() -> {
                if (gen != generation) return;
                syncBeforeParse = sync;
                if (sync.status != SyncRunner.Status.OK) {
                    tvWarning.setText(AiTexts.syncWarning(sync));
                    tvWarning.setVisibility(View.VISIBLE);
                }
                tvProgress.setText("Разбираю…");
            });
            parse(gen, app, text);
        }).start();
    }

    /** Фоновый поток: задание и long-poll до конечного статуса. */
    private void parse(int gen, Context app, String text) {
        try {
            AiApiClient.SessionState session = AiApiClient.refreshSession(app);
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                runOnUiThread(() -> finishWithMessage(gen, LOGIN_TEXT, true));
                return;
            }
            if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                runOnUiThread(() -> finishWithMessage(gen, NO_NETWORK_TEXT, true));
                return;
            }
            AiApiClient client = AiApiClient.create(app);
            AiApiClient.Job job = client.createJob(text, LocalDateTime.now(), liveDefaultWalletUuid(app),
                    "android:" + UUID.randomUUID());
            jobId = job.id;
            while (job.isPending()) {
                if (gen != generation) return;
                job = client.getJob(job.id, AiApiClient.WAIT_SECS);
            }
            final AiApiClient.Job done = job;
            runOnUiThread(() -> onJobFinished(gen, done));
        } catch (AiApiClient.HttpError e) {
            runOnUiThread(() -> finishWithMessage(gen, httpErrorText(e), e.code != 429 && e.code != 404));
        } catch (IOException e) {
            runOnUiThread(() -> finishWithMessage(gen, NO_NETWORK_TEXT, true));
        } catch (JSONException e) {
            runOnUiThread(() -> finishWithMessage(gen, "Некорректный ответ сервера", true));
        }
    }

    private static String httpErrorText(AiApiClient.HttpError e) {
        switch (e.code) {
            case 429: return "ИИ занят, попробуйте через минуту";
            case 503: return "ИИ сейчас выключен";
            case 404: return "Результат потерялся, попробуйте ещё раз";
            case 401: return LOGIN_TEXT;
            case 403: return "Нет доступа к ИИ для этого аккаунта";
            default: return "Сервер ответил ошибкой (HTTP " + e.code + ")";
        }
    }

    /** default_wallet_uuid — только если этот кошелёк жив на телефоне. */
    private static String liveDefaultWalletUuid(Context ctx) {
        String uuid = AppSettings.getAiLastWalletUuid(ctx);
        if (uuid == null) return null;
        Ref ref = new Ref(uuid, "", "", "", 2);
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(ctx);
        return DraftReconciler.lookup(db, ref).state == DraftReconciler.State.OK ? uuid : null;
    }

    private void onJobFinished(int gen, AiApiClient.Job job) {
        if (gen != generation) return;
        endBusy();
        if ("cancelled".equals(job.status)) return;
        if ("failed".equals(job.status) || job.result == null) {
            showMessage("ИИ не ответил вовремя", true);
            return;
        }
        ParseExpenseResult r = job.result;
        if (r.isDraft() && r.expense != null && r.resolution != null) {
            onDraft(r);
        } else if (ParseExpenseResult.REJECTED.equals(r.outcome)) {
            showMessage(AiTexts.rejected(r.reasons), true);
        } else {
            showMessage("Не нашёл тут трату", true);
        }
    }

    private void finishWithMessage(int gen, String text, boolean offerManual) {
        if (gen != generation) return;
        endBusy();
        showMessage(text, offerManual);
    }

    public void onCancel(View view) {
        cancelRunning();
        endBusy();
    }

    /** Новый номер разбора — поздние ответы игнорируются; задание на сервере снимается. */
    private void cancelRunning() {
        generation++;
        final String id = jobId;
        jobId = null;
        if (id == null) return;
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                AiApiClient.create(app).cancelJob(id);
            } catch (IOException ignored) {
                // Не отменилось — сервер сам выбросит результат по TTL.
            }
        }).start();
    }

    private void endBusy() {
        busy = false;
        progressBox.setVisibility(View.GONE);
        updateInputState();
    }

    private void showProgress(String text) {
        tvProgress.setText(text);
        progressBox.setVisibility(View.VISIBLE);
    }

    private void showMessage(String text, boolean offerManual) {
        tvMessage.setText(text);
        findViewById(R.id.btnManual).setVisibility(offerManual ? View.VISIBLE : View.GONE);
        messageBox.setVisibility(View.VISIBLE);
    }

    /** Обычный экран новой операции с исходным текстом в комментарии. */
    public void onManual(View view) {
        String text = sourceText != null ? sourceText : editText.getText().toString().trim();
        Intent intent = new Intent(this, TransactionActivity.class);
        intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(new Date()));
        intent.putExtra(TransactionActivity.cComment, text);
        intent.putExtra(TransactionActivity.cAmount, 0.0);
        intent.putExtra(TransactionActivity.cIsEdit, false);
        intent.putExtra(TransactionActivity.cKind, "expense");
        startActivity(intent);
    }

    // ── Черновик → окно операции ─────────────────────────────────────────────

    private void onDraft(ParseExpenseResult r) {
        draft = r;
        // Синк перед разбором мог принести новые счета — пикеры окна читают кэш.
        Singleton.instance.reloadAccounts(this);
        openOrExplain();
    }

    /** Пропавших счетов нет — сразу окно; есть — блок ошибки на экране «ИИ». */
    private void openOrExplain() {
        OperationPrefill prefill = prefill();
        if (prefill.hasProblem()) {
            showProblem(prefill);
        } else {
            problemBox.setVisibility(View.GONE);
            openOperation(prefill);
        }
    }

    private OperationPrefill prefill() {
        return OperationPrefill.build(Singleton.instance.getReadableDatabase(this), draft, sourceText);
    }

    private void openOperation(OperationPrefill p) {
        pendingBox.setVisibility(View.GONE);
        Intent intent = new Intent(this, TransactionActivity.class);
        intent.putExtra(TransactionActivity.cDate, p.date != null ? p.date : DateUtils.DateToStr(new Date()));
        intent.putExtra(TransactionActivity.cAmount, p.amount);
        intent.putExtra(TransactionActivity.cComment, p.comment);
        intent.putExtra(TransactionActivity.cFrom, p.walletId);
        intent.putExtra(TransactionActivity.cTo, p.categoryId);
        intent.putExtra(TransactionActivity.cIsEdit, false);
        intent.putExtra(TransactionActivity.cKind, "expense");
        intent.putExtra(TransactionActivity.cHint, p.hint);
        intent.putExtra(TransactionActivity.cPriorityWalletIds, p.priorityWalletIds);
        intent.putExtra(TransactionActivity.cPriorityCategoryIds, p.priorityCategoryIds);
        startActivityForResult(intent, REQUEST_OPERATION);
    }

    /** «Открыть операцию» (блок ошибки) и «Открыть снова» — тот же черновик, без запроса к ИИ. */
    public void onOpenOperation(View view) {
        if (draft == null) return;
        problemBox.setVisibility(View.GONE);
        openOperation(prefill());
    }

    private void showProblem(OperationPrefill p) {
        List<String> lines = new ArrayList<>();
        if (p.walletProblem != null) {
            lines.add(AiTexts.problem(true, p.walletProblem.ref.name, p.walletProblem.state));
        }
        if (p.categoryProblem != null) {
            lines.add(AiTexts.problem(false, p.categoryProblem.ref.name, p.categoryProblem.state));
        }
        String uuid = p.walletProblem != null ? p.walletProblem.ref.uuid : p.categoryProblem.ref.uuid;
        tvProblem.setText(String.join("\n", lines));
        tvProblemDetails.setText(AiTexts.details(syncBeforeParse,
                AppSettings.getLastSyncSuccessAt(this), uuid, new Date()));
        pendingBox.setVisibility(View.GONE);
        problemBox.setVisibility(View.VISIBLE);
    }

    /** «Синхронизировать и повторить»: синк и сверка того же черновика, без нового задания. */
    public void onSyncRetry(View view) {
        if (busy || draft == null) return;
        busy = true;
        final int gen = generation;
        showProgress("Синхронизирую…");
        updateInputState();
        final Context app = getApplicationContext();
        new Thread(() -> {
            SyncRunner.Outcome sync = SyncRunner.runOnceShared(app, SYNC_WAIT_MS);
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) return;
                syncBeforeParse = sync;
                endBusy();
                if (draft == null) return;
                Singleton.instance.reloadAccounts(this);
                openOrExplain();
            });
        }).start();
    }

    /** Возврат из окна: сохранено — «Записано: …» по операции из базы; нет — «Разбор не записан». */
    private void onOperationResult(int resultCode, Intent data) {
        long id = resultCode == RESULT_OK && data != null
                ? data.getLongExtra(TransactionActivity.cSavedOperationId, -1) : -1;
        if (id <= 0) {
            if (draft != null) pendingBox.setVisibility(View.VISIBLE);
            return;
        }

        SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
        String done = savedLine(db, id);
        PeriodFilterState.get(this).markDataChanged();
        // Отвезти трату на сервер сразу, не дожидаясь фонового синка; ждать его экрану незачем.
        final Context app = getApplicationContext();
        new Thread(() -> SyncRunner.runOnce(app)).start();

        tvDone.setText(done);
        tvDone.setVisibility(done != null ? View.VISIBLE : View.GONE);
        clearDraft();
        editText.setText("");
        sourceText = null;
        tvWarning.setVisibility(View.GONE);
    }

    /**
     * «Записано: 450 ₽ · Такси · Тинькофф» по сохранённой операции — пользователь мог
     * сменить в окне сумму, счета и вид. Заодно запоминает кошелёк для default_wallet_uuid.
     */
    private String savedLine(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT amount, acc_fr_id, acc_to_id FROM operation WHERE _id=? LIMIT 1",
                new String[]{String.valueOf(id)});
        try {
            if (!c.moveToFirst()) return null;
            BigDecimal amount = BigDecimal.valueOf(c.getDouble(0)).stripTrailingZeros();
            Singleton.instance.reloadAccounts(this);
            AccountList accounts = Singleton.instance.getAccounts(this);
            AccountItem from = accounts.getAccountById(c.getInt(1));
            AccountItem to = accounts.getAccountById(c.getInt(2));
            AccountItem wallet = from != null && from.isOperatingAccount() ? from : to;
            AccountItem other = wallet == from ? to : from;
            if (wallet != null) AppSettings.setAiLastWalletUuid(this, serverUuid(db, wallet.getId()));
            String symbol = wallet instanceof AccountOperatingItem
                    ? " " + ((AccountOperatingItem) wallet).getCurrency().getSymbol() : "";
            return "Записано: " + amount.toPlainString() + symbol
                    + " · " + (other != null ? other.getName() : "")
                    + " · " + (wallet != null ? wallet.getName() : "");
        } finally {
            c.close();
        }
    }

    private void clearDraft() {
        draft = null;
        problemBox.setVisibility(View.GONE);
        pendingBox.setVisibility(View.GONE);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (draft == null) return;
        out.putString(STATE_DRAFT, draft.json);
        out.putString(STATE_SOURCE, sourceText);
        if (syncBeforeParse != null) {
            out.putString(STATE_SYNC_STATUS, syncBeforeParse.status.name());
            out.putString(STATE_SYNC_MESSAGE, syncBeforeParse.errorMessage);
        }
    }

    /** Черновик после пересоздания экрана: окно операции могло быть открыто поверх. */
    private void restoreDraft(Bundle in) {
        String json = in.getString(STATE_DRAFT);
        if (json == null) return;
        try {
            draft = ParseExpenseResult.fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return;
        }
        sourceText = in.getString(STATE_SOURCE);
        String status = in.getString(STATE_SYNC_STATUS);
        if (status != null) {
            syncBeforeParse = new SyncRunner.Outcome(SyncRunner.Status.valueOf(status), new SyncLog(),
                    in.getString(STATE_SYNC_MESSAGE));
        }
        pendingBox.setVisibility(View.VISIBLE);
    }

    /** uuid строки в серверном написании (с дефисами, строчными): локально бывает и без дефисов. */
    static String serverUuid(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT uuid FROM account WHERE _id=? LIMIT 1", new String[]{String.valueOf(id)});
        try {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            String plain = c.getString(0).toLowerCase(Locale.ROOT).replace("-", "");
            if (plain.length() != 32) return c.getString(0);
            return plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16)
                    + "-" + plain.substring(16, 20) + "-" + plain.substring(20);
        } finally {
            c.close();
        }
    }

    /** TextWatcher одним методом. */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChange.run(); }
    }
}
