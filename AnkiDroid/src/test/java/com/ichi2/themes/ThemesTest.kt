// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.themes

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.StudyOptionsActivity
import com.ichi2.anki.settings.PrefsRepository
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.anki.settings.enums.ThemeColor
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class ThemesTest : RobolectricTest() {
    /**
     * If the decor view is created before [Themes.setTheme] applies the user's theme, the theme's
     * windowBackground is not applied and night mode renders a white background (issue 21520).
     * Ensure this fails fast in debug builds rather than rendering incorrectly.
     */
    @Test
    fun `decor view access before setTheme fails fast`() {
        val exception =
            assertFailsWith<IllegalStateException> {
                Robolectric.buildActivity(EarlyDecorViewInitActivity::class.java).create()
            }
        assertThat(exception.message, containsString("setTheme"))
    }

    @Test
    fun `window background follows the theme - issue 21520`() {
        RuntimeEnvironment.setQualifiers("+notnight")
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.DAY
            // A monochrome scheme: Themes.applyThemeColor() skips it, so no gradient overlay.
            dayTheme = DayTheme.PLAIN
        }
        val activity =
            startActivityNormallyOpenCollectionWithIntent(
                StudyOptionsActivity::class.java,
                Intent(),
            )

        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        assertThat(
            "the theme's windowBackground is expected to be a plain color",
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT,
            equalTo(true),
        )
        val decorBackground = activity.window.decorView.background
        assertThat((decorBackground as ColorDrawable).color, equalTo(tv.data))
    }

    /**
     * Anki Plus paints the window with the vertical gradient of the selected [ThemeColor], whose
     * stops are day/night qualified resources. Issue 21520 then means: the decor must carry the
     * gradient of the *night* stops, not a stale day background.
     */
    @Test
    fun `the theme color gradient follows the night theme`() {
        RuntimeEnvironment.setQualifiers("+night")
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.NIGHT
            nightTheme = NightTheme.DARK
            themeColor = ThemeColor.EMERALD
        }
        val activity =
            startActivityNormallyOpenCollectionWithIntent(
                StudyOptionsActivity::class.java,
                Intent(),
            )

        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        assertThat("the window is the emerald gradient", tv.resourceId, equalTo(R.drawable.theme_gradient_emerald))

        val gradient = activity.window.decorView.background as GradientDrawable
        assertThat(
            gradient.colors?.toList(),
            equalTo(
                listOf(
                    ContextCompat.getColor(activity, R.color.tc_emerald_gradient_top),
                    ContextCompat.getColor(activity, R.color.tc_emerald_gradient_bottom),
                ),
            ),
        )
    }

    /**
     * When an activity is relaunched (e.g. after a day/night theme change), the framework may
     * preserve the window: the decor view already exists before `super.onCreate`, and the
     * framework refreshes its `windowBackground` itself, so [Themes.setTheme] should not fail
     * (issue 21548).
     */
    @Test
    fun `recreated activity with an existing decor view does not fail - issue 21548`() {
        Robolectric.buildActivity(EarlyDecorViewInitActivity::class.java).create(Bundle())
    }

    /** simulates e.g. an [androidx.activity.enableEdgeToEdge] call before `super.onCreate` */
    class EarlyDecorViewInitActivity : AnkiActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            window.decorView
            super.onCreate(savedInstanceState)
        }
    }
}
