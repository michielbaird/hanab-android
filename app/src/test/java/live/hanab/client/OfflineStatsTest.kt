package live.hanab.client

import live.hanab.client.net.durationText
import live.hanab.client.net.offlineScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStatsTest {
    private fun game(id: Long, score: Int, players: Int = 3, variant: String = "No Variant", max: Int = 25, end: Int = 1, ms: Long = 60_000) =
        OfflineGame(
            id = id, variant = variant, convention = "HGroup5", names = List(players) { "P$it" }, human = 0,
            score = score, maxScore = max, endCondition = end, durationMs = ms,
        )

    @Test fun groupsByVariantWithBestScorePerTableSize() {
        val page = offlineScores(
            listOf(
                game(1, 20, players = 2), game(2, 25, players = 2), game(3, 10, players = 4),
                game(4, 28, players = 3, variant = "6 Suits", max = 30),
            ),
        )
        assertEquals(listOf("No Variant", "6 Suits"), page.rows.map { it.variant })
        val none = page.rows[0]
        assertEquals(3, none.games)
        // 2p, 3p, 4p, 5p, 6p: sizes never played show 0.
        assertEquals(listOf(25, 0, 10, 0, 0), none.best.map { it.score })
        assertEquals(25, none.best[0].max)
        assertEquals((20 + 25 + 10) / 3.0, none.average!!, 0.001)
        assertEquals(28, page.rows[1].best[1].score)
        assertEquals(30, page.rows[1].best[1].max)
    }

    @Test fun strikeoutRateCountsGamesThatStruckOut() {
        val page = offlineScores(listOf(game(1, 3, end = 2), game(2, 20), game(3, 25), game(4, 18, end = 2)))
        assertEquals(50.0, page.rows[0].strikeoutRate!!, 0.001)
    }

    @Test fun factsSayHowMuchWasPlayed() {
        val page = offlineScores(listOf(game(1, 25, ms = 3_600_000), game(2, 10, ms = 1_800_000)))
        val facts = page.facts.toMap()
        assertEquals("2", facts["Games played"])
        assertEquals("1 hour and 30 minutes", facts["Time spent playing"])
        assertEquals("1 (50.0%)", facts["Perfect games"])
    }

    @Test fun noGamesStillMakesAPage() {
        val page = offlineScores(emptyList())
        assertTrue(page.rows.isEmpty())
        assertEquals("0", page.facts.toMap()["Games played"])
    }

    @Test fun durationReadsLikeTheWebsite() {
        assertEquals("less than a minute", durationText(30_000))
        assertEquals("1 minute", durationText(60_000))
        assertEquals("3 hours and 28 minutes", durationText((3 * 60 + 28) * 60_000L))
        assertEquals("6 days, 23 hours, and 27 minutes", durationText(((6 * 24 + 23) * 60 + 27) * 60_000L))
    }
}
