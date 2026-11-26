package com.example.wallettransactions.Activities

import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import com.example.wallettransactions.TokenStore
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.sync.NetworkErrors
import com.example.wallettransactions.sync.HttpClients
import com.example.wallettransactions.sync.PasswordAuth
import com.example.wallettransactions.sync.PasswordAuthMessages
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Связка приложения с сервером через Google (org/docs/TODO_mobile_sync.md,
 * Фаза 1) — `POST /auth/nonce` → Credential Manager →
 * `POST /auth/google/mobile` → токены в [TokenStore]. Тот же
 * путь обслуживает и первую связку, и повторную (например, после того как
 * [com.example.wallettransactions.sync.SyncWorker] обнаружил
 * истёкший refresh-токен) — экран не различает эти два случая, вход через
 * Google в обоих один и тот же.
 *
 * Открывается с экрана «Ещё» → «Интеграции» → «Связь с Google
 * аккаунтом» ([IntegrationsActivity.onLinkServer]).
 *
 * Кроме Google экран умеет вход и регистрацию по email и паролю (change
 * add-password-login): набор способов задаёт сервер (`GET /auth/methods`),
 * пара токенов из `POST /auth/login` сохраняется тем же [onLinked], что и
 * после Google. Пароль нигде не хранится и не логируется.
 *
 * Адрес сервера — [AppSettings.getServerBaseUrl], настраивается там
 * же (по умолчанию `http://localhost:8090/` — LAN-only на этом
 * этапе, план п. 1.4). Web client ID — тот же, что уже использует
 * `google-auth` (см. `cred/webclient.json`).
 */
class SyncLinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SyncLinkActivity"

        // google-auth/cred/webclient.json → "client_id". Аудитория
        // токенов, которые уже проверяет google-auth (Web, не Android) — см.
        // README google-auth, "1. Получить Client ID".
        private const val WEB_CLIENT_ID =
                "432841979878-srpc0c3msmuli6gdcsojc2g812nmlot7.apps.googleusercontent.com"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private lateinit var textResult: TextView
    private lateinit var textInsecureWarning: TextView
    private lateinit var blockPassword: View
    private lateinit var blockRegister: View
    private lateinit var blockGoogle: View
    private lateinit var editEmail: EditText
    private lateinit var editPassword: EditText
    private lateinit var buttonLogin: Button
    private lateinit var buttonCreateAccount: Button
    private lateinit var editRegEmail: EditText
    private lateinit var editRegPassword: EditText
    private lateinit var editRegPassword2: EditText
    private lateinit var editRegName: EditText
    private lateinit var buttonRegister: Button

    /** Что сервер включил (ответ `GET /auth/methods`); `null` — ещё не получен. */
    private var methods: PasswordAuth.Methods? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_link)

        textResult = findViewById(R.id.textResult)
        textInsecureWarning = findViewById(R.id.textInsecureWarning)
        blockPassword = findViewById(R.id.blockPassword)
        blockRegister = findViewById(R.id.blockRegister)
        blockGoogle = findViewById(R.id.blockGoogle)
        editEmail = findViewById(R.id.editEmail)
        editPassword = findViewById(R.id.editPassword)
        buttonLogin = findViewById(R.id.buttonLogin)
        buttonCreateAccount = findViewById(R.id.buttonCreateAccount)
        editRegEmail = findViewById(R.id.editRegEmail)
        editRegPassword = findViewById(R.id.editRegPassword)
        editRegPassword2 = findViewById(R.id.editRegPassword2)
        editRegName = findViewById(R.id.editRegName)
        buttonRegister = findViewById(R.id.buttonRegister)

        val buttonSignIn = findViewById<Button>(R.id.buttonSignIn)
        buttonSignIn.setOnClickListener { fetchNonceThenSignIn() }
        buttonLogin.setOnClickListener { onPasswordLoginClicked() }
        buttonCreateAccount.setOnClickListener { showRegisterForm(true) }
        findViewById<View>(R.id.buttonRegisterBack).setOnClickListener { showRegisterForm(false) }
        buttonRegister.setOnClickListener { onRegisterClicked() }

        loadMethods()
    }

    // ---- Способы входа (GET /auth/methods) ----

    /** Запрашивает у сервера включённые способы входа и строит экран по ответу. */
    private fun loadMethods() {
        val request = Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/methods")
                .get()
                .build()
        HttpClients.get(this).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "GET /auth/methods failed", e)
                runOnUiThread { showResult(getString(R.string.link_server_unreachable,
                        AppSettings.getServerBaseUrl(this@SyncLinkActivity), NetworkErrors.describe(this@SyncLinkActivity, e))) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body?.string() ?: ""
                        if (!r.isSuccessful) {
                            runOnUiThread { showResult(getString(R.string.link_server_unreachable,
                                    AppSettings.getServerBaseUrl(this@SyncLinkActivity), "HTTP " + r.code)) }
                            return
                        }
                        val parsed = PasswordAuth.parseMethods(body)
                        runOnUiThread { applyMethods(parsed) }
                    }
                } catch (e: JSONException) {
                    runOnUiThread { showResult(getString(R.string.link_server_unreachable,
                            AppSettings.getServerBaseUrl(this@SyncLinkActivity), e.message)) }
                }
            }
        })
    }

    /** Показывает блоки по ответу сервера: пароль, «Создать аккаунт», Google. */
    private fun applyMethods(m: PasswordAuth.Methods) {
        methods = m
        blockPassword.visibility = if (m.password) View.VISIBLE else View.GONE
        buttonCreateAccount.visibility = if (m.password && m.registration) View.VISIBLE else View.GONE
        blockGoogle.visibility = if (m.google) View.VISIBLE else View.GONE
        blockRegister.visibility = View.GONE
        updateInsecureWarning()
    }

    /** Переключает между формой входа и формой регистрации. */
    private fun showRegisterForm(register: Boolean) {
        val m = methods
        if (m == null || !m.password) return
        blockRegister.visibility = if (register) View.VISIBLE else View.GONE
        blockPassword.visibility = if (register) View.GONE else View.VISIBLE
        blockGoogle.visibility = if (register || !m.google) View.GONE else View.VISIBLE
        textResult.text = ""
        updateInsecureWarning()
    }

    /** Предупреждение над полями пароля, если адрес сервера — `http://` (не блокирует вход). */
    private fun updateInsecureWarning() {
        val passwordFormVisible = blockPassword.visibility == View.VISIBLE
                || blockRegister.visibility == View.VISIBLE
        val insecure = PasswordAuth.isInsecureUrl(AppSettings.getServerBaseUrl(this))
        textInsecureWarning.visibility = if (passwordFormVisible && insecure) View.VISIBLE else View.GONE
    }

    // ---- Вход по паролю (POST /auth/login) ----

    private fun onPasswordLoginClicked() {
        val email = PasswordAuth.normalizeEmail(editEmail.text.toString())
        val password = editPassword.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            showResult(getString(R.string.link_password_empty_fields))
            return
        }
        passwordLogin(email, password, false)
    }

    /**
     * Вход по паролю. `afterRegister` — вызвано сразу после `POST /auth/register`:
     * тогда `401` значит «адрес занят чужим паролем», а не «опечатка» (сервер на
     * регистрации всегда отвечает `202` и не раскрывает занятость).
     */
    private fun passwordLogin(email: String, password: String, afterRegister: Boolean) {
        showResult(getString(R.string.link_password_logging_in))
        setBusy(true)

        val payload = PasswordAuth.loginBody(email, password,
                AppSettings.getOrCreateDeviceId(this), Build.MANUFACTURER + " " + Build.MODEL)
        val request = Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/login")
                .post(payload.toString().toRequestBody(JSON))
                .build()

        HttpClients.get(this).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST /auth/login failed", e) // без тела запроса: там пароль
                runOnUiThread {
                    setBusy(false)
                    showResult(getString(R.string.link_server_unreachable,
                            AppSettings.getServerBaseUrl(this@SyncLinkActivity), NetworkErrors.describe(this@SyncLinkActivity, e)))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body?.string() ?: ""
                        val result = PasswordAuth.classify(r.code, body, r.header("Retry-After"), 200)
                        if (result.outcome != PasswordAuth.Outcome.OK) {
                            runOnUiThread {
                                setBusy(false)
                                if (afterRegister && result.outcome == PasswordAuth.Outcome.BAD_CREDENTIALS) {
                                    showResult(getString(R.string.link_register_taken))
                                } else {
                                    showResult(PasswordAuthMessages.loginError(this@SyncLinkActivity, result))
                                }
                            }
                            return
                        }
                        val tokens = PasswordAuth.parseTokens(body)
                        runOnUiThread {
                            setBusy(false)
                            editPassword.setText("")
                            editRegPassword.setText("")
                            editRegPassword2.setText("")
                            onLinked(tokens.accessToken, tokens.refreshToken, email)
                        }
                    }
                } catch (e: JSONException) {
                    runOnUiThread {
                        setBusy(false)
                        showResult(getString(R.string.link_bad_sign_in_response, e.message))
                    }
                }
            }
        })
    }

    // ---- Регистрация (POST /auth/register, затем вход) ----

    private fun onRegisterClicked() {
        val email = PasswordAuth.normalizeEmail(editRegEmail.text.toString())
        val password = editRegPassword.text.toString()
        val password2 = editRegPassword2.text.toString()
        val check = PasswordAuth.checkRegistration(email, password, password2)
        if (check != PasswordAuth.RegistrationCheck.OK) {
            showResult(PasswordAuthMessages.registrationCheck(this, check)) // запрос не уходит
            return
        }

        showResult(getString(R.string.link_register_creating))
        setBusy(true)

        val payload = PasswordAuth.registerBody(email, password, editRegName.text.toString())
        val request = Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/register")
                .post(payload.toString().toRequestBody(JSON))
                .build()

        HttpClients.get(this).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST /auth/register failed", e)
                runOnUiThread {
                    setBusy(false)
                    showResult(getString(R.string.link_server_unreachable,
                            AppSettings.getServerBaseUrl(this@SyncLinkActivity), NetworkErrors.describe(this@SyncLinkActivity, e)))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body?.string() ?: ""
                    val result = PasswordAuth.classify(r.code, body, r.header("Retry-After"), 202)
                    if (result.outcome != PasswordAuth.Outcome.OK) {
                        runOnUiThread {
                            setBusy(false)
                            showResult(PasswordAuthMessages.registerError(this@SyncLinkActivity, result))
                        }
                        return
                    }
                    // 202 не открывает сессию (занятый адрес неотличим от свободного) — входим теми же данными.
                    runOnUiThread {
                        setBusy(false)
                        passwordLogin(email, password, true)
                    }
                }
            }
        })
    }

    /** Блокирует кнопки на время запроса. */
    private fun setBusy(busy: Boolean) {
        buttonLogin.isEnabled = !busy
        buttonCreateAccount.isEnabled = !busy
        buttonRegister.isEnabled = !busy
    }

    /** Шаг 1: POST /auth/nonce — сервер выдаёт одноразовый nonce (см. README google-auth, "Мобильный вход"). */
    private fun fetchNonceThenSignIn() {
        showResult(getString(R.string.link_requesting_nonce, AppSettings.getServerBaseUrl(this)))

        val request = Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/nonce")
                .post(ByteArray(0).toRequestBody()) // POST без тела — nonce сервер генерирует сам
                .build()

        HttpClients.get(this).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST /auth/nonce failed", e)
                runOnUiThread { showResult(getString(R.string.link_server_unreachable,
                        AppSettings.getServerBaseUrl(this@SyncLinkActivity), NetworkErrors.describe(this@SyncLinkActivity, e))) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body?.string() ?: ""
                        if (!r.isSuccessful) {
                            runOnUiThread { showResult(getString(R.string.link_nonce_rejected, r.code, body)) }
                            return
                        }
                        val nonce = JSONObject(body).getString("nonce")
                        runOnUiThread { startCredentialManager(nonce) }
                    }
                } catch (e: JSONException) {
                    runOnUiThread { showResult(getString(R.string.link_bad_nonce_response, e.message)) }
                }
            }
        })
    }

    /** Шаг 2: Credential Manager с nonce от сервера — Google подпишет его внутрь id_token. */
    private fun startCredentialManager(nonce: String) {
        val option = GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setNonce(nonce)
                // Явный флоу входа должен предлагать любой аккаунт на
                // устройстве, а не только уже авторизовывавшие это
                // приложение (тот фильтр — для молчаливого/one-tap входа).
                .setFilterByAuthorizedAccounts(false)
                .build()

        val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

        // Выбор аккаунта — чужой экран: возврат из него не должен запирать (security.AppLock).
        AppLock.allowNextLeave()
        CredentialManager.create(this).getCredentialAsync(
                this,
                request,
                CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        handleCredential(result.credential)
                    }

                    override fun onError(e: GetCredentialException) {
                        Log.e(TAG, "getCredentialAsync failed", e)
                        showResult(getString(R.string.link_sign_in_error, e.javaClass.simpleName, e.message))
                    }
                })
    }

    private fun handleCredential(credential: Credential) {
        if (credential !is CustomCredential
                || GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL != credential.type) {
            showResult(getString(R.string.link_unexpected_credential, credential.type))
            return
        }
        // GoogleIdTokenCredential.createFrom не объявляет checked-исключение
        // в 1.1.1 (см. историю Фазы 0 в плане) — try/catch тут не нужен.
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
        exchangeIdToken(googleIdTokenCredential.idToken, googleIdTokenCredential.id)
    }

    /** Шаг 3: POST /auth/google/mobile — обмен id_token на собственную пару токенов сервера. */
    private fun exchangeIdToken(idToken: String, email: String) {
        showResult(getString(R.string.link_exchanging_token))

        // device_id — стабильный по этой установке (AppSettings, один раз
        // сгенерированный UUID), а не android_id/IMEI и т.п.: тем и хорош,
        // что не требует разрешений и не идентифицирует физическое
        // устройство за пределами этого приложения (план, п. 1.2 — сервер
        // всё равно использует его только как непрозрачный ключ сессии, а
        // не для слежки). device_name — только для показа человеку (см.
        // README google-auth, "Мобильный вход").
        val payload = JSONObject()
        try {
            payload.put("id_token", idToken)
            payload.put("device_id", AppSettings.getOrCreateDeviceId(this))
            payload.put("device_name", Build.MANUFACTURER + " " + Build.MODEL)
            payload.put("platform", "android")
        } catch (e: JSONException) {
            throw AssertionError(e) // ключи и значения — обычные строки, не может упасть
        }

        val request = Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/google/mobile")
                .post(payload.toString().toRequestBody(JSON))
                .build()

        HttpClients.get(this).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST /auth/google/mobile failed", e)
                runOnUiThread { showResult(getString(R.string.link_exchange_failed, e.message)) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body?.string() ?: ""
                        if (!r.isSuccessful) {
                            runOnUiThread { showResult(getString(R.string.link_sign_in_rejected, r.code, body)) }
                            return
                        }
                        val json = JSONObject(body)
                        val accessToken = json.getString("access_token")
                        val refreshToken = json.getString("refresh_token")
                        runOnUiThread { onLinked(accessToken, refreshToken, email) }
                    }
                } catch (e: JSONException) {
                    runOnUiThread { showResult(getString(R.string.link_bad_sign_in_response, e.message)) }
                }
            }
        })
    }

    /**
     * Связка удалась — сохраняем токены и email (для строки статуса на
     * экране «Настройки»), снимаем флаг «нужна повторная авторизация»,
     * выставленный `SyncWorker`, если он был, и возвращаемся
     * назад с небольшой паузой, чтобы результат успел мелькнуть на экране.
     *
     * Если связанный email изменился — другой Google-аккаунт почти
     * наверняка ведёт на другой леджер сервера (org/docs/TODO_mobile_sync.md,
     * Фаза 3, п. 1.3) — сбрасываем курсоры синка: старая позиция в потоках
     * чужого леджера не подходит (см. `AppSettings.resetSyncCursors`).
     * Тот же email (обычное продление после истёкшего refresh-токена) курсоры
     * не трогает.
     */
    private fun onLinked(accessToken: String, refreshToken: String, email: String) {
        val previousEmail = AppSettings.getLinkedEmail(this)
        TokenStore.saveTokens(this, accessToken, refreshToken)
        AppSettings.setLinkedEmail(this, email)
        AppSettings.setNeedsRelink(this, false)
        // Вход прошёл — адрес точно рабочий, предлагаем его в диалоге «Адрес сервера».
        AppSettings.rememberSuccessfulServerUrl(this, AppSettings.getServerBaseUrl(this))
        if (email != previousEmail) {
            AppSettings.resetSyncCursors(this)
        }
        showResult(getString(R.string.link_done, email))
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
    }

    private fun showResult(text: String) {
        Log.i(TAG, text)
        textResult.text = text
    }
}
