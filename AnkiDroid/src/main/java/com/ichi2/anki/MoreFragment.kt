// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.databinding.FragmentMoreBinding
import com.ichi2.anki.dialogs.help.HelpDialog
import com.ichi2.anki.utils.ext.showDialogFragment
import com.ichi2.utils.VersionUtils
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Full-screen "More" destination in the bottom navigation bar.
 * Shows Settings and Help in a sectioned list.
 *
 * The help rows the fork dropped are not restored: one row opens upstream's [HelpDialog], which
 * is where the manual and the privacy policy live, so neither is left without an entry point.
 */
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.moreVersionText.text = VersionUtils.pkgVersionName

        binding.moreSettings.setOnClickListener {
            navigate(PreferencesDestination.Root)
        }

        binding.moreHelp.setOnClickListener {
            requireActivity().showDialogFragment(HelpDialog.newHelpInstance())
        }
    }
}
