package live.hanab.client

import kotlinx.serialization.json.JsonObject
import live.hanab.client.game.GameState
import live.hanab.client.game.NoteEntry
import live.hanab.client.net.HanabJson
import live.hanab.client.net.unpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameStateTest {
    private fun live(frame: String): JsonObject {
        val m = unpack(frame)!!
        assertEquals("gameAction", m.command)
        return (m.data as JsonObject)["action"] as JsonObject
    }

    private fun start() = GameState(
        tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
        variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
    ).applyAll(
        (0..3).map { HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":0,"order":$it,"suitIndex":-1,"rank":-1}""") as JsonObject } +
        (4..7).map { HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":$it,"suitIndex":0,"rank":1}""") as JsonObject }
    )

    @Test fun livePlayThenDraw() {
        var g = start()
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"play","playerIndex":0,"order":2,"suitIndex":0,"rank":1}}"""))
        assertFalse(2 in g.hands[0])
        assertEquals(1, g.stacks[0])
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"draw","playerIndex":0,"order":8,"suitIndex":-1,"rank":-1}}"""))
        assertEquals(listOf(8, 3, 1, 0), g.hands[0])
    }

    @Test fun liveClueUpdatesOwnHand() {
        var g = start()
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[1,3],"target":0,"ignoreNegative":false}}"""))
        assertEquals(setOf(5), g.cards[1]!!.possibleRanks)
        assertEquals(setOf(1, 2, 3, 4), g.cards[0]!!.possibleRanks)
    }

    @Test fun deadCards() {
        fun d(order: Int, rank: Int) = HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":$order,"suitIndex":0,"rank":$rank,"failed":false}""") as JsonObject
        fun draw(order: Int, rank: Int) = HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":$order,"suitIndex":0,"rank":$rank}""") as JsonObject
        var g = start().applyAll(listOf(draw(20, 2), draw(21, 2), draw(22, 3), draw(23, 1)))
        // red 1 not played yet: red 3 is alive; discarding both red 2s kills it.
        assertFalse(g.isDead(g.cards[22]!!))
        g = g.applyAll(listOf(d(20, 2), d(21, 2)))
        assert(g.isDead(g.cards[22]!!))
        // a played red 1 is dead
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"play","playerIndex":1,"order":23,"suitIndex":0,"rank":1}""") as JsonObject)
        assert(g.isDead(g.cards[23]!!))
    }
    @Test fun inferenceRemovesExhaustedIdentities() {
        // We hold orders 0..3 (unknown). Bob (player 1) holds red 1s at 4..7 in start(); put two more
        // red 1s... the third copy: discard one red 1 and Bob holds two => all 3 red 1s are visible to us.
        var g = start()
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":4,"suitIndex":0,"rank":1,"failed":false}""") as JsonObject)
        // Seen red 1s: 4 (discarded), 5, 6, 7 => 4 copies > 3, so red 1 is impossible for our cards.
        // Clue one of our cards as red (touches order 1): direct = red 1..5, inferred loses red 1.
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"list":[1],"target":0,"ignoreNegative":false}""") as JsonObject)
        val k = g.knowledgeOf(0).getValue(1)
        assert(0 to 1 in k.direct)
        assert(0 to 1 !in k.inferred)
        assertEquals(setOf(2, 3, 4, 5), k.inferredRanks)
    }
    @Test fun criticalCards() {
        var g = start()
        assert(g.isCriticalIdentity(0, 5))          // 5s have a single copy
        assert(!g.isCriticalIdentity(0, 2))         // two copies still around
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":30,"suitIndex":0,"rank":2}""") as JsonObject)
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":30,"suitIndex":0,"rank":2,"failed":false}""") as JsonObject)
        assert(g.isCriticalIdentity(0, 2))          // one red 2 gone: the other is critical
        assert(!g.isCriticalIdentity(0, 1) )        // three red 1s, none discarded
    }
    @Test fun replayToEarlierTurn() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        var g = start()
        g = g.apply(j("""{"type":"turn","num":0,"currentPlayerIndex":0}"""))
        g = g.apply(j("""{"type":"play","playerIndex":0,"order":2,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":8,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        assertEquals(2, g.turnStops.size)
        val past = g.replayTo(g.turnStops[0])
        assert(2 in past.hands[0])        // before the play, the card is still in hand
        assertEquals(0, past.stacks[0])
        assert(past.readOnly && !past.ourTurn)
        assertFalse(2 in g.hands[0])      // live state unchanged
        assertEquals(1, g.stacks[0])
        assertEquals(2, g.remaining(0, 1)) // one red 1 played of three
    }
    @Test fun rainbowIsTouchedByEveryColour() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Yellow"),
            live.hanab.client.game.SuitInfo("Rainbow", colors = emptyList(), allColors = true),
        )
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Rainbow (3 Suits)", suits = infos.map { it.name }, suitInfos = infos,
        )
        assertEquals(listOf("Red", "Yellow"), g.clueColors)        // Rainbow adds no colour of its own
        assert(g.touchesColor(2, 0) && g.touchesColor(2, 1))       // Rainbow: touched by red and yellow
        assert(g.touchesColor(0, 0) && !g.touchesColor(0, 1))
        // Our card 0 is touched by a red clue: it is Red or Rainbow, never Yellow.
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":1,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        assertEquals(setOf(0, 2), g.cards[0]!!.possibleSuits)
        // Card 1 was not touched by red, so it is neither Red nor Rainbow: it must be Yellow.
        assertEquals(setOf(1), g.cards[1]!!.possibleSuits)
    }
    @Test fun turnOrderAndFinalScore() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        val g = GameState(
            tableID = 1, playerNames = listOf("a", "b", "c", "me", "e"), ourIndex = 3, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // After "me" (3) come e (4), a (0), b (1), c (2): top to bottom, with me at the bottom.
        assertEquals(listOf(4, 0, 1, 2), g.othersInTurnOrder)
        var s = g.apply(j("""{"type":"status","clues":4,"score":17,"maxScore":25}"""))
        assertEquals(17, s.finalScore)                       // in progress: progress counts
        s = s.apply(j("""{"type":"gameOver","endCondition":2,"playerIndex":0,"votes":null}"""))
        assertEquals(0, s.finalScore)                        // strikeout: score is zero
        val won = g.apply(j("""{"type":"status","clues":4,"score":25,"maxScore":25}"""))
            .apply(j("""{"type":"gameOver","endCondition":1,"playerIndex":0,"votes":null}"""))
        assertEquals(25, won.finalScore)                     // normal ending keeps the score
    }
    @Test fun blackSuitHasOneCopyOfEachRank() {
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Black", oneOfEach = true),
        )
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Black (2 Suits)", suits = listOf("Red", "Black"), suitInfos = infos,
        )
        assertEquals(15, g.deckSize)                         // 10 red + 5 black
        assertEquals(1, g.copies(1, 1))                      // one black 1, not three
        assertEquals(3, g.copies(0, 1))                      // red 1s unchanged
        assert(g.isCriticalIdentity(1, 1) && g.isCriticalIdentity(1, 3))   // every black card is critical
        assert(!g.isCriticalIdentity(0, 3))
    }
    @Test fun denominatorIsPerfectScoreAfterTheGame() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        var g = GameState(
            tableID = 1, playerNames = listOf("a", "me"), ourIndex = 1, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // A critical card was discarded: the best reachable score fell to 24 while the game is on.
        g = g.apply(j("""{"type":"status","clues":4,"score":24,"maxScore":24}"""))
        assertEquals(24, g.scoreDenominator)                 // in progress: show what is still reachable
        assertEquals(25, g.perfectScore)
        g = g.apply(j("""{"type":"gameOver","endCondition":1,"playerIndex":0,"votes":null}"""))
        assertEquals(25, g.scoreDenominator)                 // finished: out of the true perfect score
        assertEquals("Out of cards", g.endText)              // 24/25 is not a perfect game
    }
    private fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject

    /** Two cards that could each only be red 5 or yellow 5 hold both 5s between them. */
    @Test fun deductionFindsPigeonholes() {
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "2 Suits", suits = listOf("Red", "Yellow"),
        )
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":1,"suitIndex":-1,"rank":-1}"""))
        // A 5 clue touches both: each is a red 5 or a yellow 5.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[0,1],"target":0,"ignoreNegative":false}"""))
        // A new card, no clues at all: any of the 10 identities by clues alone.
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":2,"suitIndex":-1,"rank":-1}"""))
        val k = g.knowledgeOf(0).getValue(2)
        assertEquals(setOf(1, 2, 3, 4, 5), k.directRanks)
        // Both 5s are taken by the two clued cards, so the new card cannot be a 5.
        assertEquals(setOf(1, 2, 3, 4), k.inferredRanks)
        // The two clued cards themselves stay red-5-or-yellow-5.
        assertEquals(setOf(0 to 5, 1 to 5), g.knowledgeOf(0).getValue(0).inferred)
    }

    /** Watching a game: the player in the observed slot has deductions too (so the toggle works there). */
    @Test fun spectatorCanSeeWhatTheObservedPlayerKnows() {
        var g = GameState(
            tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = true,
            variantName = "2 Suits", suits = listOf("Red", "Yellow"),
        )
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":0,"rank":5}"""))   // a spectator sees red 5
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":1,"suitIndex":1,"rank":5}"""))   // and yellow 5
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        val k = g.knowledgeOf(0).getValue(0)
        // Player a was told "5", and can see b's yellow 5: their card must be the red 5.
        assertEquals(setOf(0 to 5), k.inferred)
    }
    @Test fun everyonesNotesAreKeptPerCard() {
        val g = GameState(
            tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // The server's "noteList": one entry per person, each with a text per card order.
        val all = g.withAllNotes(
            listOf(
                Triple("a", listOf("", "r5", ""), false),
                Triple("b", listOf("chop", "", ""), false),
                Triple("watcher", listOf("", "r5?", "dead"), true),
            ),
        )
        assertEquals(listOf("b"), all.allNotes[0]!!.map { it.name })
        assertEquals(setOf("a", "watcher"), all.allNotes[1]!!.map { it.name }.toSet())
        assertEquals(true, all.allNotes[2]!!.single().isSpectator)
        // A live per-card update replaces that card's notes only; blank ones are dropped.
        val updated = all.withCardNotes(1, listOf(NoteEntry("a", "", false), NoteEntry("watcher", "no, y5", true)))
        assertEquals(listOf("watcher"), updated.allNotes[1]!!.map { it.name })
        assertEquals(listOf("b"), updated.allNotes[0]!!.map { it.name })
    }
    /** Turn 1 (no clues or plays yet) can be stepped back to: the server sends no "turn" action for it. */
    @Test fun startingPositionIsAStop() {
        var g = start()   // the deal: draws only
        g = g.apply(j("""{"type":"status","clues":8,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[4],"target":1,"ignoreNegative":false}"""))
        g = g.apply(j("""{"type":"status","clues":7,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        g = g.apply(j("""{"type":"play","playerIndex":1,"order":4,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":8,"suitIndex":0,"rank":2}"""))
        g = g.apply(j("""{"type":"status","clues":7,"score":1,"maxScore":10}"""))
        g = g.apply(j("""{"type":"turn","num":2,"currentPlayerIndex":0}"""))
        // stops: the start, after move 1, after move 2
        assertEquals(3, g.turnStops.size)
        val start = g.replayTo(g.turnStops[0])
        assertEquals(0, start.turn)                              // "Turn 1"
        assertEquals(8, start.clues)                             // no clue given yet
        assertEquals(0, start.stacks[0])                         // nothing played
        assertEquals(null, start.lastClue)                       // and no clue bubble
        assertEquals(4, start.hands[1].size)                     // everybody still holds their dealt cards
        assertEquals(1, g.replayTo(g.turnStops[1]).turn)         // after the first move
    }
    @Test fun cardCyclingMovesTheGiversChopToTheFront() {
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
            options = live.hanab.client.game.GameOptions(cardCycle = true),
        )
        // bob holds orders 4..7, newest first: [7, 6, 5, 4]; his oldest (the chop) is 4.
        for (o in 0..3) g = g.apply(j("""{"type":"draw","playerIndex":0,"order":$o,"suitIndex":-1,"rank":-1}"""))
        for (o in 4..7) g = g.apply(j("""{"type":"draw","playerIndex":1,"order":$o,"suitIndex":0,"rank":1}"""))
        assertEquals(listOf(7, 6, 5, 4), g.hands[1])
        // bob clues me: his chop (4) goes to the newest slot.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        assertEquals(listOf(4, 7, 6, 5), g.hands[1])
        assertEquals(listOf(3, 2, 1, 0), g.hands[0])             // the receiver's hand is untouched
        // I clue bob's oldest card (5 is now the oldest): it counts as clued, so the chop is 6.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[5],"target":1,"ignoreNegative":false}"""))
        // my own chop is 1 (the card 0 was clued): it moves to the front of MY hand
        assertEquals(listOf(1, 3, 2, 0), g.hands[0])
    }

    @Test fun characterTextGetsItsRandomValue() {
        val colors = listOf("Red", "Yellow", "Green")
        val suits = listOf("Red", "Yellow", "Green", "Blue")
        assertEquals("Can only clue numbers and green", live.hanab.client.game.fillCharacterText("Can only clue numbers and [random color]", 2, colors, suits))
        assertEquals("Cannot clue 4s", live.hanab.client.game.fillCharacterText("Cannot clue [random number]s", 4, colors, suits).replace("Cannot clue 4s", "Cannot clue 4s"))
        assertEquals("Only Blue", live.hanab.client.game.fillCharacterText("Only [random suit]", 3, colors, suits))
        assertEquals("No placeholder", live.hanab.client.game.fillCharacterText("No placeholder", 1, colors, suits))
    }

    @Test fun tableOptionsSerialiseTheServersNames() {
        val json = live.hanab.client.net.TableOpts(variantName = "Rainbow (5 Suits)", cardCycle = true, emptyClues = true).toJson()
        assertEquals("\"Rainbow (5 Suits)\"", json["variantName"].toString())
        assertEquals("true", json["cardCycle"].toString())
        assertEquals("true", json["emptyClues"].toString())
        assertEquals("false", json["speedrun"].toString())
        assertEquals("false", json["timed"].toString())
        assertEquals(listOf("Card cycling", "Empty clues"), live.hanab.client.net.TableOpts(cardCycle = true, emptyClues = true).activeLabels)
    }
    /** Stepping one turn forward in a replay finds the play / discard to animate. */
    @Test fun movesBetweenStopsFindsThePlayOrDiscard() {
        var g = start()
        g = g.apply(j("""{"type":"status","clues":8,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[4],"target":1,"ignoreNegative":false}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        g = g.apply(j("""{"type":"play","playerIndex":1,"order":4,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":8,"suitIndex":0,"rank":2}"""))
        g = g.apply(j("""{"type":"turn","num":2,"currentPlayerIndex":0}"""))
        g = g.apply(j("""{"type":"discard","playerIndex":0,"order":0,"suitIndex":1,"rank":3,"failed":true}"""))
        g = g.apply(j("""{"type":"turn","num":3,"currentPlayerIndex":1}"""))
        assertEquals(4, g.turnStops.size)                                   // the start, then after each of 3 moves
        assertEquals(emptyList<Any>(), g.movesBetweenStops(0, 1))           // a clue: nothing flies
        val play = g.movesBetweenStops(1, 2).single()
        assertEquals(true, play.play); assertEquals(4, play.order); assertEquals(0, play.suit); assertEquals(1, play.rank)
        val miss = g.movesBetweenStops(2, 3).single()
        assertEquals(false, miss.play); assertEquals(true, miss.failed); assertEquals(0, miss.order)
        assertEquals(emptyList<Any>(), g.movesBetweenStops(0, 2))           // a jump
        assertEquals(emptyList<Any>(), g.movesBetweenStops(2, 1))           // going back
    }}
