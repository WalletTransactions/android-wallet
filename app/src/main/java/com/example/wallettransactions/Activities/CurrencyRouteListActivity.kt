package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Data.CurrencyRoutes
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Экран «Маршруты пересчёта» (openspec currency-conversion-routes, spec currency-routes): список
 * маршрутов вида «BTC → EUR → RUB», создание по пути из 3–4 валют и удаление. Пары и направление
 * шагов подбирает [CurrencyRoutes.create]; курс по маршруту считает кэш
 * `DerivedRates`. Пишет только локально — шаги уйдут на сервер обычным push (dirty ставит триггер).
 */
class CurrencyRouteListActivity : AppCompatActivity() {

    /** ISO выбранных валют формы: «Из», «Через», вторая «Через», «В»; null — не выбрана. */
    private val selectedIso = arrayOfNulls<String>(4)
    private var twoVia = false

    private lateinit var adapter: RouteAdapter
    private lateinit var listRoutes: ListView
    private lateinit var routeEmpty: TextView

    private var dialogView: View? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_route_list)

        listRoutes = findViewById(R.id.listRoutes)
        routeEmpty = findViewById(R.id.routeEmpty)
        adapter = RouteAdapter(this, ArrayList())
        listRoutes.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun btnUp(view: View) {
        finish()
    }

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    private fun refresh() {
        val routes = CurrencyRoutes.list(db())
        adapter.clear()
        adapter.addAll(routes)
        adapter.notifyDataSetChanged()
        val empty = routes.isEmpty()
        routeEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listRoutes.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ── удаление ────────────────────────────────────────────────────────────────

    private fun confirmDelete(route: CurrencyRoutes.Route) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.route_delete_question, route.label()))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                CurrencyRoutes.delete(db(), route.fromId, route.toId)
                refresh()
            }
            .show()
    }

    // ── форма создания ──────────────────────────────────────────────────────────

    /** btnAddRoute (activity_currency_route_list.xml android:onClick). */
    fun onAddRoute(view: View) {
        for (i in selectedIso.indices) selectedIso[i] = null
        twoVia = false
        dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_route, null)
        dialog = AlertDialog.Builder(this).setView(dialogView).create()
        renderForm()
        dialog!!.show()
    }

    private fun renderForm() {
        val view = dialogView!!
        setValue(R.id.routeFromValue, selectedIso[0])
        setValue(R.id.routeVia1Value, selectedIso[1])
        setValue(R.id.routeVia2Value, selectedIso[2])
        setValue(R.id.routeToValue, selectedIso[3])
        view.findViewById<View>(R.id.blockRouteVia2).visibility = if (twoVia) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.btnRouteToggleVia)
            .setText(if (twoVia) R.string.route_dialog_remove_via else R.string.route_dialog_add_via)
    }

    private fun setValue(viewId: Int, iso: String?) {
        dialogView!!.findViewById<TextView>(viewId).text = iso ?: getString(R.string.pair_choose)
    }

    private fun showError(text: String?) {
        val error = dialogView!!.findViewById<TextView>(R.id.routeDialogError)
        error.text = text
        error.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun pick(slot: Int, request: Int, titleRes: Int) {
        startActivityForResult(
            CurrencyPickerActivity.buildIntent(this, getString(titleRes), selectedIso[slot]), request
        )
    }

    /** rowRouteFrom (dialog_new_route.xml android:onClick). */
    fun onPickRouteFrom(view: View) {
        pick(0, REQUEST_FROM, R.string.route_dialog_from)
    }

    /** rowRouteVia1 (dialog_new_route.xml android:onClick). */
    fun onPickRouteVia1(view: View) {
        pick(1, REQUEST_VIA1, R.string.route_dialog_via)
    }

    /** rowRouteVia2 (dialog_new_route.xml android:onClick). */
    fun onPickRouteVia2(view: View) {
        pick(2, REQUEST_VIA2, R.string.route_dialog_via)
    }

    /** rowRouteTo (dialog_new_route.xml android:onClick). */
    fun onPickRouteTo(view: View) {
        pick(3, REQUEST_TO, R.string.route_dialog_to)
    }

    /** btnRouteToggleVia: добавляет или убирает вторую промежуточную валюту. */
    fun onToggleRouteVia(view: View) {
        twoVia = !twoVia
        if (!twoVia) selectedIso[2] = null
        showError(null)
        renderForm()
    }

    /** Отмена в листе (dialog_new_route.xml android:onClick). */
    fun onCancelRoute(view: View) {
        dialog?.dismiss()
    }

    /** «Создать» в листе (dialog_new_route.xml android:onClick): проверки до записи, затем создание. */
    fun onCreateRoute(view: View) {
        val isos = ArrayList<String?>()
        isos.add(selectedIso[0])
        isos.add(selectedIso[1])
        if (twoVia) isos.add(selectedIso[2])
        isos.add(selectedIso[3])

        val seen = HashSet<String>()
        for (iso in isos) {
            if (iso == null) {
                showError(getString(R.string.route_error_choose_all))
                return
            }
        }
        for (iso in isos) {
            if (!seen.add(iso!!)) {
                showError(getString(R.string.route_error_duplicate_currency))
                return
            }
        }

        val path = LongArray(isos.size)
        for (i in path.indices) {
            val currency = Singleton.instance.getCurrencyList(this).getCurrencyByISO(isos[i])
            if (currency == null) {
                showError(getString(R.string.route_error_generic))
                return
            }
            path[i] = currency.id.toLong()
        }

        val result = CurrencyRoutes.create(db(), path)
        when (result.error) {
            CurrencyRoutes.CreateError.OK -> {
                dialog?.dismiss()
                refresh()
            }
            CurrencyRoutes.CreateError.NO_PAIR ->
                showError(getString(R.string.route_error_no_pair, result.detail))
            CurrencyRoutes.CreateError.ALREADY_EXISTS ->
                showError(getString(R.string.route_error_exists))
            CurrencyRoutes.CreateError.DUPLICATE_CURRENCY ->
                showError(getString(R.string.route_error_duplicate_currency))
            else -> showError(getString(R.string.route_error_generic))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || dialogView == null) return
        val iso = data.getStringExtra(CurrencyPickerActivity.RESULT_ISO) ?: return
        val slot = when (requestCode) {
            REQUEST_FROM -> 0
            REQUEST_VIA1 -> 1
            REQUEST_VIA2 -> 2
            REQUEST_TO -> 3
            else -> return
        }
        selectedIso[slot] = iso
        showError(null)
        renderForm()
    }

    // ── список ──────────────────────────────────────────────────────────────────

    private inner class RouteAdapter(context: Context, items: MutableList<CurrencyRoutes.Route>) :
        ArrayAdapter<CurrencyRoutes.Route>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_currency_route, parent, false)
            val route = getItem(position)!!
            row.findViewById<TextView>(R.id.routeTitle).text = route.label()

            val steps = StringBuilder()
            for (i in route.stepSymbols.indices) {
                if (i > 0) steps.append(" · ")
                val symbol = route.stepSymbols[i]
                steps.append(if (route.stepReverse[i]) getString(R.string.route_step_reversed, symbol) else symbol)
            }
            row.findViewById<TextView>(R.id.routeSteps).text = steps
            row.findViewById<View>(R.id.routeDelete).setOnClickListener { confirmDelete(route) }
            return row
        }
    }

    private companion object {
        const val REQUEST_FROM = 310
        const val REQUEST_VIA1 = 311
        const val REQUEST_VIA2 = 312
        const val REQUEST_TO = 313
    }
}
