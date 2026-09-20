package io.debi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: mantiene vivo el proceso Debian cuando la app pasa a
 * segundo plano (con una notificación persistente y un botón para detenerlo).
 */
class TermProcessService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // Locks solo si hay secciones activas (ahorro de batería); el servicio sigue vivo.
        applyWake(Term.running)
        return START_STICKY
    }

    /** Coge/suelta los locks según haga falta (ahorra batería sin secciones activas). */
    fun applyWake(on: Boolean) {
        // Wake lock: mantiene la CPU despierta (las sesiones siguen procesando en 2º plano).
        try {
            if (on) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "debi:term")
                }
                if (wakeLock?.isHeld != true) wakeLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (_: Throwable) { }
        // Wifi lock: mantiene la red activa en segundo plano (descargas, ssh, bots…).
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (on) {
                if (wifiLock == null) {
                    wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "debi:wifi")
                }
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        instance = null
        try { wakeLock?.release() } catch (_: Throwable) { }
        try { wifiLock?.release() } catch (_: Throwable) { }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    /**
     * El usuario quitó la app de "recientes": NO paramos. Las sesiones siguen vivas
     * en segundo plano. Reforzamos el foreground para que el sistema no nos degrade
     * el oom_adj (es lo que evita que Android mate el proceso).
     */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Throwable) { }
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                val ch = NotificationChannel(CH_ID, "Terminal Debian", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Mantiene Debian ejecutándose en segundo plano"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_ID) else Notification.Builder(this)
        return b
            .setContentTitle("Debian en ejecución")
            .setContentText("Toca para abrir el terminal")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .build()
    }

    companion object {
        const val CH_ID = "debi_term"
        const val NOTIF_ID = 1

        @Volatile private var instance: TermProcessService? = null

        /** Activa/desactiva los locks del proceso (lo llama Term al cambiar sesiones). */
        fun setWake(on: Boolean) { instance?.applyWake(on) }

        fun start(ctx: Context) {
            val i = Intent(ctx, TermProcessService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TermProcessService::class.java))
        }
    }
}

/** Botón "Detener" de la notificación. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Term.stopAll()
        TermProcessService.stop(context)
    }
}
