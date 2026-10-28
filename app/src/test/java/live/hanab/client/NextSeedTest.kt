package live.hanab.client

import org.junit.Assert.assertEquals
import org.junit.Test

class NextSeedTest {
    private fun game(seed: String, players: Int = 3, variant: String = "No Variant") = OfflineGame(
        id = seed.hashCode().toLong(), variant = variant, convention = "HGroup5", names = List(players) { "P$it" }, human = 0,
        score = 20, maxScore = 25, endCondition = 1, durationMs = 1000, seed = seed,
    )

    @Test fun startsAtOne() = assertEquals("1", nextUnplayedSeed(emptyList(), 3, "No Variant"))

    @Test fun takesTheLowestNumberNotPlayedYet() {
        val games = listOf(game("p3v0s1"), game("p3v0s2"), game("p3v0s4"))
        assertEquals("3", nextUnplayedSeed(games, 3, "No Variant"))
    }

    @Test fun onlyCountsTheSameTableSizeAndVariant() {
        val games = listOf(game("p3v0s1"), game("p2v0s2", players = 2), game("p3v1s3", variant = "6 Suits"))
        assertEquals("2", nextUnplayedSeed(games, 3, "No Variant"))
        assertEquals("1", nextUnplayedSeed(games, 4, "No Variant"))
    }

    @Test fun textSeedsAndGamesWithoutOneAreIgnored() {
        val games = listOf(game("p3v0ssalt-mine-3"), game(""), game("p3v0s1"))
        assertEquals("2", nextUnplayedSeed(games, 3, "No Variant"))
    }
}
