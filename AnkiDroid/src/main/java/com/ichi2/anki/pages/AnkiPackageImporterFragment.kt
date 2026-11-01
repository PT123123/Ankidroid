// SPDX-License-Identifier: GPL-3.0-or-later

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
import com.ichi2.anki.databinding.PageAnkiPackageImporterBinding
import com.ichi2.anki.pages.importer.AnkiPackageImporterScreen
import com.ichi2.anki.pages.importer.AnkiPackageImporterViewModel
import com.ichi2.compose.theme.AnkiDroidTheme
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch

/**
 * The .apkg import screen, rendered natively in Compose. It used to be the backend's SvelteKit
 * "import-anki-package" route in a WebView; the import now runs through the typed backend call
 * with its progress fed into the Compose state.
 */
class AnkiPackageImporterFragment : Fragment(R.layout.page_anki_package_importer) {
    private val binding by viewBinding(PageAnkiPackageImporterBinding::bind)
    private val viewModel by viewModels<AnkiPackageImporterViewModel>()

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
                AnkiPackageImporterScreen(state = state)
            }
        }

        // enable abort while the import is running
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { backCallback.isEnabled = it.importing }
        }

        viewModel.import(requireArguments().getString(KEY_FILE_PATH) ?: return)
    }

    companion object {
        private const val KEY_FILE_PATH = "filePath"

        fun getIntent(
            context: Context,
            filePath: String,
        ): Intent {
            val arguments = Bundle().apply { putString(KEY_FILE_PATH, filePath) }
            return SingleFragmentActivity.getIntent(context, AnkiPackageImporterFragment::class, arguments)
        }
    }
}
