package com.example.wallettransactions.sync;

import android.content.Context;
import android.util.Log;

import com.example.wallettransactions.AppSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Строки pull, которые нельзя было применить, потому что их родитель (иконка,
 * валюта, счёт, пара, проводка) ещё не пришёл (найдено на проверке #5, 2026-11-03).
 * <p>
 * Сервер отдаёт строку под её ПОСЛЕДНИМ seq: валюта, которую недавно правили
 * (сняли флаг эквивалента), уезжает в хвост потока, а ссылающаяся на неё строка
 * {@code account_operating} остаётся на одной из первых страниц. Раньше такую
 * строку пропускали с {@code Log.w}, курсор уходил дальше, и сервер её больше не
 * присылал: кошелёк оставался без валюты навсегда. Теперь строка ждёт здесь и
 * повторяется вместе с каждой следующей страницей и в конце pull.
 * <p>
 * Хранится в файле, а не в памяти: курсор сохраняется после каждой страницы, и
 * если процесс умрёт между страницами, отложенное не должно пропасть вместе с ним.
 * Файл, а не SharedPreferences: при полной перезагрузке отложенных могут быть
 * тысячи операций (счёт переименовали — он в хвосте потока, его операции в начале).
 */
final class PullDeferredRows {

    private static final String TAG = "PullDeferredRows";
    static final String FILE_NAME = AppSettings.SYNC_PULL_DEFERRED_FILE;

    /** table → (uuid → строка как пришла с сервера); порядок вставки сохраняется. */
    private final Map<String, LinkedHashMap<String, JSONObject>> byTable = new LinkedHashMap<>();
    /** {@code null} — без сохранения на диск (тесты applyPage). */
    private final File file;

    private PullDeferredRows(File file) {
        this.file = file;
    }

    static PullDeferredRows inMemory() {
        return new PullDeferredRows(null);
    }

    static PullDeferredRows load(Context ctx) {
        PullDeferredRows rows = new PullDeferredRows(new File(ctx.getNoBackupFilesDir(), FILE_NAME));
        if (!rows.file.exists()) return rows;
        try {
            String text = new String(Files.readAllBytes(rows.file.toPath()), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(text);
            Iterator<String> tables = json.keys();
            while (tables.hasNext()) {
                String table = tables.next();
                JSONArray arr = json.getJSONArray(table);
                for (int i = 0; i < arr.length(); i++) rows.add(table, arr.getJSONObject(i));
            }
        } catch (IOException | JSONException e) {
            // Битый файл не должен ронять синк. Потерянные строки вернёт только
            // перезагрузка с нуля, поэтому громко в лог.
            Log.e(TAG, "не удалось прочитать " + FILE_NAME + " — отложенные строки потеряны", e);
            rows.byTable.clear();
        }
        return rows;
    }

    void add(String table, JSONObject row) {
        String uuid = row.optString("uuid", null);
        if (uuid == null) return;
        LinkedHashMap<String, JSONObject> rows = byTable.get(table);
        if (rows == null) {
            rows = new LinkedHashMap<>();
            byTable.put(table, rows);
        }
        rows.put(uuid, row);
    }

    /**
     * Отложенные строки таблицы, кроме тех, чью более новую версию принесла
     * текущая страница: применять старую поверх новой нельзя, а новая всё равно
     * пройдёт через тот же apply и при нужде отложится сама.
     */
    List<JSONObject> rowsToRetry(String table, Set<String> uuidsInPage) {
        LinkedHashMap<String, JSONObject> rows = byTable.get(table);
        if (rows == null) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>(rows.size());
        for (Map.Entry<String, JSONObject> e : rows.entrySet()) {
            if (!uuidsInPage.contains(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    /** Подменяет содержимое — после того как транзакция страницы закоммичена. */
    void replaceWith(PullDeferredRows other) {
        byTable.clear();
        byTable.putAll(other.byTable);
    }

    void clear() {
        byTable.clear();
    }

    boolean isEmpty() {
        return size() == 0;
    }

    int size() {
        int n = 0;
        for (LinkedHashMap<String, JSONObject> rows : byTable.values()) n += rows.size();
        return n;
    }

    /** «account_operating: 5, operation: 2» — для лога и SyncLog.problem. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LinkedHashMap<String, JSONObject>> e : byTable.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(": ").append(e.getValue().size());
        }
        return sb.toString();
    }

    /** Записывает на диск (через временный файл, чтобы обрыв не оставил полфайла). */
    void save() throws IOException {
        if (file == null) return;
        if (isEmpty()) {
            if (file.exists() && !file.delete()) throw new IOException("не удалось удалить " + file);
            return;
        }
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, LinkedHashMap<String, JSONObject>> e : byTable.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                JSONArray arr = new JSONArray();
                for (JSONObject row : e.getValue().values()) arr.put(row);
                json.put(e.getKey(), arr);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new IOException("не удалось переименовать " + tmp + " в " + file);
    }
}
