// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.databinding.PageDeckOptionsBinding
import com.ichi2.anki.pages.deckoptions.DeckOptionsScreen
import com.ichi2.anki.pages.deckoptions.DeckOptionsViewModel
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch

/**
 * The deck options screen, rendered natively in Compose. It used to be the backend's SvelteKit
 * "deck-options" route in a WebView; the presets now come from `decks.getDeckConfigsForUpdate`
 * and are saved through `updateDeckConfigsRaw`, the same typed calls the page made over the
 * local HTTP bridge.
 *
 * Leaving with unsaved edits asks whether to save or discard, mirroring the web page.
 */
class DeckOptions : Fragment(R.layout.page_deck_options) {
    private val binding by viewBinding(PageDeckOptionsBinding::bind)
    private val viewModel by viewModels<DeckOptionsViewModel>()

    private val onBack =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmClose()
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val deckId = requireArguments().getLong(KEY_DECK_ID)

        binding.toolbar.setNavigationOnClickListener { confirmClose() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBack)

        binding.composeView.setContent {
            AnkiDroidTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DeckOptionsScreen(
                    state = state,
                    viewModel = viewModel,
                    onSaved = { requireActivity().finish() },
                )
            }
        }

        // the toolbar title follows the deck name once loaded
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { binding.toolbar.title = it.deckName }
        }

        viewModel.load(deckId)
    }

    private fun confirmClose() {
        if (!viewModel.state.value.dirty) {
            requireActivity().finish()
            return
        }
        AlertDialog
            .Builder(requireContext())
            .setTitle(TR.deckConfigPleaseSaveYourChangesFirst())
            .setPositiveButton(TR.deckConfigSaveButton()) { _, _ ->
                viewModel.save { requireActivity().finish() }
            }.setNegativeButton(R.string.dialog_cancel) { _, _ ->
                requireActivity().finish()
            }.show()
    }

    companion object {
        private const val KEY_DECK_ID = "deckId"

        fun getIntent(
            context: Context,
            deckId: Long,
        ): Intent =
            SingleFragmentActivity.getIntent(
                context,
                fragmentClass = DeckOptions::class,
                arguments = Bundle().apply { putLong(KEY_DECK_ID, deckId) },
            )
    }
}
