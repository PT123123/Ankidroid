// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.preferences

import androidx.core.app.ActivityCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.DeckTool
import com.ichi2.anki.R
import com.ichi2.anki.settings.Prefs

/**
 * The fork's settings page: the additions on top of upstream (theme color, profiles, LAN sync)
 * and the actions which used to be in the deck picker toolbar. Keeping them here leaves
 * [HeaderFragment] with AnkiDroid's own settings only.
 */
class MySettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_my_settings
    override val analyticsScreenNameConstant: String
        get() = "prefs.my_settings"

    override fun initSubscreen() {
        requirePreference<Preference>(R.string.pref_switch_profile_screen_key)
            .isVisible = Prefs.switchProfileEnabled

        val themeColorPref = requirePreference<ListPreference>(R.string.theme_color_key)
        themeColorPref.setOnPreferenceChangeListener { newValue ->
            if (newValue != themeColorPref.value) {
                ActivityCompat.recreate(requireActivity())
            }
        }

        val tools = requirePreference<PreferenceCategory>(R.string.pref_my_settings_tools_category_key)
        for (tool in DeckTool.entries) {
            tools.addPreference(
                Preference(requireContext()).apply {
                    setTitle(tool.title(requireContext()))
                    setIcon(tool.iconRes)
                    isPersistent = false
                    setOnPreferenceClickListener {
                        startActivity(DeckPicker.getIntentForTool(requireContext(), tool))
                        requireActivity().finish()
                        true
                    }
                },
            )
        }
    }
}
