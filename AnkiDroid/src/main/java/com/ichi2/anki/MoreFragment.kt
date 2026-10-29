// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.ichi2.anki.common.analytics.Analytics
import com.ichi2.anki.common.analytics.AnalyticsEvent.LinkClicked
import com.ichi2.anki.common.analytics.LinkAction
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.databinding.FragmentMoreBinding
import com.ichi2.anki.dialogs.help.ARG_MENU_ITEMS
import com.ichi2.anki.dialogs.help.HelpDialog
import com.ichi2.anki.dialogs.help.childHelpMenuItems
import com.ichi2.anki.dialogs.help.mainHelpMenuItems
import com.ichi2.anki.preferences.MySettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.utils.ext.showDialogFragment
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Full-screen "More" destination in the bottom navigation bar.
 * Shows the fork's "My settings" screen, Settings, and help items in a sectioned list.
 *
 * Help items open the existing [HelpDialog] focused on their subsection.
 */
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.moreMySettings.setOnClickListener {
            startActivity(PreferencesActivity.getIntent(requireContext(), MySettingsFragment::class))
        }

        binding.moreSettings.setOnClickListener {
            navigate(PreferencesDestination.Root)
        }

        // Help section: each opens HelpDialog with the relevant sub-items
        binding.moreHelpManual.setOnClickListener {
            Analytics.send(LinkClicked(LinkAction.OPENED_ANKI_MANUAL))
            openUrl(getString(R.string.link_anki_manual))
        }
        binding.moreHelpGetHelp.setOnClickListener {
            Analytics.send(LinkClicked(LinkAction.OPENED_GET_HELP))
            openHelpSection(R.string.help_title_get_help)
        }
        binding.moreHelpCommunity.setOnClickListener {
            Analytics.send(LinkClicked(LinkAction.OPENED_COMMUNITY))
            openHelpSection(R.string.help_title_community)
        }
        binding.moreHelpPrivacy.setOnClickListener {
            Analytics.send(LinkClicked(LinkAction.OPENED_PRIVACY))
            openHelpSection(R.string.help_title_privacy)
        }
    }

    /**
     * Opens [HelpDialog] showing the children of the help section matching [titleRes].
     * The parent ID is derived from [mainHelpMenuItems] to avoid hardcoded coupling.
     */
    private fun openHelpSection(
        @StringRes titleRes: Int,
    ) {
        val sectionId = mainHelpMenuItems.single { it.titleResId == titleRes }.id
        val children = childHelpMenuItems.filter { it.parentId == sectionId }
        val dialog =
            HelpDialog().apply {
                arguments =
                    Bundle().apply {
                        putInt(HelpDialog.ARG_MENU_TITLE, titleRes)
                        putParcelableArray(ARG_MENU_ITEMS, children.toTypedArray())
                    }
            }
        requireActivity().showDialogFragment(dialog)
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
