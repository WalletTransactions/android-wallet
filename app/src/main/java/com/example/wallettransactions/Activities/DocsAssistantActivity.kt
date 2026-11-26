package com.example.wallettransactions.Activities

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.R
import com.example.wallettransactions.sync.AiApiClient
import com.example.wallettransactions.sync.DocsApiClient

/**
 * Экран «Вопрос по приложению»: один вопрос — один ответ по описанию приложения
 * через wallet-docs (openspec changes/docs-assistant, design D2–D5).
 *
 * Вопрос и ответ живут только в Activity: поворот их сохраняет
 * (`configChanges` в манифесте + [onSaveInstanceState]), закрытие — стирает.
 * Сеть — на голом `new Thread`, как у «ИИ»; поздний ответ закрытого экрана
 * отсекается проверкой [isFinishing]. Текст вопроса и ответа в logcat не пишется.
 */
class DocsAssistantActivity : AppCompatActivity() {

    private lateinit var editQuestion: EditText
    private lateinit var btnAsk: Button
    private lateinit var tvError: TextView
    private lateinit var answerBox: View
    private lateinit var tvAnswer: TextView
    private lateinit var tvSources: TextView

    private var loading = false

    /** Названия разделов без подписи «Источники» — в таком виде они сохраняются при повороте. */
    private var sourcesLine = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs_assistant)
        editQuestion = findViewById(R.id.editQuestion)
        btnAsk = findViewById(R.id.btnAsk)
        tvError = findViewById(R.id.tvError)
        answerBox = findViewById(R.id.answerBox)
        tvAnswer = findViewById(R.id.tvAnswer)
        tvSources = findViewById(R.id.tvSources)

        editQuestion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAskEnabled()
            }
        })

        if (savedInstanceState != null) {
            val answer = savedInstanceState.getString(STATE_ANSWER)
            if (answer != null) showAnswer(answer, savedInstanceState.getString(STATE_SOURCES, ""))
            val error = savedInstanceState.getString(STATE_ERROR)
            if (error != null) showError(error)
        }
        updateAskEnabled()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (answerBox.visibility == View.VISIBLE && !loading) {
            out.putString(STATE_ANSWER, tvAnswer.text.toString())
            out.putString(STATE_SOURCES, sourcesLine)
        }
        if (tvError.visibility == View.VISIBLE) out.putString(STATE_ERROR, tvError.text.toString())
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onAsk(view: View) {
        val question = editQuestion.text.toString().trim()
        if (loading || question.isEmpty()) return
        setLoading(true)
        val app: Context = applicationContext
        Thread {
            var answer: DocsApiClient.Answer? = null
            var error: String? = null
            val session = AiApiClient.refreshSession(app)
            if (session == AiApiClient.SessionState.NO_SESSION || session == AiApiClient.SessionState.NEEDS_RELINK) {
                error = app.getString(R.string.docs_err_relogin)
            } else if (session == AiApiClient.SessionState.NETWORK_ERROR) {
                error = app.getString(R.string.docs_err_network)
            } else {
                try {
                    answer = DocsApiClient.create(app).ask(question)
                } catch (e: Exception) {
                    error = errorText(app, e)
                }
            }
            val result = answer
            val message = error
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                if (result != null) {
                    showAnswer(result.answer, result.sourcesLine())
                } else {
                    showError(message)
                }
            }
        }.start()
    }

    private fun setLoading(value: Boolean) {
        loading = value
        if (value) {
            tvError.visibility = View.GONE
            tvAnswer.setText(R.string.docs_searching)
            tvSources.visibility = View.GONE
            answerBox.visibility = View.VISIBLE
        }
        updateAskEnabled()
    }

    private fun showAnswer(answer: String, sources: String) {
        sourcesLine = sources
        tvError.visibility = View.GONE
        tvAnswer.text = answer
        if (sources.isEmpty()) {
            tvSources.visibility = View.GONE
        } else {
            tvSources.text = getString(R.string.docs_sources, sources)
            tvSources.visibility = View.VISIBLE
        }
        answerBox.visibility = View.VISIBLE
    }

    /** Вопрос остаётся в поле, кнопка снова доступна — повтор отправляет запрос заново. */
    private fun showError(message: String?) {
        answerBox.visibility = View.GONE
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun updateAskEnabled() {
        btnAsk.isEnabled = !loading && editQuestion.text.toString().trim().isNotEmpty()
    }

    companion object {
        internal const val MAX_QUESTION = 1000

        private const val STATE_ANSWER = "docs.answer"
        private const val STATE_SOURCES = "docs.sources"
        private const val STATE_ERROR = "docs.error"

        /** Сообщение по классу ошибки (design D5); текст ошибки `400` — от сервиса. */
        private fun errorText(ctx: Context, e: Exception): String {
            return when (DocsApiClient.classify(e)) {
                DocsApiClient.ErrorKind.RELOGIN -> ctx.getString(R.string.docs_err_relogin)
                DocsApiClient.ErrorKind.BAD_REQUEST -> {
                    val text = (e as AiApiClient.HttpError).error
                    if (text.isEmpty()) ctx.getString(R.string.docs_err_bad_request) else text
                }
                DocsApiClient.ErrorKind.UNAVAILABLE -> ctx.getString(R.string.docs_err_unavailable)
                DocsApiClient.ErrorKind.NETWORK -> ctx.getString(R.string.docs_err_network)
            }
        }
    }
}
