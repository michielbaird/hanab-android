package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import live.hanab.client.AnalysisComment
import live.hanab.client.AnalysisUi
import live.hanab.client.LobbyViewModel

/**
 * The bot, on this phone, goes through a finished game and says which moves it would have played differently.
 * It works on games from the website and on games played offline.
 */
@Composable
fun AnalysisDialog(state: AnalysisUi?, vm: LobbyViewModel, inGame: Boolean) {
    when (state) {
        null -> Unit
        is AnalysisUi.Setup -> SetupDialog(state, vm)
        is AnalysisUi.Running -> RunningDialog(state, vm)
        // Over a game the result is the box under the hands, so it needs no dialog.
        is AnalysisUi.Done -> if (!inGame) AnalysisResultDialog(
            state,
            onWatch = { vm.watchAnalysed(state.request) },
            onAgain = { vm.analyzeAgain(state.request) },
            onClose = vm::closeAnalysis,
        )
        is AnalysisUi.Failed -> AlertDialog(
            onDismissRequest = vm::closeAnalysis,
            title = { Text("Analysis") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = vm::closeAnalysis) { Text("Close") } },
        )
    }
}

@Composable
private fun SetupDialog(state: AnalysisUi.Setup, vm: LobbyViewModel) {
    var convention by remember { mutableStateOf(state.request.convention) }
    AlertDialog(
        onDismissRequest = vm::closeAnalysis,
        title = { Text("Analyze this game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.request.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "The bot replays the game and tells you every move it would have played differently. " +
                        "It runs on your phone and can take a minute or more.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Conventions", style = MaterialTheme.typography.labelLarge)
                ConventionPicker(convention) { convention = it }
            }
        },
        confirmButton = { Button(onClick = { vm.startAnalysis(convention) }) { Text("Analyze") } },
        dismissButton = { TextButton(onClick = vm::closeAnalysis) { Text("Cancel") } },
    )
}

@Composable
private fun RunningDialog(state: AnalysisUi.Running, vm: LobbyViewModel) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            seconds = (System.currentTimeMillis() - state.startedAt) / 1000
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = vm::closeAnalysis,
        title = { Text("Analyzing...") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Column {
                    Text("${state.request.title} with ${state.convention}", style = MaterialTheme.typography.bodyMedium)
                    Text("${seconds}s so far", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeAnalysis) { Text("Cancel") } },
    )
}

/** The whole list of what the bot would have done differently; [onWatch] (if given) opens the game's replay. */
@Composable
internal fun AnalysisResultDialog(state: AnalysisUi.Done, onWatch: (() -> Unit)? = null, onAgain: () -> Unit, onClose: () -> Unit) {
    var onlyMistakes by remember { mutableStateOf(false) }
    val shown = if (onlyMistakes) state.comments.filter { it.mistake } else state.comments
    val mistakes = state.comments.count { it.mistake }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Analysis with ${state.convention}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.comments.isEmpty()) "The bot would have played the same moves."
                    else "${state.comments.size} moves differ, $mistakes of them clear mistakes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mistakes > 0 && mistakes < state.comments.size) {
                    FilterChip(selected = onlyMistakes, onClick = { onlyMistakes = !onlyMistakes }, label = { Text("Mistakes only") })
                }
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shown.forEach { Comment(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAgain) { Text("Analyze again") }
                onWatch?.let { TextButton(onClick = it) { Text("Watch") } }
            }
        },
    )
}

@Composable
private fun Comment(c: AnalysisComment) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            "Turn ${c.turn} · ${c.player}" + if (c.mistake) " · mistake" else "",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (c.mistake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(c.text, style = MaterialTheme.typography.bodyMedium)
    }
}
