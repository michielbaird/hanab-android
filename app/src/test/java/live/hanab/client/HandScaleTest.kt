package live.hanab.client

import live.hanab.client.ui.MIN_HAND_SCALE
import live.hanab.client.ui.SCALE_STEP
import live.hanab.client.ui.snapHandScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandScaleTest {
    @Test fun aPerfectFitStaysFullSize() {
        assertEquals(1f, snapHandScale(1f), 0f)       // 1.0 / 0.04 is 24.999998: must not become 0.96
        assertEquals(1f, snapHandScale(1.7f), 0f)     // lots of room: never bigger than full size
    }

    @Test fun onlyShrinksWhenItDoesNotFit() {
        // 0.97 does not fit at full size: the next step down (0.96) does.
        assertEquals(0.96f, snapHandScale(0.97f), 1e-5f)
        // Always a multiple of the step, and never larger than what fits.
        for (i in 62..99) {
            val fit = i / 100f
            val s = snapHandScale(fit)
            assertTrue("$fit -> $s is larger than fits", s <= fit + 1e-4f)
            assertTrue("$fit -> $s is more than one step too small", s > fit - SCALE_STEP - 1e-4f)
        }
    }

    @Test fun neverBelowTheMinimum() {
        assertEquals(MIN_HAND_SCALE, snapHandScale(0.3f), 0f)
    }
}