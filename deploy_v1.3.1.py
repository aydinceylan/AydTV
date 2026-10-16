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
TAG = "v1.3.1"
RELEASE_NAME = "AydTV v1.3.1 - Fotoğraf Yansıtma, Web Kumanda Medya Paylaşımı & AirPlay Düzeltmesi"
COMMIT_HASH = None
RELEASE_NOTES = """## AydTV v1.3.1 Yenilikleri
- 📷 **Fotoğraf Yansıtma (AirPlay & Web Remote):** Apple Photos veya Web Kumanda üzerinden gönderilen fotoğraflar TV'de tam ekran yüksek çözünürlükte gösterilir. Sıfır baytlık üzerine yazma ve bağlantı kopma hataları giderildi.
- 📱 **Mobil Web Kumandada 4. Sekme (Paylaş):** Telefondan tek tıkla galeriden fotoğraf seçip TV'ye anında aktarma ve web video bağlantılarını TV'de tam ekran oynatma özelliği eklendi.
- ⚡ **HTTP/RTSP Komut & İkili Veri Ayrıştırıcısı:** Socket okuyucusunda ikili video karelerinin yanlışlıkla HTTP komutu olarak yorumlanıp kilitlenmesi kökten çözüldü.
- 🎯 **Net AirPlay Hub Arayüzü:** TV ekranında yanıltıcı statik metin yerine cihazın bağlandığını ve medya gönderilmeye hazır olduğunu gösteren modern durum kartı eklendi.
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

print("[*] Artifacts listeleniyor...")
art_data = api_get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts")
artifacts = art_data.get("artifacts", [])
apk_artifact = None
for art in artifacts:
    if "apk" in art["name"].lower() or "release" in art["name"].lower():
        apk_artifact = art
        break

if not apk_artifact and artifacts:
    apk_artifact = artifacts[0]

if not apk_artifact:
    print("[-] Artifact bulunamadı!")
    sys.exit(1)

download_url = apk_artifact["archive_download_url"]
print(f"[+] Artifact indiriliyor: {apk_artifact['name']} ({download_url})")

zip_path = f"AydTV-{TAG}.zip"
curl_cmd = f'curl -s -L -H "Authorization: Bearer {token}" -o "{zip_path}" "{download_url}"'
res = subprocess.run(curl_cmd, shell=True)
if res.returncode != 0 or not os.path.exists(zip_path) or os.path.getsize(zip_path) < 1000:
    print("[-] curl ile indirme başarısız, urllib deneniyor...")
    req = urllib.request.Request(download_url, headers=headers)
    with urllib.request.urlopen(req) as resp, open(zip_path, "wb") as f:
        f.write(resp.read())

print("[+] Zip başarıyla indirildi, APK çıkartılıyor...")
apk_path = None
extract_dir = f"extracted_{TAG.replace('.', '')}"
with zipfile.ZipFile(zip_path, 'r') as zip_ref:
    zip_ref.extractall(extract_dir)
    for root, _, files in os.walk(extract_dir):
        for file in files:
            if file.endswith(".apk"):
                apk_path = os.path.join(root, file)
                break

if not apk_path:
    print("[-] Zip içinde .apk bulunamadı!")
    sys.exit(1)

target_apk = f"AydTV-{TAG}.apk"
shutil.copyfile(apk_path, target_apk)
apk_path = target_apk

print(f"[+] APK bulundu: {apk_path} ({os.path.getsize(apk_path) / 1024 / 1024:.2f} MB)")

print(f"[*] GitHub Release kontrol ediliyor ({TAG})...")
try:
    release = api_get(f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}")
    print(f"[+] Release zaten var: ID {release['id']}")
except Exception:
    print(f"[*] Yeni Release oluşturuluyor: {TAG}...")
    release = api_post(f"https://api.github.com/repos/{REPO}/releases", {
        "tag_name": TAG,
        "name": RELEASE_NAME,
        "body": RELEASE_NOTES,
        "draft": False,
        "prerelease": False
    })
    print(f"[+] Release oluşturuldu: ID {release['id']}")

upload_url = release.get("upload_url", "").split("{")[0]
asset_name = f"AydTV-{TAG}.apk"

for asset in release.get("assets", []):
    if asset["name"] == asset_name or asset["name"].endswith(".apk"):
        print(f"[*] Eski asset siliniyor: {asset['name']} (ID {asset['id']})...")
        del_req = urllib.request.Request(asset["url"], headers=headers, method="DELETE")
        try:
            urllib.request.urlopen(del_req)
        except Exception as e:
            print(f"[-] Asset silme hatası: {e}")

print(f"[*] Yeni APK Release'e yükleniyor: {asset_name}...")
with open(apk_path, "rb") as f:
    apk_bytes = f.read()

up_headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/vnd.android.package-archive",
    "User-Agent": "AydTV-Deployer"
}
up_req = urllib.request.Request(f"{upload_url}?name={asset_name}", data=apk_bytes, headers=up_headers, method="POST")
with urllib.request.urlopen(up_req) as resp:
    up_resp = json.loads(resp.read().decode('utf-8'))
    print(f"[+] APK başarıyla yüklendi! İndirme linki: {up_resp.get('browser_download_url')}")

TV_IP = "192.168.1.11:5555"
print(f"\n[*] TV'ye ({TV_IP}) ADB üzerinden kuruluyor...")
subprocess.run(f"adb connect {TV_IP}", shell=True)
res = subprocess.run(f"adb -s {TV_IP} install -r {apk_path}", shell=True, capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print(res.stderr)

if "Success" in res.stdout:
    print("[+] AydTV TV'ye başarıyla kuruldu!")
    print("[*] TV'de AydTV başlatılıyor...")
    subprocess.run(f"adb -s {TV_IP} shell am start -n com.aydtv.aydtv/.MainActivity", shell=True)
    print("[✓] TÜM İŞLEMLER BAŞARIYLA TAMAMLANDI!")
else:
    print("[-] ADB kurulumu tamamlanamadı. TV bağlantısını kontrol edin.")
