package com.monitor.security

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    private lateinit var server: ApiServer

    companion object {
        val TOKEN: String = (100000..999999).random().toString()

        fun obtenerIP(ctx: Context): String {
            val wm = ctx.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        crearNotificacion()
        server = ApiServer(this, 8080)
        server.start()
        return START_STICKY
    }

    private fun crearNotificacion() {
        val channelId = "monitor_security"
        val channel = NotificationChannel(
            channelId,
            "monitor.security",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("monitor.security activo")
            .setContentText("Servidor corriendo en puerto 8080")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)
            .build()

        startForeground(1, notif)
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
