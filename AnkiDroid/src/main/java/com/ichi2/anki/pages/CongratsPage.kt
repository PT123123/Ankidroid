// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2023 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import anki.collection.OpChanges
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.OnErrorListener
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.destinations.DeckOptionsDestination
import com.ichi2.anki.common.destinations.StudyOptionsDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.SECONDS_PER_DAY
import com.ichi2.anki.common.time.TIME_HOUR
import com.ichi2.anki.common.time.TIME_MINUTE
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.databinding.PageCongratsBinding
import com.ichi2.anki.dialogs.customstudy.CustomStudyDialog
import com.ichi2.anki.dialogs.customstudy.CustomStudyDialog.CustomStudyAction
import com.ichi2.anki.launchCatchingIO
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.pages.congrats.CongratsScreen
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.ui.internationalization.sentenceCase
import com.ichi2.compose.theme.AnkiDroidTheme
import com.ichi2.utils.listItems
import com.ichi2.utils.negativeButton
import com.ichi2.utils.show
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.round

/**
 * The "deck finished" screen, rendered natively in Compose. It used to be the backend's SvelteKit
 * "congrats" route in a WebView; the message is now built from the same [anki.scheduler.CongratsInfoResponse]
 * the page fetched, and the unbury / custom study actions the page wired through bridge commands
 * are plain buttons.
 */
class CongratsPage :
    Fragment(R.layout.page_congrats),
    ChangeManager.Subscriber {
    private val binding by viewBinding(PageCongratsBinding::bind)
    private val viewModel by viewModels<CongratsViewModel>()

    init {
        ChangeManager.subscribe(this)
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        // typically due to 'day rollover'. handler !== viewModel excludes changes this
        // screen caused itself (eg. unburying), which are already handled directly
        if (changes.studyQueues && handler !== viewModel) {
            Timber.i("study queues updated")
            viewModel.onStudyQueuesChanged()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        viewModel.onError
            .flowWithLifecycle(lifecycle)
            .onEach { errorMessage ->
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.vague_error)
                    .setMessage(errorMessage)
                    .show()
            }.launchIn(lifecycleScope)

        viewModel.unburyState
            .onEach { state ->
                when (state) {
                    UnburyState.OpenStudy -> openStudyOptionsAndFinish()
                    UnburyState.SelectMode -> {
                        val unburyOptions =
                            mutableListOf(
                                TR.studyingManuallyBuriedCards(),
                                TR.studyingBuriedSiblings(),
                                TR.studyingAllBuriedCards(),
                            )
                        AlertDialog.Builder(requireContext()).show {
                            setTitle(TR.studyingWhatWouldYouLikeToUnbury())
                            negativeButton(R.string.dialog_cancel)
                            listItems(
                                unburyOptions,
                            ) { _, position ->
                                val mode =
                                    when (position) {
                                        0 -> UnburyDeckRequest.Mode.USER_ONLY
                                        1 -> UnburyDeckRequest.Mode.SCHED_ONLY
                                        2 -> UnburyDeckRequest.Mode.ALL
                                        else -> error("Unhandled unbury option: ${unburyOptions[position]}")
                                    }
                                viewModel.onUnburyModeSelected(mode)
                            }
                        }
                    }
                }
            }.launchIn(lifecycleScope)

        viewModel.deckOptionsDestination
            .flowWithLifecycle(lifecycle)
            .onEach { destination ->
                navigate(destination)
            }.launchIn(lifecycleScope)

        // a rebuild triggered elsewhere (eg. deck options) while this screen wasn't visible may
        // have been missed, so recheck on every STARTED entry instead of trusting only the
        // opExecuted callback. Launched from inside repeatOnLifecycle so the collector is
        // guaranteed active before it emits (congratsRefreshState has no replay cache, so
        // emitting with no active collector silently drops the event).
        // viewLifecycleOwner (not the fragment's own lifecycle) is required by lint - see
        // UnsafeRepeatOnLifecycleDetector.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.refreshIfNeeded() }
                viewModel.congratsRefreshState.collect { state ->
                    when (state) {
                        CongratsRefreshState.ReloadPage -> {
                            Timber.d("congrats: recomposing after rebuild")
                            viewModel.loadUiState()
                        }
                        CongratsRefreshState.DeckHasCardsToStudy -> openStudyOptionsAndFinish()
                    }
                }
            }
        }

        with(binding.toolbar) {
            inflateMenu(R.menu.congrats)
            menu.findItem(R.id.action_open_deck_options)?.title = TR.sentenceCase.deckOptions
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_open_deck_options) {
                    viewModel.onDeckOptions()
                }
                true
            }
        }

        setFragmentResultListener(CustomStudyAction.REQUEST_KEY) { _, bundle ->
            when (CustomStudyAction.fromBundle(bundle)) {
                CustomStudyAction.CUSTOM_STUDY_SESSION,
                CustomStudyAction.EXTEND_STUDY_LIMITS,
                -> openStudyOptionsAndFinish()
            }
        }

        binding.composeView.setContent {
            AnkiDroidTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CongratsScreen(
                    state = state,
                    onUnbury = viewModel::onUnbury,
                    onCustomStudy = ::onStudyMore,
                )
            }
        }
    }

    private fun openStudyOptionsAndFinish() {
        Timber.i("opening study options")
        navigate(StudyOptionsDestination)
        requireActivity().finish()
    }

    private fun onStudyMore() {
        launchCatchingTask {
            val customStudy = CustomStudyDialog.createInstance(deckId = withCol { decks.selected() })
            customStudy.show(childFragmentManager, null)
        }
    }

    companion object {
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, fragmentClass = CongratsPage::class)

        private fun displayNewCongratsScreen(context: Context): Boolean = context.sharedPrefs().getBoolean("new_congrats_screen", false)

        fun display(activity: FragmentActivity) {
            if (displayNewCongratsScreen(activity)) {
                activity.startActivity(getIntent(activity))
            } else {
                activity.launchCatchingTask {
                    val message = getDeckFinishedMessage(activity)
                    showThemedToast(activity, message, false)
                }
            }
        }

        fun onReviewsCompleted(
            activity: FragmentActivity,
            cardsInDeck: Boolean,
        ) {
            if (displayNewCongratsScreen(activity)) {
                activity.startActivity(getIntent(activity))
                return
            }

            // Show a message when reviewing has finished
            if (cardsInDeck) {
                activity.launchCatchingTask {
                    val message = getDeckFinishedMessage(activity)
                    activity.showSnackbar(message)
                }
            } else {
                activity.showSnackbar(R.string.studyoptions_no_cards_due)
            }
        }

        // based in https://github.com/ankitects/anki/blob/9b4dd54312de8798a3f2bee07892bb3a488d1f9b/ts/routes/congrats/lib.ts#L8C17-L8C34
        private suspend fun getDeckFinishedMessage(activity: FragmentActivity): String {
            val info = withCol { sched.congratulationsInfo() }
            val secsUntilNextLearn = info.secsUntilNextLearn
            if (secsUntilNextLearn >= SECONDS_PER_DAY) {
                return activity.getString(R.string.studyoptions_congrats_finished)
            }
            // https://github.com/ankitects/anki/blob/9b4dd54312de8798a3f2bee07892bb3a488d1f9b/ts/lib/tslib/time.ts#L22
            val (unit, amount) =
                if (secsUntilNextLearn < TIME_MINUTE) {
                    "seconds" to secsUntilNextLearn.toDouble()
                } else if (secsUntilNextLearn < TIME_HOUR) {
                    "minutes" to secsUntilNextLearn / TIME_MINUTE
                } else {
                    "hours" to secsUntilNextLearn / TIME_HOUR
                }

            val nextLearnDue = TR.schedulingNextLearnDue(unit, round(amount).toInt())
            return activity.getString(R.string.studyoptions_congrats_next_due_in, nextLearnDue)
        }

        fun DeckPicker.onDeckCompleted() {
            Timber.i("Opening CongratsPage")
            startActivity(getIntent(this))
        }
    }
}

class CongratsViewModel :
    ViewModel(),
    OnErrorListener {
    override val onError = MutableSharedFlow<String>()
    val unburyState = MutableSharedFlow<UnburyState>()
    val deckOptionsDestination = MutableSharedFlow<DeckOptionsDestination>()

    /** What the Compose screen renders; null until first load completes. */
    private val _uiState = MutableStateFlow<CongratsUiState?>(null)
    val uiState: StateFlow<CongratsUiState?> = _uiState.asStateFlow()

    fun loadUiState() {
        launchCatchingIO {
            val info = withCol { sched.congratulationsInfo() }
            val lines = mutableListOf<String>()
            if (info.secsUntilNextLearn < SECONDS_PER_DAY) {
                val (unit, amount) =
                    when {
                        info.secsUntilNextLearn < TIME_MINUTE -> "seconds" to info.secsUntilNextLearn.toDouble()
                        info.secsUntilNextLearn < TIME_HOUR -> "minutes" to info.secsUntilNextLearn / TIME_MINUTE
                        else -> "hours" to info.secsUntilNextLearn / TIME_HOUR
                    }
                var line = TR.schedulingNextLearnDue(unit, round(amount).toInt())
                if (info.learnRemaining > 0) {
                    line += " " + TR.schedulingLearnRemaining(info.learnRemaining)
                }
                lines.add(line)
            }
            if (info.reviewRemaining) {
                lines.add(TR.schedulingTodayReviewLimitReached())
            }
            if (info.newRemaining) {
                lines.add(TR.schedulingTodayNewLimitReached())
            }
            _uiState.emit(
                CongratsUiState(
                    title = TR.schedulingCongratulationsFinished(),
                    messageLines = lines,
                    deckDescription = info.deckDescription?.takeIf { it.isNotBlank() },
                    canUnbury = info.haveSchedBuried || info.haveUserBuried,
                    canCustomStudy = !info.isFilteredDeck,
                ),
            )
        }
    }

    fun onUnbury() {
        launchCatchingIO {
            // https://github.com/ankitects/anki/blob/acaeee91fa853e4a7a78dcddbb832d009ec3529a/qt/aqt/overview.py#L154
            val congratsInfo = withCol { sched.congratulationsInfo() }
            if (congratsInfo.haveSchedBuried && congratsInfo.haveUserBuried) {
                unburyState.emit(UnburyState.SelectMode)
                return@launchCatchingIO
            }
            unburyAndStudy(UnburyDeckRequest.Mode.ALL)
        }
    }

    fun onUnburyModeSelected(mode: UnburyDeckRequest.Mode) {
        launchCatchingIO {
            unburyAndStudy(mode)
        }
    }

    private suspend fun unburyAndStudy(mode: UnburyDeckRequest.Mode) {
        undoableOp(handler = this) {
            sched.unburyDeck(decks.getCurrentId(), mode)
        }
        unburyState.emit(UnburyState.OpenStudy)
    }

    fun onDeckOptions() {
        launchCatchingIO {
            val deckId = withCol { decks.getCurrentId() }
            val isFiltered = withCol { decks.isFiltered(deckId) }
            deckOptionsDestination.emit(DeckOptionsDestination(deckId, isFiltered))
        }
    }

    val congratsRefreshState = MutableSharedFlow<CongratsRefreshState>()

    // set when onStudyQueuesChanged() has nothing to emit to (screen not STARTED, so
    // congratsRefreshState has no collector and would silently drop the event) - consumed
    // and cleared by refreshIfNeeded() on the next STARTED
    private var missedRefresh = false

    fun onStudyQueuesChanged() {
        launchCatchingIO {
            if (congratsRefreshState.subscriptionCount.value == 0) {
                missedRefresh = true
                return@launchCatchingIO
            }
            emitRefreshState()
        }
    }

    /** Called every time the screen becomes STARTED. [isDeckFinished] is cheap so it's always
     * run, but the (latency-sensitive) reload/navigation only happens if something was actually
     * missed while unwatched, or the deck no longer needs the congrats page. */
    suspend fun refreshIfNeeded() {
        // desktop's Overview re-checks sched._is_finished() on every refresh instead of
        // assuming the deck is still done - do the same before reloading the congrats page
        val stillFinished = isDeckFinished()
        if (!missedRefresh && stillFinished) {
            if (_uiState.value == null) loadUiState()
            return
        }
        missedRefresh = false
        congratsRefreshState.emit(
            if (stillFinished) CongratsRefreshState.ReloadPage else CongratsRefreshState.DeckHasCardsToStudy,
        )
    }

    private suspend fun emitRefreshState() {
        missedRefresh = false
        val stillFinished = isDeckFinished()
        congratsRefreshState.emit(
            if (stillFinished) CongratsRefreshState.ReloadPage else CongratsRefreshState.DeckHasCardsToStudy,
        )
    }

    private suspend fun isDeckFinished(): Boolean = withCol { sched.counts().count() == 0 }
}

/** The state the Compose congrats screen renders. */
data class CongratsUiState(
    val title: String,
    val messageLines: List<String>,
    val deckDescription: String?,
    val canUnbury: Boolean,
    val canCustomStudy: Boolean,
)

sealed class UnburyState {
    data object OpenStudy : UnburyState()

    data object SelectMode : UnburyState()
}

sealed class CongratsRefreshState {
    data object ReloadPage : CongratsRefreshState()

    data object DeckHasCardsToStudy : CongratsRefreshState()
}
