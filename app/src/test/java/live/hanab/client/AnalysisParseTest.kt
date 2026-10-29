package live.hanab.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisParseTest {
    @Test fun readsCommentsWithTheNamesOfThePlayers() {
        val json = """{"players":["Ann","Bob","Cy"],"comments":[
            {"turn":3,"player":1,"mistake":false,"text":"suggest (Ann clues 1 to Bob) over (Bob discards)"},
            {"turn":12,"player":2,"mistake":true,"text":"mistake! prefer (a) over (b)"}]}"""
        val comments = parseAnalysis(json)
        assertEquals(2, comments.size)
        assertEquals(AnalysisComment(3, "Bob", false, "suggest (Ann clues 1 to Bob) over (Bob discards)"), comments[0])
        assertTrue(comments[1].mistake)
        assertEquals("Cy", comments[1].player)
    }

    @Test fun noCommentsMeansTheBotAgreedWithEveryMove() {
        assertTrue(parseAnalysis("""{"players":["A","B"],"comments":[]}""").isEmpty())
    }

    @Test fun anErrorFromTheBotIsThrown() {
        try {
            parseAnalysis("""{"error":"The bot could not follow this game."}""")
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertEquals("The bot could not follow this game.", e.message)
        }
    }
}
