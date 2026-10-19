package live.hanab.client.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Server messages look like `command {json}` (the JSON part is optional). */
data class ServerMessage(val command: String, val data: JsonElement?)

val HanabJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

fun unpack(frame: String): ServerMessage? {
    val space = frame.indexOf(' ')
    val command = if (space < 0) frame else frame.substring(0, space)
    if (command.isEmpty()) return null
    val body = if (space < 0) "" else frame.substring(space + 1)
    val data = if (body.isEmpty()) null else runCatching { HanabJson.parseToJsonElement(body) }.getOrNull()
    return ServerMessage(command, data)
}

fun pack(command: String, data: JsonObject?): String =
    if (data == null) command else "$command $data"
