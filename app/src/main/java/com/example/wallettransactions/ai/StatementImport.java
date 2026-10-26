package com.example.wallettransactions.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Импорт выписки wallet-ai ({@code Import}/{@code ImportDetail} в wallet-ai/openapi.yaml;
 * openspec add-ai-statement-import). Строки есть только в {@code ready}/{@code done}.
 * Незнакомые статусы, группы и поля разбор не ломают: группу, которую приложение
 * не знает, экран показывает как «не для записи» ({@link StatementRowState}).
 */
public final class StatementImport {

    public static final String QUEUED = "queued";
    public static final String EXTRACTING = "extracting";
    public static final String CLASSIFYING = "classifying";
    public static final String READY = "ready";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    /** Строка исходника выписки ({@code lines[]} у строки импорта). */
    public static final class Line {
        public final int n;
        public final String text;

        Line(int n, String text) {
            this.n = n;
            this.text = text;
        }
    }

    /** Строка импорта ({@code ImportRow}). */
    public static final class Row {
        public final String rowId;
        public final String group;
        public final List<Line> lines;
        /** {@code ГГГГ-ММ-ДД}. */
        public final String date;
        /** {@code 1290.00} — положительная. */
        public final String amount;
        /** {@code expense}, {@code income}, {@code transfer}. */
        public final String dir;
        public final String description;
        public final String categoryUuid;
        /** Вариант модели ниже порога уверенности — имя категории. */
        public final String suggestion;
        /** {@code amount_not_in_text}, {@code date_not_in_text}, {@code currency_mismatch} или null. */
        public final String check;
        public final boolean committed;

        public Row(String rowId, String group, List<Line> lines, String date, String amount, String dir,
                   String description, String categoryUuid, String suggestion, String check, boolean committed) {
            this.rowId = rowId;
            this.group = group;
            this.lines = lines;
            this.date = date;
            this.amount = amount;
            this.dir = dir;
            this.description = description;
            this.categoryUuid = categoryUuid;
            this.suggestion = suggestion;
            this.check = check;
            this.committed = committed;
        }

        public boolean isExpense() {
            return "expense".equals(dir);
        }

        /** Сумма числом; 0 — не разобралась. */
        public double amountValue() {
            try {
                return amount == null ? 0 : Double.parseDouble(amount.replace(',', '.'));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Текст строк выписки, из которых взята строка, — по строке на номер. */
        public String sourceText() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.text);
            }
            return sb.toString();
        }

        static Row fromJson(JSONObject j) {
            List<Line> lines = new ArrayList<>();
            JSONArray arr = j.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.optJSONObject(i);
                    if (l != null) lines.add(new Line(l.optInt("n", 0), str(l, "text")));
                }
            }
            return new Row(str(j, "row_id"), str(j, "group"), Collections.unmodifiableList(lines), str(j, "date"),
                    str(j, "amount"), str(j, "dir"), str(j, "description"), str(j, "category_uuid"),
                    str(j, "suggestion"), str(j, "check"), j.optBoolean("committed", false));
        }
    }

    public final String id;
    public final String status;
    public final String failure;
    public final String walletUuid;
    public final String sourceName;
    public final int progressDone;
    public final int progressTotal;
    /** LLM недоступна — разбор на паузе с этого момента; null — не на паузе. */
    public final String pausedSince;
    public final List<Row> rows;

    public StatementImport(String id, String status, String failure, String walletUuid, String sourceName,
                           int progressDone, int progressTotal, String pausedSince, List<Row> rows) {
        this.id = id;
        this.status = status;
        this.failure = failure;
        this.walletUuid = walletUuid;
        this.sourceName = sourceName;
        this.progressDone = progressDone;
        this.progressTotal = progressTotal;
        this.pausedSince = pausedSince;
        this.rows = rows;
    }

    /** Разбор ещё идёт: в очереди, извлечение строк или подбор категорий. */
    public boolean isActive() {
        return QUEUED.equals(status) || EXTRACTING.equals(status) || CLASSIFYING.equals(status);
    }

    /** Строки готовы к проверке: {@code ready} или {@code done}. */
    public boolean hasRows() {
        return READY.equals(status) || DONE.equals(status);
    }

    public Row row(String rowId) {
        for (Row r : rows) if (r.rowId.equals(rowId)) return r;
        return null;
    }

    public static StatementImport fromJson(JSONObject j) {
        String id = str(j, "import_id");
        JSONObject progress = j.optJSONObject("progress");
        List<Row> rows = new ArrayList<>();
        JSONArray arr = j.optJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) rows.add(Row.fromJson(r));
            }
        }
        return new StatementImport(id, str(j, "status"), str(j, "failure"), str(j, "wallet_uuid"),
                str(j, "source_name"), progress != null ? progress.optInt("done", 0) : 0,
                progress != null ? progress.optInt("total", 0) : 0, str(j, "paused_since"),
                Collections.unmodifiableList(rows));
    }

    /** {@code GET /api/ai/imports}: новые первыми, без строк. */
    public static List<StatementImport> listFromJson(JSONObject j) {
        List<StatementImport> out = new ArrayList<>();
        JSONArray arr = j.optJSONArray("imports");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }

    /** Строка или null: {@code optString} отдаёт для JSON null текст «null». */
    private static String str(JSONObject j, String key) {
        return j.isNull(key) ? null : j.optString(key, null);
    }
}
