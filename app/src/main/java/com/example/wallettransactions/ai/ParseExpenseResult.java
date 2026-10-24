package com.example.wallettransactions.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Результат задания {@code parse_expense} — {@code ParseExpenseResult} из
 * wallet-ai/openapi.yaml (там же — что значит каждое поле). Только разбор
 * JSON, без решений: что показать в карточке, решают {@link DraftReconciler}
 * и экран «ИИ».
 */
public final class ParseExpenseResult {

    public static final String DRAFT = "draft";
    public static final String NOT_EXPENSE = "not_expense";
    public static final String REJECTED = "rejected";

    public final String outcome;
    public final String promptVersion;
    public final List<String> reasons;
    public final List<String> notes;
    /** {@code null}, если не {@link #DRAFT}. */
    public final Expense expense;
    /** {@code null}, если не {@link #DRAFT}. */
    public final Resolution resolution;

    private ParseExpenseResult(String outcome, String promptVersion, List<String> reasons, List<String> notes,
                               Expense expense, Resolution resolution) {
        this.outcome = outcome;
        this.promptVersion = promptVersion;
        this.reasons = reasons;
        this.notes = notes;
        this.expense = expense;
        this.resolution = resolution;
    }

    public boolean isDraft() {
        return DRAFT.equals(outcome);
    }

    public static ParseExpenseResult fromJson(JSONObject json) {
        JSONObject e = json.optJSONObject("expense");
        JSONObject r = json.optJSONObject("resolution");
        return new ParseExpenseResult(
                json.optString("outcome", ""),
                json.optString("prompt_version", ""),
                strings(json.optJSONArray("reasons")),
                strings(json.optJSONArray("notes")),
                e == null ? null : Expense.fromJson(e),
                r == null ? null : Resolution.fromJson(r));
    }

    /** Что модель поняла из текста — строки как в ответе. */
    public static final class Expense {
        public final String amount;
        public final String currencyHint;
        public final String category;
        public final String wallet;
        /** {@code YYYY-MM-DD HH:MM:SS}. */
        public final String datetime;
        public final String comment;

        Expense(String amount, String currencyHint, String category, String wallet, String datetime, String comment) {
            this.amount = amount;
            this.currencyHint = currencyHint;
            this.category = category;
            this.wallet = wallet;
            this.datetime = datetime;
            this.comment = comment;
        }

        static Expense fromJson(JSONObject json) {
            return new Expense(json.optString("amount", ""), json.optString("currency_hint", ""),
                    json.optString("category", ""), json.optString("wallet", ""),
                    json.optString("datetime", ""), json.optString("comment", ""));
        }
    }

    /** Счёт из справочника сервера: {@code Named} или {@code Wallet} в openapi. */
    public static final class Ref {
        public final String uuid;
        public final String name;
        /** Только у кошелька, иначе пусто. */
        public final String isoCode;
        public final String symbol;
        public final int precision;

        public Ref(String uuid, String name, String isoCode, String symbol, int precision) {
            this.uuid = uuid;
            this.name = name;
            this.isoCode = isoCode;
            this.symbol = symbol;
            this.precision = precision;
        }

        static Ref fromJson(JSONObject json) {
            if (json == null) return null;
            return new Ref(json.optString("uuid", ""), json.optString("name", ""),
                    json.optString("iso_code", ""), json.optString("symbol", ""), json.optInt("precision", 2));
        }
    }

    /** Решение resolver по одному полю (кошелёк или категория). */
    public static final class Choice {
        public static final String FOUND = "found";
        public static final String ASK = "ask";
        public static final String REJECT = "reject";

        public final String kind;
        public final String why;
        public final Ref found;
        /** Только у кошелька при {@code currency_mismatch}. */
        public final Ref force;
        public final List<Ref> candidates;

        public Choice(String kind, String why, Ref found, Ref force, List<Ref> candidates) {
            this.kind = kind;
            this.why = why;
            this.found = found;
            this.force = force;
            this.candidates = candidates;
        }

        static Choice fromJson(JSONObject json) {
            if (json == null) return new Choice(ASK, "missing", null, null, Collections.emptyList());
            List<Ref> candidates = new ArrayList<>();
            JSONArray arr = json.optJSONArray("candidates");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Ref ref = Ref.fromJson(arr.optJSONObject(i));
                    if (ref != null) candidates.add(ref);
                }
            }
            return new Choice(json.optString("kind", ASK), json.optString("why", ""),
                    Ref.fromJson(json.optJSONObject("found")), Ref.fromJson(json.optJSONObject("force")),
                    candidates);
        }
    }

    public static final class Resolution {
        public final Choice wallet;
        public final Choice category;

        public Resolution(Choice wallet, Choice category) {
            this.wallet = wallet;
            this.category = category;
        }

        static Resolution fromJson(JSONObject json) {
            return new Resolution(Choice.fromJson(json.optJSONObject("wallet")),
                    Choice.fromJson(json.optJSONObject("category")));
        }
    }

    private static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        return out;
    }
}
