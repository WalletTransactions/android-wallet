package com.example.wallettransactions.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** openspec optional-icon-upload, задача 2.3: разбор capabilities в pull и отказов приёма иконок. */
@RunWith(RobolectricTestRunner::class)
class SyncApiClientIconUploadTest {

    private fun stream(): JSONObject =
        JSONObject().put("next_seq", 3).put("reset", false).put("changes", JSONObject())

    private fun pull(capabilities: JSONObject?): JSONObject {
        val json = JSONObject().put("ledger", stream().put("ledger_id", "l1")).put("reference", stream())
        if (capabilities != null) json.put("capabilities", capabilities)
        return json
    }

    @Test
    fun pull_capabilityTrue() {
        assertTrue(SyncApiClient.PullResult.fromJson(pull(JSONObject().put("icon_upload", true))).iconUploadAllowed)
    }

    @Test
    fun pull_capabilityFalse() {
        assertFalse(SyncApiClient.PullResult.fromJson(pull(JSONObject().put("icon_upload", false))).iconUploadAllowed)
    }

    @Test
    fun pull_oldServerWithoutCapabilities_isNotAllowed() {
        val r = SyncApiClient.PullResult.fromJson(pull(null))
        assertFalse(r.iconUploadAllowed)
        assertEquals(3L, r.ledger.nextSeq)
    }

    @Test
    fun push_iconsRejectedParsed() {
        val body = JSONObject().put("seq", 9).put("conflicts", JSONArray())
            .put(
                "icons_rejected", JSONObject().put("error", "icon_upload_disabled")
                    .put("uuids", JSONArray().put("a").put("b"))
            )
        val uuids = SyncApiClient.iconsRejected(body)
        assertEquals(2, uuids.size)
        assertEquals("a", uuids[0])
    }

    @Test
    fun push_withoutIconsRejected_isEmpty() {
        assertTrue(SyncApiClient.iconsRejected(JSONObject().put("seq", 1)).isEmpty())
        assertTrue(SyncApiClient.iconsRejected(null).isEmpty())
    }

    @Test
    fun blob403_onlyWithDisabledCodeIsRefusal() {
        assertTrue(SyncApiClient.isIconUploadDisabled(403, JSONObject().put("error", "icon_upload_disabled")))
        assertFalse(SyncApiClient.isIconUploadDisabled(403, JSONObject().put("error", "forbidden")))
        assertFalse(SyncApiClient.isIconUploadDisabled(409, JSONObject().put("error", "icon_upload_disabled")))
        assertFalse(SyncApiClient.isIconUploadDisabled(403, null))
    }
}
