// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import anki.import_export.CsvMetadata
import anki.import_export.ImportResponse
import anki.import_export.csvMetadataRequest
import anki.import_export.importCsvRequest
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ProgressContext
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.pages.statistics.StatsCard
import com.ichi2.anki.withProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** One selectable row of a dropdown: [id] identifies, [label] shows. */
data class CsvOptionItem(
    val id: Long,
    val label: String,
)

data class CsvImporterUiState(
    val path: String = "",
    val loading: Boolean = true,
    val metadata: CsvMetadata? = null,
    val notetypes: List<CsvOptionItem> = emptyList(),
    val decks: List<CsvOptionItem> = emptyList(),
    val selectedNotetypeId: Long? = null,
    val selectedDeckId: Long? = null,
    val fieldNames: List<String> = emptyList(),
    val importing: Boolean = false,
    val progressText: String = "",
    val result: ImportResponse? = null,
    val error: String? = null,
)

class CsvImporterViewModel : ViewModel() {
    private val _state = MutableStateFlow(CsvImporterUiState())
    val state: StateFlow<CsvImporterUiState> = _state.asStateFlow()

    fun load(filePath: String) {
        if (_state.value.path.isNotEmpty()) return
        _state.update { it.copy(path = filePath) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (metadata, notetypes, decks) =
                    withCol {
                        val csvMetadata = getCsvMetadata(filePath, null)
                        val notetypeItems =
                            notetypes
                                .allNamesAndIds()
                                .map { CsvOptionItem(it.id, it.name) }
                                .toList()
                        val deckItems =
                            decks
                                .allNamesAndIds(false, false)
                                .map { CsvOptionItem(it.id, it.name) }
                                .toList()
                        Triple(csvMetadata, notetypeItems, deckItems)
                    }
                _state.update {
                    it.copy(
                        loading = false,
                        metadata = metadata,
                        notetypes = notetypes,
                        decks = decks,
                        selectedNotetypeId = if (metadata.hasGlobalNotetype()) metadata.globalNotetype.id else null,
                        selectedDeckId = if (metadata.hasDeckId()) metadata.deckId else null,
                    )
                }
                refreshFieldNames()
            } catch (e: Exception) {
                Timber.w(e, "csv metadata failed")
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Re-runs the backend's detection with the user's hints. */
    private fun refetch(
        delimiter: CsvMetadata.Delimiter? = null,
        notetypeId: Long? = null,
        deckId: Long? = null,
        isHtml: Boolean? = null,
    ) {
        val path = _state.value.path
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadata =
                    withCol {
                        backend.getCsvMetadata(
                            csvMetadataRequest {
                                this.path = path
                                delimiter?.let { this.delimiter = it }
                                notetypeId?.let { this.notetypeId = it }
                                deckId?.let { this.deckId = it }
                                isHtml?.let { this.isHtml = it }
                            },
                        )
                    }
                _state.update {
                    it.copy(
                        loading = false,
                        metadata = metadata,
                        selectedNotetypeId = if (metadata.hasGlobalNotetype()) metadata.globalNotetype.id else it.selectedNotetypeId,
                        selectedDeckId = if (metadata.hasDeckId()) metadata.deckId else it.selectedDeckId,
                    )
                }
                refreshFieldNames()
            } catch (e: Exception) {
                Timber.w(e, "csv metadata refetch failed")
            }
        }
    }

    private suspend fun refreshFieldNames() {
        val notetypeId = _state.value.selectedNotetypeId ?: return
        val names =
            withCol {
                notetypes.get(notetypeId)?.fields?.map { it.name } ?: emptyList()
            }
        _state.update { it.copy(fieldNames = names) }
    }

    fun setNotetype(id: Long) {
        _state.update { it.copy(selectedNotetypeId = id) }
        refetch(notetypeId = id)
    }

    fun setDeck(id: Long) {
        _state.update { it.copy(selectedDeckId = id) }
        refetch(deckId = id)
    }

    fun setDelimiter(delimiter: CsvMetadata.Delimiter) {
        refetch(delimiter = delimiter)
    }

    fun setHtml(allowHtml: Boolean) {
        val metadata = _state.value.metadata ?: return
        _state.update {
            it.copy(
                metadata =
                    metadata
                        .toBuilder()
                        .setIsHtml(allowHtml)
                        .setForceIsHtml(true)
                        .build(),
            )
        }
    }

    fun setDupeResolution(resolution: CsvMetadata.DupeResolution) {
        val metadata = _state.value.metadata ?: return
        _state.update { it.copy(metadata = metadata.toBuilder().setDupeResolution(resolution).build()) }
    }

    fun setMatchScope(scope: CsvMetadata.MatchScope) {
        val metadata = _state.value.metadata ?: return
        _state.update { it.copy(metadata = metadata.toBuilder().setMatchScope(scope).build()) }
    }

    /** [columnIndex] is the 1-based file column; 0 means the field is ignored. */
    fun setFieldColumn(
        fieldIndex: Int,
        columnIndex: Int,
    ) {
        val metadata = _state.value.metadata ?: return
        if (!metadata.hasGlobalNotetype()) return
        val builder = metadata.toBuilder()
        val notetype = builder.globalNotetype.toBuilder()
        while (notetype.fieldColumnsCount <= fieldIndex) notetype.addFieldColumns(0)
        notetype.setFieldColumns(fieldIndex, columnIndex)
        builder.globalNotetype = notetype.build()
        _state.update { it.copy(metadata = builder.build()) }
    }

    /** Aborts a running import; the process cannot be resumed. */
    fun abort() {
        CollectionManager.getBackend().setWantsAbort()
    }

    fun import() {
        val current = _state.value
        val metadata = current.metadata ?: return
        if (current.importing || current.result != null) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backend = CollectionManager.getBackend()
                val progressContext = ProgressContext()
                val result =
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
                            importCsv(
                                importCsvRequest {
                                    path = current.path
                                    this.metadata = metadata
                                },
                            )
                        }
                    }
                undoableOp { result.changes }
                _state.update { it.copy(importing = false, result = result) }
            } catch (e: Exception) {
                Timber.w(e, "csv import failed")
                _state.update { it.copy(importing = false, error = e.message ?: e.toString()) }
            }
        }
    }
}

@Composable
fun CsvImporterScreen(
    state: CsvImporterUiState,
    viewModel: CsvImporterViewModel,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.importing -> {
                Text(TR.importingImportingCollection(), style = MaterialTheme.typography.titleMedium)
                if (state.progressText.isNotBlank()) {
                    Text(
                        state.progressText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            state.error != null -> {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.result != null -> ImportLogView(state.result)
            state.loading || state.metadata == null -> CircularProgressIndicator()
            else -> MappingForm(state, viewModel)
        }
    }
}

@Composable
private fun MappingForm(
    state: CsvImporterUiState,
    viewModel: CsvImporterViewModel,
) {
    val metadata = state.metadata ?: return
    val columnOptions: List<CsvOptionItem> =
        metadata.columnLabelsList.mapIndexed { index, label ->
            CsvOptionItem((index + 1).toLong(), label.ifBlank { "${index + 1}" })
        }
    val ignoreItem = CsvOptionItem(0, TR.importingIgnored())
    val dupeOptions =
        listOf(
            CsvMetadata.DupeResolution.UPDATE to TR.importingUpdate(),
            CsvMetadata.DupeResolution.PRESERVE to TR.importingPreserve(),
            CsvMetadata.DupeResolution.DUPLICATE to TR.importingDuplicate(),
        )
    val scopeOptions =
        listOf(
            CsvMetadata.MatchScope.NOTETYPE to TR.cardStatsNoteType(),
            CsvMetadata.MatchScope.NOTETYPE_AND_DECK to TR.importingNotetypeAndDeck(),
        )
    val delimiterOptions =
        listOf(
            CsvMetadata.Delimiter.TAB to TR.importingTab(),
            CsvMetadata.Delimiter.PIPE to TR.importingPipe(),
            CsvMetadata.Delimiter.SEMICOLON to TR.importingSemicolon(),
            CsvMetadata.Delimiter.COLON to TR.importingColon(),
            CsvMetadata.Delimiter.COMMA to TR.importingComma(),
            CsvMetadata.Delimiter.SPACE to "Space",
        )

    StatsCard(title = TR.importingFieldMapping()) {
        CsvDropdown(
            label = TR.cardStatsNoteType(),
            items = state.notetypes,
            selectedId = state.selectedNotetypeId,
            onSelect = { item -> viewModel.setNotetype(item.id) },
        )
        val fieldColumns = metadata.globalNotetype.fieldColumnsList
        state.fieldNames.forEachIndexed { fieldIndex, fieldName ->
            val mappedColumn = fieldColumns.getOrElse(fieldIndex) { 0 }
            CsvDropdown(
                label = fieldName,
                items = listOf(ignoreItem) + columnOptions,
                selectedId = mappedColumn.toLong(),
                onSelect = { item -> viewModel.setFieldColumn(fieldIndex, item.id.toInt()) },
            )
        }
        CsvDropdown(
            label = TR.cardStatsDeckName(),
            items = state.decks,
            selectedId = state.selectedDeckId,
            onSelect = { item -> viewModel.setDeck(item.id) },
        )
    }
    StatsCard(title = TR.importingImportOptions()) {
        CsvDropdown(
            label = TR.importingFieldSeparator(),
            items = delimiterOptions.map { CsvOptionItem(it.first.number.toLong(), it.second) },
            selectedId = metadata.delimiter.number.toLong(),
            onSelect = { item -> viewModel.setDelimiter(CsvMetadata.Delimiter.forNumber(item.id.toInt())) },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TR.importingAllowHtmlInFields(), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = metadata.isHtml, onCheckedChange = { viewModel.setHtml(it) })
        }
        CsvDropdown(
            label = TR.importingUpdates(),
            items = dupeOptions.map { CsvOptionItem(it.first.number.toLong(), it.second) },
            selectedId = metadata.dupeResolution.number.toLong(),
            onSelect = { item -> viewModel.setDupeResolution(CsvMetadata.DupeResolution.forNumber(item.id.toInt())) },
        )
        CsvDropdown(
            label = TR.importingMatchScope(),
            items = scopeOptions.map { CsvOptionItem(it.first.number.toLong(), it.second) },
            selectedId = metadata.matchScope.number.toLong(),
            onSelect = { item -> viewModel.setMatchScope(CsvMetadata.MatchScope.forNumber(item.id.toInt())) },
        )
    }
    if (metadata.previewCount > 0) {
        StatsCard(title = TR.importingOverview()) {
            metadata.previewList.take(5).forEach { row ->
                Text(
                    row.valsList.joinToString(" | "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
    FilledTonalButton(
        onClick = { viewModel.import() },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.importing && state.result == null,
    ) {
        Text(TR.actionsImport())
    }
}

/** A read-only field opening a dropdown of [items]. */
@Suppress("DEPRECATION")
@Composable
private fun CsvDropdown(
    label: String,
    items: List<CsvOptionItem>,
    selectedId: Long?,
    onSelect: (CsvOptionItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = items.firstOrNull { it.id == selectedId }?.label ?: ""
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}
