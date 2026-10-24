package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;

import android.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;

/** {@link AuthSession#accessTokenExpiresAt}: exp из payload JWT, мусор — 0 (тогда refresh). */
@RunWith(RobolectricTestRunner.class)
public class AuthSessionTokenTest {

    @Test
    public void readsExp() {
        String payload = Base64.encodeToString("{\"sub\":\"u\",\"exp\":1793000000}".getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        assertEquals(1793000000L, AuthSession.accessTokenExpiresAt("eyJhbGciOiJSUzI1NiJ9." + payload + ".sig"));
    }

    @Test
    public void garbageIsZero() {
        assertEquals(0L, AuthSession.accessTokenExpiresAt(null));
        assertEquals(0L, AuthSession.accessTokenExpiresAt("not-a-jwt"));
        assertEquals(0L, AuthSession.accessTokenExpiresAt("a.!!!.b"));
    }
}
