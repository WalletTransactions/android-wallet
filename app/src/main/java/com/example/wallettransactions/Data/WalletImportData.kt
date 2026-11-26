package com.example.wallettransactions.Data

import com.example.wallettransactions.R
import com.example.wallettransactions.Texts

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.Singleton

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

object WalletImportData {

    private var format: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")

    /** Итог импорта: строки, не попавшие в БД, раньше терялись — счётчик показывал только успехи. */
    class ImportResult {
        @JvmField var imported: Int = 0
        @JvmField val skipped: MutableList<String> = ArrayList()

        /** @param countPlural что считаем — `<plurals>` «Импортировано N операций» и т.п. */
        fun describe(countPlural: Int): String {
            val sb = StringBuilder(Texts.plural(countPlural, imported))

            if (skipped.isEmpty())
                return sb.toString()

            sb.append(Texts.get(R.string.import_skipped, skipped.size)).append("\n")
            for (i in 0 until Math.min(skipped.size, MAX_REPORTED)) 
                sb.append("• ").append(skipped.get(i)).append("\n")
            if (skipped.size > MAX_REPORTED)
                sb.append(Texts.get(R.string.import_and_more, skipped.size - MAX_REPORTED))

            return sb.toString()
        }

        companion object {

            private const val MAX_REPORTED = 10
        }
    }

    /** Strips UTF-8 BOM (﻿) and trims whitespace. */
    private fun normalizeHeader(line: String?): String? {
        var line = line
        if (line == null) return null
        if (line.startsWith("﻿")) line = line.substring(1)
        return line.trim { it <= ' ' }
    }

    private fun headerMismatchMessage(expected: String, actual: String?): String {
        val sb = StringBuilder()
        sb.append(Texts.get(R.string.import_header_wrong)).append("\n")
        sb.append(Texts.get(R.string.import_header_expected, expected)).append("\n")
        sb.append(Texts.get(R.string.import_header_actual, actual)).append("\n")
        if (actual != null && !actual.isEmpty()) {
            val first: Char = actual[0]
            if (first != expected[0]) {
                sb.append(Texts.get(R.string.import_header_first_char, first.toInt(), first.toInt())).append("\n")
            }
            // Detect semicolon delimiter
            if (actual.contains(";") && !actual.contains(",")) {
                sb.append(Texts.get(R.string.import_header_semicolon))
            }
        }
        return sb.toString()
    }


    /**
     * `_id` счёта по имени или null — источник правды здесь только БД, не кэш.
     * deleted_at IS NULL — мягко удалённый счёт для импорта не существует: имя
     * теперь можно переиспользовать (см. частичный индекс uq_account_name), и
     * импорт обязан завести НОВУЮ строку, а не тихо "воскресить" старую правкой.
     */
    private fun FindAccountIdByName(db: SQLiteDatabase, name: String): Int? {
        val c = db.rawQuery("select " + WalletContract.AccountEntry._ID +
                " from " + WalletContract.AccountEntry.TABLE_NAME +
                " where " + AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME) +
                " and " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " is null", arrayOf<String>(name))
        try {
            return if (c.moveToFirst()) c.getInt(0) else null
        } finally {
            c.close()
        }
    }

    private fun InsertOperationRecord(db: SQLiteDatabase, date: Date, accFrom: String, accTo: String, amount: Double, comment: String): Int {
        // uuid — v72 (org/docs/TODO_mobile_sync.md, Фаза 3): эта вставка работает на
        // уже существующей (возможно, апгрейженной) базе, где у колонки uuid нет
        // DEFAULT-выражения (SQLite не разрешает его в ALTER TABLE ADD COLUMN) —
        // значение обязано прийти явно.
        val sql = "insert into operation(date, acc_fr_id, acc_to_id, amount,  comment, uuid)	select dt, af._id , act._id , amount, t.comment, t.uuid from (" +
                "select	'"+ DateUtils.DateToStr(date)+"' dt, ? acc_fr, ? acc_to, ? amount,  ? comment, ? uuid	) t join account af on " + AccountNames.keySql("af.name") + " = " + AccountNames.keySql("acc_fr") + " and af.deleted_at is null" +
                " join account act on " + AccountNames.keySql("act.name") + " = " + AccountNames.keySql("acc_to") + " and act.deleted_at is null"


        db.execSQL(sql, arrayOf<String>(accFrom, accTo, amount.toString(), comment, UUID.randomUUID().toString()))
        val res = db.rawQuery("select changes() 'affected_rows'", null)
        res.moveToFirst()
        return res.getInt(res.getColumnIndex("affected_rows"))
    }

    /** @return null, если строка вставлена, иначе причина пропуска для отчёта пользователю. */
    @Throws(ParseException::class)
    private fun ParseAndInsertOperation(db: SQLiteDatabase, line: String): String? {
        var comment: String? = null

        val vals: Array<String> = java.util.regex.Pattern.compile(",").split(line)
        val dt = format.parse(vals[0])
        val accFrom = vals[1]
        val accTo = vals[2]
        val amount: Double = java.lang.Double.parseDouble(vals[3])
        if (vals.size < 5)
            comment = ""
        else
            comment = vals[4]

        if (InsertOperationRecord(db, dt, accFrom, accTo, amount, comment) > 0)
            return null

        // INSERT ... JOIN account молча вставляет 0 строк, если счёта нет в справочнике,
        // поэтому недостающее имя ищем сами — иначе пользователь видит только итоговый ноль.
        val missing = StringBuilder()
        if (FindAccountIdByName(db, accFrom) == null)
            missing.append(accFrom)
        if (FindAccountIdByName(db, accTo) == null) {
            if (missing.length > 0) missing.append(", ")
            missing.append(accTo)
        }

        if (missing.length == 0)
            return Texts.get(R.string.import_row_not_inserted, line)

        return Texts.get(R.string.import_row_no_account, line, missing)
    }



    /**
     * Бэкфилл `system_kind` по имени — см. вызов в [ParseAndInsertAccount] для
     * объяснения, почему это легитимно здесь. Идемпотентно: на уже корректной строке просто
     * перезапишет то же самое значение.
     */
    private fun stampIfSystemCategory(db: SQLiteDatabase, accName: String?) {
        var kind: AccountItem.SystemKind? = null
        if (AccountItem.SYSTEM_TRANSFER_NAME.equals(if (accName == null) null else accName.trim { it <= ' ' }, ignoreCase = true))
            kind = AccountItem.SystemKind.TRANSFER
        else if (AccountItem.SYSTEM_COMMISSION_NAME.equals(if (accName == null) null else accName.trim { it <= ' ' }, ignoreCase = true))
            kind = AccountItem.SystemKind.TRANSFER_COMMISSION
        else
            return

        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND, kind.value)
        db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                WalletContract.AccountEntry.COLUMN_NAME + " = ? COLLATE NOCASE", arrayOf(accName))
    }

    /**
     * Символ новой валюты из импорта: формат его не несёт, а `currency.symbol` NOT NULL
     * (android-wallet#54). Берём родной символ из [java.util.Currency] — по локалям
     * стран, где валюта своя (RUB → ₽, UAH → ₴, PLN → zł, USD → $); из нескольких вариантов
     * самый короткий. Неизвестный код или валюта без символа — сам код ISO.
     */
    @JvmStatic
    fun currencySymbolFor(iso: String?): String {
        if (iso == null || iso.trim { it <= ' ' }.isEmpty()) return ""
        val code = iso.trim { it <= ' ' }.toUpperCase(java.util.Locale.ROOT)
        var cur: java.util.Currency? = null
        try {
            cur = java.util.Currency.getInstance(code)
        } catch (e: IllegalArgumentException) {
            return iso    // не код ISO 4217 — своя валюта
        }
        var best: String? = null
        for (l in java.util.Locale.getAvailableLocales()) {
            if (l.getCountry().isEmpty()) continue
            var own: java.util.Currency? = null
            try {
                own = java.util.Currency.getInstance(l)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (!cur.equals(own)) continue
            val s = cur.getSymbol(l)
            if (s == null || s.isEmpty() || s.equals(code)) continue
            if (best == null || s.length < best.length
                    || (s.length == best.length && s.compareTo(best) < 0)) best = s
        }
        return if (best != null) best else iso
    }

    /** Число знаков после запятой по ISO 4217 (JPY — 0), иначе 2. */
    @JvmStatic
    fun currencyPrecisionFor(iso: String): Int {
        try {
            val d = java.util.Currency.getInstance(iso.trim { it <= ' ' }.toUpperCase(java.util.Locale.ROOT))
                    .getDefaultFractionDigits()
            return if (d >= 0 && d <= 4) d else 2
        } catch (e: IllegalArgumentException) {
            return 2
        }
    }

    /** Есть ли среди живых валюта с этим кодом (удалённая не считается, android-wallet#54). */
    @JvmStatic
    fun liveCurrencyExists(db: SQLiteDatabase, iso: String): Boolean {
        val c = db.rawQuery("select 1 from currency where iso_code = ? and deleted_at is null limit 1",
                arrayOf<String>(iso))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /**
     * Живая иконка с этим именем, иначе живая `unknown` (android-wallet#50). Ищем в
     * базе, а не в кэше [IconList]: кэш мог пережить удаление иконок. Раньше не
     * найденная иконка давала `icon_id = 1` — а это могла быть и удалённая иконка
     * (22 счёта владельца на удалённой `Groceries`).
     */
    @JvmStatic
    fun LiveIconIdOrUnknown(db: SQLiteDatabase, icons: IconList?, iconName: String?): Long {
        var id: Long = if (iconName == null || iconName.isEmpty()) -1L else WalletFillData.GetIconIdByName(db, iconName)
        if (id < 0) id = WalletFillData.GetIconIdByName(db, "unknown")
        // Последний шанс — заглушка из кэша: Singleton.loadIconList заводит её в базе сам.
        if (id < 0 && icons != null && icons.unknownIcon != null) id = icons.unknownIcon!!.id.toLong()
        return id
    }

    /** Текущий флаг архива счёта: UpdateAccount пишет его всегда, поэтому «не трогать» = перечитать. */
    private fun isAccountArchived(db: SQLiteDatabase, accId: Int): Boolean {
        val c = db.rawQuery("select " + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " from " +
                WalletContract.AccountEntry.TABLE_NAME + " where " + WalletContract.AccountEntry._ID + "=?",
                arrayOf<String>(accId.toString()))
        try {
            return c.moveToFirst() && c.getInt(0) != 0
        } finally {
            c.close()
        }
    }

    /**
     * Общее ядро для CSV- и JSON-путей импорта счёта (см. [ParseAndInsertAccount]
     * и [ParseAndInsertJsonAccount]) — раньше было продублировано, теперь один
     * источник правды для вставки счёта + (опционально) его валюты.
     *
     * @param curName валюта не задана (счёт-категория, без своего баланса) — `null`
     *                или пустая строка; curIso/curIcon в этом случае не читаются.
     */
    private fun InsertOrUpdateAccount(db: SQLiteDatabase, icons: IconList?, accName: String, accComment: String, iconName: String, curName: String?, curIso: String?, curIcon: String): Int {
        return InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon, null)
    }

    /**
     * То же с видом категории (openspec category-kind-income-expense). `categoryKind == null` —
     * вид не трогаем: у новой категории остаётся умолчание «Оба», у существующей — прежний вид.
     * Передаётся только для обычной категории: у кошелька и системной категории вид всегда «Оба».
     */
    private fun InsertOrUpdateAccount(db: SQLiteDatabase, icons: IconList?, accName: String, accComment: String, iconName: String, curName: String?, curIso: String?, curIcon: String, categoryKind: AccountItem.CategoryKind?): Int {
        return InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon,
                categoryKind, null)
    }

    /**
     * То же с флагом архива. `archived == null` — архивность не трогаем: у нового счёта она
     * «нет», у существующего остаётся прежней (раньше импорт молча разархивировал счёт).
     */
    private fun InsertOrUpdateAccount(db: SQLiteDatabase, icons: IconList?, accName: String, accComment: String, iconName: String, curName: String?, curIso: String?, curIcon: String, categoryKind: AccountItem.CategoryKind?, archived: Boolean?): Int {
        var added: Boolean

        // Ищем счёт в самой БД, а не в кэше AccountList: кэш может держать удалённые
        // счета («Ещё → Удалить все операции»), и тогда UPDATE уходил по мёртвому id,
        // INSERT не выполнялся, а импорт возвращал 0 без единой ошибки.
        val accId: Int? = FindAccountIdByName(db, accName)
        val iconId = LiveIconIdOrUnknown(db, icons, iconName).toInt()

        if (accId != null) {
            val isArchived = archived ?: isAccountArchived(db, accId)
            added = WalletDbCRUD.UpdateAccount(db, accId, accName, accComment, iconId, isArchived, categoryKind)>0
        } else
            added = WalletDbCRUD.InsertAccount(db, accName, accComment, iconId, archived != null && archived, if (categoryKind == null) AccountItem.CategoryKind.BOTH else categoryKind)>0

        // Формат этого импорта не несёт system_kind — если строка "Transfer"/
        // "Transfer commission" только что заведена заново (например, восстановление
        // на базе, где системная категория почему-то отсутствовала), WalletDbCRUD.
        // InsertAccount оставит её обычной (system_kind=0), и весь код перевода
        // перестанет находить её как системную.
        // ТО ЖЕ САМОЕ, что бэкфилл WalletDbHelper v75 / WalletFillData.stampSystemCategories —
        // третье (и последнее) легитимное место сравнения по имени.
        stampIfSystemCategory(db, accName)

        var sql: String? = null

        if (curName != null && !curName.isEmpty()) {
            // Иконка валюты — тем же путём, что у счёта: только живая (раньше LEFT JOIN icon
            // без deleted_at и COALESCE(i._id, 1) — см. LiveIconIdOrUnknown).
            val curIconId: Long = LiveIconIdOrUnknown(db, icons, curIcon)
            // Валюта «уже есть» — только среди живых: удалённая с тем же кодом не мешает
            // завести новую (android-wallet#54). Формат не несёт символа, а currency.symbol
            // NOT NULL, поэтому берём родной символ валюты (код ISO — запасной), точность по
            // ISO 4217; «в эквиваленте» — по умолчанию.
            if (!liveCurrencyExists(db, curIso!!)) {
                val curId: Long = WalletDbCRUD.InsertCurrency(db, curName, curIso,
                        currencySymbolFor(curIso), currencyPrecisionFor(curIso), curIconId.toInt(), true)
                if (!added) added = curId > 0
            }

            // uuid — a.uuid (ТОТ ЖЕ, что у самого account), не свежий случайный:
            // wallet-api/schema/table/account_operating.sql требует
            // account_operating.uuid = account.uuid того же счёта (общий
            // первичный ключ на уровне синка) — иначе push такого кошелька
            // упал бы FK-ошибкой fk_account_operating_account на сервере.
            sql = "insert into account_operating (_id , currency_id, uuid) " +
                    "select a._id, c._id, a.uuid from (\n" +
                    "select ? name, ? cur_name) t\n" +
                    "join account a\n" +
                    "on a.name  = t.name\n" +
                    "join currency c\n" +
                    "on c.iso_code = t.cur_name and c.deleted_at is null\n" +
                    "where not exists (select 1 from account_operating ao where ao._id = a._id and ao.currency_id=c._id)"
            db.execSQL(sql, arrayOf<String>(accName, curIso))

            if (!added)
                added = WalletFillData.GetAffectedRowsCount(db) > 0

            // WalletDbCRUD.InsertAccount выше всегда заводит строку как категорию
            // (kind по умолчанию 0) — в кошелёк её превращает именно вставка в
            // account_operating чуть выше, см. javadoc AccountItem.Kind. ТО ЖЕ САМОЕ,
            // что stampIfSystemCategory: формат этого импорта не несёт kind, так что
            // его выводим из факта наличия валюты в самой записи.
            val cvKind = ContentValues()
            cvKind.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.value)
            db.update(WalletContract.AccountEntry.TABLE_NAME, cvKind,
                    AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME), arrayOf<String>(accName))
        }

        return if (added) 1 else 0
    }

    @Throws(ParseException::class)
    private fun ParseAndInsertAccount(db: SQLiteDatabase, icons: IconList, line: String): Int {
        val vals: Array<String> = java.util.regex.Pattern.compile(",").split(line)

        val accName = vals[0]
        val accComment = vals[1]
        val iconName = vals[2]
        val curName: String? = if (vals.size > 3) vals[3] else null
        val curIso: String? = if ((curName != null && !curName.isEmpty())) vals[4] else null
        val curIcon: String = if (vals.size > 5) vals[5] else ""

        return InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon)
    }

    /**
     * JSON-эквивалент [ParseAndInsertAccount] — см. [FillOperationDataFromJson].
     * Ожидаемый элемент массива "accounts":
     * `{"name": "...", "comment": "...", "icon": "...", "currency": {"name": "...", "iso": "...", "icon": "..."`,
     * "debt": {"counterparty": "...", "comment": "...", "due_date": "yyyy-MM-dd"},
     * "asset": {"type": "real_estate|vehicle|other", "include_in_net_worth": true, "comment": "..."},
     * "system": "revaluation", "category_kind": 0|1|2, "archived": bool, "icon_image": "<base64 PNG/JPEG/WEBP>"}}
     * "icon_image" — своя картинка счёта (имя иконки — "icon", без него имя счёта); любой размер
     * приводится к 256×256, см. [decodeIconImage]. "comment"/"icon" опциональны (по умолчанию — имя счёта и "unknown"); "currency" опциональна —
     * без неё это счёт-категория без своего баланса, как строка без валюты в CSV. "debt"/"asset" —
     * только у кошелька и взаимоисключающие; нарушение — JSONException до любой записи, так что
     * элемент пропускается целиком (см. openspec json-debts-assets-format).
     */
    @Throws(JSONException::class)
    private fun ParseAndInsertJsonAccount(db: SQLiteDatabase, icons: IconList?, obj: JSONObject): Int {
        val accName = obj.getString("name")
        val accComment = obj.optString("comment", accName)
        var iconName = obj.optString("icon", "unknown")

        // Своя картинка счёта. Битая картинка счёт НЕ пропускает: iconImage остаётся null, и иконка
        // выбирается как раньше — по имени "icon", а не нашлась — "unknown". Имя новой иконки — "icon",
        // а без него (или при заглушке "unknown", которую нельзя перезаписывать) — имя самого счёта.
        val iconImage: ByteArray? = decodeIconImage(obj.optString("icon_image", ""))
        if (iconImage != null && (iconName.isEmpty() || "unknown".equals(iconName))) iconName = accName

        val currency = obj.optJSONObject("currency")
        var curName: String? = null
        var curIso: String? = null
        var curIcon = ""
        if (currency != null) {
            // порядок важен: curName должно стать непустым (иначе InsertOrUpdateAccount
            // решит, что валюты нет вовсе), а по умолчанию у валюты обычно и есть только iso
            curIso = currency.getString("iso")
            curName = currency.optString("name", curIso)
            curIcon = currency.optString("icon", "")
        }

        val debt = obj.optJSONObject("debt")
        val asset = obj.optJSONObject("asset")
        val system = obj.optString("system", "")
        // Вид категории (category-kind): необязательный, вне 0..2 — «Оба». Только у обычной
        // категории: кошелёк (есть currency) и системные (revaluation, Transfer, комиссия) вида не
        // имеют. Нет поля — null: вид не трогаем (новая категория — «Оба», существующая — прежний).
        var categoryKind: AccountItem.CategoryKind? = null
        if (obj.has("category_kind") && currency == null && system.isEmpty()
                && !AccountItem.isSystemName(accName)) {
            categoryKind = AccountItem.CategoryKind.fromValue(obj.optInt("category_kind", 2))
        }
        // Архив: нет поля — не трогаем (null), иначе true/false. Как category_kind, только для самого счёта.
        val archived: Boolean? = if (obj.has("archived")) obj.optBoolean("archived", false) else null
        var dueDate: String? = null
        var assetType = 0
        if (debt != null || asset != null) {
            // Всё проверяем до записи: пропущенный элемент не должен оставлять полусозданный счёт.
            if (debt != null && asset != null)
                throw JSONException("debt and asset are mutually exclusive") // not UI: причина пропуска
            if (currency == null)
                throw JSONException("debt/asset need a wallet (currency)") // not UI
            val existing: Int? = FindAccountIdByName(db, accName)
            if (debt != null) {
                if (debt.getString("counterparty").trim { it <= ' ' }.isEmpty())
                    throw JSONException("debt.counterparty is empty") // not UI
                dueDate = parseDueDate(debt.optString("due_date", ""))
                if (existing != null && WalletDbCRUD.isAssetWallet(db, existing))
                    throw JSONException("wallet is already an asset") // not UI
            } else {
                assetType = assetTypeFromName(asset.getString("type"))
                if (existing != null && WalletDbCRUD.IsDebtWallet(db, existing))
                    throw JSONException("wallet is already a debt") // not UI
            }
        }

        if (iconImage != null) putOwnIcon(db, iconName, iconImage)

        var result: Int
        if ("revaluation".equals(system) && currency == null && revaluationCategoryExists(db)) {
            // Системная «Переоценка» уже есть (завели сами или пришла синком) — второй не заводим.
            result = 0
        } else {
            result = InsertOrUpdateAccount(db, icons, accName, accComment, iconName, curName, curIso, curIcon,
                    categoryKind, archived)
            if ("revaluation".equals(system) && currency == null) stampRevaluation(db, accName)
        }

        if (debt != null) {
            val cpName = debt.getString("counterparty")
            var cpId = WalletDbCRUD.GetCounterpartyIdByName(db, cpName)
            if (cpId <= 0) cpId = WalletDbCRUD.CreateCounterparty(db, cpName, "")
            val accId: Int? = FindAccountIdByName(db, accName)
            if (cpId <= 0 || accId == null || !WalletDbCRUD.SetAccountDebt(db, accId,
                    WalletDbCRUD.CounterpartyUuid(db, cpId), debt.optString("comment", ""), dueDate))
                throw JSONException("could not mark wallet as debt") // not UI
        } else if (asset != null) {
            val accId: Int? = FindAccountIdByName(db, accName)
            if (accId == null || !WalletDbCRUD.SetAccountAsset(db, accId, assetType,
                    asset.optBoolean("include_in_net_worth", true)))
                throw JSONException("could not mark wallet as asset") // not UI
            val cv = ContentValues()
            cv.put(WalletContract.AccountAssetEntry.COLUMN_COMMENT, asset.optString("comment", ""))
            db.update(WalletContract.AccountAssetEntry.TABLE_NAME, cv,
                    WalletContract.AccountAssetEntry._ID + "=?", arrayOf<String>(accId.toString()))
        }
        return result
    }

    /** Сторона хранимой иконки в пикселях — как в IconEditDialog (самое крупное место показа + запас). */
    const val ICON_SIZE_PX = 256
    /** Потолок base64-строки картинки: файл на сотни КБ — это фото, а не иконка. */
    private const val MAX_ICON_BASE64_CHARS = 1_500_000

    /**
     * Поле `icon_image`: base64 картинки (PNG/JPEG/WEBP, допустим префикс `data:...;base64,`).
     * Пусто — `null` (картинки нет). Любой размер приводится к {@value #ICON_SIZE_PX}×{@value #ICON_SIZE_PX}:
     * не квадрат — обрезается по центру, затем масштабируется; результат кодируется WEBP, как иконки
     * из редактора. Битое (не base64, не картинка, слишком большое) — тоже `null`, без ошибки:
     * счёт импортируется со своей иконкой по имени или с `unknown`, как до появления поля.
     */
    @JvmStatic
    fun decodeIconImage(value: String?): ByteArray? {
        if (value == null) return null
        var b64 = value.trim { it <= ' ' }
        if (b64.isEmpty()) return null
        val comma = b64.indexOf(',')
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1)
        if (b64.length > MAX_ICON_BASE64_CHARS) return null
        try {
            val raw: ByteArray = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val src = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null
            val side = Math.min(src.getWidth(), src.getHeight())
            val square = android.graphics.Bitmap.createBitmap(src,
                    (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side)
            val fitted: android.graphics.Bitmap = if (side == ICON_SIZE_PX) square else android.graphics.Bitmap.createScaledBitmap(square, ICON_SIZE_PX, ICON_SIZE_PX, true)
            val out = ByteArrayOutputStream()
            if (!fitted.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, out)) return null
            return out.toByteArray()
        } catch (e: IllegalArgumentException) {
            // не base64 / картинка не помещается в память — как нечитаемая
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /**
     * Заводит свою иконку `name` (origin=local, тип «категория»). Иконка с таким именем уже есть:
     * своя — получает новую картинку, серверная (только просмотр, правит администратор) остаётся
     * как есть, и счёт просто на неё ссылается.
     */
    private fun putOwnIcon(db: SQLiteDatabase, name: String, image: ByteArray) {
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry._ID + ", " + WalletContract.IconEntry.COLUMN_ORIGIN +
                " FROM " + WalletContract.IconEntry.TABLE_NAME + " WHERE " + WalletContract.IconEntry.COLUMN_NAME +
                " = ? AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(name))
        try {
            val cv = ContentValues()
            cv.put(WalletContract.IconEntry.COLUMN_IMAGE, image)
            if (c.moveToFirst()) {
                if (c.getInt(1) == WalletContract.IconEntry.ORIGIN_LOCAL)
                    db.update(WalletContract.IconEntry.TABLE_NAME, cv,
                            WalletContract.IconEntry._ID + "=?", arrayOf<String>(c.getLong(0).toString()))
                return
            }
            cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
            cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 0)
            cv.put(WalletContract.IconEntry.COLUMN_UUID, UUID.randomUUID().toString())
            cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL)
            db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)
        } finally {
            c.close()
        }
    }

    /** `real_estate|vehicle|other` → тип `account_asset`; иное — JSONException. */
    @JvmStatic
    @Throws(JSONException::class)
    fun assetTypeFromName(name: String): Int {
        when (name) {
            "real_estate" -> return WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE
            "vehicle" -> return WalletContract.AccountAssetEntry.TYPE_VEHICLE
            "other" -> return WalletContract.AccountAssetEntry.TYPE_OTHER
            else -> throw JSONException("unknown asset.type: " + name) // not UI
        }
    }

    /** Пусто — срока нет (null); иначе строго `yyyy-MM-dd`. */
    @Throws(JSONException::class)
    private fun parseDueDate(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        val f = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        f.setLenient(false)
        try {
            f.parse(value)
        } catch (e: ParseException) {
            throw JSONException("bad debt.due_date: " + value) // not UI
        }
        if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) throw JSONException("bad debt.due_date: " + value) // not UI
        return value
    }

    private fun revaluationCategoryExists(db: SQLiteDatabase): Boolean {
        val c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + "=? AND " +
                WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(AccountItem.SystemKind.REVALUATION.value.toString()))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    private fun stampRevaluation(db: SQLiteDatabase, accName: String) {
        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND, AccountItem.SystemKind.REVALUATION.value)
        db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME), arrayOf<String>(accName))
    }

    /**
     * Элемент "counterparties": `{"name": "...", "comment": "...", "icon": "...", "archived": false`}.
     * Живой контрагент с тем же именем (правило [WalletDbCRUD.GetCounterpartyIdByName])
     * обновляется, иначе заводится. Иконка — по имени среди живых; нет такой или "unknown" — без иконки.
     */
    @Throws(JSONException::class)
    private fun ParseAndInsertJsonCounterparty(db: SQLiteDatabase, obj: JSONObject) {
        val name = obj.getString("name")
        if (WalletDbCRUD.NormalizeCounterpartyName(name).isEmpty())
            throw JSONException("counterparty name is empty") // not UI
        val comment = obj.optString("comment", "")
        val archived = obj.optBoolean("archived", false)

        var id = WalletDbCRUD.GetCounterpartyIdByName(db, name)
        if (id > 0) WalletDbCRUD.UpdateCounterpartyComment(db, id, comment)
        else id = WalletDbCRUD.CreateCounterparty(db, name, comment)
        if (id <= 0) throw JSONException("could not create counterparty") // not UI

        WalletDbCRUD.ArchiveCounterparty(db, id, archived)
        val cv = ContentValues()
        val iconUuid = iconUuidByName(db, obj.optString("icon", "unknown"))
        if (iconUuid == null) cv.putNull(WalletContract.CounterpartyEntry.COLUMN_ICON_UUID)
        else cv.put(WalletContract.CounterpartyEntry.COLUMN_ICON_UUID, iconUuid)
        db.update(WalletContract.CounterpartyEntry.TABLE_NAME, cv,
                WalletContract.CounterpartyEntry._ID + "=?", arrayOf<String>(id.toString()))
    }

    private fun iconUuidByName(db: SQLiteDatabase, iconName: String?): String? {
        if (iconName == null || iconName.isEmpty() || "unknown".equals(iconName)) return null
        val c = db.rawQuery("SELECT " + WalletContract.IconEntry.COLUMN_UUID +
                " FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry.COLUMN_NAME + "=? AND " +
                WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL LIMIT 1", arrayOf<String>(iconName))
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    @JvmStatic
    @Throws(ParseException::class, IOException::class)
    fun FillOperationDataFromFile(context: Context, fileStream: InputStream): ImportResult {
        val res = ImportResult()
        val firstLineHasToBe = "Date,AccountFr,AccountTo,Amount,Comment"
        val db = Singleton.instance.getReadableDatabase(context)


        db.beginTransaction()
        try {
            val reader = BufferedReader(InputStreamReader(fileStream))

            val firstLine = normalizeHeader(reader.readLine())
            if (!firstLineHasToBe.equals(firstLine))
                throw IOException(headerMismatchMessage(firstLineHasToBe, firstLine))

            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim { it <= ' ' }.isEmpty()) continue

                val skipReason = ParseAndInsertOperation(db, line)
                if (skipReason == null)
                    res.imported++
                else
                    res.skipped.add(skipReason)
            }

            db.setTransactionSuccessful()
            return res
        } finally {
            db.endTransaction()
        }
    }

    @JvmStatic
    @Throws(ParseException::class, IOException::class, Exception::class)
    fun FillAccountDataFromFile(context: Context, fileStream: InputStream): Int {
        var res = 0

        val db = Singleton.instance.getReadableDatabase(context)

        val firstLineHasToBe = "AccountName,AccountComment,AccountIconName,CurrencyName,CurrencyIso,CurrencyIconName"
        db.beginTransaction()
        try {
            val br = BufferedReader(InputStreamReader(fileStream))

            val firstLine = normalizeHeader(br.readLine())
            if (!firstLineHasToBe.equals(firstLine))
                throw IOException(headerMismatchMessage(firstLineHasToBe, firstLine))

            while (true) {
                val line = br.readLine() ?: break
                if (line.trim { it <= ' ' }.isEmpty()) continue
                try {
                    res += ParseAndInsertAccount(db, Singleton.instance.getIconList(context), line)
                } catch (e: Exception) {
                    throw Exception("Cant parse line:"+line +"/n"+ e)
                }
            }
            db.setTransactionSuccessful()
            return res
        } finally {
            db.endTransaction()
        }
    }

    @Throws(ParseException::class)
    private fun ParseAndInsertCurrencyPairRate(db: SQLiteDatabase, line: String): Int {
        val vals: Array<String> = java.util.regex.Pattern.compile(",").split(line)
        val sumbol = vals[0]
        val dt = format.parse(vals[1])
        val rt: Double? = java.lang.Double.parseDouble(vals[2])

        return WalletFillData.InsertCurrencyPairRate(db, sumbol, dt, rt)
    }

    @JvmStatic
    @Throws(ParseException::class, IOException::class, Exception::class)
    fun FillCurrencyPairRateDataFromStream(context: Context, fileStream: InputStream): Int {
        var res = 0
        val db = Singleton.instance.getReadableDatabase(context)

        db.beginTransaction()
        try {
            val br = BufferedReader(InputStreamReader(fileStream))

            val firstLineHasToBe = "Symbol,Date,Rate"
            val firstLine = normalizeHeader(br.readLine())
            if (!firstLineHasToBe.equals(firstLine))
                throw IOException(headerMismatchMessage(firstLineHasToBe, firstLine))

            while (true) {
                val line = br.readLine() ?: break
                if (line.trim { it <= ' ' }.isEmpty()) continue
                try {
                    res += ParseAndInsertCurrencyPairRate(db, line)
                } catch (e: Exception) {
                    throw Exception("Cant parse line:"+line +"/n"+ e)
                }
            }
            db.setTransactionSuccessful()
            return res
        } finally {
            db.endTransaction()
        }
    }

    // ── Импорт из JSON — план "Б" по docs/TODO_transfers.md, п.2 ───────────────
    //
    // Плоский CSV операций (см. FillOperationDataFromFile выше) не может выразить,
    // что несколько его строк — части одной пользовательской операции: у операции
    // с разными валютами сторон нужны ДВЕ суммы (см. operation_link.sql), а у CSV
    // нет места для такой связи. Отсюда переводы, пришедшие импортом (в т.ч. из
    // tools/moneylover), раньше не имели записей в operation_link вовсе.
    //
    // Формат — JSON-объект с четырьмя опциональными разделами (пустой/отсутствующий
    // раздел просто пропускается); tools/moneylover теперь пишет все четыре в один
    // trans.json, т.к. отдельного SQL/DB-пути для счетов/пар/курсов больше нет:
    //
    //   {
    //     "accounts": [
    //       {"name": "Тинькофф Usd", "comment": "...", "icon": "Tinkoff",
    //        "currency": {"name": "USD", "iso": "USD", "icon": ""}},
    //       {"name": "Groceries", "comment": "Groceries", "icon": "Groceries"}
    //     ],
    //     "currency_pairs": [
    //       {"symbol": "EURUSD", "base": "EUR", "quote": "USD"}
    //     ],
    //     "rates": [
    //       {"symbol": "EURUSD", "date": "2020-08-20", "rate": 1.18023}
    //     ],
    //     "operations": [
    //       {"date": "2020-08-26", "from": "Тинькофф", "to": "Тинькофф Usd",
    //        "amount": 5000, "comment": "...",
    //        "leg": {"amount": 65.4},
    //        "commission": {"amount": 50, "from": "source", "category": "Transfer commission"}},
    //       {"date": "2020-08-26", "from": "Groceries", "to": "Тинькофф",
    //        "amount": 1200, "comment": ""}
    //     ]
    //   }
    //
    // Разделы применяются в этом порядке (accounts → currency_pairs → rates →
    // operations), а не в порядке ключей в файле — счета заводят валюты, нужные
    // парам; пары нужны курсам по symbol; счета нужны операциям по имени.
    //
    // Обратная совместимость: старые файлы — голый JSONArray одних операций (без
    // обёртки) — читаются как раньше, эквивалент {"operations": [...]}.
    //
    // Для operations: "leg"/"commission" опциональны. Без них строка — обычная
    // проводка (расход/доход/одновалютный перевод), ровно как строка CSV. С "leg" —
    // валютный перевод: "from"/"to" — реальные счета отправителя/получателя, а сама
    // проводка в базе идёт через мост "Transfer" (см. TransactionActivity.saveTransfer,
    // тот же путь: InsertOperation дважды + LinkOperations типом LEG). "commission"
    // — третья проводка со стороны "from" (source, по умолчанию) или "to" (dest)
    // на категорию по имени ("category", по умолчанию — системная "Transfer commission"),
    // связанная типом COMMISSION.
    @Throws(IOException::class)
    private fun readAll(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf: ByteArray = ByteArray(4096)
        var n: Int
        while (input.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
        return bos.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * @return null при успехе, иначе причина пропуска этого элемента массива —
     * тот же принцип отчёта, что и у [ParseAndInsertOperation].
     */
    @Throws(JSONException::class, ParseException::class)
    private fun ParseAndInsertJsonOperation(db: SQLiteDatabase, obj: JSONObject): String? {
        val dateStr = obj.getString("date")
        val dt = format.parse(dateStr)
        val accFromName = obj.getString("from")
        val accToName = obj.getString("to")
        val amount: Double = obj.getDouble("amount")
        val comment = obj.optString("comment", "")
        val dtStr = DateUtils.DateToStr(dt)

        val fromId: Int? = FindAccountIdByName(db, accFromName)
        val toId: Int? = FindAccountIdByName(db, accToName)
        val missing = StringBuilder()
        if (fromId == null) missing.append(accFromName)
        if (toId == null) {
            if (missing.length > 0) missing.append(", ")
            missing.append(accToName)
        }
        if (missing.length > 0)
            return Texts.get(R.string.import_row_no_account, dateStr + " " + accFromName + "→" + accToName, missing)

        val leg = obj.optJSONObject("leg")
        var mainId: Long

        if (leg != null) {
            // Та же категория-мост, тот же способ её найти/завести, что и в
            // TransactionActivity.saveTransfer — обе стороны (интерактивный ввод и
            // импорт) обязаны сходиться к одной и той же строке account.
            val bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                    AccountItem.SystemKind.TRANSFER.value,
                    AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
            if (bridgeId <= 0) return Texts.get(R.string.import_row_no_transfer, dateStr)

            mainId = WalletDbCRUD.InsertOperation(db, dtStr, fromId!!, bridgeId, amount, comment)
            val legId: Long = WalletDbCRUD.InsertOperation(db, dtStr, bridgeId, toId!!, leg.getDouble("amount"), comment)
            if (mainId <= 0 || legId <= 0)
                return Texts.get(R.string.import_row_leg_not_inserted, dateStr + " " + accFromName + "→" + accToName)
            WalletDbCRUD.LinkOperations(db, mainId, legId, OperationItem.LinkType.LEG)
        } else {
            mainId = WalletDbCRUD.InsertOperation(db, dtStr, fromId!!, toId!!, amount, comment)
            if (mainId <= 0)
                return Texts.get(R.string.import_row_not_inserted, dateStr + " " + accFromName + "→" + accToName)
        }

        val commission = obj.optJSONObject("commission")
        if (commission != null) {
            val fromDest = "dest".equals(commission.optString("from", "source"))
            val sideId: Int? = if (fromDest) toId else fromId
            val categoryName = commission.optString("category", AccountItem.SYSTEM_COMMISSION_NAME)

            // Категория по умолчанию — системная (см. AccountItemActivity/TransactionActivity):
            // находится по system_kind, а не по имени. Своя категория, которую пользователь
            // мог выбрать для конкретного перевода, — обычная, ищется/заводится по имени.
            val categoryId: Int = if (AccountItem.SYSTEM_COMMISSION_NAME.equals(categoryName, ignoreCase = true)) WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                            AccountItem.SYSTEM_COMMISSION_NAME, AccountItem.SYSTEM_TRANSFER_NAME) else WalletDbCRUD.GetOrCreateCategory(db, categoryName, AccountItem.SYSTEM_TRANSFER_NAME)
            if (categoryId <= 0) return Texts.get(R.string.import_row_no_commission_category, dateStr)

            val commissionId: Long = WalletDbCRUD.InsertOperation(db, dtStr, sideId!!, categoryId,
                    commission.getDouble("amount"), comment)
            if (commissionId <= 0) return Texts.get(R.string.import_row_commission_not_inserted, dateStr)
            WalletDbCRUD.LinkOperations(db, mainId, commissionId, OperationItem.LinkType.COMMISSION)
        }

        return null
    }

    /**
     * JSON-эквивалент [ParseAndInsertCurrencyPairRate] для раздела "currency_pairs" —
     * заводит саму пару (символ + база/квота по ISO), а не курс к ней (тот — раздел "rates",
     * см. [ParseAndInsertJsonRate]). Идемпотентно: уже существующий symbol
     * [WalletFillData.InsertCurrencyPair] тихо пропускает (см. её javadoc).
     */
    @Throws(JSONException::class)
    private fun ParseAndInsertJsonCurrencyPair(db: SQLiteDatabase, obj: JSONObject): String? {
        val symbol = obj.getString("symbol")
        val base = obj.getString("base")
        val quote = obj.getString("quote")

        WalletFillData.InsertCurrencyPair(db, symbol, base, quote)
        return null
    }

    /**
     * JSON-эквивалент [ParseAndInsertCurrencyPairRate] (CSV, раздел "Symbol,Date,Rate")
     * для раздела "rates". [WalletFillData.InsertCurrencyPairRate] возвращает 0, если
     * валютной пары с таким symbol ещё нет — тогда отчитываемся как о пропуске, а не о тихом нуле.
     */
    @Throws(JSONException::class, ParseException::class)
    private fun ParseAndInsertJsonRate(db: SQLiteDatabase, obj: JSONObject): String? {
        val symbol = obj.getString("symbol")
        val dateStr = obj.getString("date")
        val dt = format.parse(dateStr)
        val rate: Double = obj.getDouble("rate")

        val affected = WalletFillData.InsertCurrencyPairRate(db, symbol, dt, rate)
        if (affected == 0)
            return Texts.get(R.string.import_row_no_pair, symbol + " " + dateStr, symbol)
        return null
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class, ParseException::class)
    fun FillOperationDataFromJson(context: Context, fileStream: InputStream): ImportResult {
        val res = ImportResult()
        val db = Singleton.instance.getReadableDatabase(context)

        // Обратная совместимость: старый формат — голый JSONArray одних операций.
        // Новый — JSONObject с опциональными "accounts"/"currency_pairs"/"rates"/
        // "operations" (см. комментарий-схему выше FillOperationDataFromJson).
        val text = readAll(fileStream).trim { it <= ' ' }
        var counterpartiesArr: JSONArray? = null
        var accountsArr: JSONArray? = null
        var pairsArr: JSONArray? = null
        var ratesArr: JSONArray? = null
        var opsArr: JSONArray? = null
        if (text.startsWith("[")) {
            opsArr = JSONArray(text)
        } else {
            val root = JSONObject(text)
            counterpartiesArr = root.optJSONArray("counterparties")
            accountsArr = root.optJSONArray("accounts")
            pairsArr = root.optJSONArray("currency_pairs")
            ratesArr = root.optJSONArray("rates")
            opsArr = root.optJSONArray("operations")
            if (opsArr == null) opsArr = JSONArray()
        }

        val icons: IconList? = if (accountsArr != null) Singleton.instance.getIconList(context) else null

        db.beginTransaction()
        try {
            // Порядок разделов принципиален: контрагенты нужны блокам "debt" счетов;
            // счета заводят валюты, нужные парам; пары нужны курсам по symbol;
            // счета нужны операциям по имени.
            if (counterpartiesArr != null) {
                for (i in 0 until counterpartiesArr.length()) {
                    try {
                        ParseAndInsertJsonCounterparty(db, counterpartiesArr.getJSONObject(i))
                        res.imported++
                    } catch (e: JSONException) {
                        res.skipped.add("counterparties#" + i + " — " + e.message)
                    }
                }
            }
            if (accountsArr != null) {
                for (i in 0 until accountsArr.length()) {
                    try {
                        res.imported += ParseAndInsertJsonAccount(db, icons, accountsArr.getJSONObject(i))
                    } catch (e: JSONException) {
                        res.skipped.add("accounts#" + i + " — " + e.message)
                    }
                }
            }
            if (pairsArr != null) {
                for (i in 0 until pairsArr.length()) {
                    var skipReason: String? = null
                    try {
                        skipReason = ParseAndInsertJsonCurrencyPair(db, pairsArr.getJSONObject(i))
                    } catch (e: JSONException) {
                        skipReason = "currency_pairs#" + i + " — " + e.message
                    }
                    if (skipReason == null) res.imported++
                    else res.skipped.add(skipReason)
                }
            }
            if (ratesArr != null) {
                for (i in 0 until ratesArr.length()) {
                    var skipReason: String? = null
                    try {
                        skipReason = ParseAndInsertJsonRate(db, ratesArr.getJSONObject(i))
                    } catch (e: JSONException) {
                        skipReason = "rates#" + i + " — " + e.message
                    }
                    if (skipReason == null) res.imported++
                    else res.skipped.add(skipReason)
                }
            }
            for (i in 0 until opsArr.length()) {
                val obj = opsArr.getJSONObject(i)
                var skipReason: String? = null
                try {
                    skipReason = ParseAndInsertJsonOperation(db, obj)
                } catch (e: JSONException) {
                    skipReason = "#" + i + " — " + e.message
                }
                if (skipReason == null) res.imported++
                else res.skipped.add(skipReason)
            }
            db.setTransactionSuccessful()
            return res
        } finally {
            db.endTransaction()
            // Кэш иконок мог не знать о заведённых импортом своих иконках.
            Singleton.instance.reloadIconList(context)
        }
    }
}
