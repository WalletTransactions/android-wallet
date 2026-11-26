package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.TreeMap
import java.util.TreeSet

/**
 * Составные курсы валют (openspec currency-conversion-routes): курс по явному маршруту
 * (`currency_conversion`) и кросс через USD. Те же правила, что на сервере
 * (wallet-api, функция `rate_on`): прямой курс → явный маршрут → USD.
 *
 * Результат пишется в локальную таблицу-кэш `currency_rate_derived(from, to, date,
 * rate)`, которую `v_currency_conversion_rate` добавляет к прямым курсам — запросы
 * потребителей не меняются. Кэш не синкается и перестраивается целиком (данных мало) в
 * [ensureFresh], если триггеры источников (курс, пара, валюта, шаг маршрута)
 * пометили его устаревшим (`derived_rates_state.stale = 1`).
 *
 * Считаются только курсы В валюты эквивалента (`currency.show_in_equivalent = 1`) и
 * обратные им: так число строк растёт как n × k, а не n². Вьюха «кросс всех со всеми» на
 * сервере не вычислялась за разумное время (design change currency-conversion-routes).
 *
 * Правила пары `(S, E)`, `S ≠ E`:
 *  1. есть живой прямой курс (в любую сторону, на любую дату) — составной не строится;
 *  2. структурно валидный явный маршрут: шаги подряд с 1, стыкуются, концы S/E, пары и
 *     валюты живы. Курс — произведение курсов шагов, каждый шаг — последний курс не позже
 *     даты независимо; строки на объединение дат шагов; нет курса у шага — строк нет, а USD
 *     не подставляется (валидный маршрут блокирует кросс);
 *  3. иначе кросс через USD (валюта с `iso_code = 'USD'`): `S→USD` × `USD→E`
 *     по прямым курсам; для самой USD кросса нет.
 */
object DerivedRates {

    /** Строка кэша. */
    class Row(
        @JvmField val fromId: Long,
        @JvmField val toId: Long,
        @JvmField val date: String,
        @JvmField val rate: Double
    )

    /** Курс пары на дату (дата — первые 10 символов, `yyyy-MM-dd`). */
    class PairRate(
        @JvmField val pairId: Long,
        date: String,
        @JvmField val rate: Double
    ) {
        @JvmField
        val date: String = if (date.length > 10) date.substring(0, 10) else date
    }

    class Pair(
        @JvmField val id: Long,
        @JvmField val baseId: Long,
        @JvmField val quoteId: Long
    )

    /** Шаг маршрута: `from/to` — концы всего маршрута. */
    class Step(
        @JvmField val from: Long,
        @JvmField val to: Long,
        @JvmField val num: Int,
        @JvmField val pairId: Long,
        @JvmField val reverse: Boolean
    )

    /** Исходные данные расчёта: только ЖИВЫЕ строки. */
    class Input {
        @JvmField
        val currencies: MutableMap<Long, String> = HashMap() // _id -> iso_code

        @JvmField
        val equivalents: MutableSet<Long> = HashSet() // валюты эквивалента

        @JvmField
        val pairs: MutableList<Pair> = ArrayList()

        @JvmField
        val rates: MutableList<PairRate> = ArrayList()

        @JvmField
        val steps: MutableList<Step> = ArrayList()
    }

    // ── расчёт (чистая функция, без БД) ─────────────────────────────────────────

    /** Серия курсов направления: дата → курс (прямая пара перекрывает обратную на ту же дату). */
    private class Series {
        val byDate = TreeMap<String, Double>()

        /** Последний курс не позже даты; null — нет. */
        fun at(date: String): Double? {
            val e = byDate.floorEntry(date)
            return e?.value
        }
    }

    @JvmStatic
    fun compute(input: Input): List<Row> {
        val pairById = HashMap<Long, Pair>()
        for (p in input.pairs) {
            if (input.currencies.containsKey(p.baseId) && input.currencies.containsKey(p.quoteId)) pairById[p.id] = p
        }
        val ratesByPair = HashMap<Long, MutableList<PairRate>>()
        for (r in input.rates) {
            if (!pairById.containsKey(r.pairId)) continue
            ratesByPair.getOrPut(r.pairId) { ArrayList() }.add(r)
        }

        // Направленные серии и множество связанных (неупорядоченно) валют.
        val direct = HashMap<String, Series>()
        val connected = HashSet<String>()
        for ((pairId, pairRates) in ratesByPair) {
            val p = pairById[pairId]!!
            connected.add(unordered(p.baseId, p.quoteId))
            val fwd = seriesFor(direct, p.baseId, p.quoteId)
            val rev = seriesFor(direct, p.quoteId, p.baseId)
            for (r in pairRates) {
                if (r.rate <= 0) continue
                // Прямая пара вводится как есть, обратная — 1/x: при совпадении даты прямая выигрывает,
                // это решается ниже порядком вставки (первой кладётся «своя» сторона пары).
                fwd.byDate[r.date] = r.rate
                if (!rev.byDate.containsKey(r.date)) rev.byDate[r.date] = 1.0 / r.rate
            }
        }

        var usd: Long? = null
        for ((id, iso) in input.currencies) {
            if ("USD" == iso && (usd == null || id < usd)) usd = id
        }

        // Маршруты: (from,to) -> шаги по num.
        val routes = HashMap<String, MutableList<Step>>()
        for (s in input.steps) {
            val key = s.from.toString() + ">" + s.to
            routes.getOrPut(key) { ArrayList() }.add(s)
        }
        for (list in routes.values) {
            list.sortWith { a, b -> a.num.compareTo(b.num) }
        }

        val out = ArrayList<Row>()
        val done = HashSet<String>()
        for (e in input.equivalents) {
            if (!input.currencies.containsKey(e)) continue
            for (s in input.currencies.keys) {
                if (s == e) continue
                if (connected.contains(unordered(s, e))) continue // прямой курс есть — приоритетнее всего
                if (!done.add("$s>$e")) continue

                // 2. явный маршрут (любой ориентации); валидный блокирует USD
                var chain = routes["$s>$e"]
                var reversed = false
                if (chain == null) {
                    chain = routes["$e>$s"]
                    reversed = chain != null
                }
                if (chain != null) {
                    val rf = if (reversed) e else s
                    val rt = if (reversed) s else e
                    val stepSeries = validChain(chain, rf, rt, pairById, direct)
                    if (stepSeries != null) { // структурно валидный маршрут
                        emit(out, done, stepSeries, rf, rt)
                        continue
                    }
                }
                // 3. кросс через USD
                if (usd == null || s == usd || e == usd) continue
                val leg1 = direct["$s>$usd"]
                val leg2 = direct["$usd>$e"]
                if (leg1 == null || leg2 == null) continue
                emit(out, done, listOf(leg1, leg2), s, e)
            }
        }
        return out
    }

    private fun unordered(a: Long, b: Long): String {
        return if (a < b) "$a~$b" else "$b~$a"
    }

    private fun seriesFor(map: MutableMap<String, Series>, from: Long, to: Long): Series {
        return map.getOrPut("$from>$to") { Series() }
    }

    /**
     * Серии шагов валидной цепочки `rf → rt`; null — цепочка невалидна. Шаг по обратной паре
     * берёт обратную серию. Пустая серия шага допустима (маршрут валиден, курса нет).
     */
    private fun validChain(
        chain: List<Step>, rf: Long, rt: Long, pairById: Map<Long, Pair>,
        direct: Map<String, Series>
    ): List<Series>? {
        var at = rf
        val series = ArrayList<Series>()
        for (i in chain.indices) {
            val st = chain[i]
            val p = pairById[st.pairId]
            if (p == null || st.num != i + 1) return null
            val from = if (st.reverse) p.quoteId else p.baseId
            val to = if (st.reverse) p.baseId else p.quoteId
            if (from != at) return null
            val s = direct["$from>$to"]
            series.add(s ?: Series())
            at = to
        }
        return if (at == rt) series else null
    }

    /** Строки для (rf→rt) и (rt→rf) на объединение дат серий; на дату нужны курсы у ВСЕХ серий. */
    private fun emit(out: MutableList<Row>, done: MutableSet<String>, series: List<Series>, rf: Long, rt: Long) {
        val dates = TreeSet<String>()
        for (s in series) dates.addAll(s.byDate.keys)
        done.add("$rf>$rt")
        done.add("$rt>$rf")
        for (d in dates) {
            var product = 1.0
            var complete = true
            for (s in series) {
                val r = s.at(d)
                if (r == null) {
                    complete = false
                    break
                }
                product *= r
            }
            if (!complete) continue
            out.add(Row(rf, rt, d, product))
            out.add(Row(rt, rf, d, 1.0 / product))
        }
    }

    // ── БД ──────────────────────────────────────────────────────────────────────

    /** Перестраивает кэш, если он помечен устаревшим. Дёшево, когда актуален. */
    @JvmStatic
    fun ensureFresh(db: SQLiteDatabase?) {
        if (db == null || !db.isOpen || !isStale(db)) return
        rebuild(db)
    }

    private fun isStale(db: SQLiteDatabase): Boolean {
        var c: android.database.Cursor? = null
        try {
            c = db.rawQuery("SELECT stale FROM derived_rates_state LIMIT 1", null)
            return !c.moveToFirst() || c.getInt(0) != 0
        } catch (e: RuntimeException) {
            return false // таблицы ещё нет (создание/миграция в процессе)
        } finally {
            c?.close()
        }
    }

    /** Полная перестройка кэша одной транзакцией. */
    @JvmStatic
    fun rebuild(db: SQLiteDatabase) {
        val rows = compute(read(db))
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM currency_rate_derived")
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO currency_rate_derived (from_currency_id, to_currency_id, date, rate) VALUES (?,?,?,?)"
            )
            try {
                for (r in rows) {
                    st.clearBindings()
                    st.bindLong(1, r.fromId)
                    st.bindLong(2, r.toId)
                    st.bindString(3, r.date)
                    st.bindDouble(4, r.rate)
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
            val cv = ContentValues()
            cv.put("stale", 0)
            if (db.update("derived_rates_state", cv, null, null) == 0) {
                cv.put("_id", 1)
                db.insert("derived_rates_state", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @JvmStatic
    fun read(db: SQLiteDatabase): Input {
        val input = Input()
        db.rawQuery("SELECT _id, iso_code, show_in_equivalent FROM currency WHERE deleted_at IS NULL", null).use { c ->
            while (c.moveToNext()) {
                input.currencies[c.getLong(0)] = c.getString(1)
                if (c.getInt(2) != 0) input.equivalents.add(c.getLong(0))
            }
        }
        db.rawQuery("SELECT _id, base_currency_id, quote_currency_id FROM currency_pair WHERE deleted_at IS NULL", null).use { c ->
            while (c.moveToNext()) input.pairs.add(Pair(c.getLong(0), c.getLong(1), c.getLong(2)))
        }
        db.rawQuery("SELECT currency_pair_id, date, rate FROM currency_pair_rate WHERE deleted_at IS NULL", null).use { c ->
            while (c.moveToNext()) input.rates.add(PairRate(c.getLong(0), c.getString(1), c.getDouble(2)))
        }
        db.rawQuery(
            "SELECT from_currency_id, to_currency_id, num, currency_pair_id, is_reverse "
                    + "FROM currency_conversion WHERE deleted_at IS NULL", null
        ).use { c ->
            while (c.moveToNext()) {
                input.steps.add(Step(c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getInt(4) != 0))
            }
        }
        return input
    }
}
