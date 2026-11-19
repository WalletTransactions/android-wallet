package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Составные курсы валют (openspec currency-conversion-routes): курс по явному маршруту
 * ({@code currency_conversion}) и кросс через USD. Те же правила, что на сервере
 * (wallet-api, функция {@code rate_on}): прямой курс → явный маршрут → USD.
 * <p>
 * Результат пишется в локальную таблицу-кэш {@code currency_rate_derived(from, to, date,
 * rate)}, которую {@code v_currency_conversion_rate} добавляет к прямым курсам — запросы
 * потребителей не меняются. Кэш не синкается и перестраивается целиком (данных мало) в
 * {@link #ensureFresh}, если триггеры источников (курс, пара, валюта, шаг маршрута)
 * пометили его устаревшим ({@code derived_rates_state.stale = 1}).
 * <p>
 * Считаются только курсы В валюты эквивалента ({@code currency.show_in_equivalent = 1}) и
 * обратные им: так число строк растёт как n × k, а не n². Вьюха «кросс всех со всеми» на
 * сервере не вычислялась за разумное время (design change currency-conversion-routes).
 * <p>
 * Правила пары {@code (S, E)}, {@code S ≠ E}:
 * <ol>
 *   <li>есть живой прямой курс (в любую сторону, на любую дату) — составной не строится;</li>
 *   <li>структурно валидный явный маршрут: шаги подряд с 1, стыкуются, концы S/E, пары и
 *       валюты живы. Курс — произведение курсов шагов, каждый шаг — последний курс не позже
 *       даты независимо; строки на объединение дат шагов; нет курса у шага — строк нет, а USD
 *       не подставляется (валидный маршрут блокирует кросс);</li>
 *   <li>иначе кросс через USD (валюта с {@code iso_code = 'USD'}): {@code S→USD} × {@code USD→E}
 *       по прямым курсам; для самой USD кросса нет.</li>
 * </ol>
 */
public final class DerivedRates {

    private DerivedRates() {}

    /** Строка кэша. */
    public static final class Row {
        public final long fromId;
        public final long toId;
        public final String date;
        public final double rate;

        Row(long fromId, long toId, String date, double rate) {
            this.fromId = fromId;
            this.toId = toId;
            this.date = date;
            this.rate = rate;
        }
    }

    /** Курс пары на дату (дата — первые 10 символов, {@code yyyy-MM-dd}). */
    public static final class PairRate {
        final long pairId;
        final String date;
        final double rate;

        public PairRate(long pairId, String date, double rate) {
            this.pairId = pairId;
            this.date = date.length() > 10 ? date.substring(0, 10) : date;
            this.rate = rate;
        }
    }

    public static final class Pair {
        final long id;
        final long baseId;
        final long quoteId;

        public Pair(long id, long baseId, long quoteId) {
            this.id = id;
            this.baseId = baseId;
            this.quoteId = quoteId;
        }
    }

    /** Шаг маршрута: {@code from/to} — концы всего маршрута. */
    public static final class Step {
        final long from;
        final long to;
        final int num;
        final long pairId;
        final boolean reverse;

        public Step(long from, long to, int num, long pairId, boolean reverse) {
            this.from = from;
            this.to = to;
            this.num = num;
            this.pairId = pairId;
            this.reverse = reverse;
        }
    }

    /** Исходные данные расчёта: только ЖИВЫЕ строки. */
    public static final class Input {
        public final Map<Long, String> currencies = new HashMap<>(); // _id -> iso_code
        public final Set<Long> equivalents = new HashSet<>();        // валюты эквивалента
        public final List<Pair> pairs = new ArrayList<>();
        public final List<PairRate> rates = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
    }

    // ── расчёт (чистая функция, без БД) ─────────────────────────────────────────

    /** Серия курсов направления: дата → курс (прямая пара перекрывает обратную на ту же дату). */
    private static final class Series {
        final java.util.TreeMap<String, Double> byDate = new java.util.TreeMap<>();

        /** Последний курс не позже даты; null — нет. */
        Double at(String date) {
            Map.Entry<String, Double> e = byDate.floorEntry(date);
            return e == null ? null : e.getValue();
        }
    }

    public static List<Row> compute(Input in) {
        Map<Long, Pair> pairById = new HashMap<>();
        for (Pair p : in.pairs) {
            if (in.currencies.containsKey(p.baseId) && in.currencies.containsKey(p.quoteId)) pairById.put(p.id, p);
        }
        Map<Long, List<PairRate>> ratesByPair = new HashMap<>();
        for (PairRate r : in.rates) {
            if (!pairById.containsKey(r.pairId)) continue;
            List<PairRate> list = ratesByPair.get(r.pairId);
            if (list == null) ratesByPair.put(r.pairId, list = new ArrayList<>());
            list.add(r);
        }

        // Направленные серии и множество связанных (неупорядоченно) валют.
        Map<String, Series> direct = new HashMap<>();
        Set<String> connected = new HashSet<>();
        for (Map.Entry<Long, List<PairRate>> e : ratesByPair.entrySet()) {
            Pair p = pairById.get(e.getKey());
            connected.add(unordered(p.baseId, p.quoteId));
            Series fwd = seriesFor(direct, p.baseId, p.quoteId);
            Series rev = seriesFor(direct, p.quoteId, p.baseId);
            for (PairRate r : e.getValue()) {
                if (r.rate <= 0) continue;
                // Прямая пара вводится как есть, обратная — 1/x: при совпадении даты прямая выигрывает,
                // это решается ниже порядком вставки (первой кладётся «своя» сторона пары).
                fwd.byDate.put(r.date, r.rate);
                if (!rev.byDate.containsKey(r.date)) rev.byDate.put(r.date, 1.0 / r.rate);
            }
        }

        Long usd = null;
        for (Map.Entry<Long, String> c : in.currencies.entrySet()) {
            if ("USD".equals(c.getValue()) && (usd == null || c.getKey() < usd)) usd = c.getKey();
        }

        // Маршруты: (from,to) -> шаги по num.
        Map<String, List<Step>> routes = new HashMap<>();
        for (Step s : in.steps) {
            String key = s.from + ">" + s.to;
            List<Step> list = routes.get(key);
            if (list == null) routes.put(key, list = new ArrayList<>());
            list.add(s);
        }
        for (List<Step> list : routes.values()) {
            Collections.sort(list, (a, b) -> Integer.compare(a.num, b.num));
        }

        List<Row> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (long e : in.equivalents) {
            if (!in.currencies.containsKey(e)) continue;
            for (long s : in.currencies.keySet()) {
                if (s == e) continue;
                if (connected.contains(unordered(s, e))) continue; // прямой курс есть — приоритетнее всего
                if (!done.add(s + ">" + e)) continue;

                // 2. явный маршрут (любой ориентации); валидный блокирует USD
                List<Step> chain = routes.get(s + ">" + e);
                boolean reversed = false;
                if (chain == null) {
                    chain = routes.get(e + ">" + s);
                    reversed = chain != null;
                }
                if (chain != null) {
                    long rf = reversed ? e : s;
                    long rt = reversed ? s : e;
                    List<Series> stepSeries = validChain(chain, rf, rt, pairById, direct);
                    if (stepSeries != null) { // структурно валидный маршрут
                        emit(out, done, stepSeries, rf, rt);
                        continue;
                    }
                }
                // 3. кросс через USD
                if (usd == null || s == usd || e == usd) continue;
                Series leg1 = direct.get(s + ">" + usd);
                Series leg2 = direct.get(usd + ">" + e);
                if (leg1 == null || leg2 == null) continue;
                List<Series> legs = new ArrayList<>();
                legs.add(leg1);
                legs.add(leg2);
                emit(out, done, legs, s, e);
            }
        }
        return out;
    }

    private static String unordered(long a, long b) {
        return a < b ? a + "~" + b : b + "~" + a;
    }

    private static Series seriesFor(Map<String, Series> map, long from, long to) {
        String key = from + ">" + to;
        Series s = map.get(key);
        if (s == null) map.put(key, s = new Series());
        return s;
    }

    /**
     * Серии шагов валидной цепочки {@code rf → rt}; null — цепочка невалидна. Шаг по обратной паре
     * берёт обратную серию. Пустая серия шага допустима (маршрут валиден, курса нет).
     */
    private static List<Series> validChain(List<Step> chain, long rf, long rt, Map<Long, Pair> pairById,
                                          Map<String, Series> direct) {
        long at = rf;
        List<Series> series = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            Step st = chain.get(i);
            Pair p = pairById.get(st.pairId);
            if (p == null || st.num != i + 1) return null;
            long from = st.reverse ? p.quoteId : p.baseId;
            long to = st.reverse ? p.baseId : p.quoteId;
            if (from != at) return null;
            Series s = direct.get(from + ">" + to);
            series.add(s == null ? new Series() : s);
            at = to;
        }
        return at == rt ? series : null;
    }

    /** Строки для (rf→rt) и (rt→rf) на объединение дат серий; на дату нужны курсы у ВСЕХ серий. */
    private static void emit(List<Row> out, Set<String> done, List<Series> series, long rf, long rt) {
        TreeSet<String> dates = new TreeSet<>();
        for (Series s : series) dates.addAll(s.byDate.keySet());
        done.add(rf + ">" + rt);
        done.add(rt + ">" + rf);
        for (String d : dates) {
            double product = 1.0;
            boolean complete = true;
            for (Series s : series) {
                Double r = s.at(d);
                if (r == null) { complete = false; break; }
                product *= r;
            }
            if (!complete) continue;
            out.add(new Row(rf, rt, d, product));
            out.add(new Row(rt, rf, d, 1.0 / product));
        }
    }

    // ── БД ──────────────────────────────────────────────────────────────────────

    /** Перестраивает кэш, если он помечен устаревшим. Дёшево, когда актуален. */
    public static void ensureFresh(SQLiteDatabase db) {
        if (db == null || !db.isOpen() || !isStale(db)) return;
        rebuild(db);
    }

    private static boolean isStale(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT stale FROM derived_rates_state LIMIT 1", null);
            return !c.moveToFirst() || c.getInt(0) != 0;
        } catch (RuntimeException e) {
            return false; // таблицы ещё нет (создание/миграция в процессе)
        } finally {
            if (c != null) c.close();
        }
    }

    /** Полная перестройка кэша одной транзакцией. */
    public static void rebuild(SQLiteDatabase db) {
        List<Row> rows = compute(read(db));
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM currency_rate_derived");
            SQLiteStatement st = db.compileStatement(
                    "INSERT OR REPLACE INTO currency_rate_derived (from_currency_id, to_currency_id, date, rate) VALUES (?,?,?,?)");
            try {
                for (Row r : rows) {
                    st.clearBindings();
                    st.bindLong(1, r.fromId);
                    st.bindLong(2, r.toId);
                    st.bindString(3, r.date);
                    st.bindDouble(4, r.rate);
                    st.executeInsert();
                }
            } finally {
                st.close();
            }
            ContentValues cv = new ContentValues();
            cv.put("stale", 0);
            if (db.update("derived_rates_state", cv, null, null) == 0) {
                cv.put("_id", 1);
                db.insert("derived_rates_state", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static Input read(SQLiteDatabase db) {
        Input in = new Input();
        Cursor c = db.rawQuery("SELECT _id, iso_code, show_in_equivalent FROM currency WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) {
                in.currencies.put(c.getLong(0), c.getString(1));
                if (c.getInt(2) != 0) in.equivalents.add(c.getLong(0));
            }
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT _id, base_currency_id, quote_currency_id FROM currency_pair WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) in.pairs.add(new Pair(c.getLong(0), c.getLong(1), c.getLong(2)));
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT currency_pair_id, date, rate FROM currency_pair_rate WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) in.rates.add(new PairRate(c.getLong(0), c.getString(1), c.getDouble(2)));
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT from_currency_id, to_currency_id, num, currency_pair_id, is_reverse "
                + "FROM currency_conversion WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) {
                in.steps.add(new Step(c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getInt(4) != 0));
            }
        } finally {
            c.close();
        }
        return in;
    }
}
