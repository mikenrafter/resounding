package dev.thedocruby.resounding.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blend replaces Property. The bug was arithmetic: the running count was an int while add() took
 * a double, so {@code this.count += count} silently truncated fractional compositions to zero via
 * Java's implicit narrowing on compound assignment. Here the accumulator is a double.
 */
class BlendTest {

    @Test
    void twoContributionsAtHalfCountAverageToHalf() {
        Blend blend = new Blend(false);
        assertTrue(blend.add(1.0, 1.0, 0.5, null));
        assertTrue(blend.add(0.0, 1.0, 0.5, null));

        assertEquals(0.5, blend.get(), 1e-12, "average of 1.0 and 0.0, each weighted 0.5, must be exactly 0.5");
    }

    @Test
    void fractionalCountsAccumulateExactlyRatherThanTruncatingToInt() {
        // With an int counter, `this.count += 0.5` narrows to 0 on every single call (0 + 0.5 cast
        // to int is 0, every time), so the running count never leaves zero no matter how many
        // fractional contributions are added. get() then skips division entirely and returns the
        // raw, undivided sum instead of an average. Three contributions of the same value make the
        // divergence unambiguous: correct is the value itself (2.0); the int-truncation bug returns
        // the undivided sum (6.0) instead.
        Blend blend = new Blend(false);
        blend.add(2.0, 1.0, 0.5, null);
        blend.add(2.0, 1.0, 0.5, null);
        blend.add(2.0, 1.0, 0.5, null);

        assertEquals(2.0, blend.get(), 1e-12,
                "three equal contributions must average to that value, not sum to 6.0 via a truncated int counter");
    }

    @Test
    void overrideModeDiscardsPriorContributionsAndSetsANewBase() {
        Blend blend = new Blend(false);
        blend.add(100.0, 1.0, 3.0, null);
        blend.add(2.0, 1.0, 0, null); // count == 0 -> override

        assertEquals(2.0, blend.get(), 1e-12);
    }

    @Test
    void adjustModeOffsetsTheTotalWithoutChangingTheAveragingWeight() {
        Blend blend = new Blend(false);
        blend.add(10.0, 1.0, 1.0, null); // weighted base: sum=10, count=1
        blend.add(3.0, 0, 1.0, null);    // weight == 0 -> adjust: offsets total by 3, count stays 1

        assertEquals(13.0, blend.get(), 1e-12, "adjust must offset the total (10 + 3) without touching the divisor");
    }

    @Test
    void weightedModeAveragesByWeightWhenNotInRatioMode() {
        Blend blend = new Blend(false);
        blend.add(10.0, 2.0, 1.0, null);
        blend.add(20.0, 1.0, 1.0, null);

        // (10*1*2 + 20*1*1) / (1+1) = 40/2 = 20
        assertEquals(20.0, blend.get(), 1e-12);
    }

    @Test
    void weightedModeIgnoresWeightWhenInRatioMode() {
        Blend blend = new Blend(true);
        blend.add(10.0, 2.0, 1.0, null);
        blend.add(20.0, 1.0, 1.0, null);

        // ratio mode averages by composition alone: (10*1 + 20*1) / (1+1) = 15, weight is not applied
        assertEquals(15.0, blend.get(), 1e-12);
    }

    @Test
    void getReturnsNullWhenNothingValidWasEverAdded() {
        Blend blend = new Blend(false);
        assertNull(blend.get());
    }

    @Test
    void addingNullReturnsFalseAndRecordsNothing() {
        Blend blend = new Blend(false);
        assertFalse(blend.add(null, 1.0, 1.0, null));
        assertNull(blend.get(), "a rejected null contribution must not leave the accumulator valid");
    }
}
