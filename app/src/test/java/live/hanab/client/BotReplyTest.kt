package live.hanab.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotReplyTest {
    // Reply texts copied from scala-bot's command.scala.
    @Test fun readsFastModeFromSettingsReply() {
        assertTrue(replyShowsFastMode("Currently playing with HGroup11 conventions. [fast mode]"))
        assertFalse(replyShowsFastMode("Currently playing with Reactor1 conventions."))
    }

    @Test fun conventionNamesAreTheOnesTheBotParses() {
        assertTrue("HGroup12" in BotStore.conventions && "HGroup1" in BotStore.conventions)
        assertTrue("RefSieve" in BotStore.conventions && "Reactor1" in BotStore.conventions)
        assertFalse("HGroup13" in BotStore.conventions)
    }
}