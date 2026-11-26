package com.example.wallettransactions.Activities

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MultiPickerActivity.isDoneKey]: Enter в поиске = «Готово» (spec account-screens),
 * и экранный (IME_ACTION_DONE), и физический (IME_NULL + KeyEvent) — только на нажатии.
 */
@RunWith(RobolectricTestRunner::class)
class MultiPickerDoneKeyTest {

    @Test
    fun softKeyboardDone() {
        assertTrue(MultiPickerActivity.isDoneKey(EditorInfo.IME_ACTION_DONE, null))
    }

    @Test
    fun hardwareEnterOnlyOnDown() {
        assertTrue(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun otherKeysIgnored() {
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_ACTION_SEARCH, null))
    }
}
