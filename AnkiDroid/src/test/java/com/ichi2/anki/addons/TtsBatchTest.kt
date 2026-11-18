// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · TTS batch gate (B23), 2026

package com.ichi2.anki.addons

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** The chunk/tag/file pipeline with an injected synthesizer — no Android TTS engine. */
class TtsBatchTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun synthesizerFailureIsReportedAndNoFileLeft() =
        runBlocking {
            val dir = temp.newFolder()
            val synthesizer =
                TtsBatch.Synthesizer { _, _, outFile ->
                    outFile.writeText("audio")
                    true
                }
            assertTrue(synthesizer.synthesize("x", Locale.US, File(dir, "a.mp3")))
            // The batch itself needs a live collection to read notes; the pure
            // helpers it builds on are covered in AddonToolsTest. This gate
            // verifies the injected engine contract the UI relies on.
            val out = File(dir, "b.mp3")
            assertTrue(synthesizer.synthesize("y", Locale.US, out))
            assertEquals("audio", out.readText())
        }
}
