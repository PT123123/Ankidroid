// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.pages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.pages.deckoptions.DeckOptionsViewModel
import com.ichi2.anki.settings.Prefs
import com.ichi2.testutils.ext.clear
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for the [DeckOptions] view model behind the Compose screen */
@RunWith(AndroidJUnit4::class)
class DeckOptionsTest : RobolectricTest() {
    @After
    override fun tearDown() {
        super.tearDown()
        Prefs.clear()
    }

    @Test
    fun `loading fills the presets and selects the deck's config`() =
        runBlocking {
            val viewModel = DeckOptionsViewModel()
            viewModel.load(Consts.DEFAULT_DECK_ID)
            waitFor { !viewModel.state.value.loading }
            val state = viewModel.state.value
            assertTrue(state.presets.isNotEmpty())
            assertThat(state.currentConfig, notNullValue())
            assertThat(state.selectedPresetId, equalTo(state.currentConfig!!.id))
        }

    @Test
    fun `saving pushes the edited config to the collection`() =
        runBlocking {
            val viewModel = DeckOptionsViewModel()
            viewModel.load(Consts.DEFAULT_DECK_ID)
            waitFor { !viewModel.state.value.loading }
            val presetId = viewModel.state.value.selectedPresetId!!
            viewModel.setNewPerDay(15)
            var saved = false
            viewModel.save { saved = true }
            waitFor { saved }
            waitFor { !viewModel.state.value.dirty }
            val newPerDay =
                withCol {
                    decks
                        .getDeckConfigsForUpdate(Consts.DEFAULT_DECK_ID)
                        .allConfigList
                        .first { it.config.id == presetId }
                        .config.config.newPerDay
                }
            assertThat("newPerDay", newPerDay, equalTo(15))
        }

    /** The loads and saves run on Dispatchers.IO; poll until they settle. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }
}
