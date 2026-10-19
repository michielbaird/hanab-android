package live.hanab.client

import android.app.Application
import android.text.Html
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.ui.PipSettings
import live.hanab.client.ui.PipStore
import live.hanab.client.game.GameState
import live.hanab.client.game.MoveEvent
import live.hanab.client.game.Variants
import live.hanab.client.net.ChatList
import live.hanab.client.net.PmHistory
import live.hanab.client.net.ChatMessage
import live.hanab.client.net.ConnState
import live.hanab.client.net.HanabClient
import live.hanab.client.net.HanabJson
import live.hanab.client.net.IdOnly
import live.hanab.client.net.ServerMessage
import live.hanab.client.net.Table
import live.hanab.client.net.TableGone
import live.hanab.client.net.User
import live.hanab.client.net.Welcome

/** Present when the table is a shared replay (a finished game that people can step through together). */
data class ReplayInfo(val databaseID: Int, val leader: String)

data class LobbyUiState(
    val loggedIn: Boolean = false,
    val loggingIn: Boolean = false,
    val error: String? = null,
    val me: Welcome? = null,
    val users: Map<Int, User> = emptyMap(),
    val tables: Map<Int, Table> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    /** Chat of the table we are seated at (pregame and in-game). */
    val tableChat: List<ChatMessage> = emptyList(),
    /** Live plays/discards waiting to be animated (oldest first). */
    val moves: List<MoveEvent> = emptyList(),
    /** Latest progress / failure text per bot being invited (key = bot name). */
    val botStatus: Map<String, String> = emptyMap(),
    /** Bot whose "/analyze" reply is on screen, and how many PMs from it existed when we asked. */
    val analysisBot: String? = null,
    val analysisFrom: Int = 0,
    val draws: List<live.hanab.client.game.DrawEvent> = emptyList(),
    /** True while the connection is down and we are signing back in; the screen stays as it was. */
    val reconnecting: Boolean = false,
    val replay: ReplayInfo? = null,
    /** Segment the replay leader is on (equals an index into GameState.turnStops). */
    val sharedSegment: Int? = null,
    /** Private-message conversations keyed by the other user's name, oldest first. */
    val pms: Map<String, List<ChatMessage>> = emptyMap(),
    val pmUnread: Set<String> = emptySet(),
    /** Conversation currently on screen, so its new messages are not counted as unread. */
    val pmOpen: String? = null,
    val connection: ConnState = ConnState.Disconnected,
    /** Table we are seated at (pregame or in progress). */
    val joinedTableID: Int? = null,
    val game: GameState? = null,
)

class LobbyViewModel(app: Application) : AndroidViewModel(app) {
    val client = HanabClient()
    private val creds = CredentialStore(app)

    private val _ui = MutableStateFlow(LobbyUiState())
    val ui: StateFlow<LobbyUiState> = _ui

    val savedUsername: String get() = creds.username

    init {
        Variants.load(app)
        PipStore.init(app)
        AppSettings.load(app)
        BotStore.load(app)
        GameSounds.init(app)
        creds.password()?.let { pw -> if (creds.username.isNotBlank()) login(creds.username, pw) }
        viewModelScope.launch { client.messages.collect(::handle) }
        viewModelScope.launch { client.errors.collect { e -> _ui.update { it.copy(error = e) } } }
        viewModelScope.launch {
            client.state.collect { s ->
                val dropped = s == ConnState.Disconnected && _ui.value.loggedIn
                if (dropped && creds.password() != null) {
                    // Keep the current screen (even a game in progress) and sign back in behind it.
                    _ui.update { it.copy(connection = s, reconnecting = true) }
                    startReconnect()
                } else {
                    _ui.update {
                        // No saved login: a dropped socket returns to the login screen.
                        if (dropped) LobbyUiState(error = it.error ?: "Disconnected") else it.copy(connection = s)
                    }
                }
            }
        }
    }

    private var reconnectJob: Job? = null

    /** Retries sign-in with growing pauses until the server answers with "welcome". */
    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (_ui.value.reconnecting) {
                delay(minOf(1000L shl attempt.coerceAtMost(4), 15_000L))
                attempt++
                val pw = creds.password() ?: break
                val err = client.login(creds.username, pw)
                when {
                    err == null -> {
                        client.connect()
                        delay(8_000) // give the socket time to deliver "welcome", which ends reconnecting
                    }
                    err.startsWith("Network error") -> Unit // offline: keep trying
                    else -> { // the server rejected the saved password
                        creds.clearPassword()
                        _ui.value = LobbyUiState(error = err)
                        break
                    }
                }
            }
        }
    }

    /** Called when the app returns to the foreground; the phone may have dropped the socket meanwhile. */
    fun onForeground() {
        val s = _ui.value
        if (s.loggedIn && client.state.value == ConnState.Disconnected && !s.reconnecting && creds.password() != null) {
            _ui.update { it.copy(reconnecting = true) }
            startReconnect()
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _ui.update { it.copy(error = "Enter a username and password") }
            return
        }
        _ui.update { it.copy(loggingIn = true, error = null) }
        viewModelScope.launch {
            val err = client.login(username.trim(), password)
            if (err != null) {
                // A rejected password must not be retried on every launch; network errors keep it.
                if (!err.startsWith("Network error")) creds.clearPassword()
                _ui.update { it.copy(loggingIn = false, error = err) }
            } else {
                creds.save(username.trim(), password)
                client.connect()
            }
        }
    }

    fun logout() {
        creds.clearPassword()
        client.disconnect()
        _ui.value = LobbyUiState()
    }

    private val pmAliases = setOf("pm", "w", "whisper", "msg", "tell", "t")

    /** Shows [text] where the user is looking: table chat during a table, otherwise the error line. */
    private fun notice(text: String) {
        _ui.update {
            if (it.joinedTableID != null) it.copy(tableChat = it.tableChat + ChatMessage(msg = text, who = "server", source = "server"))
            else it.copy(error = text)
        }
    }

    /** Handles "/pm Alice hi" (and aliases), which the server only understands as a "chatPM" command. */
    private fun tryPmCommand(text: String, room: String): Boolean {
        if (!text.startsWith("/")) return false
        val parts = text.drop(1).trim().split(Regex("\\s+"), limit = 3)
        if (parts[0].lowercase() !in pmAliases) return false
        val recipient = parts.getOrNull(1).orEmpty()
        val msg = parts.getOrNull(2).orEmpty().trim()
        val state = _ui.value
        when {
            recipient.isEmpty() || msg.isEmpty() -> notice("The format of a private message is: /w Alice hello")
            recipient.equals(state.me?.username, ignoreCase = true) -> notice("You cannot send a private message to yourself.")
            else -> {
                val user = state.users.values.firstOrNull { it.name.equals(recipient, ignoreCase = true) }
                if (user == null) notice("User \"$recipient\" is not currently online.")
                else client.send("chatPM", buildJsonObject {
                    put("msg", msg)
                    put("recipient", user.name)
                    put("room", room)
                })
            }
        }
        return true
    }

    // ---------------------------------------------------------------- bots

    /** Password of the table we created or joined, if any; "/join" needs it for protected tables. */
    var tablePassword: String = ""
        private set

    private enum class InviteStage { Settings, Fast, Join }

    private class Invite(val fast: Boolean, val password: String, var stage: InviteStage, var job: Job? = null)

    private val invites = HashMap<String, Invite>()

    private fun setBotStatus(bot: String, text: String?) = _ui.update {
        it.copy(botStatus = if (text == null) it.botStatus - bot else it.botStatus + (bot to text))
    }

    /**
     * Ask a bot to join our table. The bot is configured by PM first: "/settings <convention>" (its
     * reply says whether fast mode is on), then "/fastmode" only if that differs from what we want
     * (it is a toggle), then "/join [password]".
     */
    fun inviteBot(bot: String, convention: String, fast: Boolean, password: String) {
        BotStore.remember(convention, fast)
        val key = bot.lowercase()
        invites.remove(key)?.job?.cancel()
        val inv = Invite(fast, password, InviteStage.Settings)
        invites[key] = inv
        setBotStatus(bot, "Setting $convention...")
        sendPm(bot, "/settings $convention")
        inv.job = viewModelScope.launch {
            delay(6_000)
            if (invites[key] === inv && inv.stage != InviteStage.Join) {
                setBotStatus(bot, "No reply from $bot; trying to join anyway")
                joinStep(bot, inv)
            }
        }
    }

    private fun joinStep(bot: String, inv: Invite) {
        inv.job?.cancel()
        inv.stage = InviteStage.Join
        setBotStatus(bot, "Joining...")
        sendPm(bot, if (inv.password.isNotEmpty()) "/join ${inv.password}" else "/join")
        inv.job = viewModelScope.launch {
            delay(8_000)
            // A bot that joined shows up in the table list; anything else stays as its last status.
            if (invites[bot.lowercase()] === inv) { invites.remove(bot.lowercase()); setBotStatus(bot, null) }
        }
    }

    /** Called for every PM we receive; moves an invite along when the bot answers. */
    private fun onBotReply(bot: String, text: String) {
        val inv = invites[bot.lowercase()] ?: return
        when (inv.stage) {
            InviteStage.Settings -> when {
                text.startsWith("Currently playing with") -> {
                    if (replyShowsFastMode(text) != inv.fast) {
                        inv.stage = InviteStage.Fast
                        setBotStatus(bot, if (inv.fast) "Turning fast mode on..." else "Turning fast mode off...")
                        sendPm(bot, "/fastmode")
                    } else joinStep(bot, inv)
                }
                text.startsWith("Unrecognized convention") -> {
                    inv.job?.cancel(); invites.remove(bot.lowercase()); setBotStatus(bot, text)
                }
            }
            InviteStage.Fast -> if (text.startsWith("Fast mode is now")) joinStep(bot, inv)
            InviteStage.Join -> {
                // e.g. "Could not join, as you are not in a room." / "Room is password protected..."
                if (text.startsWith("Could not") || text.startsWith("Room is password")) {
                    inv.job?.cancel(); invites.remove(bot.lowercase()); setBotStatus(bot, text)
                }
            }
        }
    }

    fun kickBot(bot: String) = sendPm(bot, "/leave")

    /** Table-wide bot commands go through the table chat, where every bot at the table hears them. */
    fun setAllBots(convention: String) = sendTableChat("/setall $convention")
    fun leaveAllBots() = sendTableChat("/leaveall")

    fun dismissBotStatus(bot: String) = setBotStatus(bot, null)

    /** Ask [bot] to analyse a finished game; its replies are shown from now on. */
    fun analyzeWithBot(bot: String, databaseID: Int, convention: String) {
        BotStore.remember(convention, BotStore.fast)
        _ui.update { it.copy(analysisBot = bot, analysisFrom = it.pms[bot].orEmpty().size) }
        sendPm(bot, "/analyze $databaseID $convention")
    }

    fun closeAnalysis() = _ui.update { it.copy(analysisBot = null) }

    fun sendPm(recipient: String, text: String) {
        if (text.isBlank()) return
        client.send("chatPM", buildJsonObject {
            put("msg", text)
            put("recipient", recipient)
            put("room", "lobby")
        })
    }

    fun openPm(name: String?) {
        _ui.update { it.copy(pmOpen = name, pmUnread = if (name == null) it.pmUnread else it.pmUnread - name) }
        if (name != null) client.send("chatPMHistoryGet", buildJsonObject {
            put("amount", 50)
            put("username", name)
        })
    }

    private fun addPm(c: ChatMessage) {
        val me = _ui.value.me?.username ?: return
        val other = if (c.who.equals(me, ignoreCase = true)) c.recipient.orEmpty() else c.who.orEmpty()
        if (other.isEmpty()) return
        _ui.update { s ->
            val merged = mergePms(s.pms[other].orEmpty(), listOf(c))
            val incoming = !c.who.equals(me, ignoreCase = true)
            var next = s.copy(
                pms = s.pms + (other to merged),
                pmUnread = if (incoming && s.pmOpen != other) s.pmUnread + other else s.pmUnread,
            )
            // Mid-game the PM tab is out of sight, so also show it in the table chat.
            if (s.joinedTableID != null) {
                val label = if (incoming) "PM from $other" else "PM to $other"
                next = next.copy(tableChat = next.tableChat + c.copy(who = label))
            }
            next
        }
        // Outside the state update above: handling a reply can send messages and change state itself.
        if (!c.who.equals(me, ignoreCase = true)) onBotReply(other, plain(c.msg))
    }

    private fun mergePms(old: List<ChatMessage>, new: List<ChatMessage>): List<ChatMessage> =
        (old + new).distinctBy { it.pmID ?: "${it.who}|${it.datetime}|${it.msg}" }
            .sortedWith(compareBy({ it.pmID ?: Int.MAX_VALUE }, { it.datetime }))

    fun sendChat(text: String) {
        if (text.isBlank()) return
        if (tryPmCommand(text.trim(), "lobby")) return
        client.send("chat", buildJsonObject {
            put("msg", text)
            put("room", "lobby")
        })
    }

    fun sendTableChat(text: String) {
        val id = _ui.value.joinedTableID ?: return
        if (text.isBlank()) return
        if (tryPmCommand(text.trim(), "table$id")) return
        client.send("chat", buildJsonObject {
            put("msg", text)
            put("room", "table$id")
        })
    }

    fun joinTable(id: Int, password: String = "") {
        tablePassword = password
        AppSettings.rememberPassword(password)
        client.send("tableJoin", buildJsonObject {
            put("tableID", id)
            put("password", password)
        })
    }

    fun createTable(name: String, variant: String, maxPlayers: Int, password: String = "") {
        client.send("tableCreate", buildJsonObject {
            put("name", name.ifBlank { _ui.value.me?.randomTableName ?: "Table" })
            put("maxPlayers", maxPlayers)
            if (password.isNotEmpty()) put("password", password)
            tablePassword = password
            AppSettings.rememberPassword(password)
            put("options", buildJsonObject { put("variantName", variant) })
        })
    }

    fun startTable() {
        val id = _ui.value.joinedTableID ?: return
        client.send("tableStart", buildJsonObject { put("tableID", id) })
    }

    /**
     * Leave the table we are at. Before the game starts that is "tableLeave". During or after a game
     * (including the shared replay a finished game turns into) it is "tableUnattend", which the
     * server treats as leaving the replay: when the last person leaves, the replay is deleted.
     * The game's own table id is used, as the "joined" id can lag behind (e.g. after play again).
     */
    fun leaveTable() {
        val ui = _ui.value
        val id = ui.game?.tableID ?: ui.joinedTableID ?: return
        client.send(if (ui.game != null) "tableUnattend" else "tableLeave", buildJsonObject { put("tableID", id) })
        _ui.update {
            it.copy(
                joinedTableID = null, game = null, tableChat = emptyList(), replay = null,
                sharedSegment = null, moves = emptyList(), draws = emptyList(),
            )
        }
    }
    /**
     * Start another game with the same players. The server only allows this while every original
     * player is still in the shared replay; otherwise it answers with a warning, shown in the end panel.
     */
    fun restartTable() {
        val id = _ui.value.joinedTableID ?: return
        client.send("tableRestart", buildJsonObject {
            put("tableID", id)
            // true = start the new game immediately with the same players, as the website does.
            put("hidePregame", true)
        })
    }

    /** Watch a running game or a shared replay. */
    fun spectateTable(id: Int) {
        client.send("tableSpectate", buildJsonObject {
            put("tableID", id)
            put("shadowingPlayerIndex", -1)
        })
    }

    /** Shared replay leader only: move everyone to this segment. */
    fun sendReplaySegment(segment: Int) {
        val id = _ui.value.joinedTableID ?: return
        client.send("replayAction", buildJsonObject {
            put("tableID", id)
            put("type", 0)
            put("segment", segment)
        })
    }

    fun setNote(order: Int, text: String) {
        val g = _ui.value.game ?: return
        _ui.update { it.copy(game = it.game?.withNote(order, text)) }
        client.send("note", buildJsonObject {
            put("tableID", g.tableID)
            put("order", order)
            put("note", text.trim())
        })
    }

    fun play(order: Int) = sendAction(0, order, 0)
    fun discard(order: Int) = sendAction(1, order, 0)
    fun colorClue(player: Int, color: Int) = sendAction(2, player, color)
    fun rankClue(player: Int, rank: Int) = sendAction(3, player, rank)

    private fun sendAction(type: Int, target: Int, value: Int) {
        val g = _ui.value.game ?: return
        if (!g.ourTurn) return
        client.send("action", buildJsonObject {
            put("tableID", g.tableID)
            put("type", type)
            put("target", target)
            put("value", value)
        })
    }

    private fun beginGame(tableID: Int) {
        client.send("getGameInfo1", buildJsonObject { put("tableID", tableID) })
    }

    private fun onInit(d: JsonObject) {
        val tableID = d["tableID"]?.jsonPrimitive?.intOrNull ?: return
        val names = (d["playerNames"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return
        val variantName = d["options"]?.jsonObject?.get("variantName")?.jsonPrimitive?.content ?: "No Variant"
        val suits = Variants.suits(variantName) ?: listOf("Red", "Yellow", "Green", "Blue", "Purple")
        val state = GameState(
            tableID = tableID,
            playerNames = names,
            ourIndex = d["ourPlayerIndex"]?.jsonPrimitive?.intOrNull ?: 0,
            spectating = (d["spectating"] as? JsonPrimitive)?.content == "true" ||
                (d["replay"] as? JsonPrimitive)?.content == "true",
            variantName = variantName,
            suits = suits,
            suitInfos = suits.map(Variants::info),
        )
        val shared = (d["sharedReplay"] as? JsonPrimitive)?.content == "true"
        val replay = if (shared) ReplayInfo(
            d["databaseID"]?.jsonPrimitive?.intOrNull ?: 0,
            d["sharedReplayLeader"]?.jsonPrimitive?.content.orEmpty(),
        ) else null
        _ui.update { it.copy(game = state, joinedTableID = tableID, replay = replay, sharedSegment = null) }
        client.send("getGameInfo2", buildJsonObject { put("tableID", tableID) })
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private inline fun <reified T> ServerMessage.decode(): T? =
        data?.let { runCatching { HanabJson.decodeFromJsonElement<T>(it) }.getOrNull() }

    private fun handle(m: ServerMessage) {
        try {
            dispatch(m)
        } catch (e: Exception) {
            // One bad message must not stop the collector, or the whole UI freezes.
            android.util.Log.e("Hanab", "Failed handling ${m.command}: ${m.data}", e)
        }
    }

    private fun dispatch(m: ServerMessage) {
        if (m.command.startsWith("game") || m.command == "init" || m.command == "tableStart") {
            android.util.Log.d("Hanab", "<- ${m.command} ${m.data.toString().take(300)}")
        }
        when (m.command) {
            "welcome" -> m.decode<Welcome>()?.let { w ->
                _ui.update { it.copy(me = w, loggedIn = true, loggingIn = false, reconnecting = false) }
                // Rejoin a game we were already playing (e.g. after the app was killed).
                // The server ties a seat to the websocket that sat down, so we must take it over with
                // "tableReattend"; it answers with "tableStart", which loads the game.
                w.playingAtTables.firstOrNull()?.let { id ->
                    client.send("tableReattend", buildJsonObject { put("tableID", id) })
                }
            }
            "userList" -> (m.data as? JsonArray)?.let { arr ->
                val users = arr.mapNotNull { runCatching { HanabJson.decodeFromJsonElement<User>(it) }.getOrNull() }
                _ui.update { it.copy(users = users.associateBy { u -> u.userID }) }
            }
            "user" -> m.decode<User>()?.let { u -> _ui.update { it.copy(users = it.users + (u.userID to u)) } }
            "userLeft" -> m.decode<IdOnly>()?.let { l -> _ui.update { it.copy(users = it.users - l.userID) } }
            "tableList" -> (m.data as? JsonArray)?.let { arr ->
                val tables = arr.mapNotNull { runCatching { HanabJson.decodeFromJsonElement<Table>(it) }.getOrNull() }
                _ui.update { it.copy(tables = tables.associateBy { t -> t.id }) }
            }
            "table" -> m.decode<Table>()?.let { t -> _ui.update { it.copy(tables = it.tables + (t.id to t)) } }
            "tableGone" -> m.decode<TableGone>()?.let { g -> _ui.update { it.copy(tables = it.tables - g.tableID) } }
            "chatList" -> m.decode<ChatList>()?.let { l ->
                // Lobby history and table history arrive in the same command, tagged by room.
                val lobby = l.list.filter(::isLobby)
                val table = l.list.filter(::isOurTable)
                _ui.update {
                    it.copy(
                        chat = if (lobby.isNotEmpty() || table.isEmpty()) lobby else it.chat,
                        tableChat = if (table.isNotEmpty()) table else it.tableChat,
                    )
                }
            }
            "chatPMHistory" -> m.decode<PmHistory>()?.let { h ->
                val me = _ui.value.me?.username
                h.list.groupBy { if (it.who.equals(me, ignoreCase = true)) it.recipient.orEmpty() else it.who.orEmpty() }
                    .forEach { (other, msgs) ->
                        if (other.isNotEmpty()) _ui.update { s -> s.copy(pms = s.pms + (other to mergePms(s.pms[other].orEmpty(), msgs))) }
                    }
            }
            "chat" -> m.decode<ChatMessage>()?.let { c ->
                if (!c.recipient.isNullOrEmpty()) addPm(c)
                else if (isLobby(c)) _ui.update { it.copy(chat = (it.chat + c).takeLast(300)) }
                else if (isOurTable(c)) _ui.update { it.copy(tableChat = (it.tableChat + c).takeLast(300)) }
            }
            "joined" -> (m.data as? JsonObject)?.get("tableID")?.jsonPrimitive?.intOrNull?.let { id ->
                // A different table (e.g. after "play again") means the old game screen must go.
                _ui.update {
                    if (it.game != null && it.game.tableID != id)
                        it.copy(joinedTableID = id, game = null, replay = null, sharedSegment = null, tableChat = emptyList())
                    else it.copy(joinedTableID = id)
                }
            }
            "finishOngoingGame" -> (m.data as? JsonObject)?.let { d ->
                _ui.update {
                    it.copy(replay = ReplayInfo(
                        d["databaseID"]?.jsonPrimitive?.intOrNull ?: 0,
                        d["sharedReplayLeader"]?.jsonPrimitive?.content.orEmpty(),
                    ))
                }
            }
            "replaySegment" -> (m.data as? JsonObject)?.get("segment")?.jsonPrimitive?.intOrNull?.let { seg ->
                _ui.update { it.copy(sharedSegment = seg) }
            }
            "cardIdentities" -> (m.data as? JsonObject)?.get("cardIdentities")?.let { arr ->
                val ids = (arr as? JsonArray)?.map { e ->
                    val o = e as? JsonObject
                    (o?.get("suitIndex")?.jsonPrimitive?.intOrNull ?: -1) to (o?.get("rank")?.jsonPrimitive?.intOrNull ?: -1)
                } ?: emptyList()
                _ui.update { s -> s.copy(game = s.game?.withIdentities(ids)) }
            }
            "left" -> _ui.update { it.copy(joinedTableID = null, game = null, tableChat = emptyList(), replay = null, sharedSegment = null) }
            "tableStart" -> (m.data as? JsonObject)?.let { d ->
                if ((d["replay"] as? JsonPrimitive)?.content != "true") {
                    d["tableID"]?.jsonPrimitive?.intOrNull?.let(::beginGame)
                }
            }
            "init" -> (m.data as? JsonObject)?.let(::onInit)
            "gameActionList" -> (m.data as? JsonObject)?.get("list")?.let { list ->
                val actions = (list as JsonArray).mapNotNull { it as? JsonObject }
                _ui.update { s -> s.copy(game = s.game?.applyAll(actions)) }
                // Tell the server our UI is ready: until then we are marked "away" (red name on the
                // website) and receive no live game actions.
                _ui.value.game?.tableID?.let { id ->
                    client.send("loaded", buildJsonObject { put("tableID", id) })
                }
            }
            "gameAction" -> (m.data as? JsonObject)?.get("action")?.let { a ->
                (a as? JsonObject)?.let { act ->
                    val ev = moveEventOf(act)
                    val type = act["type"]?.jsonPrimitive?.content
                    // The sound depends on the position before the move (was the card clued, ...).
                    soundTracker.onAction(
                        _ui.value.game, type,
                        act["order"]?.jsonPrimitive?.intOrNull ?: -1,
                        (act["failed"] as? JsonPrimitive)?.content == "true",
                    )
                    val drawEv = if (type == "draw" && _ui.value.game != null) {
                        live.hanab.client.game.DrawEvent(
                            ++moveCounter,
                            act["playerIndex"]?.jsonPrimitive?.intOrNull ?: -1,
                            act["order"]?.jsonPrimitive?.intOrNull ?: -1,
                        )
                    } else null
                    _ui.update { s ->
                        s.copy(
                            game = s.game?.applySafe(act),
                            moves = if (ev != null) s.moves + ev else s.moves,
                            draws = if (drawEv != null) s.draws + drawEv else s.draws,
                        )
                    }
                    playSoundsFor(type)
                }
            }
            "noteListPlayer" -> (m.data as? JsonObject)?.get("notes")?.let { n ->
                val list = (n as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                _ui.update { it.copy(game = it.game?.withNotes(list)) }
            }
            "boot" -> _ui.update { it.copy(joinedTableID = null, game = null, tableChat = emptyList(), replay = null, sharedSegment = null) }
            "error" -> (m.data as? JsonObject)?.get("error")?.let { e ->
                _ui.update { it.copy(error = e.toString().trim('"'), loggingIn = false) }
            }
            "warning" -> (m.data as? JsonObject)?.get("warning")?.let { e ->
                _ui.update { it.copy(error = e.toString().trim('"')) }
            }
        }
    }

    private val soundTracker = MoveSoundTracker()

    /** Sound and vibration for a live action, as the website does at the start of each turn. */
    private fun playSoundsFor(type: String?) {
        val g = _ui.value.game ?: return
        when (type) {
            "turn" -> if (g.phase == live.hanab.client.game.Phase.Playing) {
                val ours = g.currentPlayer == g.ourIndex
                if (AppSettings.soundMode == SoundMode.All || (AppSettings.soundMode == SoundMode.OwnTurn && ours)) {
                    GameSounds.play(soundTracker.turnSound(g))
                }
                if (soundTracker.lastWasMisplay) GameSounds.vibrate(250)
                else if (ours && !g.spectating) GameSounds.vibrate(90)
            }
            "gameOver" -> {
                GameSounds.play(finishedSound(g))
                GameSounds.vibrate(400)
            }
        }
    }

    private var moveCounter = 0L

    private fun moveEventOf(a: JsonObject): MoveEvent? {
        val type = a["type"]?.jsonPrimitive?.content
        if (type != "play" && type != "discard") return null
        fun n(k: String) = a[k]?.jsonPrimitive?.intOrNull ?: -1
        return MoveEvent(
            id = ++moveCounter,
            play = type == "play",
            player = n("playerIndex"),
            order = n("order"),
            suit = n("suitIndex"),
            rank = n("rank"),
            failed = (a["failed"] as? JsonPrimitive)?.content == "true",
        )
    }

    /** Called by the UI once it has started animating these moves. */
    fun consumeDraws(upToId: Long) = _ui.update { s -> s.copy(draws = s.draws.filter { it.id > upToId }) }

    fun consumeMoves(upToId: Long) = _ui.update { s -> s.copy(moves = s.moves.filter { it.id > upToId }) }

    private fun isLobby(c: ChatMessage) = c.room == null || c.room == "lobby"
    private fun isOurTable(c: ChatMessage) = c.room != null && c.room == "table${_ui.value.joinedTableID}"

    companion object {
        /** Chat text arrives HTML-escaped. */
        fun plain(html: String): String =
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
