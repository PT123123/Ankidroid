// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.preferences

import androidx.fragment.app.commitNow
import androidx.preference.PreferenceCategory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.DeckTool
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.launchActivity
import com.ichi2.anki.preferences.PreferenceTestUtils.getAttrFromXml
import com.ichi2.anki.preferences.PreferenceTestUtils.resValue
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fork's additions live inside AnkiDroid's own settings: card rendering in Appearance, LAN
 * sync under Sync, the profile switcher on the root page, and the deck tools gathered under one
 * "Backup & import/export" entry.
 */
@RunWith(AndroidJUnit4::class)
class MergedSettingsTest : RobolectricTest() {
    private fun keysOf(xml: Int) = getAttrFromXml(targetContext, xml, "key").map { it.resValue(targetContext) }

    @Test
    fun `the deck picker toolbar keeps nothing but the search field`() {
        // 'getXml' on a compiled resource reports resolved ids like "pkg:id/action_undo".
        val ids =
            getAttrFromXml(targetContext, R.menu.deck_picker, "id").map {
                targetContext.resources.getResourceName(it.removePrefix("@").toInt()).substringAfter('/')
            }
        assertThat(ids, equalTo(listOf("deck_picker_action_filter", "action_undo")))
    }

    @Test
    fun `the fork additions live in the upstream screens`() {
        val appearance = keysOf(R.xml.preferences_appearance)
        assertThat(appearance.contains(targetContext.getString(R.string.theme_color_key)), equalTo(true))
        assertThat(appearance.contains(targetContext.getString(R.string.card_style_key)), equalTo(true))
        assertThat(appearance.contains(targetContext.getString(R.string.pref_stock_card_css_key)), equalTo(true))

        assertThat(
            keysOf(R.xml.preferences_sync).contains(targetContext.getString(R.string.pref_lansync_screen_key)),
            equalTo(true),
        )
        val headers = keysOf(R.xml.preference_headers)
        assertThat(headers.contains(targetContext.getString(R.string.pref_backup_transfer_screen_key)), equalTo(true))
        assertThat(headers.contains(targetContext.getString(R.string.pref_switch_profile_screen_key)), equalTo(true))
    }

    @Test
    fun `the old my settings screen is gone`() {
        val headers = keysOf(R.xml.preference_headers)
        assertThat(headers.contains("mySettingsScreen"), equalTo(false))
    }

    @Test
    fun `backup transfer offers one entry per deck tool in its three groups`() {
        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = BackupTransferFragment()
                activity.supportFragmentManager.commitNow { add(R.id.settings_container, fragment) }
                val groups =
                    listOf(
                        R.string.backup_transfer_sync_category_key to
                            listOf(
                                DeckTool.SYNC,
                                DeckTool.CREATE_BACKUP,
                                DeckTool.RESTORE_BACKUP,
                            ),
                        R.string.backup_transfer_import_export_category_key to listOf(DeckTool.IMPORT, DeckTool.EXPORT),
                        R.string.backup_transfer_maintenance_category_key to
                            listOf(DeckTool.CHECK_DATABASE, DeckTool.CHECK_MEDIA, DeckTool.EMPTY_CARDS, DeckTool.NOTE_TYPES),
                    )
                val allTitles = mutableListOf<String>()
                val expectedTitles = mutableListOf<String>()
                for ((key, tools) in groups) {
                    val category = fragment.requirePreference<PreferenceCategory>(key)
                    assertThat(category.preferenceCount, equalTo(tools.size))
                    allTitles += (0 until category.preferenceCount).map { category.getPreference(it).title.toString() }
                    expectedTitles += tools.map { it.title(activity) }
                }
                assertThat(allTitles, equalTo(expectedTitles))
            }
        }
    }
}
