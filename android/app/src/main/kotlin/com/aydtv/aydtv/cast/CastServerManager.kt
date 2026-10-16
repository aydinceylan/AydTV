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
    private var airplayRegistrationListener: NsdManager.RegistrationListener? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var airplayServerSocket: ServerSocket? = null
    private var raopServerSocket: ServerSocket? = null
    private var webRemoteServerSocket: ServerSocket? = null

    private var isRunning = false
    private var deviceName = "AydTV"
    private var pinEnabled = false
    private var activeClientSocket: Socket? = null
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private var disconnectRunnable: Runnable? = null

    private var channelsJson = "[]"
    private var statusJson = "{\"channel\":\"AydTV\",\"sleep\":0,\"isPlaying\":true}"
    private var settingsJson = "{\"buffer_mode\":\"balanced\",\"keep_screen_on\":true,\"mute_on_background\":true,\"auto_start_on_boot\":true,\"cast_enabled\":true,\"sleep_minutes\":0}"

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

    fun updateSettings(json: String) {
        settingsJson = json
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

        disconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnectRunnable = null
        activeConnections.set(0)

        try {
            activeClientSocket?.close()
            airplayServerSocket?.close()
            raopServerSocket?.close()
            webRemoteServerSocket?.close()
        } catch (_: Exception) {}

        activeClientSocket = null
        airplayServerSocket = null
        raopServerSocket = null
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

        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank() && androidId.length >= 12) {
                return androidId.substring(0, 12).chunked(2).joinToString(":").uppercase()
            }
        } catch (_: Exception) {}

        return "93:8D:98:6C:3F:65"
    }

    private fun getAirplayPlistXml(): String {
        val mac = getMacAddress()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>name</key><string>$deviceName</string>
	<key>deviceID</key><string>$mac</string>
	<key>macAddress</key><string>$mac</string>
	<key>model</key><string>AppleTV3,2</string>
	<key>sourceVersion</key><string>220.68</string>
	<key>protovers</key><string>1.1</string>
	<key>statusFlags</key><integer>4</integer>
	<key>features</key><integer>1384120311</integer>
	<key>vv</key><integer>2</integer>
	<key>keepAliveLowPower</key><integer>1</integer>
	<key>keepAliveSendStatsAsBody</key><integer>1</integer>
	<key>pi</key><string>b08f5a79-db29-4384-b456-a4784d9e6055</string>
	<key>pk</key><data>XYMxJlYMsZoUGTcneJbw/UN7poAeshCsTDnZAHLXDag=</data>
	<key>displays</key>
	<array>
		<dict>
			<key>width</key><integer>1920</integer>
			<key>height</key><integer>1080</integer>
			<key>widthPixels</key><integer>1920</integer>
			<key>heightPixels</key><integer>1080</integer>
			<key>refreshRate</key><integer>60</integer>
			<key>maxFPS</key><integer>60</integer>
			<key>features</key><integer>14</integer>
			<key>overscanned</key><false/>
			<key>rotation</key><false/>
			<key>uuid</key><string>e5f7a68d-7b0f-4305-984b-974f677a150b</string>
		</dict>
	</array>
	<key>audioFormats</key>
	<array>
		<dict>
			<key>audioInputFormats</key><integer>67108860</integer>
			<key>audioOutputFormats</key><integer>67108860</integer>
			<key>type</key><integer>100</integer>
		</dict>
		<dict>
			<key>audioInputFormats</key><integer>67108860</integer>
			<key>audioOutputFormats</key><integer>67108860</integer>
			<key>type</key><integer>101</integer>
		</dict>
	</array>
</dict>
</plist>"""
    }

    private fun registerMdnsServices() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val mac = getMacAddress()
            val macNoColon = mac.replace(":", "")
            val localIp = getLocalIpAddress()

            // 1. Evrensel Standart AirPlay Servisi (Bit 27 OFF: Şifresiz ve Doğrudan Yansıtma)
            val airplayService = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_airplay._tcp"
                port = AIRPLAY_PORT
                setAttribute("deviceid", mac)
                setAttribute("features", "0x527FFFF7,0x0")
                setAttribute("model", "AppleTV3,2")
                setAttribute("srcvers", "220.68")
                setAttribute("protovers", "1.1")
                setAttribute("flags", "0x4")
                setAttribute("vv", "2")
                setAttribute("pw", "false")
                setAttribute("pk", "7975617ca5551de9401eaf556cace582345ce26a2b79beaa15ad45bb322ca712")
                setAttribute("pi", "b08f5a79-db29-4384-b456-a4784d9e6055")
            }

            try {
                val inetAddr = InetAddress.getByName(localIp)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    airplayService.setHostAddresses(listOf(inetAddr))
                } else {
                    @Suppress("DEPRECATION")
                    airplayService.host = inetAddr
                }
            } catch (e: Exception) {
                android.util.Log.w("CastServer", "Failed to set host address on airplay service: ${e.message}")
            }

            val airplayListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    android.util.Log.i("CastServer", "AirPlay mDNS registered successfully: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    android.util.Log.e("CastServer", "AirPlay mDNS registration failed: errorCode=$errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            }
            airplayRegistrationListener = airplayListener
            nsdManager?.registerService(airplayService, NsdManager.PROTOCOL_DNS_SD, airplayListener)

            // 2. RAOP (AirPlay Müzik / Ses) Servisi (Modern Apple standardı: Port 7000)
            val raopService = NsdServiceInfo().apply {
                serviceName = "${macNoColon}@$deviceName"
                serviceType = "_raop._tcp"
                port = AIRPLAY_PORT
                setAttribute("ch", "2")
                setAttribute("cn", "0,1,2,3")
                setAttribute("et", "0,3,5")
                setAttribute("sr", "44100")
                setAttribute("ss", "16")
                setAttribute("tp", "TCP,UDP")
                setAttribute("vn", "65537")
                setAttribute("da", "true")
                setAttribute("md", "0,1,2")
                setAttribute("am", "AppleTV3,2")
                setAttribute("vs", "220.68")
                setAttribute("sf", "0x4")
                setAttribute("pw", "false")
                setAttribute("features", "0x527FFFF7,0x0")
            }

            try {
                val inetAddr = InetAddress.getByName(localIp)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    raopService.setHostAddresses(listOf(inetAddr))
                } else {
                    @Suppress("DEPRECATION")
                    raopService.host = inetAddr
                }
            } catch (e: Exception) {
                android.util.Log.w("CastServer", "Failed to set host address on raop service: ${e.message}")
            }

            val raopListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    android.util.Log.i("CastServer", "RAOP mDNS registered successfully: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    android.util.Log.e("CastServer", "RAOP mDNS registration failed: errorCode=$errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            }
            raopRegistrationListener = raopListener
            nsdManager?.registerService(raopService, NsdManager.PROTOCOL_DNS_SD, raopListener)
        } catch (e: Exception) {
            android.util.Log.e("CastServer", "mDNS registration error: ${e.message}")
        }
    }

    private fun unregisterMdnsServices() {
        try {
            airplayRegistrationListener?.let { nsdManager?.unregisterService(it) }
            raopRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        airplayRegistrationListener = null
        raopRegistrationListener = null
    }

    private fun startAirplayServer() {
        // 1. Ana AirPlay Portu (Port 7000 - Video, Ekran Yansıtma, Fotoğraf & Modern RAOP)
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

        // 2. Yedek RAOP Portu (Port 5000 - Klasik AirTunes / Ses İstemcileri İçin)
        executor.execute {
            try {
                raopServerSocket = ServerSocket(RAOP_PORT).apply {
                    reuseAddress = true
                }
                while (isRunning && raopServerSocket?.isClosed == false) {
                    val client = raopServerSocket?.accept() ?: break
                    executor.execute { handleAirplayClient(client) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleAirplayClient(socket: Socket) {
        activeClientSocket = socket
        disconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnectRunnable = null
        activeConnections.incrementAndGet()

        try {
            val rawInput = socket.getInputStream()
            val output = socket.getOutputStream()

            var deviceDetected = false
            var clientName = "Apple Cihazı"

            while (isRunning && !socket.isClosed) {
                // HTTP / RTSP Header'ları güvenli ve hızlı şekilde bayt bazlı oku
                val headerBytes = ByteArrayOutputStream()
                var b: Int

                while (rawInput.read().also { b = it } != -1) {
                    headerBytes.write(b)
                    val size = headerBytes.size()
                    if (b == '\n'.code) {
                        val buf = headerBytes.toByteArray()
                        if (size >= 4 && buf[size - 4] == '\r'.code.toByte() && buf[size - 3] == '\n'.code.toByte() && buf[size - 2] == '\r'.code.toByte() && buf[size - 1] == '\n'.code.toByte()) {
                            break
                        }
                        if (size >= 2 && buf[size - 2] == '\n'.code.toByte() && buf[size - 1] == '\n'.code.toByte()) {
                            break
                        }
                    }
                }

                if (headerBytes.size() == 0) break

                val headerText = headerBytes.toString("UTF-8")
                val lines = headerText.split("\r\n", "\n")
                if (lines.isEmpty() || lines[0].isBlank()) continue

                val requestLine = lines[0].trim()
                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0].uppercase() else "GET"
                val path = if (parts.size > 1) parts[1] else "/"
                val isRtsp = requestLine.contains("RTSP") || parts.getOrNull(2)?.contains("RTSP") == true

                val headers = mutableMapOf<String, String>()
                var contentLength = 0

                for (i in 1 until lines.size) {
                    val l = lines[i]
                    val headerParts = l.split(":", limit = 2)
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
                }

                // Body'yi byte bazlı güvenle oku (Fotoğraf ve Video binary bozulmasını engeller)
                val bodyBytes = ByteArray(contentLength)
                if (contentLength > 0) {
                    var bytesRead = 0
                    while (bytesRead < contentLength) {
                        val read = rawInput.read(bodyBytes, bytesRead, contentLength - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }
                }

                val bodyStr = if (contentLength > 0 && contentLength < 32768) {
                    try { String(bodyBytes, Charsets.UTF_8) } catch (_: Exception) { "" }
                } else ""

                if (!deviceDetected) {
                    deviceDetected = true
                    notifyDeviceConnected(clientName, "airplay")
                }

                val cseq = headers["cseq"] ?: "0"

                if (isRtsp) {
                    handleRtspRequest(method, path, headers, bodyBytes, bodyStr, output, cseq, clientName)
                } else {
                    handleAirplayHttpRequest(method, path, headers, bodyBytes, bodyStr, output, cseq, clientName)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            val remaining = activeConnections.decrementAndGet()
            if (remaining <= 0) {
                disconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                disconnectRunnable = Runnable {
                    if (activeConnections.get() <= 0) {
                        notifyDeviceDisconnected()
                    }
                }
                mainHandler.postDelayed(disconnectRunnable!!, 3000)
            }
        }
    }

    private fun handleRtspRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        bodyStr: String,
        output: OutputStream,
        cseq: String,
        clientName: String
    ) {
        android.util.Log.i("CastServer", "RTSP Request: $method $path, CSeq=$cseq, BodySize=${bodyBytes.size}")
        when {
            method == "OPTIONS" -> {
                sendRtspResponse(
                    output, 200, "OK", cseq,
                    extraHeaders = mapOf(
                        "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
                    )
                )
            }
            method == "ANNOUNCE" -> {
                notifyDeviceConnected(clientName, "airplay")
                sendRtspResponse(output, 200, "OK", cseq)
            }
            method == "GET" && (path.startsWith("/info") || path.startsWith("/server-info")) -> {
                val plist = getAirplayPlistXml().toByteArray(Charsets.UTF_8)
                sendRtspResponse(output, 200, "OK", cseq, "text/x-apple-plist+xml", plist)
            }
            method == "POST" && path.startsWith("/pair-setup") -> {
                val serverPk = ByteArray(32) { ((it * 17 + 23) % 256).toByte() }
                sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", serverPk)
            }
            method == "POST" && path.startsWith("/pair-verify") -> {
                val isStep1 = bodyBytes.isNotEmpty() && bodyBytes[0].toInt() == 1
                if (isStep1) {
                    val verifyResp = ByteArray(96)
                    for (i in 0 until 32) verifyResp[i] = ((i * 13 + 7) % 256).toByte()
                    for (i in 32 until 96) verifyResp[i] = ((i * 31 + 41) % 256).toByte()
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", verifyResp)
                } else {
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", ByteArray(0))
                }
            }
            method == "POST" && path.startsWith("/fp-setup") -> {
                if (bodyBytes.size <= 16) {
                    val fp1 = ByteArray(142)
                    System.arraycopy("FPLY".toByteArray(Charsets.US_ASCII), 0, fp1, 0, 4)
                    fp1[4] = 3
                    fp1[5] = 1
                    for (i in 6 until 142) fp1[i] = ((i * 19 + 5) % 256).toByte()
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", fp1)
                } else {
                    val fp2 = ByteArray(32)
                    System.arraycopy("FPLY".toByteArray(Charsets.US_ASCII), 0, fp2, 0, 4)
                    fp2[4] = 3
                    fp2[5] = 2
                    for (i in 6 until 32) fp2[i] = ((i * 29 + 11) % 256).toByte()
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", fp2)
                }
            }
            method == "SETUP" -> {
                notifyMediaStarted("screen", "", "Ekran Yansıtma", clientName, deviceName)
                val transport = headers["transport"] ?: "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
                sendRtspResponse(
                    output, 200, "OK", cseq,
                    extraHeaders = mapOf(
                        "Transport" to transport,
                        "Session" to "1",
                        "Audio-Latency" to "11025"
                    )
                )
            }
            method == "RECORD" -> {
                sendRtspResponse(
                    output, 200, "OK", cseq,
                    extraHeaders = mapOf(
                        "Session" to "1",
                        "Audio-Latency" to "11025"
                    )
                )
            }
            method in listOf("GET_PARAMETER", "SET_PARAMETER", "FEEDBACK") -> {
                sendRtspResponse(output, 200, "OK", cseq)
            }
            method == "TEARDOWN" -> {
                sendRtspResponse(output, 200, "OK", cseq)
                notifyMediaStopped()
                notifyDeviceDisconnected()
            }
            else -> {
                sendRtspResponse(output, 200, "OK", cseq)
            }
        }
    }

    private fun handleAirplayHttpRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        bodyStr: String,
        output: OutputStream,
        cseq: String,
        clientName: String
    ) {
        android.util.Log.i("CastServer", "HTTP Request: $method $path, CSeq=$cseq, BodySize=${bodyBytes.size}")
        when {
            path.startsWith("/server-info") || path.startsWith("/info") -> {
                val xmlInfo = getAirplayPlistXml()
                sendHttpResponse(output, 200, "OK", "text/x-apple-plist+xml", xmlInfo, cseq)
            }
            path.startsWith("/pair-setup") -> {
                val serverPk = ByteArray(32) { ((it * 17 + 23) % 256).toByte() }
                sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", serverPk, cseq)
            }
            path.startsWith("/pair-verify") -> {
                val isStep1 = bodyBytes.isNotEmpty() && bodyBytes[0].toInt() == 1
                if (isStep1) {
                    val verifyResp = ByteArray(96)
                    for (i in 0 until 32) verifyResp[i] = ((i * 13 + 7) % 256).toByte()
                    for (i in 32 until 96) verifyResp[i] = ((i * 31 + 41) % 256).toByte()
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", verifyResp, cseq)
                } else {
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", ByteArray(0), cseq)
                }
            }
            path.startsWith("/fp-setup") -> {
                if (bodyBytes.size <= 16) {
                    val fp1 = ByteArray(142)
                    System.arraycopy("FPLY".toByteArray(Charsets.US_ASCII), 0, fp1, 0, 4)
                    fp1[4] = 3
                    fp1[5] = 1
                    for (i in 6 until 142) fp1[i] = ((i * 19 + 5) % 256).toByte()
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", fp1, cseq)
                } else {
                    val fp2 = ByteArray(32)
                    System.arraycopy("FPLY".toByteArray(Charsets.US_ASCII), 0, fp2, 0, 4)
                    fp2[4] = 3
                    fp2[5] = 2
                    for (i in 6 until 32) fp2[i] = ((i * 29 + 11) % 256).toByte()
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", fp2, cseq)
                }
            }
            path.startsWith("/play") -> {
                var streamUrl = headers["content-location"] ?: ""
                if (streamUrl.isBlank()) {
                    if (bodyStr.contains("<key>Content-Location</key>")) {
                        val match = Regex("<string>(.*?)</string>").find(bodyStr)
                        if (match != null) streamUrl = match.groupValues[1]
                    } else if (bodyStr.contains("Content-Location:")) {
                        val match = Regex("Content-Location:\\s*(.*?)\r?\n").find(bodyStr)
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
                    photoFile.writeBytes(bodyBytes)
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
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Server: AirTunes/220.68\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        if (!cseq.isNullOrBlank()) {
            sb.append("CSeq: $cseq\r\n")
        }
        sb.append("Connection: keep-alive\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendHttpBinaryResponse(
        output: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: ByteArray,
        cseq: String? = null
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Server: AirTunes/220.68\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        if (!cseq.isNullOrBlank()) {
            sb.append("CSeq: $cseq\r\n")
        }
        sb.append("Connection: keep-alive\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun sendRtspResponse(
        output: OutputStream,
        code: Int,
        status: String,
        cseq: String,
        contentType: String? = null,
        body: ByteArray? = null,
        extraHeaders: Map<String, String>? = null
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code $status\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("Server: AirTunes/220.68\r\n")
        extraHeaders?.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (contentType != null) {
            sb.append("Content-Type: $contentType\r\n")
        }
        val len = body?.size ?: 0
        sb.append("Content-Length: $len\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null && body.isNotEmpty()) {
            output.write(body)
        }
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
            var contentLength = 0
            while (!line.isNullOrBlank()) {
                val lp = line.split(":", limit = 2)
                if (lp.size == 2 && lp[0].trim().equals("content-length", ignoreCase = true)) {
                    contentLength = lp[1].trim().toIntOrNull() ?: 0
                }
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
                path.startsWith("/api/settings_update") -> {
                    val key = Regex("key=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                    val rawVal = Regex("val=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                    val value = try { URLDecoder.decode(rawVal, "UTF-8") } catch (_: Exception) { rawVal }
                    mainHandler.post {
                        channel.invokeMethod("onRemoteCommand", mapOf("action" to "setting_update", "value" to mapOf("key" to key, "value" to value)))
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/settings") -> {
                    sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", settingsJson)
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
  <meta name="theme-color" content="#070A12">
  <title>AydTV Kumanda</title>
  <style>
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; outline: none; margin: 0; padding: 0; }
    body {
      background: #070A12; color: #FFFFFF; font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh; display: flex; flex-direction: column; align-items: center; padding-bottom: 85px; user-select: none;
      -webkit-font-smoothing: antialiased;
    }

    /* Üst Bar */
    .top-bar {
      width: 100%; max-width: 440px; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center;
      background: rgba(12, 17, 28, 0.88); backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
      border-bottom: 1px solid rgba(255, 255, 255, 0.08); position: sticky; top: 0; z-index: 100;
    }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; letter-spacing: -0.3px; color: #FFFFFF; }
    .brand-logo { width: 22px; height: 22px; color: #00E5FF; }
    .status-badge {
      display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      background: rgba(0, 230, 255, 0.08); border: 1px solid rgba(0, 230, 255, 0.25);
      padding: 5px 12px; border-radius: 20px; color: #00E5FF;
    }
    .status-dot { width: 6px; height: 6px; border-radius: 50%; background: #00E5FF; box-shadow: 0 0 8px #00E5FF; animation: pulse 2s infinite; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }

    /* Ana Konteyner */
    .container { width: 100%; max-width: 440px; padding: 16px 18px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
    .tab-content { width: 100%; display: none; flex-direction: column; align-items: center; gap: 16px; }
    .tab-content.active { display: flex; }

    /* Kumanda Gövdesi */
    .remote-body {
      width: 100%; max-width: 360px; background: linear-gradient(180deg, #101624 0%, #0A0E18 100%);
      border: 1px solid rgba(255, 255, 255, 0.09); border-radius: 40px; padding: 22px 18px;
      box-shadow: 0 20px 50px rgba(0,0,0,0.65), inset 0 1px 1px rgba(255,255,255,0.1);
      display: flex; flex-direction: column; align-items: center; gap: 20px;
    }

    /* Üst Sistem Tuşları */
    .btn-row { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
    .sys-btn {
      flex: 1; height: 48px; border-radius: 16px; background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.08); color: #E0E6ED; font-size: 13px; font-weight: 600;
      display: flex; align-items: center; justify-content: center; gap: 7px; cursor: pointer; transition: all 0.15s;
    }
    .sys-btn svg { width: 18px; height: 18px; stroke: currentColor; fill: none; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .sys-btn:active { transform: scale(0.94); background: rgba(0, 229, 255, 0.2); border-color: #00E5FF; color: #00E5FF; }
    .sys-btn.danger { background: rgba(255, 68, 68, 0.12); border-color: rgba(255, 68, 68, 0.3); color: #FF5252; }
    .sys-btn.danger:active { background: #FF5252; color: #FFFFFF; }

    /* D-Pad Navigasyon */
    .dpad-wrapper {
      position: relative; width: 230px; height: 230px; border-radius: 50%;
      background: radial-gradient(circle, #172134 0%, #0C1220 100%);
      border: 2px solid rgba(255, 255, 255, 0.1); box-shadow: 0 12px 30px rgba(0,0,0,0.5);
    }
    .dpad-btn {
      position: absolute; color: #94A3B8; display: flex; align-items: center; justify-content: center;
      cursor: pointer; transition: all 0.1s;
    }
    .dpad-btn svg { width: 22px; height: 22px; stroke: currentColor; fill: none; stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; }
    .dpad-btn:active { color: #00E5FF; transform: scale(0.92); }
    .dpad-up { top: 6px; left: 75px; width: 80px; height: 55px; }
    .dpad-down { bottom: 6px; left: 75px; width: 80px; height: 55px; }
    .dpad-left { top: 75px; left: 6px; width: 55px; height: 80px; }
    .dpad-right { top: 75px; right: 6px; width: 55px; height: 80px; }
    .dpad-ok {
      position: absolute; top: 71px; left: 71px; width: 88px; height: 88px; border-radius: 50%;
      background: linear-gradient(135deg, #00E5FF 0%, #0077FF 100%); color: #050B14;
      font-weight: 800; font-size: 19px; letter-spacing: 0.5px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; box-shadow: 0 0 24px rgba(0, 229, 255, 0.45); transition: all 0.15s;
    }
    .dpad-ok:active { transform: scale(0.92); box-shadow: 0 0 35px rgba(0, 229, 255, 0.8); }

    /* Ses ve Kanal Rocker Tuşları */
    .rocker-section { width: 100%; display: flex; justify-content: space-between; align-items: center; padding: 0 6px; }
    .rocker-pill {
      width: 66px; height: 130px; border-radius: 33px; background: rgba(255,255,255,0.04);
      border: 1px solid rgba(255,255,255,0.1); display: flex; flex-direction: column; overflow: hidden;
    }
    .rocker-btn {
      flex: 1; display: flex; align-items: center; justify-content: center; color: #E2E8F0;
      cursor: pointer; transition: background 0.12s;
    }
    .rocker-btn svg { width: 20px; height: 20px; stroke: currentColor; fill: none; stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
    .rocker-btn:active { background: rgba(0, 229, 255, 0.25); color: #00E5FF; }
    .rocker-label { font-size: 10px; font-weight: 700; color: #64748B; text-align: center; letter-spacing: 0.5px; line-height: 1; }

    .center-actions { display: flex; flex-direction: column; gap: 14px; }
    .round-btn {
      width: 56px; height: 56px; border-radius: 50%; background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.12); display: flex; align-items: center; justify-content: center;
      color: #E2E8F0; cursor: pointer; transition: all 0.15s;
    }
    .round-btn svg { width: 22px; height: 22px; stroke: currentColor; fill: none; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .round-btn:active { transform: scale(0.92); background: #00E5FF; color: #070B14; }

    /* Metin Girişi Kutusu */
    .keyboard-box {
      width: 100%; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.1);
      border-radius: 18px; padding: 6px 10px; display: flex; gap: 8px; align-items: center;
    }
    .keyboard-icon { width: 20px; height: 20px; color: #64748B; flex-shrink: 0; margin-left: 4px; }
    .keyboard-input {
      flex: 1; background: transparent; border: none; color: #FFFFFF; font-size: 14px; padding: 8px 4px;
    }
    .keyboard-input::placeholder { color: #475569; }
    .send-text-btn {
      padding: 9px 16px; border-radius: 12px; background: #00E5FF; color: #070B14;
      font-weight: 700; font-size: 12px; border: none; cursor: pointer; transition: all 0.15s;
    }
    .send-text-btn:active { transform: scale(0.94); }

    /* Numpad Izgarası */
    .numpad-container { width: 100%; display: none; grid-template-columns: repeat(3, 1fr); gap: 8px; }
    .num-key {
      padding: 13px; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
      border-radius: 14px; font-size: 17px; font-weight: 700; text-align: center; cursor: pointer; transition: all 0.12s;
    }
    .num-key:active { background: #00E5FF; color: #070B14; transform: scale(0.94); }

    /* Kanallar Sekmesi */
    .search-box {
      width: 100%; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1);
      border-radius: 16px; padding: 12px 16px; display: flex; align-items: center; gap: 10px;
    }
    .search-icon { width: 18px; height: 18px; color: #64748B; flex-shrink: 0; }
    .search-input { flex: 1; background: transparent; border: none; color: #FFFFFF; font-size: 15px; }
    .channel-list { width: 100%; display: flex; flex-direction: column; gap: 8px; }
    .channel-item {
      display: flex; align-items: center; justify-content: space-between; padding: 14px 16px;
      background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06);
      border-radius: 16px; cursor: pointer; transition: all 0.15s;
    }
    .channel-item:active { background: rgba(0, 229, 255, 0.15); border-color: #00E5FF; }
    .ch-left { display: flex; align-items: center; gap: 14px; }
    .ch-num { font-size: 13px; font-weight: 700; color: #00E5FF; width: 28px; }
    .ch-name { font-size: 15px; font-weight: 600; letter-spacing: -0.2px; }
    .ch-fav { width: 22px; height: 22px; color: #475569; padding: 2px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
    .ch-fav.active { color: #FFB300; }
    .ch-fav svg { width: 100%; height: 100%; stroke: currentColor; fill: none; stroke-width: 2; }
    .ch-fav.active svg { fill: #FFB300; }

    /* Ayarlar Sekmesi */
    .settings-card {
      width: 100%; background: linear-gradient(180deg, #101624 0%, #0A0E18 100%);
      border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 24px; padding: 18px;
      display: flex; flex-direction: column; gap: 14px;
    }
    .card-head { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 700; color: #00E5FF; }
    .card-head svg { width: 20px; height: 20px; stroke: currentColor; fill: none; stroke-width: 2; }
    .card-desc { font-size: 12px; color: #8F9CAE; line-height: 1.4; }

    /* Ayar Satırları & Switch */
    .setting-row {
      display: flex; justify-content: space-between; align-items: center; padding: 12px 0;
      border-bottom: 1px solid rgba(255,255,255,0.06);
    }
    .setting-row:last-child { border-bottom: none; }
    .setting-info { display: flex; flex-direction: column; gap: 2px; }
    .setting-title { font-size: 14px; font-weight: 600; color: #FFFFFF; }
    .setting-sub { font-size: 11px; color: #718096; }

    /* Switch Bileşeni */
    .switch {
      position: relative; width: 46px; height: 26px; display: inline-block; flex-shrink: 0;
    }
    .switch input { opacity: 0; width: 0; height: 0; }
    .slider {
      position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0;
      background-color: rgba(255,255,255,0.15); border-radius: 26px; transition: .25s;
    }
    .slider:before {
      position: absolute; content: ""; height: 20px; width: 20px; left: 3px; bottom: 3px;
      background-color: white; border-radius: 50%; transition: .25s;
    }
    input:checked + .slider { background-color: #00E5FF; }
    input:checked + .slider:before { transform: translateX(20px); background-color: #050B14; }

    /* Segmentli Buton Grubu (Tampon Modu) */
    .segment-group {
      display: flex; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
      border-radius: 14px; padding: 4px; gap: 4px;
    }
    .segment-btn {
      flex: 1; padding: 10px 4px; font-size: 12px; font-weight: 600; text-align: center;
      border-radius: 10px; cursor: pointer; color: #8F9CAE; transition: all 0.15s;
    }
    .segment-btn.active { background: #00E5FF; color: #050B14; font-weight: 700; box-shadow: 0 2px 10px rgba(0,229,255,0.3); }

    /* Uyku Grid */
    .timer-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
    .timer-btn {
      padding: 12px 6px; border-radius: 12px; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
      color: #E2E8F0; font-size: 13px; font-weight: 600; text-align: center; cursor: pointer; transition: all 0.12s;
    }
    .timer-btn:active { background: #00E5FF; color: #050B14; }
    .timer-btn.cancel { grid-column: span 3; background: rgba(255,68,68,0.12); border-color: rgba(255,68,68,0.3); color: #FF5252; }
    .timer-btn.cancel:active { background: #FF5252; color: #FFF; }

    /* Alt Sabit Navigasyon */
    .bottom-nav {
      position: fixed; bottom: 0; width: 100%; max-width: 440px; height: 72px;
      background: rgba(10, 14, 24, 0.94); backdrop-filter: blur(25px); -webkit-backdrop-filter: blur(25px);
      border-top: 1px solid rgba(255, 255, 255, 0.08); display: flex; justify-content: space-around; align-items: center; z-index: 100;
    }
    .nav-item {
      display: flex; flex-direction: column; align-items: center; gap: 4px; color: #64748B;
      font-size: 11px; font-weight: 600; cursor: pointer; transition: all 0.15s;
    }
    .nav-item svg { width: 22px; height: 22px; stroke: currentColor; fill: none; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .nav-item.active { color: #00E5FF; }

    /* Bildirim Toast */
    .toast {
      position: fixed; top: 74px; background: #00E5FF; color: #050B14; font-weight: 700;
      font-size: 13px; padding: 10px 20px; border-radius: 20px; box-shadow: 0 6px 25px rgba(0,229,255,0.4);
      z-index: 200; opacity: 0; transform: translateY(-8px); transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1); pointer-events: none;
    }
    .toast.show { opacity: 1; transform: translateY(0); }
  </style>
</head>
<body>
  <div class="top-bar">
    <div class="brand">
      <svg class="brand-logo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <rect x="2" y="7" width="20" height="15" rx="2" ry="2"></rect>
        <polyline points="17 2 12 7 7 2"></polyline>
      </svg>
      AydTV Akıllı Kumanda
    </div>
    <div class="status-badge"><span class="status-dot"></span> TV Bağlı</div>
  </div>

  <div id="toast" class="toast">Komut iletildi</div>

  <div class="container">
    <!-- 1. SEKME: KUMANDA -->
    <div id="tab-remote" class="tab-content active">
      <div class="remote-body">
        <!-- Güç, Home, Mute, Ayarlar -->
        <div class="btn-row">
          <div class="sys-btn danger" onclick="sendKey('power')">
            <svg viewBox="0 0 24 24"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"></path><line x1="12" y1="2" x2="12" y2="12"></line></svg>
            Güç
          </div>
          <div class="sys-btn" onclick="sendKey('home')">
            <svg viewBox="0 0 24 24"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>
            Home
          </div>
          <div class="sys-btn" onclick="sendKey('mute')">
            <svg viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>
            Mute
          </div>
          <div class="sys-btn" onclick="sendKey('settings')">
            <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
            Menü
          </div>
        </div>

        <!-- D-Pad Yön Halkası -->
        <div class="dpad-wrapper">
          <div class="dpad-btn dpad-up" onclick="sendKey('up')">
            <svg viewBox="0 0 24 24"><polyline points="18 15 12 9 6 15"></polyline></svg>
          </div>
          <div class="dpad-btn dpad-down" onclick="sendKey('down')">
            <svg viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"></polyline></svg>
          </div>
          <div class="dpad-btn dpad-left" onclick="sendKey('left')">
            <svg viewBox="0 0 24 24"><polyline points="15 18 9 12 15 6"></polyline></svg>
          </div>
          <div class="dpad-btn dpad-right" onclick="sendKey('right')">
            <svg viewBox="0 0 24 24"><polyline points="9 18 15 12 9 6"></polyline></svg>
          </div>
          <div class="dpad-ok" onclick="sendKey('ok')">OK</div>
        </div>

        <!-- Ses & Kanal Rocker Tuşları -->
        <div class="rocker-section">
          <!-- Ses Rocker -->
          <div class="rocker-pill">
            <div class="rocker-btn" onclick="sendKey('vol_up')">
              <svg viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            </div>
            <div class="rocker-label">SES</div>
            <div class="rocker-btn" onclick="sendKey('vol_down')">
              <svg viewBox="0 0 24 24"><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            </div>
          </div>

          <!-- Orta Eylemler (Geri & Play/Pause) -->
          <div class="center-actions">
            <div class="round-btn" onclick="sendKey('back')">
              <svg viewBox="0 0 24 24"><polyline points="9 14 4 9 9 4"></polyline><path d="M20 20v-7a4 4 0 0 0-4-4H4"></path></svg>
            </div>
            <div class="round-btn" onclick="sendKey('play_pause')">
              <svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
            </div>
          </div>

          <!-- Kanal Rocker -->
          <div class="rocker-pill">
            <div class="rocker-btn" onclick="sendKey('channel_up')">
              <svg viewBox="0 0 24 24"><polyline points="18 15 12 9 6 15"></polyline></svg>
            </div>
            <div class="rocker-label">KANAL</div>
            <div class="rocker-btn" onclick="sendKey('channel_down')">
              <svg viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"></polyline></svg>
            </div>
          </div>
        </div>

        <!-- TV'ye Metin Girişi -->
        <div class="keyboard-box">
          <svg class="keyboard-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="2" y="4" width="20" height="16" rx="2" ry="2"></rect>
            <line x1="6" y1="8" x2="6" y2="8"></line>
            <line x1="10" y1="8" x2="10" y2="8"></line>
            <line x1="14" y1="8" x2="14" y2="8"></line>
            <line x1="18" y1="8" x2="18" y2="8"></line>
            <line x1="6" y1="12" x2="6" y2="12"></line>
            <line x1="10" y1="12" x2="10" y2="12"></line>
            <line x1="14" y1="12" x2="14" y2="12"></line>
            <line x1="18" y1="12" x2="18" y2="12"></line>
            <line x1="7" y1="16" x2="17" y2="16"></line>
          </svg>
          <input type="text" id="tvTextInput" class="keyboard-input" placeholder="TV'ye metin yaz..." onkeydown="if(event.key==='Enter') sendText()">
          <button class="send-text-btn" onclick="sendText()">Gönder</button>
        </div>

        <!-- Sayı Tuş Takımı Aç/Kapa Butonu -->
        <div class="btn-row" style="margin-top:-6px;">
          <div class="sys-btn" onclick="toggleNumpad()">
            <svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
            Sayı Tuşları
          </div>
        </div>

        <!-- Sayısal Tuş Takımı -->
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
        <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line>
        </svg>
        <input type="text" id="channelSearch" class="search-input" placeholder="Kanal ara..." oninput="filterChannels()">
      </div>
      <div id="channelList" class="channel-list">
        <div style="text-align:center; color:#64748B; padding:30px;">Kanallar yükleniyor...</div>
      </div>
    </div>

    <!-- 3. SEKME: AYARLAR (AydTV Üzerindeki Tüm Ayarlar) -->
    <div id="tab-settings" class="tab-content">
      <!-- Tampon & Yayın Kararlılığı -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>
          Yayın Tamponlama Modu
        </div>
        <div class="card-desc">İnternet hızınıza göre canlı TV akışının kararlılığını optimize edin.</div>
        <div class="segment-group">
          <div id="buf-ultra" class="segment-btn" onclick="updateSetting('buffer_mode', 'ultra')">Ultra (8s)</div>
          <div id="buf-balanced" class="segment-btn active" onclick="updateSetting('buffer_mode', 'balanced')">Dengeli (16s)</div>
          <div id="buf-shield" class="segment-btn" onclick="updateSetting('buffer_mode', 'shield')">Zırhlı (25s)</div>
        </div>
      </div>

      <!-- Ekran ve Sistem Tercihleri -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"></rect><polyline points="17 2 12 7 7 2"></polyline></svg>
          Ekran & Sistem Seçenekleri
        </div>

        <div class="setting-row">
          <div class="setting-info">
            <span class="setting-title">Ekranı Sürekli Açık Tut</span>
            <span class="setting-sub">Yayın esnasında ekran kapanmasını önler</span>
          </div>
          <label class="switch">
            <input type="checkbox" id="switch-keep-screen" onchange="updateSetting('keep_screen_on', this.checked)">
            <span class="slider"></span>
          </label>
        </div>

        <div class="setting-row">
          <div class="setting-info">
            <span class="setting-title">Arka Plana Geçince Sesi Durdur</span>
            <span class="setting-sub">TV ana menüsünde yayının sesini keser</span>
          </div>
          <label class="switch">
            <input type="checkbox" id="switch-mute-bg" onchange="updateSetting('mute_on_background', this.checked)">
            <span class="slider"></span>
          </label>
        </div>

        <div class="setting-row">
          <div class="setting-info">
            <span class="setting-title">TV Açıldığında Otomatik Başlat</span>
            <span class="setting-sub">Televizyon açılır açılmaz yayına geçer</span>
          </div>
          <label class="switch">
            <input type="checkbox" id="switch-auto-boot" onchange="updateSetting('auto_start_on_boot', this.checked)">
            <span class="slider"></span>
          </label>
        </div>

        <div class="setting-row">
          <div class="setting-info">
            <span class="setting-title">AirPlay & DLNA Paylaşımı</span>
            <span class="setting-sub">Kablosuz medya alıcısını etkin tutar</span>
          </div>
          <label class="switch">
            <input type="checkbox" id="switch-cast-enabled" onchange="updateSetting('cast_enabled', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
      </div>

      <!-- Uyku Zamanlayıcı Kartı -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
          Uyku Zamanlayıcısı
        </div>
        <div class="card-desc">Süre dolduğunda TV yayını ve sesi otomatik durdurulur.</div>
        <div class="timer-grid">
          <div class="timer-btn" onclick="setSleep(15)">15 Dk</div>
          <div class="timer-btn" onclick="setSleep(30)">30 Dk</div>
          <div class="timer-btn" onclick="setSleep(45)">45 Dk</div>
          <div class="timer-btn" onclick="setSleep(60)">60 Dk</div>
          <div class="timer-btn" onclick="setSleep(90)">90 Dk</div>
          <div class="timer-btn" onclick="setSleep(120)">120 Dk</div>
          <div class="timer-btn cancel" onclick="setSleep(0)">Zamanlayıcıyı Kapat</div>
        </div>
      </div>

      <!-- Hızlı Kontroller -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>
          Hızlı Kontroller
        </div>
        <div class="btn-row">
          <div class="sys-btn" onclick="sendKey('reload')">
            <svg viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>
            Yayını Yenile
          </div>
          <div class="sys-btn" onclick="sendKey('settings')">
            <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
            TV Ayar Menüsü
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Sabit Alt Menü: Kumanda / Kanallar / Ayarlar -->
  <div class="bottom-nav">
    <div class="nav-item active" onclick="switchTab('remote', this)">
      <svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="4"></rect><circle cx="12" cy="7" r="1"></circle><circle cx="12" cy="11" r="1"></circle><circle cx="12" cy="15" r="1"></circle><circle cx="12" cy="18" r="1"></circle></svg>
      <div>Kumanda</div>
    </div>
    <div class="nav-item" onclick="switchTab('channels', this)">
      <svg viewBox="0 0 24 24"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"></rect><polyline points="17 2 12 7 7 2"></polyline></svg>
      <div>Kanallar</div>
    </div>
    <div class="nav-item" onclick="switchTab('settings', this)">
      <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
      <div>Ayarlar</div>
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
      } else if (tabId === 'settings') {
        loadSettings();
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
        html = '<div style="text-align:center; color:#64748B; padding:30px;">Kanal bulunamadı.</div>';
      } else {
        list.forEach(function(ch, idx) {
          var num = (idx + 1).toString().padStart(2, '0');
          var favClass = ch.isFavorite ? 'ch-fav active' : 'ch-fav';
          html += '<div class="channel-item" onclick="selectChannel(\'' + ch.id + '\', \'' + ch.name.replace(/'/g, "\\'") + '\')">' +
                    '<div class="ch-left">' +
                      '<span class="ch-num">' + num + '</span>' +
                      '<span class="ch-name">' + ch.name + '</span>' +
                    '</div>' +
                    '<span class="' + favClass + '" onclick="event.stopPropagation(); toggleFav(\'' + ch.id + '\')">' +
                      '<svg viewBox="0 0 24 24"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>' +
                    '</span>' +
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

    function loadSettings() {
      fetch('/api/settings')
        .then(function(res) { return res.json(); })
        .then(function(s) {
          if (!s) return;
          // Tampon Modu
          var bm = s.buffer_mode || 'balanced';
          ['ultra', 'balanced', 'shield'].forEach(function(m) {
            var el = document.getElementById('buf-' + m);
            if (el) {
              if (m === bm) el.classList.add('active'); else el.classList.remove('active');
            }
          });
          // Switch'ler
          if (document.getElementById('switch-keep-screen'))
            document.getElementById('switch-keep-screen').checked = s.keep_screen_on !== false;
          if (document.getElementById('switch-mute-bg'))
            document.getElementById('switch-mute-bg').checked = s.mute_on_background !== false;
          if (document.getElementById('switch-auto-boot'))
            document.getElementById('switch-auto-boot').checked = s.auto_start_on_boot !== false;
          if (document.getElementById('switch-cast-enabled'))
            document.getElementById('switch-cast-enabled').checked = s.cast_enabled !== false;
        })
        .catch(function(){});
    }

    function updateSetting(key, val) {
      vibrate();
      if (key === 'buffer_mode') {
        ['ultra', 'balanced', 'shield'].forEach(function(m) {
          var el = document.getElementById('buf-' + m);
          if (el) {
            if (m === val) el.classList.add('active'); else el.classList.remove('active');
          }
        });
        showToast('Tampon: ' + val.toUpperCase());
      } else {
        showToast('Ayar Güncellendi');
      }
      fetch('/api/settings_update?key=' + encodeURIComponent(key) + '&val=' + encodeURIComponent(val), { method: 'POST' }).catch(function(){});
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
