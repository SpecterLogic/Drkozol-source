"""Install the official, pinned Windows x64 TUN bridge; no user configuration."""
from pathlib import Path
import hashlib
import io
import sys
import urllib.request
import zipfile

VERSION = '1.14.2'
SHA256 = 'c2d8bfff918755808781dfdeeb8581b6c91eb3a243d9a7b55483cfc0c0684d32'
URL = f'https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/sing-box-{VERSION}-windows-amd64.zip'

def main():
    target = Path(sys.argv[1]).resolve()
    with urllib.request.urlopen(URL, timeout=90) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('TUN archive digest mismatch')
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for item in archive.infolist():
            if not item.is_dir():
                (target / Path(item.filename).name).write_bytes(archive.read(item))
    # sing-tun embeds the signed Wintun 0.14.1 DLL; retain its separate redistribution notice.
    with urllib.request.urlopen('https://www.wintun.net/builds/wintun-0.14.1.zip', timeout=30) as response:
        driver = response.read(16 * 1024 * 1024 + 1)
    if hashlib.sha256(driver).hexdigest() != '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51':
        raise SystemExit('Wintun notice archive digest mismatch')
    with zipfile.ZipFile(io.BytesIO(driver)) as archive:
        for item in archive.infolist():
            if 'license' in item.filename.lower() and not item.is_dir():
                (target / 'Wintun-0.14.1-LICENSE.txt').write_bytes(archive.read(item))
    if not (target / 'sing-box.exe').is_file():
        raise SystemExit('TUN engine missing')
    print(f'sing-box {VERSION} Windows x64 verified')

if __name__ == '__main__':
    main()
