package com.aydtv.aydtv.cast

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay Screen Mirroring (Type 110 stream) için donanımsal H.264 çözücü.
 * iPhone/Mac tarafından gönderilen H.264 AVCC karelerini AES-CTR ile deşifre edip
 * Annex-B NAL formatına dönüştürerek Android MediaCodec donanımı ile çözer ve
 * doğrudan Flutter Surface'ine (60 FPS) basar.
 */
object AirPlayMirrorDecoder {
    private const val TAG = "AirPlayMirror"

    private var targetSurface: Surface? = null
    private var targetSurfaceTexture: SurfaceTexture? = null
    private var flutterTextureId: Long = -1L

    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var isCodecRunning = false

    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set

    private var cachedSpsPps: ByteArray? = null
    private var hasFeedKeyframe = false
    private var frameCount = 0L

    // AES-CTR Mirror Deşifreleme
    private var aesCipher: Cipher? = null

    val hasValidCipher: Boolean
        get() = (aesCipher != null)

    private val mainHandler = Handler(Looper.getMainLooper())
    var onMirrorStateListener: ((isStarted: Boolean, textureId: Long, width: Int, height: Int) -> Unit)? = null

    /**
     * FlutterEngine'in SurfaceTexture'ından üretilen Surface nesnesini bağlar.
     */
    fun attachSurface(surface: Surface, textureId: Long, surfaceTexture: SurfaceTexture? = null) {
        synchronized(this) {
            targetSurface = surface
            targetSurfaceTexture = surfaceTexture
            flutterTextureId = textureId
            Log.i(TAG, "Attached Flutter Surface with textureId: $textureId")
        }
    }

    /**
     * Doğrudan hesaplanmış AES key ve IV ile deşifreleme motorunu başlatır.
     */
    fun initAesDirect(key: ByteArray, iv: ByteArray) {
        synchronized(this) {
            try {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                aesCipher = cipher
                val keyHex = key.joinToString("") { "%02x".format(it) }
                val ivHex = iv.joinToString("") { "%02x".format(it) }
                Log.i(TAG, "AirPlay AES-CTR deşifreleme motoru doğrudan anahtarlarla başarıyla ilklendirildi (key=$keyHex, iv=$ivHex).")
            } catch (e: Exception) {
                Log.w(TAG, "initAesDirect hatası: ${e.message}")
                aesCipher = null
            }
        }
    }

    /**
     * AirPlay AES-CTR deşifreleme oturumunu ilklendirir (RPiPlay standardı).
     */
    fun initAes(aesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long = 1L) {
        synchronized(this) {
            try {
                val md = MessageDigest.getInstance("SHA-512")
                val eaesKey = ByteArray(64)
                md.update(aesKey)
                md.update(ecdhSecret)
                val eaesDigest = md.digest()
                System.arraycopy(eaesDigest, 0, eaesKey, 0, minOf(64, eaesDigest.size))

                val streamIdStr = java.lang.Long.toUnsignedString(streamConnectionId)
                val skeyall = "AirPlayStreamKey$streamIdStr".toByteArray(Charsets.UTF_8)
                val sivall = "AirPlayStreamIV$streamIdStr".toByteArray(Charsets.UTF_8)

                md.reset()
                md.update(skeyall)
                md.update(eaesKey, 0, 16)
                val hash1 = md.digest()

                md.reset()
                md.update(sivall)
                md.update(eaesKey, 0, 16)
                val hash2 = md.digest()

                val decryptKey = hash1.copyOfRange(0, 16)
                val decryptIv = hash2.copyOfRange(0, 16)

                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptKey, "AES"), IvParameterSpec(decryptIv))
                aesCipher = cipher
                Log.i(TAG, "AirPlay AES-CTR deşifreleme motoru başarıyla ilklendirildi (streamId=$streamIdStr).")
            } catch (e: Exception) {
                Log.w(TAG, "initAes hatası: ${e.message}")
                aesCipher = null
            }
        }
    }

    /**
     * Mirroring paketini işler (Type 1: SPS/PPS, Type 0: Video Slice).
     */
    fun feedPacket(type: Int, option: Int, hBuf: ByteArray, payload: ByteArray) {
        synchronized(this) {
            try {
                val pType = type and 0xFF
                when (pType) {
                    1 -> {
                        // SPS / PPS Codec Ayar Paketi (Şifresiz)
                        val w = parseDimension(hBuf, 52, 12, 1920)
                        val h = parseDimension(hBuf, 56, 16, 1080)
                        Log.i(TAG, "SPS/PPS Config received: payloadSize=${payload.size}, dimensions=${w}x${h}")

                        currentWidth = w
                        currentHeight = h

                        // AVCC formatından SPS ve PPS NALU'larını çıkar
                        val extracted = parseAvccConfig(payload)
                        if (extracted != null) {
                            cachedSpsPps = extracted
                        } else {
                            cachedSpsPps = convertAvccToAnnexB(payload)
                        }

                        // Codec'i başlat veya yeniden boyutlandır
                        startCodecIfNeeded(w, h)

                        // Flutter UI'a canlı ekran yansıtmanın başladığını bildir
                        mainHandler.post {
                            onMirrorStateListener?.invoke(true, flutterTextureId, w, h)
                        }
                    }
                    0, 5 -> {
                        // Video Frame Paketi
                        if (payload.isEmpty()) return

                        // UxPlay standardı: 0x05 kilit ekranı binary plist raporu olabilir
                        if (pType == 5 && payload.size > 24000 && !isPlainH264Avcc(payload)) {
                            Log.d(TAG, "AirPlay streaming report trailer packet skipped (size=${payload.size})")
                            return
                        }

                        // Codec hazır değilse başlatmayı dene
                        if (!isCodecRunning || mediaCodec == null) {
                            startCodecIfNeeded(currentWidth, currentHeight)
                        }

                        val c = mediaCodec ?: return

                        // UxPlay standardı: packet[4]==0 && packet[5]==0x10 -> IDR Keyframe
                        val isIdrFromHeader = (hBuf.size > 1 && (hBuf[1].toInt() and 0xFF) == 0x10)

                        // 1. Akıllı deşifreleme: şifresiz mi yoksa AES-CTR ile mi deşifre edilecek?
                        val decryptedPayload = if (aesCipher != null && !isPlainH264Avcc(payload)) {
                            decryptPacket(payload)
                        } else {
                            payload
                        }

                        // 2. UxPlay standardı: AVCC'yi Annex-B'ye çevir ve H.264 NALU bütünlüğünü doğrula
                        val parseResult = convertAvccToAnnexBStrict(decryptedPayload)
                                       ?: if (isAlreadyAnnexB(decryptedPayload)) Pair(decryptedPayload, isIdrNalu(decryptedPayload)) else null

                        if (parseResult == null) {
                            if (frameCount < 10) {
                                val hexRaw = payload.take(minOf(8, payload.size)).joinToString(" ") { "%02x".format(it) }
                                val hexDec = decryptedPayload.take(minOf(8, decryptedPayload.size)).joinToString(" ") { "%02x".format(it) }
                                Log.w(TAG, "Frame #$frameCount geçersiz H.264 NALU (raw=[$hexRaw], dec=[$hexDec], size=${payload.size}), atlandı.")
                            }
                            return
                        }

                        val frameAnnexB = parseResult.first
                        val hasIdrInNalu = parseResult.second
                        val isIdr = isIdrFromHeader || (pType == 5) || hasIdrInNalu

                        // 3. IDR ana karesinde veya ilk karede SPS/PPS'i NAL karesinin önüne ekle
                        val finalFrame = if ((!hasFeedKeyframe || isIdr) && cachedSpsPps != null) {
                            val combined = ByteArray(cachedSpsPps!!.size + frameAnnexB.size)
                            System.arraycopy(cachedSpsPps!!, 0, combined, 0, cachedSpsPps!!.size)
                            System.arraycopy(frameAnnexB, 0, combined, cachedSpsPps!!.size, frameAnnexB.size)
                            hasFeedKeyframe = true
                            combined
                        } else {
                            frameAnnexB
                        }

                        if (frameCount < 5 || isIdr) {
                            val hexPrefix = finalFrame.take(minOf(8, finalFrame.size)).joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "Frame #$frameCount: pType=$pType, isIdr=$isIdr, size=${finalFrame.size}, nalPrefix=[$hexPrefix]")
                        }

                        // 4. MediaCodec input kuyruğuna güvenle ver
                        feedToCodec(c, finalFrame)

                        // 5. Çözülen kareleri doğrudan Surface'e bas
                        drainOutput(c)
                    }
                    2 -> {
                        // Heartbeat / Keep-alive
                    }
                    else -> {
                        Log.d(TAG, "Unknown mirror packet type: $type (pType=$pType), size: ${payload.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in feedPacket: ${e.message}")
            }
        }
    }

    /**
     * H.264 AVCC formatındaki verinin şifresiz ve geçerli olup olmadığını doğrular.
     * H.264 standardına göre ilk NAL byte'ının en yüksek biti (bit 7) forbidden_zero_bit olup daima 0'dır.
     */
    private fun isPlainH264Avcc(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val naluLen = ((data[0].toInt() and 0xFF) shl 24) or
                      ((data[1].toInt() and 0xFF) shl 16) or
                      ((data[2].toInt() and 0xFF) shl 8) or
                      (data[3].toInt() and 0xFF)
        if (naluLen in 1..(data.size - 4)) {
            val header = data[4].toInt() and 0xFF
            val forbidden = header and 0x80
            val type = header and 0x1F
            return (forbidden == 0 && type in 1..23)
        }
        return false
    }

    private fun isAlreadyAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) ||
               (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte())
    }

    private fun isIdrNalu(data: ByteArray): Boolean {
        for (i in 0 until minOf(data.size - 4, 32)) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalType = data[i + 4].toInt() and 0x1F
                if (nalType == 5) return true
            }
        }
        return false
    }

    private fun decryptPacket(payload: ByteArray): ByteArray {
        val cipher = aesCipher ?: return payload
        return try {
            val out = ByteArray(payload.size)
            val len = cipher.update(payload, 0, payload.size, out, 0)
            if (len == payload.size) out else out.copyOfRange(0, len)
        } catch (e: Exception) {
            Log.w(TAG, "AirPlay video deşifreleme hatası: ${e.message}")
            payload
        }
    }

    private fun parseAvccConfig(payload: ByteArray): ByteArray? {
        if (payload.size < 8) return null
        return try {
            val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            if (spsSize <= 0 || payload.size < 8 + spsSize) return null

            val spsNal = ByteArray(4 + spsSize)
            spsNal[0] = 0; spsNal[1] = 0; spsNal[2] = 0; spsNal[3] = 1
            System.arraycopy(payload, 8, spsNal, 4, spsSize)

            val ppsOffset = 8 + spsSize
            var ppsNal: ByteArray? = null
            if (payload.size > ppsOffset + 2) {
                val ppsSize = ((payload[ppsOffset + 1].toInt() and 0xFF) shl 8) or (payload[ppsOffset + 2].toInt() and 0xFF)
                if (ppsSize in 1..(payload.size - (ppsOffset + 3))) {
                    ppsNal = ByteArray(4 + ppsSize)
                    ppsNal[0] = 0; ppsNal[1] = 0; ppsNal[2] = 0; ppsNal[3] = 1
                    System.arraycopy(payload, ppsOffset + 3, ppsNal, 4, ppsSize)
                }
            }

            if (ppsNal != null) {
                val combined = ByteArray(spsNal.size + ppsNal.size)
                System.arraycopy(spsNal, 0, combined, 0, spsNal.size)
                System.arraycopy(ppsNal, 0, combined, spsNal.size, ppsNal.size)
                Log.i(TAG, "Parsed AVCC Config successfully: SPS size=$spsSize, PPS size=${ppsNal.size - 4}")
                combined
            } else {
                spsNal
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AVCC Config: ${e.message}")
            null
        }
    }

    private fun feedToCodec(codec: MediaCodec, data: ByteArray) {
        if (!isCodecRunning || mediaCodec == null) return
        try {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx) ?: return
                inBuf.clear()
                if (data.size > inBuf.remaining()) {
                    Log.w(TAG, "Frame size (${data.size}) exceeds input buffer capacity (${inBuf.remaining()})!")
                    return
                }
                inBuf.put(data)
                val ptsUs = System.nanoTime() / 1000
                codec.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
                frameCount++
                if (frameCount % 60 == 0L) {
                    Log.i(TAG, "Decoded $frameCount frames successfully onto Flutter Surface.")
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaCodec feed warning: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "feedToCodec exception: ${e.message}")
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        if (!isCodecRunning || mediaCodec == null) return
        val info = MediaCodec.BufferInfo()
        while (isCodecRunning) {
            try {
                val outIdx = codec.dequeueOutputBuffer(info, 0)
                if (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "MediaCodec output format changed: $newFormat")
                } else {
                    break
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaCodec drain warning: ${e.message}")
                break
            } catch (e: Exception) {
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

            // MediaTek / MStar TV çipi için 16'nın katına hizalama (Donanımsal çökme önleyici)
            val alignedWidth = ((width + 15) / 16) * 16
            val alignedHeight = ((height + 15) / 16) * 16

            try {
                targetSurfaceTexture?.setDefaultBufferSize(alignedWidth, alignedHeight)
            } catch (_: Exception) {}

            val mime = "video/avc"
            val format = MediaFormat.createVideoFormat(mime, alignedWidth, alignedHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(alignedWidth * alignedHeight * 3 / 4, 1024 * 1024))
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

            Log.i(TAG, "MediaCodec hardware decoder started successfully: ${alignedWidth}x${alignedHeight} @ 60 FPS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec: ${e.message}")
            stopCodecInternal()
        }
    }

    fun stop() {
        synchronized(this) {
            stopCodecInternal()
            cachedSpsPps = null
            hasFeedKeyframe = false
            frameCount = 0L
            aesCipher = null

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
     * UxPlay / RPiPlay standardında geçerlilik kontrolü yapar.
     * Eğer NALU formatı geçersizse veya forbidden_zero_bit 1 ise null döner.
     */
    private fun convertAvccToAnnexBStrict(data: ByteArray): Pair<ByteArray, Boolean>? {
        if (data.size < 5) return null
        val out = data.clone()
        var offset = 0
        var hasIdr = false

        while (offset + 4 <= out.size) {
            val naluLen = ((out[offset].toInt() and 0xFF) shl 24) or
                          ((out[offset + 1].toInt() and 0xFF) shl 16) or
                          ((out[offset + 2].toInt() and 0xFF) shl 8) or
                          (out[offset + 3].toInt() and 0xFF)

            if (naluLen <= 0 || offset + 4 + naluLen > out.size) {
                return null
            }

            // 4 baytlık boyutu 00 00 00 01 start code ile değiştir
            out[offset] = 0x00
            out[offset + 1] = 0x00
            out[offset + 2] = 0x00
            out[offset + 3] = 0x01

            // H.264 forbidden_zero_bit kontrolü (Bit 7 sıfır olmalıdır)
            val header = out[offset + 4].toInt() and 0xFF
            if ((header and 0x80) != 0) {
                return null
            }

            val nalType = header and 0x1F
            if (nalType == 5) {
                hasIdr = true
            }

            offset += 4 + naluLen
        }

        if (offset != out.size) return null
        return Pair(out, hasIdr)
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
