package com.aydtv.aydtv.cast

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
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
    private var activePinCode: String? = null
    private var pinExpiryRunnable: Runnable? = null
    private var activeClientSocket: Socket? = null
    private var isDisplayingPhoto = false
    private val cachedPhotos = mutableMapOf<String, File>()

    init {
        try {
            CastCryptoHelper.loadKeysFromDisk(context)
        } catch (_: Exception) {}
    }

    fun showPin(clientName: String) {
        if (activePinCode == null) {
            activePinCode = String.format("%04d", (1000..9999).random())
            notifyShowPinCode(activePinCode!!, clientName)
        }
        pinExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
        pinExpiryRunnable = Runnable {
            hidePin()
        }
        mainHandler.postDelayed(pinExpiryRunnable!!, 60000)
    }

    fun hidePin() {
        pinExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
        pinExpiryRunnable = null
        if (activePinCode != null) {
            notifyHidePinCode()
            activePinCode = null
        }
    }
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
        if (isRunning) {
            val needMdnsUpdate = (pinEnabled != isPinRequired) || (deviceName != customName)
            deviceName = customName
            pinEnabled = isPinRequired
            if (needMdnsUpdate) {
                unregisterMdnsServices()
                registerMdnsServices()
            }
            return
        }
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

        AirPlayMirrorDecoder.stop()

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

    private fun getAirplayFeatures(): Long = 130367356919L
    private fun getAirplayFeaturesHex(): String = "0x5A7FFFF7,0x1E"

    private fun getAirplayPlistXml(): String {
        val mac = getMacAddress()
        val feats = getAirplayFeatures()
        val status = if (pinEnabled) 580 else 516
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>name</key><string>$deviceName</string>
	<key>deviceID</key><string>$mac</string>
	<key>macAddress</key><string>$mac</string>
	<key>model</key><string>AppleTV5,3</string>
	<key>sourceVersion</key><string>377.17.24.4</string>
	<key>protovers</key><string>1.1</string>
	<key>statusFlags</key><integer>$status</integer>
	<key>features</key><integer>$feats</integer>
	<key>vv</key><integer>2</integer>
	<key>keepAliveLowPower</key><integer>1</integer>
	<key>keepAliveSendStatsAsBody</key><integer>1</integer>
	<key>pi</key><string>b08f5a79-db29-4384-b456-a4784d9e6055</string>
	<key>pk</key><data>${CastCryptoHelper.getPublicKeyBase64()}</data>
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
            val featuresHex = getAirplayFeaturesHex()
            val flagsStr = if (pinEnabled) "0x244" else "0x204"
            val pwStr = if (pinEnabled) "true" else "false"

            val hostAddrs = mutableListOf<InetAddress>()
            try {
                val nifs = NetworkInterface.getNetworkInterfaces()
                while (nifs.hasMoreElements()) {
                    val nif = nifs.nextElement()
                    if (nif.name.contains("wlan") || nif.name.contains("eth")) {
                        val addrs = nif.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val a = addrs.nextElement()
                            if (!a.isLoopbackAddress) hostAddrs.add(a)
                        }
                    }
                }
            } catch (_: Exception) {}
            if (hostAddrs.isEmpty()) {
                try { hostAddrs.add(InetAddress.getByName(localIp)) } catch (_: Exception) {}
            }

            // 1. Evrensel Standart AirPlay Servisi (AppleTV5,3 - Mac & iOS 14/15/16/17/18 Uyumlu)
            val airplayService = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_airplay._tcp"
                port = AIRPLAY_PORT
                setAttribute("deviceid", mac)
                setAttribute("features", featuresHex)
                setAttribute("model", "AppleTV5,3")
                setAttribute("srcvers", "377.17.24.4")
                setAttribute("protovers", "1.1")
                setAttribute("flags", flagsStr)
                setAttribute("vv", "2")
                setAttribute("acl", "0")
                setAttribute("pw", pwStr)
                setAttribute("pk", CastCryptoHelper.getPublicKeyHex())
                setAttribute("pi", "b08f5a79-db29-4384-b456-a4784d9e6055")
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        setHostAddresses(hostAddrs)
                    } catch (_: Throwable) {}
                }
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
                setAttribute("am", "AppleTV5,3")
                setAttribute("vs", "377.17.24.4")
                setAttribute("sf", flagsStr)
                setAttribute("pw", pwStr)
                setAttribute("features", featuresHex)
                setAttribute("pk", CastCryptoHelper.getPublicKeyHex())
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        setHostAddresses(hostAddrs)
                    } catch (_: Throwable) {}
                }
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
                val p0 = rawInput.read()
                if (p0 == -1) break

                // 1. RFC 2326 Interleaved RTP binary packet ($ = 0x24)
                if (p0 == 0x24) {
                    val channelId = rawInput.read()
                    val lenH = rawInput.read()
                    val lenL = rawInput.read()
                    if (channelId == -1 || lenH == -1 || lenL == -1) break
                    val packetLen = ((lenH and 0xFF) shl 8) or (lenL and 0xFF)
                    var skipped = 0
                    val skipBuf = ByteArray(minOf(packetLen, 8192))
                    while (skipped < packetLen) {
                        val toRead = minOf(skipBuf.size, packetLen - skipped)
                        val r = rawInput.read(skipBuf, 0, toRead)
                        if (r == -1) break
                        skipped += r
                    }
                    continue
                }

                // 2. HTTP / RTSP komutu mu yoksa binary mirror frame mi kontrol et
                val p1 = rawInput.read()
                val p2 = rawInput.read()
                val p3 = rawInput.read()
                if (p1 == -1 || p2 == -1 || p3 == -1) break

                val fourBytes = byteArrayOf(p0.toByte(), p1.toByte(), p2.toByte(), p3.toByte())
                val prefixStr = try { String(fourBytes, Charsets.US_ASCII) } catch (_: Exception) { "" }
                val isHttpOrRtsp = prefixStr in listOf(
                    "GET ", "POST", "PUT ", "HEAD", "OPTI", "SETU", "RECO", "TEAR", "ANNO", "PAUS", "FLUS", "GET_", "SET_"
                )

                if (!isHttpOrRtsp) {
                    // Binary mirror stream packet (128-byte header starting with 4-byte payload size)
                    val payloadSize = (p0 and 0xFF) or ((p1 and 0xFF) shl 8) or ((p2 and 0xFF) shl 16) or ((p3 and 0xFF) shl 24)
                    if (payloadSize in 0..20000000) {
                        var headerSkip = 0
                        val hBuf = ByteArray(124)
                        while (headerSkip < 124) {
                            val r = rawInput.read(hBuf, headerSkip, 124 - headerSkip)
                            if (r == -1) break
                            headerSkip += r
                        }
                        if (headerSkip < 124) break

                        // RPiPlay & UxPlay standardı: payload_type = packet[4] & 0xFF
                        val payloadType = hBuf[0].toInt() and 0xFF
                        val payloadOption = (hBuf[2].toInt() and 0xFF) or ((hBuf[3].toInt() and 0xFF) shl 8)

                        // Payload verisini oku
                        val pBuf = ByteArray(payloadSize)
                        var pRead = 0
                        while (pRead < payloadSize) {
                            val r = rawInput.read(pBuf, pRead, payloadSize - pRead)
                            if (r == -1) break
                            pRead += r
                        }
                        if (pRead < payloadSize) break

                        // Donanımsal H.264 çözücüye aktar (Flutter Texture Surface'e 60 FPS basar)
                        AirPlayMirrorDecoder.feedPacket(payloadType, payloadOption, hBuf, pBuf)
                    }
                    continue
                }

                // 3. Normal HTTP / RTSP metin komutu
                val headerBytes = ByteArrayOutputStream()
                headerBytes.write(fourBytes)
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
            if (remaining <= 0 && !isDisplayingPhoto) {
                disconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                disconnectRunnable = Runnable {
                    if (activeConnections.get() <= 0 && !isDisplayingPhoto) {
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
            method in listOf("POST", "GET") && path.startsWith("/pair-pin-start") -> {
                showPin(clientName)
                sendRtspResponse(output, 200, "OK", cseq)
            }
            method in listOf("POST", "GET") && path.startsWith("/pair-setup-pin") -> {
                showPin(clientName)
                val srpSalt = ByteArray(16) { 0x33.toByte() }
                val srpPk = ByteArray(256) { ((it * 17 + 43) % 256).toByte() }
                sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", srpSalt + srpPk)
            }
            method == "POST" && path.startsWith("/pair-setup") -> {
                if (pinEnabled) {
                    showPin(clientName)
                }
                sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", CastCryptoHelper.serverEd25519PubBytes)
            }
            method == "POST" && path.startsWith("/pair-verify") -> {
                val isStep1 = bodyBytes.isNotEmpty() && bodyBytes[0].toInt() == 1
                if (isStep1) {
                    val verifyResp = CastCryptoHelper.handlePairVerifyStep1(bodyBytes, context)
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", verifyResp)
                } else {
                    hidePin()
                    sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", ByteArray(0))
                }
            }
            method == "POST" && path.startsWith("/fp-setup") -> {
                val fpResp = CastCryptoHelper.handleFairPlaySetup(bodyBytes)
                sendRtspResponse(output, 200, "OK", cseq, "application/octet-stream", fpResp)
            }
            method == "SETUP" -> {
                hidePin()
                notifyDeviceConnected(clientName, "airplay")
                notifyMediaStarted("screen", "", "Ekran Yansıtma", clientName, deviceName)

                // AirPlay AES-CTR deşifreleme motorunu FairPlay ve stream anahtarlarıyla başlat
                try {
                    var streamId = path.substringAfterLast("/").toLongOrNull() ?: 1L
                    var ekeyBytes: ByteArray? = null

                    if (bodyBytes.isNotEmpty()) {
                        try {
                            val plist = com.dd.plist.PropertyListParser.parse(bodyBytes) as? com.dd.plist.NSDictionary
                            val ekeyData = plist?.objectForKey("ekey") as? com.dd.plist.NSData
                            if (ekeyData != null) {
                                ekeyBytes = ekeyData.bytes
                                android.util.Log.i("CastServer", "SETUP isteğinde 72-bayt ekey başarıyla ayrıştırıldı.")
                            }
                            val streams = plist?.objectForKey("streams") as? com.dd.plist.NSArray
                            if (streams != null && streams.count() > 0) {
                                val sDict = streams.array[0] as? com.dd.plist.NSDictionary
                                val sIdObj = sDict?.objectForKey("streamConnectionID")
                                if (sIdObj != null) {
                                    val parsedId = sIdObj.toString().toLongOrNull()
                                    if (parsedId != null && parsedId != 0L) {
                                        streamId = parsedId
                                    }
                                }
                            }
                        } catch (pe: Throwable) {
                            android.util.Log.w("CastServer", "SETUP body plist parse hatası: ${pe.message}")
                        }
                    }

                    // FairPlay SAP ile gerçek video AES key ve IV türetimi
                    if (ekeyBytes != null) {
                        val derivedKeys = CastCryptoHelper.deriveMirrorStreamKeys(ekeyBytes, streamId)
                        if (derivedKeys != null) {
                            AirPlayMirrorDecoder.initAesDirect(derivedKeys.first, derivedKeys.second)
                            android.util.Log.i("CastServer", "FairPlay SAP v3 video deşifre motoru tam başarıyla başlatıldı (streamId=$streamId).")
                        } else {
                            val aesKey = CastCryptoHelper.lastAesKey
                            val ecdhSecret = CastCryptoHelper.lastEcdhSecret ?: ByteArray(32)
                            if (aesKey != null) {
                                AirPlayMirrorDecoder.initAes(aesKey, ecdhSecret, streamId)
                            }
                        }
                    } else {
                        val aesKey = CastCryptoHelper.lastAesKey
                        val ecdhSecret = CastCryptoHelper.lastEcdhSecret ?: ByteArray(32)
                        if (aesKey != null) {
                            AirPlayMirrorDecoder.initAes(aesKey, ecdhSecret, streamId)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CastServer", "SETUP AES init hatası: ${e.message}")
                }

                val transport = headers["transport"] ?: "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
                val setupXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>eventPort</key><integer>7000</integer>
	<key>timingPort</key><integer>7000</integer>
	<key>streams</key>
	<array>
		<dict>
			<key>dataPort</key><integer>7000</integer>
			<key>type</key><integer>110</integer>
		</dict>
	</array>
</dict>
</plist>"""
                val plistBytes = setupXml.toByteArray(Charsets.UTF_8)
                sendRtspResponse(
                    output, 200, "OK", cseq,
                    contentType = "text/x-apple-plist+xml",
                    body = plistBytes,
                    extraHeaders = mapOf(
                        "Transport" to transport,
                        "Session" to "1",
                        "Audio-Latency" to "11025"
                    )
                )
            }
            method == "RECORD" -> {
                hidePin()
                sendRtspResponse(
                    output, 200, "OK", cseq,
                    extraHeaders = mapOf(
                        "Session" to "1",
                        "Audio-Latency" to "11025"
                    )
                )
            }
            method == "GET_PARAMETER" -> {
                val resp = if (bodyStr.contains("progress")) {
                    "progress: 0/0/0\r\n"
                } else {
                    "volume: 0.000000\r\n"
                }
                sendRtspResponse(output, 200, "OK", cseq, "text/parameters", resp.toByteArray(Charsets.UTF_8))
            }
            method in listOf("SET_PARAMETER", "FEEDBACK") -> {
                sendRtspResponse(output, 200, "OK", cseq)
            }
            method == "TEARDOWN" -> {
                hidePin()
                AirPlayMirrorDecoder.stop()
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
            path.startsWith("/pair-pin-start") -> {
                showPin(clientName)
                sendHttpResponse(output, 200, "OK", "text/plain", "OK", cseq)
            }
            path.startsWith("/pair-setup-pin") -> {
                showPin(clientName)
                val srpSalt = ByteArray(16) { 0x33.toByte() }
                val srpPk = ByteArray(256) { ((it * 17 + 43) % 256).toByte() }
                sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", srpSalt + srpPk, cseq)
            }
            path.startsWith("/pair-setup") -> {
                if (pinEnabled) {
                    showPin(clientName)
                }
                sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", CastCryptoHelper.serverEd25519PubBytes, cseq)
            }
            path.startsWith("/pair-verify") -> {
                val isStep1 = bodyBytes.isNotEmpty() && bodyBytes[0].toInt() == 1
                if (isStep1) {
                    val verifyResp = CastCryptoHelper.handlePairVerifyStep1(bodyBytes)
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", verifyResp, cseq)
                } else {
                    hidePin()
                    sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", ByteArray(0), cseq)
                }
            }
            path.startsWith("/fp-setup") -> {
                val fpResp = CastCryptoHelper.handleFairPlaySetup(bodyBytes)
                sendHttpBinaryResponse(output, 200, "OK", "application/octet-stream", fpResp, cseq)
            }
            path.startsWith("/play") -> {
                hidePin()
                notifyDeviceConnected(clientName, "airplay")
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
                    val assetAction = headers["x-apple-assetaction"] ?: "display"
                    val assetKey = headers["x-apple-assetkey"] ?: "default"
                    android.util.Log.i("CastServer", "Photo request: action=$assetAction, key=$assetKey, bodySize=${bodyBytes.size}")

                    val photoFile = if (bodyBytes.isNotEmpty()) {
                        val file = File(context.cacheDir, "airplay_photo_${assetKey.hashCode()}.jpg")
                        file.writeBytes(bodyBytes)
                        cachedPhotos[assetKey] = file
                        file
                    } else {
                        cachedPhotos[assetKey] ?: File(context.cacheDir, "airplay_photo.jpg")
                    }

                    if (assetAction != "cache" && photoFile.exists() && photoFile.length() > 0) {
                        isDisplayingPhoto = true
                        notifyDeviceConnected(clientName, "airplay")
                        notifyMediaStarted("photo", photoFile.absolutePath, "AirPlay Fotoğrafı", clientName, deviceName)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CastServer", "Photo error: ${e.message}")
                }
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
            path.startsWith("/stream") -> {
                notifyMediaStarted("screen", "", "Ekran Yansıtma", clientName, deviceName)
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
        sb.append("Server: AirTunes/377.17.24.4\r\n")
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
        sb.append("Server: AirTunes/377.17.24.4\r\n")
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
        sb.append("Server: AirTunes/377.17.24.4\r\n")
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

            var bodyStr = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                bodyStr = String(buf, 0, readTotal)
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
                    val currVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    val volPct = if (maxVol > 0) ((currVol.toFloat() / maxVol) * 100).toInt() else 0
                    val isMuted = if (Build.VERSION.SDK_INT >= 23) (audioManager?.isStreamMute(AudioManager.STREAM_MUSIC) ?: false) else (currVol == 0)
                    val dynamicStatus = try {
                        val obj = org.json.JSONObject(statusJson)
                        obj.put("volume", volPct)
                        obj.put("volumeLevel", currVol)
                        obj.put("volumeMax", maxVol)
                        obj.put("isMuted", isMuted)
                        obj.toString()
                    } catch (_: Exception) {
                        "{\"channel\":\"AydTV\",\"sleep\":0,\"isPlaying\":true,\"volume\":$volPct,\"volumeLevel\":$currVol,\"volumeMax\":$maxVol,\"isMuted\":$isMuted}"
                    }
                    sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", dynamicStatus)
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
                path.startsWith("/api/reorder_channels") -> {
                    val ids = mutableListOf<String>()
                    try {
                        val jsonArray = org.json.JSONArray(bodyStr)
                        for (i in 0 until jsonArray.length()) {
                            ids.add(jsonArray.getString(i))
                        }
                    } catch (_: Exception) {
                        val rawIds = Regex("ids=([^&]+)").find(path)?.groupValues?.get(1) ?: ""
                        if (rawIds.isNotEmpty()) {
                            val decoded = try { URLDecoder.decode(rawIds, "UTF-8") } catch (_: Exception) { rawIds }
                            ids.addAll(decoded.split(",").filter { it.isNotBlank() })
                        }
                    }
                    if (ids.isNotEmpty()) {
                        mainHandler.post {
                            channel.invokeMethod("onRemoteCommand", mapOf("action" to "reorder_channels", "value" to ids))
                        }
                    }
                    sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                }
                path.startsWith("/api/cast_photo") -> {
                    try {
                        val jsonObj = org.json.JSONObject(bodyStr)
                        var base64Data = jsonObj.optString("image", "")
                        if (base64Data.contains(",")) {
                            base64Data = base64Data.substringAfter(",")
                        }
                        if (base64Data.isNotBlank()) {
                            val imgBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            val photoFile = File(context.cacheDir, "web_photo_${System.currentTimeMillis()}.jpg")
                            photoFile.writeBytes(imgBytes)
                            isDisplayingPhoto = true
                            notifyDeviceConnected("Telefon", "web_remote")
                            notifyMediaStarted("photo", photoFile.absolutePath, "Fotoğraf", "Telefon", deviceName)
                            sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                            return
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CastServer", "Web photo error: ${e.message}")
                    }
                    sendHttpResponse(output, 400, "Bad Request", "application/json", "{\"status\":\"error\"}")
                }
                path.startsWith("/api/cast_screen_frame") -> {
                    try {
                        val jsonObj = org.json.JSONObject(bodyStr)
                        var base64Data = jsonObj.optString("frame", "")
                        if (base64Data.contains(",")) {
                            base64Data = base64Data.substringAfter(",")
                        }
                        if (base64Data.isNotBlank()) {
                            val imgBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            val frameFile = File(context.cacheDir, "live_screen_frame.jpg")
                            frameFile.writeBytes(imgBytes)
                            isDisplayingPhoto = true
                            notifyDeviceConnected("Telefon", "web_remote")
                            notifyMediaStarted("photo", frameFile.absolutePath, "📱 Canlı Telefon Ekranı", "Telefon", deviceName)
                            sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                            return
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CastServer", "Web screen frame error: ${e.message}")
                    }
                    sendHttpResponse(output, 400, "Bad Request", "application/json", "{\"status\":\"error\"}")
                }
                path.startsWith("/api/cast_video") -> {
                    try {
                        val jsonObj = org.json.JSONObject(bodyStr)
                        val videoUrl = jsonObj.optString("url", "").trim()
                        if (videoUrl.isNotBlank()) {
                            isDisplayingPhoto = false
                            notifyDeviceConnected("Telefon", "web_remote")
                            notifyMediaStarted("video", videoUrl, "Video", "Telefon", deviceName)
                            sendHttpResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                            return
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CastServer", "Web video error: ${e.message}")
                    }
                    sendHttpResponse(output, 400, "Bad Request", "application/json", "{\"status\":\"error\"}")
                }
                path.startsWith("/api/close_media") -> {
                    isDisplayingPhoto = false
                    notifyMediaStopped()
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
    .sys-btn.muted { background: rgba(255, 82, 82, 0.2) !important; border-color: #FF5252 !important; color: #FF5252 !important; box-shadow: 0 0 14px rgba(255, 82, 82, 0.35) !important; }

    /* Canlı Kanal Banner */
    .live-channel-banner {
      width: 100%; display: flex; align-items: center; justify-content: center; gap: 8px;
      padding: 9px 16px; background: rgba(0, 229, 255, 0.08); border: 1px solid rgba(0, 229, 255, 0.25);
      border-radius: 20px;
    }
    .channel-pulse-dot {
      width: 7px; height: 7px; border-radius: 50%; background: #00E5FF;
      box-shadow: 0 0 8px #00E5FF; animation: pulse 1.8s infinite;
    }
    .channel-live-title { font-size: 11px; font-weight: 700; color: #64748B; letter-spacing: 0.8px; }
    .channel-live-name { font-size: 14px; font-weight: 700; color: #00E5FF; letter-spacing: -0.2px; }

    /* Ses Göstergesi */
    .vol-status-badge {
      display: flex; align-items: center; justify-content: center; gap: 6px;
      padding: 6px 14px; background: rgba(255, 255, 255, 0.04); border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 14px; color: #94A3B8; font-size: 12px; font-weight: 700; letter-spacing: 0.5px;
    }
    .vol-status-badge svg { color: #00E5FF; width: 15px; height: 15px; stroke: currentColor; fill: none; stroke-width: 2; }

    /* Aktif Kanal Vurgusu */
    .channel-item.active-playing {
      background: rgba(0, 229, 255, 0.12) !important;
      border: 1.5px solid #00E5FF !important;
      box-shadow: 0 0 16px rgba(0, 229, 255, 0.25) !important;
    }
    .playing-badge {
      font-size: 9px; font-weight: 800; background: #00E5FF; color: #070B14;
      padding: 2px 7px; border-radius: 8px; margin-left: 8px; letter-spacing: 0.5px;
    }

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
    .timer-btn.active { background: #00E5FF; color: #050B14; font-weight: 700; box-shadow: 0 2px 10px rgba(0,229,255,0.35); }
    .timer-btn.cancel { grid-column: span 3; background: rgba(255,68,68,0.12); border-color: rgba(255,68,68,0.3); color: #FF5252; }
    .timer-btn.cancel:active { background: #FF5252; color: #FFF; }

    /* Kanal Sıralama Sürükle-Bırak */
    .reorder-list { display: flex; flex-direction: column; gap: 8px; max-height: 380px; overflow-y: auto; padding-right: 2px; }
    .reorder-item {
      display: flex; align-items: center; justify-content: space-between; padding: 12px 14px;
      background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07);
      border-radius: 14px; transition: all 0.15s; touch-action: none;
    }
    .reorder-item.dragging { opacity: 0.45; background: rgba(0, 229, 255, 0.15); border-color: #00E5FF; transform: scale(0.98); }
    .reorder-item.drag-over { border-top: 2px solid #00E5FF; background: rgba(0, 229, 255, 0.08); }
    .reorder-handle {
      padding: 6px 8px; cursor: grab; color: #8F9CAE; display: flex; align-items: center; justify-content: center;
      touch-action: none;
    }
    .reorder-handle svg { width: 20px; height: 20px; stroke: currentColor; fill: none; stroke-width: 2.2; }
    .reorder-handle:active { cursor: grabbing; color: #00E5FF; }

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
          <div class="sys-btn" id="btn-mute" onclick="sendKey('mute')">
            <svg id="mute-svg" viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>
            <span id="mute-label">Mute</span>
          </div>
          <div class="sys-btn" onclick="sendKey('settings')">
            <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
            Menü
          </div>
        </div>

        <!-- Aktif İzlenen Canlı Kanal Göstergesi -->
        <div class="live-channel-banner" id="live-channel-banner">
          <div class="channel-pulse-dot"></div>
          <span class="channel-live-title">YAYINDA:</span>
          <span class="channel-live-name" id="live-channel-name">Yükleniyor...</span>
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

        <!-- Canlı Ses Seviyesi Göstergesi -->
        <div class="vol-status-badge" id="vol-status-badge">
          <svg viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"></path></svg>
          <span id="vol-display-text">SES: %--</span>
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

        <div class="setting-row">
          <div class="setting-info">
            <span class="setting-title">AirPlay PIN Kodu Koruması</span>
            <span class="setting-sub">Bağlantıda TV'de 4 haneli PIN kodu ister</span>
          </div>
          <label class="switch">
            <input type="checkbox" id="switch-cast-pin" onchange="updateSetting('cast_pin_enabled', this.checked)">
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
          <div class="timer-btn" id="timer-15" onclick="setSleep(15)">15 Dk</div>
          <div class="timer-btn" id="timer-30" onclick="setSleep(30)">30 Dk</div>
          <div class="timer-btn" id="timer-45" onclick="setSleep(45)">45 Dk</div>
          <div class="timer-btn" id="timer-60" onclick="setSleep(60)">60 Dk</div>
          <div class="timer-btn" id="timer-90" onclick="setSleep(90)">90 Dk</div>
          <div class="timer-btn" id="timer-120" onclick="setSleep(120)">120 Dk</div>
          <div class="timer-btn cancel" id="timer-0" onclick="setSleep(0)">Zamanlayıcıyı Kapat</div>
        </div>
      </div>

      <!-- Kanal Sıralama Kartı (Sürükle & Bırak) -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><line x1="8" y1="6" x2="21" y2="6"></line><line x1="8" y1="12" x2="21" y2="12"></line><line x1="8" y1="18" x2="21" y2="18"></line><line x1="3" y1="6" x2="3.01" y2="6"></line><line x1="3" y1="12" x2="3.01" y2="12"></line><line x1="3" y1="18" x2="3.01" y2="18"></line></svg>
          Kanal Sıralaması (Sürükle & Bırak)
        </div>
        <div class="card-desc">Kanal sırasını değiştirmek için sağdaki tutamaktan basılı tutup sürükleyin. TV anında güncellenir.</div>
        <div id="reorderList" class="reorder-list">
          <div style="text-align:center; color:#64748B; padding:16px;">Kanallar yükleniyor...</div>
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

    <!-- SEKME 3: MEDYA & FOTOĞRAF PAYLAŞ -->
    <div id="tab-media" class="tab-content">
      <!-- Canlı Ekran Yansıtma Kartı (1 Tıkla) -->
      <div class="settings-card" style="border: 1.5px solid rgba(0,229,255,0.4); background: rgba(0,229,255,0.04);">
        <div class="card-head" style="color: #00E5FF;">
          <svg viewBox="0 0 24 24"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
          📲 Telefon Ekranını Canlı Yansıt
        </div>
        <div class="card-desc">Telefonunuzun ekranını canlı olarak televizyona aktarın. AirPlay şifreleme/DRM sorunu olmadan anında çalışır.</div>
        <div id="btnScreenShare" class="sys-btn" style="width:100%; height:52px; background:rgba(0,229,255,0.15); border-color:#00E5FF; color:#00E5FF; font-size:15px; font-weight:700;" onclick="toggleScreenShare()">
          <svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
          Canlı Ekran Paylaşımını Başlat
        </div>
      </div>

      <!-- Fotoğraf Yansıtma Kartı -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>
          Fotoğraf Yansıt
        </div>
        <div class="card-desc">Galerinizden dilediğiniz fotoğrafı seçin, televizyonda anında tam ekran gösterilsin.</div>
        <input type="file" id="mediaPhotoInput" accept="image/*" style="display:none" onchange="handlePhotoUpload(this)">
        <div class="sys-btn" style="width:100%; height:52px; background:rgba(0,229,255,0.12); border-color:#00E5FF; color:#00E5FF; font-size:15px;" onclick="document.getElementById('mediaPhotoInput').click()">
          <svg viewBox="0 0 24 24"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"></path><circle cx="12" cy="13" r="4"></circle></svg>
          Galeriden Fotoğraf Seç & Yansıt
        </div>
        <div id="photoPreviewContainer" style="display:none; width:100%; margin-top:14px; text-align:center;">
          <div style="font-size:12px; color:#00E5FF; font-weight:700; margin-bottom:8px;">TV'DE GÖSTERİLİYOR</div>
          <img id="photoPreview" style="max-width:100%; max-height:180px; border-radius:14px; border:1.5px solid #00E5FF; object-fit:contain; background:#000;">
          <div style="margin-top:12px;">
            <div class="sys-btn danger" style="width:100%; height:46px;" onclick="closeTvMedia()">
              <svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
              Medya Paylaşımını Kapat / TV'ye Dön
            </div>
          </div>
        </div>
      </div>

      <!-- Video / Medya Bağlantısı Yansıtma Kartı -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
          Video / Medya Bağlantısı Yansıt
        </div>
        <div class="card-desc">Herhangi bir video linki (MP4, HLS, Web) yapıştırarak TV'de tam ekran oynatabilirsiniz.</div>
        <div style="width:100%; display:flex; gap:8px;">
          <input type="text" id="videoUrlInput" placeholder="https://ornek.com/video.mp4" style="flex:1; height:46px; border-radius:14px; background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.12); color:#FFF; padding:0 14px; font-size:14px;">
          <div class="sys-btn" style="width:90px; height:46px; background:#00E5FF; color:#070B14; font-weight:700;" onclick="castVideoUrl()">Oynat</div>
        </div>
      </div>

      <!-- AirPlay Bilgilendirme Kartı -->
      <div class="settings-card">
        <div class="card-head">
          <svg viewBox="0 0 24 24"><path d="M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"></path><polygon points="12 15 17 21 7 21 12 15"></polygon></svg>
          Apple AirPlay & DLNA
        </div>
        <div class="card-desc">iPhone, iPad veya Mac cihazınızdaki Fotoğraflar veya Safari uygulamasında AirPlay simgesine dokunup "AydTV" seçerek de doğrudan gönderebilirsiniz.</div>
      </div>
    </div>
  </div>

  <!-- Sabit Alt Menü: Kumanda / Kanallar / Paylaş / Ayarlar -->
  <div class="bottom-nav">
    <div class="nav-item active" onclick="switchTab('remote', this)">
      <svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="4"></rect><circle cx="12" cy="7" r="1"></circle><circle cx="12" cy="11" r="1"></circle><circle cx="12" cy="15" r="1"></circle><circle cx="12" cy="18" r="1"></circle></svg>
      <div>Kumanda</div>
    </div>
    <div class="nav-item" onclick="switchTab('channels', this)">
      <svg viewBox="0 0 24 24"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"></rect><polyline points="17 2 12 7 7 2"></polyline></svg>
      <div>Kanallar</div>
    </div>
    <div class="nav-item" onclick="switchTab('media', this)">
      <svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>
      <div>Paylaş</div>
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

    function updateSleepUI(min) {
      [15, 30, 45, 60, 90, 120].forEach(function(m) {
        var btn = document.getElementById('timer-' + m);
        if (btn) {
          if (min > 0 && Math.abs(min - m) <= 4) {
            btn.classList.add('active');
          } else {
            btn.classList.remove('active');
          }
        }
      });
      var cancelBtn = document.getElementById('timer-0');
      if (cancelBtn) {
        if (min === 0) cancelBtn.style.opacity = '0.5';
        else cancelBtn.style.opacity = '1';
      }
    }

    function setSleep(min) {
      vibrate();
      updateSleepUI(min);
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
      if (tabId === 'channels') {
        if (channelsData.length === 0) loadChannels();
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
          renderReorderList(channelsData);
        })
        .catch(function() {
          document.getElementById('channelList').innerHTML = '<div style="text-align:center; color:#FF6666; padding:20px;">Kanallar yüklenemedi.</div>';
        });
    }

    var activeChannelName = '';
    var activeChannelId = '';

    function renderChannels(list) {
      var html = '';
      if (!list || list.length === 0) {
        html = '<div style="text-align:center; color:#64748B; padding:30px;">Kanal bulunamadı.</div>';
      } else {
        list.forEach(function(ch, idx) {
          var num = (idx + 1).toString().padStart(2, '0');
          var favClass = ch.isFavorite ? 'ch-fav active' : 'ch-fav';
          var isPlaying = (activeChannelId && ch.id === activeChannelId) || (activeChannelName && ch.name.toLowerCase() === activeChannelName.toLowerCase());
          var activeClass = isPlaying ? 'channel-item active-playing' : 'channel-item';
          var badge = isPlaying ? '<span class="playing-badge">YAYINDA</span>' : '';
          html += '<div class="' + activeClass + '" onclick="selectChannel(\'' + ch.id + '\', \'' + ch.name.replace(/'/g, "\\'") + '\')">' +
                    '<div class="ch-left">' +
                      '<span class="ch-num">' + num + '</span>' +
                      '<span class="ch-name">' + ch.name + '</span>' +
                      badge +
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
      activeChannelId = id;
      activeChannelName = name;
      var banner = document.getElementById('live-channel-name');
      if (banner) banner.textContent = name;
      if (channelsData && channelsData.length > 0) renderChannels(channelsData);
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
          if (document.getElementById('switch-cast-pin'))
            document.getElementById('switch-cast-pin').checked = s.cast_pin_enabled === true;

          // TV'deki aktif uyku süresi
          updateSleepUI(s.sleep_minutes || 0);
        })
        .catch(function(){});

      loadReorderList();
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

    // --- SÜRÜKLE & BIRAK KANAL SIRALAMA MOTORU ---
    var draggedIndex = null;

    function loadReorderList() {
      if (channelsData && channelsData.length > 0) {
        renderReorderList(channelsData);
      } else {
        fetch('/api/channels')
          .then(function(res) { return res.json(); })
          .then(function(data) {
            channelsData = data || [];
            renderReorderList(channelsData);
          })
          .catch(function(){});
      }
    }

    function renderReorderList(list) {
      var container = document.getElementById('reorderList');
      if (!container) return;
      if (!list || list.length === 0) {
        container.innerHTML = '<div style="text-align:center; color:#64748B; padding:16px;">Kanal bulunamadı.</div>';
        return;
      }
      var html = '';
      list.forEach(function(ch, idx) {
        var num = (idx + 1).toString().padStart(2, '0');
        html += '<div class="reorder-item" draggable="true" data-index="' + idx + '" data-id="' + ch.id + '">' +
                  '<div class="ch-left">' +
                    '<span class="ch-num">' + num + '</span>' +
                    '<span class="ch-name">' + ch.name + '</span>' +
                  '</div>' +
                  '<div class="reorder-handle">' +
                    '<svg viewBox="0 0 24 24"><line x1="4" y1="8" x2="20" y2="8"></line><line x1="4" y1="16" x2="20" y2="16"></line></svg>' +
                  '</div>' +
                '</div>';
      });
      container.innerHTML = html;
      attachReorderEvents(container);
    }

    function attachReorderEvents(container) {
      var items = container.querySelectorAll('.reorder-item');
      items.forEach(function(item) {
        // Masaüstü Drag & Drop
        item.addEventListener('dragstart', function(e) {
          draggedIndex = parseInt(this.getAttribute('data-index'), 10);
          this.classList.add('dragging');
          e.dataTransfer.effectAllowed = 'move';
        });
        item.addEventListener('dragend', function() {
          this.classList.remove('dragging');
          container.querySelectorAll('.reorder-item').forEach(function(el){ el.classList.remove('drag-over'); });
        });
        item.addEventListener('dragover', function(e) {
          e.preventDefault();
          e.dataTransfer.dropEffect = 'move';
          this.classList.add('drag-over');
        });
        item.addEventListener('dragleave', function() {
          this.classList.remove('drag-over');
        });
        item.addEventListener('drop', function(e) {
          e.preventDefault();
          this.classList.remove('drag-over');
          var targetIndex = parseInt(this.getAttribute('data-index'), 10);
          if (draggedIndex !== null && draggedIndex !== targetIndex) {
            moveChannelItem(draggedIndex, targetIndex);
          }
        });

        // Mobil Dokunmatik (Touch) Sürükleme Desteği
        var handle = item.querySelector('.reorder-handle');
        if (handle) {
          var activeTouchItem = null;
          handle.addEventListener('touchstart', function(e) {
            activeTouchItem = item;
            item.classList.add('dragging');
            vibrate();
          }, { passive: true });

          handle.addEventListener('touchmove', function(e) {
            if (!activeTouchItem) return;
            var touchY = e.touches[0].clientY;
            var touchX = e.touches[0].clientX;
            var elementBelow = document.elementFromPoint(touchX, touchY);
            var targetRow = elementBelow ? elementBelow.closest('.reorder-item') : null;
            container.querySelectorAll('.reorder-item').forEach(function(el){ el.classList.remove('drag-over'); });
            if (targetRow && targetRow !== activeTouchItem) {
              targetRow.classList.add('drag-over');
            }
          }, { passive: true });

          handle.addEventListener('touchend', function(e) {
            if (!activeTouchItem) return;
            activeTouchItem.classList.remove('dragging');
            var touch = e.changedTouches[0];
            var elementBelow = document.elementFromPoint(touch.clientX, touch.clientY);
            var targetRow = elementBelow ? elementBelow.closest('.reorder-item') : null;
            container.querySelectorAll('.reorder-item').forEach(function(el){ el.classList.remove('drag-over'); });
            if (targetRow && targetRow !== activeTouchItem) {
              var fromIdx = parseInt(activeTouchItem.getAttribute('data-index'), 10);
              var toIdx = parseInt(targetRow.getAttribute('data-index'), 10);
              moveChannelItem(fromIdx, toIdx);
            }
            activeTouchItem = null;
          });
        }
      });
    }

    function moveChannelItem(fromIdx, toIdx) {
      vibrate();
      var moved = channelsData.splice(fromIdx, 1)[0];
      channelsData.splice(toIdx, 0, moved);
      renderReorderList(channelsData);
      if (document.getElementById('channelList')) {
        renderChannels(channelsData);
      }
      showToast('TV Sıralaması Güncellendi');
      var ids = channelsData.map(function(c){ return c.id; });
      fetch('/api/reorder_channels', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ids)
      }).catch(function(){});
    }

    // --- CANLI DURUM & AYARLAR SENKRONİZASYONU (1.5 SN PERİYOT) ---
    var isPolling = false;

    function pollStatusAndSettings() {
      if (isPolling) return;
      isPolling = true;

      fetch('/api/status')
        .then(function(res) { return res.json(); })
        .then(function(st) {
          if (!st) return;
          // 1. Canlı İzlenen Kanal
          if (st.channel) {
            var bannerName = document.getElementById('live-channel-name');
            if (bannerName) bannerName.textContent = st.channel;
            if (activeChannelName !== st.channel || activeChannelId !== (st.channelId || '')) {
              activeChannelName = st.channel;
              activeChannelId = st.channelId || '';
              if (channelsData && channelsData.length > 0) {
                renderChannels(channelsData);
              }
            }
          }
          // 2. Ses Seviyesi
          if (st.volume !== undefined) {
            var volText = document.getElementById('vol-display-text');
            if (volText) volText.textContent = 'SES: %' + st.volume;
          }
          // 3. Mute Durumu
          var muteBtn = document.getElementById('btn-mute');
          var muteText = document.getElementById('mute-label');
          if (muteBtn && muteText) {
            if (st.isMuted) {
              muteBtn.classList.add('muted');
              muteText.textContent = 'Sessizde';
            } else {
              muteBtn.classList.remove('muted');
              muteText.textContent = 'Mute';
            }
          }
        })
        .catch(function(){});

      // 4. TV Ayarları Canlı Senkronizasyonu
      fetch('/api/settings')
        .then(function(res) { return res.json(); })
        .then(function(s) {
          if (!s) return;
          var bm = s.buffer_mode || 'balanced';
          ['ultra', 'balanced', 'shield'].forEach(function(m) {
            var el = document.getElementById('buf-' + m);
            if (el) {
              if (m === bm) el.classList.add('active'); else el.classList.remove('active');
            }
          });
          if (document.getElementById('switch-keep-screen'))
            document.getElementById('switch-keep-screen').checked = s.keep_screen_on !== false;
          if (document.getElementById('switch-mute-bg'))
            document.getElementById('switch-mute-bg').checked = s.mute_on_background !== false;
          if (document.getElementById('switch-auto-boot'))
            document.getElementById('switch-auto-boot').checked = s.auto_start_on_boot !== false;
          if (document.getElementById('switch-cast-enabled'))
            document.getElementById('switch-cast-enabled').checked = s.cast_enabled !== false;
          if (document.getElementById('switch-cast-pin'))
            document.getElementById('switch-cast-pin').checked = s.cast_pin_enabled === true;
          updateSleepUI(s.sleep_minutes || 0);
        })
        .catch(function(){})
        .finally(function() {
          isPolling = false;
        });
    }

    // --- MEDYA & FOTOĞRAF PAYLAŞMA FONKSİYONLARI ---
    function handlePhotoUpload(input) {
      if (!input.files || !input.files[0]) return;
      var file = input.files[0];
      vibrate();
      showToast('Fotoğraf TV\'ye aktarılıyor...');
      var reader = new FileReader();
      reader.onload = function(e) {
        var base64 = e.target.result;
        var preview = document.getElementById('photoPreview');
        var container = document.getElementById('photoPreviewContainer');
        if (preview && container) {
          preview.src = base64;
          container.style.display = 'block';
        }
        fetch('/api/cast_photo', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ image: base64 })
        }).then(function(res) {
          return res.json();
        }).then(function(data) {
          if (data && data.status === 'ok') {
            showToast('Fotoğraf TV\'de Açıldı');
          } else {
            showToast('Fotoğraf aktarılamadı');
          }
        }).catch(function() {
          showToast('Bağlantı hatası');
        });
      };
      reader.readAsDataURL(file);
    }

    function castVideoUrl() {
      var input = document.getElementById('videoUrlInput');
      if (!input || !input.value.trim()) {
        showToast('Lütfen geçerli bir video linki girin');
        return;
      }
      var url = input.value.trim();
      vibrate();
      showToast('Video TV\'ye aktarılıyor...');
      fetch('/api/cast_video', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: url })
      }).then(function(res) {
        return res.json();
      }).then(function(data) {
        if (data && data.status === 'ok') {
          showToast('Video TV\'de oynatılıyor');
          input.value = '';
        } else {
          showToast('Video başlatılamadı');
        }
      }).catch(function() {
        showToast('Bağlantı hatası');
      });
    }

    // --- CANLI EKRAN YANSITMA (BROWSER SCREEN SHARING) ---
    var screenStream = null;
    var screenInterval = null;
    var isScreenSharing = false;

    async function toggleScreenShare() {
      var btn = document.getElementById('btnScreenShare');
      if (isScreenSharing) {
        stopScreenShare();
        return;
      }
      try {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
          showToast('iPhone ekranı için Denetim Merkezi > Ekran Yansıtma seçin');
          alert('📱 iPhone Ekran Yansıtma Rehberi:\n\nApple iOS Safari güvenlik kuralı nedeniyle tarayıcıdan ekran kaydına izin vermez.\n\nTelefonunuzun canlı ekranını televizyona yansıtmak için:\n1. iPhone ekranının sağ üst köşesinden aşağı kaydırıp Denetim Merkezi\'ni açın.\n2. İki ekran şeklindeki [Ekran Yansıtma] simgesine dokunun.\n3. Listeden [AydTV] seçin.\n\nFotoğraflarınızı ise aşağıdaki "Galeriden Fotoğraf Seç & Yansıt" butonuyla doğrudan televizyonda tam ekran açabilirsiniz.');
          return;
        }
        showToast('Ekran paylaşımı izni bekleniyor...');
        screenStream = await navigator.mediaDevices.getDisplayMedia({
          video: { cursor: "always", displaySurface: "monitor" },
          audio: false
        });
        isScreenSharing = true;
        if (btn) {
          btn.innerHTML = '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12"></rect></svg> Ekran Paylaşımını Durdur';
          btn.style.background = 'rgba(255, 59, 48, 0.2)';
          btn.style.borderColor = '#FF3B30';
          btn.style.color = '#FF3B30';
        }
        var video = document.createElement('video');
        video.srcObject = screenStream;
        video.play();
        var canvas = document.createElement('canvas');
        var ctx = canvas.getContext('2d');

        screenStream.getVideoTracks()[0].onended = function() {
          stopScreenShare();
        };

        var sendingFrame = false;
        screenInterval = setInterval(function() {
          if (!isScreenSharing || video.readyState < 2 || sendingFrame) return;
          var maxDim = 1280;
          var w = video.videoWidth || 720;
          var h = video.videoHeight || 1280;
          var scale = Math.min(1.0, maxDim / Math.max(w, h));
          canvas.width = Math.round(w * scale);
          canvas.height = Math.round(h * scale);
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          var jpeg = canvas.toDataURL('image/jpeg', 0.65);
          sendingFrame = true;
          fetch('/api/cast_screen_frame', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ frame: jpeg })
          }).then(function(){ sendingFrame = false; }).catch(function(){ sendingFrame = false; });
        }, 200);

        showToast('Canlı Ekran TV\'ye Yansıtılıyor');
      } catch (err) {
        showToast('Ekran paylaşımı başlatılamadı');
        stopScreenShare();
      }
    }

    function stopScreenShare() {
      isScreenSharing = false;
      if (screenInterval) {
        clearInterval(screenInterval);
        screenInterval = null;
      }
      if (screenStream) {
        screenStream.getTracks().forEach(function(t) { t.stop(); });
        screenStream = null;
      }
      var btn = document.getElementById('btnScreenShare');
      if (btn) {
        btn.innerHTML = '<svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg> Canlı Ekran Paylaşımını Başlat';
        btn.style.background = 'rgba(0, 229, 255, 0.15)';
        btn.style.borderColor = '#00E5FF';
        btn.style.color = '#00E5FF';
      }
      closeTvMedia();
    }

    function closeTvMedia() {
      vibrate();
      fetch('/api/close_media', { method: 'POST' }).then(function() {
        showToast('TV Yayınına dönüldü');
        var container = document.getElementById('photoPreviewContainer');
        if (container) container.style.display = 'none';
      }).catch(function(){});
    }

    setInterval(pollStatusAndSettings, 1500);
    pollStatusAndSettings();
    loadChannels();
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
                isDisplayingPhoto = false
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

    private fun notifyShowPinCode(pin: String, clientName: String) {
        mainHandler.post {
            channel.invokeMethod("onShowPinCode", mapOf("pin" to pin, "clientName" to clientName))
        }
    }

    private fun notifyHidePinCode() {
        mainHandler.post {
            channel.invokeMethod("onHidePinCode", null)
        }
    }
}
