// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.R
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.databinding.PageCardIrBinding
import com.ichi2.anki.pages.cardir.CardIrUiState.Ready
import com.ichi2.anki.pages.cardir.CardIrUiState.Unavailable
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The native card screen, rendered from the Card IR.
 *
 * This is a **new** screen: it does not replace the WebView reviewer and it
 * does not touch `AbstractFlashcardViewer`. It exists so that a card that was
 * pre-compiled on the desktop can be drawn natively on Android.
 *
 * The IR is read from the collection's sidecar (`card_ir/cards/<id>.json`).
 * When it is missing, damaged or compiled by an incompatible version, the
 * screen reports that and the caller is expected to fall back to the WebView
 * reviewer.
 */
class CardIrFragment : Fragment(R.layout.page_card_ir) {
    private val binding by viewBinding(PageCardIrBinding::bind)
    private val viewModel by viewModels<CardIrViewModel>()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val title = requireArguments().getString(KEY_TITLE)
        if (title != null) {
            binding.toolbar.setTitle(title)
        }
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        viewModel.load(requireArguments().getLong(KEY_CARD_ID), isDarkTheme())

        binding.composeView.setContent {
            AnkiDroidTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                CardIrScreen(state = state)
            }
        }
    }

    /** Whether the system is in dark mode. */
    private fun isDarkTheme(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val KEY_CARD_ID = "cardId"
        const val KEY_TITLE = "title"
    }
}

/** Loads the Card IR of a card. */
class CardIrViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<CardIrUiState>(CardIrUiState.Loading)
    val state: StateFlow<CardIrUiState> = _state.asStateFlow()

    fun load(
        cardId: Long,
        darkTheme: Boolean,
    ) {
        if (_state.value !is CardIrUiState.Loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = read(cardId, darkTheme)
        }
    }

    private fun read(
        cardId: Long,
        darkTheme: Boolean,
    ): CardIrUiState {
        val context = getApplication<Application>()
        val collectionDirectory =
            try {
                CollectionHelper.getCurrentAnkiDroidDirectory(context)
            } catch (e: Exception) {
                return Unavailable("The collection path is not set")
            }
        val card =
            CardIrStore.read(collectionDirectory, cardId)
                ?: return Unavailable("This card has no Card IR yet")

        val mediaDirectory =
            try {
                CollectionHelper.getMediaDirectory(context)
            } catch (e: Exception) {
                null
            }

        // The variant is chosen against the width the card will be drawn at.
        // The exact width is not known before the first layout, so the closest
        // variant is used and the canvas scales it.
        val picked =
            CardIrParser.selectFrame(card, "question", darkTheme, 0)
                ?: return Unavailable("This card IR has no frame")
        val (frame, variant) = picked

        return Ready(
            card = card,
            frame = frame,
            variant = variant,
            fonts = CardIrFonts.load(mediaDirectory),
            mediaDirectory = mediaDirectory,
        )
    }
}
