// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.settings.enums

import com.ichi2.anki.R

/**
 * How the study screen draws the card and the answer buttons.
 *
 * The user picks one of these in "My settings", and can switch between them from the chip
 * in the corner of the card. [com.ichi2.anki.cardviewer.CardStyle] holds what each of them
 * actually changes.
 *
 * The stored value is the one in `R.string.card_style_*_value`, so [PrefEnum.entryResId]
 * points at the *value* string, not at the label.
 */
enum class CardStylePreset(
    override val entryResId: Int,
) : PrefEnum {
    /** No card frame, no padding: the card fills the screen, as the desktop renders it. */
    CLASSIC(R.string.card_style_classic_value),

    /** A rounded sheet with a rim and a shadow, the theme's type, a divider before the answer. */
    MODERN(R.string.card_style_modern_value),

    /** A warm paper sheet with a serif type and wide margins. */
    PAPER(R.string.card_style_paper_value),

    /** No frame, tight type, outlined answer buttons. */
    MINIMAL(R.string.card_style_minimal_value),
    ;

    companion object {
        /** The preset to use when the stored value is unknown. */
        val DEFAULT = MODERN
    }
}
