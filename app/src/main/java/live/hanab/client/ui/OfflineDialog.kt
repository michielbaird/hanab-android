@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import live.hanab.client.BotStore
import live.hanab.client.game.Variants

/** Choose who to play against offline: the table size, the bots' conventions and the variant. */
@Composable
fun OfflineDialog(
    onStart: (players: Int, convention: String, variant: String, fast: Boolean, seed: String) -> Unit,
    onStats: () -> Unit,
    onDismiss: () -> Unit,
) {
    var players by remember { mutableIntStateOf(3) }
    var convention by remember { mutableStateOf(BotStore.convention) }
    var variant by remember { mutableStateOf("No Variant") }
    var fast by remember { mutableStateOf(BotStore.fast) }
    var seed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play offline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You and bots, all on this phone. No account or connection needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Players (you and ${players - 1} bot${if (players == 2) "" else "s"})", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (2..4).forEach { n ->
                        FilterChip(selected = players == n, onClick = { players = n }, label = { Text("$n") })
                    }
                }
                Text("Conventions the bots play", style = MaterialTheme.typography.labelLarge)
                ConventionPicker(convention) { convention = it }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = fast, onCheckedChange = { fast = it })
                    Text("Fast mode (no thinking delay)")
                }
                OutlinedTextField(
                    value = seed, onValueChange = { seed = it }, singleLine = true,
                    label = { Text("Seed (optional)") },
                    placeholder = { Text("next one you have not played") },
                    supportingText = { Text("Same cards as on hanab.live. Paste a whole seed like p3v0s258 (it sets the players and variant) or just the part after the s.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(if (variant in Variants.quick) "Variant" else "Variant: $variant", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Variants.quick.forEach { v ->
                        FilterChip(selected = variant == v, onClick = { variant = v }, label = { Text(v) })
                    }
                }
                var query by remember { mutableStateOf("") }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search other variants") }, singleLine = true)
                if (query.length >= 2) {
                    val matches = remember(query) { Variants.botSupported().filter { it.contains(query, ignoreCase = true) }.take(8) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        matches.forEach { v ->
                            FilterChip(selected = variant == v, onClick = { variant = v; query = "" }, label = { Text(v) })
                        }
                    }
                    if (matches.isEmpty()) Text("No variant matches that the bots can play.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(players, convention, variant, fast, seed); onDismiss() }) { Text("Start") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onStats(); onDismiss() }) { Text("Offline stats") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
