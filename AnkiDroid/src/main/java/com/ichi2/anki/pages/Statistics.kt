// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.R
import com.ichi2.anki.databinding.PageStatisticsBinding
import com.ichi2.anki.dialogs.registerDeckSelectedHandler
import com.ichi2.anki.dialogs.startDeckSelection
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.pages.statistics.StatisticsScreen
import com.ichi2.anki.pages.viewmodel.StatisticsViewModel
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch

/**
 * The statistics screen, rendered natively in Compose.
 *
 * The page used to be the backend's SvelteKit "graphs" route in a WebView; the same typed
 * [anki.stats.GraphsResponse] the page fetched over the local HTTP bridge is now read straight
 * from the backend by [StatisticsViewModel] and drawn with Canvas composables.
 *
 * The toolbar keeps the deck selector: picking a deck re-runs the graphs query against
 * `deck:"<name>"`, as the WebView page's search box used to.
 */
class Statistics : Fragment(R.layout.page_statistics) {
    private val binding by viewBinding(PageStatisticsBinding::bind)
    private val viewModel: StatisticsViewModel by viewModels()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Hide the back arrow when requested (e.g. hosted in bottom nav)
        if (arguments?.getBoolean(ARG_HIDE_BACK_BUTTON) == true) {
            binding.toolbar.navigationIcon = null
        }

        binding.deckName.setOnClickListener {
            startDeckSelection(allowAll = false, allowFiltered = false, skipEmptyDefault = true)
        }
        registerDeckSelectedHandler(action = ::onDeckSelected)

        // the toolbar's deck selector text follows the loaded state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { binding.deckName.text = it.deckName }
        }

        binding.composeView.setContent {
            AnkiDroidTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                StatisticsScreen(state = state, onRangeSelected = viewModel::setRange)
            }
        }
    }

    private fun onDeckSelected(deck: SelectableDeck?) {
        if (deck == null) return
        require(deck is SelectableDeck.Deck)
        viewModel.setDeck(deck.name)
    }

    companion object {
        const val ARG_HIDE_BACK_BUTTON = "hideBackButton"
    }
}
