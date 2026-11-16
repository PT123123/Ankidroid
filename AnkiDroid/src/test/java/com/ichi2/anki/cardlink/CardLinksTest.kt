// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.cardlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Gate for `[[cardId]]` bidirectional links: query shape, classification, labels, backlink search. */
@RunWith(AndroidJUnit4::class)
class CardLinksTest : RobolectricTest() {
    @Test
    fun searchQueryIsAQuotedRegex() {
        // unquoted ( ) | would be eaten by the search parser before reaching the regex engine
        assertEquals("re:\"\\[\\[123(\\|[^\\]]*)?\\]\\]\"", CardLinks.searchQuery(123))
        assertEquals("[[1793887430884]]", CardLinks.text(1793887430884L))
    }

    @Test
    fun onlyPositiveDigitStringsAreCardIds() {
        assertTrue(CardLinks.isCardId("1793887430884"))
        assertTrue(CardLinks.isCardId("42"))
        assertFalse(CardLinks.isCardId(""))
        assertFalse(CardLinks.isCardId("标题"))
        assertFalse(CardLinks.isCardId("12a"))
        assertFalse(CardLinks.isCardId("0"))
        assertFalse(CardLinks.isCardId("-5"))
    }

    @Test
    fun plainLabelStripsHtmlAndTruncates() {
        assertEquals("私 わたし 我", CardLinks.plainLabel("<b>私</b>&nbsp;わたし<br>我"))
        assertEquals("a&b", CardLinks.plainLabel("a&amp;b"))
        val label = CardLinks.plainLabel("字".repeat(60))
        assertEquals(41, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun backlinksFindPlainAndAliasLinksButNotLongerIds() =
        runBlocking {
            val targetCard = addBasicNote("目标正面", "背").firstCard().id
            val plain = addBasicNote("plain 链接者", "参见 [[$targetCard]]")
            val alias = addBasicNote("别名链接者", "[[$targetCard|目标]]")
            val longer = addBasicNote("更长 id", "[[${targetCard}9]]")

            val links = CardLinks.backlinks(targetCard)
            assertEquals(2, links.size)
            val linkedNoteIds = links.map { col.getCard(it.cardId).nid }
            assertTrue(linkedNoteIds.contains(plain.id))
            assertTrue(linkedNoteIds.contains(alias.id))
            assertFalse(linkedNoteIds.contains(longer.id))
            assertTrue(links.all { it.label.isNotEmpty() })

            // control: a card nobody links to has no backlinks
            assertTrue(CardLinks.backlinks(longer.firstCard().id).isEmpty())
        }

    @Test
    fun linksFromTheCardsOwnNoteAreExcluded() =
        runBlocking {
            val note = addBasicNote("自链接", "")
            val cardId = note.firstCard().id
            note.setField(1, "见 [[$cardId]]")
            col.updateNote(note)
            assertTrue(CardLinks.backlinks(cardId).isEmpty())
        }
}
