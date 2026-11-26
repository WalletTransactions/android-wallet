package com.example.wallettransactions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Разделитель тысяч в суммах (balances-and-equivalent, «Формат суммы»): неразрывный пробел
 * в любой локали, а не запятая английской.
 */
@RunWith(RobolectricTestRunner::class)
class AmountGroupingTest {

    @Test
    fun groupingSeparatorIsNbspWithTwoDecimals() {
        val fmt = AdapterUtils.getFormat(2)
        assertEquals(NBSP, fmt.decimalFormatSymbols.groupingSeparator)
        val text = fmt.format(412000)
        assertEquals("412" + NBSP + "000", text.substring(0, 7))
    }

    @Test
    fun zeroPrecisionHasNoDecimalPart() {
        assertEquals("12" + NBSP + "346", AdapterUtils.getFormat(0).format(12345.6))
    }

    @Test
    fun englishLocaleStillGroupsWithSpace() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // Формат кэшируется по точности, поэтому берём точность, которой нет у других тестов.
            val fmt = AdapterUtils.getFormat(5)
            assertEquals(NBSP, fmt.decimalFormatSymbols.groupingSeparator)
            assertEquals("1" + NBSP + "234", fmt.format(1234).substring(0, 5))
        } finally {
            Locale.setDefault(old)
        }
    }

    companion object {
        private const val NBSP = '\u00A0'
    }
}
