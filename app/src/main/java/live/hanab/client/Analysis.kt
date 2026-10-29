package live.hanab.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.net.HanabJson

/** One move of a game that the bot would have played differently. */
@kotlinx.serialization.Serializable
data class AnalysisComment(val turn: Int, val player: String, val mistake: Boolean, val text: String)

/** Which game to analyse: one from the website (by its replay id) or one played offline (by its id). */
data class AnalysisRequest(
    val title: String,
    val variant: String,
    val convention: String,
    val online: Boolean,
    val id: Long,
)

/** The analysis dialog: choosing the conventions, running (on the phone), the result, or what went wrong. */
sealed interface AnalysisUi {
    val request: AnalysisRequest

    data class Setup(override val request: AnalysisRequest) : AnalysisUi
    data class Running(
        override val request: AnalysisRequest,
        val convention: String,
        val startedAt: Long,
        /** Moves the bot has looked at, of all the moves in the game (0 of 0 until it has started). */
        val done: Int = 0,
        val total: Int = 0,
    ) : AnalysisUi
    data class Done(override val request: AnalysisRequest, val convention: String, val comments: List<AnalysisComment>) : AnalysisUi
    data class Failed(override val request: AnalysisRequest, val message: String) : AnalysisUi
}

/** Reads what the native analysis returns: `{"players", "comments"}`, or `{"error"}` (thrown as an exception). */
fun parseAnalysis(json: String): List<AnalysisComment> {
    val o = HanabJson.parseToJsonElement(json).jsonObject
    o["error"]?.let { throw IllegalStateException(it.jsonPrimitive.content) }
    val players = (o["players"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    return (o["comments"] as? JsonArray).orEmpty().map { c ->
        val m = c as JsonObject
        AnalysisComment(
            turn = m["turn"]?.jsonPrimitive?.intOrNull ?: 0,
            player = players.getOrElse(m["player"]?.jsonPrimitive?.intOrNull ?: -1) { "?" },
            mistake = m["mistake"]?.jsonPrimitive?.booleanOrNull == true,
            text = m["text"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
