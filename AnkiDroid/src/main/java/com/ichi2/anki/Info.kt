// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2009 Nicolas Raoul <nicolas.raoul@gmail.com>
// SPDX-FileCopyrightText: Copyright (c) 2009 Edu Zamora <edu.zasu@gmail.com>
// SPDX-FileCopyrightText: Copyright (c) 2015 Tim Rae <perceptualchaos2@gmail.com>

package com.ichi2.anki

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.android.getColorFromAttr
import com.ichi2.anki.databinding.ActivityInfoBinding
import com.ichi2.anki.snackbar.BaseSnackbarBuilderProvider
import com.ichi2.anki.snackbar.SnackbarBuilder
import com.ichi2.utils.IntentUtil.canOpenIntent
import com.ichi2.utils.IntentUtil.tryOpenIntent
import com.ichi2.utils.VersionUtils.appName
import com.ichi2.utils.VersionUtils.pkgVersionName
import dev.androidbroadcast.vbpd.viewBinding
import timber.log.Timber

/**
 * Shows an about box with the changelog.
 *
 * The changelog used to be `changelog.html` in a WebView; it is now the same asset parsed with
 * [HtmlCompat] into a themed text view.
 */
class Info :
    AnkiActivity(R.layout.activity_info),
    BaseSnackbarBuilderProvider {
    private val binding by viewBinding(ActivityInfoBinding::bind)

    override val baseSnackbarBuilder: SnackbarBuilder = {
        anchorView = binding.buttons
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        // If the page crashes, we do not want to display it again (#7135 maybe)
        InitialActivity.setUpgradedToLatestVersion(sharedPrefs())
        setViewBinding(binding)
        enableToolbar()
        applyInsets()
        if (BuildConfig.SHOW_DONATE_LINKS) {
            binding.donate.setOnClickListener { openUrl(R.string.link_opencollective_donate) }
        } else {
            binding.donate.isVisible = false
        }
        title = "$appName v$pkgVersionName"
        binding.leftButton.run {
            if (canOpenMarketUri()) {
                setText(R.string.info_rate)
                setOnClickListener {
                    tryOpenIntent(
                        this@Info,
                        AnkiDroidApp.getMarketIntent(this@Info),
                    )
                }
            } else {
                visibility = View.GONE
            }
        }
        binding.rightButton.run {
            text = getString(R.string.dialog_continue)
            setOnClickListener { close() }
        }
        binding.changelogText.run {
            text = loadChangelog()
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(getColorFromAttr(this@Info, android.R.attr.colorAccent))
        }
    }

    /**
     * Parses the changelog asset into styled text. Without donation links, their anchors are
     * removed while keeping the wording, exactly like the WebView used to.
     */
    private fun loadChangelog(): CharSequence {
        val html = assets.open("changelog.html").bufferedReader().use { it.readText() }
        val builder = SpannableStringBuilder(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY))
        if (!BuildConfig.SHOW_DONATE_LINKS) {
            for (span in builder.getSpans(0, builder.length, URLSpan::class.java)) {
                if ("opencollective.com" in span.url) {
                    builder.removeSpan(span)
                }
            }
        }
        return builder
    }

    /**
     * Applies edge-to-edge insets.
     *
     * The app bar's background spans the full width and draws behind the status bar; only its
     * content is inset. Everything below it is held inside the safe area.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            // the toolbar's parent: padding on the Toolbar counts towards its minHeight,
            // which would shrink the app bar's content below actionBarSize
            binding.toolbarContainer.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            // no corner clearance: the buttons carry a 12dp horizontal margin, so the arc
            // intrudes less than the navigation bar already clears
            binding.content.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun close() {
        setResult(RESULT_OK)
        finish()
    }

    private fun canOpenMarketUri(): Boolean =
        try {
            canOpenIntent(this, AnkiDroidApp.getMarketIntent(this))
        } catch (e: Exception) {
            Timber.w(e)
            false
        }
}
