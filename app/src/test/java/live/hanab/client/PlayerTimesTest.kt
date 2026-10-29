package live.hanab.client

import kotlinx.serialization.json.JsonObject
import live.hanab.client.game.GameState
import live.hanab.client.game.formatClock
import live.hanab.client.net.HanabJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTimesTest {
    private fun action(json: String) = HanabJson.parseToJsonElement(json) as JsonObject

    private fun game() = GameState(
        tableID = 1, playerNames = listOf("me", "bob", "cy"), ourIndex = 0, spectating = false,
        variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
    )

    @Test fun theTimesArriveAfterTheLastTurnAndNotBefore() {
        val before = game().apply(action("""{"type":"gameOver","endCondition":1,"playerIndex":0,"votes":[]}"""))
        assertNull(before.timeOf(0))
        // An untimed game: the server counts down from 0, so a player who took 2:05 is at -125000.
        val after = before.apply(action("""{"type":"playerTimes","playerTimes":[-125000,-3725000,-61000],"duration":4000000}"""))
        assertEquals("2:05", after.timeOf(0))
        assertEquals("1:02:05", after.timeOf(1))
        assertEquals("1:01", after.timeOf(2))
        assertEquals(4_000_000L, after.durationMs)
        // A player with no entry has no time to show.
        assertNull(game().apply(action("""{"type":"playerTimes","playerTimes":[1000],"duration":1}""")).timeOf(2))
    }

    @Test fun aTimedGameShowsWhatIsLeft() {
        val timed = game().copy(options = live.hanab.client.game.GameOptions(timed = true))
            .apply(action("""{"type":"playerTimes","playerTimes":[90000,0,-4000],"duration":1}"""))
        assertEquals("1:30", timed.timeOf(0))
        assertEquals("0:00", timed.timeOf(1))
        assertEquals("0:00", timed.timeOf(2))
    }

    @Test fun clocksReadLikeTheWebsite() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:01", formatClock(600))
        assertEquals("4:05", formatClock(245_000))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("0:00", formatClock(-5))
    }
}
