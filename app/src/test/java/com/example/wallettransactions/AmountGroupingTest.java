package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;

import android.icu.text.DecimalFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

/**
 * Разделитель тысяч в суммах (balances-and-equivalent, «Формат суммы»): неразрывный пробел
 * в любой локали, а не запятая английской.
 */
@RunWith(RobolectricTestRunner.class)
public class AmountGroupingTest {

    private static final char NBSP = ' ';

    @Test
    public void groupingSeparatorIsNbspWithTwoDecimals() {
        DecimalFormat fmt = AdapterUtils.getFormat(2);
        assertEquals(NBSP, fmt.getDecimalFormatSymbols().getGroupingSeparator());
        String text = fmt.format(412000);
        assertEquals("412" + NBSP + "000", text.substring(0, 7));
    }

    @Test
    public void zeroPrecisionHasNoDecimalPart() {
        assertEquals("12" + NBSP + "346", AdapterUtils.getFormat(0).format(12345.6));
    }

    @Test
    public void englishLocaleStillGroupsWithSpace() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            // Формат кэшируется по точности, поэтому берём точность, которой нет у других тестов.
            DecimalFormat fmt = AdapterUtils.getFormat(5);
            assertEquals(NBSP, fmt.getDecimalFormatSymbols().getGroupingSeparator());
            assertEquals("1" + NBSP + "234", fmt.format(1234).substring(0, 5));
        } finally {
            Locale.setDefault(old);
        }
    }
}
