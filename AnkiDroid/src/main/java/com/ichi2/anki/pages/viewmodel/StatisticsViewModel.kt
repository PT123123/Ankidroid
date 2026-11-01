// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import anki.stats.GraphPreferences
import anki.stats.GraphsResponse
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.pages.statistics.StatsRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Everything the Compose statistics screen renders. */
data class StatisticsUiState(
    val loading: Boolean = true,
    val deckName: String = "",
    val range: StatsRange = StatsRange.YEAR,
    val prefs: GraphPreferences? = null,
    /** The localized "Studied N cards in M today (x/card)." line; needs the collection. */
    val studiedTodayMessage: String = "",
    val data: GraphsResponse? = null,
)

/**
 * Loads the typed [GraphsResponse] the old WebView page fetched over the local HTTP bridge,
 * directly from the backend, and exposes it to the Compose screen.
 */
class StatisticsViewModel : ViewModel() {
    private val _state = MutableStateFlow(StatisticsUiState())
    val state: StateFlow<StatisticsUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun setRange(range: StatsRange) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        reload()
    }

    fun setDeck(name: String) {
        if (_state.value.deckName == name) return
        _state.update { it.copy(deckName = name) }
        reload()
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _state.value
                val search =
                    if (state.deckName.isEmpty()) {
                        withCol { "deck:\"${decks.current().name}\"" }
                    } else {
                        "deck:\"${state.deckName}\""
                    }
                val (response, prefs, deckName, todayMessage) =
                    withCol {
                        val graphs = backend.graphs(search, state.range.days)
                        val preferences = backend.getGraphPreferences()
                        val name =
                            if (state.deckName.isEmpty()) {
                                decks.current().name
                            } else {
                                state.deckName
                            }
                        val message =
                            if (graphs.today.answerCount > 0) {
                                backend.studiedTodayMessage(graphs.today.answerCount, graphs.today.answerMillis / 1000.0)
                            } else {
                                ""
                            }
                        StatsPayload(graphs, preferences, name, message)
                    }
                _state.update {
                    it.copy(
                        loading = false,
                        deckName = deckName,
                        prefs = prefs,
                        studiedTodayMessage = todayMessage,
                        data = response,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "StatisticsViewModel: failed to load graphs")
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private data class StatsPayload(
        val response: GraphsResponse,
        val prefs: GraphPreferences,
        val deckName: String,
        val todayMessage: String,
    )
}
