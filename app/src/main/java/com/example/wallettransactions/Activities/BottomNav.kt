package com.example.wallettransactions.Activities

import android.app.Activity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.R

/**
 * Подсветка активного таба нижней панели (view_bottom_nav.xml).
 *
 * Панель — один и тот же layout на всех четырёх корневых экранах, и в XML
 * подсвечены «Операции». Каждый экран после setContentView зовёт
 * setActiveTab со своим id, иначе на «Кошельках», «Отчёте» и «Ещё»
 * горит чужой пункт.
 */
object BottomNav {

    private val TABS = intArrayOf(
        R.id.navOps, R.id.navWallets, R.id.navReport, R.id.navMore
    )

    @JvmStatic
    fun setActiveTab(activity: Activity, activeTabId: Int) {
        for (id in TABS) {
            val tab = activity.findViewById<ViewGroup>(id) ?: continue

            val color = ContextCompat.getColor(
                activity,
                if (id == activeTabId) R.color.colorPrimary else R.color.colorTextHint
            )

            (tab.getChildAt(0) as ImageView).setColorFilter(color)
            (tab.getChildAt(1) as TextView).setTextColor(color)
        }
    }
}
