// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.compose.theme.AnkiDroidTheme
import com.ichi2.compose.theme.dimensions
import com.ichi2.compose.ui.dialogs.TextInputDialog
import com.ichi2.compose.ui.preview.ThemePreviews
import androidx.appcompat.R as AppCompatR

/**
 * LAN sync screen: a switch that turns the local server and discovery on, the peers found on this
 * network, pairing, the schedule, and per-round details of what moved between them.
 *
 * Stateless - all of it lives in [LanSyncViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun LanSyncScreen(
    enabled: Boolean,
    serving: Boolean,
    selfName: String,
    selfAddress: String?,
    selfPort: Int,
    peers: List<LanPeerRow>,
    log: List<LanLogEntry>,
    rounds: List<LanRoundDetail>,
    progress: LanProgress?,
    busy: Boolean,
    allowPlaintextV1: Boolean,
    keepOnline: Boolean,
    scheduleSeconds: Int,
    pairingSession: LanPairSession?,
    hubEndpoint: String?,
    @StringRes message: Int?,
    onMessageShown: () -> Unit,
    onNavigateUp: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePlaintext: (Boolean) -> Unit,
    onToggleKeepOnline: (Boolean) -> Unit,
    onScheduleSelected: (Int) -> Unit,
    onPairHere: () -> Unit,
    onPairHereDismissed: () -> Unit,
    onPairWithCode: (LanDevice, String) -> Unit,
    onSync: (LanDevice) -> Unit,
    onSyncAll: () -> Unit,
    onAdd: (String) -> Unit,
    onForget: (String) -> Unit,
    onRenameSelf: (String) -> Unit,
    onClearLog: () -> Unit,
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

    var renameRequested by remember { mutableStateOf(false) }
    var addRequested by remember { mutableStateOf(false) }
    var forgetTarget by remember { mutableStateOf<LanPeerRow?>(null) }
    var pairTarget by remember { mutableStateOf<LanPeerRow?>(null) }
    val onlinePeers = peers.count { it.online }
    val hasV1Peers = peers.any { it.device.protocol < LanProtocol.VERSION }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lansync_title)) },
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
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                EnableCard(enabled = enabled, serving = serving, onToggleEnabled = onToggleEnabled)
            }
            item {
                SelfCard(
                    name = selfName,
                    address = selfAddress,
                    port = selfPort,
                    enabled = enabled,
                    hubEndpoint = hubEndpoint,
                    onRenameClick = { renameRequested = true },
                )
            }
            if (enabled) {
                item {
                    PairingControls(
                        session = pairingSession,
                        busy = busy,
                        onShowMyCode = onPairHere,
                        onDismissMyCode = onPairHereDismissed,
                    )
                }
                item {
                    ScheduleCard(
                        scheduleSeconds = scheduleSeconds,
                        keepOnline = keepOnline,
                        serving = serving,
                        onScheduleSelected = onScheduleSelected,
                        onToggleKeepOnline = onToggleKeepOnline,
                    )
                }
            }
            if (hasV1Peers || allowPlaintextV1) {
                item {
                    PlaintextV1Card(allowed = allowPlaintextV1, onToggle = onTogglePlaintext)
                }
            }

            item { SectionHeader(R.string.lansync_peers) }
            if (peers.isEmpty() && enabled) {
                item { EmptyHint(stringResource(R.string.lansync_no_peers)) }
            }
            items(peers, key = { it.device.id }) { row ->
                PeerRow(
                    row = row,
                    enabled = enabled,
                    busy = busy,
                    onPair = { pairTarget = row },
                    onSync = { onSync(row.device) },
                    onForget = { forgetTarget = row },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.dimensions.screenEdge),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100),
                ) {
                    Button(
                        onClick = onSyncAll,
                        enabled = enabled && peers.any { it.online && it.usable } && !busy,
                    ) {
                        Text(stringResource(R.string.lansync_sync_all))
                    }
                    OutlinedButton(
                        onClick = { addRequested = true },
                        enabled = enabled && !busy,
                    ) {
                        Text(stringResource(R.string.lansync_add_device))
                    }
                }
            }

            progress?.let { current ->
                item { ProgressLine(current) }
            }

            if (rounds.isNotEmpty()) {
                item { SectionHeader(R.string.lansync_rounds) }
                items(rounds.take(20), key = { "round-${it.at}-${it.deviceId}" }) { round ->
                    RoundRow(round)
                }
            }

            if (log.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.dimensions.screenEdge),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(R.string.lansync_activity, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearLog) { Text(stringResource(R.string.lansync_clear_log)) }
                    }
                }
                items(log) { entry ->
                    LogRow(entry)
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    if (renameRequested) {
        TextInputDialog(
            title = stringResource(R.string.lansync_device_name),
            label = stringResource(R.string.lansync_device_name),
            confirmText = stringResource(android.R.string.ok),
            initialText = selfName,
            onDismissRequest = { renameRequested = false },
            onConfirm = {
                onRenameSelf(it)
                renameRequested = false
            },
        )
    }

    if (addRequested) {
        val keyboard = LocalSoftwareKeyboardController.current
        TextInputDialog(
            title = stringResource(R.string.lansync_add_device),
            label = stringResource(R.string.lansync_add_device_hint),
            confirmText = stringResource(R.string.lansync_add_device),
            capitalization = KeyboardCapitalization.None,
            onDismissRequest = { addRequested = false },
            onConfirm = {
                keyboard?.hide()
                onAdd(it)
                addRequested = false
            },
        )
    }

    pairTarget?.let { target ->
        PairCodeDialog(
            peerName = target.device.name,
            busy = busy,
            onDismiss = { pairTarget = null },
            onConfirm = { code ->
                onPairWithCode(target.device, code)
                pairTarget = null
            },
        )
    }

    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.lansync_forget)) },
            text = { Text(stringResource(R.string.lansync_forget_confirm, target.device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForget(target.device.id)
                        forgetTarget = null
                    },
                ) {
                    Text(stringResource(R.string.lansync_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun EnableCard(
    enabled: Boolean,
    serving: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.cardSection(),
    ) {
        Column(Modifier.padding(MaterialTheme.dimensions.space200)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100),
            ) {
                Text(
                    text = stringResource(R.string.lansync_enable),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
            Text(
                text = stringResource(R.string.lansync_enable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (enabled) {
                Text(
                    text = stringResource(if (serving) R.string.lansync_foreground_note else R.string.lansync_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelfCard(
    name: String,
    address: String?,
    port: Int,
    enabled: Boolean,
    hubEndpoint: String?,
    onRenameClick: () -> Unit,
) {
    Card(
        modifier = Modifier.cardSection(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.dimensions.space200),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lansync_this_device), style = MaterialTheme.typography.titleMedium)
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text =
                        if (address.isNullOrBlank()) {
                            stringResource(R.string.lansync_not_on_lan)
                        } else {
                            // The literal address is what a peer types when the access point blocks
                            // discovery, so it has to be readable, not just a port.
                            stringResource(R.string.lansync_serving, port) + "\n$address:$port"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hubEndpoint.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.lansync_hub_active, hubEndpoint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (enabled) {
                IconButton(onClick = onRenameClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_note),
                        contentDescription = stringResource(R.string.lansync_rename),
                    )
                }
            }
        }
    }
}

/** Pairing: "show my code" (responder) lives here; initiating lives on each unpaired peer row. */
@Composable
private fun PairingControls(
    session: LanPairSession?,
    busy: Boolean,
    onShowMyCode: () -> Unit,
    onDismissMyCode: () -> Unit,
) {
    Card(
        modifier = Modifier.cardSection(),
    ) {
        Column(Modifier.padding(MaterialTheme.dimensions.space200)) {
            if (session == null) {
                Text(stringResource(R.string.lansync_pair), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.lansync_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onShowMyCode,
                    enabled = !busy,
                    modifier = Modifier.padding(top = MaterialTheme.dimensions.space100),
                ) {
                    Text(stringResource(R.string.lansync_pair_show_code))
                }
            } else {
                Text(stringResource(R.string.lansync_pair_showing), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = session.pairCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = stringResource(R.string.lansync_pair_security_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.lansync_pair_expires, ((session.expiresAt - lanNow()) / 1000L).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.lansync_pair_verify_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100)) {
                    TextButton(onClick = onDismissMyCode) { Text(stringResource(R.string.lansync_pair_done)) }
                    TextButton(onClick = onShowMyCode) { Text(stringResource(R.string.lansync_pair_new_code)) }
                }
            }
        }
    }
}

@Composable
private fun PairCodeDialog(
    peerName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextInputDialog(
        title = stringResource(R.string.lansync_pair_with, peerName),
        label = stringResource(R.string.lansync_pair_code_label),
        confirmText = stringResource(R.string.lansync_pair),
        capitalization = KeyboardCapitalization.None,
        onDismissRequest = {
            keyboard?.hide()
            onDismiss()
        },
        onConfirm = { code ->
            keyboard?.hide()
            if (!busy) onConfirm(code)
        },
    )
}

@Composable
private fun ScheduleCard(
    scheduleSeconds: Int,
    keepOnline: Boolean,
    serving: Boolean,
    onScheduleSelected: (Int) -> Unit,
    onToggleKeepOnline: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.cardSection(),
    ) {
        Column(Modifier.padding(MaterialTheme.dimensions.space200)) {
            Text(stringResource(R.string.lansync_schedule), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.lansync_schedule_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100)) {
                FilterChip(
                    selected = scheduleSeconds == 0,
                    onClick = { onScheduleSelected(0) },
                    label = { Text(stringResource(R.string.lansync_schedule_off)) },
                )
                LanStore.SCHEDULE_PERIODS.forEach { seconds ->
                    FilterChip(
                        selected = scheduleSeconds == seconds,
                        onClick = { onScheduleSelected(seconds) },
                        label = { Text(if (seconds < 60) "$seconds s" else "${seconds / 60} min") },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = MaterialTheme.dimensions.space100),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lansync_keep_online), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.lansync_keep_online_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepOnline, onCheckedChange = onToggleKeepOnline)
            }
            if (keepOnline && !serving) {
                Text(
                    text = stringResource(R.string.lansync_keep_online_dead),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The explicit "allow plaintext v1" opt-in, with its warning. Default off → data plane refuses. */
@Composable
private fun PlaintextV1Card(
    allowed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.cardSection(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.dimensions.space200),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lansync_plaintext_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.lansync_plaintext_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Switch(checked = allowed, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PeerRow(
    row: LanPeerRow,
    enabled: Boolean,
    busy: Boolean,
    onPair: () -> Unit,
    onSync: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.dimensions.screenEdge, vertical = MaterialTheme.dimensions.space100),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space100),
        ) {
            OnlineDot(row.online)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.space50),
                ) {
                    Text(row.device.name, style = MaterialTheme.typography.titleSmall)
                    if (row.device.paired) {
                        ModeBadge(stringResource(R.string.lansync_paired), MaterialTheme.colorScheme.primaryContainer)
                    } else if (row.device.protocol < LanProtocol.VERSION) {
                        ModeBadge(stringResource(R.string.lansync_v1_unpaired), MaterialTheme.colorScheme.errorContainer)
                    }
                    row.lastMode?.let { mode -> ModeBadge(modeLabel(mode), MaterialTheme.colorScheme.secondaryContainer) }
                }
                Text(
                    text = "${row.device.ip}:${row.device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The code is a hash of the shared secret, so it cannot exist before commit; this
                // row is the only place it can honestly be shown (SPEC-v2 §4.1).
                if (row.device.paired && row.device.securityCode.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.lansync_device_security, row.device.securityCode),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text =
                        if (row.device.lastSyncAt > 0L) {
                            stringResource(
                                R.string.lansync_last_sync,
                                DateUtils.getRelativeTimeSpanString(row.device.lastSyncAt).toString(),
                            )
                        } else {
                            stringResource(R.string.lansync_never_synced)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!row.usable && row.online) {
                    Text(
                        text = stringResource(R.string.lansync_need_pairing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (row.device.protocol >= LanProtocol.VERSION && !row.device.paired) {
                TextButton(onClick = onPair, enabled = enabled && row.online && !busy) {
                    Text(stringResource(R.string.lansync_pair))
                }
            } else {
                TextButton(onClick = onSync, enabled = enabled && row.online && row.usable && !busy) {
                    Text(stringResource(R.string.lansync_sync))
                }
            }
            IconButton(onClick = onForget) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.lansync_forget),
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun modeLabel(mode: LanDataMode): String =
    stringResource(
        when (mode) {
            LanDataMode.HUB -> R.string.lansync_mode_hub
            LanDataMode.APKG -> R.string.lansync_mode_apkg
            LanDataMode.PLAINTEXT_V1 -> R.string.lansync_mode_plaintext
        },
    )

@Composable
private fun ModeBadge(
    label: String,
    container: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = MaterialTheme.dimensions.space100, vertical = 2.dp),
    )
}

/** One row per round: which data plane ran, what each leg moved, how long and what failed. */
@Composable
private fun RoundRow(round: LanRoundDetail) {
    val context = LocalContext.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.dimensions.screenEdge, vertical = MaterialTheme.dimensions.space50),
    ) {
        val mode =
            stringResource(
                when (round.mode) {
                    LanDataMode.HUB -> R.string.lansync_mode_hub
                    LanDataMode.APKG -> R.string.lansync_mode_apkg
                    LanDataMode.PLAINTEXT_V1 -> R.string.lansync_mode_plaintext
                },
            )
        val push = if (round.pushBytes > 0L) "↑ ${Formatter.formatShortFileSize(context, round.pushBytes)}" else null
        val pull = if (round.pullBytes > 0L) "↓ ${Formatter.formatShortFileSize(context, round.pullBytes)}" else null
        val summary =
            listOfNotNull(
                round.deviceName,
                mode,
                push,
                pull,
                "${round.okLegs}/${round.legs}",
                "${round.millis}ms",
            ).joinToString(" · ")
        Text(summary, style = MaterialTheme.typography.bodySmall)
        if (round.error.isNotBlank()) {
            Text(
                text = round.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProgressLine(progress: LanProgress) {
    val label = stepLabel(progress)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.dimensions.screenEdge, vertical = MaterialTheme.dimensions.space100),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun stepLabel(progress: LanProgress): String {
    val peer = progress.peer.ifBlank { "peer" }
    return when (progress.step) {
        LanStep.EXPORTING -> stringResource(R.string.lansync_step_exporting)
        LanStep.SENDING -> stringResource(R.string.lansync_step_sending, peer)
        LanStep.REQUESTING -> stringResource(R.string.lansync_step_requesting, peer)
        LanStep.RECEIVING -> stringResource(R.string.lansync_step_receiving, peer)
        LanStep.IMPORTING -> stringResource(R.string.lansync_step_importing)
        LanStep.PROBING -> stringResource(R.string.lansync_step_probing, peer)
        LanStep.PAIRING -> stringResource(R.string.lansync_step_pairing, peer)
        LanStep.HUB_SYNC -> stringResource(R.string.lansync_step_hub, peer)
    }
}

@Composable
private fun LogRow(entry: LanLogEntry) {
    val context = LocalContext.current
    val direction =
        stringResource(
            when (entry.direction) {
                LanDirection.PUSH -> R.string.lansync_direction_push
                LanDirection.PULL -> R.string.lansync_direction_pull
                LanDirection.PROBE -> R.string.lansync_direction_probe
            },
        )
    val result =
        stringResource(
            when (entry.result) {
                LanResult.OK -> R.string.lansync_result_ok
                LanResult.ERROR -> R.string.lansync_result_error
                LanResult.BUSY -> R.string.lansync_result_busy
            },
        )
    val size = if (entry.bytes > 0L) Formatter.formatShortFileSize(context, entry.bytes) else null
    val summary =
        listOfNotNull(
            direction,
            result,
            size,
            "${entry.millis}ms",
            entry.errorCode.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.dimensions.screenEdge, vertical = MaterialTheme.dimensions.space50),
    ) {
        Text("${entry.deviceName}: $summary", style = MaterialTheme.typography.bodySmall)
        if (entry.detail.isNotBlank()) {
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    val description = stringResource(if (online) R.string.lansync_online else R.string.lansync_offline)
    Box(
        modifier =
            Modifier
                .semantics { contentDescription = description }
                .size(DotSize)
                .clip(CircleShape)
                .background(
                    if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
    )
}

@Composable
private fun SectionHeader(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.dimensions.screenEdge,
                    end = MaterialTheme.dimensions.screenEdge,
                    top = MaterialTheme.dimensions.space300,
                    bottom = MaterialTheme.dimensions.space100,
                ),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.dimensions.screenEdge,
                vertical = MaterialTheme.dimensions.space100,
            ),
    )
}

/** Cards span the screen with the standard edge insets. */
@Composable
private fun Modifier.cardSection(): Modifier =
    this.fillMaxWidth().padding(
        horizontal = MaterialTheme.dimensions.screenEdge,
        vertical = MaterialTheme.dimensions.space100,
    )

private val DotSize = 12.dp

/** Preview hook so the screen can be checked without a device on the network. */
@ThemePreviews
@Composable
private fun LanSyncScreenPreview() {
    AnkiDroidTheme {
        LanSyncScreen(
            enabled = true,
            serving = true,
            selfName = "Pixel 8",
            selfAddress = "192.168.1.24",
            selfPort = 5600,
            peers =
                listOf(
                    LanPeerRow(
                        LanDevice("a", "Galaxy", "192.168.1.31", 5600, LanSource.UDP, 0L),
                        online = true,
                        lastMode = LanDataMode.APKG,
                        usable = true,
                    ),
                ),
            log = listOf(LanLogEntry(0L, "a", "Galaxy", LanDirection.PUSH, LanResult.OK, 1024L, 900L, "")),
            rounds = listOf(LanRoundDetail(0L, "a", "Galaxy", LanDataMode.APKG, 2, 2, 1024L, 2048L, 900L, "")),
            progress = null,
            busy = false,
            allowPlaintextV1 = false,
            keepOnline = false,
            scheduleSeconds = 300,
            pairingSession = null,
            hubEndpoint = null,
            message = null,
            onMessageShown = {},
            onNavigateUp = {},
            onToggleEnabled = {},
            onTogglePlaintext = {},
            onToggleKeepOnline = {},
            onScheduleSelected = {},
            onPairHere = {},
            onPairHereDismissed = {},
            onPairWithCode = { _, _ -> },
            onSync = {},
            onSyncAll = {},
            onAdd = {},
            onForget = {},
            onRenameSelf = {},
            onClearLog = {},
        )
    }
}
