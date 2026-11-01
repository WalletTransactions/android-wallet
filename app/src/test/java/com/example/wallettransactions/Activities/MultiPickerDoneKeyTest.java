package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link MultiPickerActivity#isDoneKey}: Enter в поиске = «Готово» (spec account-screens),
 * и экранный (IME_ACTION_DONE), и физический (IME_NULL + KeyEvent) — только на нажатии.
 */
@RunWith(RobolectricTestRunner.class)
public class MultiPickerDoneKeyTest {

    @Test
    public void softKeyboardDone() {
        assertTrue(MultiPickerActivity.isDoneKey(EditorInfo.IME_ACTION_DONE, null));
    }

    @Test
    public void hardwareEnterOnlyOnDown() {
        assertTrue(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)));
    }

    @Test
    public void otherKeysIgnored() {
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_NULL,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertFalse(MultiPickerActivity.isDoneKey(EditorInfo.IME_ACTION_SEARCH, null));
    }
}
