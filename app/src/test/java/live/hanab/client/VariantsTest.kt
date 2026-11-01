package live.hanab.client

import live.hanab.client.game.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Runs the real suits / variants / colours data (the files bundled in the app) through the app's parser. */
class VariantsTest {
    companion object {
        private fun asset(name: String): String {
            // Unit tests run from the app module, but be forgiving about the working directory.
            val f = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }
            return f.readText()
        }

        @JvmStatic
        @BeforeClass
        fun load() = Variants.parse(asset("colors.json"), asset("suits.json"), asset("variants.json"))
    }

    @Test fun everyQuickPickIsSupported() {
        for (v in Variants.quick) assertTrue("$v should be supported", Variants.isSupported(v))
    }

    @Test fun plainAndSimpleSuitVariantsAreSupported() {
        for (v in listOf("No Variant", "Rainbow (5 Suits)", "Black (6 Suits)", "Pink (5 Suits)", "Brown (5 Suits)", "White (5 Suits)")) {
            assertTrue("$v should be supported", Variants.isSupported(v))
        }
    }

    @Test fun variantsWithSpecialRulesOrUnmodelledSuitsAreNot() {
        assertFalse(Variants.isSupported("Not a real variant"))
        // Anything with one of the rules we do not model is out.
        assertFalse(Variants.isSupported("Up or Down (5 Suits)"))
        assertFalse(Variants.isSupported("Sudoku (5 Suits)"))
        assertFalse(Variants.isSupported("Throw It in a Hole (5 Suits)"))
        assertTrue(Variants.isSupported("Orange (5 Suits)"))
    }

    @Test fun prismAndSynesthesiaAreModelled() {
        for (v in listOf("Prism (5 Suits)", "Dark Prism (5 Suits)", "Synesthesia (5 Suits)", "Pink-Ones & Prism (6 Suits)")) {
            assertTrue("$v should be supported", Variants.isSupported(v))
        }
        assertTrue(Variants.info("Prism").prism)
        assertTrue(Variants.rules("Synesthesia (5 Suits)").synesthesia)
        assertEquals(emptyList<Int>(), Variants.rules("Synesthesia (5 Suits)").clueRanks)    // no rank clues at all
        assertFalse("Orange stays out", Variants.isSupported("Orange & Prism (6 Suits)"))
        assertFalse("the bot does not play Synesthesia", "Synesthesia (5 Suits)" in Variants.botSupported())
    }

    @Test fun theRankClueRulesAreModelledButTheBotDoesNotPlayThem() {
        for (v in listOf("Pink-Ones (5 Suits)", "Brown-Fives (5 Suits)", "Deceptive-Ones (5 Suits)", "Odds and Evens (5 Suits)", "Funnels (5 Suits)", "Chimneys (5 Suits)")) {
            assertTrue("$v should be supported", Variants.isSupported(v))
            assertFalse("the bot should not be offered $v", v in Variants.botSupported())
        }
        assertEquals(listOf(2, 3, 4, 5), Variants.rules("Pink-Ones (5 Suits)").clueRanks)
        assertEquals(listOf(1, 2), Variants.rules("Odds and Evens (5 Suits)").clueRanks)
        assertTrue(Variants.rules("Funnels (5 Suits)").funnels)
        assertTrue(Variants.rules("Chimneys (5 Suits)").chimneys)
        assertTrue(Variants.rules("Pink-Ones (5 Suits)").rankTouchedByAllRanks)
        assertTrue(Variants.rules("Brown-Fives (5 Suits)").rankTouchedByNoRanks)
        assertTrue(Variants.rules("Deceptive-Ones (5 Suits)").rankDeceptive)
        assertTrue("Rainbow-Ones is still played by the bot", "Rainbow-Ones (5 Suits)" in Variants.botSupported())
    }

    @Test fun variantsWithTheRulesWeModelAreSupported() {
        assertTrue(Variants.isSupported("Rainbow-Ones (5 Suits)"))
        assertTrue(Variants.isSupported("White-Ones (5 Suits)"))
        assertTrue(Variants.isSupported("Scarce Ones (5 Suits)"))
        assertTrue(Variants.isSupported("Critical Fours (5 Suits)"))
        assertTrue(Variants.isSupported("Clue Starved (5 Suits)"))
        assertEquals(1, Variants.rules("Rainbow-Ones (5 Suits)").specialRank)
        assertTrue(Variants.rules("Rainbow-Ones (5 Suits)").rankTouchedByAllColors)
        assertTrue(Variants.rules("White-Ones (5 Suits)").rankTouchedByNoColors)
        assertTrue(Variants.rules("Scarce Ones (5 Suits)").scarceOnes)
        assertEquals(4, Variants.rules("Critical Fours (5 Suits)").criticalRank)
        assertTrue(Variants.rules("Clue Starved (5 Suits)").clueStarved)
        assertEquals(Variants.rules("No Variant"), live.hanab.client.game.VariantRules())
    }

    @Test fun blindMuteAndAlternatingVariantsAreModelled() {
        for (v in listOf("Color Blind (5 Suits)", "Number Blind (3 Suits)", "Totally Blind (6 Suits)", "Color Mute (4 Suits)",
            "Alternating Clues (5 Suits)", "Alternating Clues & Prism (5 Suits)")) {
            assertTrue("$v should be offered", Variants.isSupported(v))
            assertFalse("the bot does not play $v", v in Variants.botSupported())
        }
        assertTrue(Variants.rules("Color Blind (5 Suits)").colorCluesTouchNothing)
        assertTrue(Variants.rules("Totally Blind (5 Suits)").let { it.colorCluesTouchNothing && it.rankCluesTouchNothing })
        assertEquals(emptyList<String>(), Variants.rules("Color Mute (5 Suits)").clueColorsOverride)
        assertTrue(Variants.rules("Alternating Clues (5 Suits)").alternatingClues)
        assertTrue(Variants.isSupported("Alternating Clues & Orange (5 Suits)"))
        assertFalse("the bot does not play Orange", "Orange (5 Suits)" in Variants.botSupported())
        assertTrue(Variants.info("Orange").inverted && Variants.info("Dark Orange").inverted)
        assertEquals("pips/orange.svg", Variants.pipFile("Orange"))
        assertEquals(2275,Variants.allSupported().size)
    }

    @Test fun searchFindsEveryVariantWhoseNameHasTheWords() {
        val all = Variants.allSupported()
        // "Rainbow" used to show only the first few matches, and never Rainbow-Ones.
        val rainbow = Variants.search("Rainbow", all)
        assertTrue(rainbow.contains("Rainbow-Ones (5 Suits)"))
        assertTrue(rainbow.contains("Rainbow (5 Suits)"))
        // Hyphens and case do not matter; every word must be there, and the name that starts with them is first.
        val ones = Variants.search("rainbow ones 5", all)
        assertEquals("Rainbow-Ones (5 Suits)", ones.first())
        assertTrue(ones.all { it.lowercase().let { n -> n.contains("rainbow") && n.contains("ones") && n.contains("5") } })
        assertTrue(Variants.search("clue starved", all).all { it.startsWith("Clue Starved") })
        assertTrue(Variants.search("ones", all).contains("White-Ones (6 Suits)"))
        assertEquals(all, Variants.search("  ", all))
        assertTrue(Variants.search("no such variant zzz", all).isEmpty())
    }

    @Test fun searchPutsNamesThatStartWithTheQueryFirst() {
        val result = Variants.search("rainbow", Variants.allSupported())
        val firstOther = result.indexOfFirst { !it.lowercase().startsWith("rainbow") }
        val lastStart = result.indexOfLast { it.lowercase().startsWith("rainbow") }
        assertTrue("names starting with the query come first", firstOther == -1 || lastStart < firstOther)
    }

    @Test fun theSupportedListIsOnlyVariantsWhoseSuitsAreAllKnown() {
        val supported = Variants.allSupported()
        assertTrue("expected hundreds of supported variants, got ${supported.size}", supported.size > 300)
        for (name in supported) {
            val suits = Variants.suits(name)!!
            assertTrue("$name has no suits", suits.isNotEmpty())
            for (s in suits) assertEquals("suit $s of $name is not modelled", s, Variants.info(s).name)
        }
    }
}