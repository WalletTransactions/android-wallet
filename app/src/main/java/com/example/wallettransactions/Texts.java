package com.example.wallettransactions;

import android.content.Context;

/**
 * Тексты интерфейса для классов без своего экрана — импорт, синк, ИИ (openspec
 * android-i18n, design D2): журнал синка, отчёт импорта и т.п. строятся глубоко в логике,
 * куда {@code Context} не передаётся. Берёт строки из ресурсов приложения на текущем
 * языке; контекст ставит {@code WalletApplication.onCreate}.
 */
public final class Texts {

    private static volatile Context app;

    private Texts() {}

    public static void init(Context context) {
        app = context.getApplicationContext();
    }

    private static Context ctx() {
        Context c = app;
        if (c == null) throw new IllegalStateException("Texts.init was not called (WalletApplication.onCreate)");
        return c;
    }

    public static String get(int resId) {
        return ctx().getString(resId);
    }

    public static String get(int resId, Object... args) {
        return ctx().getString(resId, args);
    }

    /** Строка с числом по правилам языка ({@code <plurals>}); {@code n} — и выбор формы, и первый аргумент. */
    public static String plural(int resId, int n, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = n;
        System.arraycopy(args, 0, all, 1, args.length);
        return ctx().getResources().getQuantityString(resId, n, all);
    }
}
