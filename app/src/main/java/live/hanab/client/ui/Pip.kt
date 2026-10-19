package live.hanab.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.SVG
import live.hanab.client.game.Variants

/** Whether suit symbols are drawn (helps colour-blind players). Persisted by the view model. */
object PipSettings {
    val enabled: Boolean get() = live.hanab.client.AppSettings.pips
}

/** Renders hanab.live's suit symbols (SVG, from the website's repository) to bitmaps once. */
object PipStore {
    private lateinit var context: Context
    private val cache = HashMap<String, ImageBitmap?>()
    private const val PX = 96

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun get(suit: String): ImageBitmap? = cache.getOrPut(suit) {
        val file = Variants.pipFile(suit) ?: return@getOrPut null
        try {
            context.assets.open(file).use { stream ->
                val svg = SVG.getFromInputStream(stream)
                svg.documentWidth = PX.toFloat()
                svg.documentHeight = PX.toFloat()
                svg.documentPreserveAspectRatio = PreserveAspectRatio.LETTERBOX
                val bmp = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(AndroidCanvas(bmp))
                bmp.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** The symbol's fill: the suit colour, a bit darker so it stands out on its own background. */
private fun pipColor(suit: String): Color {
    val base = suitColor(suit)
    // Very dark suits (Black) would vanish into their outline, so lighten those instead.
    return if (base.luminance() < 0.12f) lerp(base, Color.White, 0.45f) else lerp(base, Color.Black, 0.35f)
}

/**
 * A suit symbol in a darker shade of its suit colour with a black outline, so it reads on any
 * background (including the suit's own colour). Draws nothing if symbols are off or the suit has none.
 */
@Composable
fun Pip(suit: String, size: Dp, modifier: Modifier = Modifier) {
    if (!PipSettings.enabled) return
    val bmp = remember(suit) { PipStore.get(suit) } ?: return
    val fill = remember(suit) { pipColor(suit) }
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val outline = (size.toPx() * 0.07f).coerceAtLeast(1f)
        val inner = (this.size.width - 2 * outline).toInt().coerceAtLeast(1)
        val dst = androidx.compose.ui.unit.IntSize(inner, inner)
        val black = ColorFilter.tint(Color.Black, BlendMode.SrcIn)
        // Black copy shifted in 8 directions forms the outline; the coloured symbol goes on top.
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            drawImage(
                bmp,
                dstOffset = androidx.compose.ui.unit.IntOffset((outline + dx * outline).toInt(), (outline + dy * outline).toInt()),
                dstSize = dst, colorFilter = black,
            )
        }
        drawImage(
            bmp,
            dstOffset = androidx.compose.ui.unit.IntOffset(outline.toInt(), outline.toInt()),
            dstSize = dst, colorFilter = ColorFilter.tint(fill, BlendMode.SrcIn),
        )
    }
}