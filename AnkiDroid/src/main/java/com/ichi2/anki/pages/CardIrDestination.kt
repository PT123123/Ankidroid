// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.pages.cardir.CardIrFragment

/**
 * Builds the [Intent] that opens the native card screen for a card.
 *
 * The screen shows the Card IR of the card; when there is none it reports
 * that, so the caller can fall back to the WebView reviewer.
 */
fun cardIrIntent(
    context: Context,
    cardId: Long,
    title: String? = null,
): Intent {
    val arguments =
        Bundle().apply {
            putString(CardIrFragment.KEY_TITLE, title)
            putLong(CardIrFragment.KEY_CARD_ID, cardId)
        }
    return SingleFragmentActivity.getIntent(
        context,
        fragmentClass = CardIrFragment::class,
        arguments = arguments,
    )
}
