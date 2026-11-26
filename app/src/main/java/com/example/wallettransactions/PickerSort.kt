package com.example.wallettransactions

import android.content.Context

/**
 * Порядок строк на экране выбора счёта (`AccountPickerActivity`). У кошельков
 * и категорий он свой и хранится отдельно ([AppSettings.getPickerSort]):
 * остатка у категории нет, поэтому [BALANCE] ей не предлагается.
 */
enum class PickerSort(private val mTitle: Int) {
    NAME(R.string.picker_sort_name),

    /** По эквиваленту остатка, крупные сверху — как в отчёте «Кошельки». */
    BALANCE(R.string.picker_sort_balance),

    /**
     * По числу операций. Для категории считаются сначала операции с кошельком из
     * второго слота операции, ниже — общая частота (WalletDbLoader.LoadAccountUsage).
     */
    USAGE(R.string.picker_sort_usage);

    fun getTitle(context: Context): String = context.getString(mTitle)

    companion object {
        @JvmField
        val WALLET_MODES = arrayOf(BALANCE, USAGE, NAME)

        @JvmField
        val CATEGORY_MODES = arrayOf(USAGE, NAME)

        @JvmStatic
        fun titles(context: Context, modes: Array<PickerSort>): Array<String> =
            Array(modes.size) { modes[it].getTitle(context) }

        @JvmStatic
        fun indexOf(modes: Array<PickerSort>, mode: PickerSort?): Int {
            for (i in modes.indices) if (modes[i] == mode) return i
            return 0
        }

        /** Неизвестное или пустое имя — `fallback`: настройка могла остаться от другой версии. */
        @JvmStatic
        fun fromName(name: String?, fallback: PickerSort?): PickerSort? {
            if (name == null) return fallback
            return try {
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                fallback
            }
        }
    }
}
