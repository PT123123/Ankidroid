// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.destinations.CsvImporterDestination
import com.ichi2.anki.databinding.PageCsvImporterBinding
import com.ichi2.anki.pages.importer.CsvImporterScreen
import com.ichi2.anki.pages.importer.CsvImporterViewModel
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch

/**
 * The CSV import wizard, rendered natively in Compose. It used to be the backend's SvelteKit
 * "import-csv" route in a WebView; the metadata detection and the import itself now run through
 * the typed backend calls.
 */
class CsvImporter : Fragment(R.layout.page_csv_importer) {
    private val binding by viewBinding(PageCsvImporterBinding::bind)
    private val viewModel by viewModels<CsvImporterViewModel>()

    /**
     * Back is only enabled while the import is running and showing progress; it aborts the
     * import, which cannot be resumed.
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewModel.abort()
                remove()
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setTitle(TR.actionsImport())
        binding.toolbar.setNavigationOnClickListener {
            if (backCallback.isEnabled) backCallback.handleOnBackPressed() else requireActivity().finish()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.composeView.setContent {
            AnkiDroidTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                CsvImporterScreen(state = state, viewModel = viewModel)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { backCallback.isEnabled = it.importing }
        }

        viewModel.load(requireArguments().getString(KEY_FILE_PATH) ?: return)
    }

    companion object {
        private const val KEY_FILE_PATH = "filePath"

        /**
         * @param filePath path of the csv file that will be imported, which should be accessible by AnkiDroid
         * @return an intent to open the [CsvImporter] page on [SingleFragmentActivity]
         */
        fun getIntent(
            context: Context,
            filePath: String,
        ): Intent {
            val arguments = Bundle().apply { putString(KEY_FILE_PATH, filePath) }
            return SingleFragmentActivity.getIntent(context, fragmentClass = CsvImporter::class, arguments)
        }
    }
}

/** Builds the [Intent] that opens the CSV importer for this destination. */
fun CsvImporterDestination.toIntent(context: Context): Intent = CsvImporter.getIntent(context, filePath)
