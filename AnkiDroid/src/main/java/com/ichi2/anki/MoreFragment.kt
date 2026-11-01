// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.databinding.FragmentMoreBinding
import com.ichi2.anki.settings.Prefs
import com.ichi2.utils.VersionUtils
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Full-screen "More" destination in the bottom navigation bar.
 * Shows Settings and the version in a sectioned list.
 *
 * Tapping the version row six times offers to enable developer options on release builds —
 * the secret that used to live on the removed About page.
 */
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)

    private var secretClickCount = 0

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.moreVersionText.text = VersionUtils.pkgVersionName

        binding.moreSettings.setOnClickListener {
            navigate(PreferencesDestination.Root)
        }

        binding.moreVersion.setOnClickListener { onVersionTapped(it.context) }
    }

    private fun onVersionTapped(context: Context) {
        if (Prefs.isDeveloperOptionsEnabled) {
            return
        }
        if (++secretClickCount == SECRET_CLICK_LIMIT) {
            AlertDialog
                .Builder(context)
                .setTitle(R.string.dev_options_enabled_pref)
                .setIcon(R.drawable.ic_warning)
                .setMessage(R.string.dev_options_warning)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    Prefs.isDeveloperOptionsEnabled = true
                    showThemedToast(context, R.string.dev_options_enabled_msg, shortLength = true)
                }.setNegativeButton(R.string.dialog_cancel) { _, _ -> secretClickCount = 0 }
                .setCancelable(false)
                .show()
        }
    }

    private companion object {
        const val SECRET_CLICK_LIMIT = 6
    }
}
