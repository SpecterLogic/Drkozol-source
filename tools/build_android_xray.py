"""Rebuild all Android Xray ABIs from the pinned wrapper and compatibility source."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from prepare_xray_compat import prepare, ROOT


def main():
    modfile, source = prepare()
    go_mod = (ROOT / 'AndroidLibXrayLite/go.mod').read_text(encoding='utf-8')
    mobile_version = re.search(r'golang.org/x/mobile\s+(\S+)', go_mod).group(1)
    tools = ROOT / 'artifacts/native/go-bin'
    tools.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, GOBIN=str(tools))
    for command in ('gomobile', 'gobind'):
        subprocess.run(['go', 'install', 'golang.org/x/mobile/cmd/' + command + '@' + mobile_version], env=environment, check=True)
    environment['PATH'] = str(tools) + os.pathsep + environment['PATH']
    # gomobile/go-packages resolves its binding dependency from the module root.
    # Keep the build module next to an isolated copy of the pinned wrapper sources.
    wrapper = ROOT / 'artifacts/native/android-xray-wrapper'
    wrapper.mkdir(parents=True, exist_ok=True)
    for path in (ROOT / 'AndroidLibXrayLite').iterdir():
        if path.is_file() and path.name != '.git':
            shutil.copy2(path, wrapper / path.name)
    subprocess.run(['go', 'mod', 'edit', '-replace', 'github.com/xtls/xray-core=' + str(source),
                    '-tool=golang.org/x/mobile/cmd/gobind'], cwd=wrapper, env=environment, check=True)
    subprocess.run(['go', 'get', '-tool', 'golang.org/x/mobile/cmd/gobind@' + mobile_version],
                   cwd=wrapper, env=environment, check=True)
    environment.pop('GOFLAGS', None)
    output = ROOT / 'V2rayNG/app/libs/libv2ray.aar'
    executable = tools / ('gomobile.exe' if os.name == 'nt' else 'gomobile')
    subprocess.run([str(executable), 'bind', '-target=android', '-androidapi=24', '-ldflags=-s -w -checklinkname=0',
                    '-o', str(output)], cwd=wrapper, env=environment, check=True)
    with zipfile.ZipFile(output) as archive:
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
            if f'jni/{abi}/libgojni.so' not in archive.namelist():
                raise SystemExit('Android Xray ABI missing: ' + abi)
    metadata = json.loads((source / '.drkozol-compat.json').read_text())
    metadata['aar_sha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
    metadata['wrapper_revision'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT / 'AndroidLibXrayLite', text=True).strip()
    (ROOT / 'artifacts/native/xray-android-provenance.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print('Pinned Android Xray with explicit plain-VLESS compatibility built for all four ABIs')


if __name__ == '__main__':
    main()
