package com.aydtv.aydtv.cast

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodChannel
import java.io.*
import java.net.*
import java.util.concurrent.Executors

class CastServerManager(
    private val context: Context,
    private val channel: MethodChannel
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var airplayServerSocket: ServerSocket? = null
    private var webRemoteServerSocket: ServerSocket? = null

    private var isRunning = false
    private var deviceName = "AydTV"
    private var pinEnabled = false
    private var activeClientSocket: Socket? = null

    private var channelsJson = "[]"
    private var statusJson = "{\"channel\":\"AydTV\",\"sleep\":0,\"isPlaying\":true}"

    companion object {
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
        const val WEB_REMOTE_PORT = 8088
    }

    fun updateChannels(json: String) {
        channelsJson = json
    }

    fun updateStatus(json: String) {
        statusJson = json
    }

    fun start(customName: String = "AydTV", isPinRequired: Boolean = false) {
        if (isRunning) return
        isRunning = true
        deviceName = customName
        pinEnabled = isPinRequired

        acquireMulticastLock()
        startAirplayServer()
        startWebRemoteServer()
        registerMdnsServices()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        unregisterMdnsServices()
        releaseMulticastLock()

        try {
            activeClientSocket?.close()
            airplayServerSocket?.close()
            webRemoteServerSocket?.close()
        } catch (_: Exception) {}

        activeClientSocket = null
        airplayServerSocket = null
        webRemoteServerSocket = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("AydTV_MulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.name.contains("wlan") || nif.name.contains("eth")) {
                    val mac = nif.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        return mac.joinToString(":") { String.format("%02X", it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return "00:11:22:33:44:55"
    }

    private fun registerMdnsServices() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val mac = getMacAddress()
            val macNoColon = mac.replace(":", "")

            // 1. Standart AirPlay Servisi (Tüm iOS cihazlarıyla uyumlu Ekran Yansıtma, HLS Video & Fotoğraf)
            val airplayService = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_airplay._tcp"
                port = AIRPLAY_PORT
                setAttribute("deviceid", mac)
                setAttribute("features", "0x5A7FFFF7,0x1E") // Ekran Yansıtma (Mirroring) + Video + Photo + Slideshow
                setAttribute("model", "AppleTV3,1")
                setAttribute("srcvers", "220.68")
                setAttribute("protovers", "1.0")
                setAttribute("flags", "0x4")
                setAttribute("vv", "2")
            }

            nsdManager?.registerService(airplayService, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            })

            // 2. RAOP (AirPlay Müzik / Ses) Servisi
            val raopService = NsdServiceInfo().apply {
                serviceName = "${macNoColon}@$deviceName"
                serviceType = "_raop._tcp"
                port = RAOP_PORT
                setAttribute("ch", "2")
                setAttribute("cn", "0,1,2,3")
                setAttribute("et", "0,1")
                setAttribute("sr", "44100")
                setAttribute("ss", "16")
                setAttribute("tp", "TCP,UDP")
                setAttribute("vn", "65537")
            }

            nsdManager?.registerService(raopService, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            })
        } catch (_: Exception) {}
    }

    private fun unregisterMdnsServices() {}

    private fun startAirplayServer() {
        executor.execute {
            try {
                airplayServerSocket = ServerSocket(AIRPLAY_PORT).apply {
                    reuseAddress = true
                }
                while (isRunning && airplayServerSocket?.isClosed == false) {
                    val client = airplayServerSocket?.accept() ?: break
                    executor.execute { handleAirplayClient(client) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleAirplayClient(socket: Socket) {
        activeClientSocket = socket
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            var deviceDetected = false
            var clientName = "Apple Cihazı"

            while (isRunning && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isBlank()) continue

                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0].uppercase() else "GET"
                val path = if (parts.size > 1) parts[1] else "/"
                val isRtsp = requestLine.contains("RTSP") || parts.getOrNull(2)?.contains("RTSP") == true

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        val key = headerParts[0].trim().lowercase()
                        val value = headerParts[1].trim()
                        headers[key] = value
                        if (key == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                        if (key == "user-agent") {
                            val ua = value
                            if (ua.contains("iPhone")) clientName = "iPhone"
                            else if (ua.contains("iPad")) clientName = "iPad"
                            else if (ua.contains("Mac")) clientName = "Mac"
                        }
                        if (key == "x-apple-device-model") {
                            clientName = value
                        }
                    }
                    line = reader.readLine()
                }

                var body = ""
                if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var readSoFar = 0
                    while (readSoFar < contentLength) {
                        val read = reader.read(charArray, readSoFar, contentLength - readSoFar)
                        if (read == -1) break
                        readSoFar += read
                    }
                    body = String(charArray, 0, readSoFar)
                }

                if (!deviceDetected) {
                    deviceDetected = true
                    notifyDeviceConnected(clientName, "airplay")
                }

                val cseq = headers["cseq"] ?: "0"

                if (isRtsp) {
                    handleRtspRequest(method, path, headers, body, output, cseq, clientName)
                } else {
                    handleAirplayHttpRequest(method, path, headers, body, output, cseq, clientName)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            notifyDeviceDisconnected()
        }
    }

    private fun handleRtspRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream,
        cseq: String,
        clientName: String
    ) {
        when (method) {
            "OPTIONS" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n" +
                        "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET, PUT\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            "ANNOUNCE" -> {
                notifyDeviceConnected(clientName, "airplay")
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            "SETUP" -> {
                // iPhone Ekran Yansıtma akışı başladı!
                notifyMediaStarted("screen", "", "Ekran Yansıtma", clientName, deviceName)
                val transport = headers["transport"] ?: "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n" +
                        "Transport: $transport\r\n" +
                        "Session: 1\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            "RECORD" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n" +
                        "Session: 1\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            "GET_PARAMETER", "SET_PARAMETER" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            "TEARDOWN" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
                notifyMediaStopped()
                notifyDeviceDisconnected()
            }
            "POST" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: 1\r\n\r\n\u0001"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            else -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n\r\n"
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun handleAirplayHttpRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream,
        cseq: String,
        clientName: String
    ) {
        when {
            path.startsWith("/server-info") || path.startsWith("/info") -> {
                val mac = getMacAddress()
                val xmlInfo = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key><string>$mac</string>
    <key>features</key><integer>14839</integer>
    <key>model</key><string>AppleTV3,1</string>
    <key>protovers</key><string>1.0</string>
    <key>srcvers</key><string>220.68</string>
    <key>statusFlags</key><integer>4</integer>
</dict>
</plist>"""
                sendHttpResponse(output, 200, "OK", "text/x-apple-plist+xml", xmlInfo, cseq)
            }
            path.startsWith("/play") -> {
                var streamUrl = headers["content-location"] ?: ""
                if (streamUrl.isBlank()) {
                    if (body.contains("<key>Content-Location</key>")) {
                        val match = Regex("<string>(.*?)</string>").find(body)
                        if (match != null) streamUrl = match.groupValues[1]
                    } else if (body.contains("Content-Location:")) {
                        val match = Regex("Content-Location:\\s*(.*?)\r?\n").find(body)
                        if (match != null) streamUrl = match.groupValues[1].trim()
                    }
                }
                var mediaType = "video"
                if (streamUrl.contains(".mp3") || streamUrl.contains(".m4a") || streamUrl.contains(".flac") || streamUrl.contains(".aac")) {
                    mediaType = "audio"
                }
                notifyMediaStarted(mediaType, streamUrl, "AirPlay Medyası", clientName, deviceName)
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
            path.startsWith("/photo") -> {
                try {
                    val photoFile = File(context.cacheDir, "airplay_photo.jpg")
                    photoFile.writeBytes(body.toByteArray(Charsets.ISO_8859_1))
                    notifyMediaStarted("photo", photoFile.absolutePath, "AirPlay Fotoğrafı", clientName, deviceName)
                } catch (_: Exception) {}
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
            path.startsWith("/rate") -> {
                val value = Regex("value=([0-9.]+)").find(path)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
                if (value == 0.0) notifyMediaPaused() else notifyMediaResumed()
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
            path.startsWith("/scrub") -> {
                if (method == "GET") {
                    sendHttpResponse(output, 200, "OK", "text/plain", "duration: 100.0\nposition: 0.0\n", cseq)
                } else {
                    val pos = Regex("position=([0-9.]+)").find(path)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    notifyPositionChanged(pos, 100.0)
                    sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
                }
            }
            path.startsWith("/stop") -> {
                notifyMediaStopped()
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
            path.startsWith("/pair-setup") || path.startsWith("/pair-verify") -> {
                sendHttpResponse(output, 200, "OK", "application/octet-stream", "\u0001", cseq)
            }
            else -> {
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
        }
    }

    private fun sendHttpResponse(
        output: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: String,
        cseq: String? = null
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        var response = "HTTP/1.1 $code $status\r\n" +
                "Server: AirTunes/220.68\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT\r\n" +
                "Access-Control-Allow-Headers: *\r\n"
        if (!cseq.isNullOrBlank()) {
            response += "CSeq: $cseq\r\n"
        }
        response += "Connection: keep-alive\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    // ----------------------------------------------------
    // TELEFONDAN AKILLI KUMANDA & WEB REMOTE (Port 8088)
    // ----------------------------------------------------
    private fun startWebRemoteServer() {
        executor.execute {
            try {
                webRemoteServerSocket = ServerSocket(WEB_REMOTE_PORT).apply {
                    reuseAddress = true
                }
                while (isRunning && webRemoteServerSocket?.isClosed == false) {
                    val client = webRemoteServerSocket?.accept() ?: break
                    executor.execute { handleWebRemoteClient(client) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleWebRemoteClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = if (parts.isNotEmpty()) parts[0] else "GET"
            val path = if (parts.size > 1) parts[1] else "/"

            // Header'ları oku
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                line = reader.readLine()
            }

            if (method == "OPTIONS") {
                sendHttpResponse(output, 200, "OK", "text/plain", "OK")
                return
            }

            when {
                path.startsWith("/api/channels") -> {
                    sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", channelsJson)
                }
                path.startsWith("/api/status") -> {
                    sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", statusJson)
                }
                path.startsWith("/api/key") -> {
                    val key = Regex("key=([a-zA-Z0-9_]+)").find(path)?.groupValues?.get(1) ?: "ok"
                    when (key) {
                        "vol_up" -> {
                            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        }
                        "vol_down" -> {
                            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        }
                        "mute" -> {
                            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                        }
                        "home" -> {
                            val handled = com.aydtv.aydtv.TvButtonAccessibilityService.performHome()
                            if (!handled) {
                                try {
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(homeIntent)
                                } catch (_: Exception) {}
                            }
                        }
                        "power" -> {
                            com.aydtv.aydtv.TvButtonAccessibilityService.performPowerDialog()
                        }
                        else -> {
                            mainHandler.post {
                                channel.invokeMethod("onRemoteCommand", mapOf("action" to key))
                            }
                        }
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/channel") -> {
                    val num = Regex("num=([0-9]+)").find(path)?.groupValues?.get(1) ?: "1"
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "channel_num", "value" to num))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/select_channel") -> {
                    val rawId = Regex("id=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                    val id = try { URLDecoder.decode(rawId, "UTF-8") } catch (_: Exception) { rawId }
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "select_channel", "value" to id))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/toggle_favorite") -> {
                    val rawId = Regex("id=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                    val id = try { URLDecoder.decode(rawId, "UTF-8") } catch (_: Exception) { rawId }
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "toggle_favorite", "value" to id))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/sleep") -> {
                    val minutes = Regex("minutes=([0-9]+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "sleep_timer", "value" to minutes))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/text") -> {
                    val rawText = Regex("text=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                    val text = try { URLDecoder.decode(rawText, "UTF-8") } catch (_: Exception) { rawText }
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "text_input", "value" to text))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                else -> {
                    val html = getWebRemoteHtml()
                    sendHttpResponse(output, 200, "OK", "text/html; charset=utf-8", html)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getWebRemoteHtml(): String {
        return """<!DOCTYPE html>
<html lang="tr">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <title>AydTV Akıllı Kumanda</title>
  <style>
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; outline: none; margin: 0; padding: 0; }
    body {
      background: #080D1A; color: #FFFFFF; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      min-height: 100vh; display: flex; flex-direction: column; align-items: center; padding-bottom: 75px; user-select: none;
    }
    /* Üst Header */
    .top-bar {
      width: 100%; max-width: 420px; padding: 14px 18px; display: flex; justify-content: space-between; align-items: center;
      background: rgba(13, 22, 38, 0.85); backdrop-filter: blur(15px); border-bottom: 1px solid rgba(0, 242, 254, 0.2);
      position: sticky; top: 0; z-index: 100;
    }
    .brand { display: flex; align-items: center; gap: 8px; font-weight: 800; font-size: 17px; color: #00F2FE; }
    .status-badge {
      display: flex; align-items: center; gap: 6px; font-size: 12px; background: rgba(0, 255, 136, 0.12);
      border: 1px solid rgba(0, 255, 136, 0.3); padding: 4px 10px; border-radius: 20px; color: #00FF88;
    }
    .dot { width: 7px; height: 7px; border-radius: 50%; background: #00FF88; box-shadow: 0 0 8px #00FF88; animation: pulse 2s infinite; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

    /* Ana Konteyner */
    .container { width: 100%; max-width: 420px; padding: 16px; display: flex; flex-direction: column; align-items: center; gap: 14px; }
    .tab-content { width: 100%; display: none; flex-direction: column; align-items: center; gap: 14px; }
    .tab-content.active { display: flex; }

    /* Kumanda Gövdesi */
    .remote-body {
      width: 100%; max-width: 350px; background: linear-gradient(165deg, #121A29 0%, #0A101D 100%);
      border: 1px solid rgba(0, 242, 254, 0.25); border-radius: 36px; padding: 22px 18px;
      box-shadow: 0 16px 40px rgba(0,0,0,0.6), inset 0 1px 1px rgba(255,255,255,0.1);
      display: flex; flex-direction: column; align-items: center; gap: 18px;
    }

    /* Üst Sistem Tuşları */
    .btn-row { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
    .sys-btn {
      flex: 1; height: 46px; border-radius: 14px; background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.12); color: #FFF; font-size: 13px; font-weight: 600;
      display: flex; align-items: center; justify-content: center; gap: 6px; cursor: pointer; transition: all 0.15s;
    }
    .sys-btn:active { transform: scale(0.93); background: rgba(0,242,254,0.25); border-color: #00F2FE; }
    .sys-btn.danger { background: rgba(255, 68, 68, 0.15); border-color: rgba(255, 68, 68, 0.4); color: #FF6666; }
    .sys-btn.danger:active { background: #FF4444; color: #FFF; }

    /* D-Pad Navigasyon */
    .dpad-wrapper {
      position: relative; width: 220px; height: 220px; border-radius: 50%;
      background: radial-gradient(circle, #1A2438 0%, #0D1424 100%);
      border: 2px solid rgba(0, 242, 254, 0.25); box-shadow: 0 8px 24px rgba(0,0,0,0.4);
    }
    .dpad-btn {
      position: absolute; color: #A0C4E8; font-size: 18px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; transition: all 0.1s;
    }
    .dpad-btn:active { color: #00F2FE; text-shadow: 0 0 10px #00F2FE; }
    .dpad-up { top: 6px; left: 75px; width: 70px; height: 50px; }
    .dpad-down { bottom: 6px; left: 75px; width: 70px; height: 50px; }
    .dpad-left { top: 75px; left: 6px; width: 50px; height: 70px; }
    .dpad-right { top: 75px; right: 6px; width: 50px; height: 70px; }
    .dpad-ok {
      position: absolute; top: 68px; left: 68px; width: 84px; height: 84px; border-radius: 50%;
      background: linear-gradient(135deg, #00F2FE 0%, #0088FF 100%); color: #070B14;
      font-weight: 800; font-size: 19px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; box-shadow: 0 0 20px rgba(0, 242, 254, 0.45); transition: all 0.15s;
    }
    .dpad-ok:active { transform: scale(0.92); box-shadow: 0 0 30px rgba(0, 242, 254, 0.8); }

    /* Ses ve Kanal Rocker Tuşları */
    .rocker-section { width: 100%; display: flex; justify-content: space-between; align-items: center; padding: 0 8px; }
    .rocker-pill {
      width: 64px; height: 120px; border-radius: 32px; background: rgba(255,255,255,0.05);
      border: 1px solid rgba(0, 242, 254, 0.2); display: flex; flex-direction: column; overflow: hidden;
    }
    .rocker-btn {
      flex: 1; display: flex; align-items: center; justify-content: center; color: #FFF; font-weight: bold; font-size: 14px;
      cursor: pointer; transition: background 0.1s;
    }
    .rocker-btn:active { background: rgba(0, 242, 254, 0.3); color: #00F2FE; }
    .rocker-label { font-size: 10px; color: #7A8EA8; text-align: center; line-height: 1; }

    .center-actions { display: flex; flex-direction: column; gap: 12px; }
    .round-btn {
      width: 52px; height: 52px; border-radius: 50%; background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.15); display: flex; align-items: center; justify-content: center;
      font-size: 17px; cursor: pointer; transition: all 0.15s;
    }
    .round-btn:active { transform: scale(0.9); background: #00F2FE; color: #000; }

    /* Metin Girişi / Klavye Kutusu */
    .keyboard-box {
      width: 100%; background: rgba(255,255,255,0.03); border: 1px solid rgba(0,242,254,0.2);
      border-radius: 16px; padding: 10px; display: flex; gap: 8px; align-items: center;
    }
    .keyboard-input {
      flex: 1; background: transparent; border: none; color: #FFF; font-size: 14px; padding: 6px;
    }
    .keyboard-input::placeholder { color: #5A7090; }
    .send-text-btn {
      padding: 8px 14px; border-radius: 10px; background: #00F2FE; color: #070B14;
      font-weight: 700; font-size: 12px; border: none; cursor: pointer;
    }
    .send-text-btn:active { transform: scale(0.94); }

    /* Numpad Izgarası */
    .numpad-container { width: 100%; display: none; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 6px; }
    .num-key {
      padding: 12px; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1);
      border-radius: 12px; font-size: 16px; font-weight: bold; text-align: center; cursor: pointer;
    }
    .num-key:active { background: #00F2FE; color: #000; }

    /* Kanallar Sekmesi */
    .search-box {
      width: 100%; background: rgba(255,255,255,0.06); border: 1px solid rgba(0,242,254,0.3);
      border-radius: 14px; padding: 12px 14px; display: flex; align-items: center; gap: 10px;
    }
    .search-input {
      flex: 1; background: transparent; border: none; color: #FFF; font-size: 15px;
    }
    .channel-list { width: 100%; display: flex; flex-direction: column; gap: 8px; }
    .channel-item {
      display: flex; align-items: center; justify-content: space-between; padding: 12px 14px;
      background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
      border-radius: 14px; cursor: pointer; transition: all 0.15s;
    }
    .channel-item:active { background: rgba(0, 242, 254, 0.15); border-color: #00F2FE; }
    .ch-left { display: flex; align-items: center; gap: 12px; }
    .ch-num { font-size: 13px; font-weight: 700; color: #00F2FE; width: 28px; }
    .ch-name { font-size: 15px; font-weight: 600; }
    .ch-fav { font-size: 18px; color: #556677; padding: 4px; cursor: pointer; }
    .ch-fav.active { color: #FFB300; text-shadow: 0 0 10px rgba(255,179,0,0.6); }

    /* Uyku Zamanlayıcı Sekmesi */
    .card-settings {
      width: 100%; background: linear-gradient(165deg, #121A29 0%, #0A101D 100%);
      border: 1px solid rgba(0, 242, 254, 0.25); border-radius: 24px; padding: 20px;
      display: flex; flex-direction: column; gap: 14px;
    }
    .card-title { font-size: 16px; font-weight: 700; color: #00F2FE; display: flex; align-items: center; gap: 8px; }
    .timer-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
    .timer-btn {
      padding: 14px; border-radius: 12px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.12);
      color: #FFF; font-size: 14px; font-weight: 600; text-align: center; cursor: pointer;
    }
    .timer-btn:active { background: #00F2FE; color: #000; }
    .timer-btn.cancel { grid-column: span 2; background: rgba(255,68,68,0.15); border-color: rgba(255,68,68,0.4); color: #FF6666; }

    /* Alt Sabit Navigasyon Çubuğu */
    .bottom-nav {
      position: fixed; bottom: 0; width: 100%; max-width: 420px; height: 65px;
      background: rgba(10, 16, 28, 0.95); backdrop-filter: blur(20px);
      border-top: 1px solid rgba(0, 242, 254, 0.2); display: flex; justify-content: space-around; align-items: center; z-index: 100;
    }
    .nav-item {
      display: flex; flex-direction: column; align-items: center; gap: 4px; color: #7A8EA8;
      font-size: 11px; font-weight: 600; cursor: pointer; transition: color 0.15s;
    }
    .nav-item.active { color: #00F2FE; }
    .nav-icon { font-size: 20px; }

    /* Bildirim Toast */
    .toast {
      position: fixed; top: 70px; background: #00F2FE; color: #070B14; font-weight: 700;
      font-size: 13px; padding: 10px 18px; border-radius: 20px; box-shadow: 0 4px 20px rgba(0,242,254,0.5);
      z-index: 200; opacity: 0; transform: translateY(-10px); transition: all 0.2s; pointer-events: none;
    }
    .toast.show { opacity: 1; transform: translateY(0); }
  </style>
</head>
<body>
  <div class="top-bar">
    <div class="brand">📺 AydTV Kumanda</div>
    <div class="status-badge"><span class="dot"></span> TV Bağlı</div>
  </div>

  <div id="toast" class="toast">Komut gönderildi</div>

  <div class="container">
    <!-- 1. SEKME: KUMANDA -->
    <div id="tab-remote" class="tab-content active">
      <div class="remote-body">
        <div class="btn-row">
          <div class="sys-btn danger" onclick="sendKey('power')">🔴 Güç</div>
          <div class="sys-btn" onclick="sendKey('home')">🏠 Home</div>
          <div class="sys-btn" onclick="sendKey('mute')">🔇 Mute</div>
          <div class="sys-btn" onclick="sendKey('settings')">⚙️ Ayarlar</div>
        </div>

        <!-- D-Pad -->
        <div class="dpad-wrapper">
          <div class="dpad-btn dpad-up" onclick="sendKey('up')">▲</div>
          <div class="dpad-btn dpad-down" onclick="sendKey('down')">▼</div>
          <div class="dpad-btn dpad-left" onclick="sendKey('left')">◀</div>
          <div class="dpad-btn dpad-right" onclick="sendKey('right')">▶</div>
          <div class="dpad-ok" onclick="sendKey('ok')">OK</div>
        </div>

        <!-- Ses & Kanal Rocker Tuşları -->
        <div class="rocker-section">
          <!-- Ses Rocker -->
          <div class="rocker-pill">
            <div class="rocker-btn" onclick="sendKey('vol_up')">+</div>
            <div class="rocker-label">SES</div>
            <div class="rocker-btn" onclick="sendKey('vol_down')">-</div>
          </div>

          <!-- Orta Eylemler -->
          <div class="center-actions">
            <div class="round-btn" onclick="sendKey('back')">↩</div>
            <div class="round-btn" onclick="sendKey('play_pause')">⏯️</div>
          </div>

          <!-- Kanal Rocker -->
          <div class="rocker-pill">
            <div class="rocker-btn" onclick="sendKey('channel_up')">▲</div>
            <div class="rocker-label">KANAL</div>
            <div class="rocker-btn" onclick="sendKey('channel_down')">▼</div>
          </div>
        </div>

        <!-- TV'ye Metin / Klavye Gönderme -->
        <div class="keyboard-box">
          <input type="text" id="tvTextInput" class="keyboard-input" placeholder="⌨️ TV'ye metin yaz..." onkeydown="if(event.key==='Enter') sendText()">
          <button class="send-text-btn" onclick="sendText()">Gönder</button>
        </div>

        <!-- Numpad Toggle & Grid -->
        <div class="btn-row" style="margin-top:-6px;">
          <div class="sys-btn" onclick="toggleNumpad()">🔢 Sayı Tuş Takımı</div>
        </div>

        <div id="numpad" class="numpad-container">
          <div class="num-key" onclick="sendNum(1)">1</div>
          <div class="num-key" onclick="sendNum(2)">2</div>
          <div class="num-key" onclick="sendNum(3)">3</div>
          <div class="num-key" onclick="sendNum(4)">4</div>
          <div class="num-key" onclick="sendNum(5)">5</div>
          <div class="num-key" onclick="sendNum(6)">6</div>
          <div class="num-key" onclick="sendNum(7)">7</div>
          <div class="num-key" onclick="sendNum(8)">8</div>
          <div class="num-key" onclick="sendNum(9)">9</div>
          <div></div>
          <div class="num-key" onclick="sendNum(0)">0</div>
          <div></div>
        </div>
      </div>
    </div>

    <!-- 2. SEKME: KANALLAR -->
    <div id="tab-channels" class="tab-content">
      <div class="search-box">
        <input type="text" id="channelSearch" class="search-input" placeholder="🔍 Kanal ara..." oninput="filterChannels()">
      </div>
      <div id="channelList" class="channel-list">
        <div style="text-align:center; color:#7A8EA8; padding:30px;">Kanallar yükleniyor...</div>
      </div>
    </div>

    <!-- 3. SEKME: UYKU & AYARLAR -->
    <div id="tab-settings" class="tab-content">
      <div class="card-settings">
        <div class="card-title">⏱️ Uyku Zamanlayıcı</div>
        <p style="font-size:13px; color:#A0C4E8;">Seçtiğiniz süre dolduğunda TV yayını ve sesi otomatik kapanır.</p>
        <div class="timer-grid">
          <div class="timer-btn" onclick="setSleep(15)">15 Dakika</div>
          <div class="timer-btn" onclick="setSleep(30)">30 Dakika</div>
          <div class="timer-btn" onclick="setSleep(45)">45 Dakika</div>
          <div class="timer-btn" onclick="setSleep(60)">60 Dakika</div>
          <div class="timer-btn" onclick="setSleep(90)">90 Dakika</div>
          <div class="timer-btn" onclick="setSleep(120)">120 Dakika</div>
          <div class="timer-btn cancel" onclick="setSleep(0)">🛑 Zamanlayıcıyı Kapat</div>
        </div>
      </div>

      <div class="card-settings">
        <div class="card-title">⚡ Hızlı TV Kontrolleri</div>
        <div class="btn-row">
          <div class="sys-btn" onclick="sendKey('reload')">🔄 Yayını Yenile</div>
          <div class="sys-btn" onclick="sendKey('settings')">⚙️ Menüyü Aç</div>
        </div>
      </div>
    </div>
  </div>

  <!-- Alt Menü -->
  <div class="bottom-nav">
    <div class="nav-item active" onclick="switchTab('remote', this)">
      <div class="nav-icon">🎮</div>
      <div>Kumanda</div>
    </div>
    <div class="nav-item" onclick="switchTab('channels', this)">
      <div class="nav-icon">📺</div>
      <div>Kanallar</div>
    </div>
    <div class="nav-item" onclick="switchTab('settings', this)">
      <div class="nav-icon">⏱️</div>
      <div>Uyku</div>
    </div>
  </div>

  <script>
    var channelsData = [];

    function vibrate() {
      if (window.navigator && window.navigator.vibrate) {
        window.navigator.vibrate(25);
      }
    }

    function showToast(msg) {
      var t = document.getElementById('toast');
      t.innerText = msg;
      t.classList.add('show');
      setTimeout(function() { t.classList.remove('show'); }, 1800);
    }

    function sendKey(action) {
      vibrate();
      fetch('/api/key?key=' + action, { method: 'POST' }).catch(function(){});
    }

    function sendNum(num) {
      vibrate();
      showToast('Kanal: ' + num);
      fetch('/api/channel?num=' + num, { method: 'POST' }).catch(function(){});
    }

    function sendText() {
      var inp = document.getElementById('tvTextInput');
      var val = inp.value.trim();
      if (!val) return;
      vibrate();
      showToast('TV\'ye Gönderildi: ' + val);
      fetch('/api/text?text=' + encodeURIComponent(val), { method: 'POST' }).catch(function(){});
      inp.value = '';
    }

    function setSleep(min) {
      vibrate();
      if (min === 0) {
        showToast('Uyku zamanlayıcı kapatıldı');
      } else {
        showToast('Uyku zamanlayıcı: ' + min + ' dk');
      }
      fetch('/api/sleep?minutes=' + min, { method: 'POST' }).catch(function(){});
    }

    function toggleNumpad() {
      var np = document.getElementById('numpad');
      np.style.display = np.style.display === 'grid' ? 'none' : 'grid';
    }

    function switchTab(tabId, el) {
      vibrate();
      document.querySelectorAll('.tab-content').forEach(function(c) { c.classList.remove('active'); });
      document.querySelectorAll('.nav-item').forEach(function(n) { n.classList.remove('active'); });
      document.getElementById('tab-' + tabId).classList.add('active');
      el.classList.add('active');
      if (tabId === 'channels' && channelsData.length === 0) {
        loadChannels();
      }
    }

    function loadChannels() {
      fetch('/api/channels')
        .then(function(res) { return res.json(); })
        .then(function(data) {
          channelsData = data || [];
          renderChannels(channelsData);
        })
        .catch(function() {
          document.getElementById('channelList').innerHTML = '<div style="text-align:center; color:#FF6666; padding:20px;">Kanallar yüklenemedi.</div>';
        });
    }

    function renderChannels(list) {
      var html = '';
      if (!list || list.length === 0) {
        html = '<div style="text-align:center; color:#7A8EA8; padding:30px;">Kanal bulunamadı.</div>';
      } else {
        list.forEach(function(ch, idx) {
          var num = (idx + 1).toString().padStart(2, '0');
          var favClass = ch.isFavorite ? 'ch-fav active' : 'ch-fav';
          html += '<div class="channel-item" onclick="selectChannel(\'' + ch.id + '\', \'' + ch.name.replace(/'/g, "\\'") + '\')">' +
                    '<div class="ch-left">' +
                      '<span class="ch-num">' + num + '</span>' +
                      '<span class="ch-name">' + ch.name + '</span>' +
                    '</div>' +
                    '<span class="' + favClass + '" onclick="event.stopPropagation(); toggleFav(\'' + ch.id + '\')">★</span>' +
                  '</div>';
        });
      }
      document.getElementById('channelList').innerHTML = html;
    }

    function filterChannels() {
      var query = document.getElementById('channelSearch').value.toLowerCase();
      var filtered = channelsData.filter(function(ch) {
        return ch.name.toLowerCase().indexOf(query) !== -1;
      });
      renderChannels(filtered);
    }

    function selectChannel(id, name) {
      vibrate();
      showToast('Açılıyor: ' + name);
      fetch('/api/select_channel?id=' + encodeURIComponent(id), { method: 'POST' }).catch(function(){});
    }

    function toggleFav(id) {
      vibrate();
      var target = channelsData.find(function(c){ return c.id === id; });
      if (target) {
        target.isFavorite = !target.isFavorite;
        renderChannels(channelsData);
      }
      fetch('/api/toggle_favorite?id=' + encodeURIComponent(id), { method: 'POST' }).catch(function(){});
    }
  </script>
</body>
</html>"""
    }

    fun sendRemoteCommand(action: String, value: Any? = null) {
        when (action) {
            "play", "resume" -> notifyMediaResumed()
            "pause" -> notifyMediaPaused()
            "seek" -> {
                val seconds = (value as? Number)?.toDouble() ?: 0.0
                notifyPositionChanged(seconds, 100.0)
            }
            "disconnect" -> {
                try {
                    activeClientSocket?.close()
                } catch (_: Exception) {}
                notifyMediaStopped()
                notifyDeviceDisconnected()
            }
        }
    }

    // Flutter Bildirimleri
    private fun notifyDeviceConnected(name: String, type: String) {
        mainHandler.post {
            channel.invokeMethod("onDeviceConnected", mapOf("name" to name, "type" to type))
        }
    }

    private fun notifyDeviceDisconnected() {
        mainHandler.post {
            channel.invokeMethod("onDeviceDisconnected", null)
        }
    }

    private fun notifyMediaStarted(mediaType: String, url: String, title: String, artist: String, album: String) {
        mainHandler.post {
            channel.invokeMethod("onMediaStarted", mapOf(
                "mediaType" to mediaType,
                "url" to url,
                "title" to title,
                "artist" to artist,
                "album" to album
            ))
        }
    }

    private fun notifyMediaStopped() {
        mainHandler.post {
            channel.invokeMethod("onMediaStopped", null)
        }
    }

    private fun notifyMediaPaused() {
        mainHandler.post {
            channel.invokeMethod("onMediaPaused", null)
        }
    }

    private fun notifyMediaResumed() {
        mainHandler.post {
            channel.invokeMethod("onMediaResumed", null)
        }
    }

    private fun notifyPositionChanged(pos: Double, duration: Double) {
        mainHandler.post {
            channel.invokeMethod("onPositionChanged", mapOf("position" to pos, "duration" to duration))
        }
    }
}
