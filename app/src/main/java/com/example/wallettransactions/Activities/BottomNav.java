package com.example.wallettransactions.Activities;

import android.app.Activity;
import androidx.core.content.ContextCompat;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.R;

/**
 * Подсветка активного таба нижней панели (view_bottom_nav.xml).
 *
 * Панель — один и тот же layout на всех четырёх корневых экранах, и в XML
 * подсвечены «Операции». Каждый экран после setContentView зовёт
 * setActiveTab со своим id, иначе на «Кошельках», «Отчёте» и «Ещё»
 * горит чужой пункт.
 */
public final class BottomNav {

    private static final int[] TABS = {
            R.id.navOps, R.id.navWallets, R.id.navReport, R.id.navMore
    };

    private BottomNav() {}

    public static void setActiveTab(Activity activity, int activeTabId) {
        for (int id : TABS) {
            ViewGroup tab = activity.findViewById(id);
            if (tab == null) continue;

            int color = ContextCompat.getColor(activity,
                    id == activeTabId ? R.color.colorPrimary : R.color.colorTextHint);

            ((ImageView) tab.getChildAt(0)).setColorFilter(color);
            ((TextView) tab.getChildAt(1)).setTextColor(color);
        }
    }
}
