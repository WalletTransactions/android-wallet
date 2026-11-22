package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/** openspec optional-icon-upload, задача 2.3: разбор capabilities в pull и отказов приёма иконок. */
@RunWith(RobolectricTestRunner.class)
public class SyncApiClientIconUploadTest {

    private static JSONObject stream() throws Exception {
        return new JSONObject().put("next_seq", 3).put("reset", false).put("changes", new JSONObject());
    }

    private static JSONObject pull(JSONObject capabilities) throws Exception {
        JSONObject json = new JSONObject().put("ledger", stream().put("ledger_id", "l1")).put("reference", stream());
        if (capabilities != null) json.put("capabilities", capabilities);
        return json;
    }

    @Test
    public void pull_capabilityTrue() throws Exception {
        assertTrue(SyncApiClient.PullResult.fromJson(pull(new JSONObject().put("icon_upload", true))).iconUploadAllowed);
    }

    @Test
    public void pull_capabilityFalse() throws Exception {
        assertFalse(SyncApiClient.PullResult.fromJson(pull(new JSONObject().put("icon_upload", false))).iconUploadAllowed);
    }

    @Test
    public void pull_oldServerWithoutCapabilities_isNotAllowed() throws Exception {
        SyncApiClient.PullResult r = SyncApiClient.PullResult.fromJson(pull(null));
        assertFalse(r.iconUploadAllowed);
        assertEquals(3, r.ledger.nextSeq);
    }

    @Test
    public void push_iconsRejectedParsed() throws Exception {
        JSONObject body = new JSONObject().put("seq", 9).put("conflicts", new JSONArray())
                .put("icons_rejected", new JSONObject().put("error", "icon_upload_disabled")
                        .put("uuids", new JSONArray().put("a").put("b")));
        List<String> uuids = SyncApiClient.iconsRejected(body);
        assertEquals(2, uuids.size());
        assertEquals("a", uuids.get(0));
    }

    @Test
    public void push_withoutIconsRejected_isEmpty() throws Exception {
        assertTrue(SyncApiClient.iconsRejected(new JSONObject().put("seq", 1)).isEmpty());
        assertTrue(SyncApiClient.iconsRejected(null).isEmpty());
    }

    @Test
    public void blob403_onlyWithDisabledCodeIsRefusal() throws Exception {
        assertTrue(SyncApiClient.isIconUploadDisabled(403, new JSONObject().put("error", "icon_upload_disabled")));
        assertFalse(SyncApiClient.isIconUploadDisabled(403, new JSONObject().put("error", "forbidden")));
        assertFalse(SyncApiClient.isIconUploadDisabled(409, new JSONObject().put("error", "icon_upload_disabled")));
        assertFalse(SyncApiClient.isIconUploadDisabled(403, null));
    }
}
