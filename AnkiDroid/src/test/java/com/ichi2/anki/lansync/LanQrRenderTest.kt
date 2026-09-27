// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The display half of the pairing QR (SPEC-v2 §4.7): the ticket text has to survive being drawn as
 * a bitmap and read back by a decoder. A camera scan cannot be exercised here, but a real decoder
 * reading our own rendering proves the code is dense enough, quiet-zoned, and carries the exact
 * bytes the peer expects - which is the part this side controls.
 */
@RunWith(AndroidJUnit4::class)
class LanQrRenderTest {
    private val id = "aaaaaaaa-1111-4111-8111-111111111111"

    /** Read a rendered bitmap back with zxing's decoder, the way a scanning app would. */
    private fun readBack(
        text: String,
        hints: Map<DecodeHintType, *> =
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                // The ticket is UTF-8 (SPEC-v2 §4.7). zxing's default for byte-mode QR is ISO-8859-1,
                // which turns a CJK device name into junk, so a scanner has to ask for UTF-8.
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
    ): String {
        val bitmap = LanQrRender.bitmap(text, 336)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val image = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(image, hints).text
    }

    @Test
    fun `a rendered ticket decodes back to the same text`() {
        val text = LanPairQr.encode(id, "我的机子", "desktop", "192.168.1.20", 5611, "004200")
        assertEquals(text, readBack(text))
    }

    @Test
    fun `a scanner that forgot to ask for UTF-8 still hands us a parsable ticket`() {
        // Only `nm` is affected by the charset guess; everything pairing depends on is ASCII, and
        // the name finally stored comes from the peer's own commit answer, not from this QR.
        val text = LanPairQr.encode(id, "我的机子", "desktop", "192.168.1.20", 5611, "004200")
        val junk = readBack(text, mapOf(DecodeHintType.TRY_HARDER to true))
        val ticket = LanPairQr.decode(junk)
        assertEquals(id, ticket.deviceId)
        assertEquals("004200", ticket.pairCode)
        assertEquals("192.168.1.20", ticket.host)
        assertEquals(5611, ticket.port)
    }

    @Test
    fun `the rendered code is not a blank field`() {
        val bitmap = LanQrRender.bitmap(LanPairQr.encode(id, "N", "android", "10.0.0.7", 5600, "123456"), 336)
        assertEquals(bitmap.width, bitmap.height)
        assert(bitmap.getPixel(0, 0) != bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) {
            "a QR is never one flat colour; a uniform bitmap means nothing was drawn"
        }
    }

    @Test
    fun `refuses to render nothing`() {
        assertThrows(IllegalArgumentException::class.java) { LanQrRender.bitmap(" ", 336) }
    }
}
