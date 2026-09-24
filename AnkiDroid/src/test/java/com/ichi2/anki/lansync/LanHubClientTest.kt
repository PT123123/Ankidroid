// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.SyncPreferences
import com.ichi2.anki.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task #4's whole point: a hub grant must land in the keys the *official* custom-sync-server path
 * reads - `syncBaseUrl` + `syncBaseUrl_switch` - and must clear `currentSyncUri`, which
 * `Sync.getEndpoint()` otherwise prefers over the custom URL, silently ignoring the grant.
 */
@RunWith(AndroidJUnit4::class)
class LanHubClientTest {
    private val grant =
        LanHubGrant(
            endpoint = "http://192.168.1.5:27700/sync/",
            username = "lan-sync",
            password = "hunter2",
            profile = "",
        )

    @Test
    fun `applying a grant writes the custom sync server keys and clears the endpoint override`() {
        // seed the state a previous AnkiWeb/custom sync would have left behind
        Prefs.sharedPrefs.edit {
            putString(SyncPreferences.CURRENT_SYNC_URI, "https://sync.ankiweb.net/sync/")
            putString(SyncPreferences.CUSTOM_SYNC_URI, "http://old.example/sync/")
        }

        LanHubClient.applyGrant(grant)

        assertEquals(grant.endpoint, Prefs.sharedPrefs.getString(SyncPreferences.CUSTOM_SYNC_URI, null))
        assertTrue(Prefs.sharedPrefs.getBoolean(SyncPreferences.CUSTOM_SYNC_ENABLED, false))
        assertNull(Prefs.sharedPrefs.getString(SyncPreferences.CURRENT_SYNC_URI, null))
        assertEquals(grant.endpoint, LanHubClient.currentHubEndpoint())
    }

    @Test
    fun `the grant is only visible as a hub endpoint while the custom switch is on`() {
        LanHubClient.applyGrant(grant)
        Prefs.sharedPrefs.edit { putBoolean(SyncPreferences.CUSTOM_SYNC_ENABLED, false) }
        assertNull(LanHubClient.currentHubEndpoint())
    }

    @Test
    fun `clearing a grant that no longer matches leaves a manual setting alone`() {
        LanHubClient.applyGrant(grant)
        // the user typed a different endpoint afterwards; forgetting the peer must not eat it
        val other = grant.copy(endpoint = "http://192.168.1.9:27700/sync/")
        LanHubClient.applyGrant(other)
        LanHubClient.clearGrantIfMatches(grant.endpoint)
        assertEquals(other.endpoint, Prefs.sharedPrefs.getString(SyncPreferences.CUSTOM_SYNC_URI, null))
        LanHubClient.clearGrantIfMatches(other.endpoint)
        assertNull(Prefs.sharedPrefs.getString(SyncPreferences.CUSTOM_SYNC_URI, null))
        assertFalse(Prefs.isCustomSyncEnabled)
    }

    @Test
    fun `key names match the constants the settings screen uses`() {
        // regression tripwires against SPEC-v2 §6.2: these literals are what rslib-backed sync reads
        assertEquals("syncBaseUrl", SyncPreferences.CUSTOM_SYNC_URI)
        assertEquals("syncBaseUrl_switch", SyncPreferences.CUSTOM_SYNC_ENABLED)
        assertEquals("currentSyncUri", SyncPreferences.CURRENT_SYNC_URI)
    }
}
