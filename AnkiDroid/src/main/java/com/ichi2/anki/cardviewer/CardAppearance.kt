// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.content.SharedPreferences
import androidx.annotation.CheckResult
import com.ichi2.anki.reviewer.ReviewerCustomFonts
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.themes.Themes.currentTheme

/** Responsible for calculating CSS and element styles and modifying content on a flashcard  */
class CardAppearance(
    private val customFonts: ReviewerCustomFonts,
    private val cardZoom: Int,
    private val imageZoom: Int,
    private val centerVertically: Boolean,
    /**
     * The card style in use. It is read for every card, so that the quick switch in the card
     * takes effect on the next card without re-creating the viewer.
     */
    private val cardStyleProvider: () -> CardStyle,
    /** The colour the paper style paints its sheet with, resolved from the theme. */
    private val paperColour: String,
) {
    /** Below could be in a better abstraction.  */
    fun appendCssStyle(style: StringBuilder) {
        // Zoom cards
        if (cardZoom != 100) {
            style.append("body { zoom: ${cardZoom / 100.0} }\n")
        }

        // Zoom images
        if (imageZoom != 100) {
            style.append("img { zoom: ${imageZoom / 100.0} }\n")
        }
    }

    @CheckResult
    fun getCssClasses(): String {
        val cardClass = StringBuilder()
        if (centerVertically) {
            cardClass.append(" vertically_centered")
        }
        if (currentTheme is NightTheme) {
            // Enable the night-mode class
            cardClass.append(" night_mode nightMode")

            // Emit the dark_mode selector to allow dark theme overrides
            if (currentTheme == NightTheme.DARK) {
                cardClass.append(" ankidroid_dark_mode")
            }
        } else {
            // Emit the plain_mode selector to allow plain theme overrides
            if (currentTheme == DayTheme.PLAIN) {
                cardClass.append(" ankidroid_plain_mode")
            }
        }
        val bodyClass = cardStyleProvider().bodyClass
        if (bodyClass.isNotEmpty()) {
            cardClass.append(" ").append(bodyClass)
        }
        return cardClass.toString()
    }

    val style: String
        get() {
            val style = StringBuilder()
            customFonts.updateCssStyle(style)
            appendCssStyle(style)
            // the rules of every card style, so that switching styles is a class swap
            style.append(CardStyle.stylesheet(paperColour))
            return style.toString()
        }

    fun getCardClass(oneBasedCardOrdinal: Int): String = "card card$oneBasedCardOrdinal" + getCssClasses()

    companion object {
        private val nightModeClassRegex = Regex("\\.night(?:_m|M)ode\\b")

        fun create(
            customFonts: ReviewerCustomFonts,
            preferences: SharedPreferences,
            cardStyleProvider: () -> CardStyle,
            paperColour: String,
        ): CardAppearance {
            val cardZoom = preferences.getInt("cardZoom", 100)
            val imageZoom = preferences.getInt("imageZoom", 100)
            val centerVertically = preferences.getBoolean("centerVertically", false)
            return CardAppearance(customFonts, cardZoom, imageZoom, centerVertically, cardStyleProvider, paperColour)
        }

        fun fixBoldStyle(content: String): String {
            // In order to display the bold style correctly, we have to change
            // font-weight to 700
            return content.replace("font-weight:600;", "font-weight:700;")
        }
    }
}
