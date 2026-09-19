// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Ashish Yadav <mailtoashish693@gmail.com>

package com.ichi2.anki.preferences.profiles

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.multiprofile.ProfileId
import com.ichi2.anki.multiprofile.ProfileName
import com.ichi2.compose.theme.AnkiDroidTheme
import com.ichi2.compose.theme.dimensions
import com.ichi2.compose.ui.components.AnkiDroidExtendedFab
import com.ichi2.compose.ui.preview.ThemePreviews
import androidx.appcompat.R as AppCompatR

/**
 * Stateless screen listing the user's profiles, with a FAB to add a new one.
 * Tapping a non-active row switches to that profile (after confirmation).
 * State lives in [SwitchProfilesViewModel], this only renders and forwards events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchProfilesScreen(
    profiles: List<ProfileItem>,
    activeProfileId: ProfileId?,
    isAddProfileDialogVisible: Boolean,
    renameTarget: ProfileItem?,
    deleteTarget: ProfileItem?,
    switchTarget: ProfileItem?,
    @StringRes message: Int?,
    onMessageShown: () -> Unit,
    onNavigateUp: () -> Unit,
    onAddProfileClick: () -> Unit,
    onAddProfileConfirm: (ProfileName) -> Unit,
    onAddProfileDismiss: () -> Unit,
    onRenameRequest: (ProfileItem) -> Unit,
    onRenameDismiss: () -> Unit,
    onRenameConfirm: (ProfileName) -> Unit,
    onDeleteProfile: (ProfileItem) -> Unit,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onSwitchProfile: (ProfileItem) -> Unit,
    onSwitchDismiss: () -> Unit,
    onSwitchConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    if (message != null) {
        val messageText = stringResource(message)
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.switch_profile)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = stringResource(AppCompatR.string.abc_action_bar_up_description),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnkiDroidExtendedFab(
                onClick = onAddProfileClick,
                icon = {
                    Icon(painterResource(R.drawable.ic_switch_profile), contentDescription = null)
                },
                text = { Text(stringResource(R.string.add_profile)) },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            items(profiles, key = { it.id.value }) { profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onProfileClick = { onSwitchProfile(profile) },
                    onEditClick = { onRenameRequest(profile) },
                    onDeleteClick = { onDeleteProfile(profile) },
                )
            }
        }
    }

    if (isAddProfileDialogVisible) {
        AddProfileDialog(
            onDismissRequest = onAddProfileDismiss,
            onConfirm = onAddProfileConfirm,
        )
    }

    renameTarget?.let { target ->
        AddProfileDialog(
            title = stringResource(R.string.rename_profile),
            confirmText = stringResource(R.string.rename),
            initialText = target.name,
            onDismissRequest = onRenameDismiss,
            onConfirm = onRenameConfirm,
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
            title = { Text(stringResource(R.string.profile_delete_confirm_title, target.name)) },
            text = { Text(stringResource(R.string.profile_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) {
                    Text(stringResource(R.string.delete_profile))
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    switchTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onSwitchDismiss,
            title = { Text(stringResource(R.string.profile_switch_confirm_title, target.name)) },
            text = { Text(stringResource(R.string.profile_switch_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onSwitchConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onSwitchDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

private val AvatarSize = 40.dp

@Composable
private fun ProfileRow(
    profile: ProfileItem,
    isActive: Boolean,
    onProfileClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = !isActive, onClick = onProfileClick)
                .padding(
                    horizontal = MaterialTheme.dimensions.space200,
                    vertical = MaterialTheme.dimensions.space100,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(AvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profile.initial,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(
                        start = MaterialTheme.dimensions.space150,
                        end = MaterialTheme.dimensions.space100,
                    ),
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                Text(
                    text = stringResource(R.string.profile_current_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isActive) {
            Icon(
                painterResource(R.drawable.ic_check_circle_24),
                contentDescription = stringResource(R.string.profile_current_label),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onEditClick) {
            Icon(
                painterResource(R.drawable.ic_popup_menu_item_editor),
                contentDescription = stringResource(R.string.edit_profile),
            )
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete_profile),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun SwitchProfilesScreenPreview() {
    AnkiDroidTheme {
        SwitchProfilesScreen(
            profiles =
                listOf(
                    ProfileItem(id = ProfileId.DEFAULT, name = "Default"),
                    ProfileItem(id = ProfileId("p_work"), name = "Work"),
                ),
            activeProfileId = ProfileId.DEFAULT,
            isAddProfileDialogVisible = false,
            renameTarget = null,
            deleteTarget = null,
            switchTarget = null,
            message = null,
            onMessageShown = {},
            onNavigateUp = {},
            onAddProfileClick = {},
            onAddProfileConfirm = {},
            onAddProfileDismiss = {},
            onRenameRequest = {},
            onRenameDismiss = {},
            onRenameConfirm = {},
            onDeleteProfile = {},
            onDeleteDismiss = {},
            onDeleteConfirm = {},
            onSwitchProfile = {},
            onSwitchDismiss = {},
            onSwitchConfirm = {},
        )
    }
}
