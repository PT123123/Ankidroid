// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.app.Activity
import android.content.Context
import com.ichi2.anki.common.crashreporting.CrashReporter
import timber.log.Timber

/**
 * Log-only [CrashReporter] for Anki Plus: the fork keeps no report endpoint, so an
 * exception is written to Timber and dropped. See `docs/analytics/README.md`.
 */
internal object LoggingCrashReporter : CrashReporter {
    override fun sendExceptionReport(
        message: String?,
        origin: String?,
    ) {
        dropReport(null, message, origin)
        AnkiDroidApp.sentExceptionReportHack = true
    }

    override fun sendExceptionReport(
        e: Throwable,
        origin: String?,
        additionalInfo: String?,
        onlyIfSilent: Boolean,
    ) {
        dropReport(e, additionalInfo, origin)
        AnkiDroidApp.sentExceptionReportHack = true
    }

    override fun sendExceptionReport(
        e: Throwable,
        origin: String?,
        additionalInfo: String?,
        onlyIfSilent: Boolean,
        context: Context,
    ) = sendExceptionReport(e, origin, additionalInfo, onlyIfSilent)

    override fun onPreferenceChanged(
        ctx: Context,
        newValue: String,
    ) = Unit

    override fun deleteLimiterData(context: Context) = Unit

    override fun setReportingMode(value: String) = Unit

    override fun isEnabled(
        context: Context,
        defaultValue: Boolean,
    ): Boolean = false

    override fun sendReport(activity: Activity): Boolean = false

    /**
     * Mirrors `CrashReportService`'s uninitialized path: the info line carries only the
     * exception class and origin, the throwable and message go to DEBUG, which
     * [com.ichi2.anki.logging.ProductionCrashReportingTree] does not forward.
     */
    private fun dropReport(
        e: Throwable?,
        message: String?,
        origin: String?,
    ) {
        Timber.i("Crash report dropped (%s: %s)", origin, e?.javaClass?.name)
        Timber.d(e, "dropped report (%s: %s)", origin, message)
    }
}
