package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import live.hanab.client.BotStore
import live.hanab.client.LobbyUiState
import live.hanab.client.LobbyViewModel

@Composable
fun PregameScreen(ui: LobbyUiState, vm: LobbyViewModel) {
    val table = ui.tables[ui.joinedTableID]
    val isOwner = table != null && table.ownerID == ui.me?.userID
    var showInvite by remember { mutableStateOf(false) }
    var setAll by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    val players = table?.players.orEmpty()
    val botsAtTable = players.filter { BotStore.isBot(it) }
    // Your marked bots that are online and not yet at this table.
    val inviteable = ui.users.values
        .filter { u -> BotStore.isBot(u.name) && players.none { it.equals(u.name, true) } }
        .sortedBy { it.name.lowercase() }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The part that can grow (up to six players, bots, option summaries) scrolls on its own...
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(table?.name ?: "Table", style = MaterialTheme.typography.headlineSmall)
                Text("${table?.variant ?: ""} - ${table?.numPlayers ?: 0}/${table?.maxPlayers ?: 0} players")
                table?.options?.activeLabels?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                // Only the table owner can change the game; the server refuses anyone else.
                if (isOwner) OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit game") }
                players.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 30.dp)) {
                        Text(
                            p + if (BotStore.isBot(p)) "  (bot)" else "",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (BotStore.isBot(p)) TextButton(onClick = { vm.kickBot(p) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Kick") }
                    }
                }
                if (botsAtTable.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { setAll = true }) { Text("Set all bots") }
                        OutlinedButton(onClick = vm::leaveAllBots) { Text("Kick all bots") }
                    }
                }
                if (BotStore.bots.isNotEmpty()) {
                    OutlinedButton(onClick = { showInvite = true }, modifier = Modifier.fillMaxWidth()) { Text("Invite bots") }
                    // Progress / errors of invites in flight, e.g. "Joining..." or the bot's own refusal.
                    ui.botStatus.forEach { (bot, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$bot: $text", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.dismissBotStatus(bot) }) { Text("Clear") }
                        }
                    }
                }
            }
            // ...while the message, the buttons and the chat stay put, so they are never pushed off screen.
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isOwner) {
                    Button(
                        onClick = vm::startTable,
                        enabled = (table?.numPlayers ?: 0) >= 2,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start game") }
                } else {
                    Text("Waiting for the host...", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = vm::leaveTable, modifier = if (isOwner) Modifier.weight(1f) else Modifier) { Text("Leave table") }
            }
            Text("Table chat", style = MaterialTheme.typography.titleSmall)
            ChatPanel(ui.tableChat, vm::sendTableChat, Modifier.height(170.dp), me = ui.me?.username)
        }
    }
    }
    if (showInvite) {
        InviteBotsDialog(
            choices = inviteable.map { u ->
                InviteChoice(u.name, ui.botStatus[u.name] ?: botWhere(u))
            },
            needsPassword = table?.passwordProtected == true,
            knownPassword = vm.tablePassword,
            onInvite = { bots, convention, fast, password -> bots.forEach { vm.inviteBot(it, convention, fast, password) } },
            onDismiss = { showInvite = false },
        )
    }
    if (setAll) SetAllBotsDialog(onSet = vm::setAllBots, onDismiss = { setAll = false })
    if (showEdit && table != null) {
        var name by remember(table.id) { mutableStateOf(table.name) }
        var opts by remember(table.id) { mutableStateOf(table.options.copy(variantName = table.variant.ifBlank { table.options.variantName })) }
        var maxPlayers by remember(table.id) { mutableStateOf(table.maxPlayers.coerceIn(2, 6)) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Edit game") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TableForm(name, { name = it }, opts, { opts = it }, maxPlayers, { maxPlayers = it })
                    if (maxPlayers < table.numPlayers) {
                        Text(
                            "Players over the new limit are removed from the table.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.updateTable(name, opts, maxPlayers); showEdit = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } },
        )
    }
}
/**
 * Where a bot is, for the invite list. The server sends a status number (0 lobby, 1 pregame, 2 playing,
 * 3 spectating, 4 replay, 5 shared replay) and tableID 0, not null, for someone in the lobby.
 */
private fun botWhere(u: live.hanab.client.net.User) = when (u.status.trim().toIntOrNull()) {
    null, 0 -> "Free"
    4, 5 -> "In a replay"
    else -> "At a table"
}
