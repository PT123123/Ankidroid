// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a pairing ticket text (SPEC-v2 §4.7) into a bitmap for the screen. Only the QR *rendering*
 * lives here: the ticket itself is built by [LanSyncManager.buildPairingTicket], and no camera or
 * scanner class is needed to show one.
 */
object LanQrRender {
    /** Quiet zone in modules: the spec minimum, so the code still fits a small card. */
    private const val QUIET_ZONE = 2

    fun bitmap(
        text: String,
        sizePx: Int,
    ): Bitmap {
        require(text.isNotBlank()) { "nothing to render" }
        val matrix =
            QRCodeWriter()
                .encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    mapOf(
                        EncodeHintType.MARGIN to QUIET_ZONE,
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                        // The writer otherwise falls back to ISO-8859-1 and turns a CJK device name
                        // in the ticket into '?' (SPEC-v2 §4.7 pins the text as UTF-8).
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                    ),
                )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
