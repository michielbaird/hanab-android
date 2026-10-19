package live.hanab.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One faint firework: position and size as fractions of the screen width / height. */
private class Burst(val x: Float, val y: Float, val radius: Float, val color: Color, val rotation: Float, val rays: Int)

// Muted versions of the Hanabi suit colours; low alpha on top of a dark sky keeps them in the background.
private val Red = Color(0xFFB4504F)
private val Yellow = Color(0xFFB59A4A)
private val Green = Color(0xFF4F9A6E)
private val Blue = Color(0xFF4F76B4)
private val Purple = Color(0xFF8460B4)

// Fixed positions (not random) so the picture is the same on every launch and rotation.
private val bursts = listOf(
    Burst(0.16f, 0.10f, 0.46f, Red, 0.10f, 16),
    Burst(0.90f, 0.26f, 0.36f, Blue, 0.30f, 14),
    Burst(0.34f, 0.50f, 0.24f, Yellow, 0.00f, 12),
    Burst(0.84f, 0.70f, 0.46f, Purple, 0.20f, 18),
    Burst(0.12f, 0.88f, 0.38f, Green, 0.05f, 14),
    Burst(0.58f, 0.90f, 0.20f, Red, 0.25f, 12),
)

/**
 * A static night sky with a few faint, muted firework bursts. It never moves, so it neither
 * distracts from the game nor costs battery; Settings can switch it off.
 */
@Composable
fun FireworksBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF04060D), Color(0xFF0A0F1F))))
        // Burst radii are fractions of the width, so they look alike on tall phones and on tablets.
        for (b in bursts) drawBurst(b)
    }
}

private fun DrawScope.drawBurst(b: Burst) {
    val center = Offset(b.x * size.width, b.y * size.height)
    val r = b.radius * size.width
    // soft glow
    drawCircle(
        Brush.radialGradient(listOf(b.color.copy(alpha = 0.07f), Color.Transparent), center = center, radius = r),
        radius = r, center = center,
    )
    val stroke = 2.dp.toPx()
    for (i in 0 until b.rays) {
        val a = b.rotation + 2 * PI * i / b.rays
        val long = i % 2 == 0
        val from = r * 0.26f
        val to = r * (if (long) 0.92f else 0.62f)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            b.color.copy(alpha = 0.17f),
            Offset(center.x + dx * from, center.y + dy * from),
            Offset(center.x + dx * to, center.y + dy * to),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        drawCircle(b.color.copy(alpha = 0.22f), radius = if (long) 3.dp.toPx() else 2.dp.toPx(),
            center = Offset(center.x + dx * (to + r * 0.07f), center.y + dy * (to + r * 0.07f)))
    }
}
