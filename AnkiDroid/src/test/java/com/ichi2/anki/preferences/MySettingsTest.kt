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

@RunWith(AndroidJUnit4::class)
class MySettingsTest : RobolectricTest() {
    private fun keysOf(xml: Int) = getAttrFromXml(targetContext, xml, "key").map { it.resValue(targetContext) }

    private val forkKeys
        get() =
            listOf(
                R.string.theme_color_key,
                R.string.pref_switch_profile_screen_key,
                R.string.pref_lansync_screen_key,
            ).map { targetContext.getString(it) }

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
    fun `upstream settings no longer hold the fork screens`() {
        assertThat(keysOf(R.xml.preference_headers).intersect(forkKeys.toSet()), equalTo(emptySet()))
        assertThat(keysOf(R.xml.preferences_appearance).contains(targetContext.getString(R.string.theme_color_key)), equalTo(false))
    }

    @Test
    fun `my settings holds the fork screens and the tools category`() {
        val keys = keysOf(R.xml.preferences_my_settings)
        assertThat(keys.containsAll(forkKeys), equalTo(true))
        assertThat(keys.contains(targetContext.getString(R.string.pref_my_settings_tools_category_key)), equalTo(true))
    }

    @Test
    fun `my settings offers one entry per deck tool`() {
        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = MySettingsFragment()
                activity.supportFragmentManager.commitNow { add(R.id.settings_container, fragment) }
                val tools = fragment.requirePreference<PreferenceCategory>(R.string.pref_my_settings_tools_category_key)
                assertThat(tools.preferenceCount, equalTo(DeckTool.entries.size))
                val titles = (0 until tools.preferenceCount).map { tools.getPreference(it).title.toString() }
                assertThat(titles, equalTo(DeckTool.entries.map { it.title(activity) }))
            }
        }
    }
}
