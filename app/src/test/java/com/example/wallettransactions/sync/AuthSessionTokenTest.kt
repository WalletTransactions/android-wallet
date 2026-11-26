package com.example.wallettransactions.sync

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets

/** [AuthSession.accessTokenExpiresAt]: exp из payload JWT, мусор — 0 (тогда refresh). */
@RunWith(RobolectricTestRunner::class)
class AuthSessionTokenTest {

    @Test
    fun readsExp() {
        val payload = Base64.encodeToString(
            "{\"sub\":\"u\",\"exp\":1793000000}".toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        assertEquals(1793000000L, AuthSession.accessTokenExpiresAt("eyJhbGciOiJSUzI1NiJ9.$payload.sig"))
    }

    @Test
    fun garbageIsZero() {
        assertEquals(0L, AuthSession.accessTokenExpiresAt(null))
        assertEquals(0L, AuthSession.accessTokenExpiresAt("not-a-jwt"))
        assertEquals(0L, AuthSession.accessTokenExpiresAt("a.!!!.b"))
    }
}
