package com.example.wallettransactions.sync

import android.content.Context
import android.util.Log
import com.example.wallettransactions.AppSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Строки pull, которые нельзя было применить, потому что их родитель (иконка,
 * валюта, счёт, пара, проводка) ещё не пришёл (найдено на проверке #5, 2026-11-03).
 *
 * Сервер отдаёт строку под её ПОСЛЕДНИМ seq: валюта, которую недавно правили
 * (сняли флаг эквивалента), уезжает в хвост потока, а ссылающаяся на неё строка
 * `account_operating` остаётся на одной из первых страниц. Раньше такую
 * строку пропускали с `Log.w`, курсор уходил дальше, и сервер её больше не
 * присылал: кошелёк оставался без валюты навсегда. Теперь строка ждёт здесь и
 * повторяется вместе с каждой следующей страницей и в конце pull.
 *
 * Хранится в файле, а не в памяти: курсор сохраняется после каждой страницы, и
 * если процесс умрёт между страницами, отложенное не должно пропасть вместе с ним.
 * Файл, а не SharedPreferences: при полной перезагрузке отложенных могут быть
 * тысячи операций (счёт переименовали — он в хвосте потока, его операции в начале).
 */
internal class PullDeferredRows private constructor(
    /** `null` — без сохранения на диск (тесты applyPage). */
    private val file: File?
) {

    /** table → (uuid → строка как пришла с сервера); порядок вставки сохраняется. */
    private val byTable = LinkedHashMap<String, LinkedHashMap<String, JSONObject>>()

    fun add(table: String, row: JSONObject) {
        val uuid = row.optString("uuid", null) ?: return
        var rows = byTable[table]
        if (rows == null) {
            rows = LinkedHashMap()
            byTable[table] = rows
        }
        rows[uuid] = row
    }

    /**
     * Отложенные строки таблицы, кроме тех, чью более новую версию принесла
     * текущая страница: применять старую поверх новой нельзя, а новая всё равно
     * пройдёт через тот же apply и при нужде отложится сама.
     */
    fun rowsToRetry(table: String, uuidsInPage: Set<String>): List<JSONObject> {
        val rows = byTable[table] ?: return emptyList()
        val out = ArrayList<JSONObject>(rows.size)
        for ((key, value) in rows) {
            if (!uuidsInPage.contains(key)) out.add(value)
        }
        return out
    }

    /** Подменяет содержимое — после того как транзакция страницы закоммичена. */
    fun replaceWith(other: PullDeferredRows) {
        byTable.clear()
        byTable.putAll(other.byTable)
    }

    fun clear() {
        byTable.clear()
    }

    fun isEmpty(): Boolean = size() == 0

    fun size(): Int {
        var n = 0
        for (rows in byTable.values) n += rows.size
        return n
    }

    /** «account_operating: 5, operation: 2» — для лога и SyncLog.problem. */
    fun summary(): String {
        val sb = StringBuilder()
        for ((key, value) in byTable) {
            if (value.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(key).append(": ").append(value.size)
        }
        return sb.toString()
    }

    /** Записывает на диск (через временный файл, чтобы обрыв не оставил полфайла). */
    @Throws(IOException::class)
    fun save() {
        if (file == null) return
        if (isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
            return
        }
        val json = JSONObject()
        try {
            for ((key, value) in byTable) {
                if (value.isEmpty()) continue
                val arr = JSONArray()
                for (row in value.values) arr.put(row)
                json.put(key, arr)
            }
        } catch (e: JSONException) {
            throw IOException(e)
        }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot rename $tmp to $file")
    }

    companion object {
        private const val TAG = "PullDeferredRows"
        val FILE_NAME: String = AppSettings.SYNC_PULL_DEFERRED_FILE

        fun inMemory(): PullDeferredRows = PullDeferredRows(null)

        fun load(ctx: Context): PullDeferredRows {
            val file = File(ctx.noBackupFilesDir, FILE_NAME)
            val rows = PullDeferredRows(file)
            if (!file.exists()) return rows
            try {
                val text = String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                val json = JSONObject(text)
                val tables = json.keys()
                while (tables.hasNext()) {
                    val table = tables.next()
                    val arr = json.getJSONArray(table)
                    for (i in 0 until arr.length()) rows.add(table, arr.getJSONObject(i))
                }
            } catch (e: IOException) {
                // Битый файл не должен ронять синк. Потерянные строки вернёт только
                // перезагрузка с нуля, поэтому громко в лог.
                Log.e(TAG, "не удалось прочитать $FILE_NAME — отложенные строки потеряны", e)
                rows.byTable.clear()
            } catch (e: JSONException) {
                Log.e(TAG, "не удалось прочитать $FILE_NAME — отложенные строки потеряны", e)
                rows.byTable.clear()
            }
            return rows
        }
    }
}
