package live.hanab.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the process (and so the websocket) running for a while after the app leaves the screen;
 * Android otherwise freezes it within seconds. It stops itself after [KEEP_ALIVE_MS] and tells
 * [onExpired] so the connection can be closed on purpose.
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        onExpired?.invoke()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Staying connected", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hanab Live")
            .setContentText("Staying connected for a few minutes")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, KEEP_ALIVE_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        super.onDestroy()
    }

    companion object {
        /** How long the connection stays open once the app is out of sight. */
        const val KEEP_ALIVE_MS = 10 * 60 * 1000L
        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 1

        /** Called on the main thread when the time is up. */
        var onExpired: (() -> Unit)? = null

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (_: Exception) {
                // Not allowed to start right now: the connection just behaves as it did before.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
