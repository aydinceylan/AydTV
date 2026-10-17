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
TAG = "v1.3.9"
RELEASE_NAME = "AydTV v1.3.9 - AirPlay TCP Port 7100 Dedicated Video Mirroring Stream & Uyku Modu Kalkanı"
COMMIT_HASH = None
RELEASE_NOTES = """## AydTV v1.3.9 Yenilikleri
- 🚀 **AirPlay TCP Port 7100 Dedicated Mirror Stream:** Apple AirPlay standardı (UxPlay / RPiPlay) gereğince video akışı için Port 7100 özel TCP soketi dinleyiciye alındı. SETUP isteğinde `dataPort: 7100` dönülerek iPhone ve Mac cihazlarının doğrudan video soketine bağlanması ve kesintisiz 60 FPS donanımsal H.264 çözücüye beslenmesi sağlandı (Siyah ekran kökten çözüldü).
- 🌙 **TV Bekleme Modu & Donanımsal Uyku İzni:** Kumandadan veya mobil kumandadan Güç (Power) tuşuna basıldığında Android penceresindeki `FLAG_KEEP_SCREEN_ON` ve `FLAG_TURN_SCREEN_ON` bayrakları derhal temizlenir, ekran tamamen karartılır ve televizyonun kendi enerji tasarrufu/uyku (deep sleep / standby) moduna geçmesine tam yetki verilir.
- ⚡ **Hızlı Uyandırma:** Kumandadan veya telefondan herhangi bir tuşa basıldığında bekleme modundan anında çıkılır, ekran açık tutma bayrağı geri yüklenir ve canlı TV yayını kaldığı yerden devam eder.
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

# 1. Commit hash'i al
proc = subprocess.run("git rev-parse HEAD", shell=True, capture_output=True, text=True)
COMMIT_HASH = proc.stdout.strip()
print(f"[*] Son Commit Hash: {COMMIT_HASH}")

# 2. GitHub Actions'ta bu commit için çalışan workflow run'ını bekle
print("[*] GitHub Actions build workflow bekleniyor...")
run_id = None
for _ in range(60):
    time.sleep(5)
    runs = api_get(f"https://api.github.com/repos/{REPO}/actions/runs?head_sha={COMMIT_HASH}")
    workflow_runs = runs.get("workflow_runs", [])
    if workflow_runs:
        run = workflow_runs[0]
        run_id = run["id"]
        status = run["status"]
        conclusion = run["conclusion"]
        print(f"[*] Workflow Run #{run_id} Durumu: {status} (Sonuç: {conclusion})")
        if status == "completed":
            if conclusion == "success":
                print("[+] Build başarıyla tamamlandı!")
                break
            else:
                print(f"[-] Build başarısız oldu: {conclusion}")
                sys.exit(1)
else:
    print("[-] Workflow zaman aşımına uğradı!")
    sys.exit(1)

# 3. Artifact'leri listele ve APK indir
print(f"[*] Artifact'ler listeleniyor (Run #{run_id})...")
artifacts = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts")
artifact_list = artifacts.get("artifacts", [])

apk_artifact = None
for art in artifact_list:
    if "apk" in art["name"].lower() or "release" in art["name"].lower():
        apk_artifact = art
        break

if not apk_artifact and artifact_list:
    apk_artifact = artifact_list[0]

if not apk_artifact:
    print("[-] Artifact bulunamadı!")
    sys.exit(1)

print(f"[+] Artifact bulundu: {apk_artifact['name']} (ID: {apk_artifact['id']}, Boyut: {apk_artifact['size_in_bytes']} bayt)")
artifact_id = apk_artifact["id"]

zip_path = f"AydTV-{TAG}.zip"
print(f"[*] Artifact zip indiriliyor (curl ile): {zip_path}")
dl_cmd = [
    'curl', '-L',
    '-H', f'Authorization: Bearer {token}',
    '-H', 'Accept: application/vnd.github.v3+json',
    f'https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip',
    '-o', zip_path
]
subprocess.run(dl_cmd, check=True)
print(f"[+] Zip indirildi: {os.path.getsize(zip_path)} bayt")

# 4. Zip'i aç ve APK'yı çıkar
extract_dir = f"extracted_{TAG.replace('.', '').replace('v', '')}"
os.makedirs(extract_dir, exist_ok=True)
with zipfile.ZipFile(zip_path, 'r') as zip_ref:
    zip_ref.extractall(extract_dir)

found_apk = None
for root, _, files in os.walk(extract_dir):
    for f in files:
        if f.endswith(".apk"):
            found_apk = os.path.join(root, f)
            break

if not found_apk:
    print("[-] Zip içinde APK bulunamadı!")
    sys.exit(1)

dest_apk = f"AydTV-{TAG}.apk"
shutil.copyfile(found_apk, dest_apk)
print(f"[+] APK hazırlandı: {dest_apk} ({os.path.getsize(dest_apk)} bayt)")

# 5. GitHub Release oluştur veya güncelle
print(f"[*] GitHub Release kontrol ediliyor: {TAG}")
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
