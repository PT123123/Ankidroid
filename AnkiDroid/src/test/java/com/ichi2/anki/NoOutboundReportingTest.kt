// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Test
import java.io.File

/**
 * Anki Plus keeps no report endpoint: neither the GA4 transport nor ACRA may come back, because a
 * fork build would otherwise post its own identity ("Anki Plus", `com.pt123123.ankiplus`) into
 * upstream AnkiDroid's infrastructure.
 *
 * Each test carries a control proving the check can fail. See `docs/analytics/README.md`.
 */
class NoOutboundReportingTest {
    private val moduleDir: File
        get() = File("src/main/java/com/ichi2/anki")

    @Test
    fun `build files name no report transport`() {
        val needles = listOf("acra", "google-analytics", "ANALYTICS_API_KEY")
        // control: a build file that still declared ACRA would be reported
        assertThat(matchedNeedles("implementation libs.acra.http", needles), contains("acra"))

        // unit tests run with the module directory as the working directory
        val buildFiles =
            listOf(
                File("build.gradle"),
                File("../common/build.gradle.kts"),
                File("../widgets/build.gradle.kts"),
                File("../gradle/libs.versions.toml"),
            ).joinToString("\n") { it.readText() }
        assertThat(matchedNeedles(buildFiles, needles), empty())
    }

    @Test
    fun `default resources carry no tracking id`() {
        val needles = listOf("ga_trackingId", "acra/report", "G-XY625S5VVE")
        // control: the tracking id the upstream build shipped is the shape this scan looks for
        assertThat(
            matchedNeedles("<string name=\"ga_trackingId\">G-XY625S5VVE</string>", needles),
            contains("ga_trackingId", "G-XY625S5VVE"),
        )

        val resources =
            (
                File("src/main/res/values").listFiles { file -> file.extension == "xml" }!!.toList() +
                    File("src/main/AndroidManifest.xml")
            ).joinToString("\n") { it.readText() }
        assertThat(matchedNeedles(resources, needles), empty())
    }

    @Test
    fun `no report transport is on the classpath`() {
        // control: the probe does resolve a class the app ships
        Class.forName("com.ichi2.anki.common.analytics.Analytics")
        assertClassAbsent("org.acra.ACRA")
        assertClassAbsent("com.criticalay.GoogleAnalytics")
    }

    @Test
    fun `the uploading sources are gone`() {
        // control: the same existence check passes for a file that is there
        assertThat(File(moduleDir, "AnkiDroidApp.kt").exists(), `is`(true))
        assertThat(File(moduleDir, "AcraCrashReporter.kt").exists(), `is`(false))
        assertThat(File(moduleDir, "analytics/AnkiDroidUsageAnalytics.kt").exists(), `is`(false))

        val appSource = File(moduleDir, "AnkiDroidApp.kt").readText()
        assertThat(appSource, not(containsString("initializeAcraCrashReporter")))
        assertThat(appSource, not(containsString("initializeAnalytics")))
    }

    private fun assertClassAbsent(name: String) {
        try {
            Class.forName(name)
        } catch (expected: ClassNotFoundException) {
            return
        }
        throw AssertionError("$name is on the classpath: Anki Plus must not ship a report transport")
    }

    private fun matchedNeedles(
        text: String,
        needles: List<String>,
    ): List<String> = needles.filter { text.contains(it, ignoreCase = true) }
}
