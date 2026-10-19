package live.hanab.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bots (such as will-hanabi-bot's scala-bot) that you host, marked by hand in the Users list, and the
 * settings last used to invite one. Bots are controlled by private message, e.g. "/join", "/settings".
 */
object BotStore {
    private lateinit var prefs: SharedPreferences

    val bots = mutableStateListOf<String>()

    /** Convention sent with "/settings" when inviting (remembered between invites). */
    var convention by mutableStateOf("HGroup11"); private set

    /** Whether to play without the human-like delay (remembered between invites). */
    var fast by mutableStateOf(false); private set

    /** Convention names the bot understands: Reactor1, RefSieve and HGroup1..HGroup12. */
    val conventions = listOf("Reactor1", "RefSieve") + (1..12).map { "HGroup$it" }

    fun load(context: Context) {
        prefs = context.getSharedPreferences("hanab_bots", Context.MODE_PRIVATE)
        bots.clear()
        bots.addAll(prefs.getStringSet("bots", emptySet()).orEmpty().sortedBy { it.lowercase() })
        convention = prefs.getString("convention", "HGroup11") ?: "HGroup11"
        fast = prefs.getBoolean("fast", false)
    }

    fun isBot(name: String) = bots.any { it.equals(name, ignoreCase = true) }

    fun mark(name: String) {
        if (isBot(name)) return
        bots.add(name)
        bots.sortBy { it.lowercase() }
        save()
    }

    fun unmark(name: String) {
        bots.removeAll { it.equals(name, ignoreCase = true) }
        save()
    }

    fun remember(convention: String, fast: Boolean) {
        this.convention = convention
        this.fast = fast
        prefs.edit().putString("convention", convention).putBoolean("fast", fast).apply()
    }

    private fun save() {
        prefs.edit().putStringSet("bots", bots.toSet()).apply()
    }
}

/** True if a bot's "/settings" reply says fast mode is on, e.g. "Currently playing with HGroup11 conventions. [fast mode]". */
fun replyShowsFastMode(text: String) = text.contains("[fast mode]")
