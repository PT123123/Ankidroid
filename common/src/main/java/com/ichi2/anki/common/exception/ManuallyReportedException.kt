// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.common.exception

/** An exception raised for manual crash reporting  */
class ManuallyReportedException(
    message: String?,
) : RuntimeException(message)
