package com.example.wallettransactions;

/**
 * Порядок строк на экране выбора счёта ({@code AccountPickerActivity}). У кошельков
 * и категорий он свой и хранится отдельно ({@link AppSettings#getPickerSort}):
 * остатка у категории нет, поэтому {@link #BALANCE} ей не предлагается.
 */
public enum PickerSort {
    NAME("По алфавиту"),
    /** По эквиваленту остатка, крупные сверху — как в отчёте «Кошельки». */
    BALANCE("По остатку"),
    /**
     * По числу операций. Для категории считаются сначала операции с кошельком из
     * второго слота операции, ниже — общая частота (WalletDbLoader.LoadAccountUsage).
     */
    USAGE("По частоте");

    public static final PickerSort[] WALLET_MODES = {BALANCE, USAGE, NAME};
    public static final PickerSort[] CATEGORY_MODES = {USAGE, NAME};

    private final String mTitle;

    PickerSort(String title) {
        mTitle = title;
    }

    public String getTitle() {
        return mTitle;
    }

    public static String[] titles(PickerSort[] modes) {
        String[] result = new String[modes.length];
        for (int i = 0; i < modes.length; i++) result[i] = modes[i].getTitle();
        return result;
    }

    public static int indexOf(PickerSort[] modes, PickerSort mode) {
        for (int i = 0; i < modes.length; i++) if (modes[i] == mode) return i;
        return 0;
    }

    /** Неизвестное или пустое имя — {@code fallback}: настройка могла остаться от другой версии. */
    public static PickerSort fromName(String name, PickerSort fallback) {
        if (name == null) return fallback;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
