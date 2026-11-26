package com.example.wallettransactions

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppSettings.pickerSoftInputMode]: «клавиатура на экранах выбора» меняет только
 * биты state и не затирает `adjustResize` из манифеста (openspec currency-ui-tweaks,
 * design D11) — иначе клавиатура снова закрывала бы «Готово».
 */
class PickerKeyboardModeTest {

    @Test
    fun keepsAdjustResize() {
        val fromManifest = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        val mode = AppSettings.pickerSoftInputMode(fromManifest)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        )
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
        )
    }

    @Test
    fun replacesPreviousState() {
        val mode = AppSettings.pickerSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, mode
        )
    }
}
