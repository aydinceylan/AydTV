# 📺 AydTV - Akıllı Android TV Canlı Yayın Uygulaması

Modern, hafif ve yüksek performanslı Android TV canlı yayın deneyimi.

[![Son Sürüm](https://img.shields.io/badge/Sürüm-v1.0.10-00F5D4?style=for-the-badge&logo=android)](https://github.com/aydinceylan/AydTV/releases/latest)
[![APK İndir](https://img.shields.io/badge/Doğrudan%20İndir-AydTV.apk-FF0055?style=for-the-badge&logo=googleplay)](https://github.com/aydinceylan/AydTV/releases/latest/download/AydTV-v1.0.10.apk)

---

## 🚀 Doğrudan İndir (APK)

Arkadaşlarınız ve tüm Android TV / TV Box kullanıcıları son sürümü tek tıkla indirebilir:

📥 **[AydTV v1.0.10 APK İndir (Doğrudan Bağlantı)](https://github.com/aydinceylan/AydTV/releases/latest/download/AydTV-v1.0.10.apk)**

> Alternatif olarak tüm sürümleri görmek için: [GitHub Releases Sayfası](https://github.com/aydinceylan/AydTV/releases)

---

## ✨ Öne Çıkan Özellikler

- **⚡ Sıfır Gecikmeli Zapping (Kanal Geçişi):** Kumanda yukarı/aşağı tuşlarına basıldığında kanal bilgisi ve HUD anında güncellenir, akış bekleme yapmadan bağlanır.
- **🔄 Akıllı Donma Dedektörü (Auto-Recovery):** İnternet dalgalanmasında yayın donarsa sistem 3-4 saniye içinde bunu algılar ve kullanıcı kanal değiştirmek zorunda kalmadan yayını canlı uca (live edge) otomatik bağlar.
- **🛡️ Yedek Hat Desteği (Failover):** Birincil kaynak yanıt vermediğinde sıradaki yedek akış devreye girer.
- **📡 TV+ Canlı EPG (Elektronik Program Rehberi):** Kanallarda o an oynayan ve sıradaki programları canlı olarak ekranda gösterir.
- **🔢 Kumanda Sayı Tuşları (0-9):** Doğrudan kumandadan kanal numarası yazarak kanala atlama.
- **⭐ Favori Kanal Yönetimi:** Sol menüden kanalları favorilere ekleme ve sadece favoriler arasında hızlı gezinme.
- **📺 Android TV & Kumanda Optimizasyonu:** D-Pad odaklanması, donanımsal `MediaCodec` hafıza yönetimi ve TV uyumlu Liquid Glass arayüz.

---

## 📲 Kurulum Rehberi (Android TV & TV Box)

### Yöntem 1: Downloader Uygulaması ile (En Kolay)
1. Android TV'nizde Google Play Store'dan **Downloader by AFTVnews** uygulamasını indirin.
2. Downloader'ı açıp arama kutusuna şu indirme linkini yazın veya tarayıcıdan GitHub Releases sayfasını açın:
   ```text
   https://github.com/aydinceylan/AydTV/releases/latest/download/AydTV-v1.0.10.apk
   ```
3. İndirme bittiğinde **Yükle (Install)** butonuna basın.

### Yöntem 2: USB Bellek ile
1. Yukarıdaki APK dosyasını bilgisayarınıza veya telefonunuza indirin.
2. APK dosyasını bir USB belleğe atıp TV'nize takın.
3. TV'deki bir Dosya Yöneticisi (File Commander vb.) ile APK'ya tıklayıp kurun.

### Yöntem 3: Kablosuz ADB ile
```bash
adb connect <TV_IP_ADRESI>:5555
adb install -r AydTV-v1.0.10.apk
```

---

## 🎮 Kumanda Kısayolları

| Tuş | İşlev |
| :--- | :--- |
| **▲ / ▼ (Yukarı / Aşağı)** | Önceki / Sonraki kanala hızlı geçiş |
| **OK / Enter** | Sol Liquid Glass Kanal Listesini açar / kapatır |
| **◄ / ► (Sol / Sağ)** | Kanal kategorileri (Tümü, Ulusal, Haber, Spor vb.) arasında geçiş |
| **0 - 9 Sayı Tuşları** | Kanal numarası girerek doğrudan geçiş |
| **Geri (Back)** | Menü açıksa menüyü kapatır; kapalıysa çıkış onayı sorar |
