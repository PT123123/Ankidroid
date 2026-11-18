// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · Corner Review notification fallback (B25), 2026

package com.ichi2.anki.addons

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * B25 · Android counterpart of the desktop corner popup. Instead of an overlay
 * (which needs a special permission), it raises a periodic "quick review"
 * notification that opens the app. Active-hour gating reuses the same policy
 * concept as the desktop [AddonTools.CornerPolicy].
 */
class CornerReviewReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour >= 22) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "速记复习", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("速记复习")
                .setContentText("花一分钟过几张卡")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ACTION = "com.ichi2.anki.addons.CORNER_REVIEW"
        private const val CHANNEL_ID = "corner_review"
        private const val NOTIFICATION_ID = 2401

        fun schedule(
            context: Context,
            intervalMin: Int,
        ) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val minutes = intervalMin.coerceIn(2, 120).toLong()
            val period = minutes * 60_000L
            alarm.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + period,
                period,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CornerReviewReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
