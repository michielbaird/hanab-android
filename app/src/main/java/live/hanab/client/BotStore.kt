package live.hanab.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings last used to invite a bot (such as will-hanabi-bot's scala-bot). Which users are bots is the server's to say
 * (it marks the accounts that sign in as one), so nothing is marked by hand. A bot is asked to join by private message
 * ("/join"), and its conventions are set for the table with "/setall" in the table chat: a bot can be at several tables at
 * once, and that sets them for this game only.
 */
object BotStore {
    private lateinit var prefs: SharedPreferences

    /** Convention sent with "/setall" when inviting (remembered between invites). */
    var convention by mutableStateOf("HGroup11"); private set

    /** Whether to play without the human-like delay (remembered between invites). */
    var fast by mutableStateOf(false); private set

    /** Convention names the bot understands: Reactor1, RefSieve and HGroup1..HGroup12. */
    val conventions = listOf("Reactor1", "RefSieve") + (1..12).map { "HGroup$it" }

    fun load(context: Context) {
        prefs = context.getSharedPreferences("hanab_bots", Context.MODE_PRIVATE)
        // Bots used to be marked by hand and kept here; the server says which users are bots now.
        prefs.edit().remove("bots").apply()
        convention = prefs.getString("convention", "HGroup11") ?: "HGroup11"
        fast = prefs.getBoolean("fast", false)
    }

    fun remember(convention: String, fast: Boolean) {
        this.convention = convention
        this.fast = fast
        prefs.edit().putString("convention", convention).putBoolean("fast", fast).apply()
    }

}

/** True if a bot's "/settings" reply says fast mode is on, e.g. "Currently playing with HGroup11 conventions. [fast mode]". */
fun replyShowsFastMode(text: String) = text.contains("[fast mode]")
