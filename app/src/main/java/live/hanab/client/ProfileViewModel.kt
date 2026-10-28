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
import live.hanab.client.net.GameLine
import live.hanab.client.net.NoSuchPlayer
import live.hanab.client.net.ProfilePage
import live.hanab.client.net.ProfileApi
import live.hanab.client.net.ProfileSource

enum class ProfileView { Stats, History }

/** Columns of the stats grid, in the order of its header (the player counts are 2 to 6). */
enum class GridColumn { Variant, Games, Best2, Best3, Best4, Best5, Best6, Average, Strikeouts }

data class ProfileUi(
    val name: String = "",
    val view: ProfileView = ProfileView.Stats,

    // The stats grid.
    val scores: ProfilePage? = null,
    val scoresLoading: Boolean = false,
    val scoresError: String? = null,
    val gridPage: Int = 0,
    /** Rows per page; 0 shows them all. */
    val gridPageSize: Int = 10,
    val sortColumn: GridColumn = GridColumn.Games,
    val sortDescending: Boolean = true,
    val gridFilter: String = "",

    // The game history, one page at a time.
    val historyPage: Int = 0,
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    /** Games matching the filters. */
    val historyTotal: Int = 0,
    val history: List<GameLine> = emptyList(),
    val players: Int? = null,
    val variant: String? = null,
    val variantNames: List<String> = emptyList(),
) {
    val historyPages: Int get() = maxOf(1, (historyTotal + ProfileApi.HISTORY_PAGE_SIZE - 1) / ProfileApi.HISTORY_PAGE_SIZE)
}

/** A player's stats grid and game history, from the website or from the games played offline. */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui

    private var source: ProfileSource? = null
    private var scoresJob: Job? = null
    private var historyJob: Job? = null

    /**
     * Shows [name] from [source]. Opening the same name again keeps what is on screen (the view, page and filters);
     * a source that changes while the app runs, like the offline games, is read again.
     */
    fun open(name: String, source: ProfileSource) {
        if (_ui.value.name == name) {
            if (source.changes) {
                this.source = source
                loadScores()
                if (_ui.value.history.isNotEmpty() || _ui.value.view == ProfileView.History) loadHistory(_ui.value.historyPage)
            }
            return
        }
        this.source = source
        scoresJob?.cancel()
        historyJob?.cancel()
        _ui.value = ProfileUi(name = name)
        loadScores()
    }

    fun show(view: ProfileView) {
        _ui.update { it.copy(view = view) }
        if (view == ProfileView.History && _ui.value.history.isEmpty() && !_ui.value.historyLoading) loadHistory(0)
    }

    // ---------------------------------------------------------------- stats grid

    fun retryScores() = loadScores()

    private fun loadScores() {
        val source = source ?: return
        scoresJob?.cancel()
        scoresJob = viewModelScope.launch {
            _ui.update { it.copy(scoresLoading = true, scoresError = null) }
            try {
                val page = source.scores()
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

    fun setVariant(variant: String?) {
        _ui.update { it.copy(variant = variant) }
        loadHistory(0)
    }

    fun retryHistory() = loadHistory(_ui.value.historyPage)

    private fun loadHistory(page: Int) {
        val source = source ?: return
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _ui.update { it.copy(historyLoading = true, historyError = null, historyPage = page) }
            try {
                if (_ui.value.variantNames.isEmpty()) {
                    val names = source.variantNames()
                    _ui.update { it.copy(variantNames = names) }
                }
                val s = _ui.value
                val result = source.games(page, s.players, s.variant)
                _ui.update { it.copy(historyTotal = result.total, history = result.lines) }
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
