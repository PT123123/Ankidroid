// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeckToolTest : RobolectricTest() {
    @Test
    fun `every deck tool has a distinct label`() {
        val titles = DeckTool.entries.map { it.title(targetContext) }
        titles.forEach { assertThat(it.isNotBlank(), equalTo(true)) }
        assertThat(titles.distinct().size, equalTo(DeckTool.entries.size))
    }

    @Test
    fun `a deck tool intent asks the deck picker to run it`() {
        val intent = DeckPicker.getIntentForTool(targetContext, DeckTool.CHECK_MEDIA)
        assertThat(intent.component?.className, equalTo(DeckPicker::class.java.name))
        assertThat(intent.getStringExtra(DeckPicker.EXTRA_DECK_TOOL), equalTo(DeckTool.CHECK_MEDIA.name))
    }
}
