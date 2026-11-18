package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Маршруты пересчёта валют (openspec currency-conversion-routes, spec currency-routes): путь из
 * 3–4 валют «BTC → EUR → RUB». Хранится шагами в {@code currency_conversion} (по строке на шаг:
 * {@code num} 1…3, пара, {@code is_reverse}); пары и направление шагов подбирает {@link #create}.
 * Курс по маршруту считает {@link DerivedRates}, здесь — список, создание, удаление.
 * <p>
 * Правила те же, что у {@code /api/currency-routes*} в wallet-api: один живой маршрут на
 * неупорядоченную пару концов, для шага берётся пара с базовой валютой «слева» (иначе обратная, своя
 * раньше общей), оборванный маршрут (пару/валюту шага удалили) в список не попадает и при создании
 * замещается. Первичный ключ таблицы — {@code (from, to, num)} без учёта {@code deleted_at}, поэтому
 * мягко удалённые строки при повторном создании ОЖИВЛЯЮТСЯ (с тем же uuid): новый uuid на тот же
 * ключ дал бы серверу живой дубль, пока tombstone ещё не отправлен.
 */
public final class CurrencyRoutes {

    private CurrencyRoutes() {}

    /** Живой структурно валидный маршрут. */
    public static final class Route {
        public final long fromId;
        public final long toId;
        /** Валюты пути от {@code from} до {@code to}, 3–4 штуки. */
        public final List<Long> path;
        public final List<String> pathIso;
        /** Символы пар шагов и признак «шаг по обратной паре» — по порядку {@code num}. */
        public final List<String> stepSymbols;
        public final List<Boolean> stepReverse;

        Route(long fromId, long toId, List<Long> path, List<String> pathIso, List<String> stepSymbols,
              List<Boolean> stepReverse) {
            this.fromId = fromId;
            this.toId = toId;
            this.path = path;
            this.pathIso = pathIso;
            this.stepSymbols = stepSymbols;
            this.stepReverse = stepReverse;
        }

        /** «BTC → EUR → RUB». */
        public String label() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pathIso.size(); i++) {
                if (i > 0) sb.append(" → ");
                sb.append(pathIso.get(i));
            }
            return sb.toString();
        }
    }

    public enum CreateError {
        OK,
        /** Валют в пути не 3–4. */
        BAD_LENGTH,
        /** Одна валюта в пути дважды. */
        DUPLICATE_CURRENCY,
        /** Валюты нет или она удалена. */
        CURRENCY_NOT_FOUND,
        /** Нет живой пары для соседних валют; {@code detail} — «EUR→RUB». */
        NO_PAIR,
        /** Живой маршрут между теми же концами (в любом порядке) уже есть. */
        ALREADY_EXISTS
    }

    public static final class CreateResult {
        public final CreateError error;
        public final String detail;

        CreateResult(CreateError error, String detail) {
            this.error = error;
            this.detail = detail;
        }

        public boolean ok() {
            return error == CreateError.OK;
        }
    }

    /** Живые структурно валидные маршруты по ISO-кодам концов. */
    public static List<Route> list(SQLiteDatabase db) {
        DerivedRates.Input in = DerivedRates.read(db);
        Map<Long, String> symbols = new HashMap<>();
        Cursor c = db.rawQuery("SELECT _id, symbol FROM currency_pair WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) symbols.put(c.getLong(0), c.getString(1));
        } finally {
            c.close();
        }
        Map<Long, DerivedRates.Pair> pairs = new HashMap<>();
        for (DerivedRates.Pair p : in.pairs) pairs.put(p.id, p);

        Map<String, List<DerivedRates.Step>> grouped = new HashMap<>();
        for (DerivedRates.Step s : in.steps) {
            String key = s.from + ">" + s.to;
            List<DerivedRates.Step> list = grouped.get(key);
            if (list == null) grouped.put(key, list = new ArrayList<>());
            list.add(s);
        }
        List<Route> routes = new ArrayList<>();
        for (List<DerivedRates.Step> steps : grouped.values()) {
            Collections.sort(steps, (a, b) -> Integer.compare(a.num, b.num));
            Route r = toRoute(steps, in.currencies, pairs, symbols);
            if (r != null) routes.add(r);
        }
        Collections.sort(routes, (a, b) -> a.label().compareTo(b.label()));
        return routes;
    }

    private static Route toRoute(List<DerivedRates.Step> steps, Map<Long, String> currencies,
                                 Map<Long, DerivedRates.Pair> pairs, Map<Long, String> symbols) {
        long from = steps.get(0).from;
        long to = steps.get(0).to;
        if (!currencies.containsKey(from)) return null;
        List<Long> path = new ArrayList<>();
        List<String> iso = new ArrayList<>();
        List<String> stepSymbols = new ArrayList<>();
        List<Boolean> stepReverse = new ArrayList<>();
        long at = from;
        path.add(at);
        iso.add(currencies.get(at));
        for (int i = 0; i < steps.size(); i++) {
            DerivedRates.Step st = steps.get(i);
            DerivedRates.Pair p = pairs.get(st.pairId);
            if (p == null || st.num != i + 1) return null;
            long stepFrom = st.reverse ? p.quoteId : p.baseId;
            long stepTo = st.reverse ? p.baseId : p.quoteId;
            if (stepFrom != at || !currencies.containsKey(stepTo)) return null;
            at = stepTo;
            path.add(at);
            iso.add(currencies.get(at));
            stepSymbols.add(symbols.get(st.pairId));
            stepReverse.add(st.reverse);
        }
        return at == to ? new Route(from, to, path, iso, stepSymbols, stepReverse) : null;
    }

    /** Создаёт маршрут по пути из валют одной транзакцией; строки помечаются dirty триггером синка. */
    public static CreateResult create(SQLiteDatabase db, long[] path) {
        if (path.length < 3 || path.length > 4) return new CreateResult(CreateError.BAD_LENGTH, null);
        Set<Long> distinct = new HashSet<>();
        for (long id : path) distinct.add(id);
        if (distinct.size() != path.length) return new CreateResult(CreateError.DUPLICATE_CURRENCY, null);

        Map<Long, String> iso = DerivedRates.read(db).currencies;
        for (long id : path) {
            if (!iso.containsKey(id)) return new CreateResult(CreateError.CURRENCY_NOT_FOUND, null);
        }

        long from = path[0];
        long to = path[path.length - 1];
        for (Route r : list(db)) {
            if ((r.fromId == from && r.toId == to) || (r.fromId == to && r.toId == from)) {
                return new CreateResult(CreateError.ALREADY_EXISTS, null);
            }
        }

        long[] pairIds = new long[path.length - 1];
        int[] reverse = new int[path.length - 1];
        for (int i = 0; i + 1 < path.length; i++) {
            long a = path[i], b = path[i + 1];
            Long pair = findPair(db, a, b);
            if (pair != null) {
                reverse[i] = 0;
            } else if ((pair = findPair(db, b, a)) != null) {
                reverse[i] = 1;
            } else {
                return new CreateResult(CreateError.NO_PAIR, iso.get(a) + "→" + iso.get(b));
            }
            pairIds[i] = pair;
        }

        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            // Оборванный маршрут с теми же концами (его пару или валюту удалили) в списке не виден и
            // из UI не удаляется — замещаем: гасим его живые шаги, а ниже ключи оживляются.
            db.execSQL("UPDATE currency_conversion SET deleted_at = ? WHERE deleted_at IS NULL AND "
                            + "((from_currency_id = ? AND to_currency_id = ?) OR (from_currency_id = ? AND to_currency_id = ?))",
                    new Object[]{now, from, to, to, from});
            for (int i = 0; i < pairIds.length; i++) {
                ContentValues cv = new ContentValues();
                cv.put("currency_pair_id", pairIds[i]);
                cv.put("is_reverse", reverse[i]);
                cv.putNull("deleted_at");
                int revived = db.update("currency_conversion", cv,
                        "from_currency_id = ? AND to_currency_id = ? AND num = ?",
                        new String[]{String.valueOf(from), String.valueOf(to), String.valueOf(i + 1)});
                if (revived == 0) {
                    cv.put("from_currency_id", from);
                    cv.put("to_currency_id", to);
                    cv.put("num", i + 1);
                    db.insertOrThrow("currency_conversion", null, cv);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new CreateResult(CreateError.OK, null);
    }

    /** Живая пара base→quote; своя (is_shared = 0) раньше общей. */
    private static Long findPair(SQLiteDatabase db, long base, long quote) {
        Cursor c = db.rawQuery("SELECT _id FROM currency_pair WHERE deleted_at IS NULL "
                + "AND base_currency_id = ? AND quote_currency_id = ? ORDER BY is_shared LIMIT 1",
                new String[]{String.valueOf(base), String.valueOf(quote)});
        try {
            return c.moveToFirst() ? c.getLong(0) : null;
        } finally {
            c.close();
        }
    }

    /** Мягко удаляет все живые шаги маршрута {@code from → to}; пары и курсы не меняются. */
    public static int delete(SQLiteDatabase db, long fromId, long toId) {
        ContentValues cv = new ContentValues();
        cv.put("deleted_at", System.currentTimeMillis());
        return db.update("currency_conversion", cv,
                "from_currency_id = ? AND to_currency_id = ? AND deleted_at IS NULL",
                new String[]{String.valueOf(fromId), String.valueOf(toId)});
    }
}
