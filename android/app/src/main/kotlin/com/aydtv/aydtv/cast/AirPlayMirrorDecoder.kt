package com.aydtv.aydtv.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * AirPlay Screen Mirroring (Type 110 stream) için donanımsal H.264 çözücü.
 * iPhone/Mac tarafından gönderilen H.264 AVCC karelerini Annex-B NAL formatına dönüştürüp
 * Android MediaCodec donanımı ile çözerek doğrudan Flutter Surface'ine (60 FPS) basar.
 */
object AirPlayMirrorDecoder {
    private const val TAG = "AirPlayMirror"

    private var targetSurface: Surface? = null
    private var flutterTextureId: Long = -1L

    private var mediaCodec: MediaCodec? = null
    private var isCodecRunning = false

    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set

    private var cachedSpsPps: ByteArray? = null
    private var hasFeedKeyframe = false
    private var frameCount = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    var onMirrorStateListener: ((isStarted: Boolean, textureId: Long, width: Int, height: Int) -> Unit)? = null

    /**
     * FlutterEngine'in SurfaceTexture'ından üretilen Surface nesnesini bağlar.
     */
    fun attachSurface(surface: Surface, textureId: Long) {
        synchronized(this) {
            targetSurface = surface
            flutterTextureId = textureId
            Log.i(TAG, "Attached Flutter Surface with textureId: $textureId")
        }
    }

    /**
     * Mirroring paketini işler (Type 1: SPS/PPS, Type 0: Video Slice).
     */
    fun feedPacket(type: Int, option: Int, hBuf: ByteArray, payload: ByteArray) {
        synchronized(this) {
            try {
                when (type) {
                    1 -> {
                        // SPS / PPS Codec Ayar Paketi (Şifresiz)
                        val w = parseDimension(hBuf, 52, 12, 1920)
                        val h = parseDimension(hBuf, 56, 16, 1080)
                        Log.i(TAG, "SPS/PPS Config received: payloadSize=${payload.size}, dimensions=${w}x${h}")

                        currentWidth = w
                        currentHeight = h

                        // AVCC uzunluklarını 00 00 00 01 Annex-B'ye çevir
                        val annexBSpsPps = convertAvccToAnnexB(payload)
                        cachedSpsPps = annexBSpsPps

                        // Codec'i başlat veya yeniden boyutlandır
                        startCodecIfNeeded(w, h)

                        // Flutter UI'a canlı ekran yansıtmanın başladığını bildir
                        mainHandler.post {
                            onMirrorStateListener?.invoke(true, flutterTextureId, w, h)
                        }
                    }
                    0 -> {
                        // Video Frame Paketi (H.264 slice)
                        if (payload.isEmpty()) return

                        // Codec hazır değilse SPS/PPS ile başlatmayı dene
                        if (!isCodecRunning || mediaCodec == null) {
                            startCodecIfNeeded(currentWidth, currentHeight)
                        }

                        val c = mediaCodec ?: return

                        // AVCC to Annex-B dönüşümü
                        val frameAnnexB = convertAvccToAnnexB(payload)

                        // İlk karede veya SPS/PPS henüz beslenmediyse, SPS/PPS'i IDR karesinin önüne ekle
                        val finalFrame = if (!hasFeedKeyframe && cachedSpsPps != null) {
                            val combined = ByteArray(cachedSpsPps!!.size + frameAnnexB.size)
                            System.arraycopy(cachedSpsPps!!, 0, combined, 0, cachedSpsPps!!.size)
                            System.arraycopy(frameAnnexB, 0, combined, cachedSpsPps!!.size, frameAnnexB.size)
                            hasFeedKeyframe = true
                            Log.i(TAG, "Prepended SPS/PPS to first keyframe (total ${combined.size} bytes)")
                            combined
                        } else {
                            frameAnnexB
                        }

                        // MediaCodec input kuyruğuna ver
                        feedToCodec(c, finalFrame)

                        // Çözülen kareleri Surface'e bas
                        drainOutput(c)
                    }
                    2 -> {
                        // Heartbeat / Keep-alive
                    }
                    else -> {
                        Log.d(TAG, "Unknown mirror packet type: $type, size: ${payload.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in feedPacket: ${e.message}", e)
            }
        }
    }

    private fun feedToCodec(codec: MediaCodec, data: ByteArray) {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = codec.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            inBuf.put(data)
            val ptsUs = System.nanoTime() / 1000
            codec.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
            frameCount++
            if (frameCount % 120 == 0L) {
                Log.i(TAG, "Decoded $frameCount frames successfully onto Flutter Surface.")
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) {
                // true: Surface'e çiz ve tamponu serbest bırak
                codec.releaseOutputBuffer(outIdx, true)
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                Log.i(TAG, "MediaCodec output format changed: $newFormat")
            } else {
                break
            }
        }
    }

    private fun startCodecIfNeeded(width: Int, height: Int) {
        val surface = targetSurface ?: run {
            Log.w(TAG, "Target surface is null! Cannot start MediaCodec.")
            return
        }

        if (isCodecRunning && mediaCodec != null) {
            return
        }

        try {
            stopCodecInternal()

            val mime = "video/avc"
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height * 3 / 4, 1024 * 1024))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            // SPS ve PPS formatına doğrudan ekle (varsa)
            cachedSpsPps?.let { spsPps ->
                extractSpsPpsBuffers(spsPps)?.let { (sps, pps) ->
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    Log.i(TAG, "Configured MediaFormat with csd-0 (SPS) and csd-1 (PPS)")
                }
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, surface, null, 0)
            decoder.start()

            mediaCodec = decoder
            isCodecRunning = true
            hasFeedKeyframe = false
            frameCount = 0L

            Log.i(TAG, "MediaCodec hardware decoder started successfully: ${width}x${height} @ 60 FPS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec: ${e.message}", e)
            stopCodecInternal()
        }
    }

    fun stop() {
        synchronized(this) {
            stopCodecInternal()
            cachedSpsPps = null
            hasFeedKeyframe = false
            frameCount = 0L

            mainHandler.post {
                onMirrorStateListener?.invoke(false, flutterTextureId, 0, 0)
            }
            Log.i(TAG, "AirPlay mirror stopped and decoder reset.")
        }
    }

    private fun stopCodecInternal() {
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        isCodecRunning = false
    }

    /**
     * AVCC formatındaki NALU uzunluk öneklerini 00 00 00 01 Annex-B koduna çevirir.
     */
    private fun convertAvccToAnnexB(data: ByteArray): ByteArray {
        val out = data.clone()
        var offset = 0
        while (offset + 4 <= out.size) {
            val naluLen = ((out[offset].toInt() and 0xFF) shl 24) or
                          ((out[offset + 1].toInt() and 0xFF) shl 16) or
                          ((out[offset + 2].toInt() and 0xFF) shl 8) or
                          (out[offset + 3].toInt() and 0xFF)

            // 4 baytlık boyutu 00 00 00 01 start code ile değiştir
            out[offset] = 0x00
            out[offset + 1] = 0x00
            out[offset + 2] = 0x00
            out[offset + 3] = 0x01

            if (naluLen <= 0 || offset + 4 + naluLen > out.size) {
                break
            }
            offset += 4 + naluLen
        }
        return out
    }

    /**
     * SPS ve PPS'i ayrı Byte dizileri olarak ayıklar (MediaFormat csd-0 ve csd-1 için).
     */
    private fun extractSpsPpsBuffers(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val nalIndices = mutableListOf<Int>()
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                nalIndices.add(i)
            }
        }
        if (nalIndices.size >= 2) {
            val sps = data.copyOfRange(nalIndices[0], nalIndices[1])
            val pps = data.copyOfRange(nalIndices[1], data.size)
            return Pair(sps, pps)
        }
        return null
    }

    /**
     * Header'dan Float IEEE 754 little-endian çözünürlük değerini okur.
     */
    private fun parseDimension(buf: ByteArray, offsetPrimary: Int, offsetSecondary: Int, defaultVal: Int): Int {
        val v1 = readFloatLe(buf, offsetPrimary).toInt()
        if (v1 in 200..4096) return v1
        val v2 = readFloatLe(buf, offsetSecondary).toInt()
        if (v2 in 200..4096) return v2
        return defaultVal
    }

    private fun readFloatLe(buf: ByteArray, offset: Int): Float {
        if (offset + 4 > buf.size) return 0f
        val bits = (buf[offset].toInt() and 0xFF) or
                   ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                   ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                   ((buf[offset + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }
}
