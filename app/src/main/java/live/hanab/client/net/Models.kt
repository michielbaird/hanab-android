package live.hanab.client.net

import kotlinx.serialization.Serializable

@Serializable
data class Welcome(
    val userID: Int = 0,
    val username: String = "",
    val playingAtTables: List<Int> = emptyList(),
    val randomTableName: String = "",
)

@Serializable
data class User(
    val userID: Int,
    val name: String,
    val status: String = "",
    val tableID: Int? = null,
    val inactive: Boolean = false,
)

@Serializable
data class Table(
    val id: Int,
    val name: String = "",
    val joined: Boolean = false,
    val maxPlayers: Int = 5,
    val numPlayers: Int = 0,
    val ownerID: Int = 0,
    val passwordProtected: Boolean = false,
    val players: List<String> = emptyList(),
    val progress: Int = 0,
    val running: Boolean = false,
    val sharedReplay: Boolean = false,
    val variant: String = "",
    val timed: Boolean = false,
)

@Serializable
data class ChatMessage(
    val msg: String = "",
    val who: String? = null,
    val source: String = "user",
    val datetime: String = "",
    val chatID: Int? = null,
    val pmID: Int? = null,
    val room: String? = null,
    val recipient: String? = null,
)

@Serializable
data class ChatList(val list: List<ChatMessage> = emptyList())

@Serializable
data class IdOnly(val userID: Int = 0)

@Serializable
data class TableGone(val tableID: Int = 0)

@Serializable
data class PmHistory(val list: List<ChatMessage> = emptyList(), val hasMore: Boolean = false)