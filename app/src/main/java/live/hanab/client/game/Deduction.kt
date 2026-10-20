package live.hanab.client.game

/**
 * Card deduction, following the website's cardDeductionReducer.
 *
 * For a card X held by player p, an identity is possible only if the OTHER cards p could use to rule
 * things out can still be given identities, within the copy limits of the deck, while leaving a copy
 * for X. That is stronger than crossing off identities one at a time: if two cards could each only
 * be red 5 or yellow 5, they hold both 5s between them, so no third card can be a red or yellow 5.
 *
 * A card helps p only if p can see it (it is in someone else's hand, or played / discarded), or a clue
 * has touched it (it is in p's own hand but p knows something about it). What p knows about each of
 * those cards:
 *  - p's own cards: p's own deduced set (their "empathy");
 *  - a card we know exactly: that identity;
 *  - a card we cannot see (in our own hand) that p can: our best deduction about it. This is how we
 *    "pretend to know what the other players see".
 *
 * Our own unknown cards are worked out first, then each hand from its holder's side; the passes are
 * repeated until nothing changes, which matches the website recalculating after every action.
 */
internal class Deduction(private val g: GameState) {
    private val numSuits = g.suits.size
    private val numIds = numSuits * 5

    private fun id(suit: Int, rank: Int) = suit * 5 + (rank - 1)
    private fun suitOf(id: Int) = id / 5
    private fun rankOf(id: Int) = id % 5 + 1

    private val total = IntArray(numIds) { g.copies(suitOf(it), rankOf(it)) }

    /** Which player's hand each drawn card is in; absent = played or discarded. */
    private val owner = HashMap<Int, Int>()

    /** Our best guess for each card in a hand (what we can deduce, given everything we see). */
    private val mine = HashMap<Int, Set<Int>>()

    /** What the holder can deduce about each card in their own hand. */
    private val empathy = HashMap<Int, Set<Int>>()

    private var budget = 0

    init {
        g.hands.forEachIndexed { p, hand -> hand.forEach { owner[it] = p } }
    }

    private fun known(c: Card) = c.suit in 0 until numSuits && c.rank in 1..5

    private fun idsOf(c: Card): Set<Int> = c.possible.mapTo(HashSet()) { id(it.first, it.second) }

    /** Result: for each card in a hand, what its holder can deduce (our own view for our hand). */
    fun run(): Map<Int, Set<Int>> {
        for ((o, p) in owner) {
            val c = g.cards[o] ?: continue
            val base = idsOf(c)
            empathy[o] = base
            mine[o] = if (known(c)) setOf(id(c.suit, c.rank)) else base
        }
        repeat(6) {
            var changed = false
            // 1. our own unknown cards, from our side
            for ((o, p) in owner) {
                val c = g.cards[o] ?: continue
                if (known(c)) continue
                val cur = mine[o] ?: continue
                if (cur.size <= 1) continue
                val next = filter(cur, othersFor(g.ourIndex, o))
                if (next.size < cur.size) {
                    mine[o] = next
                    if (p == g.ourIndex) empathy[o] = next
                    changed = true
                }
            }
            // 2. every hand from its holder's side (our own hand was done above, unless we can see it all)
            for ((o, p) in owner) {
                val c = g.cards[o] ?: continue
                if (p == g.ourIndex && !known(c)) continue
                val cur = empathy[o] ?: continue
                if (cur.size <= 1) continue
                val next = filter(cur, othersFor(p, o))
                if (next.size < cur.size) {
                    empathy[o] = next
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        // Our own hand: what we deduce. Everyone else: what they deduce.
        val out = HashMap<Int, Set<Int>>()
        for ((o, p) in owner) {
            val c = g.cards[o] ?: continue
            out[o] = if (p == g.ourIndex && !known(c)) (mine[o] ?: empathy[o]).orEmpty() else empathy[o].orEmpty()
        }
        return out
    }

    /** The possibility sets of every other card that player [p] can use to rule out identities of card [x]. */
    private fun othersFor(p: Int, x: Int): List<Set<Int>> {
        val out = ArrayList<Set<Int>>()
        for ((o, c) in g.cards) {
            if (o == x) continue
            val holder = owner[o]
            if (holder == null) {
                // Played or discarded: visible to everyone.
                if (known(c)) out += setOf(id(c.suit, c.rank))
            } else if (holder == p) {
                // In p's own hand: p can use it only if a clue told them something about it.
                if (c.clueApplied) out += empathy[o].orEmpty()
            } else {
                // In someone else's hand: p can see it.
                out += if (known(c)) setOf(id(c.suit, c.rank)) else mine[o].orEmpty()
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Keep the identities in [candidates] for which a valid assignment exists: every set in [others]
     * gets one of its identities, no identity is used more often than it has copies, and a copy is
     * left for the card itself.
     */
    private fun filter(candidates: Set<Int>, others: List<Set<Int>>): Set<Int> {
        val counts = total.copyOf()
        // Cards that can only be one thing use up their copy first (an impossible extra is ignored).
        val multi = ArrayList<Set<Int>>()
        for (s in others) {
            if (s.size == 1) {
                val only = s.first()
                if (counts[only] > 0) counts[only]--
            } else multi += s
        }
        multi.sortBy { it.size }

        val valid = HashSet<Int>()
        budget = 200_000
        for (cand in candidates) {
            if (cand in valid) continue
            if (counts[cand] <= 0) continue
            counts[cand]--
            val ok = assign(0, multi, counts, cand, candidates, valid)
            counts[cand]++
            if (ok) valid += cand
            else if (budget <= 0) valid += cand // gave up searching: do not rule anything out
        }
        return if (valid.isEmpty()) candidates else candidates.filterTo(HashSet()) { it in valid }
    }

    private fun assign(
        i: Int,
        multi: List<Set<Int>>,
        counts: IntArray,
        cand: Int,
        candidates: Set<Int>,
        valid: MutableSet<Int>,
    ): Boolean {
        if (i == multi.size) {
            // A full assignment exists. Any other candidate that still has a copy left could take
            // this card's place without disturbing it, so it is valid too.
            for (q in candidates) if (q !in valid && counts[q] + (if (q == cand) 1 else 0) >= 1) valid += q
            return true
        }
        if (--budget <= 0) return true
        for (idv in multi[i]) {
            if (counts[idv] > 0) {
                counts[idv]--
                val ok = assign(i + 1, multi, counts, cand, candidates, valid)
                counts[idv]++
                if (ok) return true
            }
        }
        return false
    }

    fun toPair(id: Int) = suitOf(id) to rankOf(id)
}
