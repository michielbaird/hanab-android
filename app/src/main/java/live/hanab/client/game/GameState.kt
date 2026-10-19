package live.hanab.client.game

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What we know about one card. suit/rank are -1 while unknown (e.g. cards in our own hand). */
data class Card(
    val order: Int,
    val suit: Int = -1,
    val rank: Int = -1,
    /** (suit, rank) identities still possible given the clues this card has received. */
    val possible: Set<Pair<Int, Int>>,
    val clued: Boolean = false,
) {
    val possibleSuits: Set<Int> get() = possible.mapTo(HashSet()) { it.first }
    val possibleRanks: Set<Int> get() = possible.mapTo(HashSet()) { it.second }
}

data class LogLine(val text: String)

/** The most recent clue: which cards it touched and what was said. Shown as a bubble for one turn. */
data class ClueMark(
    val orders: Set<Int>,
    /** Colour name for a colour clue, else null. */
    val color: String?,
    /** Rank for a rank clue, else null. */
    val rank: Int?,
    val giver: Int,
    val target: Int,
    /** Turn number when the clue was given; the bubble lasts through the next turn. */
    val turn: Int,
)

/** A card that was just drawn live; the UI flies it in from the deck. */
data class DrawEvent(val id: Long, val player: Int, val order: Int)

/** A play or discard that just happened live; the UI animates it and then consumes it. */
data class MoveEvent(
    val id: Long,
    val play: Boolean,
    val player: Int,
    val order: Int,
    val suit: Int,
    val rank: Int,
    val failed: Boolean,
)

/** Possible (suit, rank) identities for one card. [inferred] is always a subset of [direct]. */
data class Knowledge(val direct: Set<Pair<Int, Int>>, val inferred: Set<Pair<Int, Int>>) {
    val directSuits get() = direct.map { it.first }.toSortedSet()
    val directRanks get() = direct.map { it.second }.toSortedSet()
    val inferredSuits get() = inferred.map { it.first }.toSortedSet()
    val inferredRanks get() = inferred.map { it.second }.toSortedSet()

    /** True when only deduction (not a clue) pins the identity down. */
    val solvedByInference get() = inferred.size == 1 && direct.size > 1
}

enum class Phase { Playing, Over }

/**
 * Immutable snapshot of a game, rebuilt by feeding it the server's `gameAction` stream.
 * Supports plain-suit variants (every suit has exactly one colour and rank clues touch only that rank).
 */
data class GameState(
    val tableID: Int,
    val playerNames: List<String>,
    val ourIndex: Int,
    val spectating: Boolean,
    val variantName: String,
    val suits: List<String>,
    /** Clue behaviour per suit; defaults to plain suits (touched by their own colour and by ranks). */
    val suitInfos: List<SuitInfo> = suits.map { SuitInfo(it) },
    val cards: Map<Int, Card> = emptyMap(),
    /** Card orders per player, newest card first. */
    val hands: List<List<Int>> = playerNames.map { emptyList() },
    /** Highest rank played per suit (0 = nothing played). */
    val stacks: List<Int> = suits.map { 0 },
    val discards: List<Int> = emptyList(),
    val clues: Int = 8,
    val strikes: Int = 0,
    val score: Int = 0,
    val maxScore: Int = suits.size * 5,
    val turn: Int = 0,
    val currentPlayer: Int = 0,
    val cardsDrawn: Int = 0,
    val deckSize: Int = suitInfos.map { if (it.oneOfEach) 5 else 10 }.sum(),
    val phase: Phase = Phase.Playing,
    val log: List<LogLine> = emptyList(),
    /** Our private notes, keyed by card order. */
    val notes: Map<Int, String> = emptyMap(),
    /** Every action applied so far, in order; lets us rebuild any earlier position. */
    val actions: List<JsonObject> = emptyList(),
    /** True for a rebuilt past position: no moves may be made from it. */
    val readOnly: Boolean = false,
    val lastClue: ClueMark? = null,
    /** True identities of every card (index = card order), sent once the game is a replay. */
    val identities: List<Pair<Int, Int>> = emptyList(),
    /** After the game ends every card, including our own, is visible. */
    val revealAll: Boolean = false,
    /** EndCondition value from the server (1 = normal, 2 = strikeout, 4/10 = terminated, ...). */
    val endCondition: Int? = null,
) {
    val numPlayers get() = playerNames.size

    /** The other players in the order they will act after us; the last one acts just before us. */
    val othersInTurnOrder: List<Int> get() = (1 until numPlayers).map { (ourIndex + it) % numPlayers }

    /** Any ending other than a normal finish (strikeout, timeout, terminated, ...) scores zero. */
    val finalScore: Int get() = if (phase == Phase.Over && (endCondition ?: 1) > 1) 0 else score

    /** Short human text for how the game ended. */
    val endText: String get() = when (endCondition) {
        1 -> if (score == maxScore) "Perfect score" else "Out of cards"
        2 -> "Three strikes"
        3 -> "Time ran out"
        4 -> "Terminated by a player"
        6 -> "Idle timeout"
        10 -> "Terminated by vote"
        null -> "Game over"
        else -> "Game over"
    }

    /** Colours offered as colour clues, in the order the server numbers them (Rainbow adds none). */
    val clueColors: List<String> by lazy {
        suitInfos.filter { !it.allColors }.flatMap { it.colors }.distinct()
    }

    private val allIdentities: Set<Pair<Int, Int>> by lazy {
        suits.indices.flatMap { s -> (1..5).map { r -> s to r } }.toSet()
    }

    /** Does a colour clue (index into [clueColors]) touch a card of this suit? */
    fun touchesColor(suit: Int, colorIdx: Int): Boolean {
        val info = suitInfos.getOrNull(suit) ?: return false
        if (info.allColors) return true
        if (info.noColors) return false
        return clueColors.getOrNull(colorIdx) in info.colors
    }

    /** Does a rank clue touch a card of this suit and rank? */
    fun touchesRank(suit: Int, rank: Int, clueRank: Int): Boolean {
        val info = suitInfos.getOrNull(suit) ?: return false
        if (info.allRanks) return true
        if (info.noRanks) return false
        return rank == clueRank
    }
    val ourTurn get() = !readOnly && !spectating && phase == Phase.Playing && currentPlayer == ourIndex
    val handSize get() = if (numPlayers <= 3) 5 else 4

    fun apply(a: JsonObject): GameState = applyOne(a).let { next ->
        if (next === this) this else next.copy(actions = actions + a)
    }

    /** Positions worth stopping at when stepping back: right after each "turn" action. */
    val turnStops: List<Int> by lazy {
        actions.mapIndexedNotNull { i, a -> if (a["type"]?.jsonPrimitive?.content == "turn") i + 1 else null }
    }

    /** The game as it was after the first [k] actions (read-only). */
    fun replayTo(k: Int): GameState {
        val blank = GameState(tableID, playerNames, ourIndex, spectating, variantName, suits)
        val past = blank.applyAll(actions.take(k)).copy(actions = actions, notes = notes, readOnly = true)
        return if (revealAll) past.withIdentities(identities) else past
    }

    /** Reveal all cards using the server's list of identities (sent when a game becomes a replay). */
    fun withIdentities(list: List<Pair<Int, Int>>): GameState = copy(
        identities = list,
        revealAll = true,
        cards = cards.mapValues { (o, c) ->
            val id = list.getOrNull(o)
            if (c.suit < 0 && id != null && id.first >= 0) c.copy(suit = id.first, rank = id.second) else c
        },
    )

    /** Copies of (suit, rank) not yet played or discarded. */
    fun remaining(suit: Int, rank: Int): Int =
        copies(suit, rank) - (if (stacks[suit] >= rank) 1 else 0) - (discardCounts[suit to rank] ?: 0)

    /** Of those, how many are not visible to us in anyone's hand (so could be in the deck or our hand). */
    fun unseen(suit: Int, rank: Int): Int {
        val inHands = hands.flatten().mapNotNull { cards[it] }.count { it.suit == suit && it.rank == rank }
        return remaining(suit, rank) - inHands
    }

    private fun applyOne(a: JsonObject): GameState {
        fun int(key: String) = a[key]?.jsonPrimitive?.intOrNull ?: -1
        return when (a["type"]?.jsonPrimitive?.content) {
            "draw" -> {
                val p = int("playerIndex"); val o = int("order")
                if (cards.containsKey(o)) return this // already applied (duplicate delivery)
                val card = Card(o, int("suitIndex"), int("rank"), possible = allIdentities)
                copy(
                    cards = cards + (o to card),
                    hands = hands.mapIndexed { i, h -> if (i == p) listOf(o) + h else h },
                    deckSize = deckSize - 1,
                )
            }
            "play" -> {
                val p = int("playerIndex"); val o = int("order"); val s = int("suitIndex"); val r = int("rank")
                val newStacks = if (s in stacks.indices) stacks.mapIndexed { i, v -> if (i == s) r else v } else stacks
                removeFromHand(p, o).copy(
                    cards = cards.reveal(o, s, r),
                    stacks = newStacks,
                ).logged("${name(p)} played ${cardName(s, r)}")
            }
            "discard" -> {
                val p = int("playerIndex"); val o = int("order"); val s = int("suitIndex"); val r = int("rank")
                val failed = a["failed"]?.jsonPrimitive?.booleanOrNull ?: false
                removeFromHand(p, o).copy(
                    cards = cards.reveal(o, s, r),
                    discards = discards + o,
                ).logged("${name(p)} ${if (failed) "misplayed" else "discarded"} ${cardName(s, r)}")
            }
            "clue" -> {
                val clue = a["clue"]!!.jsonObject
                val isColor = clue["type"]!!.jsonPrimitive.int == 0
                val value = clue["value"]!!.jsonPrimitive.int
                // Go encodes an empty list as null.
                val touched = (a["list"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet()
                val target = int("target"); val giver = int("giver")
                val newCards = cards.mapValues { (o, c) ->
                    if (o !in hands.getOrElse(target) { emptyList() }) c
                    else {
                        val hit = o in touched
                        // Keep identities that agree with whether this card was touched.
                        val kept = c.possible.filter { (s, r) ->
                            (if (isColor) touchesColor(s, value) else touchesRank(s, r, value)) == hit
                        }.toSet()
                        c.copy(possible = kept.ifEmpty { c.possible }, clued = c.clued || hit)
                    }
                }
                val what = if (isColor) clueColors.getOrElse(value) { "?" } else "$value"
                copy(
                    cards = newCards,
                    lastClue = ClueMark(
                        orders = touched,
                        color = if (isColor) clueColors.getOrNull(value) else null,
                        rank = if (isColor) null else value,
                        giver = giver, target = target, turn = turn,
                    ),
                ).logged("${name(giver)} told ${name(target)} $what (${touched.size} card${if (touched.size == 1) "" else "s"})")
            }
            "status" -> copy(clues = int("clues"), score = int("score"), maxScore = int("maxScore"))
            "strike" -> copy(strikes = int("num"))
            "turn" -> copy(turn = int("num"), currentPlayer = int("currentPlayerIndex"))
            "gameOver" -> copy(phase = Phase.Over, endCondition = int("endCondition")).logged("Game over")
            else -> this
        }
    }

    /** Never throws: a malformed action is shown in the log instead of killing the update stream. */
    fun applySafe(a: JsonObject): GameState =
        try { apply(a) } catch (e: Exception) { logged("[error] ${a["type"]}: ${e.message}") }

    fun applyAll(actions: List<JsonObject>) = actions.fold(this) { s, a -> s.applySafe(a) }

    private fun removeFromHand(p: Int, o: Int) =
        copy(hands = hands.mapIndexed { i, h -> if (i == p) h - o else h })

    private fun Map<Int, Card>.reveal(o: Int, s: Int, r: Int): Map<Int, Card> {
        val c = this[o] ?: return this
        return this + (o to c.copy(suit = s, rank = r))
    }

    private fun logged(t: String) = copy(log = (log + LogLine(t)).takeLast(60))
    fun name(p: Int) = playerNames.getOrElse(p) { "?" }
    fun cardName(s: Int, r: Int) = if (s < 0 || r < 0) "a card" else "${suits.getOrElse(s) { "?" }} $r"

    /** Playable-now check used for hints in the UI. */
    fun isPlayable(c: Card) = c.suit in stacks.indices && c.rank == stacks[c.suit] + 1

    /**
     * What player [p] can work out about each card in their hand, from where we sit.
     *  - direct: identities not ruled out by the clues that touched / missed the card.
     *  - inferred: direct, minus identities whose every copy is visible to [p] (in other hands, the
     *    discards or the stacks), repeated while cards in the hand resolve to a single identity.
     * For a partner this is a lower bound: they also see our hand, which we can't.
     */
    fun knowledgeOf(p: Int): Map<Int, Knowledge> {
        val hand = hands.getOrElse(p) { emptyList() }.mapNotNull { cards[it] }
        val mine = hand.map { it.order }.toSet()
        val seen = HashMap<Pair<Int, Int>, Int>()
        for (c in cards.values) {
            if (c.suit >= 0 && c.rank >= 0 && c.order !in mine) seen.merge(c.suit to c.rank, 1, Int::plus)
        }
        val direct = hand.associate { c ->
            c.order to c.possible
        }
        var inferred = direct.mapValues { (_, set) ->
            set.filter { (s, r) -> (seen[s to r] ?: 0) < copies(s, r) }.toSet().ifEmpty { set }
        }
        repeat(6) {
            val taken = HashMap<Pair<Int, Int>, Int>()
            inferred.values.filter { it.size == 1 }.forEach { taken.merge(it.first(), 1, Int::plus) }
            inferred = inferred.mapValues { (_, set) ->
                if (set.size == 1) set
                else set.filter { id -> (seen[id] ?: 0) + (taken[id] ?: 0) < copies(id.first, id.second) }.toSet().ifEmpty { set }
            }
        }
        return direct.mapValues { (o, d) -> Knowledge(d, inferred.getValue(o)) }
    }

    /** True if at least one of [ids] could be played on its stack right now. */
    fun anyPlayableNow(ids: Set<Pair<Int, Int>>) = ids.any { (s, r) -> s in stacks.indices && r == stacks[s] + 1 }

    /** True if every one of [ids] is playable on its stack right now. */
    fun allPlayableNow(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> s in stacks.indices && r == stacks[s] + 1 }

    /** "Red 3", or "a card" if [ids] is not a single identity. */
    fun describe(ids: Set<Pair<Int, Int>>): String =
        ids.singleOrNull()?.let { (s, r) -> "${suits.getOrElse(s) { "?" }} $r" } ?: "this card"

    fun withNotes(list: List<String>) =
        copy(notes = list.mapIndexedNotNull { order, t -> if (t.isBlank()) null else order to t }.toMap())

    fun withNote(order: Int, text: String) =
        copy(notes = if (text.isBlank()) notes - order else notes + (order to text.trim()))

    /** How many copies of each (suit, rank) are in the discard pile. */
    val discardCounts: Map<Pair<Int, Int>, Int> by lazy {
        discards.mapNotNull { cards[it] }.filter { it.suit >= 0 && it.rank >= 0 }
            .groupingBy { it.suit to it.rank }.eachCount()
    }

    /** Copies of a card in the deck: 3/2/2/2/1 by rank, or 1 for a one-of-each suit (Black). */
    fun copies(suit: Int, rank: Int): Int =
        if (suitInfos.getOrNull(suit)?.oneOfEach == true) 1 else when (rank) { 1 -> 3; 5 -> 1; else -> 2 }

    /**
     * A (suit, rank) is dead if it is already played, or if every copy of a lower rank in that suit
     * that is still needed has been discarded (so the stack can never reach it).
     */
    fun isDeadIdentity(suit: Int, rank: Int): Boolean {
        if (suit !in stacks.indices || rank !in 1..5) return false
        if (rank <= stacks[suit]) return true
        return (stacks[suit] + 1 until rank).any { r -> (discardCounts[suit to r] ?: 0) >= copies(suit, r) }
    }

    /** Last remaining copy of a still-needed card: discarding it caps the maximum score. */
    fun isCriticalIdentity(suit: Int, rank: Int): Boolean =
        suit in stacks.indices && !isDeadIdentity(suit, rank) &&
            copies(suit, rank) - (discardCounts[suit to rank] ?: 0) <= 1

    /** Critical if every identity in [ids] is critical. */
    fun allCritical(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> isCriticalIdentity(s, r) }

    /** Safe to discard if every identity in [ids] is dead. */
    fun allDead(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> isDeadIdentity(s, r) }

    /** True only when every identity the card could still be is dead (works for clued own cards). */
    fun isDead(c: Card): Boolean {
        val ids = if (c.suit >= 0 && c.rank >= 0) setOf(c.suit to c.rank) else c.possible
        return ids.isNotEmpty() && ids.all { (s, r) -> isDeadIdentity(s, r) }
    }

    companion object {
        fun totalCards(numSuits: Int) = numSuits * 10
    }
}
