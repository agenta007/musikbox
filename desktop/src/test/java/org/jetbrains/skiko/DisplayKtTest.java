package org.jetbrains.skiko;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DisplayKtTest {
    @Test
    public void aMissingRateFallsBackToSixtyHertz() {
        assertEquals(60.0, DisplayKt.usableRate(0.0), 0.0);
        assertEquals(60.0, DisplayKt.usableRate(-1.0), 0.0);
        assertEquals(60.0, DisplayKt.usableRate(Double.NaN), 0.0);
    }

    @Test
    public void aRealRateIsKept() {
        assertEquals(99.96, DisplayKt.usableRate(99.96), 0.0);
        assertEquals(30.0, DisplayKt.usableRate(30.0), 0.0);
    }
}
