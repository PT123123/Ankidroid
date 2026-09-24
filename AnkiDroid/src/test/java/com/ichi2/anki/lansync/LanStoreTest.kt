// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LAN sync storage is device-level, not profile-level, and survives a process restart through a
 * JSON blob in SharedPreferences - both of which are only visible if we read it back with a fresh
 * [LanStore] over the same preference file.
 */
@RunWith(AndroidJUnit4::class)
class LanStoreTest {
    private lateinit var context: Context
    private lateinit var store: LanStore

    private fun freshStore() = LanStore(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE).edit { clear() }
        context.getSharedPreferences("lan_sync_secrets", Context.MODE_PRIVATE).edit { clear() }
        store = freshStore()
    }

    @Test
    fun `device identity is generated once and stays stable`() {
        val first = store.deviceId
        assertTrue(first.isNotBlank())
        assertEquals(first, freshStore().deviceId)
        assertEquals(first, store.selfInfo(5600).id)
    }

    @Test
    fun `self info advertises the port it is actually served on`() {
        val info = store.selfInfo(5607)
        assertEquals(5607, info.port)
        assertEquals(store.deviceName, info.name)
        assertEquals(LanProtocol.VERSION, info.protocol)
        assertEquals("android", info.platform)
    }

    @Test
    fun `renaming this device is persisted`() {
        store.deviceName = "  Kitchen tablet  "
        assertEquals("Kitchen tablet", freshStore().deviceName)
    }

    @Test
    fun `a peer is remembered once and refreshed in place`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        val (added, changed) = store.remember(info, "192.168.1.31", LanSource.UDP)
        assertTrue(changed)
        assertEquals("peer-a", added.id)
        assertEquals("192.168.1.31", added.ip)
        assertTrue(store.isOnline("peer-a"))

        val (refreshed, unchanged) = store.remember(info, "192.168.1.31", LanSource.UDP)
        assertFalse(unchanged)
        assertEquals(added, refreshed)
        assertEquals(1, store.devices.value.size)
    }

    @Test
    fun `a peer that moved keeps its history and gains the new address`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        store.remember(info, "192.168.1.31", LanSource.UDP)
        store.markSynced("peer-a")
        val later = lanNow()

        val (device, changed) = store.remember(info.copy(name = " ", port = 5603), "192.168.1.44", LanSource.NSD)
        assertTrue(changed)
        assertEquals("192.168.1.44", device.ip)
        assertEquals(5603, device.port)
        assertEquals(LanSource.NSD, device.source)
        assertEquals("Book phone", device.name) // a blank announce must not wipe a known name
        assertTrue(device.lastSyncAt in 1..later)
    }

    @Test
    fun `a manually added peer stays manual when discovery later finds it`() {
        val info = LanPeerInfo(id = "peer-a", name = "Desktop", port = 5600)
        store.remember(info, "192.168.1.50", LanSource.MANUAL)
        val (device, _) = store.remember(info, "192.168.1.50", LanSource.UDP)
        assertEquals(LanSource.MANUAL, device.source)
    }

    @Test
    fun `peers and log survive a restart`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        store.remember(info, "192.168.1.31", LanSource.UDP)
        store.log(
            LanLogEntry(
                at = 1L,
                deviceId = "peer-a",
                deviceName = "Book phone",
                direction = LanDirection.PUSH,
                result = LanResult.OK,
                bytes = 12L,
                millis = 3L,
                detail = "192.168.1.31",
            ),
        )

        val reopened = freshStore()
        assertEquals(store.devices.value, reopened.devices.value)
        assertEquals(1, reopened.log.value.size)
        assertEquals(
            LanDirection.PUSH,
            reopened.log.value
                .single()
                .direction,
        )
        // "online" is deliberately in-memory: after a restart a peer has not been heard from yet
        assertFalse(reopened.isOnline("peer-a"))
    }

    @Test
    fun `forgetting a peer drops it from the persisted list`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        store.remember(info, "192.168.1.31", LanSource.UDP)
        store.remember(info.copy(id = "peer-b", name = "Tablet"), "192.168.1.32", LanSource.UDP)
        store.forget("peer-a")
        assertEquals(listOf("peer-b"), store.devices.value.map { it.id })
        assertEquals(listOf("peer-b"), freshStore().devices.value.map { it.id })
        assertFalse(store.isOnline("peer-a"))
    }

    @Test
    fun `the log keeps the newest entries and can be cleared`() {
        for (i in 1..210) {
            store.log(
                LanLogEntry(
                    at = i.toLong(),
                    deviceId = "peer-$i",
                    deviceName = "peer",
                    direction = LanDirection.PROBE,
                    result = LanResult.OK,
                ),
            )
        }
        assertEquals(200, store.log.value.size)
        assertEquals(
            210L,
            store.log.value
                .first()
                .at,
        )
        assertEquals(
            11L,
            store.log.value
                .last()
                .at,
        )
        store.clearLog()
        assertTrue(store.log.value.isEmpty())
        assertTrue(freshStore().log.value.isEmpty())
    }

    @Test
    fun `unreadable storage falls back to empty lists instead of crashing`() {
        context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE).edit {
            putString("lanPeers", "{ this is not json")
            putString("lanLog", "nor is this")
        }
        val reopened = freshStore()
        assertTrue(reopened.devices.value.isEmpty())
        assertTrue(reopened.log.value.isEmpty())
    }

    @Test
    fun `the enabled flag is off until the user turns it on`() {
        assertFalse(store.enabled)
        store.enabled = true
        assertTrue(freshStore().enabled)
    }

    @Test
    fun `the v2 switches and the schedule default to the safe side`() {
        assertFalse(store.allowPlaintextV1)
        assertFalse(store.keepOnline)
        assertEquals(LanStore.SCHEDULE_OFF, store.scheduleSeconds)
        store.allowPlaintextV1 = true
        store.keepOnline = true
        store.scheduleSeconds = 300
        val reopened = freshStore()
        assertTrue(reopened.allowPlaintextV1)
        assertTrue(reopened.keepOnline)
        assertEquals(300, reopened.scheduleSeconds)
        assertTrue(LanStore.SCHEDULE_PERIODS.containsAll(listOf(10, 300, 1800)))
    }

    @Test
    fun `secrets live in their own preference file and are never written into the public one`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        store.remember(info, "192.168.1.31", LanSource.UDP)
        val secret = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val secretB64 = LanCrypto.base64(secret)
        val kid = LanCrypto.kidOf(secret)
        store.recordPairing(info, secret)

        assertTrue(store.isPaired("peer-a"))
        assertEquals(
            kid,
            store.devices.value
                .first { it.id == "peer-a" }
                .kid,
        )
        assertEquals(LanCrypto.securityCodeOf(secret), store.securityCodeFor("peer-a"))
        assertArrayEquals(secret, store.outboundFor("peer-a")?.second)
        assertArrayEquals(secret, store.secretForKid(kid))
        assertEquals("peer-a", store.peerIdForKid(kid))
        assertEquals(listOf(kid), store.pairedKids())

        val publicFile =
            context
                .getSharedPreferences("lan_sync", Context.MODE_PRIVATE)
                .all.entries
                .joinToString("|") { "${it.key}=${it.value}" }
        assertFalse("secret material leaked into lan_sync", publicFile.contains(secretB64.take(16)))
        val secretsFile =
            context
                .getSharedPreferences(
                    "lan_sync_secrets",
                    Context.MODE_PRIVATE,
                ).all.values
                .joinToString("|") { it.toString() }
        assertTrue(secretsFile.contains(secretB64.take(16)))
    }

    @Test
    fun `forgetting a peer drops its key material in both directions`() {
        val info = LanPeerInfo(id = "peer-a", name = "Book phone", port = 5600)
        store.remember(info, "192.168.1.31", LanSource.UDP)
        val secret = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val kid = LanCrypto.kidOf(secret)
        store.recordPairing(info, secret)
        store.noteRoundMode("peer-a", LanDataMode.HUB)

        store.forget("peer-a")
        assertNull(store.outboundFor("peer-a"))
        assertNull(store.secretForKid(kid))
        assertFalse(store.isPaired("peer-a"))
        assertEquals("", store.securityCodeFor("peer-a"))
        assertNull(freshStore().secretForKid(kid))
    }

    @Test
    fun `pairing sessions survive a restart`() {
        val session = store.pairing.beginLocal()
        val reopened = freshStore()
        assertEquals(session.pairCode, reopened.pairing.activeSession()?.pairCode)
        assertEquals(LanCommitOutcome.OK, reopened.pairing.commit(session.pairCode))
    }

    @Test
    fun `round details are kept newest-first and survive a restart`() {
        store.recordRound(
            LanRoundDetail(
                at = 1L,
                deviceId = "peer-a",
                deviceName = "A",
                mode = LanDataMode.APKG,
                legs = 2,
                okLegs = 2,
                pushBytes = 10L,
                pullBytes = 20L,
                millis = 5L,
            ),
        )
        store.recordRound(
            LanRoundDetail(at = 2L, deviceId = "peer-b", deviceName = "B", mode = LanDataMode.HUB, legs = 1, okLegs = 0, error = "BUSY"),
        )
        val reopened = freshStore()
        assertEquals(listOf(2L, 1L), reopened.rounds.value.map { it.at })
        assertEquals(
            LanDataMode.HUB,
            reopened.rounds.value
                .first()
                .mode,
        )
        assertEquals(
            "BUSY",
            reopened.rounds.value
                .first()
                .error,
        )
    }

    @Test
    fun `v1 trust table entries come out unpaired after the one-shot migration`() {
        val v1Devices =
            """{"devices":[{"id":"old","name":"Old phone","ip":"192.168.1.7","port":5600,""" +
                """"source":"UDP","addedAt":5,"protocol":1,"paired":true}]}"""
        context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE).edit {
            clear()
            putString("lanPeers", v1Devices)
            putBoolean("lanMigratedToV2", false)
        }
        val migrated = freshStore()
        val device = migrated.devices.value.first()
        assertEquals("old", device.id)
        assertFalse(device.paired)
        assertTrue(migrated.log.value.any { it.detail.contains("migrated from v1") })
        // and the migration is one-shot: with the flag set it does not touch anything again
        context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE).edit {
            putString("lanPeers", v1Devices)
        }
        assertTrue(
            freshStore()
                .devices.value
                .first()
                .paired,
        )
    }
}
