package live.hanab.client.game

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A "detrimental character" a player was given: an extra restriction on top of the normal rules. */
data class CharacterInfo(val id: Int, val emoji: String, val name: String, val text: String)

/**
 * Fill the placeholders in a character's description with the values the server chose for this player:
 * "[random color]" is a clue colour, "[random number]" a rank and "[random suit]" a suit.
 */
fun fillCharacterText(text: String, meta: Int, clueColors: List<String>, suits: List<String>): String = when {
    "[random color]" in text -> text.replace("[random color]", clueColors.getOrNull(meta)?.lowercase() ?: "?")
    "[random number]" in text -> text.replace("[random number]", meta.toString())
    "[random suit]" in text -> text.replace("[random suit]", suits.getOrNull(meta) ?: "?")
    else -> text
}

/** The characters from the website's characters.json, bundled with the app. */
object Characters {
    private var byId: Map<Int, CharacterInfo> = emptyMap()

    fun load(context: Context) {
        if (byId.isNotEmpty()) return
        val text = context.assets.open("characters.json").bufferedReader().use { it.readText() }
        byId = Json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            CharacterInfo(
                id,
                o["emoji"]?.jsonPrimitive?.content.orEmpty(),
                o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                o["description"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.associateBy { it.id }
    }

    fun describe(id: Int, meta: Int, clueColors: List<String>, suits: List<String>): CharacterInfo? =
        byId[id]?.let { it.copy(text = fillCharacterText(it.text, meta, clueColors, suits)) }
}