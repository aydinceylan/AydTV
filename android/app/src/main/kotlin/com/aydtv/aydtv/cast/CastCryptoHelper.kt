package com.aydtv.aydtv.cast

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay 2 Pair-Setup, Pair-Verify ve FairPlay el sıkışma kriptografi motoru.
 * Apple TV standardına uygun Ed25519, X25519 (Curve25519) ve AES-128-CTR uygular.
 */
object CastCryptoHelper {
    private const val TAG = "CastCryptoHelper"

    // Deterministik ama güvenli Ed25519 sunucu tohumu (32 bayt)
    private val SERVER_SEED = byteArrayOf(
        0x79.toByte(), 0x75.toByte(), 0x61.toByte(), 0x7c.toByte(), 0xa5.toByte(), 0x55.toByte(), 0x1d.toByte(), 0xe9.toByte(),
        0x40.toByte(), 0x1e.toByte(), 0xaf.toByte(), 0x55.toByte(), 0x6c.toByte(), 0xac.toByte(), 0xe5.toByte(), 0x82.toByte(),
        0x34.toByte(), 0x5c.toByte(), 0xe2.toByte(), 0x6a.toByte(), 0x2b.toByte(), 0x79.toByte(), 0xbe.toByte(), 0xaa.toByte(),
        0x15.toByte(), 0xad.toByte(), 0x45.toByte(), 0xbb.toByte(), 0x32.toByte(), 0x2c.toByte(), 0xa7.toByte(), 0x12.toByte()
    )

    val serverEd25519PrivateKey: Ed25519PrivateKeyParameters = Ed25519PrivateKeyParameters(SERVER_SEED, 0)
    val serverEd25519PublicKey: Ed25519PublicKeyParameters = serverEd25519PrivateKey.generatePublicKey()
    val serverEd25519PubBytes: ByteArray = serverEd25519PublicKey.encoded

    init {
        try {
            System.loadLibrary("aydtv_crypto")
            Log.i(TAG, "Native aydtv_crypto kütüphanesi başarıyla yüklendi.")
        } catch (e: Throwable) {
            Log.w(TAG, "Native aydtv_crypto kütüphanesi yüklenemedi: ${e.message}")
        }
    }

    @JvmStatic
    external fun decryptPlayFair(keymsg: ByteArray, ekey: ByteArray): ByteArray?

    // Canlı ekran (Type 110 H.264 stream) AES-CTR deşifreleme için ortak anahtarlar
    @Volatile
    var lastSharedSecret: ByteArray? = null
    @Volatile
    var lastAesKey: ByteArray? = null
    @Volatile
    var lastEcdhSecret: ByteArray? = null
    @Volatile
    var lastFpKeymsg: ByteArray? = null

    fun saveKeysToDisk(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("airplay_crypto", android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                lastSharedSecret?.let { putString("shared_secret", Base64.encodeToString(it, Base64.NO_WRAP)) }
                lastAesKey?.let { putString("aes_key", Base64.encodeToString(it, Base64.NO_WRAP)) }
                lastEcdhSecret?.let { putString("ecdh_secret", Base64.encodeToString(it, Base64.NO_WRAP)) }
                apply()
            }
            Log.i(TAG, "AirPlay kripto anahtarları diske kalıcı kaydedildi.")
        } catch (_: Exception) {}
    }

    fun loadKeysFromDisk(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("airplay_crypto", android.content.Context.MODE_PRIVATE)
            val ss = prefs.getString("shared_secret", null)
            val ak = prefs.getString("aes_key", null)
            val es = prefs.getString("ecdh_secret", null)
            if (ss != null) lastSharedSecret = Base64.decode(ss, Base64.NO_WRAP)
            if (ak != null) lastAesKey = Base64.decode(ak, Base64.NO_WRAP)
            if (es != null) lastEcdhSecret = Base64.decode(es, Base64.NO_WRAP)
            if (lastAesKey != null) {
                Log.i(TAG, "AirPlay kripto anahtarları diskten başarıyla yüklendi.")
            }
        } catch (_: Exception) {}
    }

    /**
     * mDNS TXT kaydında kullanılan 32-baytlık public key hex string
     */
    fun getPublicKeyHex(): String {
        return serverEd25519PubBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * XML Plist yanıtında kullanılan Base64 public key string
     */
    fun getPublicKeyBase64(): String {
        return Base64.encodeToString(serverEd25519PubBytes, Base64.NO_WRAP)
    }

    /**
     * POST /pair-verify Step 1 el sıkışmasını yönetir.
     * İstemciden gelen Curve25519 public key ile shared secret üretir,
     * Ed25519 ile imzalar ve AES-128-CTR ile şifreleyip 96-baytlık yanıt döner.
     */
    fun handlePairVerifyStep1(clientPayload: ByteArray, context: android.content.Context? = null): ByteArray {
        try {
            val clientEcdhPubBytes: ByteArray
            if (clientPayload.size >= 68 && clientPayload[0].toInt() == 1) {
                // Standart AirPlay formatı: [0..3]=0x01, [4..35]=ECDH PK, [36..67]=Ed25519 PK
                clientEcdhPubBytes = clientPayload.copyOfRange(4, 36)
            } else if (clientPayload.size >= 64) {
                // Başlıksız ham format
                clientEcdhPubBytes = clientPayload.copyOfRange(0, 32)
            } else {
                Log.w(TAG, "Beklenmeyen pair-verify Step 1 veri boyutu: ${clientPayload.size}")
                return generateFallbackVerifyResponse()
            }

            // 1. Sunucu tarafında tek kullanımlık X25519 keypair üret
            val x25519Gen = X25519KeyPairGenerator()
            x25519Gen.init(KeyGenerationParameters(SecureRandom(), 256))
            val serverEcdhPair = x25519Gen.generateKeyPair()
            val serverEcdhPriv = serverEcdhPair.private as X25519PrivateKeyParameters
            val serverEcdhPub = serverEcdhPair.public as X25519PublicKeyParameters
            val serverEcdhPubBytes = serverEcdhPub.encoded // 32 bayt

            // 2. X25519 Diffie-Hellman ortak gizli anahtarını (shared secret) hesapla
            val clientEcdhPub = X25519PublicKeyParameters(clientEcdhPubBytes, 0)
            val agreement = X25519Agreement()
            agreement.init(serverEcdhPriv)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(clientEcdhPub, sharedSecret, 0) // 32 bayt

            // 3. İmzalanacak mesajı oluştur: serverEcdhPub (32) + clientEcdhPub (32) = 64 bayt
            val sigMsg = ByteArray(64)
            System.arraycopy(serverEcdhPubBytes, 0, sigMsg, 0, 32)
            System.arraycopy(clientEcdhPubBytes, 0, sigMsg, 32, 32)

            // 4. Ed25519 sunucu özel anahtarıyla imzala -> 64 bayt imza
            val signer = Ed25519Signer()
            signer.init(true, serverEd25519PrivateKey)
            signer.update(sigMsg, 0, sigMsg.size)
            val signature = signer.generateSignature() // 64 bayt

            // 5. Shared secret üzerinden SHA-512 ile AES-128 Key ve IV türet
            val mdKey = MessageDigest.getInstance("SHA-512")
            mdKey.update("Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8))
            mdKey.update(sharedSecret)
            val aesKey = mdKey.digest().copyOfRange(0, 16)

            val mdIv = MessageDigest.getInstance("SHA-512")
            mdIv.update("Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8))
            mdIv.update(sharedSecret)
            val aesIv = mdIv.digest().copyOfRange(0, 16)

            // 6. İmzayı AES-128-CTR ile şifrele
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val encryptedSignature = cipher.doFinal(signature) // 64 bayt

            // 7. 96-baytlık yanıtı paketle: serverEcdhPub (32) + encryptedSignature (64)
            val verifyResp = ByteArray(96)
            System.arraycopy(serverEcdhPubBytes, 0, verifyResp, 0, 32)
            System.arraycopy(encryptedSignature, 0, verifyResp, 32, 64)

            // Canlı ekran (Type 110 stream) için anahtarları kaydet
            lastSharedSecret = sharedSecret
            lastAesKey = aesKey
            lastEcdhSecret = clientEcdhPubBytes
            context?.let { saveKeysToDisk(it) }

            Log.i(TAG, "Pair-Verify Step 1 kriptografik el sıkışma başarıyla tamamlandı (96 bayt).")
            return verifyResp
        } catch (e: Exception) {
            Log.e(TAG, "Pair-Verify Step 1 hesaplama hatası: ${e.message}", e)
            return generateFallbackVerifyResponse()
        }
    }

    private fun generateFallbackVerifyResponse(): ByteArray {
        val verifyResp = ByteArray(96)
        for (i in 0 until 32) verifyResp[i] = ((i * 13 + 7) % 256).toByte()
        for (i in 32 until 96) verifyResp[i] = ((i * 31 + 41) % 256).toByte()
        return verifyResp
    }

    // Apple FairPlay SAP v3.1 / PlayFair Gerçek Sertifika Paketleri (142 bayt)
    private val FP_REPLY_MODE_0 = byteArrayOf(
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x82.toByte(), 0x02, 0x00, 0x0f, 0x9f.toByte(),
        0x3f, 0x9e.toByte(), 0x0a, 0x25, 0x21, 0xdb.toByte(), 0xdf.toByte(), 0x31, 0x2a, 0xb2.toByte(), 0xbf.toByte(), 0xb2.toByte(),
        0x9e.toByte(), 0x8d.toByte(), 0x23, 0x2b, 0x63, 0x76, 0xa8.toByte(), 0xc8.toByte(), 0x18, 0x70, 0x1d, 0x22,
        0xae.toByte(), 0x93.toByte(), 0xd8.toByte(), 0x27, 0x37, 0xfe.toByte(), 0xaf.toByte(), 0x9d.toByte(), 0xb4.toByte(), 0xfd.toByte(),
        0xf4.toByte(), 0x1c, 0x2d, 0xba.toByte(), 0x9d.toByte(), 0x1f, 0x49, 0xca.toByte(), 0xaa.toByte(), 0xbf.toByte(), 0x65,
        0x91.toByte(), 0xac.toByte(), 0x1f, 0x7b, 0xc6.toByte(), 0xf7.toByte(), 0xe0.toByte(), 0x66, 0x3d, 0x21, 0xaf.toByte(),
        0xe0.toByte(), 0x15, 0x65, 0x95.toByte(), 0x3e, 0xab.toByte(), 0x81.toByte(), 0xf4.toByte(), 0x18, 0xce.toByte(), 0xed.toByte(),
        0x09, 0x5a, 0xdb.toByte(), 0x7c, 0x3d, 0x0e, 0x25, 0x49, 0x09, 0xa7.toByte(), 0x98.toByte(), 0x31, 0xd4.toByte(),
        0x9c.toByte(), 0x39, 0x82.toByte(), 0x97.toByte(), 0x34, 0x34, 0xfa.toByte(), 0xcb.toByte(), 0x42, 0xc6.toByte(), 0x3a,
        0x1c, 0xd9.toByte(), 0x11, 0xa6.toByte(), 0xfe.toByte(), 0x94.toByte(), 0x1a, 0x8a.toByte(), 0x6d, 0x4a, 0x74,
        0x3b, 0x46, 0xc3.toByte(), 0xa7.toByte(), 0x64, 0x9e.toByte(), 0x44, 0xc7.toByte(), 0x89.toByte(), 0x55, 0xe4.toByte(),
        0x9d.toByte(), 0x81.toByte(), 0x55, 0x00, 0x95.toByte(), 0x49, 0xc4.toByte(), 0xe2.toByte(), 0xf7.toByte(), 0xa3.toByte(),
        0xf6.toByte(), 0xd5.toByte(), 0xba.toByte()
    )

    private val FP_REPLY_MODE_1 = byteArrayOf(
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x82.toByte(), 0x02, 0x01, 0xcf.toByte(), 0x32,
        0xa2.toByte(), 0x57, 0x14, 0xb2.toByte(), 0x52, 0x4f, 0x8a.toByte(), 0xa0.toByte(), 0xad.toByte(), 0x7a, 0xf1.toByte(), 0x64,
        0xe3.toByte(), 0x7b, 0xcf.toByte(), 0x44, 0x24, 0xe2.toByte(), 0x00, 0x04, 0x7e, 0xfc.toByte(), 0x0a, 0xd6.toByte(), 0x7a,
        0xfc.toByte(), 0xd9.toByte(), 0x5d, 0xed.toByte(), 0x1c, 0x27, 0x30, 0xbb.toByte(), 0x59, 0x1b, 0x96.toByte(), 0x2e,
        0xd6.toByte(), 0x3a, 0x9c.toByte(), 0x4d, 0xed.toByte(), 0x88.toByte(), 0xba.toByte(), 0x8f.toByte(), 0xc7.toByte(), 0x8d.toByte(), 0xe6.toByte(),
        0x4d, 0x91.toByte(), 0xcc.toByte(), 0xfd.toByte(), 0x5c, 0x7b, 0x56, 0xda.toByte(), 0x88.toByte(), 0xe3.toByte(), 0x1f,
        0x5c, 0xce.toByte(), 0xaf.toByte(), 0xc7.toByte(), 0x43, 0x19, 0x95.toByte(), 0xa0.toByte(), 0x16, 0x65, 0xa5.toByte(), 0x4e,
        0x19, 0x39, 0xd2.toByte(), 0x5b, 0x94.toByte(), 0xdb.toByte(), 0x64, 0xb9.toByte(), 0xe4.toByte(), 0x5d, 0x8d.toByte(), 0x06,
        0x3e, 0x1e, 0x6a, 0xf0.toByte(), 0x7e, 0x96.toByte(), 0x56, 0x16, 0x2b, 0x0e, 0xfa.toByte(), 0x40, 0x42, 0x75,
        0xea.toByte(), 0x5a, 0x44, 0xd9.toByte(), 0x59, 0x1c, 0x72, 0x56, 0xb9.toByte(), 0xfb.toByte(), 0xe6.toByte(), 0x51, 0x38,
        0x98.toByte(), 0xb8.toByte(), 0x02, 0x27, 0x72, 0x19, 0x88.toByte(), 0x57, 0x16, 0x50, 0x94.toByte(), 0x2a, 0xd9.toByte(),
        0x46, 0x68, 0x8a.toByte()
    )

    private val FP_REPLY_MODE_2 = byteArrayOf(
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x82.toByte(), 0x02, 0x02, 0xc1.toByte(), 0x69,
        0xa3.toByte(), 0x52, 0xee.toByte(), 0xed.toByte(), 0x35, 0xb1.toByte(), 0x8c.toByte(), 0xdd.toByte(), 0x9c.toByte(), 0x58, 0xd6.toByte(), 0x4f,
        0x16, 0xc1.toByte(), 0x51, 0x9a.toByte(), 0x89.toByte(), 0xeb.toByte(), 0x53, 0x17, 0xbd.toByte(), 0x0d, 0x43, 0x36, 0xcd.toByte(),
        0x68, 0xf6.toByte(), 0x38, 0xff.toByte(), 0x9d.toByte(), 0x01, 0x6a, 0x5b, 0x52, 0xb7.toByte(), 0xfa.toByte(), 0x92.toByte(), 0x16,
        0xb2.toByte(), 0xb6.toByte(), 0x54, 0x82.toByte(), 0xc7.toByte(), 0x84.toByte(), 0x44, 0x11, 0x81.toByte(), 0x21, 0xa2.toByte(), 0xc7.toByte(),
        0xfe.toByte(), 0xd8.toByte(), 0x3d, 0xb7.toByte(), 0x11, 0x9e.toByte(), 0x91.toByte(), 0x82.toByte(), 0xaa.toByte(), 0xd7.toByte(), 0xd1.toByte(),
        0x8c.toByte(), 0x70, 0x63, 0xe2.toByte(), 0xa4.toByte(), 0x57, 0x55, 0x59, 0x10, 0xaf.toByte(), 0x9e.toByte(), 0x0e,
        0xfc.toByte(), 0x76, 0x34, 0x7d, 0x16, 0x40, 0x43, 0x80.toByte(), 0x7f, 0x58, 0x1e, 0xe4.toByte(), 0xfb.toByte(), 0xe4.toByte(),
        0x2c, 0xa9.toByte(), 0xde.toByte(), 0xdc.toByte(), 0x1b, 0x5e, 0xb2.toByte(), 0xa3.toByte(), 0xaa.toByte(), 0x3d, 0x2e,
        0xcd.toByte(), 0x59, 0xe7.toByte(), 0xee.toByte(), 0xe7.toByte(), 0x0b, 0x36, 0x29, 0xf2.toByte(), 0x2a, 0xfd.toByte(), 0x16,
        0x1d, 0x87.toByte(), 0x73, 0x53, 0xdd.toByte(), 0xb9.toByte(), 0x9a.toByte(), 0xdc.toByte(), 0x8e.toByte(), 0x07, 0x00, 0x6e,
        0x56, 0xf8.toByte(), 0x50, 0xce.toByte()
    )

    private val FP_REPLY_MODE_3 = byteArrayOf(
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x82.toByte(), 0x02, 0x03, 0x90.toByte(), 0x01,
        0xe1.toByte(), 0x72, 0x7e, 0x0f, 0x57, 0xf9.toByte(), 0xf5.toByte(), 0x88.toByte(), 0x0d, 0xb1.toByte(), 0x04, 0xa6.toByte(),
        0x25, 0x7a, 0x23, 0xf5.toByte(), 0xcf.toByte(), 0xff.toByte(), 0x1a, 0xbb.toByte(), 0xe1.toByte(), 0xe9.toByte(), 0x30, 0x45,
        0x25, 0x1a, 0xfb.toByte(), 0x97.toByte(), 0xeb.toByte(), 0x9f.toByte(), 0xc0.toByte(), 0x01, 0x1e, 0xbe.toByte(), 0x0f, 0x3a,
        0x81.toByte(), 0xdf.toByte(), 0x5b, 0x69, 0x1d, 0x76, 0xac.toByte(), 0xb2.toByte(), 0xf7.toByte(), 0xa5.toByte(), 0xc7.toByte(),
        0x08, 0xe3.toByte(), 0xd3.toByte(), 0x28, 0xf5.toByte(), 0x6b, 0xb3.toByte(), 0x9d.toByte(), 0xbd.toByte(), 0xe5.toByte(), 0xf2.toByte(),
        0x9c.toByte(), 0x8a.toByte(), 0x17, 0xf4.toByte(), 0x81.toByte(), 0x48, 0x7e, 0x3a, 0xe8.toByte(), 0x63, 0xc6.toByte(), 0x78,
        0x32, 0x54, 0x22, 0xe6.toByte(), 0xf7.toByte(), 0x8e.toByte(), 0x16, 0x6d, 0x18, 0xaa.toByte(), 0x7f, 0xd6.toByte(), 0x36,
        0x25, 0x8b.toByte(), 0xce.toByte(), 0x28, 0x72, 0x6f, 0x66, 0x1f, 0x73, 0x88.toByte(), 0x93.toByte(), 0xce.toByte(), 0x44,
        0x31, 0x1e, 0x4b, 0xe6.toByte(), 0xc0.toByte(), 0x53, 0x51, 0x93.toByte(), 0xe5.toByte(), 0xef.toByte(), 0x72, 0xe8.toByte(),
        0x68, 0x62, 0x33, 0x72, 0x9c.toByte(), 0x22, 0x7d, 0x82.toByte(), 0x0c, 0x99.toByte(), 0x94.toByte(), 0x45, 0xd8.toByte(),
        0x92.toByte(), 0x46, 0xc8.toByte(), 0xc3.toByte(), 0x59
    )

    /**
     * FairPlay Phase 1 ve Phase 2 el sıkışmasını yönetir.
     * RFC / Apple FairPlay PlayFair standardında 142 ve 32 baytlık sertifika paketleri döner.
     */
    fun handleFairPlaySetup(bodyBytes: ByteArray): ByteArray {
        return if (bodyBytes.size <= 16) {
            val mode = if (bodyBytes.size > 14) (bodyBytes[14].toInt() and 0xFF) else 0
            when (mode) {
                0 -> FP_REPLY_MODE_0.copyOf()
                1 -> FP_REPLY_MODE_1.copyOf()
                2 -> FP_REPLY_MODE_2.copyOf()
                3 -> FP_REPLY_MODE_3.copyOf()
                else -> FP_REPLY_MODE_0.copyOf()
            }
        } else {
            val res = ByteArray(32)
            val fpHeader = byteArrayOf(0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x14)
            System.arraycopy(fpHeader, 0, res, 0, 12)
            if (bodyBytes.size >= 164) {
                lastFpKeymsg = bodyBytes.copyOf()
                Log.i(TAG, "AirPlay FairPlay Phase 2 el sıkışma keymsg kaydedildi (164 bayt).")
                System.arraycopy(bodyBytes, 144, res, 12, 20)
            } else {
                for (i in 12 until 32) res[i] = ((i * 29 + 11) % 256).toByte()
            }
            res
        }
    }

    /**
     * SETUP isteğindeki 72-bayt ekey ve streamConnectionId ile
     * AirPlay Type 110 H.264 video deşifreleme anahtarını (AES key ve IV) türetir.
     */
    fun deriveMirrorStreamKeys(ekey: ByteArray, streamConnectionId: Long): Pair<ByteArray, ByteArray>? {
        val keymsg = lastFpKeymsg
        if (keymsg == null || keymsg.size < 164) {
            Log.w(TAG, "deriveMirrorStreamKeys: lastFpKeymsg eksik veya geçersiz!")
            return null
        }
        val rawAesKey = try {
            decryptPlayFair(keymsg, ekey)
        } catch (e: Throwable) {
            Log.e(TAG, "decryptPlayFair native çağrısı başarısız oldu: ${e.message}")
            null
        }

        if (rawAesKey == null || rawAesKey.size < 16) {
            Log.w(TAG, "deriveMirrorStreamKeys: Playfair 16-baytlık AES anahtarı üretemedi!")
            return null
        }

        val rawHex = rawAesKey.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "AirPlay Playfair rawAesKey başarıyla çözüldü (16 bayt): $rawHex")

        // UxPlay standardı: FairPlay SAP v3 ile çözülen rawAesKey doğrudan kök anahtardır.
        // ecdhSecret ile ASLA SHA-512 yapılmaz!
        val finalAesKey = rawAesKey

        // Video AES-128-CTR anahtar ve IV türetimi (UxPlay / RPiPlay standardı)
        val streamIdStr = java.lang.Long.toUnsignedString(streamConnectionId)
        val skeyall = "AirPlayStreamKey$streamIdStr".toByteArray(Charsets.UTF_8)
        val sivall = "AirPlayStreamIV$streamIdStr".toByteArray(Charsets.UTF_8)

        val md = MessageDigest.getInstance("SHA-512")
        md.update(skeyall)
        md.update(finalAesKey, 0, 16)
        val videoKey = md.digest().copyOfRange(0, 16)

        md.reset()
        md.update(sivall)
        md.update(finalAesKey, 0, 16)
        val videoIv = md.digest().copyOfRange(0, 16)

        val keyHex = videoKey.joinToString("") { "%02x".format(it) }
        val ivHex = videoIv.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "AirPlay mirror stream anahtarları başarıyla türetildi: streamId=$streamIdStr, key=$keyHex, iv=$ivHex")
        return Pair(videoKey, videoIv)
    }
}
