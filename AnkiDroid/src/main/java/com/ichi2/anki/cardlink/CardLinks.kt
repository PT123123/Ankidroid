// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardlink

import com.ichi2.anki.CollectionManager.withCol

/**
 * `[[cardId]]` bidirectional links. The markup stays raw in field text; renderers turn it
 * into clickable links and [backlinks] finds the cards pointing at a given card.
 */
object CardLinks {
    data class Backlink(
        val cardId: Long,
        val label: String,
    )

    fun isCardId(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' } && text.toLongOrNull()?.let { it > 0 } == true

    fun text(cardId: Long): String = "[[$cardId]]"

    // The value must stay quoted: unquoted ( ) | are search-parser operators and would
    // mangle the pattern before it reaches the regex engine.
    fun searchQuery(cardId: Long): String = "re:\"\\[\\[$cardId(\\|[^\\]]*)?\\]\\]\""

    fun plainLabel(html: String): String {
        val text =
            html
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
        return if (text.length > 40) text.take(40) + "…" else text
    }

    /** Cards whose notes contain `[[cardId]]` or `[[cardId|alias]]`, excluding the card's own note. */
    suspend fun backlinks(cardId: Long): List<Backlink> =
        withCol {
            val selfNoteId = getCard(cardId).nid
            val noteIds = findNotes(searchQuery(cardId)).filter { it != selfNoteId }.take(100)
            if (noteIds.isEmpty()) return@withCol emptyList()
            findCards("nid:" + noteIds.joinToString(","))
                .filter { it != cardId }
                .take(12)
                .map { linkedCardId ->
                    val firstField = getNote(getCard(linkedCardId).nid).fields.firstOrNull().orEmpty()
                    Backlink(linkedCardId, plainLabel(firstField).ifEmpty { text(linkedCardId) })
                }
        }
}
