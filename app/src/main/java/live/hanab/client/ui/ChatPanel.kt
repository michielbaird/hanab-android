package live.hanab.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import live.hanab.client.LobbyViewModel
import live.hanab.client.net.ChatMessage

/** A scrolling message list with a send box; used for the pregame and in-game table chat. */
@Composable
fun ChatPanel(messages: List<ChatMessage>, onSend: (String) -> Unit, modifier: Modifier = Modifier, me: String? = null) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    fun send() {
        if (draft.isBlank()) return
        onSend(draft)
        draft = ""
    }
    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState) {
            items(messages) { c -> ChatLine(c, me) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = ::send) { Text("Send") }
        }
    }
}

private val NAME_COLORS = listOf(
    0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DD0E1, 0xFFF06292, 0xFFDCE775, 0xFFFF8A65,
).map { Color(it) }

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** "14:05" in the phone's time zone from the server's ISO timestamp, or null if there is none. */
private fun clock(datetime: String): String? =
    runCatching { CLOCK.format(Instant.parse(datetime)) }.getOrNull()

/**
 * One chat line: a dim time, the sender in bold and a colour of their own (the same every time), then the
 * message. Notices from the server ("X joined the table") and whispers ("PM from X") are in italics instead.
 */
@Composable
internal fun ChatLine(c: ChatMessage, me: String?) {
    val dim = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    val who = c.who.orEmpty()
    val notice = c.source == "server" || who.isEmpty() || who == "server"
    val whisper = who.startsWith("PM from") || who.startsWith("PM to")
    val text: AnnotatedString = buildAnnotatedString {
        clock(c.datetime)?.let { withStyle(SpanStyle(color = dim, fontSize = 12.sp)) { append("$it ") } }
        val body = LobbyViewModel.plain(c.msg)
        when {
            notice -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = dim)) { append(body) }
            whisper -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = NAME_COLORS[5])) { append("$who: $body") }
            else -> {
                val color = if (me != null && who.equals(me, ignoreCase = true)) MaterialTheme.colorScheme.primary
                else NAME_COLORS[(who.lowercase().hashCode() and 0x7fffffff) % NAME_COLORS.size]
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(who) }
                append(": ")
                append(body)
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}
