package com.example.wallettransactions.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Юнит-тесты [SyncEngine.parseMissingReference] — чистая разборка тела
 * 409-ответа `missing_reference`, без БД/сети (см. её javadoc).
 *
 * Реальный инцидент 2026-09-13: первый же синк с телефона упал на
 * `POST /sync/push` — `currency_pair` (EURHRK) ссылалась на
 * `currency` (uuid `1c21da38-...`), которую сервер не получил,
 * потому что на клиенте она была `dirty=0` (уже считалась
 * отправленной), хотя ЭТОТ сервер её никогда не видел. Сервер теперь (см.
 * wallet-api/src/sync_repo.cpp, `parse_missing_reference`)
 * достаёт из PG_DIAG_MESSAGE_DETAIL и присылает обратно `table`/
 * `uuid` строки, которой не хватило — этот тест проверяет, что клиент
 * умеет прочесть именно такое тело (и НЕ пытается самовосстановиться на
 * всём, что этому телу не соответствует).
 */
class SyncEngineMissingReferenceTest {

    @Test
    fun recognizesStructuredMissingReference() {
        val body = JSONObject()
            .put("error", "missing_reference")
            .put("table", "currency")
            .put("uuid", "1c21da38-b329-4d15-a1c4-8055f49a550b")
            .put(
                "detail", "Postgres: ERROR: insert or update on table \"currency_pair\" " +
                    "violates foreign key constraint \"currency_pair_base_currency_uuid_fkey\""
            )

        val result = SyncEngine.parseMissingReference(409, body)!!

        assertEquals("currency", result.table)
        assertEquals("1c21da38-b329-4d15-a1c4-8055f49a550b", result.uuid)
    }

    @Test
    fun ignoresNonConflictHttpStatus() {
        val body = JSONObject()
            .put("error", "missing_reference")
            .put("table", "currency")
            .put("uuid", "1c21da38-b329-4d15-a1c4-8055f49a550b")

        // 500/400 — не тот класс отказа, даже если бы тело случайно совпало
        // по форме (в проде так не бывает, но метод не должен верить статусу
        // молча, см. javadoc pushDirty про "не самовосстанавливающийся").
        assertNull(SyncEngine.parseMissingReference(500, body))
        assertNull(SyncEngine.parseMissingReference(400, body))
    }

    @Test
    fun ignoresOtherErrorReasons() {
        // duplicate_key (icon.name/currency.iso_code, см. sync_repo.cpp) —
        // такой же 409, но восстанавливать тут нечего: сервер не назвал
        // строку, которой не хватает, потому что дело не в этом.
        val body = JSONObject().put("error", "duplicate_key").put("constraint", "uq_icon_name_reference")

        assertNull(SyncEngine.parseMissingReference(409, body))
    }

    @Test
    fun ignoresLegacyBodyWithoutTableOrUuid() {
        // Форма ДО этой правки (или сервер постарее) — только error/detail,
        // без table/uuid: составной внешний ключ (fk_operation_acc_fr и
        // т.п., см. sync_repo.cpp, parse_missing_reference) тоже приходит
        // так и сегодня. Клиент не может сам понять, что послать — фатально,
        // как раньше.
        val body = JSONObject()
            .put("error", "missing_reference")
            .put("detail", "Postgres: ERROR: ...")

        assertNull(SyncEngine.parseMissingReference(409, body))
    }

    @Test
    fun ignoresUnknownTableName() {
        // Оборона на случай будущего/чужого сервера — table должна быть
        // одной из TABLE_ORDER, иначе клиент НЕ должен выполнять
        // UPDATE <имя> SET dirty=1 по имени таблицы, взятому из HTTP-ответа
        // без проверки (см. javadoc parseMissingReference).
        val body = JSONObject()
            .put("error", "missing_reference")
            .put("table", "not_a_real_table")
            .put("uuid", "1c21da38-b329-4d15-a1c4-8055f49a550b")

        assertNull(SyncEngine.parseMissingReference(409, body))
    }

    @Test
    fun ignoresEmptyUuid() {
        val body = JSONObject()
            .put("error", "missing_reference")
            .put("table", "currency")
            .put("uuid", "")

        assertNull(SyncEngine.parseMissingReference(409, body))
    }
}
