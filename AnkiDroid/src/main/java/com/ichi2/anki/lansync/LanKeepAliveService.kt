// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ichi2.anki.R
import timber.log.Timber

/**
 * The explicit "keep online" opt-in (SPEC-v2 §8): without it the feature keeps v1's deliberate
 * behaviour of closing every port the moment the LAN screen leaves the foreground. Enabling it
 * starts this foreground service so the server, discovery and scheduler survive a backgrounded
 * app - and the standing notification says so, because a permanently open port should never be
 * something the user forgets about. OEM power managers may still kill the listener; the screen
 * warns about that too.
 */
class LanKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(getString(R.string.lansync_keep_online))
                .setContentText(getString(R.string.lansync_keep_online_running))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        LanSyncManager.onKeepAliveStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        LanSyncManager.onKeepAliveStopped()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.lansync_keep_online), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.ichi2.anki.lansync.action.STOP_KEEP_ONLINE"
        private const val CHANNEL_ID = "lansync_keep_online"
        private const val NOTIFICATION_ID = 1302

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LanKeepAliveService::class.java))
            }.onFailure { Timber.w(it, "could not start LAN keep-alive service") }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LanKeepAliveService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
