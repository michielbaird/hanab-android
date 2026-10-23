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
        // Anything with a Prism suit (cycling colours) or one of the special-rule keys is out.
        val prism = Variants.suits("Prism (5 Suits)") != null
        if (prism) assertFalse(Variants.isSupported("Prism (5 Suits)"))
        assertFalse(Variants.isSupported("Funnels (5 Suits)"))
        assertFalse(Variants.isSupported("Chimneys (5 Suits)"))
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