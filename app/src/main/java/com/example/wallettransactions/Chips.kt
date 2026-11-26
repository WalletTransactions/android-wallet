package com.example.wallettransactions

import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Окраска плоских чипов `FilterChip`: выбранный — `bg_chip_selected` и светлый текст,
 * остальные — `bg_chip_unselected`. Так же красит чипы типа операции `FilterActivity`;
 * здесь — для чипов направления («Долги») и секций («Кошельки»).
 */
object Chips {
    @JvmStatic
    fun paint(ctx: Context, chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(ctx, if (active) R.color.colorSurface else R.color.colorTextSecondary))
    }
}
