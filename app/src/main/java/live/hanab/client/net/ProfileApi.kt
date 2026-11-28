package live.hanab.client.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import live.hanab.client.protocol.GameSummary
import live.hanab.client.protocol.GamePage
import live.hanab.client.protocol.ProtocolJson
import live.hanab.client.protocol.PlayerScores
import live.hanab.client.protocol.PublicPageTable
import live.hanab.client.protocol.PublicPageTableBestScores
import live.hanab.client.protocol.VariantBestScores
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One finished game in a player's history, as the site's `/api/v1/history/<name>` lists it (a [GameSummary]). */
data class HistoryRow(
    val id: Int = 0,
    val numPlayers: Int = 0,
    val score: Int = 0,
    /** The site's variant id; see [ProfileApi.variantNames]. */
    val variant: Int = 0,
    /** Everyone at the table. */
    val playerNames: List<String> = emptyList(),
    /** Like "2026-10-25T01:44:52.123Z". */
    val finishedAt: String = "",
) {
    val date: String get() = finishedAt.substringBefore("T")
    fun others(me: String): List<String> = playerNames.filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }

    companion object {
        fun of(g: GameSummary) = HistoryRow(g.gameId, g.playerNames.size, g.score, g.options.variantId, g.playerNames, g.finishedAt)
    }
}

data class HistoryPage(val total: Int, val rows: List<HistoryRow>)

/** A player's best score in a variant at one table size; [max] is the most that variant allows. */
data class BestScore(
    val score: Int,
    val max: Int,
    /** The score was made with an option (e.g. empty clues) that keeps it counting as a normal one. */
    val modified: Boolean = false,
)

/** One row of the stats grid: how a player did in one variant. */
data class VariantRow(
    val variant: String,
    /** Games played in the variant; 0 when the page does not say (see [ProfilePage.hasGames]). */
    val games: Int,
    /** Best scores for 2 to 6 players, in that order. */
    val best: List<BestScore>,
    /** Null when there is no finished game to average. */
    val average: Double?,
    val strikeoutRate: Double?,
)

/** What the website's profile page ("/scores/<name>") shows: the facts at the top and the grid of variants. */
data class ProfilePage(
    val facts: List<Pair<String, String>>,
    val rows: List<VariantRow>,
    /** False when the rows have no [VariantRow.average] or [VariantRow.strikeoutRate] to show (the website's page). */
    val hasAverages: Boolean = true,
    /** False when the rows have no [VariantRow.games] to show (the website's best scores page does not count them). */
    val hasGames: Boolean = true,
)

/** The site says this (HTTP 404) when the name is not a player. */
class NoSuchPlayer : Exception("That player does not exist.")

/**
 * The public, read-only side of hanab.live (no sign-in needed): game history and stats both come from its JSON API.
 */
object ProfileApi {
    const val HISTORY_PAGE_SIZE = 20

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var variants: Map<Int, String>? = null

    private fun get(url: String, json: Boolean = true): String {
        val request = Request.Builder().url(url).apply { if (json) header("Accept", "application/json") }.build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) throw NoSuchPlayer()
            if (!resp.isSuccessful) error("The server answered ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** The website's export of a finished game (players, deck and every action), as text. */
    suspend fun export(id: Long): String = withContext(Dispatchers.IO) { WireAdapter.legacyExport(get("https://$BASE_HOST/export/$id", json = false)) }

    /** Variant id to name, fetched once. */
    suspend fun variantNames(): Map<Int, String> {
        variants?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            HanabJson.parseToJsonElement(get("https://$BASE_HOST/api/v1/variants")).jsonObject
                .mapNotNull { (id, name) -> id.toIntOrNull()?.let { it to name.jsonPrimitive.content } }
                .toMap()
        }
        variants = loaded
        return loaded
    }

    /**
     * The stats of [name]: the facts from the site's JSON, and the best scores from the table that its best scores page
     * draws (the site has no JSON route for those). Without that table the facts are still shown.
     */
    suspend fun scores(name: String): ProfilePage = withContext(Dispatchers.IO) {
        val url = "https://$BASE_HOST/api/v1/scores".toHttpUrl().newBuilder().addPathSegment(name).build()
        val facts = ProtocolJson.decodeFromString(PlayerScores.serializer(), get(url.toString()))
        val pageUrl = "https://$BASE_HOST/best-scores".toHttpUrl().newBuilder().addPathSegment(name).build()
        val rows = runCatching { parseBestScores(get(pageUrl.toString(), json = false)) }.getOrNull().orEmpty()
        profileOf(facts, rows)
    }

    /**
     * One page ([HISTORY_PAGE_SIZE] games, counting from page 0) of [name]'s games, newest first, optionally only
     * those with this many players and/or this variant.
     */
    suspend fun history(name: String, page: Int, players: Int? = null, variantId: Int? = null): HistoryPage =
        withContext(Dispatchers.IO) {
            val url = "https://$BASE_HOST/api/v1/history".toHttpUrl().newBuilder()
                .addPathSegment(name)
                .addQueryParameter("offset", (page * HISTORY_PAGE_SIZE).toString())
                .addQueryParameter("limit", HISTORY_PAGE_SIZE.toString())
                .apply {
                    if (players != null) addQueryParameter("filter[numPlayers]", players.toString())
                    if (variantId != null) addQueryParameter("filter[variantId]", variantId.toString())
                }
                .build()
            val page = ProtocolJson.decodeFromString(GamePage.serializer(), get(url.toString()))
            HistoryPage(page.total.toInt(), page.games.map(HistoryRow::of))
        }
}

/** The grid of a best scores page: the JSON that `public-pages` draws into `#public-page-table`. */
private val TABLE = Regex("""id="public-page-table"[^>]*\sdata-table="([^"]*)"""")
private val ATTRIBUTE_ENTITY = Regex("""&(?:#(\d+)|#x([0-9a-fA-F]+)|(amp|lt|gt|quot|#39));""")

/** An attribute value as the HTML source spells it: entities decoded, since `data-table` holds JSON in one. */
private fun attributeValue(raw: String): String =
    ATTRIBUTE_ENTITY.replace(raw) { m ->
        val named = m.groupValues[3]
        when {
            named == "amp" -> "&"
            named == "lt" -> "<"
            named == "gt" -> ">"
            named == "quot" -> "\""
            named.isNotEmpty() -> "'"
            m.groupValues[1].isNotEmpty() -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> m.groupValues[2].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

/** The rows of a best scores page; null if it has no such table. */
fun parseBestScores(html: String): List<VariantRow>? {
    val json = TABLE.find(html)?.groupValues?.get(1) ?: return null
    val table = runCatching { ProtocolJson.decodeFromString(PublicPageTable.serializer(), attributeValue(json)) }.getOrNull()
    return (table as? PublicPageTableBestScores)?.rows?.map(::variantRow)
}

/** The facts the website's profile shows (from `/api/v1/scores/<name>`), with the [rows] of the best scores grid. */
fun profileOf(scores: PlayerScores, rows: List<VariantRow> = emptyList()): ProfilePage {
    val s = scores.statistics
    val facts = buildList {
        add("Date joined" to s.dateJoined.substringBefore("T"))
        add("Games played" to "${s.numGames} (${s.numGamesNormal} finished normally)")
        add("Time spent playing" to durationText(s.timePlayedSeconds * 1000))
        if (s.timeReviewedSeconds > 0) add("Time spent reviewing" to durationText(s.timeReviewedSeconds * 1000))
        s.bestStreak?.let { add("Longest streak" to "${it.length} max scores in a row (${it.numPlayers} players)") }
        add("Total max scores" to "${scores.maxScores.total} (${"%.1f".format(java.util.Locale.US, scores.maxScores.percentage)}%)")
        scores.maxScores.perType.forEachIndexed { i, c ->
            add("Max scores at ${i + 2} players" to "${c.total} (${"%.1f".format(java.util.Locale.US, c.percentage)}%)")
        }
        val done = scores.achievements.count { it.dateUnlocked != null }
        if (scores.achievements.isNotEmpty()) add("Achievements" to "$done of ${scores.achievements.size}")
    }
    return ProfilePage(facts, rows, hasAverages = false, hasGames = false)
}

/** One variant of the site's grid, with a row for each table size (2 to 6 players, in that order). */
private fun variantRow(v: VariantBestScores): VariantRow {
    val best = (2..6).map { n ->
        val b = v.scores.firstOrNull { it.numPlayers.toInt() == n }
        BestScore(b?.score?.toInt() ?: 0, v.maxScore.toInt(), modified = b?.modifier != null)
    }
    return VariantRow(v.variantName, 0, best, average = null, strikeoutRate = null)
}
