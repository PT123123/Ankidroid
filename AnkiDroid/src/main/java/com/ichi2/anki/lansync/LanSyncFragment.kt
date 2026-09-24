// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

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
import com.ichi2.compose.theme.AnkiDroidTheme

/**
 * Host of [LanSyncScreen], opened from Settings by class name.
 *
 * The LAN server and discovery follow the fragment's visibility: resuming starts them when the user
 * enabled LAN sync, pausing tears them down, so no port stays open while the app is backgrounded.
 */
class LanSyncFragment : Fragment() {
    private val viewModel: LanSyncViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AnkiDroidTheme {
                    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
                    val serving by viewModel.serving.collectAsStateWithLifecycle()
                    val selfName by viewModel.selfName.collectAsStateWithLifecycle()
                    val selfAddress by viewModel.selfAddress.collectAsStateWithLifecycle()
                    val selfPort by viewModel.selfPort.collectAsStateWithLifecycle()
                    val peers by viewModel.peers.collectAsStateWithLifecycle()
                    val log by viewModel.log.collectAsStateWithLifecycle()
                    val rounds by viewModel.rounds.collectAsStateWithLifecycle()
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    val busy by viewModel.busy.collectAsStateWithLifecycle()
                    val allowPlaintextV1 by viewModel.allowPlaintextV1.collectAsStateWithLifecycle()
                    val keepOnline by viewModel.keepOnline.collectAsStateWithLifecycle()
                    val scheduleSeconds by viewModel.scheduleSeconds.collectAsStateWithLifecycle()
                    val pairingSession by viewModel.pairingSession.collectAsStateWithLifecycle()
                    val hubEndpoint by viewModel.hubEndpoint.collectAsStateWithLifecycle()
                    var message by remember { mutableStateOf<Int?>(null) }

                    LaunchedEffect(Unit) {
                        viewModel.messages.collect { message = it }
                    }

                    LanSyncScreen(
                        enabled = enabled,
                        serving = serving,
                        selfName = selfName,
                        selfAddress = selfAddress,
                        selfPort = selfPort,
                        peers = peers,
                        log = log,
                        rounds = rounds,
                        progress = progress,
                        busy = busy,
                        allowPlaintextV1 = allowPlaintextV1,
                        keepOnline = keepOnline,
                        scheduleSeconds = scheduleSeconds,
                        pairingSession = pairingSession,
                        hubEndpoint = hubEndpoint,
                        message = message,
                        onMessageShown = { message = null },
                        onNavigateUp = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                        onToggleEnabled = viewModel::setEnabled,
                        onTogglePlaintext = viewModel::setAllowPlaintextV1,
                        onToggleKeepOnline = viewModel::setKeepOnline,
                        onScheduleSelected = viewModel::setScheduleSeconds,
                        onPairHere = viewModel::beginPairingHere,
                        onPairHereDismissed = viewModel::dismissPairingHere,
                        onPairWithCode = viewModel::pairWithCode,
                        onSync = viewModel::sync,
                        onSyncAll = viewModel::syncAll,
                        onAdd = viewModel::addPeer,
                        onForget = viewModel::forget,
                        onRenameSelf = viewModel::renameSelf,
                        onClearLog = viewModel::clearLog,
                    )
                }
            }
        }

    override fun onResume() {
        super.onResume()
        viewModel.startIfEnabled()
    }

    override fun onPause() {
        viewModel.stopRuntime()
        super.onPause()
    }
}
