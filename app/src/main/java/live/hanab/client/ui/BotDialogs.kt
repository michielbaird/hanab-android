@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.hanab.client.BotStore
import live.hanab.client.LobbyUiState
import live.hanab.client.LobbyViewModel

/** A button that opens a menu of the conventions the bot understands (Reactor1, RefSieve, HGroup1-12). */
@Composable
fun ConventionPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$selected  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BotStore.conventions.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { onSelect(c); open = false })
            }
        }
    }
}

/** Convention + fast-mode choices (remembered between invites) and, if the table needs one, a password. */
@Composable
fun InviteBotDialog(
    bot: String,
    needsPassword: Boolean,
    knownPassword: String,
    onInvite: (convention: String, fast: Boolean, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var convention by remember { mutableStateOf(BotStore.convention) }
    var fast by remember { mutableStateOf(BotStore.fast) }
    var password by remember { mutableStateOf(knownPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite $bot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conventions", style = MaterialTheme.typography.labelLarge)
                ConventionPicker(convention) { convention = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fast, onCheckedChange = { fast = it })
                    Text("Fast mode (no thinking delay)")
                }
                if (needsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Table password") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onInvite(convention, fast, password); onDismiss() }, enabled = !needsPassword || password.isNotEmpty()) {
                Text("Invite")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Set the same conventions on every bot at the table ("/setall" in table chat). */
@Composable
fun SetAllBotsDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var convention by remember { mutableStateOf(BotStore.convention) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set all bots") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conventions for every bot at this table", style = MaterialTheme.typography.bodySmall)
                ConventionPicker(convention) { convention = it }
            }
        },
        confirmButton = { Button(onClick = { onSet(convention); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a bot and conventions, then ask it to analyse the finished game. */
@Composable
fun AnalyzeDialog(
    onlineBots: List<String>,
    onAnalyze: (bot: String, convention: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var bot by remember { mutableStateOf(onlineBots.firstOrNull()) }
    var convention by remember { mutableStateOf(BotStore.convention) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analyze this game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onlineBots.isEmpty()) {
                    Text("None of your bots is online.")
                } else {
                    Text("Bot", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        onlineBots.forEach { b -> FilterChip(bot == b, { bot = b }, { Text(b) }) }
                    }
                    Text("Conventions", style = MaterialTheme.typography.labelLarge)
                    ConventionPicker(convention) { convention = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = { bot?.let { onAnalyze(it, convention) }; onDismiss() }, enabled = bot != null) { Text("Analyze") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The bot's replies to "/analyze": a status line, then a list of suggestions, then "Analysis completed." */
@Composable
fun AnalysisDialog(ui: LobbyUiState, vm: LobbyViewModel) {
    val bot = ui.analysisBot ?: return
    val replies = ui.pms[bot].orEmpty().drop(ui.analysisFrom).filter { !it.who.equals(ui.me?.username, true) }
    AlertDialog(
        onDismissRequest = vm::closeAnalysis,
        title = { Text("Analysis by $bot") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replies.isEmpty()) Text("Waiting for $bot...")
                replies.forEach { Text(LobbyViewModel.plain(it.msg), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeAnalysis) { Text("Close") } },
    )
}

/** Tap menu for a user in the Users list. */
@Composable
fun UserMenuDialog(name: String, online: Boolean, onMessage: () -> Unit, onDismiss: () -> Unit) {
    val isBot = BotStore.isBot(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                if (online) TextButton(onClick = { onMessage(); onDismiss() }, Modifier.fillMaxWidth()) { Text("Send message") }
                TextButton(
                    onClick = { if (isBot) BotStore.unmark(name) else BotStore.mark(name); onDismiss() },
                    Modifier.fillMaxWidth(),
                ) { Text(if (isBot) "Remove bot mark" else "Mark as bot") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A bot you could invite and what we know about it. */
data class InviteChoice(val name: String, val note: String)

/**
 * One dialog for the whole invite: tick the bots, choose the conventions (remembered) and fast mode
 * (remembered), add the table password if one is needed, then invite them all.
 */
@Composable
fun InviteBotsDialog(
    choices: List<InviteChoice>,
    needsPassword: Boolean,
    knownPassword: String,
    onInvite: (bots: List<String>, convention: String, fast: Boolean, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    var convention by remember { mutableStateOf(BotStore.convention) }
    var fast by remember { mutableStateOf(BotStore.fast) }
    var password by remember { mutableStateOf(knownPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite bots") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choices.isEmpty()) {
                    Text("None of your marked bots is online and free. Mark bots from the Users tab.")
                }
                choices.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (c.name in picked) picked - c.name else picked + c.name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.name in picked, onCheckedChange = { picked = if (it) picked + c.name else picked - c.name })
                        Column {
                            Text(c.name)
                            Text(c.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (choices.isNotEmpty()) {
                    Text("Conventions", style = MaterialTheme.typography.labelLarge)
                    ConventionPicker(convention) { convention = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fast, onCheckedChange = { fast = it })
                        Text("Fast mode (no thinking delay)")
                    }
                    if (needsPassword) {
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("Table password") }, singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(picked.toList(), convention, fast, password); onDismiss() },
                enabled = picked.isNotEmpty() && (!needsPassword || password.isNotEmpty()),
            ) { Text(if (picked.size > 1) "Invite ${picked.size}" else "Invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}