package live.hanab.client.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One finished game in a player's history, as the site's `/api/v1/history/<name>` lists it. */
@Serializable
data class HistoryRow(
    val id: Int = 0,
    @SerialName("num_players") val numPlayers: Int = 0,
    val score: Int = 0,
    /** The site's variant id; see [ProfileApi.variantNames]. */
    val variant: Int = 0,
    /** Everyone at the table, comma-separated. */
    val users: String = "",
    /** Like "2026-10-25 - 01:44:52 UTC". */
    val datetime: String = "",
) {
    val date: String get() = datetime.substringBefore(" ")
    fun others(me: String): List<String> = users.split(", ").filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
}

@Serializable
private data class HistoryDto(
    @SerialName("total_rows") val totalRows: Int = 0,
    val rows: List<HistoryRow>? = null,
)

data class HistoryPage(val total: Int, val rows: List<HistoryRow>)

/** A player's best score in a variant at one table size; [max] is the most that variant allows. */
data class BestScore(val score: Int, val max: Int)

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
data class ProfilePage(val facts: List<Pair<String, String>>, val rows: List<VariantRow>)

/** The site says this (HTTP 404) when the name is not a player. */
class NoSuchPlayer : Exception("That player does not exist.")

/**
 * The public, read-only side of hanab.live (no sign-in needed). Game history comes from its JSON API; the stats
 * only exist in the website's server-rendered profile page, so [parseScores] reads that.
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
    suspend fun export(id: Long): String = withContext(Dispatchers.IO) { get("https://$BASE_HOST/export/$id", json = false) }

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

    /** The stats page of [name]. */
    suspend fun scores(name: String): ProfilePage = withContext(Dispatchers.IO) {
        val url = "https://$BASE_HOST/scores".toHttpUrl().newBuilder().addPathSegment(name).build()
        parseScores(get(url.toString(), json = false)) ?: error("The profile page was not in the expected format")
    }

    /**
     * One page ([HISTORY_PAGE_SIZE] games, counting from page 0) of [name]'s games, newest first, optionally only
     * those with this many players and/or this variant.
     */
    suspend fun history(name: String, page: Int, players: Int? = null, variantId: Int? = null): HistoryPage =
        withContext(Dispatchers.IO) {
            val url = "https://$BASE_HOST/api/v1/history".toHttpUrl().newBuilder()
                .addPathSegment(name)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("size", HISTORY_PAGE_SIZE.toString())
                .apply {
                    // The filter columns are numbered: 1 is the number of players, 3 the variant.
                    if (players != null) addQueryParameter("fcol[1]", players.toString())
                    if (variantId != null) addQueryParameter("fcol[3]", variantId.toString())
                }
                .build()
            val dto = HanabJson.decodeFromString<HistoryDto>(get(url.toString()))
            HistoryPage(dto.totalRows, dto.rows.orEmpty())
        }
}

private val FACT = Regex("""<li>\s*<span class="stat-description"\s*>(.*?)</span\s*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
private val ROW = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
private val CELL = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
private val TAG = Regex("""<[^>]+>""")
private val SCORE = Regex("""(\d+)\s*/\s*(\d+)""")

/** The text of a bit of HTML: tags gone, entities decoded, whitespace collapsed. */
private fun text(html: String): String =
    TAG.replace(html, " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()

/** Reads the website's profile page; null if it does not have the facts and the grid. */
fun parseScores(html: String): ProfilePage? {
    val table = html.indexOf("<table class=\"history\"")
    if (table < 0) return null
    val facts = FACT.findAll(html.substring(0, table)).map { text(it.groupValues[1]).trimEnd(':') to text(it.groupValues[2]) }.toList()
    val body = html.substring(table).substringAfter("<tbody>", "").substringBefore("</tbody>")
    val rows = ROW.findAll(body).mapNotNull { row ->
        val cells = CELL.findAll(row.groupValues[1]).map { text(it.groupValues[1]) }.toList()
        if (cells.size < 9) return@mapNotNull null
        val best = cells.subList(2, 7).map { c ->
            val m = SCORE.find(c) ?: return@mapNotNull null
            BestScore(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        VariantRow(
            variant = cells[0],
            games = cells[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null,
            best = best,
            average = cells[7].toDoubleOrNull(),
            strikeoutRate = cells[8].removeSuffix("%").toDoubleOrNull(),
        )
    }.toList()
    return ProfilePage(facts, rows)
}
