package live.hanab.client

import org.junit.Assert.assertEquals
import org.junit.Test

class BotPowerTest {
    @Test fun fewerThreadsAsTheBatteryRunsDown() {
        assertEquals(4, BotPower.threadsFor(100, charging = false, powerSaving = false))
        assertEquals(3, BotPower.threadsFor(60, charging = false, powerSaving = false))
        assertEquals(2, BotPower.threadsFor(30, charging = false, powerSaving = false))
        assertEquals(1, BotPower.threadsFor(10, charging = false, powerSaving = false))
    }

    @Test fun chargingGetsEverythingUnlessPowerSaving() {
        assertEquals(4, BotPower.threadsFor(5, charging = true, powerSaving = false))
        assertEquals(1, BotPower.threadsFor(90, charging = true, powerSaving = true))
    }

    @Test fun anUnknownBatteryGetsEverything() {
        assertEquals(4, BotPower.threadsFor(-1, charging = false, powerSaving = false))
    }
}
