// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.ui.internationalization.sentenceCase

/**
 * The maintenance actions which used to sit in the deck picker's toolbar overflow menu.
 *
 * The fork moved them to the "My settings" screen ([com.ichi2.anki.preferences.MySettingsFragment]),
 * keeping the main screen free of a menu. Selecting one returns to [DeckPicker] with
 * [DeckPicker.EXTRA_DECK_TOOL] set to [name], where [DeckPicker.runDeckTool] performs it.
 */
enum class DeckTool(
    @DrawableRes val iconRes: Int,
    /** 0 when the label only exists in the backend translations. */
    @StringRes val localTitleRes: Int = 0,
) {
    SYNC(iconRes = R.drawable.ic_sync, localTitleRes = R.string.button_sync),
    CHECK_DATABASE(iconRes = R.drawable.ic_build_black_24),
    CHECK_MEDIA(iconRes = R.drawable.ic_image),
    EMPTY_CARDS(iconRes = R.drawable.ic_remove),
    CREATE_BACKUP(iconRes = R.drawable.ic_baseline_backup_24, localTitleRes = R.string.menu_create_backup),
    RESTORE_BACKUP(iconRes = R.drawable.ic_backup_restore, localTitleRes = R.string.backup_restore),
    NOTE_TYPES(iconRes = R.drawable.ic_notes, localTitleRes = R.string.model_browser_label),
    IMPORT(iconRes = R.drawable.ic_file_download_white),
    EXPORT(iconRes = R.drawable.ic_export_file),
    ;

    /** The label of the action, matching what the old overflow menu showed. */
    fun title(context: Context): String =
        if (localTitleRes != 0) {
            context.getString(localTitleRes)
        } else {
            with(context) {
                when (this@DeckTool) {
                    CHECK_DATABASE -> TR.sentenceCase.checkDatabase
                    CHECK_MEDIA -> TR.sentenceCase.checkMediaAction
                    EMPTY_CARDS -> TR.sentenceCase.emptyCards
                    IMPORT -> TR.actionsImport()
                    EXPORT -> TR.actionsExport()
                    else -> error("no label for ${this@DeckTool}")
                }
            }
        }
}
