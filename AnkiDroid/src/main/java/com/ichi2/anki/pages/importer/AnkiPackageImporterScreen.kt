// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import anki.import_export.ImportAnkiPackageOptions
import anki.import_export.ImportResponse
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ProgressContext
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.withProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** The state of an .apkg import: running progress, then the result log. */
data class AnkiPackageImporterUiState(
    val importing: Boolean = false,
    val progressText: String = "",
    val result: ImportResponse? = null,
    val error: String? = null,
)

class AnkiPackageImporterViewModel : ViewModel() {
    private val _state = MutableStateFlow(AnkiPackageImporterUiState())
    val state: StateFlow<AnkiPackageImporterUiState> = _state.asStateFlow()

    fun import(filePath: String) {
        if (_state.value.importing || _state.value.result != null) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backend = CollectionManager.getBackend()
                val progressContext = ProgressContext()
                val response =
                    backend.withProgress(
                        progressContext = progressContext,
                        extractProgress = {
                            if (progress.hasImporting()) {
                                _state.update { it.copy(progressText = progress.importing) }
                            }
                        },
                        updateUi = {},
                    ) {
                        withCol {
                            importAnkiPackage(
                                filePath,
                                ImportAnkiPackageOptions.getDefaultInstance(),
                            )
                        }
                    }
                undoableOp { response.changes }
                _state.update { it.copy(importing = false, result = response) }
            } catch (e: Exception) {
                Timber.w(e, "apkg import failed")
                _state.update { it.copy(importing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Aborts a running import; the process cannot be resumed. */
    fun abort() {
        CollectionManager.getBackend().setWantsAbort()
    }
}

@Composable
fun AnkiPackageImporterScreen(state: AnkiPackageImporterUiState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.importing -> {
                Text(
                    TR.importingImportingCollection(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (state.progressText.isNotBlank()) {
                    Text(
                        state.progressText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                LinearProgressIndicator(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                )
            }
            state.error != null -> {
                Text(
                    TR.importingFailedDebugInfo(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> ImportLogView(state.result)
        }
    }
}
