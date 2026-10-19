@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import live.hanab.client.AnimSpeed
import live.hanab.client.AppSettings
import live.hanab.client.LobbyUiState
import live.hanab.client.LobbyViewModel
import live.hanab.client.game.Card
import live.hanab.client.game.Knowledge
import live.hanab.client.game.GameState
import live.hanab.client.game.Phase

/** Muted version of a colour for dead cards: still readable as that suit, but clearly washed out. */
fun dull(c: Color): Color = androidx.compose.ui.graphics.lerp(c, Color(0xFF3A3A3A), 0.6f)

/**
 * White text with a thin black outline, so numbers stay readable on every suit colour (white alone
 * disappears on Yellow). The outline is the text stamped in black in 8 directions at a small pixel
 * offset, which stays crisp at any size (a font stroke turns into a dark halo on small digits).
 */
@Composable
fun OutlinedText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fill: Color = Color.White,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    if (text.isEmpty()) return
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layout = remember(text, fontSize, fontWeight) {
        measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontWeight = fontWeight))
    }
    val style = AppSettings.outline
    val r = if (style.factor == 0f) 0f
    else (with(density) { fontSize.toPx() } * style.factor).coerceIn(1f, style.maxPx)
    val w = with(density) { (layout.size.width + 2 * r).toDp() }
    val h = with(density) { (layout.size.height + 2 * r).toDp() }
    androidx.compose.foundation.Canvas(modifier.size(w, h)) {
        if (r > 0f) for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            drawText(layout, color = Color.Black, topLeft = Offset(r + dx * r, r + dy * r))
        }
        drawText(layout, color = fill, topLeft = Offset(r, r))
    }
}
/** Solid colour for a suit or clue colour name, from suits.json (Rainbow's fills collapse to one colour). */
fun suitColor(name: String): Color {
    val info = live.hanab.client.game.Variants.info(name)
    val fills = if (AppSettings.colorblind && info.fillsColorblind.isNotEmpty()) info.fillsColorblind else info.fills
    return when {
        fills.size > 1 -> Color(0xFFBA68C8)
        fills.size == 1 -> Color(fills[0])
        else -> Color(0xFF616161)
    }
}

/** Like [suitColor], but multi-colour suits (Rainbow) get a left-to-right gradient. */
fun suitBrush(name: String): androidx.compose.ui.graphics.Brush {
    val fills = live.hanab.client.game.Variants.info(name).fills
    return if (fills.size > 1) androidx.compose.ui.graphics.Brush.horizontalGradient(fills.map { Color(it) })
    else androidx.compose.ui.graphics.SolidColor(suitColor(name))
}
@Composable
fun GameScreen(ui: LobbyUiState, g: GameState, vm: LobbyViewModel) {
    // Stop the phone sleeping (and dropping the connection) mid-game, if the setting is on.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(AppSettings.keepAwake) {
        view.keepScreenOn = AppSettings.keepAwake
        onDispose { view.keepScreenOn = false }
    }
    var tab by remember { mutableIntStateOf(0) }
    var seen by remember { mutableIntStateOf(0) }
    val total = ui.tableChat.size
    LaunchedEffect(tab, total) { if (tab == 1) seen = total }
    val unread = if (tab == 1) 0 else (total - seen).coerceAtLeast(0)

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide screens (tablets, phones in landscape) show the board and the table chat side by side.
        val wide = maxWidth >= 700.dp
        // On a tall tablet there is room to make the whole board bigger.
        val scale = if (wide && maxHeight >= 600.dp) 1.25f else 1f
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1.5f)) {
                            val d = androidx.compose.ui.platform.LocalDensity.current
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
                            ) { GameBoard(g, vm, ui) }
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("Table chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
                            ChatPanel(ui.tableChat, vm::sendTableChat)
                        }
                    }
                } else {
                    androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                        androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Game") })
                        androidx.compose.material3.Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(if (unread > 0) "Chat ($unread)" else "Chat") },
                        )
                    }
                    if (tab == 0) GameBoard(g, vm, ui) else ChatPanel(ui.tableChat, vm::sendTableChat)
                }
            }
        }
    }
}
@Composable
private fun GameBoard(live: GameState, vm: LobbyViewModel, ui: LobbyUiState) {
    val moves = ui.moves
    // Review mode: index into live.turnStops, or null for the live game. Everything below draws `g`.
    var reviewIdx by remember { mutableStateOf<Int?>(null) }
    val g = reviewIdx?.let { i -> live.turnStops.getOrNull(i)?.let(live::replayTo) } ?: live

    // Shared replay: the leader's segment drives everyone who is following; the leader's own steps are broadcast.
    val replay = ui.replay
    val amLeader = replay != null && replay.leader.equals(ui.me?.username, ignoreCase = true)
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(ui.sharedSegment, follow, live.turnStops.size) {
        val seg = ui.sharedSegment
        if (replay != null && !amLeader && follow && seg != null) {
            reviewIdx = if (seg >= live.turnStops.size - 1) null else seg
        }
    }
    val setReview: (Int?) -> Unit = { i ->
        reviewIdx = i
        if (replay != null) {
            if (amLeader) vm.sendReplaySegment(i ?: (live.turnStops.size - 1).coerceAtLeast(0)) else follow = false
        }
    }
    // Screen rectangles of cards, stacks and the discard button, used as animation start/end points.
    val bounds = remember { HashMap<String, Rect>() }
    val flights = remember { mutableStateListOf<Flight>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Drawn cards are hidden in the hand until their flight from the deck lands.
    val arriving = remember { mutableStateListOf<Int>() }
    val drawFlights = remember { mutableStateListOf<Triple<Int, Rect, Rect>>() }
    var pulseSuit by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pulseSuit) { if (pulseSuit != null) { kotlinx.coroutines.delay(380); pulseSuit = null } }
    val pendingDraws = ui.draws.map { it.order }
    // A played card changes its stack only once the flying card has landed: until then the stack keeps
    // showing the old number (held while the flight runs, and for plays not yet picked up below).
    val holdBack = remember { mutableStateMapOf<Int, Int>() }
    val pendingHold = if (reviewIdx == null) moves.filter { it.play }.associate { it.suit to it.rank - 1 } else emptyMap()
    val shownStacks = g.stacks.mapIndexed { i, v -> if (reviewIdx != null) v else holdBack[i] ?: pendingHold[i] ?: v }
    LaunchedEffect(ui.draws) {
        if (ui.draws.isEmpty()) return@LaunchedEffect
        val batch = ui.draws
        vm.consumeDraws(batch.last().id)
        val animate = AppSettings.animSpeed != AnimSpeed.Off && reviewIdx == null
        if (animate) {
            kotlinx.coroutines.delay((350 * AppSettings.animSpeed.factor).toLong()) // after the play/discard flight starts
            for (d in batch) {
                // The new card has to be laid out before we know where it lands.
                var tries = 0
                while (bounds["card:${d.order}"] == null && tries++ < 20) androidx.compose.runtime.withFrameNanos { }
                val deck = bounds["deck"]
                val slot = bounds["card:${d.order}"]
                if (deck != null && slot != null) {
                    arriving += d.order
                    drawFlights += Triple(d.order, deck, slot)
                }
            }
        }
    }
    LaunchedEffect(moves) {
        if (moves.isEmpty()) return@LaunchedEffect
        moves.forEach { m ->
            val from = bounds["card:${m.order}"]
            val to = if (m.play) bounds["stack:${m.suit}"] else bounds["discards"]
            if (AppSettings.animSpeed != AnimSpeed.Off && reviewIdx == null && from != null && to != null) {
                flights += Flight(m, from, to, flip = m.player == g.ourIndex && !g.spectating)
                if (m.play) holdBack[m.suit] = m.rank - 1
            }
        }
        vm.consumeMoves(moves.last().id)
    }
    var selected by remember { mutableStateOf<Int?>(null) }   // own card order awaiting play/discard
    var noteFor by remember { mutableStateOf<Int?>(null) }    // card order whose note is being edited
    var showDiscards by remember { mutableStateOf(false) }
    // A play/discard that needs a second "are you sure": (order, isPlay, explanation).
    var warn by remember { mutableStateOf<Triple<Int, Boolean, String>?>(null) }
    var infoOn by remember { mutableStateOf(setOf<Int>()) }   // players whose hand shows deduced knowledge
    val partners = g.othersInTurnOrder
    var cluePartner by remember(g.turn) { mutableIntStateOf(partners.first()) }
    var clueChoice by remember(g.turn) { mutableStateOf<ClueChoice?>(null) }
    // Cards the clue being composed would touch (only while composing on our turn).
    val highlighted: Set<Int> = if (!g.ourTurn) emptySet() else clueChoice?.let { c ->
        g.hands[cluePartner].filter { o ->
            val card = g.cards[o] ?: return@filter false
            when (c) {
                is ClueChoice.Color -> g.touchesColor(card.suit, c.color)
                is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
            }
        }.toSet()
    } ?: emptySet()
    fun toggleInfo(p: Int) { infoOn = if (p in infoOn) infoOn - p else infoOn + p }

    // Moves are only accepted on our turn: drop a pending play/discard prompt when the turn passes.
    LaunchedEffect(g.ourTurn) { if (!g.ourTurn) { selected = null; warn = null } }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier) {
            // Pinned: the discard button and the stacks are where cards fly to, so they must stay on screen.
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                    GameHeader(live, reviewIdx, g, replay, amLeader, follow, { follow = it }, setReview, bounds) { showDiscards = true }
                }
                Stacks(g, shownStacks, bounds, pulseSuit)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                g.othersInTurnOrder.forEach { p ->
                    val kn = if (p in infoOn) g.knowledgeOf(p) else null
                    Column(Modifier.turnGlow(g.phase == Phase.Playing && g.currentPlayer == p)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g.name(p) + if (g.currentPlayer == p && g.phase == Phase.Playing) "  (turn)" else "",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            InfoToggle("What they know", p in infoOn) { toggleInfo(p) }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(top = RAISE, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            userScrollEnabled = false,
                        ) {
                            items(g.hands[p], key = { it }) { o ->
                                g.cards[o]?.let { c ->
                                    val hl = o in highlighted
                                    Box(
                                        Modifier
                                            .animateItem(fadeOutSpec = null)
                                            .alpha(if (o in arriving || o in pendingDraws) 0f else 1f)
                                            .raiseIfClued(c.clued)
                                            .background(if (hl) Color(0xFFFFEB3B) else Color.Transparent, RoundedCornerShape(11.dp))
                                            .padding(3.dp)
                                            .onGloballyPositioned { bounds["card:$o"] = it.boundsInRoot() }
                                            .combinedClickable(onClick = {}, onLongClick = { noteFor = o }),
                                    ) {
                                        CardView(g, c, known = true, note = g.notes[o], knowledge = kn?.get(o))
                                        ClueBubbleFor(g, o)
                                    }
                                }
                            }
                        }
                    }
                }
                // Our own hand always shows what we know / can deduce (a spectator sees real cards instead).
                // After the game every card is public, so (as for partners) knowledge becomes a toggle.
                val myKn = when {
                    g.revealAll -> if (g.ourIndex in infoOn) g.knowledgeOf(g.ourIndex) else null
                    g.spectating -> null
                    else -> g.knowledgeOf(g.ourIndex)
                }
                Column(Modifier.turnGlow(g.phase == Phase.Playing && g.currentPlayer == g.ourIndex)) {
                    val me = if (g.spectating) g.name(g.ourIndex) else "You"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            me + if (g.ourTurn) "  - your turn" else "",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (g.ourTurn) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            modifier = Modifier.weight(1f),
                        )
                        if (g.revealAll && !g.spectating) InfoToggle("What I knew", g.ourIndex in infoOn) { toggleInfo(g.ourIndex) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(top = RAISE, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = false,
                    ) {
                        items(g.hands[g.ourIndex], key = { it }) { o ->
                            g.cards[o]?.let { c ->
                                Box(
                                    Modifier.animateItem(fadeOutSpec = null).alpha(if (o in arriving || o in pendingDraws) 0f else 1f).raiseIfClued(c.clued).onGloballyPositioned { bounds["card:$o"] = it.boundsInRoot() }.combinedClickable(
                                        onClick = { if (g.ourTurn) selected = o },
                                        onLongClick = { noteFor = o },
                                    ),
                                ) {
                                    CardView(g, c, known = g.spectating || g.revealAll, note = g.notes[o], knowledge = myKn?.get(o))
                                    ClueBubbleFor(g, o)
                                }
                            }
                        }
                    }
                }
                Column {
                    g.log.takeLast(5).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                }
                if (g.phase == Phase.Over && !g.readOnly) {
                    EndPanel(g, ui, onWatch = { reviewIdx = 0 }, vm = vm)
                }
                OutlinedButton(onClick = vm::leaveTable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (g.phase == Phase.Over) "Back to lobby" else "Leave game")
                }
            }
            if (g.ourTurn) CluePanel(g, vm, partners, cluePartner, { cluePartner = it }, clueChoice, { clueChoice = it })
        }
    }
    flights.toList().forEach { f ->
        key(f.move.id) {
            FlightCard(g, f, origin) {
                flights.remove(f)
                holdBack.remove(f.move.suit)
                if (f.move.play && !f.move.failed) pulseSuit = f.move.suit
            }
        }
    }
    drawFlights.toList().forEach { d ->
        key("draw${d.first}") {
            DrawFlightCard(d.second, d.third, origin) { drawFlights.remove(d); arriving.remove(d.first) }
        }
    }
    }

    if (showDiscards) DiscardDialog(g) { showDiscards = false }
    AnalysisDialog(ui, vm)
    noteFor?.let { order ->
        NoteDialog(g, order, onSave = { vm.setNote(order, it); noteFor = null }, onDismiss = { noteFor = null })
    }

    warn?.let { (order, isPlay, text) ->
        AlertDialog(
            onDismissRequest = { warn = null },
            title = { Text(if (isPlay) "Play this card?" else "Discard this card?") },
            text = { Text(text) },
            confirmButton = {
                Button(
                    onClick = { if (isPlay) vm.play(order) else vm.discard(order); warn = null },
                    enabled = g.ourTurn,
                ) { Text(if (isPlay) "Play anyway" else "Discard anyway") }
            },
            dismissButton = { TextButton(onClick = { warn = null }) { Text("Cancel") } },
        )
    }

    selected?.let { order ->
        // Cancel, Discard, Play: the safe way out is the first button and the one furthest from Play,
        // where a panicked tap lands. (Discard used to sit in the usual "cancel" spot.)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Card in slot ${g.hands[g.ourIndex].indexOf(order) + 1}") },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { selected = null },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Cancel", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val ids = g.knowledgeOf(g.ourIndex)[order]?.inferred.orEmpty()
                            val reason = when {
                                g.allCritical(ids) -> "${g.describe(ids)} is the last copy of a card you still need. Discard it anyway?"
                                g.allPlayableNow(ids) -> "${g.describe(ids)} can be played right now. Discard it anyway?"
                                else -> null
                            }
                            if (reason != null) warn = Triple(order, false, reason.replaceFirstChar { it.uppercase() })
                            else vm.discard(order)
                            selected = null
                        },
                        enabled = g.ourTurn && g.clues < 8,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Discard", maxLines = 1) }
                    Button(
                        onClick = {
                            val ids = g.knowledgeOf(g.ourIndex)[order]?.inferred.orEmpty()
                            if (ids.isNotEmpty() && !g.anyPlayableNow(ids)) {
                                warn = Triple(order, true, "${g.describe(ids).replaceFirstChar { it.uppercase() }} cannot be played right now. Play it anyway?")
                            } else vm.play(order)
                            selected = null
                        },
                        enabled = g.ourTurn,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Play", maxLines = 1) }
                }
            },
        )
    }}

/** A clue being composed: either a colour (suit index) or a rank. */
sealed interface ClueChoice {
    data class Color(val color: Int) : ClueChoice
    data class Rank(val rank: Int) : ClueChoice
}

/** Bottom bar, shown only on our turn: pick a partner, toggle one clue, and give it if it is valid. */
@Composable
private fun CluePanel(
    g: GameState,
    vm: LobbyViewModel,
    partners: List<Int>,
    partner: Int,
    onPartner: (Int) -> Unit,
    choice: ClueChoice?,
    onChoice: (ClueChoice?) -> Unit,
) {
    if (g.clues == 0) {
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Text(
                "You have no clues",
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        return
    }

    val hand = g.hands[partner].mapNotNull { g.cards[it] }
    fun touches(c: ClueChoice) = hand.any { card ->
        when (c) {
            is ClueChoice.Color -> g.touchesColor(card.suit, c.color)
            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
        }
    }
    // Does [c] touch anything in [p]'s hand? Used to carry a selected clue over to another partner.
    fun touchesIn(p: Int, c: ClueChoice) = g.hands[p].mapNotNull { g.cards[it] }.any { card ->
        when (c) {
            is ClueChoice.Color -> g.touchesColor(card.suit, c.color)
            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
        }
    }
    // A clue is valid if we have a clue token and it touches at least one card.
    val valid = g.clues > 0 && choice?.let(::touches) == true

    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (partners.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    partners.forEach { p ->
                        FilterChip(
                            selected = partner == p,
                            onClick = {
                                onPartner(p)
                                // Keep the chosen clue if it is still valid for the new partner.
                                if (choice != null && !touchesIn(p, choice)) onChoice(null)
                            },
                            label = { Text(g.name(p)) },
                        )
                    }
                }
            } else {
                Text("Clue ${g.name(partner)}", style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.clueColors.forEachIndexed { ci, name ->
                    val option = ClueChoice.Color(ci)
                    ClueToggle(
                        modifier = Modifier.weight(1f),
                        label = "",
                        pip = name,
                        color = suitColor(name),
                        selected = choice == option,
                        enabled = g.clues > 0 && touches(option),
                    ) { onChoice(option) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { r ->
                    val option = ClueChoice.Rank(r)
                    ClueToggle(
                        modifier = Modifier.weight(1f),
                        label = "$r",
                        color = Color(0xFF455A64),
                        selected = choice == option,
                        enabled = g.clues > 0 && touches(option),
                    ) { onChoice(option) }
                }
            }
            Button(
                onClick = {
                    when (val c = choice) {
                        is ClueChoice.Color -> vm.colorClue(partner, c.color)
                        is ClueChoice.Rank -> vm.rankClue(partner, c.rank)
                        null -> {}
                    }
                    onChoice(null)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val touchedCount = choice?.let { c ->
                    g.hands[partner].count { o ->
                        val card = g.cards[o]
                        card != null && when (c) {
                            is ClueChoice.Color -> g.touchesColor(card.suit, c.color)
                            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
                        }
                    }
                } ?: 0
                val what = when (choice) {
                    is ClueChoice.Color -> g.clueColors.getOrElse(choice.color) { "?" }
                    is ClueChoice.Rank -> "${choice.rank}"
                    null -> null
                }
                Text(
                    if (what == null) "Give clue (${g.clues} left)"
                    else "Tell ${g.name(partner)}: $what ($touchedCount card${if (touchedCount == 1) "" else "s"})",
                )
            }
        }
    }
}

@Composable
private fun ClueToggle(
    modifier: Modifier,
    label: String,
    color: Color,
    pip: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.25f)
            .background(color, shape)
            .border(if (selected) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color(0x55FFFFFF)), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pip != null) Pip(pip, 26.dp)
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}


@Composable
private fun Stacks(g: GameState, shown: List<Int>, bounds: MutableMap<String, Rect>, pulse: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.suits.forEachIndexed { i, s ->
            Box(
                Modifier.weight(1f).height(44.dp).onGloballyPositioned { bounds["stack:$i"] = it.boundsInRoot() }
                    .graphicsLayer {
                        val s = if (pulse == i) 1.18f else 1f
                        scaleX = s; scaleY = s
                    }.background(suitBrush(s), RoundedCornerShape(6.dp), alpha = if (shown[i] > 0) 1f else 0.25f),
                contentAlignment = Alignment.Center,
            ) {
                Pip(s, 16.dp, Modifier.align(Alignment.TopStart).padding(2.dp))
                OutlinedText(if (shown[i] > 0) "${shown[i]}" else "", 22.sp)
            }
        }
    }
}

/**
 * [known] = we can see the real identity (other players' hands). Otherwise we show only what
 * the clues have narrowed it down to.
 */
@Composable
private fun CardView(g: GameState, c: Card, known: Boolean, note: String? = null, knowledge: Knowledge? = null) {
    if (knowledge != null) {
        KnowledgeCardView(g, c, knowledge, note)
        return
    }
    val dead = g.isDead(c)
    val realId = if (known && c.suit >= 0 && c.rank >= 0) setOf(c.suit to c.rank) else emptySet()
    val knownSuit = if (known) c.suit else c.possibleSuits.singleOrNull() ?: -1
    val knownRank = if (known) c.rank else c.possibleRanks.singleOrNull() ?: -1
    val bgBrush: androidx.compose.ui.graphics.Brush = when {
        knownSuit < 0 -> androidx.compose.ui.graphics.SolidColor(Color(0xFF424242))
        dead -> androidx.compose.ui.graphics.SolidColor(dull(suitColor(g.suits[knownSuit])))
        else -> suitBrush(g.suits[knownSuit])
    }
    val border = if (c.clued) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color(0xFF9E9E9E))
    Box(
        Modifier.width(56.dp).height(78.dp)
            .background(bgBrush, RoundedCornerShape(8.dp)).border(border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedText(
            if (knownRank >= 0) "$knownRank" else "", 30.sp,
            fill = if (dead) Color(0xFFBDBDBD) else Color.White,
        )
        if (!known && knownRank < 0 && c.possibleRanks.size < 5) {
            Text(
                c.possibleRanks.sorted().joinToString(""),
                fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
        if (!note.isNullOrBlank()) {
            Text(
                note.take(6), fontSize = 10.sp, color = Color.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                    .background(Color(0xFFFFEB3B), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
            )
        }
        if (knownSuit >= 0) Pip(g.suits[knownSuit], 16.dp, Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        CardBadges(g.allCritical(realId), g.allDead(realId), Modifier.align(Alignment.BottomStart))
        if (known && g.isPlayable(c)) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(Color.White, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun DiscardDialog(g: GameState, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Discards (${g.discards.size})") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Cards left") })
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 1) Text(
                    "Big number: copies not yet played or discarded. Small: of those, how many are not in anyone's hand that you can see.",
                    style = MaterialTheme.typography.bodySmall,
                )
                g.suits.forEachIndexed { s, name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.width(24.dp).height(if (tab == 0) 34.dp else 48.dp).background(suitBrush(name), RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Pip(name, 18.dp) }
                        if (tab == 0) {
                            val ranks = g.discards.mapNotNull { g.cards[it] }.filter { it.suit == s }.map { it.rank }.sorted()
                            if (ranks.isEmpty()) Text("-", style = MaterialTheme.typography.bodySmall)
                            ranks.forEach { r ->
                                Box(
                                    Modifier.width(28.dp).height(34.dp).background(suitBrush(name), RoundedCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { OutlinedText("$r", 16.sp) }
                            }
                        } else {
                            (1..5).forEach { r ->
                                val left = g.remaining(s, r)
                                Column(
                                    Modifier.width(40.dp).height(48.dp)
                                        .background(suitBrush(name), RoundedCornerShape(6.dp), alpha = if (left > 0) 1f else 0.25f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    OutlinedText("$left", 18.sp)
                                    OutlinedText("$r\u00B7${g.unseen(s, r).coerceAtLeast(0)}", 10.sp, fontWeight = FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NoteDialog(g: GameState, order: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(order) { mutableStateOf(g.notes[order] ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("e.g. r5, chop, f") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InfoToggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.height(30.dp),
    )
}

/** Draws a red X over the content when [on]; used for possibilities ruled out by deduction. */
private fun Modifier.xOut(on: Boolean): Modifier = if (!on) this else this.drawWithContent {
    drawContent()
    val stroke = 2.dp.toPx()
    val red = Color(0xFFFF1744)
    drawLine(red, Offset(0f, 0f), Offset(size.width, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(red, Offset(size.width, 0f), Offset(0f, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
}

/**
 * Card face showing what is known about the card instead of its identity.
 *  - Middle: the possible suits as symbols on a ring (one large symbol once the suit is certain).
 *  - Bottom: the possible ranks (one large number once the rank is certain).
 *  - A red X over a symbol or number means only deduction (visible cards) rules it out, not a clue.
 *  - A cyan border means deduction alone pins the card down.
 *  - Top-left: note tag. Top-right: critical "!" or discardable bin.
 */
@Composable
private fun KnowledgeCardView(g: GameState, c: Card, k: Knowledge, note: String?) {
    val solvedSuit = k.inferredSuits.singleOrNull()
    val solvedRank = k.inferredRanks.singleOrNull()
    val discardable = g.allDead(k.inferred)
    val bg: androidx.compose.ui.graphics.Brush = when {
        solvedSuit == null -> androidx.compose.ui.graphics.SolidColor(Color(0xFF37474F))
        discardable -> androidx.compose.ui.graphics.SolidColor(dull(suitColor(g.suits[solvedSuit])))
        else -> suitBrush(g.suits[solvedSuit])
    }
    val shape = RoundedCornerShape(8.dp)
    val border = when {
        k.solvedByInference -> BorderStroke(3.dp, Color(0xFF80DEEA))
        c.clued -> BorderStroke(3.dp, Color.White)
        else -> BorderStroke(1.dp, Color(0xFF9E9E9E))
    }
    Box(Modifier.width(56.dp).height(78.dp).background(bg, shape).border(border, shape)) {
        // --- suits: a ring of symbols in the middle of the card
        val cx = 28f
        val cy = 34f
        if (solvedSuit != null) {
            val s = 32f
            Pip(g.suits[solvedSuit], s.dp, Modifier.offset((cx - s / 2).dp, (cy - s / 2).dp))
        } else {
            val suits = k.directSuits.toList()
            val n = suits.size
            val size = when {
                n <= 3 -> 21f
                n <= 5 -> 19f
                else -> 17f
            }
            val radius = when (n) { 1 -> 0f; 2 -> 13f; 3 -> 14f; 4 -> 15f; 5 -> 16f; else -> 17f }
            suits.forEachIndexed { i, s ->
                // 2 suits sit left/right; 3+ start at the top and go clockwise.
                val angle = when {
                    n == 2 -> if (i == 0) Math.PI else 0.0
                    else -> -Math.PI / 2 + 2 * Math.PI * i / n
                }
                val x = cx + radius * kotlin.math.cos(angle).toFloat() - size / 2
                val y = cy + radius * kotlin.math.sin(angle).toFloat() - size / 2
                val circle = androidx.compose.foundation.shape.CircleShape
                Box(
                    Modifier.offset(x.dp, y.dp).size(size.dp)
                        .background(suitBrush(g.suits[s]), circle)
                        .border(1.dp, Color.White, circle)
                        .xOut(s !in k.inferredSuits),
                    contentAlignment = Alignment.Center,
                ) { Pip(g.suits[s], (size - 5f).dp) }
            }
        }
        // --- ranks: along the bottom
        if (solvedRank != null) {
            OutlinedText(
                "$solvedRank", 26.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        } else {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) {
                k.directRanks.forEach { r ->
                    Box(Modifier.width(10.dp).height(18.dp).xOut(r !in k.inferredRanks), contentAlignment = Alignment.Center) {
                        OutlinedText("$r", 13.sp)
                    }
                }
            }
        }
        // --- corners
        CardBadges(g.allCritical(k.inferred), discardable, Modifier.align(Alignment.TopEnd))
        if (!note.isNullOrBlank()) {
            Text(
                note.take(4), fontSize = 9.sp, color = Color.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                    .background(Color(0xFFFFEB3B), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
            )
        }
    }
}
/**
 * Corner markers on a card face:
 *  "!" (amber)   critical: the last copy of a card the stacks still need.
 *  bin (grey)    discardable: it is already played or can never be played.
 */
@Composable
private fun CardBadges(critical: Boolean, discardable: Boolean, modifier: Modifier) {
    if (!critical && !discardable) return
    val shape = androidx.compose.foundation.shape.CircleShape
    Row(modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (critical) {
            Box(Modifier.size(16.dp).background(Color(0xFFFFB300), shape), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
        }
        if (discardable) {
            Box(Modifier.size(16.dp).background(Color(0xFF9E9E9E), shape), contentAlignment = Alignment.Center) {
                BinIcon(Modifier.size(11.dp))
            }
        }
    }
}
private val RAISE = 10.dp

/** Clued cards sit slightly higher than the rest of the hand, which makes the chop easy to spot. */
@Composable
private fun Modifier.raiseIfClued(clued: Boolean): Modifier {
    val lift by androidx.compose.animation.core.animateDpAsState(if (clued) -RAISE else 0.dp, label = "raise")
    return this.offset(y = lift)
}

/**
 * Bubble under a card that the latest clue touched: a colour dot or a rank number. It is shown while
 * the clue is current: from when it is given until the turn after it ends. Because it is derived
 * from the game state, it also appears when stepping through earlier turns in review.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClueBubbleFor(g: GameState, order: Int) {
    val mark = g.lastClue ?: return
    if (g.turn > mark.turn + 1 || order !in mark.orders) return
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .offset(y = 11.dp)
            .size(24.dp)
            .background(
                if (mark.color != null) suitBrush(mark.color) else androidx.compose.ui.graphics.SolidColor(Color(0xFF455A64)),
                shape,
            )
            .border(2.dp, Color.White, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (mark.rank != null) {
            Text("${mark.rank}", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        if (mark.color != null) Pip(mark.color, 16.dp)
    }
}
/** A small black waste bin, drawn directly so its colour is not decided by the emoji font. */
@Composable
private fun BinIcon(modifier: Modifier, color: Color = Color.Black) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val black = color
        // lid and handle
        drawRect(black, topLeft = Offset(0f, h * 0.18f), size = androidx.compose.ui.geometry.Size(w, h * 0.12f))
        drawRect(black, topLeft = Offset(w * 0.35f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.18f))
        // body, slightly tapered
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.36f)
            lineTo(w * 0.88f, h * 0.36f)
            lineTo(w * 0.78f, h)
            lineTo(w * 0.22f, h)
            close()
        }
        drawPath(body, black)
    }
}

/** Shown when the game is over: result, share link, play again, and a way into the shared replay. */
@Composable
private fun EndPanel(g: GameState, ui: LobbyUiState, onWatch: () -> Unit, vm: LobbyViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val replay = ui.replay
    val link = replay?.takeIf { it.databaseID > 0 }?.let { "https://hanab.live/shared-replay/${it.databaseID}" }
    var showAnalyze by remember { mutableStateOf(false) }
    val onlineBots = ui.users.values.map { it.name }.filter { live.hanab.client.BotStore.isBot(it) }.sortedBy { it.lowercase() }
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.endText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Score ${g.finalScore} / ${g.maxScore}", style = MaterialTheme.typography.titleMedium)
            Text(g.playerNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onWatch) { Text("Watch replay") }
                if (!g.spectating) OutlinedButton(onClick = vm::restartTable) { Text("Play again") }
            }
            if (link != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Share replay"))
                    }) { Text("Share link") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)),
                        )
                    }) { Text("Open on web") }
                }
                // Your bots can review the finished game (/analyze <replay id> <conventions>).
                if (live.hanab.client.BotStore.bots.isNotEmpty()) {
                    OutlinedButton(onClick = { showAnalyze = true }) { Text("Analyze with a bot") }
                }
            }
        }
    }
    if (showAnalyze && replay != null) {
        AnalyzeDialog(onlineBots, { bot, conv -> vm.analyzeWithBot(bot, replay.databaseID, conv) }, { showAnalyze = false })
    }
}


/**
 * Lights up whoever's turn it is: an amber outline and a faint amber wash behind their hand.
 * The padding is always applied so the layout does not jump when the turn moves.
 */
@Composable
private fun Modifier.turnGlow(active: Boolean): Modifier {
    val amber = Color(0xFFFFB300)
    val line by androidx.compose.animation.animateColorAsState(if (active) amber else Color.Transparent, label = "turnLine")
    val wash by androidx.compose.animation.animateColorAsState(if (active) amber.copy(alpha = 0.14f) else Color.Transparent, label = "turnWash")
    val shape = RoundedCornerShape(12.dp)
    return this.background(wash, shape).border(2.dp, line, shape).padding(6.dp)
}

/**
 * One compact box with everything about the game state, and the playback arrows spanning both rows:
 *
 *     [<]          turn info (live / reviewing + last move)          [>]
 *     [<]   clues   strikes   deck   score   discards               [>]
 *
 * [<] steps back through earlier turns, [>] forward (past the last one it returns to live play).
 */
@Composable
private fun GameHeader(
    live: GameState,
    idx: Int?,
    shown: GameState,
    replay: live.hanab.client.ReplayInfo?,
    amLeader: Boolean,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    onChange: (Int?) -> Unit,
    bounds: MutableMap<String, Rect>,
    onDiscards: () -> Unit,
) {
    val g = shown
    val n = live.turnStops.size
    val amber = Color(0xFFFFB300)
    val backEnabled = n >= 2 && (idx == null || idx > 0)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange(if (idx == null) n - 2 else idx - 1) },
            enabled = backEnabled,
            modifier = Modifier.fillMaxHeight().width(40.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("\u25C0") }

        Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ---- row 1: turn info
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val text = when {
                    idx != null -> "Turn ${g.turn + 1}/${live.turn + 1} \u00B7 " + (g.log.lastOrNull()?.text ?: "start")
                    g.phase == Phase.Over -> "Game over"
                    g.ourTurn -> "Turn ${g.turn + 1} \u00B7 your turn"
                    else -> "Turn ${g.turn + 1} \u00B7 ${g.name(g.currentPlayer)}"
                }
                Text(
                    (if (replay != null && idx == null) "Replay \u00B7 " else "") + text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (idx != null) amber else Color.Unspecified,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (replay != null && !amLeader) {
                    Text(
                        if (follow) "following \u2713" else "follow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onFollow(!follow) }.padding(horizontal = 6.dp),
                    )
                }
                if (idx != null) {
                    Text(
                        "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onChange(null) }.padding(start = 6.dp),
                    )
                }
            }
            // ---- row 2: clues, strikes, deck, score, discards
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // clues: a token with the number
                Box(
                    Modifier.size(24.dp).background(if (g.clues == 0) Color(0xFFD32F2F) else Color(0xFF1976D2), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${g.clues}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                // strikes: three boxes, filled when taken
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { i ->
                        Box(
                            Modifier.size(14.dp)
                                .background(if (i < g.strikes) Color(0xFFD32F2F) else Color.Transparent, RoundedCornerShape(3.dp))
                                .border(1.5.dp, if (i < g.strikes) Color(0xFFD32F2F) else Color(0xFF9E9E9E), RoundedCornerShape(3.dp)),
                        )
                    }
                }
                // deck: a little card with the count
                Box(
                    Modifier.width(22.dp).height(28.dp)
                        .onGloballyPositioned { bounds["deck"] = it.boundsInRoot() }
                        .background(Color(0xFF455A64), RoundedCornerShape(4.dp))
                        .border(1.5.dp, Color(0xFFB0BEC5), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${g.deckSize}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                Text("${g.finalScore}/${g.maxScore}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                // discard pile: a bin with the count
                OutlinedButton(
                    onClick = onDiscards,
                    modifier = Modifier.height(30.dp).onGloballyPositioned { bounds["discards"] = it.boundsInRoot() },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    BinIcon(Modifier.size(13.dp), MaterialTheme.colorScheme.onSurface)
                    Text(" ${g.discards.size}", fontSize = 13.sp)
                }
            }
        }

        OutlinedButton(
            onClick = { onChange(if (idx!! + 1 >= n - 1) null else idx + 1) },
            enabled = idx != null,
            modifier = Modifier.fillMaxHeight().width(40.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("\u25B6") }
    }
}