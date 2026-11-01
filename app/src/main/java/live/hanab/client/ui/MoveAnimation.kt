package live.hanab.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.hanab.client.game.GameState
import live.hanab.client.game.MoveEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** One card in flight: from where it sat in a hand to the stack (play) or the discard button. */
data class Flight(val move: MoveEvent, val from: Rect, val to: Rect, val flip: Boolean)

private const val CARD_W = 56
private const val CARD_H = 78
private const val DURATION_MS = 1300

/**
 * Timeline (0..1):
 *  0.00-0.15 lift the card up
 *  0.10-0.50 flip it face up (only for cards we could not see: our own)
 *  0.15-0.55 slide to the target while shrinking
 *  0.55-0.80 hold so the identity can be read
 *  0.80-1.00 shrink away into the target
 */
@Composable
fun FlightCard(g: GameState, flight: Flight, origin: Offset, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween((DURATION_MS * live.hanab.client.AppSettings.animSpeed.factor).toInt().coerceAtLeast(1), easing = LinearEasing))
        onDone()
    }
    val t = progress.value
    val density = LocalDensity.current

    val lift = (t / 0.15f).coerceIn(0f, 1f)
    val slide = FastOutSlowInEasing.transform(((t - 0.15f) / 0.40f).coerceIn(0f, 1f))
    val exit = ((t - 0.80f) / 0.20f).coerceIn(0f, 1f)
    val flipT = ((t - 0.10f) / 0.40f).coerceIn(0f, 1f)

    val start = flight.from.center
    val end = flight.to.center
    val liftPx = with(density) { 28.dp.toPx() }
    val cx = start.x + (end.x - start.x) * slide
    val cy = start.y + (end.y - start.y) * slide - liftPx * lift * (1f - slide)

    // The card it leaves may have been scaled to fit its hand, so start from that size.
    val unit = (flight.from.width / with(density) { CARD_W.dp.toPx() }).coerceIn(0.5f, 1.5f)
    val scale = (1f + 0.2f * lift - 0.45f * slide) * (1f - 0.7f * exit) * unit
    val faceUp = !flight.flip || flipT >= 0.5f
    val squash = if (flight.flip) abs(cos(PI * flipT)).toFloat().coerceAtLeast(0.02f) else 1f

    val w = with(density) { CARD_W.dp.toPx() }
    val h = with(density) { CARD_H.dp.toPx() }
    val m = flight.move
    val suitName = g.suits.getOrNull(m.suit)

    // The face is drawn like the cards in the hands: hanab.live's card front, unless the simplified display is on.
    val hanabFace = faceUp && suitName != null && !live.hanab.client.AppSettings.simplifiedCards
    Box(
        Modifier
            .offset { IntOffset((cx - origin.x - w / 2).roundToInt(), (cy - origin.y - h / 2).roundToInt()) }
            .size(CARD_W.dp, CARD_H.dp)
            .graphicsLayer {
                scaleX = scale * squash
                scaleY = scale
                alpha = 1f - exit
            }
            .then(
                if (hanabFace) Modifier else Modifier.background(
                    if (faceUp && suitName != null) suitBrush(suitName) else androidx.compose.ui.graphics.SolidColor(Color(0xFF424242)),
                    RoundedCornerShape(8.dp),
                ),
            )
            .border(
                BorderStroke(3.dp, if (m.failed) Color(0xFFFF5252) else Color.White),
                RoundedCornerShape(if (hanabFace) 6.dp else 8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hanabFace) {
            HanabCardFace(suitName, m.rank, false, Modifier.fillMaxSize())
        } else {
            if (faceUp && suitName != null) Pip(suitName, 16.dp, Modifier.align(Alignment.TopCenter).offset(y = 4.dp))
            OutlinedText(if (faceUp && m.rank > 0) "${m.rank}" else "?", 30.sp)
        }
    }
}

/** A face-down card flying from the deck to its place in a hand. */
@Composable
fun DrawFlightCard(from: Rect, to: Rect, origin: Offset, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val ms = (450 * live.hanab.client.AppSettings.animSpeed.factor).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
        onDone()
    }
    val t = progress.value
    val density = LocalDensity.current
    val w = with(density) { CARD_W.dp.toPx() }
    val h = with(density) { CARD_H.dp.toPx() }
    val cx = from.center.x + (to.center.x - from.center.x) * t
    val cy = from.center.y + (to.center.y - from.center.y) * t
    val unit = (to.width / w).coerceIn(0.5f, 1.5f)
    val scale = (0.55f + 0.45f * t) * unit
    Box(
        Modifier
            .offset { IntOffset((cx - origin.x - w / 2).roundToInt(), (cy - origin.y - h / 2).roundToInt()) }
            .size(CARD_W.dp, CARD_H.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Color(0xFF424242), RoundedCornerShape(8.dp))
            .border(BorderStroke(2.dp, Color(0xFF9E9E9E)), RoundedCornerShape(8.dp)),
    )
}