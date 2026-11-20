package live.hanab.client

import live.hanab.client.net.HanabJson
import live.hanab.client.net.HistoryRow
import live.hanab.client.protocol.GamePage
import live.hanab.client.protocol.ProtocolJson
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
         "statistics":{"dateJoined":"2020-03-31T12:00:00Z","numGamesNormal":418,"numGamesOther":10,"numGamesSpeedrun":56,"numGamesTotal":484,
           "timePlayedSeconds":603620,"timePlayedSpeedrunSeconds":0,"timeReviewedSeconds":3600,"timeTotalSeconds":607220},
         "variants":[
           {"variantName":"No Variant","maxScore":25,"numGames":1564,"bestScores":[{"score":25},{"score":25},{"score":25},{"score":25},{"score":25}]},
           {"variantName":"Muddy Rainbow (6 Suits)","maxScore":30,"numGames":19,"bestScores":[{"score":30},{"score":30,"modifier":"deckPlays"},{},{},{}]},
           {"variantName":"Omni & Black","maxScore":20,"numGames":0,"bestScores":[]}
         ]}
    """.trimIndent().replace("\n", "")

    @Test fun readsTheFacts() {
        val p = profileOf(ProtocolJson.decodeFromString(PlayerScores.serializer(), scores))
        assertEquals("Date joined" to "2020-03-31", p.facts[0])
        assertEquals("Games played" to "484 (418 normal, 56 speedrun, 10 other)", p.facts[1])
        assertEquals("6 days, 23 hours, and 40 minutes", p.facts[2].second)
        assertEquals("Time spent reviewing" to "1 hour", p.facts.single { it.first == "Time spent reviewing" })
        // No speedrun time, so no line for it.
        assertTrue(p.facts.none { it.first == "Time spent in speedruns" })
        assertEquals("10 (0.1%)", p.facts.single { it.first == "Total max scores" }.second)
        assertEquals("4 (1.5%)", p.facts.single { it.first == "Max scores at 2 players" }.second)
    }

    @Test fun readsTheGrid() {
        val page = profileOf(ProtocolJson.decodeFromString(PlayerScores.serializer(), scores))
        // The site sends no average or strikeout rate, so the grid must not claim to have them.
        assertFalse(page.hasAverages)
        val rows = page.rows
        assertEquals(3, rows.size)
        val noVariant = rows[0]
        assertEquals("No Variant", noVariant.variant)
        assertEquals(1564, noVariant.games)
        assertEquals(listOf(25, 25, 25, 25, 25), noVariant.best.map { it.score })
        assertEquals(listOf(25, 25, 25, 25, 25), noVariant.best.map { it.max })
        assertTrue(noVariant.best.none { it.modified })

        val muddy = rows[1]
        assertEquals(listOf(30, 30, 0, 0, 0), muddy.best.map { it.score })
        assertEquals(listOf(30, 30, 30, 30, 30), muddy.best.map { it.max })
        assertFalse(muddy.best[0].modified)
        // A score made with an option still counts, but is marked.
        assertTrue(muddy.best[1].modified)

        assertEquals("Omni & Black", rows[2].variant)
        assertEquals(0, rows[2].games)
        assertTrue(rows[2].best.all { it.score == 0 })
        assertNull(rows[2].average)
    }
}
