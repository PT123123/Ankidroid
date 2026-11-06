// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/** The state of the native card screen. */
sealed interface CardIrUiState {
    /** The IR is being read. */
    data object Loading : CardIrUiState

    /**
     * There is no usable IR.
     *
     * This is the fallback signal: the card has to be shown by the WebView
     * reviewer instead.
     */
    data class Unavailable(
        val reason: String,
    ) : CardIrUiState

    /** The card is ready to be drawn. */
    data class Ready(
        val card: CardIr,
        val irJson: ByteArray,
        val frame: IrFrame,
        val variant: String,
        val fonts: CardIrFonts,
        val mediaDirectory: File?,
    ) : CardIrUiState
}

/**
 * The native card screen.
 *
 * It draws the Card IR with Compose; no WebView is involved. When the IR is
 * missing the screen says so instead of pretending the card is empty.
 */
@Composable
fun CardIrScreen(
    state: CardIrUiState,
    modifier: Modifier = Modifier,
    onAction: (IrNode) -> Unit = {},
) {
    when (state) {
        is CardIrUiState.Loading ->
            Column(
                modifier = modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

        is CardIrUiState.Unavailable ->
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

        is CardIrUiState.Ready ->
            CardIrView(
                frame = state.frame,
                irJson = state.irJson,
                variant = state.variant,
                modifier = modifier,
                fonts = state.fonts,
                mediaDirectory = state.mediaDirectory,
                onAction = onAction,
            )
    }
}
