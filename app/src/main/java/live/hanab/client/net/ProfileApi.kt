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
import live.hanab.client.protocol.VariantScores
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

    /** The stats of [name], from the site's JSON. */
    suspend fun scores(name: String): ProfilePage = withContext(Dispatchers.IO) {
        val url = "https://$BASE_HOST/api/v1/scores".toHttpUrl().newBuilder().addPathSegment(name).build()
        profileOf(ProtocolJson.decodeFromString(PlayerScores.serializer(), get(url.toString())))
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

/** What the website's profile page shows, from its JSON (`/api/v1/scores/<name>`). */
fun profileOf(scores: PlayerScores): ProfilePage {
    val s = scores.statistics
    val facts = buildList {
        add("Date joined" to s.dateJoined.substringBefore("T"))
        add("Games played" to "${s.numGamesTotal} (${s.numGamesNormal} normal, ${s.numGamesSpeedrun} speedrun, ${s.numGamesOther} other)")
        add("Time spent playing" to durationText(s.timePlayedSeconds.toLong() * 1000))
        if (s.timePlayedSpeedrunSeconds.toLong() > 0) add("Time spent in speedruns" to durationText(s.timePlayedSpeedrunSeconds.toLong() * 1000))
        if (s.timeReviewedSeconds.toLong() > 0) add("Time spent reviewing" to durationText(s.timeReviewedSeconds.toLong() * 1000))
        add("Total max scores" to "${scores.maxScores.total} (${"%.1f".format(java.util.Locale.US, scores.maxScores.percentage)}%)")
        scores.maxScores.perType.forEachIndexed { i, c ->
            add("Max scores at ${i + 2} players" to "${c.total} (${"%.1f".format(java.util.Locale.US, c.percentage)}%)")
        }
    }
    return ProfilePage(facts, scores.variants.map(::variantRow), hasAverages = false)
}

/** One variant of the site's grid, with a row for each table size (2 to 6 players, in that order). */
private fun variantRow(v: VariantScores): VariantRow {
    val best = (0 until 5).map { i ->
        val b = v.bestScores.getOrNull(i)
        BestScore(b?.score?.toInt() ?: 0, v.maxScore.toInt(), modified = b?.modifier != null)
    }
    return VariantRow(v.variantName, v.numGames.toInt(), best, average = null, strikeoutRate = null)
}
