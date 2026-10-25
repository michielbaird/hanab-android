package live.hanab.client.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

@Serializable
data class Welcome(
    val userID: Int = 0,
    val username: String = "",
    val playingAtTables: List<Int> = emptyList(),
    /** A table we were watching when the connection dropped (the server remembers it for us). */
    val disconSpectatingTable: Int? = null,
    /** The seat we were shadowing there, or -1 / null. */
    val disconShadowingSeat: Int? = null,
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
    val options: TableOpts = TableOpts(),
    /** Who is watching (players of a finished game included, once they are back in the replay). */
    val spectators: List<SpectatorInfo> = emptyList(),
)

@Serializable
data class SpectatorInfo(val name: String = "")

/** The special options of a table, as the server names them (see "Options" in the server). */
@Serializable
data class TableOpts(
    val variantName: String = "No Variant",
    val speedrun: Boolean = false,
    val cardCycle: Boolean = false,
    val deckPlays: Boolean = false,
    val emptyClues: Boolean = false,
    val oneExtraCard: Boolean = false,
    val oneLessCard: Boolean = false,
    val allOrNothing: Boolean = false,
    val detrimentalCharacters: Boolean = false,
) {
    /** Names of the switched-on special options, for a one-line summary. */
    val activeLabels: List<String>
        get() = buildList {
            if (speedrun) add("Speedrun")
            if (cardCycle) add("Card cycling")
            if (deckPlays) add("Bottom-deck blind play")
            if (emptyClues) add("Empty clues")
            if (oneExtraCard) add("One extra card")
            if (oneLessCard) add("One less card")
            if (allOrNothing) add("All or nothing")
            if (detrimentalCharacters) add("Detrimental characters")
        }

    /** The "options" object of tableCreate / tableUpdate. (Timed games are not offered here.) */
    fun toJson(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("variantName", variantName)
        put("timed", false)
        put("speedrun", speedrun)
        put("cardCycle", cardCycle)
        put("deckPlays", deckPlays)
        put("emptyClues", emptyClues)
        put("oneExtraCard", oneExtraCard)
        put("oneLessCard", oneLessCard)
        put("allOrNothing", allOrNothing)
        put("detrimentalCharacters", detrimentalCharacters)
    }
}

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