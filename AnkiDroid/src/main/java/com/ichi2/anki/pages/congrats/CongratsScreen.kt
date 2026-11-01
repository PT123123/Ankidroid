// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.congrats

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.pages.CongratsUiState

/** The native "deck finished" screen: the message lines and the unbury / custom study actions. */
@Composable
fun CongratsScreen(
    state: CongratsUiState?,
    onUnbury: () -> Unit,
    onCustomStudy: () -> Unit,
) {
    if (state == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        state.messageLines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        state.deckDescription?.let { description ->
            DeckDescriptionText(html = description, modifier = Modifier.padding(top = 16.dp))
        }
        if (state.canUnbury || state.canCustomStudy) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canUnbury) {
                    FilledTonalButton(onClick = onUnbury) {
                        Text(TR.schedulingUnburyThem())
                    }
                }
                if (state.canCustomStudy) {
                    FilledTonalButton(onClick = onCustomStudy) {
                        Text(TR.schedulingCustomStudy())
                    }
                }
            }
        }
    }
}

/** Deck descriptions are free-form HTML from the collection; render them in a themed TextView. */
@Composable
private fun DeckDescriptionText(
    html: String,
    modifier: Modifier = Modifier,
) {
    val descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.text =
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            textView.setTextColor(descriptionColor)
            textView.textSize = 14f
        },
    )
}
