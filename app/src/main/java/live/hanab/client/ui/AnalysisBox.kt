package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import live.hanab.client.AnalysisComment
import live.hanab.client.LobbyUiState

/**
 * The website's id (or, offline, ours) of the game on screen, for the analysis. For a game of the website it is the
 * replay id, which the server gives for replays as well; for an offline game it is the one just kept or the saved
 * one being replayed. Null if it cannot be told.
 */
internal fun analysisGameId(ui: LobbyUiState): Long? =
    if (ui.offline) {
        ui.lastOfflineGame ?: ui.offlineReplayId
    } else {
        (ui.replay?.databaseID ?: 0).takeIf { it > 0 }?.toLong() ?: ui.replayDatabaseID.takeIf { it > 0 }?.toLong()
    }

/**
 * The bot's analysis of the game being watched: the move it would have played differently, with arrows to go to the
 * previous and next one. [position] is the 1-based place of [comment] in the [total]; null before an arrow is used.
 */
@Composable
internal fun AnalysisBox(
    comment: AnalysisComment?,
    position: Int?,
    total: Int,
    mistakesOnly: Boolean,
    canFilter: Boolean,
    onMistakesOnly: (Boolean) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onList: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), tonalElevation = 3.dp, shadowElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrev, enabled = total > 0) { Text("◀") }
                Column(Modifier.weight(1f).heightIn(min = 56.dp, max = 96.dp).verticalScroll(rememberScrollState())) {
                    if (comment == null) {
                        Text("Bot analysis", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (total == 0) "The bot would have played the same moves." else "$total moves differ. Use the arrows to go through them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Turn ${comment.turn} · ${comment.player}" + if (comment.mistake) " · mistake" else "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (comment.mistake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(comment.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onNext, enabled = total > 0) { Text("▶") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (position != null) "$position of $total" else "$total",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
                if (canFilter) FilterChip(selected = mistakesOnly, onClick = { onMistakesOnly(!mistakesOnly) }, label = { Text("Mistakes only") })
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onList) { Text("List") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
