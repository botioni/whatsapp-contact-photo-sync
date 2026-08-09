package ro.bara.whatsappcontactphotosync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the sync process alive while the app is backgrounded (screen off,
 * user on another app) by holding a foreground-service notification. The
 * WebView driving the actual sync still lives in WebSyncActivity — this
 * service only prevents the OS from freezing/killing that process and
 * shows progress so the user doesn't have to keep the screen open.
 *
 * Note: swiping the app away from Recents still destroys the Activity (and
 * its WebView) — only pressing Home or locking the screen keeps it alive.
 */
class SyncForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification("Se pregătește...", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCallback?.invoke()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun buildNotification(status: String, current: Int, total: Int): Notification {
        val openIntent = Intent(this, WebSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sincronizare poze WhatsApp")
            .setContentText(if (total > 0) "$status ($current/$total)" else status)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Oprește", stopPendingIntent)

        if (total > 0) {
            builder.setProgress(total, current, false)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sincronizare WhatsApp", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wa_sync_progress"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "ro.bara.whatsappcontactphotosync.STOP"

        /** Set by WebSyncActivity so the notification's "Oprește" action can reach it. */
        var stopCallback: (() -> Unit)? = null

        private var instance: SyncForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, status: String, current: Int, total: Int) {
            val svc = instance ?: return
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, svc.buildNotification(status, current, total))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
