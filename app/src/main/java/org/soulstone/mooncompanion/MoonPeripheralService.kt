package org.soulstone.mooncompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that owns the peripheral lifetime. The UI lives in an
 * Activity; the Activity starts us, we keep advertising + handling GATT
 * writes even if the Activity is backgrounded or the screen turns off.
 *
 * Phase 1a scope: own a MoonGattServer, own a RequestHandler, pump a
 * position loop on the main handler while a subscription is live. No
 * proper lifecycle teardown on user swipe yet — we just stop when the
 * Activity tells us to.
 */
class MoonPeripheralService : Service() {

    private val state = PhoneState()
    private val handler = RequestHandler(state)
    private lateinit var gatt: MoonGattServer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionTicker = object : Runnable {
        override fun run() {
            if (state.positionSubscribed && state.paired && gatt.hasSubscribers()) {
                gatt.notifySubscribers(handler.buildPositionEvent())
            }
            mainHandler.postDelayed(this, state.positionIntervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Starting..."))

        gatt = MoonGattServer(this) { payload -> handler.onRpcTxWrite(payload) }
        gatt.onStateChange = { status ->
            updateForegroundNotification(status)
            broadcastStatus(status)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> gatt.start()
            ACTION_STOP  -> {
                gatt.stop()
                stopSelf()
            }
        }
        // Kick the ticker. It self-guards on subscription state, so keeping
        // it posted unconditionally is cheap and means SubscribePosition
        // doesn't need to fight the handler.
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(positionTicker)
        gatt.stop()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Moon Companion",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Moon Companion")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateForegroundNotification(status: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildForegroundNotification(status))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    companion object {
        private const val TAG = "MoonPeripheral"
        private const val CHANNEL_ID = "moon_companion"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "org.soulstone.mooncompanion.START"
        const val ACTION_STOP  = "org.soulstone.mooncompanion.STOP"
        const val BROADCAST_STATUS = "org.soulstone.mooncompanion.STATUS"
        const val EXTRA_STATUS = "status"

        fun start(ctx: Context) {
            val i = Intent(ctx, MoonPeripheralService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MoonPeripheralService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
