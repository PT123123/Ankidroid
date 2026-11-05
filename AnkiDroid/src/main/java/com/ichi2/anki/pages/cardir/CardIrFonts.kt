// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Typeface
import java.io.File

/**
 * Resolves the `font-family` of a node to a real [Typeface].
 *
 * The card's own fonts travel with the media folder, not with the device, so
 * the renderer loads the otf/ttf files from the media directory and matches
 * them against the CSS font stack by name. Anything that is not in the media
 * folder falls back to the system font of the same name.
 */
class CardIrFonts(
    private val mediaFonts: Map<String, Typeface>,
) {
    /**
     * The typeface for a style.
     *
     * The CSS stack order is kept: the first family that can be resolved
     * wins, which is what a browser does as well.
     */
    fun resolve(style: Map<String, String>): Typeface {
        val stack = CardIrStyle.fontStack(style)
        val flag = CardIrStyle.fontStyle(style)
        for (family in stack) {
            val media = mediaFonts[normalize(family)]
            if (media != null) {
                return if (flag == Typeface.BOLD) Typeface.create(media, Typeface.BOLD) else media
            }
            if (isGeneric(family)) {
                return Typeface.create(family, flag)
            }
            val system = Typeface.create(family, flag)
            if (system != null) {
                return system
            }
        }
        return Typeface.create(Typeface.DEFAULT, flag)
    }

    companion object {
        /** No custom fonts: everything uses the system font. */
        val EMPTY = CardIrFonts(emptyMap())

        private val FONT_EXTENSIONS = listOf("otf", "ttf", "ttc", "otc")

        /**
         * Loads the fonts of a media folder.
         *
         * @param mediaDirectory the collection's media folder, may be null
         */
        fun load(mediaDirectory: File?): CardIrFonts {
            if (mediaDirectory == null || !mediaDirectory.isDirectory) return EMPTY
            val fonts = HashMap<String, Typeface>()
            val files = mediaDirectory.listFiles() ?: return EMPTY
            for (file in files) {
                val extension = file.extension.lowercase()
                if (extension !in FONT_EXTENSIONS) continue
                val typeface =
                    try {
                        Typeface.createFromFile(file)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                for (key in fontKeys(file.name)) {
                    fonts.putIfAbsent(key, typeface)
                }
            }
            return CardIrFonts(fonts)
        }

        /**
         * The names a font file can be found under.
         *
         * The media folder keeps the original file names (`_SourceHanSerifJP-Medium.otf`),
         * while the CSS refers to the family (`Source Han Serif JP`), so both the
         * full name and the name without the weight suffix are registered.
         */
        private fun fontKeys(fileName: String): List<String> {
            val base = fileName.substringBeforeLast('.')
            val trimmed = base.trimStart('_', '-')
            val keys = HashSet<String>()
            keys.add(normalize(trimmed))
            // `_SourceHanSerifJP-Medium` also answers to `Source Han Serif JP`.
            val withoutWeight = trimmed.substringBeforeLast('-')
            if (withoutWeight.isNotEmpty()) {
                keys.add(normalize(withoutWeight))
            }
            return keys.toList()
        }

        /** Lower case, letters and digits only. */
        private fun normalize(name: String): String =
            buildString(name.length) {
                for (character in name) {
                    if (character.isLetterOrDigit()) {
                        append(character.lowercaseChar())
                    }
                }
            }

        private fun isGeneric(family: String): Boolean =
            family.lowercase() in setOf("serif", "sans-serif", "monospace", "cursive", "fantasy")
    }
}
