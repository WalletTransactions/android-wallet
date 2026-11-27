package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.AppLanguage
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.BuildConfig
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.EquivalentCurrencies
import com.example.wallettransactions.PickerSort
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.security.AppLock
import com.example.wallettransactions.security.AppLockStore
import com.example.wallettransactions.security.BiometricErrors
import com.example.wallettransactions.security.BiometricKey
import com.example.wallettransactions.reminder.ReminderWorker

import javax.crypto.Cipher

/**
 * Экран «Настройки», открывается с таба «Ещё» нижней панели (MainActivity.navMore).
 *
 * Тема оформления, валюта эквивалента и время напоминания реально сохраняются
 * (SharedPreferences) и переживают переоткрытие экрана. «Код доступа» — настоящий
 * (security.AppLock, org/docs/TODO_app_lock.md): включение, выключение и смена идут
 * через [LockActivity]. «Вход по отпечатку» — тоже (Фаза 2 плана, п. 3.4).
 * «Напоминание вносить операции» — [ReminderWorker].
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PIN_ENABLE = 1
        private const val REQUEST_PIN_DISABLE = 2
        private const val REQUEST_PIN_CHANGE_VERIFY = 3
        private const val REQUEST_PIN_CHANGE_SET = 4

        /** Тапы по строке «Версия» — [onVersionTap]. */
        private const val HIDDEN_MENU_TAPS = 5
        private const val HIDDEN_MENU_TAP_TIMEOUT_MS = 1500L

        private fun languageIndex(): Int {
            val tag = AppLanguage.current()
            for (i in AppLanguage.TAGS.indices) {
                if (AppLanguage.TAGS[i] == tag) return i
            }
            return 0
        }
    }

    private lateinit var mainCurrencyValue: TextView
    private lateinit var languageValue: TextView
    private lateinit var chipThemeLight: TextView
    private lateinit var chipThemeDark: TextView
    private lateinit var chipThemeSystem: TextView
    private lateinit var switchAccessCode: FrameLayout
    private lateinit var switchBiometric: FrameLayout
    private lateinit var labelBiometric: TextView
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var rowsPinSet: View
    private lateinit var lockTimeoutValue: TextView
    private lateinit var switchHideRecents: FrameLayout
    private lateinit var hideRecentsHint: TextView
    private lateinit var switchRemind: FrameLayout
    private lateinit var switchPickerKeyboard: FrameLayout
    private lateinit var switchSummaryOtherExpanded: FrameLayout
    private lateinit var switchCatalogPanelWallets: FrameLayout
    private lateinit var switchCatalogPanelCategories: FrameLayout
    private lateinit var switchIconUpload: FrameLayout
    private lateinit var rowIconUpload: View
    private lateinit var textIconUploadHint: TextView
    private lateinit var walletSortValue: TextView
    private lateinit var categorySortValue: TextView
    private lateinit var rowRemindTime: TextView

    private var versionTapCount = 0
    private var versionTapLastAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mainCurrencyValue = findViewById(R.id.mainCurrencyValue)
        languageValue = findViewById(R.id.languageValue)
        chipThemeLight = findViewById(R.id.chipThemeLight)
        chipThemeDark = findViewById(R.id.chipThemeDark)
        chipThemeSystem = findViewById(R.id.chipThemeSystem)
        switchAccessCode = findViewById(R.id.switchAccessCode)
        switchBiometric = findViewById(R.id.switchBiometric)
        labelBiometric = findViewById(R.id.labelBiometric)
        // В onCreate: BiometricPrompt должен пережить поворот вместе с колбэком.
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), enableBiometricCallback)
        rowsPinSet = findViewById(R.id.rowsPinSet)
        lockTimeoutValue = findViewById(R.id.lockTimeoutValue)
        switchHideRecents = findViewById(R.id.switchHideRecents)
        hideRecentsHint = findViewById(R.id.hideRecentsHint)
        switchRemind = findViewById(R.id.switchRemind)
        switchPickerKeyboard = findViewById(R.id.switchPickerKeyboard)
        switchSummaryOtherExpanded = findViewById(R.id.switchSummaryOtherExpanded)
        switchCatalogPanelWallets = findViewById(R.id.switchCatalogPanelWallets)
        switchCatalogPanelCategories = findViewById(R.id.switchCatalogPanelCategories)
        switchIconUpload = findViewById(R.id.switchIconUpload)
        rowIconUpload = findViewById(R.id.rowIconUpload)
        textIconUploadHint = findViewById(R.id.textIconUploadHint)
        walletSortValue = findViewById(R.id.walletSortValue)
        categorySortValue = findViewById(R.id.categorySortValue)
        rowRemindTime = findViewById(R.id.rowRemindTime)

        val versionValue = findViewById<TextView>(R.id.versionValue)
        try {
            versionValue.text = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (ignored: Exception) {
            // Keep the layout's placeholder if the version can't be read.
        }

        // BUILD_TIME — сгенерированное поле BuildConfig (app/build.gradle),
        // дата и время сборки в UTC, растёт вместе с версией на каждой сборке.
        val buildDateValue = findViewById<TextView>(R.id.buildDateValue)
        buildDateValue.text = BuildConfig.BUILD_TIME

        applyThemeChips(AppSettings.getThemeMode(this))
        languageValue.text = languageTitles()[languageIndex()]

        val savedIso = AppSettings.getCurrencyEqIso(this)
        val saved: Currency? = if (savedIso != null)
            Singleton.instance.getCurrencyList(this).getCurrencyByISO(savedIso) else null
        if (saved != null) mainCurrencyValue.text = saved.iso + " — " + saved.name

        paintSecurity()
        applySwitchState(switchRemind, AppSettings.isRemindOn(this))
        applySwitchState(switchPickerKeyboard, AppSettings.isPickerKeyboardOn(this))
        applySwitchState(switchSummaryOtherExpanded, AppSettings.isSummaryOtherExpanded(this))
        applySwitchState(switchCatalogPanelWallets, AppSettings.isCatalogPanelOpen(this, true))
        applySwitchState(switchCatalogPanelCategories, AppSettings.isCatalogPanelOpen(this, false))
        paintSortValues()

        paintRemindTime()
    }

    fun btnUp(view: View) {
        finish()
    }

    /** Общий обработчик трёх чипов темы — какой нажали, видно по id. */
    fun onPickTheme(view: View) {
        val id = view.id
        val mode = if (id == R.id.chipThemeLight) AppSettings.THEME_LIGHT
        else if (id == R.id.chipThemeDark) AppSettings.THEME_DARK
        else AppSettings.THEME_SYSTEM

        // Повторный тап по уже выбранному варианту ничего не меняет: пересоздавать
        // экран ради той же темы — только моргнуть им на пустом месте.
        if (mode == AppSettings.getThemeMode(this)) return

        AppSettings.setThemeMode(this, mode)
        // Своей активности смена defaultNightMode не касается — палитру подхватит
        // только новая. Остальной стек пересоздастся сам, когда всплывёт.
        recreate()
    }

    private fun applyThemeChips(mode: String?) {
        paintThemeChip(chipThemeLight, AppSettings.THEME_LIGHT == mode)
        paintThemeChip(chipThemeDark, AppSettings.THEME_DARK == mode)
        paintThemeChip(chipThemeSystem, AppSettings.THEME_SYSTEM == mode)
    }

    private fun paintThemeChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected)
            R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(this,
                if (selected) R.color.colorSurface else R.color.colorTextSecondary))
    }

    /** Язык приложения (AppLanguage): язык телефона, English, Русский. */
    fun onPickLanguage(view: View) {
        val checked = languageIndex()
        AlertDialog.Builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(languageTitles(), checked) { d, which ->
                    d.dismiss()
                    // Экраны AppCompat пересоздаются сами на новом языке.
                    if (which != checked) AppLanguage.set(AppLanguage.TAGS[which])
                }
                .show()
    }

    private fun languageTitles(): Array<String> {
        return arrayOf(getString(R.string.settings_language_system),
                getString(R.string.settings_language_en), getString(R.string.settings_language_ru))
    }

    fun onPickMainCurrency(view: View) {
        val currencyList = Singleton.instance.getCurrencyList(this)
        val currentIso = AppSettings.getCurrencyEqIso(this)
        val current: Currency? = if (currentIso != null) currencyList.getCurrencyByISO(currentIso) else null
        // Только валюты с флагом «Показывать в эквиваленте» + текущая (currency-ui-tweaks).
        val shown: List<Currency> = EquivalentCurrencies.filter(ArrayList(currencyList), current)

        val items = arrayOfNulls<String>(shown.size)
        var checked = -1
        for (i in shown.indices) {
            val c = shown[i]
            items[i] = c.iso + " — " + c.name
            if (current != null && c.id == current.id) checked = i
        }

        AlertDialog.Builder(this)
                .setTitle(R.string.settings_currency_eq)
                .setSingleChoiceItems(items, checked) { d, which ->
                    val chosen = shown[which]
                    mainCurrencyValue.text = chosen.iso + " — " + chosen.name
                    AppSettings.setCurrencyEqIso(this, chosen.iso)
                    d.dismiss()
                }
                .show()
    }

    /** Импорт CSV/JSON (счета, операции, курсы валют) — см. [ImportActivity].
     *  До 2026-11-11 пункт стоял в «Ещё». */
    fun onImportFile(view: View) {
        startActivity(Intent(this, ImportActivity::class.java))
    }

    /** Экспорт базы (файл .db) или JSON — см. [ExportActivity]. */
    fun onExportDatabase(view: View) {
        startActivity(Intent(this, ExportActivity::class.java))
    }

    /**
     * Пять тапов подряд по строке «Версия» (с паузой не больше
     * [HIDDEN_MENU_TAP_TIMEOUT_MS] между тапами) открывают экран
     * «Удалить данные» ([ResetDataActivity]) — раньше он был виден
     * прямо в «Ещё», теперь спрятан за этот жест, чтобы не подставлять
     * необратимое действие под случайный тап.
     */
    fun onVersionTap(view: View) {
        val now = System.currentTimeMillis()
        if (now - versionTapLastAt > HIDDEN_MENU_TAP_TIMEOUT_MS) versionTapCount = 0
        versionTapLastAt = now
        versionTapCount++

        if (versionTapCount < HIDDEN_MENU_TAPS) {
            val left = HIDDEN_MENU_TAPS - versionTapCount
            if (versionTapCount >= 3) {
                Toast.makeText(this, resources.getQuantityString(R.plurals.settings_taps_left, left, left),
                        Toast.LENGTH_SHORT).show()
            }
            return
        }

        versionTapCount = 0
        startActivity(Intent(this, ResetDataActivity::class.java))
    }


    // ── Код доступа ───────────────────────────────────────────────────────────

    /** Включение — придумать код; выключение — сначала текущий код. Тумблер меняет только результат. */
    @Suppress("DEPRECATION")
    fun onToggleAccessCode(view: View) {
        if (AppLockStore.isPinSet(this)) {
            startActivityForResult(LockActivity.verifyIntent(this), REQUEST_PIN_DISABLE)
        } else {
            startActivityForResult(LockActivity.setIntent(this), REQUEST_PIN_ENABLE)
        }
    }

    @Suppress("DEPRECATION")
    fun onChangeAccessCode(view: View) {
        startActivityForResult(LockActivity.verifyIntent(this), REQUEST_PIN_CHANGE_VERIFY)
    }

    fun onPickLockTimeout(view: View) {
        val current = AppLockStore.getTimeoutMs(this)
        var checked = 1
        for (i in AppLockStore.TIMEOUTS_MS.indices) {
            if (AppLockStore.TIMEOUTS_MS[i] == current) checked = i
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.settings_lock_after)
                .setSingleChoiceItems(AppLockStore.timeoutTitles(this), checked) { d, which ->
                    AppLockStore.setTimeoutMs(this, AppLockStore.TIMEOUTS_MS[which])
                    paintSecurity()
                    d.dismiss()
                }
                .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PIN_ENABLE ->
                Toast.makeText(this, R.string.settings_pin_on, Toast.LENGTH_SHORT).show()
            REQUEST_PIN_DISABLE -> {
                AppLockStore.clearPin(this)
                AppLock.unlock()
                Toast.makeText(this, R.string.settings_pin_off, Toast.LENGTH_SHORT).show()
            }
            REQUEST_PIN_CHANGE_VERIFY -> {
                // Старый код остаётся, пока новый не подтверждён: отмена на любом шаге ничего не меняет.
                startActivityForResult(LockActivity.setIntent(this), REQUEST_PIN_CHANGE_SET)
                return
            }
            REQUEST_PIN_CHANGE_SET ->
                Toast.makeText(this, R.string.settings_pin_changed, Toast.LENGTH_SHORT).show()
            else -> return
        }
        paintSecurity()
    }

    /** Отпечатки могли поменять в настройках телефона, пока этот экран был в фоне. */
    override fun onResume() {
        super.onResume()
        paintSecurity()
        // Здесь, а не в onCreate: фоновый синк мог выключить отправку отказом сервера.
        paintIconUpload()
    }

    /**
     * «Отправлять свои иконки на сервер» (openspec optional-icon-upload). Пока сервер не
     * разрешил приём (последний pull без `capabilities.icon_upload = true`),
     * переключатель неактивен и показывает выключенное состояние с пояснением.
     */
    private fun paintIconUpload() {
        val allowed = AppSettings.isServerIconUploadAllowed(this)
        applySwitchState(switchIconUpload, allowed && AppSettings.isIconUploadEnabled(this))
        switchIconUpload.isEnabled = allowed
        rowIconUpload.alpha = if (allowed) 1f else 0.5f
        textIconUploadHint.setText(if (allowed) R.string.settings_icon_upload_hint else R.string.settings_icon_upload_server_off)
    }

    /** Включение отправит и уже накопленные свои иконки — при ближайшем проходе синка. */
    fun onToggleIconUpload(view: View) {
        if (!AppSettings.isServerIconUploadAllowed(this)) return
        AppSettings.setIconUploadEnabled(this, toggleSwitch(switchIconUpload))
    }

    private fun paintSecurity() {
        val pinSet = AppLockStore.isPinSet(this)
        applySwitchState(switchAccessCode, pinSet)
        rowsPinSet.visibility = if (pinSet) View.VISIBLE else View.GONE
        lockTimeoutValue.text = AppLockStore.getTimeoutTitle(this)
        applySwitchState(switchHideRecents, AppLockStore.getHideInRecentsSetting(this))
        hideRecentsHint.setText(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            R.string.settings_hide_recents_hint
        else
            R.string.settings_hide_recents_hint_screenshots)

        // Ключ инвалидирован (отпечатки в телефоне изменились) — тумблер не должен врать.
        if (AppLockStore.isBiometricOn(this) && BiometricKey.cipherOrNull() == null) {
            AppLockStore.setBiometricOn(this, false)
        }
        val available = BiometricErrors.unavailableReason(this, pinSet, canAuthenticate()) == null
        applySwitchState(switchBiometric, AppLockStore.isBiometricOn(this))
        labelBiometric.setTextColor(ContextCompat.getColor(this,
                if (available) R.color.colorTextPrimary else R.color.colorTextHint))
    }

    fun onToggleHideRecents(view: View) {
        AppLockStore.setHideInRecents(this, toggleSwitch(switchHideRecents))
        // Этому экрану — сразу; остальные подхватят на своём onResume (AppLock).
        AppLock.applyRecentsPrivacy(this)
    }

    private fun canAuthenticate(): Int {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }

    /**
     * Выключение — сразу и без кода (удобства меньше, защиты не меньше). Включение —
     * новый ключ Keystore и один успешный отпечаток; недоступен — объяснить почему.
     */
    fun onToggleBiometric(view: View) {
        if (AppLockStore.isBiometricOn(this)) {
            AppLockStore.setBiometricOn(this, false)
            paintSecurity()
            return
        }
        val pinSet = AppLockStore.isPinSet(this)
        val can = canAuthenticate()
        val reason = BiometricErrors.unavailableReason(this, pinSet, can)
        if (reason != null) {
            if (BiometricErrors.canEnroll(pinSet, can)) {
                AlertDialog.Builder(this)
                        .setMessage(reason)
                        .setPositiveButton(R.string.settings_open_phone_settings) { _, _ -> openBiometricEnroll() }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
            } else {
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val cipher: Cipher? = try {
            BiometricKey.create()
            BiometricKey.cipherOrNull()
        } catch (e: Exception) {
            null
        }
        if (cipher == null) {
            Toast.makeText(this, R.string.settings_biometric_prepare_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_biometric_title))
                .setSubtitle(getString(R.string.settings_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private val enableBiometricCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val crypto = result.cryptoObject
            if (BiometricKey.confirm(crypto?.cipher)) {
                AppLockStore.setBiometricOn(this@SettingsActivity, true)
                Toast.makeText(this@SettingsActivity, R.string.settings_biometric_on, Toast.LENGTH_SHORT).show()
            } else {
                BiometricKey.delete()
            }
            paintSecurity()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            BiometricKey.delete()
            paintSecurity()
        }
    }

    /** Системный экран добавления отпечатка; возврат оттуда не запирает приложение. */
    private fun openBiometricEnroll() {
        val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                            BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        AppLock.allowNextLeave()
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    // ── Напоминание ───────────────────────────────────────────────────────────

    /**
     * Само напоминание — reminder.ReminderWorker. Разрешение на уведомления (Android 13+)
     * спрашиваем здесь, когда его включают, — как у «Уведомлять о новых операциях».
     */
    fun onToggleRemind(view: View) {
        val on = toggleSwitch(switchRemind)
        AppSettings.setRemindOn(this, on)
        ReminderWorker.reschedule(this)

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun onTogglePickerKeyboard(view: View) {
        AppSettings.setPickerKeyboardOn(this, toggleSwitch(switchPickerKeyboard))
    }

    /** Ревизию не поднимаем: настройку читает экран при открытии, уже раскрытую вручную
     *  карточку на открытых табах она не переворачивает (design D3). */
    fun onToggleSummaryOtherExpanded(view: View) {
        AppSettings.setSummaryOtherExpanded(this, toggleSwitch(switchSummaryOtherExpanded))
    }

    /** Исходное состояние панели «Фильтры» справочника: читается при его открытии
     *  (openspec catalog-filters-panel); кнопка на самом экране сюда не пишет. */
    fun onToggleCatalogPanelWallets(view: View) {
        AppSettings.setCatalogPanelOpen(this, true, toggleSwitch(switchCatalogPanelWallets))
    }

    fun onToggleCatalogPanelCategories(view: View) {
        AppSettings.setCatalogPanelOpen(this, false, toggleSwitch(switchCatalogPanelCategories))
    }

    fun onPickWalletSort(view: View) {
        pickSort(true, getString(R.string.settings_sort_wallets))
    }

    fun onPickCategorySort(view: View) {
        pickSort(false, getString(R.string.settings_sort_categories))
    }

    private fun pickSort(wallets: Boolean, title: String) {
        val modes = if (wallets) PickerSort.WALLET_MODES else PickerSort.CATEGORY_MODES
        val checked = PickerSort.indexOf(modes, AppSettings.getPickerSort(this, wallets))

        AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(PickerSort.titles(this, modes), checked) { d, which ->
                    AppSettings.setPickerSort(this, wallets, modes[which])
                    paintSortValues()
                    d.dismiss()
                }
                .show()
    }

    private fun paintSortValues() {
        walletSortValue.text = AppSettings.getPickerSort(this, true).getTitle(this)
        categorySortValue.text = AppSettings.getPickerSort(this, false).getTitle(this)
    }

    fun onPickRemindTime(view: View) {
        TimePickerDialog(this, { _, h, m ->
            AppSettings.setRemindTime(this, h, m)
            paintRemindTime()
            // Выключенное напоминание reschedule не заведёт — только запомним время.
            ReminderWorker.reschedule(this)
        }, AppSettings.getRemindHour(this), AppSettings.getRemindMinute(this), true).show()
    }

    private fun paintRemindTime() {
        rowRemindTime.text = getString(R.string.settings_remind_time,
                AppSettings.getRemindHour(this), AppSettings.getRemindMinute(this))
    }

    fun onRateApp(view: View) {
        val pkg = packageName
        AppLock.allowNextLeave()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
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
