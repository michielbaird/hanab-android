package live.hanab.client

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import live.hanab.client.net.HanabJson
import java.io.File

@Serializable
private data class SavedAnalysis(val convention: String, val comments: List<AnalysisComment>)

/**
 * Finished analyses, one small file per game, so closing the analysis (or the app) does not lose a result that took
 * a minute to work out. The newest analysis of a game replaces the last.
 */
object AnalysisStore {
    private fun file(context: Context, online: Boolean, id: Long) =
        File(File(context.filesDir, "analysis").apply { mkdirs() }, "${if (online) "web" else "offline"}-$id.json")

    fun save(context: Context, request: AnalysisRequest, convention: String, comments: List<AnalysisComment>) {
        runCatching {
            file(context, request.online, request.id).writeText(HanabJson.encodeToString(SavedAnalysis(convention, comments)))
        }
    }

    /** The kept analysis of this game, if there is one. */
    fun load(context: Context, request: AnalysisRequest): AnalysisUi.Done? = runCatching {
        val f = file(context, request.online, request.id)
        if (!f.exists()) return null
        val saved = HanabJson.decodeFromString<SavedAnalysis>(f.readText())
        AnalysisUi.Done(request, saved.convention, saved.comments)
    }.getOrNull()

    fun exists(context: Context, online: Boolean, id: Long): Boolean = file(context, online, id).exists()
}
