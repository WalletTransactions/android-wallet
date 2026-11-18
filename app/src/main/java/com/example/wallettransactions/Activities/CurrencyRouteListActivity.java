package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.wallettransactions.Data.CurrencyRoutes;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Экран «Маршруты пересчёта» (openspec currency-conversion-routes, spec currency-routes): список
 * маршрутов вида «BTC → EUR → RUB», создание по пути из 3–4 валют и удаление. Пары и направление
 * шагов подбирает {@link CurrencyRoutes#create}; курс по маршруту считает кэш
 * {@code DerivedRates}. Пишет только локально — шаги уйдут на сервер обычным push (dirty ставит триггер).
 */
public class CurrencyRouteListActivity extends AppCompatActivity {

    private static final int REQUEST_FROM = 310;
    private static final int REQUEST_VIA1 = 311;
    private static final int REQUEST_VIA2 = 312;
    private static final int REQUEST_TO = 313;

    /** ISO выбранных валют формы: «Из», «Через», вторая «Через», «В»; null — не выбрана. */
    private final String[] selectedIso = new String[4];
    private boolean twoVia;

    private RouteAdapter adapter;
    private ListView listRoutes;
    private TextView routeEmpty;

    private View dialogView;
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_route_list);

        listRoutes = findViewById(R.id.listRoutes);
        routeEmpty = findViewById(R.id.routeEmpty);
        adapter = new RouteAdapter(this, new ArrayList<>());
        listRoutes.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    public void btnUp(View view) {
        finish();
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    private void refresh() {
        List<CurrencyRoutes.Route> routes = CurrencyRoutes.list(db());
        adapter.clear();
        adapter.addAll(routes);
        adapter.notifyDataSetChanged();
        boolean empty = routes.isEmpty();
        routeEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        listRoutes.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ── удаление ────────────────────────────────────────────────────────────────

    private void confirmDelete(CurrencyRoutes.Route route) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.route_delete_question, route.label()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    CurrencyRoutes.delete(db(), route.fromId, route.toId);
                    refresh();
                })
                .show();
    }

    // ── форма создания ──────────────────────────────────────────────────────────

    /** btnAddRoute (activity_currency_route_list.xml android:onClick). */
    public void onAddRoute(View view) {
        for (int i = 0; i < selectedIso.length; i++) selectedIso[i] = null;
        twoVia = false;
        dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_route, null);
        dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        renderForm();
        dialog.show();
    }

    private void renderForm() {
        setValue(R.id.routeFromValue, selectedIso[0]);
        setValue(R.id.routeVia1Value, selectedIso[1]);
        setValue(R.id.routeVia2Value, selectedIso[2]);
        setValue(R.id.routeToValue, selectedIso[3]);
        dialogView.findViewById(R.id.blockRouteVia2).setVisibility(twoVia ? View.VISIBLE : View.GONE);
        ((TextView) dialogView.findViewById(R.id.btnRouteToggleVia))
                .setText(twoVia ? R.string.route_dialog_remove_via : R.string.route_dialog_add_via);
    }

    private void setValue(int viewId, String iso) {
        ((TextView) dialogView.findViewById(viewId)).setText(iso != null ? iso : getString(R.string.pair_choose));
    }

    private void showError(String text) {
        TextView error = dialogView.findViewById(R.id.routeDialogError);
        error.setText(text);
        error.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void pick(int slot, int request, int titleRes) {
        startActivityForResult(
                CurrencyPickerActivity.buildIntent(this, getString(titleRes), selectedIso[slot]), request);
    }

    /** rowRouteFrom (dialog_new_route.xml android:onClick). */
    public void onPickRouteFrom(View view) {
        pick(0, REQUEST_FROM, R.string.route_dialog_from);
    }

    /** rowRouteVia1 (dialog_new_route.xml android:onClick). */
    public void onPickRouteVia1(View view) {
        pick(1, REQUEST_VIA1, R.string.route_dialog_via);
    }

    /** rowRouteVia2 (dialog_new_route.xml android:onClick). */
    public void onPickRouteVia2(View view) {
        pick(2, REQUEST_VIA2, R.string.route_dialog_via);
    }

    /** rowRouteTo (dialog_new_route.xml android:onClick). */
    public void onPickRouteTo(View view) {
        pick(3, REQUEST_TO, R.string.route_dialog_to);
    }

    /** btnRouteToggleVia: добавляет или убирает вторую промежуточную валюту. */
    public void onToggleRouteVia(View view) {
        twoVia = !twoVia;
        if (!twoVia) selectedIso[2] = null;
        showError(null);
        renderForm();
    }

    /** Отмена в листе (dialog_new_route.xml android:onClick). */
    public void onCancelRoute(View view) {
        if (dialog != null) dialog.dismiss();
    }

    /** «Создать» в листе (dialog_new_route.xml android:onClick): проверки до записи, затем создание. */
    public void onCreateRoute(View view) {
        List<String> isos = new ArrayList<>();
        isos.add(selectedIso[0]);
        isos.add(selectedIso[1]);
        if (twoVia) isos.add(selectedIso[2]);
        isos.add(selectedIso[3]);

        Set<String> seen = new HashSet<>();
        for (String iso : isos) {
            if (iso == null) {
                showError(getString(R.string.route_error_choose_all));
                return;
            }
        }
        for (String iso : isos) {
            if (!seen.add(iso)) {
                showError(getString(R.string.route_error_duplicate_currency));
                return;
            }
        }

        long[] path = new long[isos.size()];
        for (int i = 0; i < path.length; i++) {
            Currency currency = Singleton.instance.getCurrencyList(this).getCurrencyByISO(isos.get(i));
            if (currency == null) {
                showError(getString(R.string.route_error_generic));
                return;
            }
            path[i] = currency.getId();
        }

        CurrencyRoutes.CreateResult result = CurrencyRoutes.create(db(), path);
        switch (result.error) {
            case OK:
                if (dialog != null) dialog.dismiss();
                refresh();
                return;
            case NO_PAIR:
                showError(getString(R.string.route_error_no_pair, result.detail));
                return;
            case ALREADY_EXISTS:
                showError(getString(R.string.route_error_exists));
                return;
            case DUPLICATE_CURRENCY:
                showError(getString(R.string.route_error_duplicate_currency));
                return;
            default:
                showError(getString(R.string.route_error_generic));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || dialogView == null) return;
        String iso = data.getStringExtra(CurrencyPickerActivity.RESULT_ISO);
        if (iso == null) return;
        int slot;
        switch (requestCode) {
            case REQUEST_FROM: slot = 0; break;
            case REQUEST_VIA1: slot = 1; break;
            case REQUEST_VIA2: slot = 2; break;
            case REQUEST_TO: slot = 3; break;
            default: return;
        }
        selectedIso[slot] = iso;
        showError(null);
        renderForm();
    }

    // ── список ──────────────────────────────────────────────────────────────────

    private final class RouteAdapter extends ArrayAdapter<CurrencyRoutes.Route> {

        RouteAdapter(Context context, List<CurrencyRoutes.Route> items) {
            super(context, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_currency_route, parent, false);
            CurrencyRoutes.Route route = getItem(position);
            ((TextView) row.findViewById(R.id.routeTitle)).setText(route.label());

            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < route.stepSymbols.size(); i++) {
                if (i > 0) steps.append(" · ");
                String symbol = route.stepSymbols.get(i);
                steps.append(route.stepReverse.get(i) ? getString(R.string.route_step_reversed, symbol) : symbol);
            }
            ((TextView) row.findViewById(R.id.routeSteps)).setText(steps);
            row.findViewById(R.id.routeDelete).setOnClickListener(v -> confirmDelete(route));
            return row;
        }
    }
}
