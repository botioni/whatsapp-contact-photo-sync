package ro.bara.whatsappcontactphotosync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

/**
 * Keeps the sync process alive while the app is backgrounded (screen off,
 * user on another app) by holding a foreground-service notification, and
 * (when the user has granted "draw over other apps") by hosting the actual
 * WebView in a real, invisible system-overlay window whenever the Activity
 * itself isn't visible.
 *
 * This matters because a foreground service alone only stops the OS from
 * killing the process — it does NOT stop Chromium's own page-visibility
 * throttling, which pauses a WebView's JS timers once its hosting Activity
 * window is gone. Moving the WebView into a real (if invisible) window
 * keeps it "visible" from Chromium's point of view, so the automation
 * keeps running at normal speed even with the screen off or another app in
 * front. WebSyncActivity moves the WebView in on onStop() and back out on
 * onStart(); if the overlay permission isn't granted, this is skipped and
 * the sync simply pauses while backgrounded (the old behavior).
 */
class SyncForegroundService : Service() {

    private var lastStatus = "Se pregătește..."
    private var lastCurrent = 0
    private var lastTotal = 0
    private var lastStats = ""

    private var overlayContainer: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCallback?.invoke()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    private fun attachToOverlay(webView: WebView): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (overlayContainer != null) return true
        return try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = resources.displayMetrics
            val params = WindowManager.LayoutParams(
                metrics.widthPixels, metrics.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            val container = FrameLayout(this).apply { alpha = 0f }
            container.addView(
                webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            wm.addView(container, params)
            overlayContainer = container
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun detachFromOverlay(webView: WebView) {
        val container = overlayContainer ?: return
        container.removeView(webView)
        removeOverlay()
    }

    private fun removeOverlay() {
        val container = overlayContainer ?: return
        overlayContainer = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(container) }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
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

        val progressLine = if (lastTotal > 0) "$lastStatus ($lastCurrent/$lastTotal)" else lastStatus
        val fullText = if (lastStats.isNotEmpty()) "$progressLine\n$lastStats" else progressLine

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sincronizare poze WhatsApp")
            .setContentText(progressLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Oprește", stopPendingIntent)

        if (lastTotal > 0) {
            builder.setProgress(lastTotal, lastCurrent, false)
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

        fun updateProgress(status: String, current: Int, total: Int) {
            val svc = instance ?: return
            svc.lastStatus = status
            svc.lastCurrent = current
            svc.lastTotal = total
            svc.refreshNotification()
        }

        fun updateStats(stats: String) {
            val svc = instance ?: return
            svc.lastStats = stats
            svc.refreshNotification()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }

        fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestOverlayPermission(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }

        /** Moves the WebView into a real (invisible) overlay window so it keeps running while the Activity is gone. */
        fun moveToOverlay(webView: WebView): Boolean = instance?.attachToOverlay(webView) ?: false

        /** Moves the WebView back out — the caller is responsible for re-adding it to its own layout. */
        fun moveOutOfOverlay(webView: WebView) {
            instance?.detachFromOverlay(webView)
        }

        fun isInOverlay(): Boolean = instance?.overlayContainer != null
    }
}
