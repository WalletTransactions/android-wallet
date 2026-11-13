package com.example.wallettransactions.Activities;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.R;
import com.example.wallettransactions.sync.AiApiClient;
import com.example.wallettransactions.sync.DocsApiClient;

/**
 * Экран «Вопрос по приложению»: один вопрос — один ответ по описанию приложения
 * через wallet-docs (openspec changes/docs-assistant, design D2–D5).
 * <p>
 * Вопрос и ответ живут только в Activity: поворот их сохраняет
 * ({@code configChanges} в манифесте + {@link #onSaveInstanceState}), закрытие — стирает.
 * Сеть — на голом {@code new Thread}, как у «ИИ»; поздний ответ закрытого экрана
 * отсекается проверкой {@link #isFinishing()}. Текст вопроса и ответа в logcat не пишется.
 */
public class DocsAssistantActivity extends AppCompatActivity {

    static final int MAX_QUESTION = 1000;

    private static final String STATE_ANSWER = "docs.answer";
    private static final String STATE_SOURCES = "docs.sources";
    private static final String STATE_ERROR = "docs.error";

    private EditText editQuestion;
    private Button btnAsk;
    private TextView tvError;
    private View answerBox;
    private TextView tvAnswer;
    private TextView tvSources;

    private boolean loading;
    /** Названия разделов без подписи «Источники» — в таком виде они сохраняются при повороте. */
    private String sourcesLine = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_docs_assistant);
        editQuestion = findViewById(R.id.editQuestion);
        btnAsk = findViewById(R.id.btnAsk);
        tvError = findViewById(R.id.tvError);
        answerBox = findViewById(R.id.answerBox);
        tvAnswer = findViewById(R.id.tvAnswer);
        tvSources = findViewById(R.id.tvSources);

        editQuestion.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateAskEnabled(); }
        });

        if (savedInstanceState != null) {
            String answer = savedInstanceState.getString(STATE_ANSWER);
            if (answer != null) showAnswer(answer, savedInstanceState.getString(STATE_SOURCES, ""));
            String error = savedInstanceState.getString(STATE_ERROR);
            if (error != null) showError(error);
        }
        updateAskEnabled();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (answerBox.getVisibility() == View.VISIBLE && !loading) {
            out.putString(STATE_ANSWER, tvAnswer.getText().toString());
            out.putString(STATE_SOURCES, sourcesLine);
        }
        if (tvError.getVisibility() == View.VISIBLE) out.putString(STATE_ERROR, tvError.getText().toString());
    }

    public void btnUp(View view) {
        finish();
    }

    public void onAsk(View view) {
        final String question = editQuestion.getText().toString().trim();
        if (loading || question.isEmpty()) return;
        setLoading(true);
        final Context app = getApplicationContext();
        new Thread(() -> {
            DocsApiClient.Answer answer = null;
            String error = null;
            AiApiClient.SessionState session = AiApiClient.refreshSession(app);
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                error = app.getString(R.string.docs_err_relogin);
            } else if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                error = app.getString(R.string.docs_err_network);
            } else {
                try {
                    answer = DocsApiClient.create(app).ask(question);
                } catch (Exception e) {
                    error = errorText(app, e);
                }
            }
            final DocsApiClient.Answer result = answer;
            final String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setLoading(false);
                if (result != null) {
                    showAnswer(result.answer, result.sourcesLine());
                } else {
                    showError(message);
                }
            });
        }).start();
    }

    /** Сообщение по классу ошибки (design D5); текст ошибки {@code 400} — от сервиса. */
    private static String errorText(Context ctx, Exception e) {
        switch (DocsApiClient.classify(e)) {
            case RELOGIN:
                return ctx.getString(R.string.docs_err_relogin);
            case BAD_REQUEST:
                String text = ((AiApiClient.HttpError) e).error;
                return text.isEmpty() ? ctx.getString(R.string.docs_err_bad_request) : text;
            case UNAVAILABLE:
                return ctx.getString(R.string.docs_err_unavailable);
            case NETWORK:
            default:
                return ctx.getString(R.string.docs_err_network);
        }
    }

    private void setLoading(boolean value) {
        loading = value;
        if (value) {
            tvError.setVisibility(View.GONE);
            tvAnswer.setText(R.string.docs_searching);
            tvSources.setVisibility(View.GONE);
            answerBox.setVisibility(View.VISIBLE);
        }
        updateAskEnabled();
    }

    private void showAnswer(String answer, String sources) {
        sourcesLine = sources;
        tvError.setVisibility(View.GONE);
        tvAnswer.setText(answer);
        if (sources.isEmpty()) {
            tvSources.setVisibility(View.GONE);
        } else {
            tvSources.setText(getString(R.string.docs_sources, sources));
            tvSources.setVisibility(View.VISIBLE);
        }
        answerBox.setVisibility(View.VISIBLE);
    }

    /** Вопрос остаётся в поле, кнопка снова доступна — повтор отправляет запрос заново. */
    private void showError(String message) {
        answerBox.setVisibility(View.GONE);
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void updateAskEnabled() {
        btnAsk.setEnabled(!loading && !editQuestion.getText().toString().trim().isEmpty());
    }
}
