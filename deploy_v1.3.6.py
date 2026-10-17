import os
import sys
import time
import json
import zipfile
import subprocess
import urllib.request
import urllib.error
import shutil

REPO = "aydinceylan/AydTV"
TAG = "v1.3.6"
RELEASE_NAME = "AydTV v1.3.6 - AirPlay Canlı Ekran Sıkı NALU Doğrulaması & Kusursuz Donanım Çözümü"
COMMIT_HASH = None
RELEASE_NOTES = """## AydTV v1.3.6 Yenilikleri
- 📱 **FairPlay SAP Doğrudan Anahtar Kullanımı:** rawAesKey'in ecdhSecret ile bozulması giderildi, UxPlay/RPiPlay standardında doğrudan video kök anahtarı olarak işleniyor.
- ⚡ **UxPlay Standardında Sıkı H.264 NALU Doğrulaması:** Bozuk veya tam deşifre olmamış paketler MediaCodec'e verilmeden elenir, donanım çözücünün çökme (Released state error) döngüsü tamamen önlendi.
- 🛡️ **MediaCodec Güvenli Tampon Yönetimi:** Tampon taşmaları korumaya alındı, codec resetlenmeleri engellendi.
- 📺 **Watchdog TV Yayın Koruması:** Ekran yansıtma (Mirroring) veya Cast sırasında TV yayını donma dedektörünün arkaplanda TV yayını başlatması ve ses çıkarması tamamen engellendi.
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
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode('utf-8'))
        except Exception as e:
            if attempt == 4:
                raise e
            time.sleep(3)

def api_post(url, data):
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode('utf-8'))
        except Exception as e:
            if attempt == 4:
                raise e
            time.sleep(3)

print("[*] Yeni GitHub Actions Run aranıyor...")
run_id = None

rev_proc = subprocess.run("git rev-parse --short HEAD", shell=True, capture_output=True, text=True)
COMMIT_HASH = rev_proc.stdout.strip()
print(f"[*] Hedef Commit: {COMMIT_HASH}")

for _ in range(25):
    runs_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs")
    for run in runs_data.get("workflow_runs", []):
        if run.get("head_sha", "").startswith(COMMIT_HASH):
            run_id = run["id"]
            print(f"[+] Run bulundu: ID {run_id}, Durum: {run['status']}, Sonuç: {run['conclusion']}")
            break
    if run_id:
        break
    time.sleep(3)

if not run_id:
    print("[-] Bu commit için henüz run başlamadı, ilk gelen run bekleniyor...")
    time.sleep(5)
    runs_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs")
    if runs_data.get("workflow_runs"):
        run_id = runs_data["workflow_runs"][0]["id"]
        print(f"[+] En son Run seçildi: ID {run_id}")

print(f"[*] Run {run_id} tamamlanması bekleniyor...")
while True:
    run_info = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}")
    status = run_info.get("status")
    conclusion = run_info.get("conclusion")
    print(f"    -> Durum: {status}, Sonuç: {conclusion}")
    if status == "completed":
        if conclusion != "success":
            print(f"[-] Derleme başarısız oldu: {conclusion}")
            sys.exit(1)
        print("[+] Derleme başarıyla tamamlandı!")
        break
    time.sleep(10)

# Artifact indir
print("[*] Artifact indiriliyor...")
artifacts = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts")
if not artifacts.get("artifacts"):
    print("[-] Artifact bulunamadı!")
    sys.exit(1)

artifact = artifacts["artifacts"][0]
art_download_url = artifact["archive_download_url"]
print(f"[+] Artifact: {artifact['name']} ({artifact['size_in_bytes']} bayt)")

zip_path = f"AydTV-{TAG}.zip"
curl_cmd = f'curl -s -L -H "Authorization: Bearer {token}" -o "{zip_path}" "{art_download_url}"'
res = subprocess.run(curl_cmd, shell=True)
if res.returncode != 0 or not os.path.exists(zip_path) or os.path.getsize(zip_path) < 1000:
    print("[-] curl ile indirme başarısız!")
    sys.exit(1)

print(f"[+] Zip indirildi: {zip_path} ({os.path.getsize(zip_path)} bayt)")

# Zip'ten APK çıkar
extract_dir = f"extracted_{TAG.replace('.', '')}"
os.makedirs(extract_dir, exist_ok=True)
with zipfile.ZipFile(zip_path, 'r') as zip_ref:
    zip_ref.extractall(extract_dir)

apk_file = None
for root, dirs, files in os.walk(extract_dir):
    for f in files:
        if f.endswith(".apk"):
            apk_file = os.path.join(root, f)
            break

if not apk_file:
    print("[-] APK dosyası bulunamadı!")
    sys.exit(1)

dest_apk = f"AydTV-{TAG}.apk"
shutil.copyfile(apk_file, dest_apk)
apk_size = os.path.getsize(dest_apk)
print(f"[+] APK hazır: {dest_apk} ({apk_size} bayt)")

# GitHub Release oluştur veya güncelle
print(f"[*] GitHub Release {TAG} kontrol ediliyor...")
try:
    release = api_get(f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}")
    print(f"[+] Mevcut release bulundu: ID {release['id']}")
except Exception:
    release = None

if not release:
    print(f"[*] Yeni release oluşturuluyor: {TAG}")
    release_data = {
        "tag_name": TAG,
        "target_commitish": "main",
        "name": RELEASE_NAME,
        "body": RELEASE_NOTES,
        "draft": False,
        "prerelease": False
    }
    release = api_post(f"https://api.github.com/repos/{REPO}/releases", release_data)
    print(f"[+] Release oluşturuldu: ID {release['id']}")

# Asset yükle
upload_url_template = release.get("upload_url", "")
upload_url = upload_url_template.split("{")[0] + f"?name={dest_apk}"
print(f"[*] APK release'e yükleniyor: {upload_url}")

# Eski asset varsa sil
for asset in release.get("assets", []):
    if asset["name"] == dest_apk:
        print(f"[*] Eski asset siliniyor: {asset['id']}")
        del_req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases/assets/{asset['id']}",
            headers=headers,
            method="DELETE"
        )
        try:
            urllib.request.urlopen(del_req)
        except Exception:
            pass
        time.sleep(2)

with open(dest_apk, "rb") as f:
    apk_data = f.read()

up_headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": str(len(apk_data)),
    "User-Agent": "AydTV-Deployer"
}
up_req = urllib.request.Request(upload_url, data=apk_data, headers=up_headers)
with urllib.request.urlopen(up_req) as resp:
    up_res = json.loads(resp.read().decode('utf-8'))
    print(f"[+] APK Asset başarıyla yüklendi: {up_res.get('browser_download_url')}")

# TV'ye ADB ile yükleme ve başlatma
print("[*] TV'ye ADB üzerinden yükleniyor...")
adb_target = "192.168.1.11:5555"
subprocess.run(f"adb connect {adb_target}", shell=True)
install_res = subprocess.run(f"adb -s {adb_target} install -r {dest_apk}", shell=True, capture_output=True, text=True)
print(f"[+] ADB Install Çıktısı: {install_res.stdout.strip()} {install_res.stderr.strip()}")

# Uygulamayı başlat
print("[*] AydTV başlatılıyor...")
start_res = subprocess.run(
    f"adb -s {adb_target} shell am start -n com.aydtv.aydtv/com.aydtv.aydtv.MainActivity",
    shell=True, capture_output=True, text=True
)
print(f"[+] Başlatma Çıktısı: {start_res.stdout.strip()}")
print(f"[🎉] {TAG} başarıyla kuruldu ve başlatıldı!")
