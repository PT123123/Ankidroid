// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.cardviewer.CardStyle
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.reviewer.FullScreenMode
import com.ichi2.anki.settings.Prefs
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The study screen as one sheet, laid out on screen.
 *
 * The chrome (the toolbar, the counts bar), the card and the answer area are drawn on a single
 * surface which the card style paints (docs/ui-polish §9), so the three have to agree on where
 * the sheet's edge is and on what colour it is. [CardStyleSurfaceTest] covers the colour half
 * without an activity; this covers the layout half, which only shows up once the views are
 * really placed: the sheet behind the chrome rather than over it, the card inset by the rim,
 * the chrome inset with the sheet, and the answer area inside the same sheet.
 */
@RunWith(AndroidJUnit4::class)
class StudySurfaceTest : RobolectricTest() {
    // rendering a card requires a media folder
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `the sheet carries the chrome and the card, in every style and study layout`() {
        for (style in CardStyle.entries) {
            for (mode in FullScreenMode.entries) {
                withStudyScreen(style, mode) { reviewer ->
                    val tag = "${style.name} / ${mode.name}"
                    val surface = reviewer.studySurface()
                    val holder = reviewer.studySurfaceHolder()
                    val page = reviewer.webView!!
                    val density = reviewer.resources.displayMetrics.density
                    val marginH = (style.marginHorizontalDp * density).toInt()
                    val marginV = (style.marginVerticalDp * density).toInt()
                    val rim =
                        if (style.surfaceStrokeAttr == null) {
                            0
                        } else {
                            reviewer.resources.getDimensionPixelSize(R.dimen.card_surface_stroke_width)
                        }

                    // The sheet must be *behind* the chrome. A view with an elevation is drawn
                    // above its lower siblings, so it is the holder which is put first, and the
                    // holder which must stay out of the ordering: it is what keeps the sheet's
                    // shadow from turning the sheet into the topmost layer.
                    assertThat(
                        "$tag: the sheet is drawn behind everything else on the screen",
                        (holder.parent as ViewGroup).indexOfChild(holder),
                        equalTo(0),
                    )
                    assertThat("$tag: the holder carries no shadow", holder.elevation, equalTo(0f))
                    assertThat(
                        "$tag: the sheet carries the style's shadow",
                        surface.elevation,
                        equalTo(style.elevationDp * density),
                    )
                    assertThat("$tag: the page carries none", page.elevation, equalTo(0f))

                    // The gap around the sheet, and the rim kept clear inside it.
                    val sheetParams = surface.params()
                    assertThat("$tag: the sheet's left gap", sheetParams.leftMargin, equalTo(marginH))
                    assertThat("$tag: the sheet's right gap", sheetParams.rightMargin, equalTo(marginH))
                    assertThat("$tag: the sheet's bottom gap", sheetParams.bottomMargin, equalTo(marginV))
                    assertThat(
                        "$tag: the sheet runs to the top of the screen, so there is no top corner",
                        sheetParams.topMargin,
                        equalTo(0),
                    )
                    val pageParams = page.params()
                    assertThat("$tag: the page sits inside the rim", pageParams.leftMargin, equalTo(marginH + rim))
                    assertThat("$tag: the page sits inside the rim", pageParams.rightMargin, equalTo(marginH + rim))
                    assertThat(
                        "$tag: the page is inset at the bottom by the rim and the sheet's gap",
                        pageParams.bottomMargin,
                        equalTo(marginV + rim),
                    )

                    // Whatever chrome this layout has, it is inset with the sheet: the two share
                    // the sheet's edges, which is the whole point of putting them on it.
                    for (id in CHROME_IDS) {
                        reviewer.findViewById<View>(id)?.let { chrome ->
                            assertThat(
                                "$tag: ${nameOf(id)} keeps the sheet's left edge",
                                chrome.params().leftMargin,
                                equalTo(marginH),
                            )
                            assertThat(
                                "$tag: ${nameOf(id)} keeps the sheet's right edge",
                                chrome.params().rightMargin,
                                equalTo(marginH),
                            )
                        }
                    }

                    // The chrome's own bands would split the sheet up again. In the fullscreen
                    // layouts the toolbar is an overlay pulled over the sheet, so it keeps an
                    // opaque fill of the sheet's colour instead of none.
                    val toolbarContainer = reviewer.findViewById<View>(R.id.toolbar_container)
                    if (mode.isFullScreenReview()) {
                        assertThat(
                            "$tag: the overlay is painted the sheet's colour, not a guessed one",
                            (toolbarContainer?.background as? ColorDrawable)?.color,
                            equalTo(style.studySurfaceColour(reviewer)),
                        )
                    } else {
                        assertThat(
                            "$tag: the app bar paints no band of its own",
                            toolbarContainer?.background,
                            nullValue(),
                        )
                        assertThat(
                            "$tag: the toolbar paints no band of its own",
                            reviewer.findViewById<View>(R.id.toolbar)?.background,
                            nullValue(),
                        )
                    }

                    // The chip that switches the style is in all three layouts, from one include.
                    assertThat(
                        "$tag: the card style chip is present",
                        reviewer.findViewById<ImageButton>(R.id.card_style_button),
                        instanceOf(ImageButton::class.java),
                    )
                }
            }
        }
    }

    @Test
    fun `the sheet runs behind the answer area, wherever the buttons sit`() {
        for (position in listOf("bottom", "top")) {
            withStudyScreen(CardStyle.MODERN, FullScreenMode.BUTTONS_AND_MENU, position) { reviewer ->
                val surface = reviewer.studySurface()
                val answerArea = reviewer.findViewById<View>(R.id.answer_options_layout)
                assertThat(
                    "answer buttons at $position: the sheet starts at the top of the screen",
                    surface.top,
                    equalTo(0),
                )
                assertThat(
                    "answer buttons at $position: the sheet reaches past the answer area",
                    surface.bottom,
                    greaterThanOrEqualTo(answerArea.bottom),
                )
                assertThat(
                    "answer buttons at $position: the answer area stays inside the sheet",
                    answerArea.bottom,
                    lessThanOrEqualTo(surface.bottom + 1),
                )
            }
        }
    }

    private fun nameOf(viewId: Int): String = resources.getResourceEntryName(viewId)

    private fun View.params(): ViewGroup.MarginLayoutParams = layoutParams as ViewGroup.MarginLayoutParams

    private fun Reviewer.studySurface(): View = findViewById(R.id.study_surface)!!

    private fun Reviewer.studySurfaceHolder(): View = findViewById(R.id.study_surface_holder)!!

    private fun withStudyScreen(
        style: CardStyle,
        mode: FullScreenMode,
        answerButtonsPosition: String? = null,
        block: (Reviewer) -> Unit,
    ) {
        Prefs.cardStylePreset = style.preset
        targetContext.sharedPrefs().edit {
            putString(FullScreenMode.PREF_KEY, mode.getPreferenceValue())
            answerButtonsPosition?.let { putString(targetContext.getString(R.string.answer_buttons_position_preference), it) }
        }
        addBasicNote("Hello", "World")
        block(ReviewerTest.startReviewer(this))
    }

    private companion object {
        /** The chrome which the viewer insets with the sheet, wherever a layout puts it. */
        val CHROME_IDS = intArrayOf(R.id.toolbar_container, R.id.top_bar)
    }
}
