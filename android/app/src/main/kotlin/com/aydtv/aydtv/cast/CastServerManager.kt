package com.aydtv.aydtv.cast

import android.content.Context
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

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var airplayServerSocket: ServerSocket? = null
    private var webRemoteServerSocket: ServerSocket? = null

    private var isRunning = false
    private var deviceName = "AydTV"
    private var pinEnabled = false
    private var activeClientSocket: Socket? = null

    companion object {
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
        const val WEB_REMOTE_PORT = 8088
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

            // 1. Standart AirPlay Servisi (Tüm iOS cihazlarıyla uyumlu şifresiz HLS & Video & Fotoğraf)
            val airplayService = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_airplay._tcp"
                port = AIRPLAY_PORT
                setAttribute("deviceid", mac)
                setAttribute("features", "0x7") // AirPlay 1 Video + Photo (FairPlay DRM kısıtlaması olmadan)
                setAttribute("model", "AppleTV2,1")
                setAttribute("srcvers", "130.14")
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
                val method = if (parts.isNotEmpty()) parts[0] else "GET"
                val path = if (parts.size > 1) parts[1] else "/"

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

                handleAirplayRequest(method, path, headers, body, output)
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            notifyDeviceDisconnected()
        }
    }

    private fun handleAirplayRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream
    ) {
        val cseq = headers["cseq"]
        when {
            path.startsWith("/server-info") || path.startsWith("/info") -> {
                val mac = getMacAddress()
                val xmlInfo = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key><string>$mac</string>
    <key>features</key><integer>7</integer>
    <key>model</key><string>AppleTV2,1</string>
    <key>protovers</key><string>1.0</string>
    <key>srcvers</key><string>130.14</string>
    <key>statusFlags</key><integer>4</integer>
</dict>
</plist>"""
                sendHttpResponse(output, 200, "OK", "text/x-apple-plist+xml", xmlInfo, cseq)
            }
            path.startsWith("/play") -> {
                var streamUrl = ""
                var mediaType = "video"
                if (body.contains("<key>Content-Location</key>")) {
                    val match = Regex("<string>(.*?)</string>").find(body)
                    if (match != null) streamUrl = match.groupValues[1]
                } else if (body.contains("Content-Location:")) {
                    val match = Regex("Content-Location:\\s*(.*?)\r?\n").find(body)
                    if (match != null) streamUrl = match.groupValues[1].trim()
                }
                if (streamUrl.contains(".mp3") || streamUrl.contains(".m4a") || streamUrl.contains(".flac")) {
                    mediaType = "audio"
                }
                notifyMediaStarted(mediaType, streamUrl, "AirPlay Medyası", "Apple Cihazı", deviceName)
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
                "Server: AirTunes/130.14\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n"
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

            // Header'ları tüket
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                line = reader.readLine()
            }

            when {
                path.startsWith("/api/key") -> {
                    val key = Regex("key=([a-zA-Z0-9_]+)").find(path)?.groupValues?.get(1) ?: "ok"
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to key))
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
                else -> {
                    // Modern Cam Efektli Mobil Kumanda HTML Sayfası
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
  <title>AydTV Akıllı Kumanda</title>
  <style>
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
    body {
      margin: 0; padding: 16px; background: #070B12; color: #FFF;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      display: flex; flex-direction: column; align-items: center; min-height: 100vh;
    }
    .header { font-size: 20px; font-weight: bold; color: #00F2FE; margin-bottom: 16px; text-shadow: 0 0 10px rgba(0,242,254,0.4); }
    .card {
      background: rgba(255,255,255,0.05); border: 1px solid rgba(0,242,254,0.3);
      border-radius: 20px; padding: 16px; width: 100%; max-width: 340px;
      backdrop-filter: blur(20px); box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    }
    .dpad {
      position: relative; width: 200px; height: 200px; margin: 10px auto;
      border-radius: 50%; background: rgba(255,255,255,0.03); border: 2px solid rgba(0,242,254,0.2);
    }
    .btn {
      position: absolute; background: rgba(0,242,254,0.15); border: 1px solid rgba(0,242,254,0.5);
      color: #FFF; font-weight: bold; font-size: 15px; border-radius: 12px; cursor: pointer;
      display: flex; align-items: center; justify-content: center; transition: all 0.1s;
    }
    .btn:active { background: #00F2FE; color: #000; transform: scale(0.92); }
    .btn-up { top: 6px; left: 65px; width: 70px; height: 48px; }
    .btn-down { bottom: 6px; left: 65px; width: 70px; height: 48px; }
    .btn-left { top: 65px; left: 6px; width: 48px; height: 70px; }
    .btn-right { top: 65px; right: 6px; width: 48px; height: 70px; }
    .btn-ok {
      top: 65px; left: 65px; width: 70px; height: 70px; border-radius: 50%;
      background: #00F2FE; color: #000; font-size: 17px; box-shadow: 0 0 15px rgba(0,242,254,0.5);
    }
    .row { display: flex; justify-content: space-between; gap: 10px; margin-top: 14px; }
    .action-btn {
      flex: 1; padding: 14px 8px; border-radius: 14px; background: rgba(255,255,255,0.08);
      border: 1px solid rgba(255,255,255,0.15); color: #FFF; font-size: 14px; font-weight: 600;
      text-align: center; cursor: pointer;
    }
    .action-btn:active { background: #00F2FE; color: #000; }
    .num-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 14px; }
    .num-btn {
      padding: 12px; font-size: 16px; font-weight: bold; border-radius: 10px;
      background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1);
      color: #FFF; text-align: center; cursor: pointer;
    }
    .num-btn:active { background: #00F2FE; color: #000; }
  </style>
</head>
<body>
  <div class="header">📺 AydTV Akıllı Kumanda</div>
  <div class="card">
    <div class="row" style="margin-top:0;">
      <div class="action-btn" onclick="send('back')">↩ Geri</div>
      <div class="action-btn" onclick="send('settings')">⚙️ Ayarlar</div>
    </div>
    
    <div class="dpad">
      <div class="btn btn-up" onclick="send('up')">▲</div>
      <div class="btn btn-down" onclick="send('down')">▼</div>
      <div class="btn btn-left" onclick="send('left')">◀</div>
      <div class="btn btn-right" onclick="send('right')">▶</div>
      <div class="btn btn-ok" onclick="send('ok')">OK</div>
    </div>

    <div class="row">
      <div class="action-btn" onclick="send('channel_up')">Kanal ▲</div>
      <div class="action-btn" onclick="send('channel_down')">Kanal ▼</div>
    </div>

    <div class="num-grid">
      <div class="num-btn" onclick="sendNum(1)">1</div>
      <div class="num-btn" onclick="sendNum(2)">2</div>
      <div class="num-btn" onclick="sendNum(3)">3</div>
      <div class="num-btn" onclick="sendNum(4)">4</div>
      <div class="num-btn" onclick="sendNum(5)">5</div>
      <div class="num-btn" onclick="sendNum(6)">6</div>
      <div class="num-btn" onclick="sendNum(7)">7</div>
      <div class="num-btn" onclick="sendNum(8)">8</div>
      <div class="num-btn" onclick="sendNum(9)">9</div>
      <div></div>
      <div class="num-btn" onclick="sendNum(0)">0</div>
      <div></div>
    </div>
  </div>

  <script>
    function send(action) {
      if (window.navigator.vibrate) window.navigator.vibrate(25);
      fetch('/api/key?key=' + action, { method: 'POST' }).catch(function(){});
    }
    function sendNum(num) {
      if (window.navigator.vibrate) window.navigator.vibrate(25);
      fetch('/api/channel?num=' + num, { method: 'POST' }).catch(function(){});
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
