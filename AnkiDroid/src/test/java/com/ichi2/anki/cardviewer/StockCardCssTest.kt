// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.testutils.ext.addNote
import com.ichi2.anki.libanki.testutils.ext.newNote
import com.ichi2.anki.settings.Prefs
import org.intellij.lang.annotations.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockCardCssTest : RobolectricTest() {
    @Test
    fun `current stock block loses the theme-owned declarations`() {
        @Language("CSS")
        val stock =
            """
            .card {
                font-family: arial;
                font-size: 20px;
                line-height: 1.5;
                text-align: center;
                color: black;
                background-color: white;
            }
            """.trimIndent()
        assertEquals(".card {font-size: 20px; line-height: 1.5; text-align: center;}", StockCardCss.retheme(stock))
    }

    @Test
    fun `older stock block without line-height is handled too`() {
        @Language("CSS")
        val stock =
            """
            .card {
             font-family: arial;
             font-size: 20px;
             text-align: center;
             color: black;
             background-color: white;
            }
            """.trimIndent()
        assertEquals(".card {font-size: 20px; text-align: center;}", StockCardCss.retheme(stock))
    }

    /** The rule rides inside a `<style>` which the rendered field text is prefixed with. */
    @Test
    fun `whole card HTML keeps its content and loses the white slab`() {
        @Language("HTML")
        val html =
            """
            <style>.card {
                font-family: arial;
                font-size: 20px;
                line-height: 1.5;
                text-align: center;
                color: black;
                background-color: white;
            }
            </style><div>front text</div>
            """.trimIndent()
        val rethemed = StockCardCss.retheme(html)
        assertTrue(rethemed.startsWith("<style>.card {font-size: 20px; line-height: 1.5; text-align: center;}"))
        assertTrue(rethemed.endsWith("</style><div>front text</div>"))
    }

    @Test
    fun `restyled note types pass through untouched`() {
        // changing one value is enough: "Noto Serif" is not a stock declaration
        @Language("CSS")
        val withCustomFont =
            """
            .card {
                font-family: "Noto Serif";
                font-size: 20px;
            }
            """.trimIndent()
        assertEquals(withCustomFont, StockCardCss.retheme(withCustomFont))

        @Language("CSS")
        val withLargerText = ".card {\n    font-family: arial;\n    font-size: 24px;\n}"
        assertEquals(withLargerText, StockCardCss.retheme(withLargerText))

        @Language("CSS")
        val withOneAddedDeclaration =
            """
            .card {
                font-family: arial;
                font-size: 20px;
                line-height: 1.5;
                text-align: center;
                color: black;
                background-color: white;
                margin: 0 8px;
            }
            """.trimIndent()
        assertEquals(withOneAddedDeclaration, StockCardCss.retheme(withOneAddedDeclaration))
    }

    @Test
    fun `selectors which are not exactly the card rule are left alone`() {
        @Language("CSS")
        val ordinal = ".card1 { background-color: white; }"
        assertEquals(ordinal, StockCardCss.retheme(ordinal))

        @Language("CSS")
        val grouped = ".card, .night_mode { background-color: white; }"
        assertEquals(grouped, StockCardCss.retheme(grouped))

        @Language("CSS")
        val descendant = "html .card { background-color: white; }"
        assertEquals(descendant, StockCardCss.retheme(descendant))
    }

    @Test
    fun `a card rule following another rule is still recognised`() {
        @Language("CSS")
        val css = ".hint { color: grey; }.card {\n    font-family: arial;\n    background-color: white;\n}"
        assertEquals(".hint { color: grey; }.card {}", StockCardCss.retheme(css))
    }

    @Test
    fun `switching the setting off renders like the desktop`() {
        @Language("CSS")
        val stock = ".card {\n    font-family: arial;\n    background-color: white;\n}"
        Prefs.stockCardCssRethemed = false
        assertEquals(stock, StockCardCss.retheme(stock))
        Prefs.stockCardCssRethemed = true
    }

    /**
     * The fixtures above are transcribed from `rslib/src/notetype/styling.css`, so they could drift
     * from the backend actually bundled here. This one asks that backend what it emits.
     */
    @Test
    fun `a card rendered by the bundled backend loses its stock colours`() {
        val note = col.newNote()
        note.setItem("Front", "front text")
        note.setItem("Back", "back text")
        col.addNote(note)
        val question = note.firstCard().question(col)
        // control: the stock rule is really there before the rewrite
        assertTrue("expected the stock .card rule in:\n$question", question.contains("font-family: arial"))
        val rethemed = StockCardCss.retheme(question)
        assertFalse("the stock rule survived the rewrite:\n$rethemed", rethemed.contains("font-family: arial"))
        assertTrue(rethemed.contains("font-size"))
        assertTrue(rethemed.contains("front text"))
    }
}
