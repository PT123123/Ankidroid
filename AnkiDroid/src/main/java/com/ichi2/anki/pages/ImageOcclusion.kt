// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2023 Abdo <abdo@abdnh.net>

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import anki.image_occlusion.GetImageOcclusionNoteResponse
import com.google.android.material.appbar.MaterialToolbar
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NoteEditorFragment
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.dialogs.DiscardChangesDialog
import com.ichi2.anki.dialogs.registerDeckSelectedHandler
import com.ichi2.anki.dialogs.startDeckSelection
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.pages.viewmodel.ImageOcclusionArgs
import com.ichi2.anki.pages.viewmodel.ImageOcclusionViewModel
import com.ichi2.anki.pages.viewmodel.ImageOcclusionViewModel.Companion.ARG_IMAGE_OCCLUSION
import com.ichi2.anki.utils.ext.launchCollectionInLifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** Native canvas page for creating or editing an image-occlusion note. */
class ImageOcclusion : Fragment(R.layout.page_image_occlusion) {
    private val viewModel: ImageOcclusionViewModel by viewModels()
    private lateinit var deckNameView: TextView
    private lateinit var canvas: ImageOcclusionCanvasView
    private lateinit var headerField: TextView
    private lateinit var backExtraField: TextView
    private lateinit var tagsField: TextView
    private var originalImagePath: String? = null
    private val preservedShapes = mutableListOf<String>()
    private var dirty = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (dirty) {
                DiscardChangesDialog.showDialog(requireActivity()) { requireActivity().finish() }
            } else {
                requireActivity().finish()
            }
        }

        deckNameView = view.findViewById(R.id.deck_name)
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        canvas = view.findViewById(R.id.occlusion_canvas)
        headerField = view.findViewById(R.id.header_field)
        backExtraField = view.findViewById(R.id.back_extra_field)
        tagsField = view.findViewById(R.id.tags_field)
        deckNameView.setOnClickListener { startDeckSelection(allowAll = false, allowFiltered = false) }

        val args = viewModel.args
        deckNameView.isVisible = args is ImageOcclusionArgs.Add
        registerDeckSelectedHandler(action = ::onDeckSelected)
        setupEditorControls(view)
        setupFlows()
        loadEditor()

        view.findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            if (it.itemId == R.id.action_save) save()
            true
        }
    }

    private fun setupEditorControls(view: View) {
        val masksPanel = view.findViewById<View>(R.id.masks_panel)
        val notesPanel = view.findViewById<View>(R.id.notes_panel)
        val toolsBar = view.findViewById<View>(R.id.tools_bar)
        view
            .findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.editor_tabs)
            .addOnButtonCheckedListener { _, checkedId, checked ->
                if (checked) {
                    val showingMasks = checkedId == R.id.tab_masks
                    masksPanel.isVisible = showingMasks
                    notesPanel.isVisible = !showingMasks
                    toolsBar.isVisible = showingMasks
                }
            }
        view.findViewById<View>(R.id.tab_masks).isSelected = true
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.tab_masks).isChecked = true
        view.findViewById<View>(R.id.tool_select).setOnClickListener { canvas.tool = ImageOcclusionCanvasView.Tool.SELECT }
        view.findViewById<View>(R.id.tool_rectangle).setOnClickListener { canvas.tool = ImageOcclusionCanvasView.Tool.RECTANGLE }
        view.findViewById<View>(R.id.tool_ellipse).setOnClickListener { canvas.tool = ImageOcclusionCanvasView.Tool.ELLIPSE }
        view.findViewById<View>(R.id.tool_polygon).setOnClickListener { canvas.tool = ImageOcclusionCanvasView.Tool.POLYGON }
        view.findViewById<View>(R.id.tool_delete).setOnClickListener { canvas.removeSelected() }
        view.findViewById<View>(R.id.tool_undo).setOnClickListener { canvas.undo() }
        view.findViewById<View>(R.id.tool_redo).setOnClickListener { canvas.redo() }
        canvas.onMasksChanged = { dirty = true }
        listOf(headerField, backExtraField, tagsField).forEach { field ->
            field.addTextChangedListener(SimpleTextWatcher { dirty = true })
        }
    }

    private fun loadEditor() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                preservedShapes.clear()
                when (val args = viewModel.args) {
                    is ImageOcclusionArgs.Add -> {
                        originalImagePath = args.imagePath
                        val response = withCol { getImageForOcclusion(args.imagePath) }
                        canvas.setImage(decodeEditorImage(response.data.toByteArray()) ?: error("Unable to decode image"))
                    }
                    is ImageOcclusionArgs.Edit -> {
                        canvas.tool = ImageOcclusionCanvasView.Tool.SELECT
                        val response = withCol { getImageOcclusionNote(args.noteId) }
                        if (response.valueCase != GetImageOcclusionNoteResponse.ValueCase.NOTE) {
                            error(response.error.ifBlank { "Unable to load image occlusion note" })
                        }
                        val note = response.note
                        originalImagePath = note.imageFileName
                        canvas.setImage(decodeEditorImage(note.imageData.toByteArray()) ?: error("Unable to decode image"))
                        headerField.text = Html.fromHtml(note.header, Html.FROM_HTML_MODE_LEGACY)
                        backExtraField.text = Html.fromHtml(note.backExtra, Html.FROM_HTML_MODE_LEGACY)
                        tagsField.text = note.tagsList.joinToString(" ")
                        preservedShapes.clear()
                        canvas.setNormalizedMasks(
                            note.occlusionsList.flatMap { group ->
                                group.shapesList.mapNotNull { shape ->
                                    val props = shape.propertiesList.associate { it.name to it.value }
                                    val tool =
                                        when (shape.shape) {
                                            "rect" -> ImageOcclusionCanvasView.Tool.RECTANGLE
                                            "ellipse" -> ImageOcclusionCanvasView.Tool.ELLIPSE
                                            "polygon" -> ImageOcclusionCanvasView.Tool.POLYGON
                                            else -> null
                                        } ?: run {
                                            preservedShapes += shape.toClozeShape(group.ordinal)
                                            return@mapNotNull null
                                        }
                                    val points =
                                        props["points"].orEmpty().split(' ').mapNotNull { pair ->
                                            val coordinates = pair.split(',')
                                            if (coordinates.size != 2) return@mapNotNull null
                                            val x = coordinates[0].toFloatOrNull() ?: return@mapNotNull null
                                            val y = coordinates[1].toFloatOrNull() ?: return@mapNotNull null
                                            ImageOcclusionCanvasView.Point(x, y)
                                        }
                                    val left =
                                        if (tool ==
                                            ImageOcclusionCanvasView.Tool.POLYGON
                                        ) {
                                            points.minOfOrNull { it.x } ?: 0f
                                        } else {
                                            props.float("left")
                                        }
                                    val top =
                                        if (tool ==
                                            ImageOcclusionCanvasView.Tool.POLYGON
                                        ) {
                                            points.minOfOrNull { it.y } ?: 0f
                                        } else {
                                            props.float("top")
                                        }
                                    val right =
                                        if (tool ==
                                            ImageOcclusionCanvasView.Tool.POLYGON
                                        ) {
                                            points.maxOfOrNull { it.x } ?: left
                                        } else {
                                            left +
                                                props.float(if (tool == ImageOcclusionCanvasView.Tool.ELLIPSE) "rx" else "width") *
                                                if (tool == ImageOcclusionCanvasView.Tool.ELLIPSE) 2f else 1f
                                        }
                                    val bottom =
                                        if (tool ==
                                            ImageOcclusionCanvasView.Tool.POLYGON
                                        ) {
                                            points.maxOfOrNull { it.y } ?: top
                                        } else {
                                            top +
                                                props.float(if (tool == ImageOcclusionCanvasView.Tool.ELLIPSE) "ry" else "height") *
                                                if (tool == ImageOcclusionCanvasView.Tool.ELLIPSE) 2f else 1f
                                        }
                                    ImageOcclusionCanvasView.Mask(
                                        tool,
                                        left,
                                        top,
                                        right - left,
                                        bottom - top,
                                        group.ordinal,
                                        points,
                                    )
                                }
                            },
                        )
                    }
                }
                dirty = false
            }.onFailure { error ->
                Timber.e(error, "Unable to load image occlusion editor")
                showThemedToast(requireContext(), error.message ?: getString(R.string.multimedia_editor_something_wrong), false)
            }
        }
    }

    private fun save() {
        val masks = canvas.normalizedMasks()
        if (masks.isEmpty()) {
            showThemedToast(requireContext(), R.string.image_occlusion_add_mask_before_saving, true)
            return
        }
        val usedOrdinals = masks.mapNotNull { it.ordinal }.toSet()
        val missingOrdinals = (1..(usedOrdinals.maxOrNull() ?: 0)).filterNot(usedOrdinals::contains).toMutableList()
        var nextOrdinal = (usedOrdinals.maxOrNull() ?: 0) + 1
        val occlusions =
            masks
                .map { mask ->
                    val ordinal = mask.ordinal ?: if (missingOrdinals.isNotEmpty()) missingOrdinals.removeAt(0) else nextOrdinal++
                    val kind =
                        when (mask.type) {
                            ImageOcclusionCanvasView.Tool.ELLIPSE -> "ellipse"
                            ImageOcclusionCanvasView.Tool.POLYGON -> "polygon"
                            else -> "rect"
                        }
                    val geometry =
                        when (kind) {
                            "ellipse" -> "rx=${mask.width / 2f}:ry=${mask.height / 2f}"
                            "polygon" -> "points=${mask.points.joinToString(" ") { "${it.x},${it.y}" }}"
                            else -> "width=${mask.width}:height=${mask.height}"
                        }
                    val position = if (kind == "polygon") "left=0:top=0" else "left=${mask.left}:top=${mask.top}"
                    "{{c$ordinal::image-occlusion:$kind:$position:$geometry}}<br>"
                }.joinToString("") + preservedShapes.joinToString("")
        val header = htmlField(headerField.text?.toString().orEmpty())
        val backExtra = htmlField(backExtraField.text?.toString().orEmpty())
        val tags =
            tagsField.text
                ?.toString()
                .orEmpty()
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                when (val args = viewModel.args) {
                    is ImageOcclusionArgs.Add ->
                        undoableOp {
                            addImageOcclusionNote(args.noteTypeId, args.imagePath, occlusions, header, backExtra, tags)
                        }
                    is ImageOcclusionArgs.Edit ->
                        undoableOp {
                            updateImageOcclusionNote(args.noteId, occlusions, header, backExtra, tags)
                        }
                }
                viewModel.onSaveOperationCompleted()
                dirty = false
                showThemedToast(requireContext(), R.string.note_message, true)
                delay(750)
                requireActivity().setResult(
                    if (viewModel.args is ImageOcclusionArgs.Edit) {
                        NoteEditorFragment.RESULT_UPDATED_IO_NOTE
                    } else {
                        android.app.Activity.RESULT_OK
                    },
                )
                requireActivity().finish()
            }.onFailure { error ->
                Timber.e(error, "Unable to save image occlusion note")
                showThemedToast(requireContext(), error.message ?: getString(R.string.multimedia_editor_something_wrong), false)
            }
        }
    }

    private fun onDeckSelected(deck: SelectableDeck?) {
        if (deck == null) return
        require(deck is SelectableDeck.Deck)
        viewModel.handleDeckSelection(deck.deckId)
    }

    private fun setupFlows() {
        viewModel.deckNameFlow?.let { flow ->
            flow.launchCollectionInLifecycleScope { deckNameView.text = it }
        } ?: run { deckNameView.isVisible = false }
    }

    companion object {
        fun getIntent(
            context: Context,
            args: ImageOcclusionArgs,
        ): Intent {
            val arguments = Bundle().apply { putParcelable(ARG_IMAGE_OCCLUSION, args) }
            return SingleFragmentActivity.getIntent(context, ImageOcclusion::class, arguments)
        }
    }
}

private fun Map<String, String>.float(name: String): Float = this[name]?.toFloatOrNull() ?: 0f

private fun anki.image_occlusion.GetImageOcclusionNoteResponse.ImageOcclusionShape.toClozeShape(ordinal: Int): String {
    val properties =
        propertiesList.joinToString("") { property ->
            val escaped = property.value.replace("\\", "\\\\").replace(":", "\\:")
            ":${property.name}=$escaped"
        }
    return "{{c$ordinal::image-occlusion:$shape$properties}}<br>"
}

private fun decodeEditorImage(data: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) sampleSize *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

private fun htmlField(value: String): String =
    value.takeIf(String::isNotBlank)?.let { "<div>${android.text.TextUtils.htmlEncode(it)}</div>" } ?: ""

private class SimpleTextWatcher(
    private val changed: () -> Unit,
) : android.text.TextWatcher {
    override fun beforeTextChanged(
        s: CharSequence?,
        start: Int,
        count: Int,
        after: Int,
    ) = Unit

    override fun onTextChanged(
        s: CharSequence?,
        start: Int,
        before: Int,
        count: Int,
    ) = changed()

    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
