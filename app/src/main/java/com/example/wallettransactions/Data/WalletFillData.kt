package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DateUtils

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.*
import java.nio.charset.StandardCharsets
import java.util.Date


/*
SELECT 'db.execSQL("insert into operation(date, acc_fr_id, acc_to_id, amount, amount_rc, comment)',
'select dt, af._id , act._id , amount, am_rc, t.comment from (select',
cast((julianday(date)  - 2440587.5)*86400000 AS INTEGER)||" dt, '"||af.name||"' acc_fr,'"||act.name||"' acc_to, "||amount||" amount, "|| amount_rc ||" am_rc , '"|| o2.comment||"' comment",
') t join account af on af.name  = acc_fr join account act on act.name  = acc_to");'
FROM operation o2
join account af
on af."_id" =acc_fr_id
join account act
on act."_id" =acc_to_id
==============
SELECT 'InsertRecord(db,format.parse("'||
date||'"), "'||af.name||'","'||act.name||'", '||amount||', '|| amount_rc ||' , "'|| o2.comment||'");',
') t join account af on af.name  = acc_fr join account act on act.name  = acc_to");'
FROM operation o2
join account af
on af."_id" =acc_fr_id
join account act
on act."_id" =acc_to_id
======
SELECT date||","||af.name||","||act.name||", "||amount||", "|| amount_rc ||","|| o2.comment||"",
') t join account af on af.name  = acc_fr join account act on act.name  = acc_to");'
FROM operation o2
join account af
on af."_id" =acc_fr_id
join account act
on act."_id" =acc_to_id



 */
object WalletFillData {
    private lateinit var appResources: Resources
    /** Пакет приложения — вместе с [appResources] нужен для [ResolveDrawableId]. */
    private lateinit var appPackageName: String
    const val ICO_CATEGORY_FLAGS = 1L
    const val ICO_CATEGORY_ACCOUNT = 2L

    /**
     * Дефолтные иконки/валюты/валютные пары+курсы/счета для пустой БД (см. [*]) и частичного реналива (см. ResetDataActivity) — единый
     * источник правды `assets/seed.json`, а не разбросанные по этому классу вызовы
     * `InsertIconEntry`/`InsertCurrencyEntry` с `R.drawable.*`. Чтобы
     * добавить/убрать иконку, валюту, валютную пару, курс или дефолтный счёт — правишь
     * только этот JSON (плюс сам файл иконки в `res/drawable`, если он новый) и
     * пересобираешь; менять код в этом классе для этого больше не нужно.
     *
     * Схема (все секции опциональны, но в штатном файле присутствуют все):
     * <pre>
     * {
     *   "category_icons": [{"name": "Bar", "resource": "acc_beer"}],
     *   "currencies": [{"name": "Euro", "iso": "EUR", "symbol": "€",
     *                    "icon": "Flag Euro", "icon_resource": "flag_eur", "precision": 2}],
     *   "flag_icons": [{"name": "Flag ALL", "resource": "flag_albania"}],
     *   "currency_pairs": [{"symbol": "EURUSD", "base": "EUR", "quote": "USD"}],
     *   "rates": [{"symbol": "EURUSD", "date": "2020-08-20", "rate": 1.18023}],
     *   "accounts": [{"names": {"ru": "Такси", "en": "Taxi"}, "icon": "Taxi", "category_kind": 0},
     *                {"name": "Transfer", "icon": "Transfer"}]
     * }
     * </pre>
     * Штатный набор (openspec new-user-default-set, spec starter-set) — два общих
     * кошелька («Наличные / Cash», «Кредитная карта / Credit card», у них
     * "accounts[].currency_iso" — строка или объект по языкам `{"ru": "RUB", "en":
     * "USD"`}), без курсов и личных названий; "rates" поддерживается, но в файле не
     * используется. Категория — пара имён "names", язык — [*]; название валюты — [CurrencyNames.displayName] по
     * коду, "name" — запасное. Каждая строка набора — с меткой `from_seed = 1`.
     * "resource"/"icon_resource" — имя файла в `res/drawable` без расширения,
     * разрешается в id через [ResolveDrawableId] (не `R.drawable.*` — иначе
     * добавление иконки снова требовало бы правки Java). "accounts[].currency_iso" ссылается
     * на валюту, уже заведённую секцией "currencies" этого же файла (см. [*]), а не несёт её данные повторно, в отличие от формата
     * пользовательского JSON-импорта ([WalletImportData.FillOperationDataFromJson]) —
     * там счетов с новой валютой единицы, здесь львиная доля валют не привязана ни к
     * одному дефолтному счёту вовсе.
     */
    private fun LoadSeed(context: Context): JSONObject {
        try {
            context.assets.open("seed.json").use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
                return JSONObject(bos.toString(StandardCharsets.UTF_8.name()))
            }
        } catch (e: IOException) {
            return seedReadFailed(e)
        } catch (e: JSONException) {
            return seedReadFailed(e)
        }
    }

    private fun seedReadFailed(e: Exception): JSONObject {
        // Файл — часть приложения (assets), а не пользовательский ввод: дойти сюда
        // может только сломанная сборка. Пустой объект — секции ниже это optJSONArray,
        // так что сеятели просто ничего не заведут, а не упадут на пустой БД.
        Log.e("WalletFillData", "Cant read seed.json", e)
        return JSONObject()
    }

    /**
     * Имя drawable-ресурса (без расширения, как в `res/drawable`) → id, без `* R.drawable.*` — см. [LoadSeed]. "unknown" — тот же фолбэк по имени, что уже
     * используют [GetIconIdByName]/`InsertOrUpdateAccount` для не найденной иконки.
     */
    private fun ResolveDrawableId(resourceName: String): Int {
        val id = appResources.getIdentifier(resourceName, "drawable", appPackageName)
        if (id != 0) return id
        Log.w("WalletFillData", "seed.json: drawable not found: " + resourceName)
        return appResources.getIdentifier("unknown", "drawable", appPackageName)
    }




    @JvmStatic
    fun GetAffectedRowsCount(db: SQLiteDatabase): Int {
        val res = db.rawQuery("select changes() 'affected_rows'", null)
        res.moveToFirst()
        return res.getInt(res.getColumnIndex("affected_rows"))
    }



    private fun InsertIconEntry(db: SQLiteDatabase, name: String, iconType: Long, resource: Int): Long {
        val values = ContentValues()
        values.put(WalletContract.IconEntry.COLUMN_NAME, name)
        values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, iconType)

        var bitmap = BitmapFactory.decodeResource(appResources, resource)
        if (bitmap == null) {
            // Vector drawable — render onto a Canvas
            val d = appResources.getDrawable(resource, null)
            if (d != null) {
                val w: Int = if (d.getIntrinsicWidth()  > 0) d.getIntrinsicWidth() else 96
                val h: Int = if (d.getIntrinsicHeight() > 0) d.getIntrinsicHeight() else 96
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(bitmap))
            }
        }
        if (bitmap == null) return -1L
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val img: ByteArray = bos.toByteArray()

        values.put(WalletContract.IconEntry.COLUMN_IMAGE, img)
        // Иконка из seed.json заведена здесь, на сервере её uuid нет — своя (server-managed-icons):
        // в push вместо неё уходит заместитель, одноимённая с сервера её не дублирует (SyncEngine.applyIcon).
        values.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL)

        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, values)
    }

    /**
     * Иконка набора: живая иконка с этим именем уже есть (с сервера или своя) — её `_id`,
     * своя не заводится (android-wallet#50). Иначе пересев после «Удалить данные» заводил бы
     * свою рядом с живой иконкой с сервера, а голая вставка упёрлась бы в `uq_icon_name`
     * и молча вернула бы `-1`. На чистой базе имён нет — заводится как раньше.
     */
    private fun SeedIcon(db: SQLiteDatabase, name: String, iconType: Long, resourceName: String): Long {
        val existing: Long = GetIconIdByName(db, name)
        if (existing >= 0) return existing
        return InsertIconEntry(db, name, iconType, resourceName)
    }

    /** `seed.json`-вариант — имя drawable-ресурса вместо `R.drawable.*` id, см. [ResolveDrawableId]. */
    private fun InsertIconEntry(db: SQLiteDatabase, name: String, iconType: Long, resourceName: String): Long {
        return InsertIconEntry(db, name, iconType, ResolveDrawableId(resourceName))
    }

    /** Валюта набора — с меткой «из набора» (new-user-default-set, design D3). */
    private fun InsertCurrencyEntry(db: SQLiteDatabase, curName: String?, isoName: String, symbol: String, iconId: Long?, precision: Int): Long {
        val values = ContentValues()
        values.put(WalletContract.CurrencyEntry.COLUMN_NAME, curName)
        values.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, isoName)
        values.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId)
        values.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol)
        values.put(WalletContract.CurrencyEntry.COLUMN_PRECISION, precision)
        values.put(WalletContract.CurrencyEntry.COLUMN_FROM_SEED, 1)
        return db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, values)
    }


    /** Счёт набора — с меткой «из набора» (new-user-default-set, design D3). */
    private fun InsertAccountEntry(db: SQLiteDatabase, accName: String, accComment: String, accIconId: Long): Long {
        return InsertAccountEntry(db, accName, accComment, accIconId, AccountItem.CategoryKind.BOTH)
    }

    /** С видом категории (category-kind): у кошельков и системных категорий набора — всегда «Оба». */
    private fun InsertAccountEntry(db: SQLiteDatabase, accName: String, accComment: String, accIconId: Long, categoryKind: AccountItem.CategoryKind): Long {
        val values = ContentValues()
        values.put(WalletContract.AccountEntry.COLUMN_NAME, accName)
        values.put(WalletContract.AccountEntry.COLUMN_COMMENT, accComment)
        values.put(WalletContract.AccountEntry.COLUMN_ICON_ID, accIconId)
        values.put(WalletContract.AccountEntry.COLUMN_FROM_SEED, 1)
        values.put(WalletContract.AccountEntry.COLUMN_CATEGORY_KIND, categoryKind.value)
        return db.insert(WalletContract.AccountEntry.TABLE_NAME, null, values)
    }


    private fun UpdateIconAccountEntry(db: SQLiteDatabase, accName: String, accIconId: Long): Long {
        val values = ContentValues()
        values.put(WalletContract.AccountEntry.COLUMN_ICON_ID, accIconId)
        return db.update(WalletContract.AccountEntry.TABLE_NAME, values, WalletContract.AccountEntry.COLUMN_NAME +"=?", arrayOf<String>(accName)).toLong()
    }

    private fun GetAffectedRecordAmount(db: SQLiteDatabase): Int {
        val res = db.rawQuery("select changes() 'affected_rows'", null)
        res.moveToFirst()
        return res.getInt(res.getColumnIndex("affected_rows"))
    }

    /**
     * Публичный (см. [com.example.wallettransactions.Data.WalletImportData] —
     * JSON-импорт теперь умеет заводить и валютные пары, не только курсы к ним).
     *
     * Раньше был "insert or replace": для SQLite это snapshot-семантика "удалить
     * старую строку целиком и вставить новую" — то же самое, что уже чинили в
     * [InsertCurrencyPairRate] (см. её javadoc про v72). Повторный импорт
     * уже существующего symbol перегенерировал бы и _id, и uuid, осиротив все
     * currency_pair_rate, которые на этот _id ссылаются. Раз эта функция теперь
     * вызывается не только один раз при первом запуске (сид), а на каждый JSON-
     * импорт, столкнуться с этим стало реально, а не гипотетически — поэтому: уже
     * существующий symbol просто пропускаем (base/quote для существующей пары не
     * трогаем), заводим только то, чего ещё нет.
     */
    @JvmStatic
    fun InsertCurrencyPair(db: SQLiteDatabase, curName: String, curBase: String, curQuote: String): Int {
        // deleted_at IS NULL — символ мягко удалённой пары не занят: уникальность
        // теперь по частичному индексу uq_currency_pair_symbol (см. WalletDbHelper.
        // createPartialUniqueIndexes), не по голому column-level UNIQUE. Без этого
        // условия реналив после ResetDataActivity/WalletFillData.ResetCurrencyPairsAndRates
        // тихо не заводил бы новую пару — эта проверка видела бы старую (уже
        // мягко удалённую) строку с тем же symbol и решала бы, что заводить нечего.
        val c = db.rawQuery("SELECT " + WalletContract.CurrencyPairEntry._ID +
                " FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?" +
                " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(curName))
        try {
            if (c.moveToFirst()) return 0
        } finally {
            c.close()
        }

        val sql = "insert into currency_pair (symbol, base_currency_id,quote_currency_id)\n" +
                "select sym, cb.\"_id\" , cq.\"_id\"  from\n" +
                "(select ? sym, ? cb, ? cq) t\n" +
                "join currency cb \n" +
                "on cb.iso_code = t.cb \n" +
                "join currency cq \n" +
                "on cq.iso_code = t.cq"

        db.execSQL(sql, arrayOf<String>(curName, curBase, curQuote))

        return GetAffectedRecordAmount(db)
    }

    /**
     * v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — голый `INSERT OR
     * REPLACE`, для SQLite это "удалить старую строку целиком и вставить новую" —
     * заново сгенерированный uuid на повторную загрузку того же курса раздувал бы
     * поток синка дублями. Тот же приём, что [WalletDbCRUD.InsertOrReplaceRate]:
     * нашлась строка на эту (пара, дата) — обновляем (uuid не трогаем, dirty
     * форсит триггер), не нашлась — вставляем с новым uuid.
     */
    @JvmStatic
    fun InsertCurrencyPairRate(db: SQLiteDatabase, curName: String, date: Date, rate: Double?): Int {
        // deleted_at IS NULL — тот же довод, что в InsertCurrencyPair: не находить
        // мягко удалённую пару и не вешать на неё новый курс, который тогда нигде
        // не показывался бы (UI фильтрует пары по deleted_at IS NULL).
        val c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME +
                " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?" +
                " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(curName))
        var pairId: Long
        try {
            if (!c.moveToFirst()) return 0
            pairId = c.getLong(0)
        } finally {
            c.close()
        }

        // DateToDateStr (не DateToStr) — курс это величина на день, время в
        // колонке не несёт смысла (см. wallet-api/schema/table/currency_pair_rate.sql,
        // тот же довод на сервере). Раньше здесь был DateToStr ("yyyy-MM-dd
        // HH:mm") — тот самый источник локальных дублей одной даты с разным
        // временем, из-за которого push бился об uq_currency_pair_rate_ledger
        // (см. org/docs/TODO_mobile_sync.md).
        val dateStr = DateUtils.DateToDateStr(date)
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_RATE, rate)
        // Заодно нормализует date у найденной строки (см. substr-матч ниже) —
        // если она ещё несёт легаси-время, следующий же импорт/обновление этой
        // даты приводит её к чистому "yyyy-MM-dd", без отдельной миграции.
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, dateStr)

        // substr(date,1,10), не точное "=" — на строке, ещё оставшейся с полным
        // "yyyy-MM-dd HH:mm" от старого InsertCurrencyPairRate, точное сравнение
        // с новым dateStr (уже без времени) никогда не совпало бы — та же правка,
        // что уже сделана в RateEditDialog.saveRate.
        //
        // deleted_at IS NULL — реальный баг, найденный на живом устройстве
        // (2026-09-11): без этого условия matcher находил и "оживлял" МЯГКО
        // УДАЛЁННУЮ строку (rate/date обновлялись, deleted_at оставался
        // проставленным как был) — на сервере эта же строка (uuid уже занят)
        // к тому моменту тоже мягко удалена и потому НЕ покрыта частичным
        // индексом uq_currency_pair_rate_ledger (WHERE deleted_at IS NULL) —
        // ON CONFLICT по этому индексу не находил "конфликта" и пытался
        // голый INSERT с уже существующим uuid, падая на самом PRIMARY KEY
        // (currency_pair_rate_pkey, "duplicate_key"). С фильтром мёртвая
        // строка для матчинга не существует — обычный INSERT ниже заведёт
        // НОВУЮ строку со свежим uuid, старая тень остаётся как есть.
        val updated = db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND " +
                        "substr(" + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10)=? AND " +
                        WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(pairId.toString(), dateStr))
        if (updated > 0) return updated

        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE, pairId)
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, dateStr)
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_UUID, java.util.UUID.randomUUID().toString())
        val inserted: Long = db.insert(WalletContract.CurrencyPairRateEntry.TABLE_NAME, null, cv)
        return if (inserted > 0) 1 else 0
    }

    /**
     * Валюты + их флаги (парой) из секции "currencies" `seed.json` — годится только
     * на чистой БД (первый запуск), см. [SeedDefaultCurrencyFlagIcons] про реналив
     * одних иконок без пересоздания currency.
     */
    @JvmStatic
    fun FillCurrenis(db: SQLiteDatabase, context: Context) {
        val currencies = LoadSeed(context).optJSONArray("currencies")
        if (currencies == null) return
        // Название — от Android по коду на языке посева, "name" из файла — запасное (design D10).
        val locale = java.util.Locale(CurrencyNames.seedLanguage(context))

        for (i in 0 until currencies.length()) {
            try {
                val c = currencies.getJSONObject(i)
                val iconId: Long = SeedIcon(db, c.getString("icon"), ICO_CATEGORY_FLAGS, c.getString("icon_resource"))
                val iso = c.getString("iso")
                InsertCurrencyEntry(db, CurrencyNames.displayName(iso, c.getString("name"), locale), iso,
                        c.getString("symbol"), iconId, c.optInt("precision", 2))
            } catch (e: JSONException) {
                Log.e("WalletFillData", "seed.json currencies#" + i, e)
            }
        }
        InsertSpareFlagIcons(db, context)
    }

    /**
     * Флаги без валюты из секции "flag_icons" `seed.json`: валюты нет в наборе, а флаг
     * остаётся в списке иконок для своих валют. Имена — прежние ("Flag ALL" и др.), по ним
     * телефон сопоставляет иконку с одноимённой серверной (icon-sync).
     */
    private fun InsertSpareFlagIcons(db: SQLiteDatabase, context: Context) {
        val flags = LoadSeed(context).optJSONArray("flag_icons")
        if (flags == null) return
        for (i in 0 until flags.length()) {
            try {
                val f = flags.getJSONObject(i)
                SeedIcon(db, f.getString("name"), ICO_CATEGORY_FLAGS, f.getString("resource"))
            } catch (e: JSONException) {
                Log.e("WalletFillData", "seed.json flag_icons#" + i, e)
            }
        }
    }

    /**
     * Только флаги валют (иконки, не сами валюты) — [FillCurrenis] заводит их в
     * паре с currency, что на чистой БД (первый запуск) верно, но не годится для реналива
     * после очистки `icon` без пересоздания `currency` (см. ResetDataActivity):
     * там валюты остаются как есть, и заново создавать нужно только иконки, с теми же
     * именами, что и здесь ("Flag EUR" и т.д.), чтобы релинк по имени их нашёл.
     */
    @JvmStatic
    fun SeedDefaultCurrencyFlagIcons(db: SQLiteDatabase, context: Context) {
        appResources = context.getResources()
        appPackageName = context.getPackageName()
        val currencies = LoadSeed(context).optJSONArray("currencies")
        if (currencies == null) return

        for (i in 0 until currencies.length()) {
            try {
                val c = currencies.getJSONObject(i)
                SeedIcon(db, c.getString("icon"), ICO_CATEGORY_FLAGS, c.getString("icon_resource"))
            } catch (e: JSONException) {
                Log.e("WalletFillData", "seed.json currencies#" + i, e)
            }
        }
        InsertSpareFlagIcons(db, context)
    }

    /**
     * Дефолтные валютные пары и их курсы — вынесено из [InsertFirstDictionary] в
     * отдельный метод, чтобы им же можно было пользоваться при реналиве после очистки
     * currency_pair/currency_pair_rate без пересоздания остального (см. ResetDataActivity).
     */
    @JvmStatic
    fun SeedDefaultCurrencyPairsAndRates(db: SQLiteDatabase, context: Context) {
        val seed = LoadSeed(context)

        val pairs = seed.optJSONArray("currency_pairs")
        if (pairs != null) {
            for (i in 0 until pairs.length()) {
                try {
                    val p = pairs.getJSONObject(i)
                    val symbol = p.getString("symbol")
                    if (InsertCurrencyPair(db, symbol, p.getString("base"), p.getString("quote")) > 0) {
                        markPairFromSeed(db, symbol)
                    }
                } catch (e: JSONException) {
                    Log.e("WalletFillData", "seed.json currency_pairs#" + i, e)
                }
            }
        }

        val rates = seed.optJSONArray("rates")
        if (rates != null) {
            for (i in 0 until rates.length()) {
                try {
                    val r = rates.getJSONObject(i)
                    InsertCurrencyPairRate(db, r.getString("symbol"),
                            DateUtils.StrToDate(r.getString("date")), r.getDouble("rate"))
                } catch (e: JSONException) {
                    Log.e("WalletFillData", "seed.json rates#" + i, e)
                }
            }
        }
    }


    /**
     * Метка «из набора» у только что заведённой пары. [InsertCurrencyPair] общий с
     * JSON-импортом, поэтому метку ставит не он. Строка новая (`dirty = 1`), так что
     * триггер `trg_currency_pair_dirty` ничего не меняет.
     */
    private fun markPairFromSeed(db: SQLiteDatabase, symbol: String) {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_FROM_SEED, 1)
        db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=? AND " +
                        WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(symbol))
    }

    /**
     * Дефолтные иконки категорий (плюс "unknown") — вынесено из [InsertFirstDictionary]
     * в отдельный метод по тому же доводу, что [SeedDefaultCurrencyFlagIcons]: нужен
     * для реналива после очистки `icon` без пересоздания остального.
     */
    @JvmStatic
    fun SeedDefaultCategoryIcons(db: SQLiteDatabase, context: Context) {
        appResources = context.getResources()
        appPackageName = context.getPackageName()
        val icons = LoadSeed(context).optJSONArray("category_icons")
        if (icons == null) return

        for (i in 0 until icons.length()) {
            try {
                val ic = icons.getJSONObject(i)
                SeedIcon(db, ic.getString("name"), ICO_CATEGORY_ACCOUNT, ic.getString("resource"))
            } catch (e: JSONException) {
                Log.e("WalletFillData", "seed.json category_icons#" + i, e)
            }
        }
    }

    /**
     * Заводит иконку-заглушку "unknown", если её нет (см. Singleton.loadIconList, issue #18).
     * Только её, а не все [SeedDefaultCategoryIcons]: пользователь, очистивший базу без
     * пересева, справочников не просил — заглушка нужна коду, а не ему.
     *
     * @return true — иконку пришлось завести.
     */
    @JvmStatic
    fun EnsureUnknownIcon(db: SQLiteDatabase, context: Context): Boolean {
        appResources = context.getResources()
        appPackageName = context.getPackageName()
        if (GetIconIdByName(db, "unknown") >= 0) return false
        return InsertIconEntry(db, "unknown", ICO_CATEGORY_ACCOUNT, "unknown") > 0
    }

    @JvmStatic
    fun InsertFirstDictionary(db: SQLiteDatabase, context: Context) {
        appResources = context.getResources()
        appPackageName = context.getPackageName()
        SeedDefaultCategoryIcons(db, context)
        FillCurrenis(db, context) // валюты + их флаги парой — годится только на чистой БД (см. SeedDefaultCurrencyFlagIcons)
        SeedDefaultCurrencyPairsAndRates(db, context)
        InsertSeedAccounts(db, context)
        stampSystemCategories(db)
    }

    /**
     * v75 — проставляет `system_kind` только что вставленным из seed.json строкам
     * «Transfer»/«Transfer commission» (`InsertSeedAccounts` выше делает обычный
     * `INSERT`, без этой колонки). ТО ЖЕ САМОЕ, что бэкфилл шага v75 в
     * `WalletDbHelper.onUpgrade` (сознательно продублировано — сидирование новой базы
     * и апгрейд существующей идут разными путями и в разных классах), и второе (и
     * последнее) легитимное место сравнения по имени: ровно момент превращения старого
     * неявного состояния (имя) в новое явное (`system_kind`), дальше по коду имя
     * больше нигде не участвует в определении системности.
     */
    private fun stampSystemCategories(db: SQLiteDatabase) {
        db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME +
                " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = " +
                AccountItem.SystemKind.TRANSFER.value +
                " WHERE " + WalletContract.AccountEntry.COLUMN_NAME +
                " = '" + AccountItem.SYSTEM_TRANSFER_NAME + "' COLLATE NOCASE")
        db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME +
                " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = " +
                AccountItem.SystemKind.TRANSFER_COMMISSION.value +
                " WHERE " + WalletContract.AccountEntry.COLUMN_NAME +
                " = '" + AccountItem.SYSTEM_COMMISSION_NAME + "' COLLATE NOCASE")
    }

    /**
     * Дефолтные счета из секции "accounts" `seed.json` (раньше — отдельный
     * `accounts.csv`, теперь часть единого файла, см. [LoadSeed]).
     * "currency_iso" ссылается на валюту, уже заведённую секцией "currencies" того же
     * файла ([FillCurrenis] вызывается раньше, см. порядок в [*]) — если такой валюты нет, счёт заводится обычной
     * категорией без баланса, как и раньше при пустом столбце CurrencyIso в CSV.
     */
    private fun InsertSeedAccounts(db: SQLiteDatabase, context: Context) {
        val accounts = LoadSeed(context).optJSONArray("accounts")
        if (accounts == null) return
        val language = CurrencyNames.seedLanguage(context)

        for (i in 0 until accounts.length()) {
            try {
                val a = accounts.getJSONObject(i)
                val accName = seedAccountName(a, language).trim { it <= ' ' }
                if (accName.isEmpty()) continue

                // Живой (deleted_at IS NULL) счёт с этим именем уже есть — пропускаем: голый
                // INSERT ниже упал бы на uq_account_name (WalletDbHelper.createPartialUniqueIndexes),
                // если этот метод зовут не только на чистой БД (см. ResetOperationsAndAccounts —
                // системные категории Transfer/Transfer commission, которые не удаляются, тоже
                // есть в этом seed.json).
                if (GetAccountIdByName(db, accName) > 0) continue

                val iconName = a.optString("icon", "unknown")
                var iconId: Long = GetIconIdByName(db, iconName)
                if (iconId < 0) iconId = GetIconIdByName(db, "unknown")

                val currencyIso = seedCurrencyIso(a, language)
                // Вид категории набора (category-kind, спека starter-set): необязательный
                // "category_kind" — 0 расход, 1 доход; нет поля или значение вне 0..2 — «Оба».
                // Кошелёк набора (есть currency_iso) вида не имеет — всегда «Оба».
                val seedKind: AccountItem.CategoryKind = if (currencyIso.isEmpty()) seedCategoryKind(a) else AccountItem.CategoryKind.BOTH

                val accId: Long = InsertAccountEntry(db, accName, a.optString("comment", accName), iconId, seedKind)

                if (!currencyIso.isEmpty() && accId > 0) {
                    val curId: Long = GetCurrencyIdByIso(db, currencyIso)
                    if (curId > 0) {
                        // uuid — тот же, что у только что вставленного account (не свежий
                        // случайный DEFAULT): server/wallet-api/schema/table/account_operating.sql
                        // требует account_operating.uuid = account.uuid того же счёта (общий
                        // первичный ключ на уровне синка, не только локального _id), иначе
                        // push такого кошелька упал бы FK-ошибкой (fk_account_operating_account).
                        val values = ContentValues()
                        values.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, curId)
                        values.put(WalletContract.AccountOperatingEntry._ID, accId)
                        values.put(WalletContract.AccountOperatingEntry.COLUMN_UUID, GetUuidById(db, accId))
                        db.insert(WalletContract.AccountOperatingEntry.TABLE_NAME, null, values)

                        // InsertAccountEntry выше всегда заводит строку как категорию
                        // (kind по умолчанию 0) — в кошелёк её превращает именно эта
                        // строка account_operating, см. javadoc AccountItem.Kind.
                        val kindValues = ContentValues()
                        kindValues.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.value)
                        db.update(WalletContract.AccountEntry.TABLE_NAME, kindValues,
                                WalletContract.AccountEntry._ID + "=?", arrayOf<String>(accId.toString()))
                    }
                }
            } catch (e: JSONException) {
                Log.e("WalletFillData", "seed.json accounts#" + i, e)
            }
        }
    }

    /**
     * Имя счёта набора: `names[language]`, иначе `names.en`, иначе `name`
     * (системные Transfer/Transfer commission — одно имя на всех языках, design D2/D10).
     */
    @JvmStatic
    @Throws(JSONException::class)
    fun seedAccountName(account: JSONObject, language: String): String {
        val names = account.optJSONObject("names")
        if (names != null) {
            val localized = names.optString(language, "")
            return if (localized.isEmpty()) names.getString("en") else localized
        }
        return account.getString("name")
    }

    /** Вид категории записи набора: `"category_kind"`, нет поля или вне 0..2 — «Оба». */
    @JvmStatic
    fun seedCategoryKind(account: JSONObject): AccountItem.CategoryKind {
        return AccountItem.CategoryKind.fromValue(account.optInt("category_kind", AccountItem.CategoryKind.BOTH.value))
    }

    /**
     * Код валюты кошелька набора: строка `"currency_iso": "EUR"` — одна на всех
     * языках, объект `{"ru": "RUB", "en": "USD"`} — по языку посева, иначе по
     * `en`. Нет поля — пустая строка (счёт заводится категорией без баланса).
     * Openspec new-user-default-set, design D2: «Наличные / Cash» и «Кредитная карта /
     * Credit card» — рубль для русского телефона, доллар для остальных.
     */
    @JvmStatic
    fun seedCurrencyIso(account: JSONObject, language: String): String {
        val byLanguage = account.optJSONObject("currency_iso")
        if (byLanguage == null) return account.optString("currency_iso", "")
        val iso = byLanguage.optString(language, "")
        return if (iso.isEmpty()) byLanguage.optString("en", "") else iso
    }

    /** uuid только что вставленного/существующего account по его _id — см. InsertSeedAccounts. */
    private fun GetUuidById(db: SQLiteDatabase, accId: Long): String? {
        val c = db.rawQuery("SELECT uuid FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + WalletContract.AccountEntry._ID + "=?", arrayOf<String>(accId.toString()))
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    // deleted_at IS NULL в обоих lookup'ах ниже — то же самое, что уже стоит на
    // FindAccountIdByName/аналогах: имя/iso мягко удалённой строки не занято (см.
    // WalletDbHelper.createPartialUniqueIndexes), и релинк после ResetIcons должен
    // найти свежепосеянную живую иконку, а не старую мягко удалённую с тем же именем.
    @JvmStatic
    fun GetIconIdByName(db: SQLiteDatabase, iconName: String): Long {
        val c = db.rawQuery("SELECT _id FROM " + WalletContract.IconEntry.TABLE_NAME +
                " WHERE name = ? AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(iconName))
        var id: Long = -1
        if (c.moveToFirst()) id = c.getLong(0)
        c.close()
        return id
    }

    private fun GetAccountIdByName(db: SQLiteDatabase, accName: String): Long {
        val c = db.rawQuery("SELECT _id FROM " + WalletContract.AccountEntry.TABLE_NAME +
                " WHERE " + AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME) + " AND " +
                WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", arrayOf<String>(accName))
        var id: Long = -1
        if (c.moveToFirst()) id = c.getLong(0)
        c.close()
        return id
    }

    private fun GetCurrencyIdByIso(db: SQLiteDatabase, isoCode: String): Long {
        val c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                " WHERE iso_code = ? AND " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(isoCode))
        var id: Long = -1
        if (c.moveToFirst()) id = c.getLong(0)
        c.close()
        return id
    }

    private fun ExecuteSqlFile(db: SQLiteDatabase, context: Context, fileName: String) {
        try {
            val reader = BufferedReader(
                    InputStreamReader(context.getAssets().open(fileName), "UTF-8"))
            val stmt = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                val trimmedLine = line.trim { it <= ' ' }
                if (trimmedLine.equals("next line", ignoreCase = true) || trimmedLine.startsWith("--")) {
                    continue
                }
                stmt.append(line).append('\n')
                if (trimmedLine.endsWith(";")) {
                    var sql = stmt.toString().trim { it <= ' ' }
                    sql = sql.substring(0, sql.length - 1).trim { it <= ' ' }
                    if (!sql.isEmpty()) {
                        try {
                            db.execSQL(sql)
                        } catch (e: Exception) {
                            Log.w("WalletFillData", "SQL skip [" + fileName + "]: " + e.message)
                        }
                    }
                    stmt.setLength(0)
                }
            }
            reader.close()
            db.execSQL("DROP TABLE IF EXISTS csv_import_operation_raw")
        } catch (e: IOException) {
            Log.e("WalletFillData", "Error reading " + fileName, e)
        }
    }

    // ── «Удалить всё» (ResetDataActivity) ─────────────────────────────────────────

    /**
     * То же самое, что раньше делала кнопка «Удалить все» на «Ещё» (MoreActivity) —
     * вынесено сюда, чтобы ResetDataActivity могло сочетать с очисткой icon/currency_pair.
     * Системные категории (Transfer/Transfer commission) переживают очистку — их нельзя
     * удалить этой кнопкой, см. AccountItem.SystemKind.
     *
     * `UPDATE ... deleted_at` — МЯГКОЕ удаление, не `DELETE`, тот же довод, что
     * у [WalletDbCRUD.DeleteOperation]: настоящий `DELETE` никогда не долетел бы
     * до сервера и других устройств push'ем (строки просто исчезли бы локально, серверу
     * сообщать об этом нечем), а следующий же pull притащил бы их обратно с сервера, как
     * будто их и не удаляли. `dirty` явно не ставим — форсит триггер `trg_*_dirty`
     * (schema/triggers/trg_sync_dirty.sql), раз эти UPDATE его не трогают.
     * `operation_link` НЕ трогаем — то же самое, за тем же доводом, что и в
     * `DeleteOperation`: раньше уходил через `ON DELETE CASCADE`, при мягком
     * удалении операция физически остаётся, и он остаётся тоже (безвредно: невидим, пока
     * невидима сама операция).
     *
     * `reseed` — завести дефолтные счета из seed.json ([InsertSeedAccounts],
     * тот же файл, что и на первом запуске); она сама пропускает имена, которые уже живы
     * (см. её комментарий) — актуально для Transfer/Transfer commission, переживших очистку.
     */
    @JvmStatic
    fun ResetOperationsAndAccounts(db: SQLiteDatabase, context: Context, reseed: Boolean) {
        val now: Long = System.currentTimeMillis()

        var cv = ContentValues()
        cv.put(WalletContract.OperationEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL", null)

        cv = ContentValues()
        cv.put(WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cv,
                WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL", null)

        cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = ? AND " +
                        WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(AccountItem.SystemKind.NONE.value.toString()))

        // Займы (debts, D14): признак уходит вместе с удалёнными кошельками — живой
        // account_debt у удалённого счёта синк отправил бы на несуществующий кошелёк.
        // Контрагенты остаются: это справочник людей, а не данные операций.
        cv = ContentValues()
        cv.put(WalletContract.AccountDebtEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.AccountDebtEntry.TABLE_NAME, cv,
                WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL", null)

        // Правила повторяющихся операций живут только локально (без deleted_at) и без
        // своих счетов смысла не имеют — удаляем их физически вместе со счетами.
        db.delete(WalletContract.RecurringOperationEntry.TABLE_NAME, null, null)

        if (reseed) InsertSeedAccounts(db, context)
    }

    /**
     * Очищает `icon` и, если `reseed`, заново сеет дефолтный набор (флаги валют +
     * иконки категорий, см. [SeedDefaultCurrencyFlagIcons]/[SeedDefaultCategoryIcons]).
     *
     * `account.icon_id`/`currency.icon_id` — `NOT NULL` FK на `icon._id`
     * (см. schema/table/account.sql, currency.sql): по всему приложению это читается как
     * инвариант "у счёта/валюты всегда есть живая иконка", и рендер нигде не проверяет
     * `getIcon()` на null (кроме одного места). Жёсткий `DELETE` упёрся бы в этот FK
     * (см. icon.sql), но здесь удаление мягкое (`UPDATE ... deleted_at`, тот же довод, что
     * у ResetOperationsAndAccounts — иначе не долетело бы до сервера push'ем), а оно FK не
     * проверяет — этим и объясняется репорт "удалил только иконки — у счёта странная иконка не
     * из списка": `icon_id` тихо продолжал указывать на строку, помеченную удалённой и
     * потому невидимую в списке иконок.
     *
     * Поэтому при `reseed=false` (пересевать нечем) чистим только то, чем сейчас никто не
     * пользуется — живой счёт/валюта, ссылающиеся на иконку, не дают её тронуть, инвариант не
     * рвётся. При `reseed=true` обратный сценарий: чистим всё (в т.ч. используемое — так
     * можно освежить и битую/устаревшую картинку у уже назначенной иконки), сеем дефолтный набор
     * и перепривязываем каждый ранее занятый счёт/валюту по снятому заранее имени иконки —
     * иначе после пересева autoincrement выдал бы новым строкам новые _id, не совпадающие со
     * старыми (та же ловушка, что чинили в InsertCurrencyPair/InsertCurrencyPairRate для
     * symbol/даты), и осиротил бы всё то же самое. Не нашедшееся по имени (кастомная иконка не
     * из seed.json) откатывается на "unknown" — единственный оставшийся приемлемый фолбэк раз
     * пересев уже случился.
     *
     * Иконки с сервера (`origin = 0`) не трогаются ни в одном режиме (android-wallet#50):
     * в push иконки не уходят, так что удаление осталось бы только на телефоне, pull его не
     * отменил бы, а синк остался бы без заместителей для своих иконок. Удалить их может только
     * администратор на сайте (icon-sync). Пересев не заводит свою иконку с именем живой
     * серверной ([SeedIcon]) — счета и валюты перепривязываются к серверной.
     */
    /** Своя иконка (`origin = 1`) — только такие удаляет [ResetIcons]. */
    private const val OWN_ICON = WalletContract.IconEntry.COLUMN_ORIGIN + " = " +
            WalletContract.IconEntry.ORIGIN_LOCAL

    @JvmStatic
    fun ResetIcons(db: SQLiteDatabase, context: Context, reseed: Boolean) {
        if (!reseed) {
            // uq_icon_name — частичный индекс (deleted_at IS NULL, см. WalletDbHelper.
            // createPartialUniqueIndexes) — не мешает: под условие NOT IN (...) попадают только
            // строки, которые прямо сейчас ничем не заняты.
            val delIcon = ContentValues()
            delIcon.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
            db.update(WalletContract.IconEntry.TABLE_NAME, delIcon,
                    WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL" +
                            " AND " + OWN_ICON +
                            " AND " + WalletContract.IconEntry._ID + " NOT IN (" +
                            "SELECT " + WalletContract.AccountEntry.COLUMN_ICON_ID +
                            " FROM " + WalletContract.AccountEntry.TABLE_NAME +
                            " WHERE " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL)" +
                            " AND " + WalletContract.IconEntry._ID + " NOT IN (" +
                            "SELECT " + WalletContract.CurrencyEntry.COLUMN_ICON_ID +
                            " FROM " + WalletContract.CurrencyEntry.TABLE_NAME +
                            " WHERE " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL)",
                    null)
            return
        }

        val accountIcons = HashMap<Long, String>()
        var c = db.rawQuery("SELECT a." + WalletContract.AccountEntry._ID + ", ic." + WalletContract.IconEntry.COLUMN_NAME +
                " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a" +
                " JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                " ON ic." + WalletContract.IconEntry._ID + " = a." + WalletContract.AccountEntry.COLUMN_ICON_ID, null)
        try {
            while (c.moveToNext()) accountIcons.put(c.getLong(0), c.getString(1))
        } finally {
            c.close()
        }

        val currencyIcons = HashMap<Long, String>()
        c = db.rawQuery("SELECT cu." + WalletContract.CurrencyEntry._ID + ", ic." + WalletContract.IconEntry.COLUMN_NAME +
                " FROM " + WalletContract.CurrencyEntry.TABLE_NAME + " cu" +
                " JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                " ON ic." + WalletContract.IconEntry._ID + " = cu." + WalletContract.CurrencyEntry.COLUMN_ICON_ID, null)
        try {
            while (c.moveToNext()) currencyIcons.put(c.getLong(0), c.getString(1))
        } finally {
            c.close()
        }

        // Мягкое удаление — тот же довод, что в ResetOperationsAndAccounts (иначе локальный
        // DELETE никогда не долетел бы до сервера/других устройств push'ем, а следующий pull
        // притащил бы иконки обратно). uq_icon_name — частичный индекс (deleted_at IS NULL,
        // см. WalletDbHelper.createPartialUniqueIndexes), поэтому реналив с теми же именами
        // ниже не конфликтует с только что помеченными как удалённые строками.
        val delIcon = ContentValues()
        delIcon.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        db.update(WalletContract.IconEntry.TABLE_NAME, delIcon,
                WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL AND " + OWN_ICON, null)

        SeedDefaultCategoryIcons(db, context)
        SeedDefaultCurrencyFlagIcons(db, context)

        for (e in accountIcons.entries) {
            var iconId: Long = GetIconIdByName(db, e.value)
            if (iconId < 0) iconId = GetIconIdByName(db, "unknown")
            if (iconId < 0) continue // и "unknown" не завёлся (нет такого drawable) — оставляем как есть
            val cv = ContentValues()
            cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId)
            db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                    WalletContract.AccountEntry._ID + "=?", arrayOf<String>(e.key.toString()))
        }
        for (e in currencyIcons.entries) {
            var iconId: Long = GetIconIdByName(db, e.value)
            if (iconId < 0) iconId = GetIconIdByName(db, "unknown")
            if (iconId < 0) continue
            val cv = ContentValues()
            cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId)
            db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                    WalletContract.CurrencyEntry._ID + "=?", arrayOf<String>(e.key.toString()))
        }
    }

    /**
     * Чинит уже испорченные ссылки `icon_id` на уже пострадавших устройствах —
     * реальная находка (2026-09-11, живой девайс): более старая версия [ResetIcons]
     * (до правки того же дня) мягко удаляла ВСЕ иконки без разбора, даже используемые живым
     * счётом/валютой — `icon_id` оставался указывать на теперь невидимую (мягко
     * удалённую) строку. `getIconById` её не находит, и то, что показывается вместо
     * неё, зависит от экрана: где-то системная заглушка Android
     * (`android.R.drawable.ic_menu_myplaces` — не из каталога приложения), где-то
     * `NullPointerException`. Реальный отчёт пользователя: кошелёк "Kenian" и валюта
     * "Хорватские куны" показывали "непонятную иконку, которой нет среди возможных в
     * каталоге". [ResetIcons] сама больше так не делает (защищает используемое от
     * удаления при `reseed=false` — см. её javadoc), но это не чинит то, что уже
     * испорчено на диске — этим занимается этот метод (см. `WalletDbHelper.onUpgrade`,
     * v78).
     *
     * Для каждой живой строки `account`/`currency`, чей `icon_id`
     * ссылается на несуществующую или мягко удалённую строку `icon`: сперва пробуем
     * найти живую иконку с ТЕМ ЖЕ именем (частый случай — имя пережило реналив
     * [ResetIcons]/пересев под другим `_id`), иначе — живую "unknown", иначе —
     * заводим свежую "unknown" из ресурса ([EnsureLiveUnknownIcon], гарантированный
     * фолбэк последней инстанции, раз ни то, ни другое не нашлось).
     */
    @JvmStatic
    fun RepairOrphanedIconLinks(db: SQLiteDatabase, context: Context) {
        val unknownIconId: Long = EnsureLiveUnknownIcon(db, context)
        repairIconLinksInTable(db, WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry._ID, WalletContract.AccountEntry.COLUMN_ICON_ID,
                WalletContract.AccountEntry.COLUMN_DELETED_AT, unknownIconId)
        repairIconLinksInTable(db, WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry._ID, WalletContract.CurrencyEntry.COLUMN_ICON_ID,
                WalletContract.CurrencyEntry.COLUMN_DELETED_AT, unknownIconId)
    }

    /**
     * Гарантирует хотя бы одну живую иконку "unknown" — фолбэк последней инстанции для
     * [repairIconLinksInTable] ниже (тот же смысл, что уже несёт "unknown" у
     * [GetIconIdByName]/[InsertSeedAccounts], только та не заводит её заново,
     * если и она вдруг не нашлась). Если живая уже есть — просто отдаёт её `_id`,
     * ничего не пишет.
     */
    @JvmStatic
    fun EnsureLiveUnknownIcon(db: SQLiteDatabase, context: Context): Long {
        val id: Long = GetIconIdByName(db, "unknown")
        if (id >= 0) return id
        appResources = context.getResources()
        appPackageName = context.getPackageName()
        val inserted: Long? = InsertIconEntry(db, "unknown", ICO_CATEGORY_ACCOUNT,
                appResources.getIdentifier("unknown", "drawable", appPackageName))
        return if (inserted != null) inserted else -1
    }

    /**
     * Один проход [RepairOrphanedIconLinks] по одной таблице (`account` или
     * `currency`) — сама таблица, её `_id`/`icon_id`/`deleted_at`
     * передаются параметрами, раз обе таблицы устроены одинаково в этой части.
     * `LEFT JOIN` без условия по `deleted_at` — чтобы достать имя иконки, ДАЖЕ
     * если она мягко удалена (нужно для реналива по имени ниже); "осиротела ли ссылка"
     * проверяет отдельно `WHERE`.
     */
    private fun repairIconLinksInTable(db: SQLiteDatabase, table: String, idColumn: String, iconIdColumn: String, deletedAtColumn: String, unknownIconId: Long) {
        val orphans = HashMap<Long, String?>() // строка -> имя мёртвой иконки (может быть null)
        val c = db.rawQuery("SELECT t." + idColumn + ", ic." + WalletContract.IconEntry.COLUMN_NAME +
                " FROM " + table + " t" +
                " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic" +
                " ON ic." + WalletContract.IconEntry._ID + " = t." + iconIdColumn +
                " WHERE t." + deletedAtColumn + " IS NULL" +
                " AND (ic." + WalletContract.IconEntry._ID + " IS NULL" +
                " OR ic." + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NOT NULL)", null)
        try {
            while (c.moveToNext()) orphans.put(c.getLong(0), if (c.isNull(1)) null else c.getString(1))
        } finally {
            c.close()
        }
        if (orphans.isEmpty()) return

        Log.w("WalletFillData", "RepairOrphanedIconLinks: " + orphans.size +
                " orphaned " + iconIdColumn + " in " + table + " — relinking")

        for (e in orphans.entries) {
            val orphanName = e.value
            var iconId: Long = if (orphanName != null) GetIconIdByName(db, orphanName) else -1L
            if (iconId < 0) iconId = unknownIconId
            val cv = ContentValues()
            cv.put(iconIdColumn, iconId)
            db.update(table, cv, idColumn + "=?", arrayOf<String>(e.key.toString()))
        }
    }

    /**
     * Мягко удаляет `currency_pair_rate`/`currency_pair` (тот же довод, что в
     * ResetOperationsAndAccounts/ResetIcons — настоящий DELETE не долетел бы до сервера как
     * удаление) и, если `reseed`, заново сеет дефолтные пары+курсы
     * ([SeedDefaultCurrencyPairsAndRates] — сама пропускает уже живой symbol и не находит
     * мягко удалённый, см. её и [InsertCurrencyPair]/[InsertCurrencyPairRate]).
     * Валюты не трогаются (пары ссылаются на них по iso_code, не по _id), так что релинк, в
     * отличие от [ResetIcons], не нужен.
     */
    @JvmStatic
    fun ResetCurrencyPairsAndRates(db: SQLiteDatabase, context: Context, reseed: Boolean) {
        val now: Long = System.currentTimeMillis()

        var cv = ContentValues()
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL", null)

        cv = ContentValues()
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT, now)
        db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL", null)

        if (reseed) SeedDefaultCurrencyPairsAndRates(db, context)
    }
}
