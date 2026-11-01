// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.preferences

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.DeckTool
import com.ichi2.anki.R

/**
 * The collection actions which used to sit in the deck picker's toolbar, gathered under one
 * settings entry: sync and backups, import and export, and the maintenance checks. Selecting an
 * entry returns to [DeckPicker] with [DeckPicker.EXTRA_DECK_TOOL] set, where
 * [DeckPicker.runDeckTool] performs it.
 */
class BackupTransferFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_backup_transfer
    override val analyticsScreenNameConstant: String
        get() = "prefs.backup_transfer"

    override fun initSubscreen() {
        val groups =
            mapOf(
                R.string.backup_transfer_sync_category_key to syncAndBackups,
                R.string.backup_transfer_import_export_category_key to importAndExport,
                R.string.backup_transfer_maintenance_category_key to maintenance,
            )
        for ((categoryKey, tools) in groups) {
            val category = requirePreference<PreferenceCategory>(categoryKey)
            for (tool in tools) {
                category.addPreference(toolPreference(tool))
            }
        }
    }

    private fun toolPreference(tool: DeckTool): Preference =
        Preference(requireContext()).apply {
            setTitle(tool.title(requireContext()))
            setIcon(tool.iconRes)
            isPersistent = false
            setOnPreferenceClickListener {
                startActivity(DeckPicker.getIntentForTool(requireContext(), tool))
                requireActivity().finish()
                true
            }
        }

    companion object {
        private val syncAndBackups =
            listOf(DeckTool.SYNC, DeckTool.CREATE_BACKUP, DeckTool.RESTORE_BACKUP)
        private val importAndExport =
            listOf(DeckTool.IMPORT, DeckTool.EXPORT)
        private val maintenance =
            listOf(DeckTool.CHECK_DATABASE, DeckTool.CHECK_MEDIA, DeckTool.EMPTY_CARDS, DeckTool.NOTE_TYPES)
    }
}
