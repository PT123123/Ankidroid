// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki

import android.view.View
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BottomNavController.NavigationItem
import com.ichi2.anki.dialogs.help.HelpDialog
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.utils.ext.DIALOG_FRAGMENT_TAG
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Anki Plus ships the bottom navigation bar on the main screen: the deck list keeps the first tab,
 * while the browser, the statistics page (which carries the calendar heatmap) and the hub holding
 * the settings and help entries are reachable below it.
 */
@RunWith(AndroidJUnit4::class)
class HomeBottomNavigationTest : RobolectricTest() {
    @Test
    fun `the bottom navigation is on by default`() {
        assertThat(Prefs.devBottomNavEnabled, equalTo(true))
    }

    @Test
    fun `the deck picker shows four labelled tabs`() =
        deckPicker {
            val bottomNav = binding.bottomNavigation!!
            assertThat("the bar is shown", bottomNav.isVisible, equalTo(true))
            NavigationItem.entries.forEach { item ->
                val title =
                    bottomNav.menu
                        .findItem(item.id)
                        ?.title
                        ?.toString()
                assertThat("${item.name} has a label", title?.isNotBlank(), equalTo(true))
            }
            assertThat("the deck list starts on top", binding.deckPickerContentWrapper!!.isVisible, equalTo(true))
        }

    @Test
    fun `the more tab opens the settings`() =
        deckPicker {
            binding.bottomNavigation!!.selectedItemId = NavigationItem.MORE.id
            advanceRobolectricLooper()
            val moreView =
                supportFragmentManager
                    .findFragmentByTag(NavigationItem.MORE.tag)!!
                    .requireView()

            moreView.findViewById<View>(R.id.more_settings).performClick()

            val intent = shadowOf(this).nextStartedActivity
            assertThat(intent.component?.className, equalTo(PreferencesActivity::class.java.name))
        }

    /**
     * The fork's More page has no drawer and no overflow menu behind it, so the help rows the drawer
     * used to carry are gone with it - but the manual and the privacy policy have to stay reachable,
     * and [HelpDialog] is the only screen listing them.
     */
    @Test
    fun `the more tab keeps an entry to the help dialog`() =
        deckPicker {
            binding.bottomNavigation!!.selectedItemId = NavigationItem.MORE.id
            advanceRobolectricLooper()
            val moreView =
                supportFragmentManager
                    .findFragmentByTag(NavigationItem.MORE.tag)!!
                    .requireView()

            moreView.findViewById<View>(R.id.more_help).performClick()
            advanceRobolectricLooper()

            val dialog = supportFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG)
            assertThat("the help row opens the help dialog", dialog is HelpDialog, equalTo(true))
        }
}
