package live.hanab.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.hanab.client.net.HistoryRow
import live.hanab.client.net.NoSuchPlayer
import live.hanab.client.net.ProfileApi
import live.hanab.client.net.ProfilePage

enum class ProfileView { Stats, History }

/** Columns of the stats grid, in the order of its header (the player counts are 2 to 6). */
enum class GridColumn { Variant, Games, Best2, Best3, Best4, Best5, Best6, Average, Strikeouts }

data class ProfileUi(
    val name: String = "",
    val view: ProfileView = ProfileView.Stats,

    // The stats grid (from the website's profile page).
    val scores: ProfilePage? = null,
    val scoresLoading: Boolean = false,
    val scoresError: String? = null,
    val gridPage: Int = 0,
    /** Rows per page; 0 shows them all. */
    val gridPageSize: Int = 10,
    val sortColumn: GridColumn = GridColumn.Games,
    val sortDescending: Boolean = true,
    val gridFilter: String = "",

    // The game history (from the JSON API), one page at a time.
    val historyPage: Int = 0,
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    /** Games matching the filters. */
    val historyTotal: Int = 0,
    val history: List<HistoryRow> = emptyList(),
    val players: Int? = null,
    val variantId: Int? = null,
    val variantNames: Map<Int, String> = emptyMap(),
) {
    val historyPages: Int get() = maxOf(1, (historyTotal + ProfileApi.HISTORY_PAGE_SIZE - 1) / ProfileApi.HISTORY_PAGE_SIZE)
}

/** A player's stats grid and game history. */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui

    private var scoresJob: Job? = null
    private var historyJob: Job? = null

    fun open(name: String) {
        if (_ui.value.name.equals(name, ignoreCase = true)) return
        scoresJob?.cancel()
        historyJob?.cancel()
        _ui.value = ProfileUi(name = name, variantNames = _ui.value.variantNames)
        loadScores()
    }

    fun show(view: ProfileView) {
        _ui.update { it.copy(view = view) }
        if (view == ProfileView.History && _ui.value.history.isEmpty() && !_ui.value.historyLoading) loadHistory(0)
    }

    // ---------------------------------------------------------------- stats grid

    fun retryScores() = loadScores()

    private fun loadScores() {
        scoresJob?.cancel()
        scoresJob = viewModelScope.launch {
            _ui.update { it.copy(scoresLoading = true, scoresError = null) }
            try {
                val page = ProfileApi.scores(_ui.value.name)
                _ui.update { it.copy(scores = page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchPlayer) {
                _ui.update { it.copy(scoresError = "No player called ${it.name} on hanab.live.") }
            } catch (e: Exception) {
                _ui.update { it.copy(scoresError = "Could not load the profile: ${e.message}") }
            } finally {
                _ui.update { it.copy(scoresLoading = false) }
            }
        }
    }

    fun setGridPage(page: Int) = _ui.update { it.copy(gridPage = page) }

    fun setGridPageSize(size: Int) = _ui.update { it.copy(gridPageSize = size, gridPage = 0) }

    fun setGridFilter(text: String) = _ui.update { it.copy(gridFilter = text, gridPage = 0) }

    /** Sorts by [column]; choosing the same column again flips the direction. */
    fun sortBy(column: GridColumn) = _ui.update {
        val flip = it.sortColumn == column
        it.copy(
            sortColumn = column,
            // Names read best A to Z; everything else with the biggest first.
            sortDescending = if (flip) !it.sortDescending else column != GridColumn.Variant,
            gridPage = 0,
        )
    }

    // ---------------------------------------------------------------- game history

    fun setHistoryPage(page: Int) = loadHistory(page.coerceIn(0, _ui.value.historyPages - 1))

    fun setPlayers(players: Int?) {
        _ui.update { it.copy(players = players) }
        loadHistory(0)
    }

    fun setVariant(variantId: Int?) {
        _ui.update { it.copy(variantId = variantId) }
        loadHistory(0)
    }

    fun retryHistory() = loadHistory(_ui.value.historyPage)

    private fun loadHistory(page: Int) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _ui.update { it.copy(historyLoading = true, historyError = null, historyPage = page) }
            try {
                if (_ui.value.variantNames.isEmpty()) {
                    val names = ProfileApi.variantNames()
                    _ui.update { it.copy(variantNames = names) }
                }
                val s = _ui.value
                val result = ProfileApi.history(s.name, page, s.players, s.variantId)
                _ui.update { it.copy(historyTotal = result.total, history = result.rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(historyError = "Could not load the games: ${e.message}") }
            } finally {
                _ui.update { it.copy(historyLoading = false) }
            }
        }
    }
}
