// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import anki.import_export.ImportResponse
import com.ichi2.anki.CollectionManager.TR

/** The summary of an [ImportResponse]: how many notes were added, updated or skipped. */
@Composable
fun ImportLogView(
    result: ImportResponse?,
    modifier: Modifier = Modifier,
) {
    if (result == null) {
        CircularProgressIndicator()
        return
    }
    val log = result.log
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            TR.importingImportingComplete(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (log.foundNotes == 0) {
            Text(
                TR.importingNoNotesInFile(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        val skipped =
            log.duplicateCount + log.conflictingCount + log.firstFieldMatchCount +
                log.missingNotetypeCount + log.missingDeckCount + log.emptyFirstFieldCount
        LogLine(TR.importingNotesAdded(log.newCount), log.newCount)
        LogLine(TR.importingNotesUpdated(log.updatedCount), log.updatedCount)
        if (skipped > 0) {
            LogLine(TR.importingExistingNotesSkipped(skipped), skipped)
        }
    }
}

@Composable
private fun LogLine(
    label: String,
    count: Int,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

val ImportResponse.Log.newCount: Int get() = newList.size
val ImportResponse.Log.updatedCount: Int get() = updatedList.size
val ImportResponse.Log.duplicateCount: Int get() = duplicateList.size
val ImportResponse.Log.conflictingCount: Int get() = conflictingList.size
val ImportResponse.Log.firstFieldMatchCount: Int get() = firstFieldMatchList.size
val ImportResponse.Log.missingNotetypeCount: Int get() = missingNotetypeList.size
val ImportResponse.Log.missingDeckCount: Int get() = missingDeckList.size
val ImportResponse.Log.emptyFirstFieldCount: Int get() = emptyFirstFieldList.size
