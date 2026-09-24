// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import androidx.core.content.edit
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse
import com.ichi2.anki.BackupManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.SyncPreferences
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.reopen
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.worker.SyncMediaWorker
import timber.log.Timber

/**
 * The hub data plane client (SPEC-v2 §6.2): the desktop handed us a `SimpleServer` credential
 * through an enveloped `GET /hub/grant`, and now the *official* AnkiDroid custom-sync-server path
 * does the actual syncing - rslib gives us incremental changes and graves-propagates deletes for
 * free, which is the entire reason `hub` mode exists. We never speak the sync protocol ourselves.
 *
 * Preference writes go through the same keys the Settings → custom sync screen uses
 * ([SyncPreferences.CUSTOM_SYNC_URI] / [SyncPreferences.CUSTOM_SYNC_ENABLED]), and - like the
 * screen's own listener - we clear [SyncPreferences.CURRENT_SYNC_URI], because `getEndpoint()`
 * prefers "last endpoint the backend reported" over the custom URL and would silently ignore the
 * grant otherwise.
 *
 * Profile routing (v1's known defect): these prefs are per-profile, so we refuse a grant addressed
 * to another profile instead of quietly reconfiguring whatever profile happens to be open.
 */
object LanHubClient {
    /**
     * Applies [grant] and runs one collection sync. rslib reports its own progress in the app UI,
     * so there are no byte counts to hand back - callers log the round, not a counter.
     */
    suspend fun applyAndSync(
        grant: LanHubGrant,
        peerName: String = "",
    ) {
        val store = LanStore.instance
        if (grant.profile.isNotBlank() && grant.profile != store.activeProfileName) {
            throw LanPeerRejectException(
                "hub grant targets profile '${grant.profile}' but '${store.activeProfileName}' is open - switch profiles first",
            )
        }
        applyGrant(grant)
        val auth = login(grant)
        syncOnce(auth)
        Timber.i("LAN hub round with %s finished", peerName.ifBlank { grant.endpoint })
    }

    /** Point the existing custom-sync-server settings at the hub. Idempotent. */
    fun applyGrant(grant: LanHubGrant) {
        Prefs.sharedPrefs.edit {
            putString(SyncPreferences.CUSTOM_SYNC_URI, grant.endpoint)
            putBoolean(SyncPreferences.CUSTOM_SYNC_ENABLED, true)
            remove(SyncPreferences.CURRENT_SYNC_URI)
        }
        Timber.i("LAN hub grant applied for %s", grant.endpoint)
    }

    /**
     * The endpoint currently in force for the open profile, if the hub mode is what backs it -
     * lets the UI show "desktop hub: <endpoint>" without re-deriving the lookup rules.
     */
    fun currentHubEndpoint(): String? =
        if (Prefs.isCustomSyncEnabled) {
            Prefs.sharedPrefs.getString(SyncPreferences.CUSTOM_SYNC_URI, null)
        } else {
            null
        }

    /** Clears the grant again, e.g. when the peer is forgotten; safe when nothing was set. */
    fun clearGrantIfMatches(endpoint: String) {
        if (Prefs.sharedPrefs.getString(SyncPreferences.CUSTOM_SYNC_URI, null) == endpoint) {
            Prefs.sharedPrefs.edit {
                remove(SyncPreferences.CUSTOM_SYNC_URI)
                remove(SyncPreferences.CUSTOM_SYNC_ENABLED)
            }
        }
    }

    private suspend fun login(grant: LanHubGrant): SyncAuth {
        val auth =
            withCol {
                syncLogin(grant.username, grant.password, grant.endpoint)
            }
        // updateLogin keeps the official UI on the same account the hub round uses.
        Prefs.username = grant.username
        Prefs.hkey = auth.hkey
        return auth
    }

    /**
     * One hub round of collection sync. First contact answers `FULL_UPLOAD`/`FULL_DOWNLOAD`; the
     * documented discipline (SPEC-v2 §6.2) is backup → `close(forFullSync)` →
     * `fullUploadOrDownload` → `reopen(afterFullSync)` on the *same* manager-managed collection -
     * creating a second Collection handle here would hit "Anki already open".
     */
    private suspend fun syncOnce(auth: SyncAuth) {
        val response =
            withCol {
                syncCollection(auth, syncMedia = false)
            }
        Timber.i("LAN hub sync required: %s", response.required)
        when (response.required) {
            SyncCollectionResponse.ChangesRequired.NO_CHANGES -> {
                withCol { _loadScheduler() } // the scheduler may have been re-versioned
                SyncMediaWorker.start(appContext, auth)
            }
            SyncCollectionResponse.ChangesRequired.FULL_DOWNLOAD -> fullSync(auth, upload = false)
            SyncCollectionResponse.ChangesRequired.FULL_UPLOAD -> fullSync(auth, upload = true)
            SyncCollectionResponse.ChangesRequired.FULL_SYNC ->
                throw LanPeerRejectException(
                    "the hub wants a one-way full sync but left the direction to the user - open the normal sync UI once to choose",
                )
            else -> throw LanPeerRejectException("unexpected syncCollection response ${response.required}")
        }
    }

    private suspend fun fullSync(
        auth: SyncAuth,
        upload: Boolean,
    ) {
        Timber.i("LAN hub full %s", if (upload) "upload" else "download")
        withCol {
            try {
                // A full sync replaces the local collection; back it up first, exactly like the
                // DeckPicker's conflict dialog does.
                createBackup(BackupManager.getBackupDirectoryFromCollection(colDb), force = true, waitForCompletion = true)
                close(downgrade = false, forFullSync = true)
                fullUploadOrDownload(auth, serverUsn = null, upload = upload)
            } finally {
                reopen(afterFullSync = true)
            }
        }
        withCol { _loadScheduler() }
    }

    /** Cheap fingerprint of "the local user changed something", for the debounced sync trigger. */
    suspend fun localChangeFingerprint(): String =
        runCatching {
            withCol {
                "${db.queryLongScalar("select count(1) from revlog")}/${db.queryLongScalar("select count(1) from notes")}"
            }
        }.getOrDefault("")
}
