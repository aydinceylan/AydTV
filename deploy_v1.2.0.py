import os
import sys
import time
import json
import zipfile
import subprocess
import urllib.request
import urllib.error

REPO = "aydinceylan/AydTV"
TAG = "v1.2.0"
RELEASE_NAME = "AydTV v1.2.0 - Yeni Çift Panelli Ayarlar, Kanal Sıralama, Web Kumanda & Uyku Zamanlayıcı"
COMMIT_HASH = "237ecf6"
RELEASE_NOTES = """## AydTV v1.2.0 Yenilikleri
- 🎨 **Baştan Aşağı Yenilenen Çift Panelli Ayarlar Arayüzü:** 55" TV ekranları için özel tasarlanmış, sekmeli, modern cam efektli ve çok ferah yeni ayarlar menüsü.
- 🔢 **Kumanda ile Kolay Kanal Sıralama:** Ayarlar içerisinden istenilen kanalı OK ile seçip kumandanın Yukarı/Aşağı tuşlarıyla sırasını değiştirme ve kilitli kaydetme özelliği.
- 📲 **Telefondan Akıllı TV Kumandası (Web Remote):** Sıfır uygulama yükleme! TV'deki yerel ağ adresini (`http://192.168.1.X:8088`) telefonun tarayıcısından açarak telefonu dokunmatik titreşimli bir TV kumandasına dönüştürme.
  - 🎮 Kumanda: D-Pad, ses rocker, kanal rocker, geri, play/pause.
  - ⌨️ Klavye: Telefondan doğrudan TV'ye metin gönderme.
  - 📺 Kanallar: Telefondan tek dokunuşla TV'de kanal açma ve favori yıldızı.
  - ⏱️ Uyku: 15, 30, 45, 60, 90, 120 dk uyku zamanlayıcı kurma.
- 🍏 **AirPlay Video, Fotoğraf & Müzik:** Modern AppleTV3,1 ve features=0x39F7 ile video akışı ve galeri fotoğraflarını TV'ye yansıtma.
"""

# GitHub Token al
proc = subprocess.run(
    'printf "protocol=https\\nhost=github.com\\n" | git credential fill',
    shell=True, capture_output=True, text=True
)
token = None
for line in proc.stdout.splitlines():
    if line.startswith("password="):
        token = line.split("=", 1)[1]
        break

if not token:
    print("HATA: GitHub token bulunamadı!")
    sys.exit(1)

headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github.v3+json",
    "User-Agent": "AydTV-Deployer"
}

def api_get(url):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def api_post(url, data):
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

print("[*] Yeni GitHub Actions Run aranıyor...")
run_id = None
for _ in range(15):
    runs_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs")
    for run in runs_data.get("workflow_runs", []):
        if run.get("head_commit", {}).get("id", "").startswith(COMMIT_HASH) or "v1.2.0" in run.get("head_commit", {}).get("message", ""):
            run_id = run["id"]
            break
    if run_id:
        break
    time.sleep(3)

if not run_id:
    print("Run bulunamadı, son run alınıyor...")
    runs_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs")
    run_id = runs_data["workflow_runs"][0]["id"]

print(f"[*] İzlenen Run ID: {run_id}")

while True:
    run_info = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}")
    status = run_info.get("status")
    conclusion = run_info.get("conclusion")
    print(f"[*] Durum: {status}, Sonuç: {conclusion}")
    if status == "completed":
        if conclusion != "success":
            print(f"[-] Derleme başarısız oldu: {conclusion}")
            sys.exit(1)
        break
    time.sleep(12)

print("[+] Derleme başarıyla tamamlandı! Artifact aranıyor...")
artifacts_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts")
artifact_url = None
for art in artifacts_data.get("artifacts", []):
    if art["name"] == "AydTV-ArçelikTV-APK":
        artifact_url = art["archive_download_url"]
        break

if not artifact_url:
    print("[-] Artifact bulunamadı!")
    sys.exit(1)

print("[*] APK indiriliyor (curl ile)...")
subprocess.run([
    "curl", "-sSL",
    "-H", f"Authorization: Bearer {token}",
    "-H", "Accept: application/vnd.github.v3+json",
    "-o", "artifact.zip",
    artifact_url
], check=True)

print("[*] ZIP açılıyor...")
with zipfile.ZipFile("artifact.zip", "r") as z:
    z.extractall("extracted_apk")

apk_path = os.path.join("extracted_apk", "app-release.apk")
if not os.path.exists(apk_path):
    for root, dirs, files in os.walk("extracted_apk"):
        for f in files:
            if f.endswith(".apk"):
                apk_path = os.path.join(root, f)
                break

print(f"[+] APK bulundu: {apk_path} ({os.path.getsize(apk_path)} bayt)")

# Release Oluşturma
print(f"[*] GitHub Release oluşturuluyor: {TAG}")
release_payload = {
    "tag_name": TAG,
    "name": RELEASE_NAME,
    "body": RELEASE_NOTES,
    "draft": False,
    "prerelease": False
}

try:
    rel_resp = api_post(f"https://api.github.com/repos/{REPO}/releases", release_payload)
except urllib.error.HTTPError as e:
    err_body = e.read().decode('utf-8')
    print(f"[!] Release zaten var veya hata: {err_body}")
    releases = api_get(f"https://api.github.com/repos/{REPO}/releases")
    rel_resp = None
    for r in releases:
        if r["tag_name"] == TAG:
            rel_resp = r
            break

upload_url = rel_resp["upload_url"].split("{")[0]
print(f"[+] Release hazır! Upload URL: {upload_url}")

# APK Yükleme (app-release.apk)
with open(apk_path, "rb") as f:
    apk_bytes = f.read()

up_headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/vnd.android.package-archive",
    "User-Agent": "AydTV-Deployer"
}

print("[*] APK Release'e yükleniyor (app-release.apk)...")
req = urllib.request.Request(f"{upload_url}?name=app-release.apk", data=apk_bytes, headers=up_headers)
try:
    urllib.request.urlopen(req)
    print("[+] app-release.apk yüklendi!")
except Exception as e:
    print(f"[!] app-release.apk yüklenirken not: {e}")

print(f"[*] APK Release'e yükleniyor (AydTV-{TAG}.apk)...")
req2 = urllib.request.Request(f"{upload_url}?name=AydTV-{TAG}.apk", data=apk_bytes, headers=up_headers)
try:
    urllib.request.urlopen(req2)
    print(f"[+] AydTV-{TAG}.apk yüklendi!")
except Exception as e:
    print(f"[!] AydTV-{TAG}.apk yüklenirken not: {e}")

# TV Kurulumu (ADB)
print("[*] Televizyona kuruluyor (ADB: 192.168.1.11:5555)...")
res = subprocess.run(["adb", "-s", "192.168.1.11:5555", "install", "-r", apk_path], capture_output=True, text=True)
print(f"ADB Kurulum Çıktısı: {res.stdout.strip()} {res.stderr.strip()}")

# Erişilebilirlik Servisini Etkinleştir
print("[*] Erişilebilirlik Servisi (TvButtonAccessibilityService) etkinleştiriliyor...")
subprocess.run([
    "adb", "-s", "192.168.1.11:5555", "shell",
    "settings", "put", "secure", "enabled_accessibility_services",
    "com.aydtv.aydtv/.TvButtonAccessibilityService"
], check=False)
subprocess.run([
    "adb", "-s", "192.168.1.11:5555", "shell",
    "settings", "put", "secure", "accessibility_enabled", "1"
], check=False)

# AydTV'yi Başlat
print("[*] AydTV televizyonda başlatılıyor...")
subprocess.run([
    "adb", "-s", "192.168.1.11:5555", "shell",
    "monkey", "-p", "com.aydtv.aydtv", "-c", "android.intent.category.LAUNCHER", "1"
], check=False)

# Temizlik
subprocess.run(["rm", "-rf", "artifact.zip", "extracted_apk"], check=False)
print(f"\n🎉 TEBRİKLER! AydTV {TAG} başarıyla GitHub Release'e yüklendi, televizyona kuruldu ve başlatıldı!")
