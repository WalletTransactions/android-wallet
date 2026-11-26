package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Маршруты пересчёта валют (openspec currency-conversion-routes, spec currency-routes): путь из
 * 3–4 валют «BTC → EUR → RUB». Хранится шагами в `currency_conversion` (по строке на шаг:
 * `num` 1…3, пара, `is_reverse`); пары и направление шагов подбирает [create].
 * Курс по маршруту считает [DerivedRates], здесь — список, создание, удаление.
 *
 * Правила те же, что у `/api/currency-routes*` в wallet-api: один живой маршрут на
 * неупорядоченную пару концов, для шага берётся пара с базовой валютой «слева» (иначе обратная, своя
 * раньше общей), оборванный маршрут (пару/валюту шага удалили) в список не попадает и при создании
 * замещается. Первичный ключ таблицы — `(from, to, num)` без учёта `deleted_at`, поэтому
 * мягко удалённые строки при повторном создании ОЖИВЛЯЮТСЯ (с тем же uuid): новый uuid на тот же
 * ключ дал бы серверу живой дубль, пока tombstone ещё не отправлен.
 */
object CurrencyRoutes {

    /**
     * Живой структурно валидный маршрут.
     *
     * [path] — валюты пути от `from` до `to`, 3–4 штуки; [stepSymbols] и [stepReverse] — символы
     * пар шагов и признак «шаг по обратной паре» по порядку `num`.
     */
    class Route internal constructor(
        @JvmField val fromId: Long,
        @JvmField val toId: Long,
        @JvmField val path: List<Long>,
        @JvmField val pathIso: List<String>,
        @JvmField val stepSymbols: List<String?>,
        @JvmField val stepReverse: List<Boolean>
    ) {
        /** «BTC → EUR → RUB». */
        fun label(): String {
            val sb = StringBuilder()
            for (i in pathIso.indices) {
                if (i > 0) sb.append(" → ")
                sb.append(pathIso[i])
            }
            return sb.toString()
        }
    }

    enum class CreateError {
        OK,

        /** Валют в пути не 3–4. */
        BAD_LENGTH,

        /** Одна валюта в пути дважды. */
        DUPLICATE_CURRENCY,

        /** Валюты нет или она удалена. */
        CURRENCY_NOT_FOUND,

        /** Нет живой пары для соседних валют; `detail` — «EUR→RUB». */
        NO_PAIR,

        /** Живой маршрут между теми же концами (в любом порядке) уже есть. */
        ALREADY_EXISTS
    }

    class CreateResult internal constructor(
        @JvmField val error: CreateError,
        @JvmField val detail: String?
    ) {
        fun ok(): Boolean {
            return error == CreateError.OK
        }
    }

    /** Живые структурно валидные маршруты по ISO-кодам концов. */
    @JvmStatic
    fun list(db: SQLiteDatabase): List<Route> {
        val input = DerivedRates.read(db)
        val symbols = HashMap<Long, String?>()
        db.rawQuery("SELECT _id, symbol FROM currency_pair WHERE deleted_at IS NULL", null).use { c ->
            while (c.moveToNext()) symbols[c.getLong(0)] = c.getString(1)
        }
        val pairs = HashMap<Long, DerivedRates.Pair>()
        for (p in input.pairs) pairs[p.id] = p

        val grouped = HashMap<String, MutableList<DerivedRates.Step>>()
        for (s in input.steps) {
            val key = s.from.toString() + ">" + s.to
            grouped.getOrPut(key) { ArrayList() }.add(s)
        }
        val routes = ArrayList<Route>()
        for (steps in grouped.values) {
            steps.sortWith { a, b -> a.num.compareTo(b.num) }
            val r = toRoute(steps, input.currencies, pairs, symbols)
            if (r != null) routes.add(r)
        }
        routes.sortWith { a, b -> a.label().compareTo(b.label()) }
        return routes
    }

    private fun toRoute(
        steps: List<DerivedRates.Step>, currencies: Map<Long, String>,
        pairs: Map<Long, DerivedRates.Pair>, symbols: Map<Long, String?>
    ): Route? {
        val from = steps[0].from
        val to = steps[0].to
        if (!currencies.containsKey(from)) return null
        val path = ArrayList<Long>()
        val iso = ArrayList<String>()
        val stepSymbols = ArrayList<String?>()
        val stepReverse = ArrayList<Boolean>()
        var at = from
        path.add(at)
        iso.add(currencies[at]!!)
        for (i in steps.indices) {
            val st = steps[i]
            val p = pairs[st.pairId]
            if (p == null || st.num != i + 1) return null
            val stepFrom = if (st.reverse) p.quoteId else p.baseId
            val stepTo = if (st.reverse) p.baseId else p.quoteId
            if (stepFrom != at || !currencies.containsKey(stepTo)) return null
            at = stepTo
            path.add(at)
            iso.add(currencies[at]!!)
            stepSymbols.add(symbols[st.pairId])
            stepReverse.add(st.reverse)
        }
        return if (at == to) Route(from, to, path, iso, stepSymbols, stepReverse) else null
    }

    /** Создаёт маршрут по пути из валют одной транзакцией; строки помечаются dirty триггером синка. */
    @JvmStatic
    fun create(db: SQLiteDatabase, path: LongArray): CreateResult {
        if (path.size < 3 || path.size > 4) return CreateResult(CreateError.BAD_LENGTH, null)
        val distinct = HashSet<Long>()
        for (id in path) distinct.add(id)
        if (distinct.size != path.size) return CreateResult(CreateError.DUPLICATE_CURRENCY, null)

        val iso = DerivedRates.read(db).currencies
        for (id in path) {
            if (!iso.containsKey(id)) return CreateResult(CreateError.CURRENCY_NOT_FOUND, null)
        }

        val from = path[0]
        val to = path[path.size - 1]
        for (r in list(db)) {
            if ((r.fromId == from && r.toId == to) || (r.fromId == to && r.toId == from)) {
                return CreateResult(CreateError.ALREADY_EXISTS, null)
            }
        }

        val pairIds = LongArray(path.size - 1)
        val reverse = IntArray(path.size - 1)
        var i = 0
        while (i + 1 < path.size) {
            val a = path[i]
            val b = path[i + 1]
            var pair = findPair(db, a, b)
            if (pair != null) {
                reverse[i] = 0
            } else {
                pair = findPair(db, b, a)
                if (pair != null) {
                    reverse[i] = 1
                } else {
                    return CreateResult(CreateError.NO_PAIR, iso[a].toString() + "→" + iso[b])
                }
            }
            pairIds[i] = pair
            i++
        }

        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // Оборванный маршрут с теми же концами (его пару или валюту удалили) в списке не виден и
            // из UI не удаляется — замещаем: гасим его живые шаги, а ниже ключи оживляются.
            db.execSQL(
                "UPDATE currency_conversion SET deleted_at = ? WHERE deleted_at IS NULL AND "
                        + "((from_currency_id = ? AND to_currency_id = ?) OR (from_currency_id = ? AND to_currency_id = ?))",
                arrayOf<Any>(now, from, to, to, from)
            )
            for (k in pairIds.indices) {
                val cv = ContentValues()
                cv.put("currency_pair_id", pairIds[k])
                cv.put("is_reverse", reverse[k])
                cv.putNull("deleted_at")
                val revived = db.update(
                    "currency_conversion", cv,
                    "from_currency_id = ? AND to_currency_id = ? AND num = ?",
                    arrayOf(from.toString(), to.toString(), (k + 1).toString())
                )
                if (revived == 0) {
                    cv.put("from_currency_id", from)
                    cv.put("to_currency_id", to)
                    cv.put("num", k + 1)
                    db.insertOrThrow("currency_conversion", null, cv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return CreateResult(CreateError.OK, null)
    }

    /** Живая пара base→quote; своя (is_shared = 0) раньше общей. */
    private fun findPair(db: SQLiteDatabase, base: Long, quote: Long): Long? {
        db.rawQuery(
            "SELECT _id FROM currency_pair WHERE deleted_at IS NULL "
                    + "AND base_currency_id = ? AND quote_currency_id = ? ORDER BY is_shared LIMIT 1",
            arrayOf(base.toString(), quote.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /** Мягко удаляет все живые шаги маршрута `from → to`; пары и курсы не меняются. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, fromId: Long, toId: Long): Int {
        val cv = ContentValues()
        cv.put("deleted_at", System.currentTimeMillis())
        return db.update(
            "currency_conversion", cv,
            "from_currency_id = ? AND to_currency_id = ? AND deleted_at IS NULL",
            arrayOf(fromId.toString(), toId.toString())
        )
    }
}
