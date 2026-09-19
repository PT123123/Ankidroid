// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.settings.enums

import androidx.annotation.StyleRes
import com.ichi2.anki.R

/**
 * User-selectable accent color themes. The overlay style is applied on top of
 * the active day/night theme by [com.ichi2.themes.Themes].
 *
 * [R.array.theme_color_values]
 */
enum class ThemeColor(
    override val entryResId: Int,
    @param:StyleRes val overlayResId: Int,
) : PrefEnum {
    SKY_BLUE(R.string.theme_color_sky_value, R.style.ThemeOverlay_App_ThemeColor_Sky),
    INDIGO(R.string.theme_color_indigo_value, R.style.ThemeOverlay_App_ThemeColor_Indigo),
    TEAL(R.string.theme_color_teal_value, R.style.ThemeOverlay_App_ThemeColor_Teal),
    EMERALD(R.string.theme_color_emerald_value, R.style.ThemeOverlay_App_ThemeColor_Emerald),
    LIME(R.string.theme_color_lime_value, R.style.ThemeOverlay_App_ThemeColor_Lime),
    AMBER(R.string.theme_color_amber_value, R.style.ThemeOverlay_App_ThemeColor_Amber),
    SUNSET(R.string.theme_color_sunset_value, R.style.ThemeOverlay_App_ThemeColor_Sunset),
    ROSE(R.string.theme_color_rose_value, R.style.ThemeOverlay_App_ThemeColor_Rose),
    LAVENDER(R.string.theme_color_lavender_value, R.style.ThemeOverlay_App_ThemeColor_Lavender),
    GRAPHITE(R.string.theme_color_graphite_value, R.style.ThemeOverlay_App_ThemeColor_Graphite),
}
