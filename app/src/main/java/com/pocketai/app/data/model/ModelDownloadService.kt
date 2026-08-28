package com.pocketai.app.data.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.pocketai.app.MainActivity
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and the user informed) while a multi-gigabyte model
 * download is in flight. The service owns no download logic of its own - it
 * mirrors [ModelDownloadManager]'s state into a progress notification.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Preparing download", null, 0, 0, true))

        val manager = (application as? PocketAiApplication)?.container?.downloadManager
        if (manager == null) {
            stopSelf()
            return
        }
        scope.launch {
            manager.state.collectLatest { state ->
                when (state.status) {
                    DownloadStatus.CONNECTING -> notify(
                        buildNotification("Connecting", state.displayName, 0, 0, true)
                    )
                    DownloadStatus.RUNNING -> notify(
                        buildNotification(
                            title = state.displayName,
                            text = "${ModelRepository.formatBytes(state.downloadedBytes)} of " +
                                "${ModelRepository.formatBytes(state.totalBytes)}",
                            progress = (state.progress * 100).toInt(),
                            max = 100,
                            indeterminate = state.totalBytes <= 0
                        )
                    )
                    else -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun notify(notification: Notification) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String?,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Downloading model" })
            .setSmallIcon(R.drawable.ic_stat_pocketai)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (text != null) builder.setContentText(text)
        if (max > 0 || indeterminate) builder.setProgress(max, progress, indeterminate)
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while PocketAI downloads a local AI model."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "pocketai_downloads"
        private const val NOTIFICATION_ID = 4711
    }
}
