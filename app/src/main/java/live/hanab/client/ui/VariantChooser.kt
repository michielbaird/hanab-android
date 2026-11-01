@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import live.hanab.client.game.Variants

/**
 * The chosen variant on one line with a Change button that opens [VariantPicker] ([options] is every variant on offer;
 * the quick picks are at the top of the picker).
 */
@Composable
fun VariantChooser(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Variant", style = MaterialTheme.typography.labelMedium)
            Text(selected, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { picking = true }) { Text("Change") }
    }
    if (picking) VariantPicker(options, selected, onPick = { onSelect(it); picking = false }, onDismiss = { picking = false })
}

/**
 * A full-screen search over [options]: the search box on top and every match below it, in a list that stays on screen
 * above the keyboard. Back closes the keyboard first, then the picker.
 */
@Composable
fun VariantPicker(options: List<String>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, options) { Variants.search(query, options) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // While the keyboard is up, back closes it and nothing more.
        BackHandler(enabled = WindowInsets.isImeVisible) {
            keyboard?.hide()
            focus.clearFocus()
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a variant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search, like \"rainbow ones\" or \"6 suits\"") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(Variants.quick.filter { it in options }, key = { it }) { v ->
                        FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(v) })
                    }
                }
                Text(
                    if (matches.isEmpty()) "No variant matches." else "${matches.size} variant${if (matches.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(matches, key = { it }) { name ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(name) }) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (name == selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (name == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 14.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
