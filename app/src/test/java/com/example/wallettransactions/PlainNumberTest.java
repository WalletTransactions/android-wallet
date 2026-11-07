package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** {@link AdapterUtils#plainNumber}: без экспоненты и лишних нулей. */
public class PlainNumberTest {
    @Test
    public void noExponent_noTrailingZeros() {
        assertEquals("10000000", AdapterUtils.plainNumber(10_000_000));
        assertEquals("12345678.5", AdapterUtils.plainNumber(12_345_678.5));
        assertEquals("73791.98", AdapterUtils.plainNumber(73791.98));
        assertEquals("150", AdapterUtils.plainNumber(150.0));
        assertEquals("0", AdapterUtils.plainNumber(0));
    }
}
