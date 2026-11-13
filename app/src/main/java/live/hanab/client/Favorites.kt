package live.hanab.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf

/**
 * The users (bots included) that you marked as favorites, kept on this phone. They come first in the Users tab, and the
 * favorite bots first when you invite bots to a table.
 */
object Favorites {
    private lateinit var prefs: SharedPreferences

    private val names = mutableStateListOf<String>()

    fun load(context: Context) {
        prefs = context.getSharedPreferences("hanab_favorites", Context.MODE_PRIVATE)
        names.clear()
        names.addAll(prefs.getStringSet("names", emptySet()).orEmpty().sortedBy { it.lowercase() })
    }

    /** Reads the list as Compose state, so what shows it updates when a favorite is added or removed. */
    fun isFavorite(name: String) = names.any { it.equals(name, ignoreCase = true) }

    fun toggle(name: String) {
        if (isFavorite(name)) names.removeAll { it.equals(name, ignoreCase = true) } else names.add(name)
        prefs.edit().putStringSet("names", names.toSet()).apply()
    }
}
