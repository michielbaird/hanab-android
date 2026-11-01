package live.hanab.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.game.GameState
import live.hanab.client.game.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Checks which cards a clue touches against the website's own rules. `site_touch.json.gz` holds, for every variant the
 * app plays whose suits we can draw, what the website's isCardTouchedByClueColor and isCardTouchedByClueRank
 * (packages/game/src/rules/clues.ts in the Hanabi-Live repository) answer for each suit, rank and clue, with the clue
 * colours and clue ranks of the variant: a string of 0s and 1s in the order suits, ranks 1 to 5, clues. It was made by
 * running a copy of those functions in a browser over variants.json and suits.json.
 */
class ClueTouchTest {
    companion object {
        private fun asset(name: String): String {
            val f = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }
            return f.readText()
        }

        @JvmStatic
        @BeforeClass
        fun load() = Variants.parse(asset("colors.json"), asset("suits.json"), asset("variants.json"))
    }

    private val golden: JsonObject = Json.parseToJsonElement(
        GZIPInputStream(javaClass.getResourceAsStream("/site_touch.json.gz")!!).bufferedReader().readText(),
    ).jsonObject

    private fun strings(a: JsonArray) = a.map { it.jsonPrimitive.content }

    @Test fun everyVariantsClueTouchWhatTheWebsiteTouches() {
        assertTrue("expected the golden file to cover about 1,950 variants, got ${golden.size}", golden.size > 1900)
        val wrongColours = mutableListOf<String>()
        val wrongRanks = mutableListOf<String>()
        val wrongLists = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((name, entry) in golden) {
            val e = entry.jsonObject
            val suits = Variants.suits(name)
            if (suits == null || !Variants.isSupported(name)) {
                missing += name
                continue
            }
            val rules = Variants.rules(name)
            val g = GameState(
                tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = false, variantName = name,
                suits = suits, suitInfos = suits.map(Variants::info), rules = rules,
            )
            val siteColours = strings(e.getValue("colors").jsonArray)
            val siteRanks = e.getValue("ranks").jsonArray.map { it.jsonPrimitive.int }
            if (g.clueColors != siteColours || rules.clueRanks != siteRanks) {
                wrongLists += name
                continue
            }
            val colours = StringBuilder()
            val ranks = StringBuilder()
            for (s in suits.indices) for (rank in 1..5) {
                for (c in g.clueColors.indices) colours.append(if (g.touchesColor(s, rank, c)) '1' else '0')
                for (r in rules.clueRanks) ranks.append(if (g.touchesRank(s, rank, r)) '1' else '0')
            }
            if (colours.toString() != e.getValue("colorBits").jsonPrimitive.content) wrongColours += name
            if (ranks.toString() != e.getValue("rankBits").jsonPrimitive.content) wrongRanks += name
        }
        assertEquals("variants the app does not offer: ${missing.take(10)}", 0, missing.size)
        assertEquals("variants whose clue colours or ranks differ from the website's: ${wrongLists.take(10)}", 0, wrongLists.size)
        assertEquals("variants whose colour clues differ from the website's: ${wrongColours.take(10)}", 0, wrongColours.size)
        assertEquals("variants whose rank clues differ from the website's: ${wrongRanks.take(10)}", 0, wrongRanks.size)
    }
}
