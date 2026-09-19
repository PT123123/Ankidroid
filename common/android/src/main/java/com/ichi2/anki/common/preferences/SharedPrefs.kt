// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.common.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.ichi2.anki.common.android.ApplicationContextInitializer

/**
 * shorthand method to get the default [SharedPreferences] instance
 *
 * Resolved against the application context so that under multi-account profiles the
 * [com.ichi2.anki.multiprofile.ProfileContextWrapper] namespacing applies regardless of
 * which context (activity/service/receiver) the caller holds.
 */
fun Context.sharedPrefs(): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(ApplicationContextInitializer.instanceOrNull ?: this)
