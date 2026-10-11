package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Юнит-тесты {@link SyncEngine#deferDependents} и {@link SyncEngine#sha256Hex}
 * — без БД/сети.
 * <p>
 * Инцидент 2026-10-11: на медленном Tailscale-relay оборвалась заливка одной
 * из 144 иконок, и весь push (вместе с удалениями операций) не ушёл. Теперь
 * незалитая иконка откладывается — и вместе с ней всё, что на неё ссылается
 * по цепочке, иначе сервер ответил бы missing_reference.
 */
public class SyncEngineDeferIconTest {

    private static JSONObject row(String uuid, String... refs) throws Exception {
        JSONObject r = new JSONObject().put("uuid", uuid);
        for (int i = 0; i < refs.length; i += 2) r.put(refs[i], refs[i + 1]);
        return r;
    }

    @Test
    public void defersWholeChainButKeepsUnrelatedRows() throws Exception {
        Map<String, JSONArray> byTable = new LinkedHashMap<>();
        byTable.put("icon", new JSONArray().put(row("icon-ok")));
        byTable.put("account", new JSONArray()
                .put(row("acc-bad", "icon_uuid", "icon-bad"))
                .put(row("acc-ok", "icon_uuid", "icon-ok")));
        byTable.put("account_operating", new JSONArray()
                .put(row("acc-bad", "currency_uuid", "cur"))
                .put(row("acc-ok", "currency_uuid", "cur")));
        byTable.put("operation", new JSONArray()
                .put(row("op-bad", "acc_fr_uuid", "acc-bad", "acc_to_uuid", "acc-ok"))
                .put(row("op-ok", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok"))
                .put(row("op-deleted", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok").put("deleted", true)));
        byTable.put("operation_link", new JSONArray()
                .put(row("link-bad", "main_uuid", "op-ok", "link_uuid", "op-bad")));

        Set<String> deferred = new HashSet<>();
        deferred.add("icon-bad");
        List<String> problems = new ArrayList<>();

        SyncEngine.deferDependents(byTable, deferred, problems);

        assertEquals(1, byTable.get("icon").length());
        assertEquals("acc-ok", byTable.get("account").getJSONObject(0).getString("uuid"));
        assertEquals(1, byTable.get("account").length());
        assertEquals(1, byTable.get("account_operating").length());
        assertEquals(2, byTable.get("operation").length());
        // Таблица, из которой ушли все строки, не должна оставаться пустой в changes.
        assertFalse(byTable.containsKey("operation_link"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("4 строк"));
    }

    @Test
    public void sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SyncEngine.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
