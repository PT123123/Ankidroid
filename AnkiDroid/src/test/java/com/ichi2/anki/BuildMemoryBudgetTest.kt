// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Test
import java.io.File

/**
 * This fork is built on a machine that is already at ~95 % of its commit limit before Gradle
 * starts, so every JVM the build launches has to be budgeted explicitly rather than inheriting
 * `org.gradle.jvmargs`. The ceilings asserted here are the ones measured on 2026-10-28; the
 * reasoning and the numbers live in `docs/development/fork-build-speed.md`.
 *
 * Each test carries a control proving that the check it makes can actually go red.
 */
class BuildMemoryBudgetTest {
    @Test
    fun `every build jvm declares its own heap ceiling`() {
        val text = File("../gradle.properties").readText()

        // control: a ceiling four times the budget is read correctly and would be rejected
        assertThat(xmxMb("-Xmx8g"), `is`(8192))
        assertThat(metaspaceMb("-XX:MaxMetaspaceSize=1g"), `is`(1024))

        // The daemon hosts R8 in-process, which measured a 3703 MB working set on its own.
        assertThat(xmxMb(propertyValue(text, "org.gradle.jvmargs")), `is`(4096))
        // Uncapped, the Kotlin daemon inherits that same 4g as a second private heap.
        assertThat(
            "kotlin.daemon.jvmargs must bound its own heap",
            xmxMb(propertyValue(text, "kotlin.daemon.jvmargs")),
            not(greaterThan(2048)),
        )
        assertThat(
            "and its own metaspace",
            metaspaceMb(propertyValue(text, "kotlin.daemon.jvmargs")),
            not(greaterThan(512)),
        )
        // Inert key, not a knob: Kotlin 2.3.21's `CompilerSystemProperties` ships a client-alive
        // *path* (a flag file the daemon watches), no alive *timeout*, and `DaemonOptions`' own
        // autoshutdown idle/unused seconds default to 0 = off. So the Gradle daemon's `idletimeout`
        // (next test) and `just stop` are the only things that release this 2g.
        assertThat(
            "no -D flag may name a Kotlin property that does not exist",
            propertyValue(text, "kotlin.daemon.jvmargs"),
            not(containsString("client.alive.timeout")),
        )
    }

    @Test
    fun `daemon fan-out and retention are bounded`() {
        val text = File("../gradle.properties").readText()

        // control: the default of one worker per logical core would be rejected here
        assertThat(intProperty("22"), greaterThan(4))

        assertThat(intProperty(propertyValue(text, "org.gradle.workers.max")), not(greaterThan(4)))
        // G1 does not uncommit after R8, so the daemon keeps its high-water mark until it dies.
        assertThat(
            longProperty(propertyValue(text, "org.gradle.daemon.idletimeout")),
            not(greaterThan(600_000L)),
        )
        // …and the build needs a way to say "die now", since the Kotlin daemon only follows this
        // process out of existence. recipeBody fails on its own if the recipe is gone.
        assertThat(recipeBody(File("../Justfile").readText(), "stop"), containsString("--stop"))
    }

    @Test
    fun `unit test forks pre-commit nothing and are capped`() {
        val script = File("../build.gradle.kts").readText()

        // control: 11 forks reserving 1g each is the ask that got workers refused
        assertThat(script, not(containsString("""it.minHeapSize = "1g"""")))
        assertThat(script, containsString("""it.minHeapSize = "256m""""))
        assertThat("a run must be able to override the cap", script, containsString("testForks"))

        val cap =
            Regex("maxLocalTestForks = (\\d+)")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: error("maxLocalTestForks is gone, so local forks fall back to cores/2")
        assertThat(cap, not(greaterThan(4)))
    }

    @Test
    fun `the daily release path costs less than the publish path`() {
        // R8 is the most expensive task in this build - 3m 27s and 3703 MB inside the daemon - so the
        // daily recipe turns it off. It has to be a Gradle property: upstream's MINIFY_ENABLED is
        // read through System.getenv, which returns the *daemon's* environment, i.e. whatever was set
        // when the daemon first started, so it is silently wrong on the second invocation.
        val buildGradle = File("build.gradle").readText()
        assertThat(buildGradle, containsString("""project.findProperty("skipMinify") == "true""""))
        assertThat(buildGradle, containsString("minifyEnabled !skipMinify"))
        // control: a recipe that sets the env var instead would pass the "no R8" reading but not work
        val envRecipe = "build-release:\n    MINIFY_ENABLED=false ./gradlew :AnkiDroid:assemblePlayRelease\n"
        // …and the probe does read that line, so `not(containsString("-PskipMinify"))` below is a
        // finding rather than an empty extraction.
        assertThat(recipeCommands(envRecipe), containsString("MINIFY_ENABLED"))
        assertThat(recipeCommands(envRecipe), not(containsString("-PskipMinify")))

        val justfile = File("../Justfile").readText()
        // Every "must not contain" in this class is only worth something if the extraction is not
        // empty: indent the whole Justfile with a tab and recipeCommands() returns "" while still
        // existing as a file. Assert it sees real recipe lines before trusting its silences.
        assertThat(recipeCommands(justfile), containsString("assemblePlayRelease"))
        assertThat(recipeBody(justfile, "default"), containsString("@just build"))
        assertThat(recipeBody(justfile, "build-release"), containsString("-PskipMinify=true"))
        assertThat(recipeCommands(justfile), not(containsString("MINIFY_ENABLED")))
        // The publish chain must NOT inherit that shortcut.
        assertThat(recipeBody(justfile, "minify-release"), not(containsString("skipMinify")))
        assertThat(
            "publish has to rebuild through the minified path",
            justfile,
            containsString("publish: release-slow"),
        )
        assertThat(recipeBody(justfile, "release-slow"), containsString("release-check"))
    }

    @Test
    fun `release lint never shares an invocation with R8`() {
        val lint = File("../lint.gradle").readText()
        assertThat(
            "checkReleaseBuilds has to stay property-driven, or assemble drags lintVital in",
            lint,
            containsString("""checkReleaseBuilds = project.findProperty("skipReleaseLint") != "true""""),
        )

        val justfile = File("../Justfile").readText()
        // control: the same probe against a recipe that keeps the gate in-graph
        assertThat(
            recipeBody("build-release:\n    ./gradlew :AnkiDroid:assemblePlayRelease\n", "build-release"),
            not(containsString("-PskipReleaseLint")),
        )
        assertThat(recipeBody(justfile, "build-release"), containsString("-PskipReleaseLint=true"))
        // …and the publish path skips it in *its* invocation only to run it in the next one.
        assertThat(recipeBody(justfile, "minify-release"), containsString("-PskipReleaseLint=true"))
        // lintVital* tasks are only registered when the property is absent.
        assertThat(recipeBody(justfile, "lint-release"), not(containsString("skipReleaseLint")))
        assertThat(
            "the lint gate has to be reachable from publish",
            justfile,
            containsString("release-check: minify-release lint-release"),
        )
        // A different -Xmx on the CLI means a second daemon, i.e. two retained heaps. Checked on
        // the recipe bodies only: the Justfile is allowed to warn about the flag in a comment.
        val commands = recipeCommands(justfile)
        assertThat(
            "a CLI jvmargs override would start a second daemon",
            commands,
            not(containsString("-Dorg.gradle.jvmargs")),
        )
        // control: that probe reads command lines, and it does see the forbidden flag there
        val forbidden = "build:\n    ./gradlew -Dorg.gradle.jvmargs=-Xmx8g :AnkiDroid:help\n"
        assertThat(recipeCommands(forbidden), containsString("-Dorg.gradle.jvmargs"))
    }

    /** The command lines of every recipe, i.e. what actually runs, without comments. */
    private fun recipeCommands(text: String): String = text.lines().filter { it.startsWith("    ") }.joinToString("\n") { it.trim() }

    private fun propertyValue(
        text: String,
        key: String,
    ): String =
        Regex("^${Regex.escape(key)}=(.*)$", RegexOption.MULTILINE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: error("$key is not set in gradle.properties")

    private fun intProperty(value: String): Int = value.trim().toIntOrNull() ?: error("'$value' is not an int")

    private fun longProperty(value: String): Long = value.trim().toLongOrNull() ?: error("'$value' is not a long")

    private fun xmxMb(jvmArgs: String): Int {
        val match = Regex("-Xmx(\\d+)([mMgG])").find(jvmArgs) ?: error("No -Xmx in '$jvmArgs'")
        val (size, unit) = match.destructured
        return size.toInt() * if (unit.lowercase() == "g") 1024 else 1
    }

    private fun metaspaceMb(jvmArgs: String): Int {
        val match =
            Regex("-XX:MaxMetaspaceSize=(\\d+)([mMgG])").find(jvmArgs)
                ?: error("No -XX:MaxMetaspaceSize in '$jvmArgs'")
        val (size, unit) = match.destructured
        return size.toInt() * if (unit.lowercase() == "g") 1024 else 1
    }

    /**
     * A recipe's own lines, header included: dependencies can hang off the header
     * (`release-slow: release-check`), and that line is where the publish chain is declared.
     */
    private fun recipeBody(
        text: String,
        name: String,
    ): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it == "$name:" || it.startsWith("$name: ") }
        assertThat("`$name` recipe is missing from the Justfile", start, not(`is`(-1)))
        return (
            listOf(lines[start]) +
                lines.drop(start + 1).takeWhile { it.startsWith("    ") || it.isBlank() }
        ).joinToString("\n") { it.trim() }
    }
}
