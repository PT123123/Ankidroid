// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2025 Ashish Yadav <mailtoashish693@gmail.com>

package com.ichi2.anki.preferences.profiles

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ichi2.anki.DeckPicker
import com.ichi2.compose.theme.AnkiDroidTheme
import kotlin.system.exitProcess

/**
 * Lets the user switch between profiles.
 *
 * Thin host for the Compose [SwitchProfilesScreen]. Stays a Fragment because
 * preference_headers.xml launches it by class name.
 */
class SwitchProfilesFragment : Fragment() {
    private val viewModel: SwitchProfilesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AnkiDroidTheme {
                    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
                    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
                    val isAddProfileDialogVisible by viewModel.isAddProfileDialogVisible.collectAsStateWithLifecycle()
                    val renameTarget by viewModel.renameTarget.collectAsStateWithLifecycle()
                    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()
                    val switchTarget by viewModel.switchTarget.collectAsStateWithLifecycle()
                    var message by remember { mutableStateOf<Int?>(null) }

                    LaunchedEffect(Unit) {
                        viewModel.messages.collect { message = it }
                    }
                    LaunchedEffect(Unit) {
                        viewModel.profileSwitched.collect { restartWithActiveProfile() }
                    }

                    SwitchProfilesScreen(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        isAddProfileDialogVisible = isAddProfileDialogVisible,
                        renameTarget = renameTarget,
                        deleteTarget = deleteTarget,
                        switchTarget = switchTarget,
                        message = message,
                        onMessageShown = { message = null },
                        onNavigateUp = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                        onAddProfileClick = viewModel::showAddProfileDialog,
                        onAddProfileConfirm = viewModel::addProfile,
                        onAddProfileDismiss = viewModel::dismissAddProfileDialog,
                        onRenameRequest = viewModel::showRenameDialog,
                        onRenameDismiss = viewModel::dismissRenameDialog,
                        onRenameConfirm = viewModel::renameProfile,
                        onDeleteProfile = viewModel::showDeleteDialog,
                        onDeleteDismiss = viewModel::dismissDeleteDialog,
                        onDeleteConfirm = viewModel::deleteProfile,
                        onSwitchProfile = viewModel::showSwitchDialog,
                        onSwitchDismiss = viewModel::dismissSwitchDialog,
                        onSwitchConfirm = viewModel::switchProfile,
                    )
                }
            }
        }

    /**
     * The persisted active profile changed: request a fresh launch of the home screen and
     * end this process immediately (the `startActivity` binder call has already been
     * delivered). The system recreates the process, so the Application re-runs
     * `attachBaseContext` with the new profile's storage environment.
     */
    private fun restartWithActiveProfile() {
        val appContext = requireContext().applicationContext
        val intent =
            Intent(appContext, DeckPicker::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        appContext.startActivity(intent)
        requireActivity().finish()
        exitProcess(0)
    }
}
