package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.wallettransactions.Data.AccountNames;
import com.example.wallettransactions.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Диалог выбора из списка с полем «Поиск по названию» (openspec wallets-filter-and-debt-autoarchive,
 * спека debts «Поиск по имени контрагента»): подстрока, без регистра и различия «ё»/«е»
 * ({@link AccountNames#matchesSearch}); ничего не нашлось — «Ничего не найдено». Закреплённые
 * строки (например, «Новый контрагент») поиском не скрываются.
 */
final class SearchableListDialog {
    private SearchableListDialog() {}

    interface OnPick {
        /** @param index индекс в списке {@code items}, а не в отфильтрованном */
        void onPick(int index);
    }

    /**
     * @param pinned строки сверху, всегда видимые; номер в {@link OnPick} — {@code -1 - позиция}
     *               (−1 — первая закреплённая)
     */
    static void show(Activity activity, String title, List<String> pinned,
                     List<String> items, OnPick onPick) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText search = new EditText(activity);
        search.setHint(R.string.search_by_name_hint);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(search);

        ListView listView = new ListView(activity);
        box.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * density)));

        TextView empty = new TextView(activity);
        empty.setText(R.string.search_nothing_found);
        empty.setPadding(0, pad / 2, 0, pad / 2);
        empty.setVisibility(View.GONE);
        box.addView(empty);

        // Строки списка: закреплённые, затем подходящие под поиск; код — индекс в исходных списках.
        List<Integer> codes = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        listView.setAdapter(adapter);

        Runnable refill = () -> {
            adapter.clear();
            codes.clear();
            for (int i = 0; i < pinned.size(); i++) {
                adapter.add(pinned.get(i));
                codes.add(-1 - i);
            }
            String q = search.getText().toString();
            int shown = 0;
            for (int i = 0; i < items.size(); i++) {
                if (!AccountNames.matchesSearch(items.get(i), q)) continue;
                adapter.add(items.get(i));
                codes.add(i);
                shown++;
            }
            adapter.notifyDataSetChanged();
            empty.setVisibility(shown == 0 && !items.isEmpty() ? View.VISIBLE : View.GONE);
        };
        refill.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refill.run(); }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        listView.setOnItemClickListener((p, v, position, id) -> {
            dialog.dismiss();
            onPick.onPick(codes.get(position));
        });
        dialog.show();
    }
}
