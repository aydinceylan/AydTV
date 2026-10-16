package com.aydtv.aydtv

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.aydtv.aydtv/system"
    private val CAST_CHANNEL = "com.aydtv.aydtv/cast"
    private var castServerManager: com.aydtv.aydtv.cast.CastServerManager? = null
    private var mirrorTextureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var mirrorSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKeepScreenOn()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestDefaultTv" -> {
                    try {
                        // Android TV Canlı Yayın Seçim Ekranını Tetikle
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("content://android.media.tv/passthrough/")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        try {
                            val inputIntent = Intent("android.media.tv.action.VIEW_INPUTS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(inputIntent)
                            result.success(true)
                        } catch (e2: Exception) {
                            result.error("UNAVAILABLE", "Live TV picker not available", null)
                        }
                    }
                }
                "openAccessibilitySettings" -> {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.localizedMessage, null)
                    }
                }
                "isAccessibilityEnabled" -> {
                    result.success(isAccessibilityServiceEnabled())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }

        // AirPlay & DLNA / Smart View Kanalı
        val castChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAST_CHANNEL)
        castServerManager = com.aydtv.aydtv.cast.CastServerManager(this, castChannel)

        // Flutter donanımsal GPU Surface Texture oluştur ve AirPlayMirrorDecoder'a bağla
        try {
            val entry = flutterEngine.renderer.createSurfaceTexture()
            mirrorTextureEntry = entry
            val surface = Surface(entry.surfaceTexture())
            mirrorSurface = surface
            com.aydtv.aydtv.cast.AirPlayMirrorDecoder.attachSurface(surface, entry.id())
            com.aydtv.aydtv.cast.AirPlayMirrorDecoder.onMirrorStateListener = { isStarted, textureId, width, height ->
                if (isStarted) {
                    castChannel.invokeMethod("onMirrorStarted", mapOf(
                        "textureId" to textureId,
                        "width" to width,
                        "height" to height
                    ))
                } else {
                    castChannel.invokeMethod("onMirrorStopped", null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to create Flutter SurfaceTexture: ${e.message}")
        }

        castChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getMirrorTextureId" -> {
                    result.success(mirrorTextureEntry?.id() ?: -1L)
                }
                "startServer" -> {
                    val name = call.argument<String>("deviceName") ?: "AydTV"
                    val isPin = call.argument<Boolean>("pinEnabled") ?: false
                    castServerManager?.start(name, isPin)
                    result.success(true)
                }
                "stopServer" -> {
                    castServerManager?.stop()
                    result.success(true)
                }
                "sendRemoteCommand" -> {
                    val action = call.argument<String>("action") ?: "play"
                    val value = call.argument<Any>("value")
                    castServerManager?.sendRemoteCommand(action, value)
                    result.success(true)
                }
                "getLocalIp" -> {
                    result.success(castServerManager?.getLocalIpAddress() ?: "127.0.0.1")
                }
                "updateChannels" -> {
                    val json = call.argument<String>("json") ?: "[]"
                    castServerManager?.updateChannels(json)
                    result.success(true)
                }
                "updateStatus" -> {
                    val json = call.argument<String>("json") ?: "{}"
                    castServerManager?.updateStatus(json)
                    result.success(true)
                }
                "updateSettings" -> {
                    val json = call.argument<String>("json") ?: "{}"
                    castServerManager?.updateSettings(json)
                    result.success(true)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val expectedComponentName = ComponentName(this, TvButtonAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && (enabledComponent == expectedComponentName ||
                    componentNameString.contains(TvButtonAccessibilityService::class.java.simpleName))) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Hata durumunda güvenli dönüş
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        TvButtonAccessibilityService.isAppForeground = true
        setKeepScreenOn()
        try {
            flutterEngine?.dartExecutor?.binaryMessenger?.let {
                MethodChannel(it, CHANNEL).invokeMethod("resumeAudio", null)
            }
        } catch (e: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        TvButtonAccessibilityService.isAppForeground = false
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.abandonAudioFocus(null)
            flutterEngine?.dartExecutor?.binaryMessenger?.let {
                MethodChannel(it, CHANNEL).invokeMethod("forceStopAudio", null)
            }
        } catch (e: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.abandonAudioFocus(null)
            flutterEngine?.dartExecutor?.binaryMessenger?.let {
                MethodChannel(it, CHANNEL).invokeMethod("forceStopAudio", null)
            }
        } catch (e: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setKeepScreenOn()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Kumandadaki TV veya TV_INPUT tuşuna basıldığında AydTV içinde kalmasını sağla
        if (keyCode == KeyEvent.KEYCODE_TV ||
            keyCode == KeyEvent.KEYCODE_TV_INPUT ||
            keyCode == KeyEvent.KEYCODE_GUIDE) {
            return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setKeepScreenOn() {
        // Android TV ve TV Box cihazlarında canlı yayın izlerken ekranın ve sistemin uykuya geçmesini engelle
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    override fun onDestroy() {
        com.aydtv.aydtv.cast.AirPlayMirrorDecoder.stop()
        try {
            mirrorTextureEntry?.release()
        } catch (_: Exception) {}
        mirrorTextureEntry = null
        mirrorSurface = null
        castServerManager?.stop()
        super.onDestroy()
    }
}
