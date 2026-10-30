// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardviewer

import com.ichi2.anki.settings.Prefs

/**
 * A note type nobody restyled ships `.card { font-family: arial; font-size: 20px; text-align:
 * center; color: black; background-color: white }` at the top of the card HTML, inside the
 * `<style>` which rides along with the rendered field text. The rule matches the
 * `<body class="card card1 …">` element, so as a class selector it outranks the app's `BODY` font
 * rule and the day/night colours of `flashcard.css`: every untouched note type renders as an
 * opaque white Arial slab whatever theme the user picked.
 *
 * Dropping the three declarations the theme owns leaves `font-size`, `line-height` and `text-align`
 * alone, so the card keeps the size and centring Anki chose for it.
 *
 * A block is rewritten only while every one of its declarations is verbatim stock, so a note type
 * the user styled — even by changing a single value — is passed through byte for byte.
 */
object StockCardCss {
    private val block = Regex("""(^|[}>])\s*\.card\s*\{([^{}]*)\}""")

    /** `rslib/src/notetype/styling.css`; older collections are the same set without `line-height`. */
    private val stock =
        setOf(
            "font-family: arial",
            "font-size: 20px",
            "line-height: 1.5",
            "text-align: center",
            "color: black",
            "background-color: white",
        )
    private val themeOwned = setOf("font-family", "color", "background-color")

    fun retheme(html: String): String {
        if (!Prefs.stockCardCssRethemed) return html
        return html.replace(block) { match ->
            val declarations =
                match.groupValues[2]
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (declarations.any { it.canonical() !in stock }) {
                match.value
            } else {
                val kept = declarations.filterNot { name(it) in themeOwned }
                "${match.groupValues[1]}.card {${kept.joinToString(" ") { "$it;" }}}"
            }
        }
    }

    private fun name(declaration: String): String = declaration.substringBefore(':').trim().lowercase()

    private fun String.canonical(): String = replace(Regex("""\s*:\s*"""), ": ").trim().lowercase()
}
