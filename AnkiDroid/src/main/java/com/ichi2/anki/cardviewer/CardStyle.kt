// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardviewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.settings.enums.CardStylePreset

/** How the four answer buttons are drawn. */
enum class AnswerButtonFamily {
    /** A saturated button with a light label: the look the fork shipped with. */
    FILLED,

    /** A tinted container with the ease's hue as the label: Material's tonal button. */
    TONAL,

    /** No fill: an outline in the ease's hue, the label in the same hue. */
    OUTLINED,
}

/**
 * How the card and the answer buttons are drawn on the study screen.
 *
 * The style has two halves:
 *
 * * the native half - the sheet the card sits on, its rim, its shadow and the gap around it -
 *   which [applyTo] puts on the web view;
 * * the page half - the type, the padding and the rule between question and answer - which
 *   [stylesheet] puts into the card's `<style>` block.
 *
 * Both halves of *every* style are installed at once, so switching styles only has to swap a
 * class on the body: the answer that is on screen stays on screen.
 */
enum class CardStyle(
    val preset: CardStylePreset,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    /** `null` → the card is not drawn as a sheet, it fills the area as it always did. */
    val surfaceColorAttr: Int?,
    val surfaceStrokeAttr: Int?,
    /** the radius of the sheet's corners */
    val cornerRadiusDp: Int,
    /** the shadow under the sheet */
    val elevationDp: Int,
    /** the gap between the edge of the screen and the sheet */
    val marginHorizontalDp: Int,
    val marginVerticalDp: Int,
    /** the padding between the edge of the sheet and the card's content */
    val contentPaddingDp: Int,
    val buttonFamily: AnswerButtonFamily,
    /** the class this style puts on the card's `<body>` */
    val bodyClass: String,
) {
    /** The desktop's look: no sheet, no padding, the saturated answer buttons. */
    CLASSIC(
        CardStylePreset.CLASSIC,
        R.string.card_style_classic,
        R.string.card_style_classic_summary,
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        AnswerButtonFamily.FILLED,
        "",
    ),

    /** A rounded sheet with a hairline rim and a shadow, tonal answer buttons. */
    MODERN(
        CardStylePreset.MODERN,
        R.string.card_style_modern,
        R.string.card_style_modern_summary,
        R.attr.cardSurfaceColor,
        R.attr.cardSurfaceStrokeColor,
        24,
        2,
        12,
        8,
        12,
        AnswerButtonFamily.TONAL,
        "ankiplus_modern",
    ),

    /** A warm paper sheet with a serif type and wide margins. */
    PAPER(
        CardStylePreset.PAPER,
        R.string.card_style_paper,
        R.string.card_style_paper_summary,
        R.attr.cardPaperColor,
        R.attr.cardPaperStrokeColor,
        8,
        1,
        12,
        8,
        20,
        AnswerButtonFamily.TONAL,
        "ankiplus_paper",
    ),

    /** No sheet, tight type, outlined answer buttons. */
    MINIMAL(
        CardStylePreset.MINIMAL,
        R.string.card_style_minimal,
        R.string.card_style_minimal_summary,
        null,
        null,
        0,
        0,
        0,
        0,
        4,
        AnswerButtonFamily.OUTLINED,
        "ankiplus_minimal",
    ),
    ;

    /** Whether the card is drawn as a sheet floating above the background. */
    val isSheet: Boolean get() = surfaceColorAttr != null

    /**
     * Puts the native half of this style on [webView]: the sheet's rim and shadow, the rounded
     * corners the page is clipped to, and the gap between the screen edge and the sheet.
     */
    fun applyTo(
        webView: View,
        context: Context,
    ) {
        val density = context.resources.displayMetrics.density
        val radius = cornerRadiusDp * density
        val strokeWidth = context.resources.getDimension(R.dimen.card_surface_stroke_width).toInt()

        if (isSheet) {
            // The page paints over this, so the fill is only seen where the card's own
            // background is transparent: it is the colour the sheet would have anyway.
            webView.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    if (radius > 0f) cornerRadius = radius
                    setColor(surfaceColour(context, surfaceColorAttr!!))
                }
        } else {
            // near-transparent, as the viewer has always used it, to avoid a white flash
            webView.setBackgroundColor(Color.argb(1, 0, 0, 0))
        }
        // the page is clipped to the rounded outline, so the sheet has rounded corners
        webView.clipToOutline = radius > 0f
        webView.foreground =
            surfaceStrokeAttr?.let { attr ->
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    if (radius > 0f) cornerRadius = radius
                    setColor(Color.TRANSPARENT)
                    setStroke(strokeWidth, surfaceColour(context, attr))
                }
            }
        webView.elevation = elevationDp * density

        val marginH = (marginHorizontalDp * density).toInt()
        val marginV = (marginVerticalDp * density).toInt()
        (webView.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(marginH, marginV, marginH, marginV)
            webView.layoutParams = it
        }
    }

    /** The colour the sheet is filled with, or `null` when the card is not drawn as a sheet. */
    @ColorInt
    fun surfaceColour(context: Context): Int? = surfaceColorAttr?.let { surfaceColour(context, it) }

    companion object {
        fun of(preset: CardStylePreset): CardStyle = entries.first { it.preset == preset }

        /** Every class a style may put on the body, so that a swap can remove all of them. */
        val bodyClasses: List<String> = entries.mapNotNull { it.bodyClass.takeIf { c -> c.isNotEmpty() } }

        @ColorInt
        private fun surfaceColour(
            context: Context,
            attr: Int,
        ): Int = MaterialColors.getColor(context, attr, Color.TRANSPARENT)

        /**
         * The stylesheet every card gets.
         *
         * It carries the rules of all four styles, each behind the class of its own style, so
         * that switching styles is a class swap on the body instead of a re-render.
         *
         * The rules are deliberately weak: the note type's own styling is injected after this
         * block and therefore still wins.
         */
        fun stylesheet(paperColour: String): String =
            """
            body.ankiplus_modern {
              box-sizing: border-box;
              padding: ${MODERN.contentPaddingDp}px;
            }
            body.ankiplus_paper {
              box-sizing: border-box;
              padding: ${PAPER.contentPaddingDp}px;
              background-color: $paperColour;
            }
            body.ankiplus_paper h1, body.ankiplus_paper h2, body.ankiplus_paper h3 {
              font-family: serif;
            }
            body.ankiplus_minimal {
              box-sizing: border-box;
              padding: ${MINIMAL.contentPaddingDp}px;
            }
            body.ankiplus_modern hr#answer, body.ankiplus_paper hr#answer, body.ankiplus_minimal hr#answer {
              border: 0; border-top: 1px solid rgba(128,128,128,0.4); margin: 1em 0;
            }
            """.trimIndent()
    }
}
