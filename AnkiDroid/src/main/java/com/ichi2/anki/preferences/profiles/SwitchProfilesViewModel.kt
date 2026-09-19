// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Ashish Yadav <mailtoashish693@gmail.com>

package com.ichi2.anki.preferences.profiles

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.multiprofile.ProfileId
import com.ichi2.anki.multiprofile.ProfileManager
import com.ichi2.anki.multiprofile.ProfileName
import com.ichi2.anki.multiprofile.ProfileSwitchGuard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State holder for [SwitchProfilesFragment]. Backed by the app-wide
 * [ProfileManager]: lists the registered profiles and drives create/rename/delete
 * plus the guarded switch-and-restart flow.
 */
class SwitchProfilesViewModel : ViewModel() {
    private val profileManager: ProfileManager
        get() = AnkiDroidApp.profileManager

    /** Profiles shown in the list. */
    private val _profiles = MutableStateFlow<List<ProfileItem>>(emptyList())
    val profiles: StateFlow<List<ProfileItem>> = _profiles

    private val _activeProfileId = MutableStateFlow<ProfileId?>(null)
    val activeProfileId: StateFlow<ProfileId?> = _activeProfileId

    private val _isAddProfileDialogVisible = MutableStateFlow(false)
    val isAddProfileDialogVisible: StateFlow<Boolean> = _isAddProfileDialogVisible

    /** Set while the rename dialog is open for this profile. */
    private val _renameTarget = MutableStateFlow<ProfileItem?>(null)
    val renameTarget: StateFlow<ProfileItem?> = _renameTarget

    private val _deleteTarget = MutableStateFlow<ProfileItem?>(null)
    val deleteTarget: StateFlow<ProfileItem?> = _deleteTarget

    /** Set while the switch confirmation dialog is open for this profile. */
    private val _switchTarget = MutableStateFlow<ProfileItem?>(null)
    val switchTarget: StateFlow<ProfileItem?> = _switchTarget

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Emitted once the active profile has been switched: the UI must restart the app. */
    private val _profileSwitched = Channel<Unit>(Channel.CONFLATED)
    val profileSwitched = _profileSwitched.receiveAsFlow()

    init {
        refresh()
    }

    private fun refresh() {
        runCatching {
            _activeProfileId.value = profileManager.activeProfileId
            _profiles.value =
                profileManager
                    .getAllProfiles()
                    .map { (id, metadata) -> ProfileItem(id, metadata.displayName.value) }
                    .sortedWith(
                        compareByDescending<ProfileItem> { it.id.isDefault() }
                            .thenBy { it.name.lowercase() },
                    )
        }.onFailure { Timber.w(it, "Failed to load profiles") }
    }

    fun showAddProfileDialog() {
        _isAddProfileDialogVisible.value = true
    }

    fun dismissAddProfileDialog() {
        _isAddProfileDialogVisible.value = false
    }

    /** Called when the user confirms a valid name in the add-profile dialog. */
    fun addProfile(name: ProfileName) {
        dismissAddProfileDialog()
        Timber.i("Add profile confirmed (%d chars)", name.value.length)
        runCatching { profileManager.createNewProfile(name) }
            .onFailure {
                Timber.w(it, "Failed to create profile")
                sendMessage(R.string.profile_switch_failed)
            }
        refresh()
    }

    fun showRenameDialog(profile: ProfileItem) {
        _renameTarget.value = profile
    }

    fun dismissRenameDialog() {
        _renameTarget.value = null
    }

    fun renameProfile(name: ProfileName) {
        val target = _renameTarget.value ?: return
        runCatching { profileManager.renameProfile(target.id, name) }
            .onFailure { Timber.w(it, "Failed to rename profile %s", target.id) }
        dismissRenameDialog()
        refresh()
    }

    fun showDeleteDialog(profile: ProfileItem) {
        if (profileManager.isActive(profile.id)) {
            Timber.i("Delete ignored: %s is active", profile.id)
            return
        }
        _deleteTarget.value = profile
    }

    fun dismissDeleteDialog() {
        _deleteTarget.value = null
    }

    fun deleteProfile() {
        val target = _deleteTarget.value ?: return
        dismissDeleteDialog()
        viewModelScope.launch {
            try {
                CollectionManager.ensureClosed()
                profileManager.deleteProfile(target.id)
                refresh()
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete profile %s", target.id)
                sendMessage(R.string.profile_delete_failed)
            }
        }
    }

    fun showSwitchDialog(profile: ProfileItem) {
        if (profileManager.isActive(profile.id)) return
        _switchTarget.value = profile
    }

    fun dismissSwitchDialog() {
        _switchTarget.value = null
    }

    fun switchProfile() {
        val target = _switchTarget.value ?: return
        dismissSwitchDialog()
        viewModelScope.launch {
            try {
                // Wait for the collection queue to drain, then hand the collection over safely.
                CollectionManager.ensureClosed()
                val guard = ProfileSwitchGuard(profileManager, emptyList())
                when (guard(target.id)) {
                    is ProfileSwitchGuard.Result.Success -> _profileSwitched.send(Unit)
                    is ProfileSwitchGuard.Result.Blocked -> sendMessage(R.string.profile_switch_failed)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to switch to profile %s", target.id)
                sendMessage(R.string.profile_switch_failed)
            }
        }
    }

    private fun sendMessage(
        @StringRes messageId: Int,
    ) {
        _messages.trySend(messageId)
    }
}
