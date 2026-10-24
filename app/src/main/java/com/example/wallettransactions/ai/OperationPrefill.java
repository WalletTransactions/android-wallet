package com.example.wallettransactions.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Поля окна «Новая операция» из черновика wallet-ai (spec ai-expense-entry,
 * «Карточка черновика», «Вопрос resolver — выбор в карточке»). Счета — только
 * живые на телефоне (через {@link DraftReconciler}): выбранный resolver кошелёк
 * или категория, которых на телефоне нет, остаются пустыми и попадают в
 * {@link #walletProblem}/{@link #categoryProblem} — тогда экран «ИИ» сначала
 * показывает блок ошибки, а не окно.
 */
public final class OperationPrefill {

    public final double amount;
    /** {@code yyyy-MM-dd HH:mm} — формат окна операции ({@code DateUtils}); null — «сейчас». */
    public final String date;
    public final String comment;
    /** Локальный {@code _id}; 0 — не выбран. */
    public final int walletId;
    public final int categoryId;
    /** Кандидаты {@code ask} и {@code force} — первыми в пикерах, по порядку resolver. */
    public final int[] priorityWalletIds;
    public final int[] priorityCategoryIds;
    public final String hint;
    public final DraftReconciler.Local walletProblem;
    public final DraftReconciler.Local categoryProblem;

    private OperationPrefill(double amount, String date, String comment, int walletId, int categoryId,
                             int[] priorityWalletIds, int[] priorityCategoryIds, String hint,
                             DraftReconciler.Local walletProblem, DraftReconciler.Local categoryProblem) {
        this.amount = amount;
        this.date = date;
        this.comment = comment;
        this.walletId = walletId;
        this.categoryId = categoryId;
        this.priorityWalletIds = priorityWalletIds;
        this.priorityCategoryIds = priorityCategoryIds;
        this.hint = hint;
        this.walletProblem = walletProblem;
        this.categoryProblem = categoryProblem;
    }

    /** Есть пропавший с телефона счёт — окно сразу не открывается. */
    public boolean hasProblem() {
        return walletProblem != null || categoryProblem != null;
    }

    public static OperationPrefill build(SQLiteDatabase db, ParseExpenseResult draft, String sourceText) {
        ParseExpenseResult.Expense e = draft.expense;
        DraftReconciler.Field w = DraftReconciler.reconcile(db, draft.resolution.wallet);
        DraftReconciler.Field c = DraftReconciler.reconcile(db, draft.resolution.category);

        int walletId = w.selected != null && isWallet(db, w.selected.id, true) ? (int) w.selected.id : 0;
        int categoryId = c.selected != null && isWallet(db, c.selected.id, false) ? (int) c.selected.id : 0;

        List<DraftReconciler.Local> walletPriority = new ArrayList<>(w.candidates);
        if (w.force != null) walletPriority.add(w.force);

        return new OperationPrefill(parseAmount(e.amount), toWindowDate(e.datetime), e.comment,
                walletId, categoryId, ids(db, walletPriority, true), ids(db, c.candidates, false),
                AiTexts.operationHint(sourceText, draft), w.problem, c.problem);
    }

    /** {@code YYYY-MM-DD HH:MM:SS} → {@code YYYY-MM-DD HH:MM}; пусто — null («сейчас»). */
    static String toWindowDate(String datetime) {
        if (datetime == null || datetime.length() < 16) return null;
        return datetime.substring(0, 16);
    }

    static double parseAmount(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int[] ids(SQLiteDatabase db, List<DraftReconciler.Local> locals, boolean wallets) {
        List<Integer> out = new ArrayList<>();
        for (DraftReconciler.Local l : locals) {
            int id = (int) l.id;
            if (!out.contains(id) && isWallet(db, l.id, wallets)) out.add(id);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Кошелёк ли это ({@code account.kind = 1}) — resolver мог прислать не тот вид счёта. */
    private static boolean isWallet(SQLiteDatabase db, long id, boolean wallet) {
        Cursor cur = db.rawQuery("SELECT kind FROM account WHERE _id=? LIMIT 1", new String[]{String.valueOf(id)});
        try {
            return cur.moveToFirst() && (cur.getInt(0) == 1) == wallet;
        } finally {
            cur.close();
        }
    }
}
