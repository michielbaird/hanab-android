package live.hanab.client

import live.hanab.client.net.HanabJson
import live.hanab.client.net.HistoryRow
import live.hanab.client.protocol.GamePage
import live.hanab.client.protocol.ProtocolJson
import live.hanab.client.net.parseBestScores
import live.hanab.client.net.profileOf
import live.hanab.client.protocol.PlayerScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileApiTest {
    @Test fun readsAHistoryPageAsTheSiteSendsIt() {
        val json = """{"games":[{"gameId":2063346,"options":{"variantId":2197,"speedrun":false,"deckPlays":false,"emptyClues":false,"oneExtraCard":false,"oneLessCard":false,"allOrNothing":false,"detrimentalCharacters":false},"seed":"p2v2197s7","score":25,"numTurns":40,"endCondition":"normal","startedAt":"2026-10-25T01:30:00.000Z","finishedAt":"2026-10-25T01:44:52.123Z","numGamesOnSeed":8,"playerNames":["RandP","WillFlame"],"tags":[]}],"offset":0,"total":7298}"""
        val page = ProtocolJson.decodeFromString(GamePage.serializer(), json)
        assertEquals(7298L, page.total)
        val row = HistoryRow.of(page.games.single())
        assertEquals(2063346, row.id)
        assertEquals(2197, row.variant)
        assertEquals(2, row.numPlayers)
        assertEquals(25, row.score)
        assertEquals("2026-10-25", row.date)
        assertEquals(listOf("RandP"), row.others("WillFlame"))
    }

    /** Trimmed from a real `/api/v1/scores/<name>` answer. */
    private val scores = """
        {"name":"RandP","maxScores":{"percentage":0.1,"total":10,"perType":[{"percentage":1.5,"total":4},{"percentage":0,"total":0}]},
         "achievements":[{"id":"noVariant10Wins","name":"First","description":"d","goal":1,"progress":1,"dateUnlocked":"2021-01-02T03:04:05Z","unlockedPercentage":50.0},
                         {"id":"black10Wins","name":"Ten","description":"d","goal":10,"progress":3,"unlockedPercentage":10.0}],
         "statistics":{"dateJoined":"2020-03-31T12:00:00Z","numGames":484,"numGamesNormal":418,
           "timePlayedSeconds":603620,"timeReviewedSeconds":3600,"timeTotalSeconds":607220,
           "bestStreak":{"length":7,"numPlayers":3,"variantId":0}}}
    """.trimIndent().replace("\n", "")

    @Test fun readsTheFacts() {
        val p = profileOf(ProtocolJson.decodeFromString(PlayerScores.serializer(), scores))
        assertEquals("Date joined" to "2020-03-31", p.facts[0])
        assertEquals("Games played" to "484 (418 finished normally)", p.facts[1])
        assertEquals("6 days, 23 hours, and 40 minutes", p.facts[2].second)
        assertEquals("1 hour", p.facts.single { it.first == "Time spent reviewing" }.second)
        assertEquals("7 max scores in a row (3 players)", p.facts.single { it.first == "Longest streak" }.second)
        assertEquals("10 (0.1%)", p.facts.single { it.first == "Total max scores" }.second)
        assertEquals("4 (1.5%)", p.facts.single { it.first == "Max scores at 2 players" }.second)
        assertEquals("1 of 2", p.facts.single { it.first == "Achievements" }.second)
    }

    /** The grid is the JSON that the best scores page draws into `#public-page-table`. */
    @Test fun readsTheBestScoresTheSiteSends() {
        val json = """
            {"type":"bestScores","rows":[
              {"variantName":"No Variant","recipe":"x","maxScore":25,"scores":[{"numPlayers":2,"score":25,"gameId":9},{"numPlayers":3,"score":24},{"numPlayers":5,"score":25}]},
              {"variantName":"Muddy Rainbow (6 Suits)","recipe":"y","maxScore":30,"scores":[{"numPlayers":4,"score":30,"modifier":"deckPlays"}]},
              {"variantName":"Omni & Black","recipe":"z","maxScore":20,"scores":[]}
            ]}
        """.trimIndent().replace("\n", "")
        val html = """<div id="public-page-table" data-table="${json.replace("&", "&amp;").replace("\"", "&quot;")}"></div>"""

        val rows = parseBestScores(html)!!
        assertEquals(3, rows.size)
        assertEquals("No Variant", rows[0].variant)
        // Table sizes 2 to 6; a size nobody played is a 0.
        assertEquals(listOf(25, 24, 0, 25, 0), rows[0].best.map { it.score })
        assertEquals(listOf(25, 25, 25, 25, 25), rows[0].best.map { it.max })
        assertTrue(rows[0].best.none { it.modified })
        // A score made with an option still counts, but is marked.
        assertTrue(rows[1].best[2].modified)
        assertFalse(rows[1].best[0].modified)
        // Entities in the attribute are decoded before the JSON is read.
        assertEquals("Omni & Black", rows[2].variant)
        assertTrue(rows[2].best.all { it.score == 0 })
        assertNull(rows[2].average)
    }

    @Test fun aPageWithoutTheTableHasNoScores() {
        assertNull(parseBestScores("<html>Error</html>"))
    }

    @Test fun theGridHasNoGamesColumnOrAverages() {
        val page = profileOf(ProtocolJson.decodeFromString(PlayerScores.serializer(), scores))
        assertFalse(page.hasAverages)
        assertFalse(page.hasGames)
    }
}
