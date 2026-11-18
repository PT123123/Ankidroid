// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2024 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.ui.windows.reviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.ActionMenuView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.DispatchKeyEventListener
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.android.AnkiShakeDetector
import com.ichi2.anki.browser.toIntent
import com.ichi2.anki.cardviewer.Gesture
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.common.destinations.BrowserDestination
import com.ichi2.anki.common.destinations.CardInfoDestination
import com.ichi2.anki.common.destinations.CardInfoDestination.EntryPoint
import com.ichi2.anki.common.destinations.DeckOptionsDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.common.utils.android.isRobolectric
import com.ichi2.anki.databinding.FragmentReviewerBinding
import com.ichi2.anki.dialogs.showDeckOptionsSelectionDialog
import com.ichi2.anki.dialogs.tags.TagsDialogFactory
import com.ichi2.anki.dialogs.tags.TagsDialogListener
import com.ichi2.anki.model.CardStateFilter
import com.ichi2.anki.pages.cardir.CardIrFonts
import com.ichi2.anki.pages.cardir.CardIrMedia
import com.ichi2.anki.pages.cardir.CardIrParser
import com.ichi2.anki.pages.cardir.CardIrStore
import com.ichi2.anki.pages.cardir.CardIrStyle
import com.ichi2.anki.pages.cardir.CardIrView
import com.ichi2.anki.pages.cardir.IrFrame
import com.ichi2.anki.pages.cardir.IrNode
import com.ichi2.anki.pages.cardir.TaffyCardLayout
import com.ichi2.anki.pages.cardir.displayedText
import com.ichi2.anki.pages.cardir.needsClozeReveal
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.previewer.CardViewerActivity
import com.ichi2.anki.previewer.CardViewerFragment
import com.ichi2.anki.previewer.TypeAnswer
import com.ichi2.anki.previewer.setFrameStyle
import com.ichi2.anki.previewer.stdHtml
import com.ichi2.anki.reviewer.BindingMap
import com.ichi2.anki.reviewer.ReviewerBinding
import com.ichi2.anki.scheduling.ForgetCardsDialog
import com.ichi2.anki.scheduling.SetDueDateDialog
import com.ichi2.anki.scheduling.registerOnForgetHandler
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.settings.enums.FrameStyle
import com.ichi2.anki.settings.enums.HideSystemBars
import com.ichi2.anki.settings.enums.ToolbarPosition
import com.ichi2.anki.snackbar.BaseSnackbarBuilderProvider
import com.ichi2.anki.snackbar.SnackbarBuilder
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.ui.windows.reviewer.audiorecord.CheckPronunciationFragment
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardFragment
import com.ichi2.anki.utils.CollectionPreferences
import com.ichi2.anki.utils.ext.collectIn
import com.ichi2.anki.utils.ext.collectLatestIn
import com.ichi2.anki.utils.ext.sharedPrefs
import com.ichi2.anki.utils.ext.showDialogFragment
import com.ichi2.anki.utils.ext.window
import com.ichi2.anki.workarounds.SafeWebViewLayout
import com.ichi2.themes.Themes
import com.ichi2.utils.TruncatedString
import com.ichi2.utils.copyToClipboard
import com.ichi2.utils.dp
import com.ichi2.utils.show
import com.squareup.seismic.ShakeDetector
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.jvm.jvmName

private data class NativeReviewerCard(
    val cardId: Long,
    val irJson: ByteArray,
    val frame: IrFrame,
    val side: String,
    val variant: String,
    val fonts: CardIrFonts,
    val mediaDirectory: File?,
)

class ReviewerFragment :
    CardViewerFragment(R.layout.fragment_reviewer),
    BaseSnackbarBuilderProvider,
    ActionMenuView.OnMenuItemClickListener,
    DispatchKeyEventListener,
    TagsDialogListener,
    ShakeDetector.Listener {
    override val viewModel: ReviewerViewModel by viewModels()
    private val binding by viewBinding(FragmentReviewerBinding::bind)

    override val webViewLayout: SafeWebViewLayout get() = binding.webViewLayout
    private lateinit var bindingMap: BindingMap<ReviewerBinding, ViewerAction>
    private var shakeDetector: AnkiShakeDetector? = null
    private val nativeCardContent = mutableStateOf<NativeReviewerCard?>(null)
    private var nativeCardLoadJob: Job? = null
    private var cardCaptureJob: Job? = null
    private var currentFaceSkippedWebView = false
    private var wikilinkPageVersion = 0
    private val gestureParser by lazy {
        GestureParser(
            scope = lifecycleScope,
            isDoubleTapEnabled = bindingMap.isBound(Gesture.DOUBLE_TAP),
        )
    }
    private val whiteboardFragment get() = childFragmentManager.findFragmentByTag(WhiteboardFragment::class.jvmName) as? WhiteboardFragment
    private val isBigScreen: Boolean get() = resources.configuration.smallestScreenWidthDp >= 720

    override val baseSnackbarBuilder: SnackbarBuilder = {
        anchorView =
            when {
                binding.typeAnswerContainer.isVisible -> binding.typeAnswerContainer
                binding.answerArea.isVisible -> binding.answerArea
                Prefs.toolbarPosition == ToolbarPosition.BOTTOM -> binding.toolsLayout
                else -> null
            }
    }

    private lateinit var tagsDialogFactory: TagsDialogFactory

    override fun onLoadInitialHtml(): String =
        stdHtml(
            context = requireContext(),
            extraJsAssets = listOf("scripts/ankidroid-reviewer.js", "scripts/card-ir-capture.js"),
            nightMode = Themes.isNightTheme,
        )

    override fun onStart() {
        super.onStart()
        shakeDetector?.start()
    }

    override fun onStop() {
        super.onStop()
        if (!requireActivity().isChangingConfigurations) {
            viewModel.stopAutoAdvance()
        }
        shakeDetector?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            // The renderer preference can change in Reviewing settings while
            // this fragment is stopped; apply it as soon as Reviewer returns.
            if (Prefs.useWebViewCardRenderer && currentFaceSkippedWebView) {
                currentFaceSkippedWebView = false
                // The last face was rendered only by Compose, so the WebView
                // needs the current side again when the user switches back.
                viewModel.reloadCurrentSideInWebView()
            }
            refreshNativeCard()
            captureCurrentCardFace()
        }
    }

    override fun onCardSideRendered(side: String) {
        refreshNativeCard()
        captureCurrentCardFace(side)
    }

    override fun onNativeRenderFailed() {
        currentFaceSkippedWebView = false
        showNativeCard(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tagsDialogFactory = TagsDialogFactory(this).attachToActivity<TagsDialogFactory>(requireActivity())
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            requireActivity().finish()
        }

        setupBindings()
        setupNativeCardRenderer()
        setupImmersiveMode()
        setupTypeAnswer()
        setupAnswerButtons()
        setupCounts()
        setupCardInfo()
        setupMenu()
        setupToolbarPosition()
        setupAnswerTimer()
        setupMargins()
        setupResetProgress()
        setupCheckPronunciation()
        setupActions()
        setupWhiteboard()
        setupTimebox()

        viewModel.finishResultFlow.collectIn(lifecycleScope) { result ->
            requireActivity().run {
                setResult(result)
                finish()
            }
        }

        viewModel.statesMutationEvalFlow.collectIn(lifecycleScope) { eval ->
            // Completion is signaled by `statesMutated`
            webViewLayout.evaluateJavascript(eval) { result ->
                // eval failed, usually a syntax error
                // Note: this is `"null"`, not null
                if ("null" == result) {
                    viewModel.onStateMutationCallback()
                }
            }
        }

        viewModel.showingAnswer.collectIn(lifecycleScope) {
            resetZoom()
            // focus on the whole layout so motion controllers can be captured
            // without navigating the other View elements
            binding.rootLayout.requestFocus()
        }

        viewModel.navigateFlow.collectIn(lifecycleScope) { destination ->
            if (destination is DeckOptionsDestination && destination.options.size > 1) {
                requireContext().showDeckOptionsSelectionDialog(destination.options) { selectedOption ->
                    Timber.i("Deck options target selected: ${selectedOption.deckId}")
                    navigate(
                        destination.copy(
                            deckId = selectedOption.deckId,
                            isFiltered = selectedOption.isFiltered,
                        ),
                    )
                }
                return@collectIn
            }
            navigate(destination)
        }

        binding.webViewContainer.setFrameStyle()

        if (Prefs.showAnswerFeedback) {
            viewModel.answerFeedbackFlow.collectIn(lifecycleScope) { ease ->
                val drawableId =
                    when (ease) {
                        Rating.AGAIN -> R.drawable.ic_ease_again
                        Rating.HARD -> R.drawable.ic_ease_hard
                        Rating.GOOD -> R.drawable.ic_ease_good
                        Rating.EASY -> R.drawable.ic_ease_easy
                        Rating.UNRECOGNIZED -> throw IllegalArgumentException("Invalid rating")
                    }
                binding.answerFeedback.apply {
                    setImageResource(drawableId)
                    toggle()
                }
            }
        }

        if (Prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupNativeCardRenderer() {
        binding.nativeCardCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.nativeCardCompose.setContent {
            val nativeCardScrollState = rememberScrollState()
            nativeCardContent.value?.let { card ->
                CardIrView(
                    frame = card.frame,
                    irJson = card.irJson,
                    side = card.side,
                    variant = card.variant,
                    modifier = Modifier.fillMaxSize().verticalScroll(nativeCardScrollState),
                    fonts = card.fonts,
                    mediaDirectory = card.mediaDirectory,
                    onAction = ::handleNativeCardAction,
                    onTap = { tap -> handleNativeCardTap(tap, nativeCardScrollState.value) },
                    onSwipe = { deltaX -> handleNativeCardSwipe(deltaX = deltaX) },
                    verticalScrollOffset = { nativeCardScrollState.value },
                    onVerticalSwipe = { deltaY, startOffset ->
                        val endOffset = nativeCardScrollState.value
                        val atSameScrollPosition = startOffset == endOffset
                        val towardScrollBoundary =
                            (deltaY > 0f && endOffset == 0) ||
                                (deltaY < 0f && endOffset == nativeCardScrollState.maxValue)
                        if (atSameScrollPosition && towardScrollBoundary) {
                            handleNativeCardSwipe(deltaY = deltaY)
                        }
                    },
                    onMultiTouch = ::handleNativeMultiTouch,
                    onLayoutUnavailable = { fallbackNativeCardToWebView(card.cardId, card.side) },
                )
            }
        }
        viewModel.onCardUpdatedFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) {
                refreshNativeCard()
            }
        viewModel.showingAnswer
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) {
                refreshNativeCard()
            }
        viewModel.typeAnswerFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { refreshNativeCard() }
    }

    /** Load the safe native subset; any interactive/unsupported IR keeps WebView visible. */
    private fun refreshNativeCard() {
        nativeCardLoadJob?.cancel()
        val typeAnswerUsesWebView = Prefs.isHtmlTypeAnswerEnabled && viewModel.typeAnswerFlow.value != null
        if (Prefs.useWebViewCardRenderer || typeAnswerUsesWebView) {
            showNativeCard(null)
            return
        }
        nativeCardLoadJob =
            lifecycleScope.launch {
                val cardId = viewModel.getCardId()
                val side = if (viewModel.showingAnswer.value) "answer" else "question"
                val card = loadNativeCardSafely(cardId, side)
                if (viewModel.getCardId() != cardId ||
                    (if (viewModel.showingAnswer.value) "answer" else "question") != side ||
                    Prefs.useWebViewCardRenderer
                ) {
                    return@launch
                }
                showNativeCard(card)
            }
    }

    /** Use an existing eligible frame before the eval collector sends card HTML to WebView. */
    override suspend fun renderCardWithoutWebView(side: String): Boolean {
        currentFaceSkippedWebView = false
        if (Prefs.useWebViewCardRenderer ||
            (Prefs.isHtmlTypeAnswerEnabled && viewModel.typeAnswerFlow.value != null)
        ) {
            showNativeCard(null)
            return false
        }
        val cardId = viewModel.getCardId()
        val card = loadNativeCardSafely(cardId, side)
        if (card == null) {
            showNativeCard(null)
            return false
        }
        if (viewModel.getCardId() != cardId ||
            (if (viewModel.showingAnswer.value) "answer" else "question") != side ||
            Prefs.useWebViewCardRenderer
        ) {
            showNativeCard(null)
            return false
        }
        showNativeCard(card)
        currentFaceSkippedWebView = true
        return true
    }

    private suspend fun loadNativeCardSafely(
        cardId: Long,
        side: String,
    ): NativeReviewerCard? =
        try {
            loadNativeCard(cardId, side)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Unable to load native card %d (%s); using WebView", cardId, side)
            showNativeCard(null)
            null
        }

    /** Load and validate a frame without touching the WebView. */
    private suspend fun loadNativeCard(
        cardId: Long,
        side: String,
    ): NativeReviewerCard? =
        withContext(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val collectionDirectory =
                runCatching {
                    CollectionHelper.getCurrentAnkiDroidDirectory(context)
                }.getOrNull() ?: return@withContext null
            val irText = CardIrStore.readText(collectionDirectory, cardId) ?: return@withContext null
            val ir = CardIrParser.parse(irText) ?: return@withContext null
            if (ir.unsupported.isNotEmpty() || ir.behaviors.any { !it.isSupportedByNativeReviewer() }) {
                return@withContext null
            }
            val capturedUnsupported =
                runCatching {
                    JSONObject(irText)
                        .optJSONObject("android_capture_unsupported")
                        ?.optJSONArray(side)
                        ?.length() ?: 0
                }.getOrDefault(0)
            if (capturedUnsupported > 0) return@withContext null
            // Never substitute the question frame for an answer. Some cards
            // have only one captured face while the other is still loading.
            if (ir.sides[side].isNullOrEmpty()) return@withContext null
            val (frame, variant) =
                CardIrParser.selectFrame(ir, side, Themes.isNightTheme, 0) ?: return@withContext null
            val mediaDirectory = runCatching { CollectionHelper.getMediaDirectory(context) }.getOrNull()
            val fonts = CardIrFonts.load(mediaDirectory)
            if (!supportsNativeReviewer(frame.root, fonts) ||
                !CardIrMedia.imagesAreRenderable(frame.root, mediaDirectory)
            ) {
                return@withContext null
            }
            val irJson = irText.encodeToByteArray()
            // Ruby, cloze, and inline sup/sub need captured browser positions;
            // other supported nodes are positioned by the shared Taffy kernel.
            if (!frame.root.requiresCapturedGeometry() &&
                TaffyCardLayout.layout(irJson, side, variant, frame.width, fonts) == null
            ) {
                return@withContext null
            }
            NativeReviewerCard(
                cardId = cardId,
                irJson = irJson,
                frame = frame,
                side = side,
                variant = variant,
                fonts = fonts,
                mediaDirectory = mediaDirectory,
            )
        }

    private fun supportsNativeReviewer(
        node: IrNode,
        fonts: CardIrFonts,
        inheritedStyle: Map<String, String> = emptyMap(),
    ): Boolean {
        val style = CardIrStyle.inheritedStyle(inheritedStyle, node.style)
        val supportedType = node.type in setOf("text", "box", "image", "svg", "audio", "break", "ruby", "cloze")
        val supportedActions =
            node.actions.all { action ->
                action.on in setOf("reveal", "click", "tap") &&
                    action.kind == "reveal-cloze" && node.type == "cloze"
            }
        val hasRenderableClozeAnswer =
            if (node.type != "cloze") {
                true
            } else {
                val answer = node.attrs["data-cloze"]
                val paint =
                    TextPaint().apply {
                        textSize = CardIrStyle.fontSize(style)
                        typeface = fonts.resolve(style)
                        letterSpacing = if (textSize > 0f) CardIrStyle.length(style, "letter-spacing") / textSize else 0f
                    }
                answer != null && !Regex("<[^>]*>").containsMatchIn(answer) &&
                    '\n' !in answer && '\r' !in answer &&
                    paint.measureText(
                        answer,
                    ) + CardIrStyle.length(style, "word-spacing") * answer.count { it == ' ' } <= node.rect.w + 0.5f &&
                    if (node.needsClozeReveal()) {
                        node.actions.any { it.kind == "reveal-cloze" }
                    } else {
                        node.displayedText() == answer
                    }
            }
        val childStyle = CardIrStyle.childStyle(style)
        return supportedType && supportedActions && hasRenderableClozeAnswer &&
            node.children.all { supportsNativeReviewer(it, fonts, childStyle) }
    }

    /** These actions are dispatched through the reviewer's existing audio and URL handlers. */
    private fun com.ichi2.anki.pages.cardir.IrBehavior.isSupportedByNativeReviewer(): Boolean =
        (kind in setOf("play-audio", "replay-audio", "open-link") && trigger in setOf("click", "tap")) ||
            (kind == "reveal-cloze" && trigger in setOf("click", "tap", "reveal"))

    /** Route supported native links through the Reviewer's existing URL handler. */
    private fun handleNativeCardAction(node: IrNode) {
        if (node.type == "audio") {
            val audioUrl = node.src?.takeIf { it.matches(Regex("playsound:[qa]:\\d+")) } ?: return
            viewModel.playSoundFromUrl(audioUrl)
            return
        }
        val link = node.src?.takeIf { node.tag == "a" && it.matches(Regex("(?i)(https?|mailto|tel):.*")) } ?: return
        (requireActivity() as? AbstractFlashcardViewer)?.webViewClient?.filterUrl(link)
    }

    private fun IrNode.requiresCapturedGeometry(): Boolean =
        type == "ruby" || type == "cloze" || attrs["captured-inline"] == "true" || children.any { it.requiresCapturedGeometry() }

    private fun showNativeCard(card: NativeReviewerCard?) {
        nativeCardContent.value = card
        binding.nativeCardCompose.isVisible = card != null
        binding.webViewLayout.isVisible = card == null
    }

    /** Restore this face in WebView if a runtime Taffy layout fails after its eval was skipped. */
    private fun fallbackNativeCardToWebView(
        cardId: Long,
        side: String,
    ) {
        if (nativeCardContent.value?.let { it.cardId == cardId && it.side == side } != true) return
        val needsWebViewEval = currentFaceSkippedWebView && !Prefs.useWebViewCardRenderer
        currentFaceSkippedWebView = false
        showNativeCard(null)
        if (!needsWebViewEval) return
        lifecycleScope.launch {
            if (viewModel.getCardId() == cardId &&
                (if (viewModel.showingAnswer.value) "answer" else "question") == side
            ) {
                viewModel.reloadCurrentSideInWebView()
            }
        }
    }

    /** Routes Canvas taps through the same user-configured tap gesture map as WebView. */
    private fun handleNativeCardTap(
        tap: Offset,
        scrollOffsetY: Int,
    ) {
        val scale = webViewLayout.scale.takeIf { it > 0f } ?: 1f
        val tapUri =
            Uri
                .parse("gesture://tapOrSwipe")
                .buildUpon()
                .appendQueryParameter("x", (tap.x / scale).roundToInt().toString())
                .appendQueryParameter("y", ((tap.y - scrollOffsetY) / scale).roundToInt().toString())
                .appendQueryParameter("deltaX", "0")
                .appendQueryParameter("deltaY", "0")
                .appendQueryParameter("time", System.currentTimeMillis().toString())
                .build()
        dispatchNativeGesture(tapUri)
    }

    private fun handleNativeCardSwipe(
        deltaX: Float = 0f,
        deltaY: Float = 0f,
    ) {
        val scale = webViewLayout.scale.takeIf { it > 0f } ?: 1f
        val swipeUri =
            Uri
                .parse("gesture://tapOrSwipe")
                .buildUpon()
                .appendQueryParameter("x", "0")
                .appendQueryParameter("y", "0")
                .appendQueryParameter("deltaX", (deltaX / scale).roundToInt().toString())
                .appendQueryParameter("deltaY", (deltaY / scale).roundToInt().toString())
                .appendQueryParameter("time", System.currentTimeMillis().toString())
                .build()
        dispatchNativeGesture(swipeUri)
    }

    private fun handleNativeMultiTouch(touchCount: Int) {
        val multiTouchUri =
            Uri
                .parse("gesture://multiFingerTap")
                .buildUpon()
                .appendQueryParameter("touchCount", touchCount.toString())
                .build()
        dispatchNativeGesture(multiTouchUri)
    }

    private fun dispatchNativeGesture(uri: Uri) {
        val scale = webViewLayout.scale.takeIf { it > 0f } ?: 1f
        val surfaceState =
            GestureParser.WebViewState(
                scale = scale,
                scrollX = 0,
                scrollY = 0,
                width = binding.nativeCardCompose.width,
                height = binding.nativeCardCompose.height,
            )
        gestureParser.parse(uri, surfaceState) { gesture ->
            if (gesture != null) bindingMap.onGesture(gesture)
        }
    }

    /** Captures the settled WebView DOM once, then keeps the IR as a local sidecar. */
    private fun captureCurrentCardFace(expectedSide: String? = null) {
        cardCaptureJob?.cancel()
        if (Prefs.useWebViewCardRenderer) return
        cardCaptureJob =
            lifecycleScope.launch {
                delay(120)
                val cardId = runCatching { viewModel.getCardId() }.getOrNull() ?: return@launch
                val side = if (viewModel.showingAnswer.value) "answer" else "question"
                if (expectedSide != null && expectedSide != side) return@launch
                // Keep desktop-compiled IR intact when it already rendered this
                // face. DOM capture is only the compatibility path for a missing
                // or unsupported native frame.
                nativeCardLoadJob?.join()
                if (nativeCardContent.value?.let { it.cardId == cardId && it.side == side } == true) return@launch
                val script =
                    "window.ankiCaptureCardIr?.(" +
                        "${JSONObject.quote(side)}," +
                        "${JSONObject.quote(cardId.toString())}," +
                        "${Themes.isNightTheme}" +
                        ");"
                webViewLayout.evaluateJavascript(script) { result ->
                    val snapshot =
                        runCatching { JSONTokener(result).nextValue() as? JSONObject }
                            .getOrNull() ?: return@evaluateJavascript
                    if (snapshot.optString("side") != side || snapshot.optString("card_id") != cardId.toString()) {
                        return@evaluateJavascript
                    }
                    val frameJson = snapshot.optJSONObject("frame")?.toString() ?: return@evaluateJavascript
                    val variant = snapshot.optString("variant").takeIf(String::isNotBlank) ?: return@evaluateJavascript
                    val unsupportedJson = snapshot.optJSONArray("unsupported") ?: JSONArray()
                    val unsupported =
                        (0 until unsupportedJson.length())
                            .mapNotNull { index -> unsupportedJson.optString(index).takeIf(String::isNotBlank) }
                    lifecycleScope.launch {
                        if (viewModel.getCardId() != cardId ||
                            (if (viewModel.showingAnswer.value) "answer" else "question") != side ||
                            Prefs.useWebViewCardRenderer
                        ) {
                            return@launch
                        }
                        val saved =
                            withContext(Dispatchers.IO) {
                                val context = requireContext().applicationContext
                                val collectionDirectory =
                                    runCatching { CollectionHelper.getCurrentAnkiDroidDirectory(context) }
                                        .getOrNull() ?: return@withContext false
                                CardIrStore.writeCapturedFace(
                                    collectionDirectory = collectionDirectory,
                                    cardId = cardId,
                                    side = side,
                                    variant = variant,
                                    frameJson = frameJson,
                                    unsupported = unsupported,
                                )
                            }
                        if (saved && !Prefs.useWebViewCardRenderer && viewModel.getCardId() == cardId &&
                            (if (viewModel.showingAnswer.value) "answer" else "question") == side
                        ) {
                            refreshNativeCard()
                        }
                    }
                }
            }
    }

    override fun onDestroyView() {
        nativeCardLoadJob?.cancel()
        cardCaptureJob?.cancel()
        nativeCardContent.value = null
        super.onDestroyView()
    }

    private fun setupTypeAnswer() {
        binding.typeAnswerEditText.apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    viewModel.onShowAnswer()
                    return@setOnEditorActionListener true
                }
                false
            }
            setOnFocusChangeListener { editTextView, hasFocus ->
                val insetsController = WindowInsetsControllerCompat(window, editTextView)
                if (hasFocus) {
                    insetsController.show(WindowInsetsCompat.Type.ime())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.ime())
                }
            }
        }

        val isHtmlTypeAnswerEnabled = Prefs.isHtmlTypeAnswerEnabled
        lifecycleScope.launch {
            val autoFocusTypeAnswer = Prefs.autoFocusTypeAnswer

            /**
             * Sync `imeHintLocales` on the answer `EditText` to match [typeInAnswer].
             * Returns `true` if anything changed (caller should `restartInput()`).
             */
            fun EditText.syncTypeAnswerProperties(typeInAnswer: TypeAnswer): Boolean {
                if (imeHintLocales == typeInAnswer.imeHintLocales) return false
                imeHintLocales = typeInAnswer.imeHintLocales
                return true
            }

            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.typeAnswerFlow.collect { typeInAnswer ->
                    if (typeInAnswer == null) {
                        binding.typeAnswerContainer.isVisible = false
                        return@collect
                    }

                    if (isHtmlTypeAnswerEnabled) {
                        if (!autoFocusTypeAnswer) return@collect
                        webViewLayout.focusOnWebView()
                        // `evaluateJavascript()` doesn't trigger the IME unless the WebView
                        // has been touched before, so ´loadUrl()` is used instead.
                        webViewLayout.loadUrl("javascript:document.getElementById('typeans')?.focus();")
                        return@collect
                    }

                    binding.typeAnswerContainer.isVisible = true
                    binding.typeAnswerEditText.apply {
                        if (syncTypeAnswerProperties(typeInAnswer)) {
                            context?.getSystemService<InputMethodManager>()?.restartInput(this)
                        }
                        if (autoFocusTypeAnswer) {
                            requestFocus()
                        }
                    }
                }
            }
        }
        viewModel.onCardUpdatedFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) {
            binding.typeAnswerEditText.text = null
        }

        viewModel.onTypedAnswerResultFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { request ->
                if (isHtmlTypeAnswerEnabled) {
                    val script = """document.getElementById("typeans").value;"""
                    webViewLayout.evaluateJavascript(script) { callback ->
                        // the retuned string comes with surrounding `"`, so remove it once
                        val typedAnswer = callback.removeSurrounding("\"")
                        request.complete(typedAnswer)
                    }
                } else {
                    val typedAnswer = binding.typeAnswerEditText.text.toString()
                    request.complete(typedAnswer)
                }
            }
    }

    private fun resetZoom() {
        webViewLayout.settings.loadWithOverviewMode = false
        webViewLayout.settings.loadWithOverviewMode = true
    }

    @NeedsTest("Whiteboard takes priority on key events")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.action != KeyEvent.ACTION_DOWN ||
            view?.let { binding.typeAnswerEditText }?.isFocused == true
        ) {
            return false
        }
        return whiteboardFragment?.dispatchKeyEvent(event) == true || bindingMap.onKeyDown(event)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        Timber.v("ReviewerFragment::onMenuItemClick %s", item)
        if (item.hasSubMenu()) return false
        val action = ViewerAction.fromId(item.itemId)
        viewModel.executeAction(action)
        return true
    }

    @NeedsTest("Whiteboard takes priority on shake events")
    override fun hearShake() {
        // Sensor events still arrive while a dialog or another window covers the reviewer.
        if (view?.hasWindowFocus() != true) {
            Timber.d("Ignoring shake: reviewer window does not have focus")
            return
        }

        if (whiteboardFragment?.onScreenShake() != true) {
            bindingMap.onGesture(Gesture.SHAKE)
        }
    }

    private fun setupBindings() {
        bindingMap = BindingMap(sharedPrefs(), ViewerAction.entries, viewModel)
        binding.root.setOnGenericMotionListener { _, event ->
            bindingMap.onGenericMotionEvent(event)
        }
        if (bindingMap.isBound(Gesture.SHAKE)) {
            shakeDetector = AnkiShakeDetector.createInstance(requireContext(), this)
        }
    }

    private fun setupAnswerButtons() {
        if (!Prefs.showAnswerButtons) {
            binding.answerArea.isVisible = false
            return
        }

        binding.answerArea.setButtonListeners(
            onRatingClicked = { viewModel.answerCard(it) },
            onShowAnswerClicked = { viewModel.onShowAnswer() },
        )

        binding.answerArea.setRelativeHeight(Prefs.newStudyScreenAnswerButtonSize)

        viewModel.answerButtonsNextTimeFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { times ->
                binding.answerArea.setNextTimes(times)
            }

        val insetsController = WindowInsetsControllerCompat(window, binding.rootLayout)
        viewModel.showingAnswer.collectLatestIn(lifecycleScope) { isAnswerShown ->
            if (isAnswerShown) {
                insetsController.hide(WindowInsetsCompat.Type.ime())
            }
            binding.answerArea.setAnswerState(isAnswerShown)
        }

        if (Prefs.hideHardAndEasyButtons) {
            binding.answerArea.hideHardAndEasyButtons()
        }
    }

    private fun setupCounts() {
        viewModel.countsFlow
            .flowWithLifecycle(lifecycle)
            .collectLatestIn(lifecycleScope) { counts ->
                binding.studyCounts.updateCounts(counts)
            }

        lifecycleScope.launch {
            if (!CollectionPreferences.getShowRemainingDueCounts()) {
                binding.studyCounts.isVisible = false
            }
        }
    }

    private fun setupCardInfo() {
        viewModel.cardInfoFlow
            .flowWithLifecycle(lifecycle)
            .collectLatestIn(lifecycleScope) { info ->
                binding.reviewCardInfo.isVisible = info != null
                binding.reviewCardInfo.text =
                    info?.let {
                        ReviewCardInfo.text(it, getString(R.string.review_recent_ratings), getString(R.string.review_eta))
                    }
            }

        // B28 · optional clickable note tags. Mirrors the desktop chip row:
        // a tag tap opens the browser filtered by a quoted tag search.
        lifecycleScope.launch {
            viewModel.cardInfoFlow
                .flowWithLifecycle(lifecycle)
                .collectLatestIn(lifecycleScope) { info ->
                    val tags = info?.tags.orEmpty()
                    if (tags.isEmpty()) {
                        binding.reviewCardTags.isVisible = false
                        return@collectLatestIn
                    }
                    val spannable = android.text.SpannableString(tags.joinToString("  "))
                    var offset = 0
                    for (tag in tags) {
                        val query =
                            com.ichi2.anki.addons.AddonTools
                                .tagSearchQuery(tag)
                        spannable.setSpan(
                            object : android.text.style.ClickableSpan() {
                                override fun onClick(widget: android.view.View) {
                                    startActivity(BrowserDestination.Search(query, allDecks = true).toIntent(requireContext()))
                                }
                            },
                            offset,
                            offset + tag.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        offset += tag.length + 2
                    }
                    binding.reviewCardTags.text = spannable
                    binding.reviewCardTags.movementMethod =
                        android.text.method.LinkMovementMethod
                            .getInstance()
                    binding.reviewCardTags.isVisible = true
                }
        }
    }

    private fun setupMenu() {
        binding.reviewerMenuView.apply {
            setup(lifecycle, viewModel)
            setOnMenuItemClickListener(this@ReviewerFragment)
        }
    }

    private fun setupImmersiveMode() {
        val barsToHide =
            when (Prefs.hideSystemBars) {
                HideSystemBars.NONE -> return
                HideSystemBars.STATUS_BAR -> WindowInsetsCompat.Type.statusBars()
                HideSystemBars.NAVIGATION_BAR -> WindowInsetsCompat.Type.navigationBars()
                HideSystemBars.ALL -> WindowInsetsCompat.Type.systemBars()
            }

        val window = requireActivity().window
        with(WindowInsetsControllerCompat(window, window.decorView)) {
            hide(barsToHide)
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val minTopPadding =
            if (Prefs.frameStyle == FrameStyle.CARD && Prefs.toolbarPosition != ToolbarPosition.TOP) {
                8F.dp.toPx(requireContext())
            } else {
                0
            }
        val ignoreDisplayCutout = Prefs.ignoreDisplayCutout
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val defaultTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val typeMask =
                if (ignoreDisplayCutout) {
                    defaultTypes
                } else {
                    defaultTypes or WindowInsetsCompat.Type.displayCutout()
                }
            val bars = insets.getInsets(typeMask)
            // don't let a 'card' frame reach the top of the screen
            val topPadding = max(bars.top, minTopPadding)
            v.updatePadding(
                left = bars.left,
                top = topPadding,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbarPosition() {
        when (Prefs.toolbarPosition) {
            ToolbarPosition.TOP -> return
            ToolbarPosition.NONE -> binding.toolsLayout.isVisible = false
            ToolbarPosition.BOTTOM -> {
                binding.mainLayout.removeView(binding.toolsLayout)
                binding.mainLayout.addView(binding.toolsLayout)

                // Put the answer buttons inside the toolbar on big screens
                if (!isBigScreen || !Prefs.showAnswerButtons) return
                binding.bottomLayout.removeView(binding.answerArea)
                binding.toolsLayout.addView(binding.answerArea)
                binding.answerArea.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    width = 0
                    matchConstraintPercentWidth = 0.6F
                    matchConstraintMaxWidth = 480.dp.toPx(requireContext())
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                binding.reviewerMenuView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    width = 0
                    matchConstraintPercentWidth = 0.16F
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                }
            }
        }
    }

    /**
     * Updates margins based on the possible combinations
     * of [Prefs.toolbarPosition], [Prefs.frameStyle] and `Hide answer buttons`
     */
    private fun setupMargins() {
        if (Prefs.toolbarPosition == ToolbarPosition.BOTTOM && !isBigScreen) {
            binding.bottomLayout.showDividers =
                LinearLayout.SHOW_DIVIDER_MIDDLE or LinearLayout.SHOW_DIVIDER_BEGINNING
        }
    }

    private fun setupAnswerTimer() {
        lifecycle.addObserver(viewModel.answerTimer)
        viewModel.answerTimer.state.collectIn(lifecycleScope) { state ->
            binding.timer.setup(state)
        }
    }

    private fun setupResetProgress() {
        viewModel.resetProgressFlow
            .flowWithLifecycle(lifecycle)
            .onEach {
                showDialogFragment(ForgetCardsDialog())
            }.launchIn(lifecycleScope)
        // TODO handle 'Reset progress' in the ViewModel instead of the activity, once
        //  a mechanism of showing a progress bar if the operation takes too long is implemented
        registerOnForgetHandler { listOf(viewModel.getCardId()) }
    }

    private fun setupCheckPronunciation() {
        viewModel.voiceRecorderEnabledFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { isEnabled ->
            if (isEnabled && binding.checkPronunciationContainer.getFragment<CheckPronunciationFragment?>() == null) {
                childFragmentManager.commit {
                    add(binding.checkPronunciationContainer.id, CheckPronunciationFragment())
                }
            }
            binding.checkPronunciationContainer.isVisible = isEnabled
        }
    }

    private fun setupWhiteboard() {
        childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (f !is WhiteboardFragment) return
                    f.setOnScrollByListener { y ->
                        webViewLayout.scrollVerticallyBy(y)
                    }
                }
            },
            false,
        )

        viewModel.whiteboardEnabledFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { isEnabled ->
            val existingFragment = whiteboardFragment
            childFragmentManager.commit {
                if (isEnabled) {
                    if (existingFragment != null) {
                        show(existingFragment)
                    } else {
                        val newFragment = WhiteboardFragment()
                        newFragment.gestureFallbackListener = { gesture -> bindingMap.onGesture(gesture) }
                        add(R.id.web_view_container, newFragment, WhiteboardFragment::class.jvmName)
                    }
                } else {
                    existingFragment?.let { hide(it) }
                }
            }
        }
        viewModel.onCardUpdatedFlow.collectIn(lifecycleScope) {
            whiteboardFragment?.resetCanvas()
        }
    }

    private fun setupTimebox() {
        viewModel.timeBoxReachedFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { timebox ->
            Timber.i("ReviewerFragment: Timebox reached (reps %d - secs %d)", timebox.reps, timebox.secs)

            viewModel.stopAutoAdvance()

            val minutes = (timebox.secs / 60f).roundToInt()
            val message = CollectionManager.TR.studyingCardStudiedIn(timebox.reps) + " " + CollectionManager.TR.studyingMinute(minutes)

            AlertDialog.Builder(requireContext()).show {
                setTitle(R.string.timebox_reached_title)
                setMessage(message)
                setPositiveButton(CollectionManager.TR.studyingContinue()) { _, _ ->
                    Timber.i("ReviewerFragment: Timebox 'Continue'")
                    viewModel.onPageFinished(false)
                }
                setNegativeButton(CollectionManager.TR.studyingFinish()) { _, _ ->
                    Timber.i("ReviewerFragment: Timebox 'Finish'")
                    requireActivity().finish()
                }
                setCancelable(false)
            }
        }
    }

    private fun setupActions() {
        viewModel.actionFeedbackFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { message ->
                showSnackbar(message, duration = 500)
            }

        viewModel.editNoteTagsFlow.collectIn(lifecycleScope) { noteId ->
            tagsDialogFactory.show(requireActivity(), noteIds = listOf(noteId))
        }

        viewModel.setDueDateFlow.collectIn(lifecycleScope) { cardId ->
            SetDueDateDialog.show(requireActivity(), listOf(cardId))
        }

        viewModel.copyCardLinkFlow.collectIn(lifecycleScope) { link ->
            requireContext().copyToClipboard(TruncatedString.from(link), successMessageId = R.string.card_link_copied)
        }

        viewModel.pageUpFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) {
            webViewLayout.pageUp()
        }

        viewModel.pageDownFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) {
            webViewLayout.pageDown()
        }

        val repository = StudyScreenRepository()

        viewModel.isMarkedFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { isMarked ->
                if (!repository.isMarkShownInToolbar) {
                    binding.markIcon.isVisible = isMarked
                }
            }
        val flagView = binding.flagIcon
        viewModel.flagFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { flag ->
                if (!repository.isFlagShownInToolbar) {
                    if (flag == Flag.NONE) {
                        flagView.isVisible = false
                    } else {
                        flagView.setImageDrawable(ContextCompat.getDrawable(requireContext(), flag.drawableRes))
                        flagView.isVisible = true
                    }
                }
            }
    }

    override fun onSelectedTags(
        selectedTags: List<String>,
        indeterminateTags: List<String>,
        stateFilter: CardStateFilter,
    ) = viewModel.onEditedTags(selectedTags)

    override fun onCreateWebViewClient(savedInstanceState: Bundle?): CardViewerWebViewClient = ReviewerWebViewClient(savedInstanceState)

    override fun onCreateWebChromeClient(): CardViewerWebChromeClient = ReviewerWebChromeClient()

    private inner class ReviewerWebViewClient(
        savedInstanceState: Bundle?,
    ) : CardViewerWebViewClient(savedInstanceState) {
        private var scale: Float = if (!isRobolectric) webViewLayout.scale else 1F
        private var isScrolling: Boolean = false
        private var isScrollingJob: Job? = null
        private var hasShownUnsupportedFeatureWarning = false

        init {
            webViewLayout.setOnScrollChangeListener { _, _, _, _, _ ->
                isScrolling = true
                isScrollingJob?.cancel()
                isScrollingJob =
                    lifecycleScope.launch {
                        delay(300)
                        isScrolling = false
                    }
            }
        }

        override fun handleUrl(
            webView: WebView,
            url: Uri,
        ): Boolean {
            return when (url.scheme) {
                "anki-wikilink" -> {
                    val cardId = url.getQueryParameter("cardId")?.toLongOrNull()
                    if (cardId != null && cardId > 0) {
                        navigate(CardInfoDestination(cardId, EntryPoint.CURRENT_CARD_STUDY))
                        return true
                    }
                    val title = url.getQueryParameter("title")?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        val escaped = title.replace("\"", "\\\"")
                        val query = "\"$escaped\""
                        startActivity(BrowserDestination.Search(query, allDecks = true).toIntent(requireContext()))
                    }
                    true
                }
                "gesture" -> {
                    if (isScrolling) return true
                    gestureParser.parse(url, scale, webView) { gesture ->
                        if (gesture == null) return@parse
                        Timber.v("ReviewerFragment::onGesture %s", gesture)
                        bindingMap.onGesture(gesture)
                    }
                    true
                }
                "ankidroid" -> {
                    when (url.host) {
                        "show-answer" -> viewModel.onShowAnswer()
                    }
                    true
                }
                "signal" -> {
                    if (hasShownUnsupportedFeatureWarning) return true
                    hasShownUnsupportedFeatureWarning = true
                    AlertDialog.Builder(requireContext()).show {
                        setMessage(R.string.feature_not_supported_by_study_screen)
                        setPositiveButton(R.string.dialog_ok) { _, _ -> }
                    }
                    true
                }
                else -> super.handleUrl(webView, url)
            }
        }

        override fun onScaleChanged(
            view: WebView?,
            oldScale: Float,
            newScale: Float,
        ) {
            super.onScaleChanged(view, oldScale, newScale)
            scale = newScale
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            super.onPageFinished(view, url)
            refreshNativeCard()
            view?.evaluateJavascript(wikilinksScript, null)
            val pageVersion = ++wikilinkPageVersion
            val page = view
            lifecycleScope.launch {
                val backlinks = runCatching { viewModel.wikilinkBacklinks() }.getOrDefault(emptyList())
                if (backlinks.isNotEmpty() && pageVersion == wikilinkPageVersion) {
                    page?.evaluateJavascript("window.ankiAddWikilinkBacklinks(${JSONArray(backlinks)})", null)
                }
            }
            Prefs.cardZoom.let {
                if (it == 100) return@let
                val scale = it / 100.0
                val script = """document.body.style.zoom = `$scale`;"""
                view?.evaluateJavascript(script, null)
            }
        }
    }

    /** Turn [[cardId]] / [[First field]] references into internal links and a compact chain map. */
    private val wikilinksScript =
        """
        (function(){
          const found=[];
          function ensureMap(){
            let map=document.getElementById('anki-wikilink-map'); if(map) return map;
            map=document.createElement('div'); map.id='anki-wikilink-map';
            map.style.cssText='position:fixed;z-index:2147483647;bottom:8px;left:8px;right:8px;padding:7px 10px;border-radius:8px;background:rgba(30,45,65,.92);color:white;font:13px sans-serif;overflow-x:auto;white-space:nowrap';
            const label=document.createElement('span'); label.textContent='关联链路：当前笔记'; map.append(label); document.body.append(map); return map;
          }
          const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
          const nodes=[]; while(walker.nextNode()) nodes.push(walker.currentNode);
          for(const node of nodes){
            if(!node.parentElement || /^(SCRIPT|STYLE|A|TEXTAREA)$/.test(node.parentElement.tagName)) continue;
            const text=node.nodeValue; if(!text || !/\[\[[^\]]+\]\]/.test(text)) continue;
            const fragment=document.createDocumentFragment(); let last=0;
            const re=/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g; let match;
            while((match=re.exec(text))){
              fragment.append(document.createTextNode(text.slice(last,match.index)));
              const title=match[1].trim(); if(!title) continue;
              const link=document.createElement('a');
              if(/^\d+$/.test(title)){ link.href='anki-wikilink://open?cardId='+title; link.textContent=match[2]?match[2].trim():('卡片 '+title); }
              else { link.href='anki-wikilink://open?title='+encodeURIComponent(title); link.textContent=(match[2]||title).trim(); }
              link.style.cssText='color:#3978d4;text-decoration:underline'; fragment.append(link);
              if(!found.some(f=>f.href===link.href)) found.push({label:link.textContent,href:link.href}); last=re.lastIndex;
            }
            fragment.append(document.createTextNode(text.slice(last))); node.replaceWith(fragment);
          }
          const map=found.length ? ensureMap() : null;
          for(const item of found){ const arrow=document.createElement('span'); arrow.textContent='  →  '; map.append(arrow);
            const link=document.createElement('a'); link.textContent=item.label; link.href=item.href;
            link.style.cssText='color:#b9d7ff;text-decoration:underline'; map.append(link); }
          window.ankiAddWikilinkBacklinks=function(items){
            if(!items || !items.length) return; const graph=ensureMap();
            for(const item of items){ const arrow=document.createElement('span'); arrow.textContent='  → 当前笔记 · '; graph.append(arrow);
              const link=document.createElement('a'); link.textContent=item.label; link.href=item.href;
              link.style.cssText='color:#b9d7ff;text-decoration:underline'; graph.append(link); }
          };
        })();
        """.trimIndent()

    private inner class ReviewerWebChromeClient : CardViewerWebChromeClient() {
        override fun onHideCustomView() {
            val barsToHide = Prefs.hideSystemBars
            if (barsToHide == HideSystemBars.NONE) {
                super.onHideCustomView()
            } else {
                val window = requireActivity().window
                (window.decorView as FrameLayout).removeView(paramView)

                val barsToShowBack =
                    when (barsToHide) {
                        HideSystemBars.STATUS_BAR -> WindowInsetsCompat.Type.navigationBars()
                        HideSystemBars.NAVIGATION_BAR -> WindowInsetsCompat.Type.statusBars()
                        HideSystemBars.ALL, HideSystemBars.NONE -> return
                    }
                with(WindowInsetsControllerCompat(window, window.decorView)) {
                    show(barsToShowBack)
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    companion object {
        fun getIntent(context: Context): Intent = CardViewerActivity.getIntent(context, ReviewerFragment::class)
    }
}
