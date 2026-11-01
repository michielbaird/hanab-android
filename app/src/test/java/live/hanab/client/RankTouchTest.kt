package live.hanab.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.game.GameState
import live.hanab.client.game.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Checks which cards a rank clue touches against the website's own rules: `site_rank_touch.json` holds, for every
 * variant the app plays whose suits are plain, what the website's isCardTouchedByClueRank (packages/game/src/rules/clues.ts
 * in the Hanabi-Live repository) answers for each suit, rank and rank clue on offer, as a string of 0s and 1s in that
 * order: suits, then ranks 1 to 5, then the clues in the variant's clueRanks.
 */
class RankTouchTest {
    companion object {
        private fun asset(name: String): String {
            val f = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }
            return f.readText()
        }

        @JvmStatic
        @BeforeClass
        fun load() = Variants.parse(asset("colors.json"), asset("suits.json"), asset("variants.json"))
    }

    private val golden = Json.parseToJsonElement(
        javaClass.getResource("/site_rank_touch.json")!!.readText(),
    ).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test fun everyVariantsRankCluesTouchWhatTheWebsiteTouches() {
        assertTrue("expected the golden file to cover about 1,700 variants, got ${golden.size}", golden.size > 1600)
        val wrong = mutableListOf<String>()
        for ((name, bits) in golden) {
            val suits = Variants.suits(name)
            if (suits == null || !Variants.isSupported(name)) {
                wrong += "$name: not offered"
                continue
            }
            val rules = Variants.rules(name)
            val g = GameState(
                tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = false, variantName = name,
                suits = suits, suitInfos = suits.map(Variants::info), rules = rules,
            )
            val ours = StringBuilder()
            for (s in suits.indices) for (rank in 1..5) for (clue in rules.clueRanks) {
                ours.append(if (g.touchesRank(s, rank, clue)) '1' else '0')
            }
            if (ours.toString() != bits) wrong += name
        }
        assertEquals("variants whose rank clues differ from the website's: ${wrong.take(10)}", 0, wrong.size)
    }
}
