package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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

/** Choose who to play against offline: the table size, the bots' conventions and the variant. */
@Composable
fun OfflineDialog(onStart: (players: Int, convention: String, variant: String, fast: Boolean) -> Unit, onDismiss: () -> Unit) {
    var players by remember { mutableIntStateOf(3) }
    var convention by remember { mutableStateOf(BotStore.convention) }
    var variant by remember { mutableStateOf("No Variant") }
    var fast by remember { mutableStateOf(BotStore.fast) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play offline") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text("Variant", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("No Variant", "6 Suits").forEach { v ->
                        FilterChip(selected = variant == v, onClick = { variant = v }, label = { Text(v) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(players, convention, variant, fast); onDismiss() }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
