// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.dialogs.help

import androidx.annotation.StringRes
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.utils.IntentUtil

/**
 * An abstraction for the actions that can be done when the user selects a menu item in the
 * Help/Support menus. Used for testing.
 */
interface HelpItemActionsDispatcher {
    fun onOpenUrl(url: String)

    fun onOpenUrlResource(
        @StringRes url: Int,
    )

    fun onRate()
}

class AnkiActivityHelpActionsDispatcher(
    private val ankiActivity: AnkiActivity,
) : HelpItemActionsDispatcher {
    override fun onOpenUrl(url: String) {
        ankiActivity.openUrl(url)
    }

    override fun onOpenUrlResource(
        @StringRes url: Int,
    ) {
        ankiActivity.openUrl(url)
    }

    override fun onRate() {
        IntentUtil.tryOpenIntent(
            ankiActivity,
            AnkiDroidApp.getMarketIntent(ankiActivity),
        )
    }
}
