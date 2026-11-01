// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardviewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.nullValue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/**
 * The native half of every card style, against every theme the study screen can be drawn in.
 *
 * The study screen is one sheet: the chrome, the card and the answer area are painted on the
 * same surface, so a style and a theme which disagree show up as a seam across the whole screen
 * rather than as a slightly wrong corner. These are the gates for the two ways that has gone
 * wrong before - a colour which did not carry from the sheet to the page, and an icon colour
 * which was right on the old app bar but not on the sheet.
 */
@RunWith(AndroidJUnit4::class)
class CardStyleSurfaceTest : RobolectricTest() {
    /** Every theme the study screen can be drawn in, by the style the app picks for it. */
    private val themes: List<Pair<String, Int>> =
        DayTheme.entries.map { "day/${it.name}" to it.styleResId } +
            NightTheme.entries.map { "night/${it.name}" to it.styleResId }

    private fun themed(themeResId: Int): Context = ContextThemeWrapper(targetContext, themeResId)

    @Test
    fun `the sheet and the page are painted the same colour`() {
        for ((themeName, themeResId) in themes) {
            val context = themed(themeResId)
            for (style in CardStyle.entries) {
                val tag = "$themeName / ${style.name}"
                val surface = FrameLayout(context)
                val page = View(context)
                style.applyTo(surface, page, context)

                val fill = style.studySurfaceColour(context)
                assertThat(
                    "$tag: the sheet has to be opaque, or the window background shows through it",
                    Color.alpha(fill),
                    equalTo(255),
                )
                assertThat("$tag: the sheet is painted with the style's colour", surface.sheetColour(), equalTo(fill))
                assertThat(
                    "$tag: the page is painted with the sheet's colour too, so the card cannot " +
                        "show a seam against the chrome",
                    (page.background as? ColorDrawable)?.color,
                    equalTo(fill),
                )
            }
        }
    }

    @Test
    fun `only a style with a rim puts one on the sheet`() {
        for ((themeName, themeResId) in themes) {
            val context = themed(themeResId)
            for (style in CardStyle.entries) {
                val tag = "$themeName / ${style.name}"
                val surface = FrameLayout(context)
                val page = View(context)
                style.applyTo(surface, page, context)

                if (style.surfaceStrokeAttr == null) {
                    assertThat("$tag: a rimless style must not leave a rim on the sheet", surface.foreground, nullValue())
                } else {
                    assertThat(
                        "$tag: the rim is drawn by the sheet, which is bigger than the page",
                        surface.foreground,
                        instanceOf(GradientDrawable::class.java),
                    )
                }
                assertThat("$tag: the page carries no rim of its own", page.foreground, nullValue())
            }
        }
    }

    @Test
    fun `the shadow is carried by the sheet and never by the page`() {
        val context = themed(DayTheme.LIGHT.styleResId)
        val density = context.resources.displayMetrics.density
        for (style in CardStyle.entries) {
            val surface = FrameLayout(context)
            val page = View(context)
            style.applyTo(surface, page, context)

            assertThat(
                "${style.name}: the sheet carries the style's shadow",
                surface.elevation,
                equalTo(style.elevationDp * density),
            )
            assertThat(
                "${style.name}: a page with an elevation would be drawn over the chrome",
                page.elevation,
                equalTo(0f),
            )
        }
    }

    @Test
    fun `without a surface the page carries the sheet itself`() {
        val context = themed(DayTheme.LIGHT.styleResId)
        val density = context.resources.displayMetrics.density
        for (style in CardStyle.entries) {
            val page = View(context)
            style.applyTo(null, page, context)

            if (style.isSheet) {
                assertThat(
                    "${style.name}: without a surface the page is the sheet",
                    page.background,
                    instanceOf(GradientDrawable::class.java),
                )
                assertThat(
                    "${style.name}: and it carries the shadow",
                    page.elevation,
                    equalTo(style.elevationDp * density),
                )
            } else {
                assertThat(
                    "${style.name}: a style with no sheet leaves the page near-transparent",
                    Color.alpha((page.background as? ColorDrawable)?.color ?: 0),
                    equalTo(1),
                )
            }
        }
    }

    @Test
    fun `the chrome's icons read on the sheet, in every style and theme`() {
        val offenders =
            themes.flatMap { (themeName, themeResId) ->
                val context = themed(themeResId)
                val icon = MaterialColors.getColor(context, R.attr.studySurfaceIconColor, Color.TRANSPARENT)
                CardStyle.entries.mapNotNull { style ->
                    val contrast = contrastRatio(icon, style.studySurfaceColour(context))
                    if (contrast < MINIMUM_ICON_CONTRAST) {
                        "$themeName / ${style.name}: ${"%.2f".format(contrast)}:1"
                    } else {
                        null
                    }
                }
            }

        assertThat(
            "The chrome sits on the sheet, so its icons are tinted for the sheet's colour " +
                "(R.attr.studySurfaceIconColor, docs/ui-polish §9). These pairs are unreadable:",
            offenders,
            empty(),
        )
    }

    /** The colour a sheet fills with, read back off its background drawable. */
    private fun View.sheetColour(): Int {
        val fill =
            (background as GradientDrawable).color
                ?: error("the sheet is filled with a gradient, not a colour")
        return fill.defaultColor
    }

    /** WCAG's contrast ratio, so the threshold below means the same thing as in the design notes. */
    private fun contrastRatio(
        foreground: Int,
        background: Int,
    ): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val linear = value / 255.0
            return if (linear <= 0.03928) linear / 12.92 else ((linear + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private companion object {
        /** WCAG's floor for a non-text element: the icons are the chrome's only signal. */
        const val MINIMUM_ICON_CONTRAST = 3.0
    }
}
