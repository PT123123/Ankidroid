/*
 * Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.pages

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ichi2.anki.R
import com.ichi2.anki.databinding.PageCardInfoBinding
import com.ichi2.anki.pages.cardinfo.CardInfoScreen
import com.ichi2.anki.pages.cardinfo.CardInfoViewModel
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding

/**
 * The card info screen, rendered natively in Compose. It used to be the backend's SvelteKit
 * "card-info" route in a WebView; the same [anki.stats.CardStatsResponse] is now fetched straight
 * from the backend by [CardInfoViewModel].
 */
class CardInfoFragment : Fragment(R.layout.page_card_info) {
    private val binding by viewBinding(PageCardInfoBinding::bind)
    private val viewModel by viewModels<CardInfoViewModel>()

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

        viewModel.load(requireArguments().getLong(KEY_CARD_ID))

        binding.composeView.setContent {
            AnkiDroidTheme {
                val data by viewModel.data.collectAsStateWithLifecycle()
                CardInfoScreen(data = data)
            }
        }
    }

    companion object {
        const val KEY_CARD_ID = "cardId"
        const val KEY_TITLE = "title"
    }
}
