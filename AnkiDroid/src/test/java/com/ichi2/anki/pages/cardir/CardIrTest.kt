// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.testutils.EmptyApplication
import com.ichi2.testutils.JvmTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files

/** Tests for the Card IR model, the parser and the style helpers. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class CardIrTest : JvmTest() {
    private val cardIr =
        """
        {
          "ir_version": "0.1.0",
          "card_id": "1706102393680",
          "notetype": "Basic",
          "layout_mode": "bounds",
          "variants": ["light@800x1", "dark@800x1"],
          "sides": {
            "question": {
              "light@800x1": {
                "width": 800,
                "height": 200,
                "node_count": 2,
                "text_run_count": 1,
                "root": {
                  "id": 0,
                  "type": "box",
                  "style": {
                    "display": "block",
                    "width": "800px",
                    "background-color": "rgb(255, 255, 255)",
                    "font-family": "\"Source Han Serif JP\", serif",
                    "font-size": "22px"
                  },
                  "rect": {"x": 0, "y": 0, "w": 800, "h": 200},
                  "children": [
                    {
                      "id": 1,
                      "type": "text",
                      "text": "hello",
                      "style": {},
                      "rect": {"x": 40, "y": 20, "w": 60, "h": 20},
                      "runs": [
                        {"text": "hello", "rect": {"x": 40, "y": 20, "w": 60, "h": 20}}
                      ]
                    }
                  ]
                }
              },
              "dark@800x1": {
                "width": 800,
                "height": 200,
                "node_count": 2,
                "text_run_count": 1,
                "root": {
                  "id": 0,
                  "type": "box",
                  "style": {"display": "block", "width": "800px"},
                  "rect": {"x": 0, "y": 0, "w": 800, "h": 200},
                  "children": []
                }
              }
            }
          }
        }
        """.trimIndent()

    @Test
    fun `parses a card ir`() {
        val card = CardIrParser.parse(cardIr)
        assertThat(card, notNullValue())
        card!!
        assertEquals("0.1.0", card.ir_version)
        assertEquals("1706102393680", card.card_id)
        assertEquals("Basic", card.notetype)
        assertEquals(2, card.variants.size)
        val frame = card.sides["question"]!!["light@800x1"]!!
        assertEquals(800f, frame.width)
        assertEquals(2, frame.node_count)
        assertEquals(1, frame.root.children.size)
        assertEquals(
            "hello",
            frame.root.children[0]
                .runs[0]
                .text,
        )
    }

    @Test
    fun `rejects an unsupported version`() {
        val broken = cardIr.replace("\"0.1.0\"", "\"9.9.9\"")
        assertThat(CardIrParser.parse(broken), nullValue())
    }

    @Test
    fun `rejects malformed json`() {
        assertThat(CardIrParser.parse("{not json"), nullValue())
    }

    @Test
    fun `selects the variant of the theme`() {
        val card = CardIrParser.parse(cardIr)!!
        val (light, lightKey) = CardIrParser.selectFrame(card, "question", false, 800)!!
        assertEquals("light@800x1", lightKey)
        val (_, darkKey) = CardIrParser.selectFrame(card, "question", true, 800)!!
        assertEquals("dark@800x1", darkKey)
    }

    @Test
    fun `falls back to the light variant when there is no dark one`() {
        val card = CardIrParser.parse(cardIr)!!
        val (_, key) = CardIrParser.selectFrame(card, "question", true, 360)!!
        assertEquals("dark@800x1", key)
    }

    @Test
    fun `reads the width of a variant key`() {
        assertEquals(800, CardIrParser.variantWidth("light@800x1"))
        assertEquals(360, CardIrParser.variantWidth("dark@360x1.25"))
        assertEquals(0, CardIrParser.variantWidth("nonsense"))
    }

    @Test
    fun `a text node inherits only inheritable properties`() {
        val parent =
            mapOf(
                "font-size" to "22px",
                "color" to "rgb(31, 41, 55)",
                "width" to "800px",
                "margin-top" to "4px",
            )
        val inherited = CardIrStyle.childStyle(parent)
        assertEquals("22px", inherited["font-size"])
        assertEquals("rgb(31, 41, 55)", inherited["color"])
        assertFalse(inherited.containsKey("width"))
        assertFalse(inherited.containsKey("margin-top"))
    }

    @Test
    fun `parses colours`() {
        assertEquals(Color.argb(255, 31, 41, 55), CardIrStyle.parseColor("rgb(31, 41, 55)"))
        assertEquals(Color.argb(128, 0, 0, 0), CardIrStyle.parseColor("rgba(0, 0, 0, 0.5)"))
        assertEquals(Color.WHITE, CardIrStyle.parseColor("white"))
        assertThat(CardIrStyle.parseColor("not a colour"), nullValue())
    }

    @Test
    fun `a fully transparent colour is treated as absent`() {
        assertThat(CardIrStyle.color(mapOf("background-color" to "rgba(0, 0, 0, 0)"), "background-color"), nullValue())
    }

    @Test
    fun `parses lengths`() {
        assertEquals(800f, CardIrStyle.parseLength("800px"))
        assertEquals(19.1875f, CardIrStyle.parseLength("19.1875px"))
        assertThat(CardIrStyle.parseLength("50%"), nullValue())
        assertThat(CardIrStyle.parseLength(null), nullValue())
    }

    @Test
    fun `splits the font stack`() {
        val stack = CardIrStyle.fontStack(mapOf("font-family" to "\"Source Han Serif JP\", \"Source Han Serif CN\", serif"))
        assertThat(stack, hasSize(3))
        assertEquals("Source Han Serif JP", stack[0])
        assertEquals("serif", stack[2])
    }

    @Test
    fun `the sidecar layout matches the windows side`() {
        val directory = Files.createTempDirectory("card-ir-test").toFile()
        val card = CardIrParser.parse(cardIr)!!
        val file = CardIrStore.irFile(directory, 1706102393680)
        file.parentFile?.mkdirs()
        file.writeText(cardIr)
        assertTrue(CardIrStore.hasIr(directory, 1706102393680))
        val read = CardIrStore.read(directory, 1706102393680)
        assertThat(read, notNullValue())
        assertEquals(card.card_id, read!!.card_id)
        assertThat(CardIrStore.read(directory, 42), nullValue())
        directory.deleteRecursively()
    }
}
