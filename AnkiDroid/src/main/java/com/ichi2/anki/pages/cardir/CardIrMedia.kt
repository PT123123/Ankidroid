// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.BitmapFactory
import java.io.File

/** Media checks shared by Reviewer preflight and the Canvas painter. */
internal object CardIrMedia {
    /** Resolve a media reference only when it stays inside the collection media directory. */
    fun resolve(
        directory: File?,
        source: String?,
    ): File? {
        if (directory == null || source.isNullOrBlank()) return null
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (!root.isDirectory) return null
        val file = runCatching { File(root, source).canonicalFile }.getOrNull() ?: return null
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!file.path.startsWith(rootPrefix) || !file.isFile) return null
        return file
    }

    /** Return false for missing, escaping, or undecodable raster image references. */
    fun imagesAreRenderable(
        node: IrNode,
        mediaDirectory: File?,
    ): Boolean {
        if (node.type == "image") {
            val file = resolve(mediaDirectory, node.src) ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        }
        return node.children.all { imagesAreRenderable(it, mediaDirectory) }
    }
}
