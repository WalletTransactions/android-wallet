package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;

import android.view.WindowManager;

import org.junit.Test;

/**
 * {@link AppSettings#pickerSoftInputMode}: «клавиатура на экранах выбора» меняет только
 * биты state и не затирает {@code adjustResize} из манифеста (openspec currency-ui-tweaks,
 * design D11) — иначе клавиатура снова закрывала бы «Готово».
 */
public class PickerKeyboardModeTest {

    @Test
    public void keepsAdjustResize() {
        int fromManifest = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        int mode = AppSettings.pickerSoftInputMode(fromManifest);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
    }

    @Test
    public void replacesPreviousState() {
        int mode = AppSettings.pickerSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, mode);
    }
}
