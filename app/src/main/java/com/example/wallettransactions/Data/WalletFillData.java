package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DateUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


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
public class WalletFillData {
    private static Resources Resources;
    /** Пакет приложения — вместе с {@link #Resources} нужен для {@link #ResolveDrawableId}. */
    private static String PackageName;
    public static final int ICO_CATEGORY_FLAGS = 1;
    public static final int ICO_CATEGORY_ACCOUNT = 2;

    /**
     * Дефолтные иконки/валюты/валютные пары+курсы/счета для пустой БД (см. {@link
     * #InsertFirstDictionary}) и частичного реналива (см. ResetDataActivity) — единый
     * источник правды {@code assets/seed.json}, а не разбросанные по этому классу вызовы
     * {@code InsertIconEntry}/{@code InsertCurrencyEntry} с {@code R.drawable.*}. Чтобы
     * добавить/убрать иконку, валюту, валютную пару, курс или дефолтный счёт — правишь
     * только этот JSON (плюс сам файл иконки в {@code res/drawable}, если он новый) и
     * пересобираешь; менять код в этом классе для этого больше не нужно.
     * <p>
     * Схема (все секции опциональны, но в штатном файле присутствуют все):
     * <pre>
     * {
     *   "category_icons": [{"name": "Bar", "resource": "acc_beer"}],
     *   "currencies": [{"name": "Euro", "iso": "EUR", "symbol": "€",
     *                    "icon": "Flag Euro", "icon_resource": "flag_eur", "precision": 2}],
     *   "flag_icons": [{"name": "Flag ALL", "resource": "flag_albania"}],
     *   "currency_pairs": [{"symbol": "EURUSD", "base": "EUR", "quote": "USD"}],
     *   "rates": [{"symbol": "EURUSD", "date": "2020-08-20", "rate": 1.18023}],
     *   "accounts": [{"names": {"ru": "Такси", "en": "Taxi"}, "icon": "Taxi"},
     *                {"name": "Transfer", "icon": "Transfer"}]
     * }
     * </pre>
     * Штатный набор (openspec new-user-default-set, spec starter-set) — без кошельков,
     * курсов и личных названий; "rates" и "accounts[].currency_iso" поддерживаются, но в
     * файле не используются. Категория — пара имён "names", язык — {@link
     * CurrencyNames#seedLanguage}; название валюты — {@link CurrencyNames#displayName} по
     * коду, "name" — запасное. Каждая строка набора — с меткой {@code from_seed = 1}.
     * "resource"/"icon_resource" — имя файла в {@code res/drawable} без расширения,
     * разрешается в id через {@link #ResolveDrawableId} (не {@code R.drawable.*} — иначе
     * добавление иконки снова требовало бы правки Java). "accounts[].currency_iso" ссылается
     * на валюту, уже заведённую секцией "currencies" этого же файла (см. {@link
     * #InsertSeedAccounts}), а не несёт её данные повторно, в отличие от формата
     * пользовательского JSON-импорта ({@link WalletImportData#FillOperationDataFromJson}) —
     * там счетов с новой валютой единицы, здесь львиная доля валют не привязана ни к
     * одному дефолтному счёту вовсе.
     */
    private static JSONObject LoadSeed(Context context) {
        try (InputStream in = context.getAssets().open("seed.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | JSONException e) {
            // Файл — часть приложения (assets), а не пользовательский ввод: дойти сюда
            // может только сломанная сборка. Пустой объект — секции ниже это optJSONArray,
            // так что сеятели просто ничего не заведут, а не упадут на пустой БД.
            Log.e("WalletFillData", "Cant read seed.json", e);
            return new JSONObject();
        }
    }

    /**
     * Имя drawable-ресурса (без расширения, как в {@code res/drawable}) → id, без {@code
     * R.drawable.*} — см. {@link #LoadSeed}. "unknown" — тот же фолбэк по имени, что уже
     * используют {@link #GetIconIdByName}/{@code InsertOrUpdateAccount} для не найденной иконки.
     */
    private static int ResolveDrawableId(String resourceName) {
        int id = Resources.getIdentifier(resourceName, "drawable", PackageName);
        if (id != 0) return id;
        Log.w("WalletFillData", "seed.json: drawable not found: " + resourceName);
        return Resources.getIdentifier("unknown", "drawable", PackageName);
    }




    public static int GetAffectedRowsCount(SQLiteDatabase db)
    {
        Cursor res = db.rawQuery("select changes() 'affected_rows'", null);
        res.moveToFirst();
        return res.getInt(res.getColumnIndex("affected_rows"));
    }



    private static Long InsertIconEntry(SQLiteDatabase db, String name, long iconType, int resource) {
        ContentValues values = new ContentValues();
        values.put(WalletContract.IconEntry.COLUMN_NAME, name);
        values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, iconType);

        Bitmap bitmap = BitmapFactory.decodeResource(Resources, resource);
        if (bitmap == null) {
            // Vector drawable — render onto a Canvas
            Drawable d = Resources.getDrawable(resource, null);
            if (d != null) {
                int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : 96;
                int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96;
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, w, h);
                d.draw(new Canvas(bitmap));
            }
        }
        if (bitmap == null) return -1L;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        byte[] img = bos.toByteArray();

        values.put(WalletContract.IconEntry.COLUMN_IMAGE, img);
        // Иконка из seed.json заведена здесь, на сервере её uuid нет — своя (server-managed-icons):
        // в push вместо неё уходит заместитель, одноимённая с сервера её не дублирует (SyncEngine.applyIcon).
        values.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL);

        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, values);
    }

    /** {@code seed.json}-вариант — имя drawable-ресурса вместо {@code R.drawable.*} id, см. {@link #ResolveDrawableId}. */
    private static Long InsertIconEntry(SQLiteDatabase db, String name, long iconType, String resourceName) {
        return InsertIconEntry(db, name, iconType, ResolveDrawableId(resourceName));
    }

    /** Валюта набора — с меткой «из набора» (new-user-default-set, design D3). */
    private static long InsertCurrencyEntry(SQLiteDatabase db, String curName, String isoName, String symbol, Long iconId, int precision) {
        ContentValues values = new ContentValues();
        values.put(WalletContract.CurrencyEntry.COLUMN_NAME, curName);
        values.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, isoName);
        values.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId);
        values.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol);
        values.put(WalletContract.CurrencyEntry.COLUMN_PRECISION, precision);
        values.put(WalletContract.CurrencyEntry.COLUMN_FROM_SEED, 1);
        return db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, values);
    }


    /** Счёт набора — с меткой «из набора» (new-user-default-set, design D3). */
    private static long InsertAccountEntry(SQLiteDatabase db, String accName, String accComment, long accIconId) {
        ContentValues values = new ContentValues();
        values.put(WalletContract.AccountEntry.COLUMN_NAME, accName);
        values.put(WalletContract.AccountEntry.COLUMN_COMMENT, accComment);
        values.put(WalletContract.AccountEntry.COLUMN_ICON_ID, accIconId);
        values.put(WalletContract.AccountEntry.COLUMN_FROM_SEED, 1);
        return db.insert(WalletContract.AccountEntry.TABLE_NAME, null, values);
    }


    private static long UpdateIconAccountEntry(SQLiteDatabase db, String accName, long accIconId) {
        ContentValues values = new ContentValues();
        values.put(WalletContract.AccountEntry.COLUMN_ICON_ID, accIconId);
        return db.update(WalletContract.AccountEntry.TABLE_NAME, values, WalletContract.AccountEntry.COLUMN_NAME +"=?", new String[]{ accName });
    }

    private static int GetAffectedRecordAmount(SQLiteDatabase db){
        Cursor res = db.rawQuery("select changes() 'affected_rows'", null);
        res.moveToFirst();
        return res.getInt(res.getColumnIndex("affected_rows"));
    }

    /**
     * Публичный (см. {@link com.example.wallettransactions.Data.WalletImportData} —
     * JSON-импорт теперь умеет заводить и валютные пары, не только курсы к ним).
     *
     * Раньше был "insert or replace": для SQLite это snapshot-семантика "удалить
     * старую строку целиком и вставить новую" — то же самое, что уже чинили в
     * {@link #InsertCurrencyPairRate} (см. её javadoc про v72). Повторный импорт
     * уже существующего symbol перегенерировал бы и _id, и uuid, осиротив все
     * currency_pair_rate, которые на этот _id ссылаются. Раз эта функция теперь
     * вызывается не только один раз при первом запуске (сид), а на каждый JSON-
     * импорт, столкнуться с этим стало реально, а не гипотетически — поэтому: уже
     * существующий symbol просто пропускаем (base/quote для существующей пары не
     * трогаем), заводим только то, чего ещё нет.
     */
    public static int InsertCurrencyPair(SQLiteDatabase db, String curName, String curBase, String curQuote){
        // deleted_at IS NULL — символ мягко удалённой пары не занят: уникальность
        // теперь по частичному индексу uq_currency_pair_symbol (см. WalletDbHelper.
        // createPartialUniqueIndexes), не по голому column-level UNIQUE. Без этого
        // условия реналив после ResetDataActivity/WalletFillData.ResetCurrencyPairsAndRates
        // тихо не заводил бы новую пару — эта проверка видела бы старую (уже
        // мягко удалённую) строку с тем же symbol и решала бы, что заводить нечего.
        Cursor c = db.rawQuery("SELECT " + WalletContract.CurrencyPairEntry._ID
                + " FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME
                + " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?"
                + " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{curName});
        try {
            if (c.moveToFirst()) return 0;
        } finally {
            c.close();
        }

        String sql = "insert into currency_pair (symbol, base_currency_id,quote_currency_id)\n" +
                "select sym, cb.\"_id\" , cq.\"_id\"  from\n" +
                "(select ? sym, ? cb, ? cq) t\n" +
                "join currency cb \n" +
                "on cb.iso_code = t.cb \n" +
                "join currency cq \n" +
                "on cq.iso_code = t.cq";

        db.execSQL(sql, new String[]{curName, curBase, curQuote});

        return GetAffectedRecordAmount(db);
    }

    /**
     * v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — голый {@code INSERT OR
     * REPLACE}, для SQLite это "удалить старую строку целиком и вставить новую" —
     * заново сгенерированный uuid на повторную загрузку того же курса раздувал бы
     * поток синка дублями. Тот же приём, что {@link WalletDbCRUD#InsertOrReplaceRate}:
     * нашлась строка на эту (пара, дата) — обновляем (uuid не трогаем, dirty
     * форсит триггер), не нашлась — вставляем с новым uuid.
     */
    public static int InsertCurrencyPairRate(SQLiteDatabase db, String curName, Date date, Double rate){
        // deleted_at IS NULL — тот же довод, что в InsertCurrencyPair: не находить
        // мягко удалённую пару и не вешать на неё новый курс, который тогда нигде
        // не показывался бы (UI фильтрует пары по deleted_at IS NULL).
        Cursor c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyPairEntry.TABLE_NAME
                + " WHERE " + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=?"
                + " AND " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{curName});
        long pairId;
        try {
            if (!c.moveToFirst()) return 0;
            pairId = c.getLong(0);
        } finally {
            c.close();
        }

        // DateToDateStr (не DateToStr) — курс это величина на день, время в
        // колонке не несёт смысла (см. wallet-api/schema/table/currency_pair_rate.sql,
        // тот же довод на сервере). Раньше здесь был DateToStr ("yyyy-MM-dd
        // HH:mm") — тот самый источник локальных дублей одной даты с разным
        // временем, из-за которого push бился об uq_currency_pair_rate_ledger
        // (см. org/docs/TODO_mobile_sync.md).
        String dateStr = DateUtils.DateToDateStr(date);
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_RATE, rate);
        // Заодно нормализует date у найденной строки (см. substr-матч ниже) —
        // если она ещё несёт легаси-время, следующий же импорт/обновление этой
        // даты приводит её к чистому "yyyy-MM-dd", без отдельной миграции.
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, dateStr);

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
        int updated = db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND "
                        + "substr(" + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10)=? AND "
                        + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(pairId), dateStr});
        if (updated > 0) return updated;

        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE, pairId);
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DATE, dateStr);
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_UUID, java.util.UUID.randomUUID().toString());
        long inserted = db.insert(WalletContract.CurrencyPairRateEntry.TABLE_NAME, null, cv);
        return inserted > 0 ? 1 : 0;
    }

    /**
     * Валюты + их флаги (парой) из секции "currencies" {@code seed.json} — годится только
     * на чистой БД (первый запуск), см. {@link #SeedDefaultCurrencyFlagIcons} про реналив
     * одних иконок без пересоздания currency.
     */
    public static void FillCurrenis(SQLiteDatabase db, Context context) {
        JSONArray currencies = LoadSeed(context).optJSONArray("currencies");
        if (currencies == null) return;
        // Название — от Android по коду на языке посева, "name" из файла — запасное (design D10).
        java.util.Locale locale = new java.util.Locale(CurrencyNames.seedLanguage(context));

        for (int i = 0; i < currencies.length(); i++) {
            try {
                JSONObject c = currencies.getJSONObject(i);
                long iconId = InsertIconEntry(db, c.getString("icon"), ICO_CATEGORY_FLAGS, c.getString("icon_resource"));
                String iso = c.getString("iso");
                InsertCurrencyEntry(db, CurrencyNames.displayName(iso, c.getString("name"), locale), iso,
                        c.getString("symbol"), iconId, c.optInt("precision", 2));
            } catch (JSONException e) {
                Log.e("WalletFillData", "seed.json currencies#" + i, e);
            }
        }
        InsertSpareFlagIcons(db, context);
    }

    /**
     * Флаги без валюты из секции "flag_icons" {@code seed.json}: валюты нет в наборе, а флаг
     * остаётся в списке иконок для своих валют. Имена — прежние ("Flag ALL" и др.), по ним
     * телефон сопоставляет иконку с одноимённой серверной (icon-sync).
     */
    private static void InsertSpareFlagIcons(SQLiteDatabase db, Context context) {
        JSONArray flags = LoadSeed(context).optJSONArray("flag_icons");
        if (flags == null) return;
        for (int i = 0; i < flags.length(); i++) {
            try {
                JSONObject f = flags.getJSONObject(i);
                InsertIconEntry(db, f.getString("name"), ICO_CATEGORY_FLAGS, f.getString("resource"));
            } catch (JSONException e) {
                Log.e("WalletFillData", "seed.json flag_icons#" + i, e);
            }
        }
    }

    /**
     * Только флаги валют (иконки, не сами валюты) — {@link #FillCurrenis} заводит их в
     * паре с currency, что на чистой БД (первый запуск) верно, но не годится для реналива
     * после очистки {@code icon} без пересоздания {@code currency} (см. ResetDataActivity):
     * там валюты остаются как есть, и заново создавать нужно только иконки, с теми же
     * именами, что и здесь ("Flag EUR" и т.д.), чтобы релинк по имени их нашёл.
     */
    public static void SeedDefaultCurrencyFlagIcons(SQLiteDatabase db, Context context) {
        Resources = context.getResources();
        PackageName = context.getPackageName();
        JSONArray currencies = LoadSeed(context).optJSONArray("currencies");
        if (currencies == null) return;

        for (int i = 0; i < currencies.length(); i++) {
            try {
                JSONObject c = currencies.getJSONObject(i);
                InsertIconEntry(db, c.getString("icon"), ICO_CATEGORY_FLAGS, c.getString("icon_resource"));
            } catch (JSONException e) {
                Log.e("WalletFillData", "seed.json currencies#" + i, e);
            }
        }
        InsertSpareFlagIcons(db, context);
    }

    /**
     * Дефолтные валютные пары и их курсы — вынесено из {@link #InsertFirstDictionary} в
     * отдельный метод, чтобы им же можно было пользоваться при реналиве после очистки
     * currency_pair/currency_pair_rate без пересоздания остального (см. ResetDataActivity).
     */
    public static void SeedDefaultCurrencyPairsAndRates(SQLiteDatabase db, Context context) {
        JSONObject seed = LoadSeed(context);

        JSONArray pairs = seed.optJSONArray("currency_pairs");
        if (pairs != null) {
            for (int i = 0; i < pairs.length(); i++) {
                try {
                    JSONObject p = pairs.getJSONObject(i);
                    String symbol = p.getString("symbol");
                    if (InsertCurrencyPair(db, symbol, p.getString("base"), p.getString("quote")) > 0) {
                        markPairFromSeed(db, symbol);
                    }
                } catch (JSONException e) {
                    Log.e("WalletFillData", "seed.json currency_pairs#" + i, e);
                }
            }
        }

        JSONArray rates = seed.optJSONArray("rates");
        if (rates != null) {
            for (int i = 0; i < rates.length(); i++) {
                try {
                    JSONObject r = rates.getJSONObject(i);
                    InsertCurrencyPairRate(db, r.getString("symbol"),
                            DateUtils.StrToDate(r.getString("date")), r.getDouble("rate"));
                } catch (JSONException e) {
                    Log.e("WalletFillData", "seed.json rates#" + i, e);
                }
            }
        }
    }


    /**
     * Метка «из набора» у только что заведённой пары. {@link #InsertCurrencyPair} общий с
     * JSON-импортом, поэтому метку ставит не он. Строка новая ({@code dirty = 1}), так что
     * триггер {@code trg_currency_pair_dirty} ничего не меняет.
     */
    private static void markPairFromSeed(SQLiteDatabase db, String symbol) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_FROM_SEED, 1);
        db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + "=? AND "
                        + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{symbol});
    }

    /**
     * Дефолтные иконки категорий (плюс "unknown") — вынесено из {@link #InsertFirstDictionary}
     * в отдельный метод по тому же доводу, что {@link #SeedDefaultCurrencyFlagIcons}: нужен
     * для реналива после очистки {@code icon} без пересоздания остального.
     */
    public static void SeedDefaultCategoryIcons(SQLiteDatabase db, Context context) {
        Resources = context.getResources();
        PackageName = context.getPackageName();
        JSONArray icons = LoadSeed(context).optJSONArray("category_icons");
        if (icons == null) return;

        for (int i = 0; i < icons.length(); i++) {
            try {
                JSONObject ic = icons.getJSONObject(i);
                InsertIconEntry(db, ic.getString("name"), ICO_CATEGORY_ACCOUNT, ic.getString("resource"));
            } catch (JSONException e) {
                Log.e("WalletFillData", "seed.json category_icons#" + i, e);
            }
        }
    }

    /**
     * Заводит иконку-заглушку "unknown", если её нет (см. Singleton.loadIconList, issue #18).
     * Только её, а не все {@link #SeedDefaultCategoryIcons}: пользователь, очистивший базу без
     * пересева, справочников не просил — заглушка нужна коду, а не ему.
     *
     * @return true — иконку пришлось завести.
     */
    public static boolean EnsureUnknownIcon(SQLiteDatabase db, Context context) {
        Resources = context.getResources();
        PackageName = context.getPackageName();
        if (GetIconIdByName(db, "unknown") >= 0) return false;
        return InsertIconEntry(db, "unknown", ICO_CATEGORY_ACCOUNT, "unknown") > 0;
    }

    public static void InsertFirstDictionary(SQLiteDatabase db, Context context){
        Resources = context.getResources();
        PackageName = context.getPackageName();
        SeedDefaultCategoryIcons(db, context);
        FillCurrenis(db, context); // валюты + их флаги парой — годится только на чистой БД (см. SeedDefaultCurrencyFlagIcons)
        SeedDefaultCurrencyPairsAndRates(db, context);
        InsertSeedAccounts(db, context);
        stampSystemCategories(db);
    }

    /**
     * v75 — проставляет {@code system_kind} только что вставленным из seed.json строкам
     * «Transfer»/«Transfer commission» ({@code InsertSeedAccounts} выше делает обычный
     * {@code INSERT}, без этой колонки). ТО ЖЕ САМОЕ, что бэкфилл шага v75 в
     * {@code WalletDbHelper.onUpgrade} (сознательно продублировано — сидирование новой базы
     * и апгрейд существующей идут разными путями и в разных классах), и второе (и
     * последнее) легитимное место сравнения по имени: ровно момент превращения старого
     * неявного состояния (имя) в новое явное ({@code system_kind}), дальше по коду имя
     * больше нигде не участвует в определении системности.
     */
    private static void stampSystemCategories(SQLiteDatabase db) {
        db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME
                + " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = "
                + AccountItem.SystemKind.TRANSFER.getValue()
                + " WHERE " + WalletContract.AccountEntry.COLUMN_NAME
                + " = '" + AccountItem.SYSTEM_TRANSFER_NAME + "' COLLATE NOCASE");
        db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME
                + " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = "
                + AccountItem.SystemKind.TRANSFER_COMMISSION.getValue()
                + " WHERE " + WalletContract.AccountEntry.COLUMN_NAME
                + " = '" + AccountItem.SYSTEM_COMMISSION_NAME + "' COLLATE NOCASE");
    }

    /**
     * Дефолтные счета из секции "accounts" {@code seed.json} (раньше — отдельный
     * {@code accounts.csv}, теперь часть единого файла, см. {@link #LoadSeed}).
     * "currency_iso" ссылается на валюту, уже заведённую секцией "currencies" того же
     * файла ({@link #FillCurrenis} вызывается раньше, см. порядок в {@link
     * #InsertFirstDictionary}) — если такой валюты нет, счёт заводится обычной
     * категорией без баланса, как и раньше при пустом столбце CurrencyIso в CSV.
     */
    private static void InsertSeedAccounts(SQLiteDatabase db, Context context) {
        JSONArray accounts = LoadSeed(context).optJSONArray("accounts");
        if (accounts == null) return;
        String language = CurrencyNames.seedLanguage(context);

        for (int i = 0; i < accounts.length(); i++) {
            try {
                JSONObject a = accounts.getJSONObject(i);
                String accName = seedAccountName(a, language).trim();
                if (accName.isEmpty()) continue;

                // Живой (deleted_at IS NULL) счёт с этим именем уже есть — пропускаем: голый
                // INSERT ниже упал бы на uq_account_name (WalletDbHelper.createPartialUniqueIndexes),
                // если этот метод зовут не только на чистой БД (см. ResetOperationsAndAccounts —
                // системные категории Transfer/Transfer commission, которые не удаляются, тоже
                // есть в этом seed.json).
                if (GetAccountIdByName(db, accName) > 0) continue;

                String iconName = a.optString("icon", "unknown");
                long iconId = GetIconIdByName(db, iconName);
                if (iconId < 0) iconId = GetIconIdByName(db, "unknown");

                long accId = InsertAccountEntry(db, accName, a.optString("comment", accName), iconId);

                String currencyIso = a.optString("currency_iso", "");
                if (!currencyIso.isEmpty() && accId > 0) {
                    long curId = GetCurrencyIdByIso(db, currencyIso);
                    if (curId > 0) {
                        // uuid — тот же, что у только что вставленного account (не свежий
                        // случайный DEFAULT): server/wallet-api/schema/table/account_operating.sql
                        // требует account_operating.uuid = account.uuid того же счёта (общий
                        // первичный ключ на уровне синка, не только локального _id), иначе
                        // push такого кошелька упал бы FK-ошибкой (fk_account_operating_account).
                        ContentValues values = new ContentValues();
                        values.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, curId);
                        values.put(WalletContract.AccountOperatingEntry._ID, accId);
                        values.put(WalletContract.AccountOperatingEntry.COLUMN_UUID, GetUuidById(db, accId));
                        db.insert(WalletContract.AccountOperatingEntry.TABLE_NAME, null, values);

                        // InsertAccountEntry выше всегда заводит строку как категорию
                        // (kind по умолчанию 0) — в кошелёк её превращает именно эта
                        // строка account_operating, см. javadoc AccountItem.Kind.
                        ContentValues kindValues = new ContentValues();
                        kindValues.put(WalletContract.AccountEntry.COLUMN_KIND, AccountItem.Kind.WALLET.getValue());
                        db.update(WalletContract.AccountEntry.TABLE_NAME, kindValues,
                                WalletContract.AccountEntry._ID + "=?", new String[]{String.valueOf(accId)});
                    }
                }
            } catch (JSONException e) {
                Log.e("WalletFillData", "seed.json accounts#" + i, e);
            }
        }
    }

    /**
     * Имя счёта набора: {@code names[language]}, иначе {@code names.en}, иначе {@code name}
     * (системные Transfer/Transfer commission — одно имя на всех языках, design D2/D10).
     */
    static String seedAccountName(JSONObject account, String language) throws JSONException {
        JSONObject names = account.optJSONObject("names");
        if (names != null) {
            String localized = names.optString(language, "");
            return localized.isEmpty() ? names.getString("en") : localized;
        }
        return account.getString("name");
    }

    /** uuid только что вставленного/существующего account по его _id — см. InsertSeedAccounts. */
    private static String GetUuidById(SQLiteDatabase db, long accId) {
        Cursor c = db.rawQuery("SELECT uuid FROM " + WalletContract.AccountEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountEntry._ID + "=?", new String[]{String.valueOf(accId)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    // deleted_at IS NULL в обоих lookup'ах ниже — то же самое, что уже стоит на
    // FindAccountIdByName/аналогах: имя/iso мягко удалённой строки не занято (см.
    // WalletDbHelper.createPartialUniqueIndexes), и релинк после ResetIcons должен
    // найти свежепосеянную живую иконку, а не старую мягко удалённую с тем же именем.
    private static long GetIconIdByName(SQLiteDatabase db, String iconName) {
        Cursor c = db.rawQuery("SELECT _id FROM " + WalletContract.IconEntry.TABLE_NAME
                + " WHERE name = ? AND " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{iconName});
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    private static long GetAccountIdByName(SQLiteDatabase db, String accName) {
        Cursor c = db.rawQuery("SELECT _id FROM " + WalletContract.AccountEntry.TABLE_NAME
                + " WHERE " + AccountNames.sameSql(WalletContract.AccountEntry.COLUMN_NAME) + " AND "
                + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL", new String[]{accName});
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    private static long GetCurrencyIdByIso(SQLiteDatabase db, String isoCode) {
        Cursor c = db.rawQuery("SELECT _id FROM " + WalletContract.CurrencyEntry.TABLE_NAME
                + " WHERE iso_code = ? AND " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{isoCode});
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    private static void ExecuteSqlFile(SQLiteDatabase db, Context context, String fileName) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(fileName), "UTF-8"));
            StringBuilder stmt = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.equalsIgnoreCase("next line") || trimmedLine.startsWith("--")) {
                    continue;
                }
                stmt.append(line).append('\n');
                if (trimmedLine.endsWith(";")) {
                    String sql = stmt.toString().trim();
                    sql = sql.substring(0, sql.length() - 1).trim();
                    if (!sql.isEmpty()) {
                        try {
                            db.execSQL(sql);
                        } catch (Exception e) {
                            Log.w("WalletFillData", "SQL skip [" + fileName + "]: " + e.getMessage());
                        }
                    }
                    stmt.setLength(0);
                }
            }
            reader.close();
            db.execSQL("DROP TABLE IF EXISTS csv_import_operation_raw");
        } catch (IOException e) {
            Log.e("WalletFillData", "Error reading " + fileName, e);
        }
    }

    // ── «Удалить всё» (ResetDataActivity) ─────────────────────────────────────────

    /**
     * То же самое, что раньше делала кнопка «Удалить все» на «Ещё» (MoreActivity) —
     * вынесено сюда, чтобы ResetDataActivity могло сочетать с очисткой icon/currency_pair.
     * Системные категории (Transfer/Transfer commission) переживают очистку — их нельзя
     * удалить этой кнопкой, см. AccountItem.SystemKind.
     * <p>
     * {@code UPDATE ... deleted_at} — МЯГКОЕ удаление, не {@code DELETE}, тот же довод, что
     * у {@link WalletDbCRUD#DeleteOperation}: настоящий {@code DELETE} никогда не долетел бы
     * до сервера и других устройств push'ем (строки просто исчезли бы локально, серверу
     * сообщать об этом нечем), а следующий же pull притащил бы их обратно с сервера, как
     * будто их и не удаляли. {@code dirty} явно не ставим — форсит триггер {@code trg_*_dirty}
     * (schema/triggers/trg_sync_dirty.sql), раз эти UPDATE его не трогают.
     * {@code operation_link} НЕ трогаем — то же самое, за тем же доводом, что и в
     * {@code DeleteOperation}: раньше уходил через {@code ON DELETE CASCADE}, при мягком
     * удалении операция физически остаётся, и он остаётся тоже (безвредно: невидим, пока
     * невидима сама операция).
     * <p>
     * {@code reseed} — завести дефолтные счета из seed.json ({@link #InsertSeedAccounts},
     * тот же файл, что и на первом запуске); она сама пропускает имена, которые уже живы
     * (см. её комментарий) — актуально для Transfer/Transfer commission, переживших очистку.
     */
    public static void ResetOperationsAndAccounts(SQLiteDatabase db, Context context, boolean reseed) {
        long now = System.currentTimeMillis();

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.OperationEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.OperationEntry.TABLE_NAME, cv,
                WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL", null);

        cv = new ContentValues();
        cv.put(WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, cv,
                WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL", null);

        cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = ? AND "
                        + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{String.valueOf(AccountItem.SystemKind.NONE.getValue())});

        // Правила повторяющихся операций живут только локально (без deleted_at) и без
        // своих счетов смысла не имеют — удаляем их физически вместе со счетами.
        db.delete(WalletContract.RecurringOperationEntry.TABLE_NAME, null, null);

        if (reseed) InsertSeedAccounts(db, context);
    }

    /**
     * Очищает {@code icon} и, если {@code reseed}, заново сеет дефолтный набор (флаги валют +
     * иконки категорий, см. {@link #SeedDefaultCurrencyFlagIcons}/{@link #SeedDefaultCategoryIcons}).
     * <p>
     * {@code account.icon_id}/{@code currency.icon_id} — {@code NOT NULL} FK на {@code icon._id}
     * (см. schema/table/account.sql, currency.sql): по всему приложению это читается как
     * инвариант "у счёта/валюты всегда есть живая иконка", и рендер нигде не проверяет
     * {@code getIcon()} на null (кроме одного места). Жёсткий {@code DELETE} упёрся бы в этот FK
     * (см. icon.sql), но здесь удаление мягкое ({@code UPDATE ... deleted_at}, тот же довод, что
     * у ResetOperationsAndAccounts — иначе не долетело бы до сервера push'ем), а оно FK не
     * проверяет — этим и объясняется репорт "удалил только иконки — у счёта странная иконка не
     * из списка": {@code icon_id} тихо продолжал указывать на строку, помеченную удалённой и
     * потому невидимую в списке иконок.
     * <p>
     * Поэтому при {@code reseed=false} (пересевать нечем) чистим только то, чем сейчас никто не
     * пользуется — живой счёт/валюта, ссылающиеся на иконку, не дают её тронуть, инвариант не
     * рвётся. При {@code reseed=true} обратный сценарий: чистим всё (в т.ч. используемое — так
     * можно освежить и битую/устаревшую картинку у уже назначенной иконки), сеем дефолтный набор
     * и перепривязываем каждый ранее занятый счёт/валюту по снятому заранее имени иконки —
     * иначе после пересева autoincrement выдал бы новым строкам новые _id, не совпадающие со
     * старыми (та же ловушка, что чинили в InsertCurrencyPair/InsertCurrencyPairRate для
     * symbol/даты), и осиротил бы всё то же самое. Не нашедшееся по имени (кастомная иконка не
     * из seed.json) откатывается на "unknown" — единственный оставшийся приемлемый фолбэк раз
     * пересев уже случился.
     */
    public static void ResetIcons(SQLiteDatabase db, Context context, boolean reseed) {
        if (!reseed) {
            // uq_icon_name — частичный индекс (deleted_at IS NULL, см. WalletDbHelper.
            // createPartialUniqueIndexes) — не мешает: под условие NOT IN (...) попадают только
            // строки, которые прямо сейчас ничем не заняты.
            ContentValues delIcon = new ContentValues();
            delIcon.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
            db.update(WalletContract.IconEntry.TABLE_NAME, delIcon,
                    WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL"
                            + " AND " + WalletContract.IconEntry._ID + " NOT IN ("
                            + "SELECT " + WalletContract.AccountEntry.COLUMN_ICON_ID
                            + " FROM " + WalletContract.AccountEntry.TABLE_NAME
                            + " WHERE " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL)"
                            + " AND " + WalletContract.IconEntry._ID + " NOT IN ("
                            + "SELECT " + WalletContract.CurrencyEntry.COLUMN_ICON_ID
                            + " FROM " + WalletContract.CurrencyEntry.TABLE_NAME
                            + " WHERE " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL)",
                    null);
            return;
        }

        Map<Long, String> accountIcons = new HashMap<>();
        Cursor c = db.rawQuery("SELECT a." + WalletContract.AccountEntry._ID + ", ic." + WalletContract.IconEntry.COLUMN_NAME
                + " FROM " + WalletContract.AccountEntry.TABLE_NAME + " a"
                + " JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic"
                + " ON ic." + WalletContract.IconEntry._ID + " = a." + WalletContract.AccountEntry.COLUMN_ICON_ID, null);
        try {
            while (c.moveToNext()) accountIcons.put(c.getLong(0), c.getString(1));
        } finally {
            c.close();
        }

        Map<Long, String> currencyIcons = new HashMap<>();
        c = db.rawQuery("SELECT cu." + WalletContract.CurrencyEntry._ID + ", ic." + WalletContract.IconEntry.COLUMN_NAME
                + " FROM " + WalletContract.CurrencyEntry.TABLE_NAME + " cu"
                + " JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic"
                + " ON ic." + WalletContract.IconEntry._ID + " = cu." + WalletContract.CurrencyEntry.COLUMN_ICON_ID, null);
        try {
            while (c.moveToNext()) currencyIcons.put(c.getLong(0), c.getString(1));
        } finally {
            c.close();
        }

        // Мягкое удаление — тот же довод, что в ResetOperationsAndAccounts (иначе локальный
        // DELETE никогда не долетел бы до сервера/других устройств push'ем, а следующий pull
        // притащил бы иконки обратно). uq_icon_name — частичный индекс (deleted_at IS NULL,
        // см. WalletDbHelper.createPartialUniqueIndexes), поэтому реналив с теми же именами
        // ниже не конфликтует с только что помеченными как удалённые строками.
        ContentValues delIcon = new ContentValues();
        delIcon.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        db.update(WalletContract.IconEntry.TABLE_NAME, delIcon,
                WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL", null);

        SeedDefaultCategoryIcons(db, context);
        SeedDefaultCurrencyFlagIcons(db, context);

        for (Map.Entry<Long, String> e : accountIcons.entrySet()) {
            long iconId = GetIconIdByName(db, e.getValue());
            if (iconId < 0) iconId = GetIconIdByName(db, "unknown");
            if (iconId < 0) continue; // и "unknown" не завёлся (нет такого drawable) — оставляем как есть
            ContentValues cv = new ContentValues();
            cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
            db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                    WalletContract.AccountEntry._ID + "=?", new String[]{String.valueOf(e.getKey())});
        }
        for (Map.Entry<Long, String> e : currencyIcons.entrySet()) {
            long iconId = GetIconIdByName(db, e.getValue());
            if (iconId < 0) iconId = GetIconIdByName(db, "unknown");
            if (iconId < 0) continue;
            ContentValues cv = new ContentValues();
            cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId);
            db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                    WalletContract.CurrencyEntry._ID + "=?", new String[]{String.valueOf(e.getKey())});
        }
    }

    /**
     * Чинит уже испорченные ссылки {@code icon_id} на уже пострадавших устройствах —
     * реальная находка (2026-09-11, живой девайс): более старая версия {@link #ResetIcons}
     * (до правки того же дня) мягко удаляла ВСЕ иконки без разбора, даже используемые живым
     * счётом/валютой — {@code icon_id} оставался указывать на теперь невидимую (мягко
     * удалённую) строку. {@code getIconById} её не находит, и то, что показывается вместо
     * неё, зависит от экрана: где-то системная заглушка Android
     * ({@code android.R.drawable.ic_menu_myplaces} — не из каталога приложения), где-то
     * {@code NullPointerException}. Реальный отчёт пользователя: кошелёк "Kenian" и валюта
     * "Хорватские куны" показывали "непонятную иконку, которой нет среди возможных в
     * каталоге". {@link #ResetIcons} сама больше так не делает (защищает используемое от
     * удаления при {@code reseed=false} — см. её javadoc), но это не чинит то, что уже
     * испорчено на диске — этим занимается этот метод (см. {@code WalletDbHelper.onUpgrade},
     * v78).
     * <p>
     * Для каждой живой строки {@code account}/{@code currency}, чей {@code icon_id}
     * ссылается на несуществующую или мягко удалённую строку {@code icon}: сперва пробуем
     * найти живую иконку с ТЕМ ЖЕ именем (частый случай — имя пережило реналив
     * {@link #ResetIcons}/пересев под другим {@code _id}), иначе — живую "unknown", иначе —
     * заводим свежую "unknown" из ресурса ({@link #EnsureLiveUnknownIcon}, гарантированный
     * фолбэк последней инстанции, раз ни то, ни другое не нашлось).
     */
    public static void RepairOrphanedIconLinks(SQLiteDatabase db, Context context) {
        long unknownIconId = EnsureLiveUnknownIcon(db, context);
        repairIconLinksInTable(db, WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry._ID, WalletContract.AccountEntry.COLUMN_ICON_ID,
                WalletContract.AccountEntry.COLUMN_DELETED_AT, unknownIconId);
        repairIconLinksInTable(db, WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry._ID, WalletContract.CurrencyEntry.COLUMN_ICON_ID,
                WalletContract.CurrencyEntry.COLUMN_DELETED_AT, unknownIconId);
    }

    /**
     * Гарантирует хотя бы одну живую иконку "unknown" — фолбэк последней инстанции для
     * {@link #repairIconLinksInTable} ниже (тот же смысл, что уже несёт "unknown" у
     * {@link #GetIconIdByName}/{@link #InsertSeedAccounts}, только та не заводит её заново,
     * если и она вдруг не нашлась). Если живая уже есть — просто отдаёт её {@code _id},
     * ничего не пишет.
     */
    private static long EnsureLiveUnknownIcon(SQLiteDatabase db, Context context) {
        long id = GetIconIdByName(db, "unknown");
        if (id >= 0) return id;
        Resources = context.getResources();
        PackageName = context.getPackageName();
        Long inserted = InsertIconEntry(db, "unknown", ICO_CATEGORY_ACCOUNT,
                Resources.getIdentifier("unknown", "drawable", PackageName));
        return inserted != null ? inserted : -1;
    }

    /**
     * Один проход {@link #RepairOrphanedIconLinks} по одной таблице ({@code account} или
     * {@code currency}) — сама таблица, её {@code _id}/{@code icon_id}/{@code deleted_at}
     * передаются параметрами, раз обе таблицы устроены одинаково в этой части.
     * {@code LEFT JOIN} без условия по {@code deleted_at} — чтобы достать имя иконки, ДАЖЕ
     * если она мягко удалена (нужно для реналива по имени ниже); "осиротела ли ссылка"
     * проверяет отдельно {@code WHERE}.
     */
    private static void repairIconLinksInTable(SQLiteDatabase db, String table, String idColumn,
                                                String iconIdColumn, String deletedAtColumn,
                                                long unknownIconId) {
        Map<Long, String> orphans = new HashMap<>(); // строка -> имя мёртвой иконки (может быть null)
        Cursor c = db.rawQuery("SELECT t." + idColumn + ", ic." + WalletContract.IconEntry.COLUMN_NAME
                + " FROM " + table + " t"
                + " LEFT JOIN " + WalletContract.IconEntry.TABLE_NAME + " ic"
                + " ON ic." + WalletContract.IconEntry._ID + " = t." + iconIdColumn
                + " WHERE t." + deletedAtColumn + " IS NULL"
                + " AND (ic." + WalletContract.IconEntry._ID + " IS NULL"
                + " OR ic." + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NOT NULL)", null);
        try {
            while (c.moveToNext()) orphans.put(c.getLong(0), c.isNull(1) ? null : c.getString(1));
        } finally {
            c.close();
        }
        if (orphans.isEmpty()) return;

        Log.w("WalletFillData", "RepairOrphanedIconLinks: " + orphans.size()
                + " orphaned " + iconIdColumn + " in " + table + " — relinking");

        for (Map.Entry<Long, String> e : orphans.entrySet()) {
            long iconId = e.getValue() != null ? GetIconIdByName(db, e.getValue()) : -1;
            if (iconId < 0) iconId = unknownIconId;
            ContentValues cv = new ContentValues();
            cv.put(iconIdColumn, iconId);
            db.update(table, cv, idColumn + "=?", new String[]{String.valueOf(e.getKey())});
        }
    }

    /**
     * Мягко удаляет {@code currency_pair_rate}/{@code currency_pair} (тот же довод, что в
     * ResetOperationsAndAccounts/ResetIcons — настоящий DELETE не долетел бы до сервера как
     * удаление) и, если {@code reseed}, заново сеет дефолтные пары+курсы
     * ({@link #SeedDefaultCurrencyPairsAndRates} — сама пропускает уже живой symbol и не находит
     * мягко удалённый, см. её и {@link #InsertCurrencyPair}/{@link #InsertCurrencyPairRate}).
     * Валюты не трогаются (пары ссылаются на них по iso_code, не по _id), так что релинк, в
     * отличие от {@link #ResetIcons}, не нужен.
     */
    public static void ResetCurrencyPairsAndRates(SQLiteDatabase db, Context context, boolean reseed) {
        long now = System.currentTimeMillis();

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.CurrencyPairRateEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL", null);

        cv = new ContentValues();
        cv.put(WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT, now);
        db.update(WalletContract.CurrencyPairEntry.TABLE_NAME, cv,
                WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL", null);

        if (reseed) SeedDefaultCurrencyPairsAndRates(db, context);
    }

}
