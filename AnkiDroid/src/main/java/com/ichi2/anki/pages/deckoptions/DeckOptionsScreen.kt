// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.deckoptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import anki.collection.OpChanges
import anki.deck_config.DeckConfig
import anki.deck_config.DeckConfigsForUpdate
import anki.deck_config.updateDeckConfigsRequest
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ProgressContext
import com.ichi2.anki.libanki.updateDeckConfigsRaw
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

/** One entry of the preset dropdown. */
data class DeckPresetItem(
    val configId: Long,
    val name: String,
    val useCount: Int,
)

data class DeckOptionsUiState(
    val loading: Boolean = true,
    val deckName: String = "",
    val deckId: Long = 0,
    val presets: List<DeckPresetItem> = emptyList(),
    val selectedPresetId: Long? = null,
    /** Every preset; a save pushes the whole set, like the desktop does. */
    val allConfig: List<DeckConfig> = emptyList(),
    /** The deck's own limit overrides; echoed back unchanged on save. */
    val limits: anki.deck_config.DeckConfigsForUpdate.CurrentDeck.Limits? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val currentConfig: DeckConfig?
        get() = allConfig.firstOrNull { it.id == selectedPresetId }
}

class DeckOptionsViewModel : ViewModel() {
    private val _state = MutableStateFlow(DeckOptionsUiState())
    val state: StateFlow<DeckOptionsUiState> = _state.asStateFlow()

    fun load(deckId: Long) {
        if (_state.value.deckId != 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (configs, deckName) =
                    withCol {
                        decks.getDeckConfigsForUpdate(deckId) to decks.name(deckId, default = true)
                    }
                _state.update {
                    it.copy(
                        loading = false,
                        deckId = deckId,
                        deckName = deckName,
                        presets =
                            configs.allConfigList.map { extra ->
                                DeckPresetItem(extra.config.id, extra.config.name, extra.useCount)
                            },
                        selectedPresetId = configs.currentDeck.configId,
                        allConfig = configs.allConfigList.map { it.config },
                        limits = configs.currentDeck.limits,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "deck options load failed")
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun selectPreset(configId: Long) {
        _state.update { it.copy(selectedPresetId = configId) }
    }

    /** Applies [edit] to the selected preset and marks the state dirty. */
    fun updateConfig(edit: (DeckConfig.Config.Builder) -> Unit) {
        _state.update { state ->
            val updated =
                state.allConfig.map { deckConfig ->
                    if (deckConfig.id == state.selectedPresetId) {
                        val builder = deckConfig.config.toBuilder()
                        edit(builder)
                        deckConfig.toBuilder().setConfig(builder.build()).build()
                    } else {
                        deckConfig
                    }
                }
            state.copy(allConfig = updated, dirty = true)
        }
    }

    fun setLearnSteps(text: String) {
        val steps = stepsFromText(text)
        updateConfig { it.clearLearnSteps().addAllLearnSteps(steps) }
    }

    fun setRelearnSteps(text: String) {
        val steps = stepsFromText(text)
        updateConfig { it.clearRelearnSteps().addAllRelearnSteps(steps) }
    }

    fun setNewPerDay(value: Int) = updateConfig { it.newPerDay = value }

    fun setReviewsPerDay(value: Int) = updateConfig { it.reviewsPerDay = value }

    fun setNewInsertOrder(random: Boolean) =
        updateConfig {
            it.newCardInsertOrder =
                if (random) {
                    DeckConfig.Config.NewCardInsertOrder.NEW_CARD_INSERT_ORDER_RANDOM
                } else {
                    DeckConfig.Config.NewCardInsertOrder.NEW_CARD_INSERT_ORDER_DUE
                }
        }

    fun setBuryNew(bury: Boolean) = updateConfig { it.buryNew = bury }

    fun setMaximumInterval(days: Int) = updateConfig { it.maximumReviewInterval = days }

    fun setLeechThreshold(failures: Int) = updateConfig { it.leechThreshold = failures }

    fun setLeechAction(suspendLeeches: Boolean) =
        updateConfig {
            it.leechAction =
                if (suspendLeeches) {
                    DeckConfig.Config.LeechAction.LEECH_ACTION_SUSPEND
                } else {
                    DeckConfig.Config.LeechAction.LEECH_ACTION_TAG_ONLY
                }
        }

    fun setDesiredRetention(percent: Int) = updateConfig { it.desiredRetention = percent / 100.0f }

    fun setStartingEase(percent: Int) = updateConfig { it.initialEase = percent / 100.0f }

    fun setEasyBonus(percent: Int) = updateConfig { it.easyMultiplier = percent / 100.0f }

    fun setHardInterval(percent: Int) = updateConfig { it.hardMultiplier = percent / 100.0f }

    fun setIntervalModifier(percent: Int) = updateConfig { it.intervalMultiplier = percent / 100.0f }

    fun setNewInterval(percent: Int) = updateConfig { it.lapseMultiplier = percent / 100.0f }

    fun setMinimumInterval(days: Int) = updateConfig { it.minimumLapseInterval = days }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (current.saving || !current.dirty || current.deckId == 0L) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backend = CollectionManager.getBackend()
                val changesBytes =
                    backend.withProgress(
                        progressContext = ProgressContext(),
                        extractProgress = {},
                        updateUi = {},
                    ) {
                        withCol {
                            updateDeckConfigsRaw(
                                updateDeckConfigsRequest {
                                    targetDeckId = current.deckId
                                    mode = anki.deck_config.UpdateDeckConfigsMode.UPDATE_DECK_CONFIGS_MODE_NORMAL
                                    current.limits?.let { limits = it }
                                    current.allConfig.forEach { config ->
                                        configs.add(config)
                                    }
                                }.toByteArray(),
                            )
                        }
                    }
                val changes = OpChanges.parseFrom(changesBytes)!!
                undoableOp { changes }
                _state.update { it.copy(saving = false, dirty = false) }
                onSaved()
            } catch (e: Exception) {
                Timber.w(e, "deck options save failed")
                _state.update { it.copy(saving = false, error = e.message ?: e.toString()) }
            }
        }
    }
}

/** "1 10" -> [1f, 10f]; unparsable entries are dropped. */
private fun stepsFromText(text: String): List<Float> = text.split(" ", ",").mapNotNull { it.trim().toFloatOrNull() }

@Composable
fun DeckOptionsScreen(
    state: DeckOptionsUiState,
    viewModel: DeckOptionsViewModel,
    onSaved: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.loading ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            state.error != null ->
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> {
                val config = state.currentConfig?.config
                if (config == null) {
                    CircularProgressIndicator()
                    return@Column
                }
                PresetCard(state, viewModel)
                StatsCard(title = TR.deckConfigDailyLimits()) {
                    NumberRow(TR.schedulingNewCardsday(), config.newPerDay) { viewModel.setNewPerDay(it) }
                    NumberRow(TR.schedulingMaximumReviewsday(), config.reviewsPerDay) { viewModel.setReviewsPerDay(it) }
                }
                StatsCard(title = TR.deckConfigLearningSteps()) {
                    StepsRow(TR.deckConfigLearningSteps(), config.learnStepsList) { viewModel.setLearnSteps(it) }
                    DropdownRow(
                        label = TR.deckConfigNewInsertionOrder(),
                        items =
                            listOf(
                                DeckConfig.Config.NewCardInsertOrder.NEW_CARD_INSERT_ORDER_DUE to
                                    TR.deckConfigNewInsertionOrderSequential(),
                                DeckConfig.Config.NewCardInsertOrder.NEW_CARD_INSERT_ORDER_RANDOM to
                                    TR.deckConfigNewInsertionOrderRandom(),
                            ),
                        selected = config.newCardInsertOrder,
                        onSelect = { viewModel.setNewInsertOrder(it == DeckConfig.Config.NewCardInsertOrder.NEW_CARD_INSERT_ORDER_RANDOM) },
                    )
                    SwitchRow(TR.deckConfigBuryNewSiblings(), config.buryNew) { viewModel.setBuryNew(it) }
                }
                StatsCard(title = TR.deckConfigRelearningSteps()) {
                    StepsRow(TR.deckConfigRelearningSteps(), config.relearnStepsList) { viewModel.setRelearnSteps(it) }
                    NumberRow(TR.schedulingLeechThreshold(), config.leechThreshold) { viewModel.setLeechThreshold(it) }
                    DropdownRow(
                        label = TR.schedulingLeechAction(),
                        items =
                            listOf(
                                DeckConfig.Config.LeechAction.LEECH_ACTION_TAG_ONLY to "Tag",
                                DeckConfig.Config.LeechAction.LEECH_ACTION_SUSPEND to TR.deckConfigSuspendLeeches(),
                            ),
                        selected = config.leechAction,
                        onSelect = { viewModel.setLeechAction(it == DeckConfig.Config.LeechAction.LEECH_ACTION_SUSPEND) },
                    )
                }
                StatsCard(title = TR.deckConfigAdvancedTitle()) {
                    NumberRow(TR.schedulingMaximumInterval(), config.maximumReviewInterval) { viewModel.setMaximumInterval(it) }
                    PercentRow(TR.schedulingStartingEase(), config.initialEase) { viewModel.setStartingEase(it) }
                    PercentRow(TR.schedulingEasyBonus(), config.easyMultiplier) { viewModel.setEasyBonus(it) }
                    PercentRow(TR.schedulingHardInterval(), config.hardMultiplier) { viewModel.setHardInterval(it) }
                    PercentRow(TR.schedulingIntervalModifier(), config.intervalMultiplier) { viewModel.setIntervalModifier(it) }
                    PercentRow(TR.schedulingNewInterval(), config.lapseMultiplier) { viewModel.setNewInterval(it) }
                    NumberRow(TR.schedulingMinimumInterval(), config.minimumLapseInterval) { viewModel.setMinimumInterval(it) }
                }
                StatsCard(title = TR.deckConfigDesiredRetention()) {
                    PercentRow(TR.deckConfigDesiredRetention(), config.desiredRetention * 100) { viewModel.setDesiredRetention(it) }
                }
                FilledTonalButton(
                    onClick = { viewModel.save(onSaved) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.dirty && !state.saving,
                ) {
                    Text(TR.deckConfigSaveButton())
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    state: DeckOptionsUiState,
    viewModel: DeckOptionsViewModel,
) {
    StatsCard(title = TR.deckConfigSharedPreset()) {
        DropdownRow(
            label = state.deckName,
            items = state.presets.map { it.configId to "${it.name} (${it.useCount})" },
            selected = state.selectedPresetId,
            onSelect = { viewModel.selectPreset(it) },
        )
    }
}

@Composable
private fun NumberRow(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun PercentRow(
    label: String,
    multiplier: Float,
    onCommit: (Int) -> Unit,
) {
    var text by remember(multiplier) { mutableStateOf((multiplier * 100).toInt().toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label + " (%)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun StepsRow(
    label: String,
    steps: List<Float>,
    onCommit: (String) -> Unit,
) {
    var text by remember(steps) { mutableStateOf(steps.joinToString(" ")) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun <T> DropdownRow(
    label: String,
    items: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = items.firstOrNull { it.first == selected }?.second ?: ""
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
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (id, itemLabel) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}
