package com.example.wallettransactions;

import android.content.Context;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Окраска плоских чипов {@code FilterChip}: выбранный — {@code bg_chip_selected} и светлый текст,
 * остальные — {@code bg_chip_unselected}. Так же красит чипы типа операции {@code FilterActivity};
 * здесь — для чипов направления («Долги») и секций («Кошельки»).
 */
public final class Chips {
    private Chips() {}

    public static void paint(Context ctx, TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(ctx, active ? R.color.colorSurface : R.color.colorTextSecondary));
    }
}
