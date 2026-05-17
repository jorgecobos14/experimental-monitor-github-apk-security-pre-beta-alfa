package com.monitor.security

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File

class MonitorService : Service() {

    private lateinit var server: ApiServer
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        val TOKEN: String = (100000..999999).random().toString()
        var screenResultCode: Int = 0
        var screenResultData: Intent? = null
        var ultimoFrame: ByteArray? = null

        // Texto e ícono personalizables remotamente
        var notifTexto: String = "Servicio activo"
        var notifIconoPath: String? = null

        fun obtenerIP(ctx: Context): String {
            val wm = ctx.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            detener()
            return START_NOT_STICKY
        }
        if (intent?.action == "UPDATE_NOTIF") {
            actualizarNotificacion()
            return START_NOT_STICKY
        }
        crearNotificacion()
        iniciarCapturaPantalla()
        server = ApiServer(this, 8080)
        server.start()
        return START_STICKY
    }

    fun actualizarNotificacion() {
        val notif = construirNotificacion()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, notif)
    }

    private fun construirNotificacion(): Notification {
        val channelId = "monitor_security"

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentText(notifTexto)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)

        // Ícono personalizado si existe
        notifIconoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                builder.setLargeIcon(bitmap)
                // Sin título para minimalismo
                builder.setContentTitle("")
            } else {
                builder.setContentTitle("")
            }
        } ?: builder.setContentTitle("")

        return builder.build()
    }

    private fun crearNotificacion() {
        val channelId = "monitor_security"
        val channel = NotificationChannel(
            channelId, "Sistema", NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, construirNotificacion())
    }

    private fun iniciarCapturaPantalla() {
        val data = screenResultData ?: return
        if (screenResultCode == 0) return

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(screenResultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                ultimoFrame = baos.toByteArray()
                bitmap.recycle()
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "monitor.security", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun detener() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::server.isInitialized) server.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detener()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
