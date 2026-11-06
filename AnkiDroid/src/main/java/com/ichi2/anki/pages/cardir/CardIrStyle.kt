// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Helpers for the resolved CSS values the compiler baked into the IR.
 *
 * There is no cascade here: the compiler already resolved every value, so
 * these functions only translate a string such as `rgb(31, 41, 55)` or
 * `800px` into a number.
 */
object CardIrStyle {
    /**
     * The CSS properties a text node inherits from its element ancestor.
     *
     * Geometry is deliberately absent: a text leaf must never inherit the
     * width or the margins of the box it sits in.
     */
    val INHERITED_PROPERTIES =
        listOf(
            "color",
            "background-color",
            "fill",
            "fill-opacity",
            "fill-rule",
            "stroke",
            "stroke-opacity",
            "stroke-width",
            "stroke-linecap",
            "stroke-linejoin",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "letter-spacing",
            "line-height",
            "text-align",
            "text-decoration-color",
            "text-decoration-line",
            "text-decoration-style",
            "text-indent",
            "text-transform",
            "white-space",
            "word-break",
            "word-spacing",
            "direction",
            "writing-mode",
            "unicode-bidi",
            "vertical-align",
            "visibility",
            "list-style-position",
            "list-style-type",
        )

    /**
     * The style of a node: its own style, or the inherited one when the node
     * carries none (which is the case for text runs).
     */
    fun inheritedStyle(
        inherited: Map<String, String>,
        own: Map<String, String>,
    ): Map<String, String> {
        if (own.isEmpty()) return inherited
        val merged = HashMap(inherited)
        merged.putAll(own)
        return merged
    }

    /** The style a node passes down to its children. */
    fun childStyle(parent: Map<String, String>): Map<String, String> {
        val child = HashMap<String, String>(parent.size + INHERITED_PROPERTIES.size)
        for (key in INHERITED_PROPERTIES) {
            parent[key]?.let { child[key] = it }
        }
        return child
    }

    /** Parses a resolved CSS length into pixels. Percentages are not resolved here. */
    fun parseLength(value: String?): Float? {
        val text = value?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.endsWith("px")) return text.dropLast(2).trim().toFloatOrNull()
        if (text.endsWith("%")) return null
        return text.toFloatOrNull()
    }

    /** Parses a resolved CSS length, falling back to [fallback]. */
    fun length(
        style: Map<String, String>,
        key: String,
        fallback: Float = 0f,
    ): Float = parseLength(style[key]) ?: fallback

    /** Parses a resolved colour. Returns null when the value is not a colour. */
    fun parseColor(value: String?): Int? {
        val text = value?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.startsWith("rgb", ignoreCase = true)) {
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open < 0 || close <= open) return null
            val parts = text.substring(open + 1, close).split(',').map { it.trim() }
            if (parts.size < 3) return null
            val r = parts[0].toIntOrNull() ?: return null
            val g = parts[1].toIntOrNull() ?: return null
            val b = parts[2].toIntOrNull() ?: return null
            val a = if (parts.size >= 4) (parts[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f) else 1f
            // CSS rounds the alpha to the nearest 0-255 step; truncating would
            // make `rgba(0, 0, 0, 0.5)` one step too transparent.
            return Color.argb((a * 255f).roundToInt(), r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
        return NAMED_COLORS[text.lowercase()]
    }

    /** The colour of a style entry, or null when it is transparent or absent. */
    fun color(
        style: Map<String, String>,
        key: String,
    ): Int? {
        val color = parseColor(style[key]) ?: return null
        return if (Color.alpha(color) == 0) null else color
    }

    /**
     * Splits a CSS font stack into family names.
     *
     * `font-family` looks like `"Source Han Serif JP", "Source Han Serif CN", serif`.
     */
    fun fontStack(style: Map<String, String>): List<String> {
        val raw = style["font-family"] ?: return emptyList()
        return raw.split(',').mapNotNull { part ->
            var name = part.trim()
            if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
                name = name.substring(1, name.length - 1)
            } else if (name.length >= 2 && name.startsWith('\'') && name.endsWith('\'')) {
                name = name.substring(1, name.length - 1)
            }
            name.takeIf { it.isNotEmpty() }
        }
    }

    /** The font size in pixels. */
    fun fontSize(style: Map<String, String>): Float = length(style, "font-size", 16f)

    /** The font weight as an Android [android.graphics.Typeface] style flag. */
    fun fontStyle(style: Map<String, String>): Int {
        val weight = style["font-weight"]?.trim()?.toIntOrNull() ?: 400
        val bold = weight >= 600
        val italic = style["font-style"]?.trim()?.lowercase() in setOf("italic", "oblique")
        return when {
            bold && italic -> android.graphics.Typeface.BOLD_ITALIC
            bold -> android.graphics.Typeface.BOLD
            italic -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
    }

    /** The border width of one side in pixels. */
    fun borderWidth(
        style: Map<String, String>,
        side: String,
    ): Float = length(style, "border-$side-width", 0f)

    /** The corner radius in pixels. */
    fun cornerRadius(
        style: Map<String, String>,
        corner: String,
    ): Float = length(style, "border-$corner-radius", 0f)

    private val NAMED_COLORS =
        mapOf(
            "transparent" to Color.TRANSPARENT,
            "black" to Color.BLACK,
            "white" to Color.WHITE,
            "red" to Color.RED,
            "green" to Color.GREEN,
            "blue" to Color.BLUE,
            "yellow" to Color.YELLOW,
            "gray" to Color.GRAY,
            "grey" to Color.GRAY,
            "cyan" to Color.CYAN,
            "magenta" to Color.MAGENTA,
        )
}
