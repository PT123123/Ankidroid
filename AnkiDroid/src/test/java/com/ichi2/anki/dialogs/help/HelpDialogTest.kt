// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.dialogs.help

import android.os.Bundle
import androidx.fragment.app.testing.launchFragment
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class HelpDialogTest {
    private lateinit var mockActionDispatcher: HelpItemActionsDispatcher

    @Before
    fun setUp() {
        mockActionDispatcher = mockk(relaxed = true)
    }

    @Test
    fun `Help contains the expected items at start`() {
        val expectedHelpItems =
            listOf(
                R.string.help_item_anki_manual,
                R.string.help_title_get_help,
                R.string.help_title_community,
                R.string.help_title_privacy,
            )
        val actualHelpItems =
            HelpDialog.newHelpInstance().requireArgsHelpEntries().map { it.titleResId }
        assertEquals(
            expectedHelpItems,
            actualHelpItems,
            "Unexpected help menu item at start",
        )
    }

    @Test
    fun `Menu items IDs are consistent`() {
        // main help menu items have unique ids
        assertEquals(
            mainHelpMenuItems.size,
            mainHelpMenuItems.map { it.id }.toSet().size,
            "Main help menu has items with the same id",
        )
        // help menu child items have a non-null valid parent id
        val allFoundParentIds = childHelpMenuItems.map { it.parentId }
        assertFalse(
            allFoundParentIds
                .any { it == null || !mainHelpMenuItems.map { entry -> entry.id }.contains(it) },
            "Help item has an invalid parentId",
        )
    }

    @Test
    fun `Help menu handles submenus correctly`() {
        // simulate a help menu start
        launchFragment<HelpDialog>(
            fragmentArgs =
                Bundle().apply {
                    putInt(HelpDialog.ARG_MENU_TITLE, R.string.help)
                    putParcelableArray(ARG_MENU_ITEMS, mainHelpMenuItems)
                },
            themeResId = R.style.Theme_Light,
            initialState = Lifecycle.State.RESUMED,
        ).onFragment {
            onView(withText(R.string.help_title_community)).inRoot(isDialog()).perform(click())
            // check that the expected six children are shown
            onView(withText(R.string.help_item_discord))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_reddit))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_facebook))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_mailing_list))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_twitter))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_anki_forums))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            // press back
            pressBackUnconditionally()
            // check that the expected initial help items are shown
            onView(withText(R.string.help_title_community))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_title_get_help))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_title_privacy))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.help_item_anki_manual))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun `Help menu item executes expected action on menu item selection`() {
        // simulate a help menu start
        launchFragment<HelpDialog>(
            fragmentArgs =
                Bundle().apply {
                    putInt(HelpDialog.ARG_MENU_TITLE, R.string.help)
                    putParcelableArray(ARG_MENU_ITEMS, mainHelpMenuItems)
                },
            themeResId = R.style.Theme_Light,
            initialState = Lifecycle.State.RESUMED,
        ).onFragment { fragment ->
            fragment.actionsDispatcher = mockActionDispatcher
            onView(withText(R.string.help_item_anki_manual)).inRoot(isDialog()).perform(click())
            verify(exactly = 1) { mockActionDispatcher.onOpenUrlResource(R.string.link_anki_manual) }
            // start the second submenu; its "report a bug" entry is gone from the fork
            onView(withText(R.string.help_title_get_help)).inRoot(isDialog()).perform(click())
            onView(withText(R.string.help_item_mailing_list)).inRoot(isDialog()).perform(click())
            verify(exactly = 1) { mockActionDispatcher.onOpenUrlResource(R.string.link_forum) }
        }
    }
}
