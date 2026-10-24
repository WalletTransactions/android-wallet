package com.example.wallettransactions.Activities;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.ai.AiTexts;
import com.example.wallettransactions.ai.DraftReconciler;
import com.example.wallettransactions.ai.ParseExpenseResult;
import com.example.wallettransactions.ai.ParseExpenseResult.Choice;
import com.example.wallettransactions.ai.ParseExpenseResult.Ref;
import com.example.wallettransactions.sync.AiApiClient;
import com.example.wallettransactions.sync.SyncRunner;

import org.json.JSONException;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
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
 * {@code parse_expense} и long-poll. Черновик сверяется с базой телефона
 * ({@link DraftReconciler}): записать можно только на живые кошелёк и категорию.
 * Запись — локально через {@link WalletDbCRUD#InsertOperation}, на сервер её
 * отвезёт обычный синк.
 * <p>
 * Сеть — на голом {@code new Thread}, как у «Интеграций»; поздние ответы
 * отменённого разбора отсекает {@link #generation}. Текст траты в logcat не пишется.
 */
public class AiActivity extends AppCompatActivity {

    static final int MAX_TEXT = 500;
    static final long SYNC_WAIT_MS = 10_000;
    private static final int REQUEST_SPEECH = 41;

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
    private View cardDraft;
    private EditText editAmount;
    private TextView tvCurrency;
    private TextView tvWalletLabel;
    private TextView tvWallet;
    private TextView tvWalletNote;
    private TextView tvCategoryLabel;
    private TextView tvCategory;
    private TextView tvDate;
    private TextView tvDateNote;
    private EditText editComment;
    private TextView tvNotes;
    private View problemBox;
    private TextView tvProblem;
    private TextView tvProblemDetails;
    private TextView tvSource;
    private Button btnSave;

    /** Номер текущего разбора: ответ потока с другим номером уже никому не нужен. */
    private volatile int generation;
    private volatile String jobId;
    private boolean busy;
    private boolean sessionMissing;

    /** Исход синка перед текущим разбором — для деталей блока ошибки. */
    private SyncRunner.Outcome syncBeforeParse;

    // Карточка
    private ParseExpenseResult draft;
    private String sourceText;
    private AccountOperatingItem wallet;
    private AccountItem category;
    /** Серверное написание uuid выбранного кошелька — для default_wallet_uuid. */
    private String walletUuid;
    private final Calendar dateTime = Calendar.getInstance();
    private DraftReconciler.Field walletField;
    private DraftReconciler.Field categoryField;

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
        cardDraft = findViewById(R.id.cardDraft);
        editAmount = findViewById(R.id.editAmount);
        tvCurrency = findViewById(R.id.tvCurrency);
        tvWalletLabel = findViewById(R.id.tvWalletLabel);
        tvWallet = findViewById(R.id.tvWallet);
        tvWalletNote = findViewById(R.id.tvWalletNote);
        tvCategoryLabel = findViewById(R.id.tvCategoryLabel);
        tvCategory = findViewById(R.id.tvCategory);
        tvDate = findViewById(R.id.tvDate);
        tvDateNote = findViewById(R.id.tvDateNote);
        editComment = findViewById(R.id.editComment);
        tvNotes = findViewById(R.id.tvNotes);
        problemBox = findViewById(R.id.problemBox);
        tvProblem = findViewById(R.id.tvProblem);
        tvProblemDetails = findViewById(R.id.tvProblemDetails);
        tvSource = findViewById(R.id.tvSource);
        btnSave = findViewById(R.id.btnSave);

        editText.addTextChangedListener(new SimpleWatcher(this::updateInputState));
        editAmount.addTextChangedListener(new SimpleWatcher(this::validateCard));

        // Системное распознавание речи; нет его на устройстве — нет и кнопки.
        boolean canSpeak = speechIntent().resolveActivity(getPackageManager()) != null;
        btnMic.setVisibility(canSpeak ? View.VISIBLE : View.GONE);

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
    protected void onResume() {
        super.onResume();
        // Карточка открыта, а кошелёк могли удалить на другом экране — перепроверить.
        if (cardDraft.getVisibility() == View.VISIBLE) validateCard();
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
        hideCard();
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
            showCard(r);
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

    // ── Карточка ──────────────────────────────────────────────────────────────

    private void showCard(ParseExpenseResult r) {
        draft = r;
        // Синк мог принести новые счета — карточка строит списки из свежего кэша.
        Singleton.instance.reloadAccounts(this);

        editAmount.setText(r.expense.amount);
        editComment.setText(r.expense.comment);
        tvSource.setText("«" + sourceText + "»");
        dateTime.setTime(parseDraftDate(r.expense.datetime));
        tvDate.setText(DateUtils.DateToStr(dateTime.getTime()));

        List<String> notes = new ArrayList<>();
        if (r.notes.contains("currency_dropped")) notes.add("Валюту из текста пришлось убрать — проверьте кошелёк");
        if (r.notes.contains("comment_truncated")) notes.add("Комментарий сокращён");
        tvNotes.setText(String.join("\n", notes));
        tvNotes.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);

        reconcileDraft();
        cardDraft.setVisibility(View.VISIBLE);
    }

    /** Сверка черновика с телефоном: выбор полей, подписи resolver и блок ошибки. */
    private void reconcileDraft() {
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
        walletField = DraftReconciler.reconcile(db, draft.resolution.wallet);
        categoryField = DraftReconciler.reconcile(db, draft.resolution.category);

        AccountList accounts = Singleton.instance.getAccounts(this);
        wallet = null;
        walletUuid = null;
        if (walletField.selected != null) {
            AccountItem item = accounts.getAccountById((int) walletField.selected.id);
            if (item instanceof AccountOperatingItem) {
                wallet = (AccountOperatingItem) item;
                walletUuid = walletField.selected.ref.uuid;
            }
        }
        category = null;
        if (categoryField.selected != null) {
            AccountItem item = accounts.getAccountById((int) categoryField.selected.id);
            if (item != null && !item.isOperatingAccount()) category = item;
        }

        // Подписи вопроса resolver
        Choice w = draft.resolution.wallet;
        String walletNote = null;
        tvWalletLabel.setText("Кошелёк");
        if (w != null && Choice.ASK.equals(w.kind)) {
            tvWalletLabel.setText("С какого кошелька?");
            if ("currency_mismatch".equals(w.why) && w.force != null) {
                walletNote = "В тексте " + draft.expense.currencyHint + ", а «" + w.force.name + "» в "
                        + w.force.isoCode;
            }
        } else if (w != null && Choice.REJECT.equals(w.kind)) {
            walletNote = AiTexts.walletReject(w.why, draft.expense.currencyHint);
        }
        tvWalletNote.setText(walletNote);
        tvWalletNote.setVisibility(walletNote == null ? View.GONE : View.VISIBLE);
        Choice c = draft.resolution.category;
        tvCategoryLabel.setText(c != null && Choice.ASK.equals(c.kind) ? "Какая категория?" : "Категория");

        showProblem(walletField.problem, categoryField.problem);
        renderSelection();
    }

    /** Блок «пропал с телефона» — по кошельку и/или категории; null, null — скрыть. */
    private void showProblem(DraftReconciler.Local walletProblem, DraftReconciler.Local categoryProblem) {
        if (walletProblem == null && categoryProblem == null) {
            problemBox.setVisibility(View.GONE);
            return;
        }
        List<String> lines = new ArrayList<>();
        if (walletProblem != null) lines.add(AiTexts.problem(true, walletProblem.ref.name, walletProblem.state));
        if (categoryProblem != null) {
            lines.add(AiTexts.problem(false, categoryProblem.ref.name, categoryProblem.state));
        }
        String uuid = walletProblem != null ? walletProblem.ref.uuid : categoryProblem.ref.uuid;
        tvProblem.setText(String.join("\n", lines));
        tvProblemDetails.setText(AiTexts.details(syncBeforeParse,
                AppSettings.getLastSyncSuccessAt(this), uuid, new Date()));
        problemBox.setVisibility(View.VISIBLE);
    }

    private void renderSelection() {
        tvWallet.setText(wallet != null ? wallet.getName() : "");
        tvCurrency.setText(wallet != null ? wallet.getCurrency().getSymbol() : "");
        tvCategory.setText(category != null ? category.getName() : "");
        validateCard();
    }

    /** Проверки перед записью: сумма, дата, живые кошелёк и категория. */
    private void validateCard() {
        if (draft == null) {
            btnSave.setEnabled(false);
            return;
        }
        BigDecimal amount = parseAmount(editAmount.getText().toString());
        int precision = wallet != null ? wallet.getCurrency().getPrecision() : 2;
        boolean amountOk = amount != null && amount.signum() > 0
                && Math.max(amount.stripTrailingZeros().scale(), 0) <= precision;
        editAmount.setError(amountOk || editAmount.length() == 0 ? null : "Проверьте сумму");
        boolean dateOk = !dateTime.getTime().after(new Date());
        tvDateNote.setVisibility(dateOk ? View.GONE : View.VISIBLE);
        btnSave.setEnabled(amountOk && dateOk && wallet != null && category != null);
    }

    static BigDecimal parseAmount(String s) {
        String t = s.trim().replace(',', '.').replace(" ", "");
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Date parseDraftDate(String s) {
        if (s != null && !s.isEmpty()) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(s);
            } catch (ParseException ignored) {
                // ниже — «сейчас»
            }
        }
        return new Date();
    }

    public void onPickWallet(View view) {
        List<AccountItem> items = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        AccountList accounts = Singleton.instance.getAccounts(this);
        for (DraftReconciler.Local local : walletField.candidates) {
            addOption(items, labels, accounts.getAccountById((int) local.id), null);
        }
        if (walletField.force != null) {
            AccountItem force = accounts.getAccountById((int) walletField.force.id);
            if (force != null) addOption(items, labels, force, " — всё равно в «" + force.getName() + "»");
        }
        for (AccountOperatingItem item : accounts.getOperationAccounts()) {
            if (!item.getIsArchived() && !item.isSystem()) addOption(items, labels, item, null);
        }
        pick("Кошелёк", items, labels, picked -> {
            wallet = (AccountOperatingItem) picked;
            walletUuid = null;
            clearProblemIfResolved();
            renderSelection();
        });
    }

    public void onPickCategory(View view) {
        List<AccountItem> items = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        AccountList accounts = Singleton.instance.getAccounts(this);
        for (DraftReconciler.Local local : categoryField.candidates) {
            addOption(items, labels, accounts.getAccountById((int) local.id), null);
        }
        for (AccountItem item : accounts.getExpanseAccounts()) {
            if (!item.getIsArchived() && !item.isSystem()) addOption(items, labels, item, null);
        }
        pick("Категория", items, labels, picked -> {
            category = picked;
            clearProblemIfResolved();
            renderSelection();
        });
    }

    /** «Выбрать другой» — то поле, по которому ошибка (кошелёк — первым). */
    public void onPickOther(View view) {
        if (wallet == null) onPickWallet(view);
        else onPickCategory(view);
    }

    private void clearProblemIfResolved() {
        DraftReconciler.Local wp = wallet == null ? walletField.problem : null;
        DraftReconciler.Local cp = category == null ? categoryField.problem : null;
        showProblem(wp, cp);
    }

    private static void addOption(List<AccountItem> items, List<String> labels, AccountItem item, String suffix) {
        if (item == null) return;
        if (suffix == null && items.contains(item)) return;
        items.add(item);
        labels.add(item.getName() + (suffix != null ? suffix : ""));
    }

    private interface OnPicked {
        void picked(AccountItem item);
    }

    private void pick(String title, List<AccountItem> items, List<String> labels, OnPicked onPicked) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> onPicked.picked(items.get(which)))
                .show();
    }

    public void onPickDate(View view) {
        new DatePickerDialog(this, (dp, y, m, d) -> {
            dateTime.set(y, m, d);
            new TimePickerDialog(this, (tp, h, min) -> {
                dateTime.set(Calendar.HOUR_OF_DAY, h);
                dateTime.set(Calendar.MINUTE, min);
                tvDate.setText(DateUtils.DateToStr(dateTime.getTime()));
                validateCard();
            }, dateTime.get(Calendar.HOUR_OF_DAY), dateTime.get(Calendar.MINUTE), true).show();
        }, dateTime.get(Calendar.YEAR), dateTime.get(Calendar.MONTH), dateTime.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** «Синхронизировать и повторить»: синк и сверка того же черновика, без нового задания. */
    public void onSyncRetry(View view) {
        if (busy) return;
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
                if (draft != null) {
                    Singleton.instance.reloadAccounts(this);
                    reconcileDraft();
                }
            });
        }).start();
    }

    public void onDiscard(View view) {
        hideCard();
    }

    private void hideCard() {
        draft = null;
        wallet = null;
        category = null;
        cardDraft.setVisibility(View.GONE);
        problemBox.setVisibility(View.GONE);
    }

    // ── Запись ────────────────────────────────────────────────────────────────

    public void onSave(View view) {
        validateCard();
        if (!btnSave.isEnabled()) return;

        SQLiteDatabase db = Singleton.instance.getWritableDatabase(this);
        // Повторная сверка: кошелёк могли удалить, пока карточка была открыта.
        DraftReconciler.State ws = DraftReconciler.stateById(db, wallet.getId());
        DraftReconciler.State cs = DraftReconciler.stateById(db, category.getId());
        if (ws != DraftReconciler.State.OK || cs != DraftReconciler.State.OK) {
            DraftReconciler.Local wp = ws == DraftReconciler.State.OK ? null : localOf(db, wallet, ws);
            DraftReconciler.Local cp = cs == DraftReconciler.State.OK ? null : localOf(db, category, cs);
            if (wp != null) wallet = null;
            if (cp != null) category = null;
            showProblem(wp, cp);
            renderSelection();
            return;
        }

        BigDecimal amount = parseAmount(editAmount.getText().toString());
        String comment = editComment.getText().toString().trim();
        long id = WalletDbCRUD.InsertOperation(db, DateUtils.DateToStr(dateTime.getTime()),
                wallet.getId(), category.getId(), amount.doubleValue(), comment);
        if (id == -1) {
            Toast.makeText(this, "Произошла ошибка при записи", Toast.LENGTH_SHORT).show();
            return;
        }

        AppSettings.setAiLastWalletUuid(this, walletUuid != null ? walletUuid : serverUuid(db, wallet.getId()));
        PeriodFilterState.get(this).markDataChanged();
        // Отвезти трату на сервер сразу, не дожидаясь фонового синка; ждать его экрану незачем.
        final Context app = getApplicationContext();
        new Thread(() -> SyncRunner.runOnce(app)).start();

        tvDone.setText("Записано: " + amount.stripTrailingZeros().toPlainString() + " "
                + wallet.getCurrency().getSymbol() + " · " + category.getName() + " · " + wallet.getName());
        tvDone.setVisibility(View.VISIBLE);
        hideCard();
        editText.setText("");
        sourceText = null;
        tvWarning.setVisibility(View.GONE);
    }

    private static DraftReconciler.Local localOf(SQLiteDatabase db, AccountItem item, DraftReconciler.State state) {
        return new DraftReconciler.Local(new Ref(serverUuid(db, item.getId()), item.getName(), "", "", 2),
                state, item.getId());
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
