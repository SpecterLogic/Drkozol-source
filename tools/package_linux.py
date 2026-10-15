"""Package a clean Linux publish directory, preserving native executable permissions."""
from pathlib import Path
import argparse
import gzip
import io
import shutil
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXECUTABLES = {'DrKozol', 'install-tun.sh', 'bin/xray/xray', 'bin/drkozol-awg', 'bin/sing_box/sing-box'}
PRIVATE_DIRECTORIES = {'guiConfigs', 'guiLogs', 'guiTemps', 'guiBackups', 'binConfigs', '.signing', '.tools', '.git'}

def tar_bytes(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.PAX_FORMAT) as archive:
        directories = {parent.as_posix() for name, _, _ in files for parent in Path(name).parents if parent.as_posix() != '.'}
        for name in sorted(directories, key=lambda value: (value.count('/'), value)):
            info = tarfile.TarInfo(name + '/')
            info.type = tarfile.DIRTYPE; info.mode = 0o755; info.uid = info.gid = 0; info.mtime = 0
            archive.addfile(info)
        for name, content, mode in files:
            info = tarfile.TarInfo(name)
            info.size = len(content); info.mode = mode; info.uid = info.gid = 0; info.mtime = 0
            archive.addfile(info, io.BytesIO(content))
    return gzip.compress(output.getvalue(), mtime=0)

def ar_file(path, members):
    with path.open('wb') as output:
        output.write(b'!<arch>\n')
        for name, content in members:
            header = f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{"100644":<8}{len(content):<10}`\n'
            output.write(header.encode('ascii')); output.write(content)
            if len(content) % 2: output.write(b'\n')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('--version', default='1.4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/packages')
    args = parser.parse_args()
    source = args.input.resolve()
    if any(c not in '0123456789.' for c in args.version): raise SystemExit('Invalid version')
    for file in source.rglob('*'):
        relative = file.relative_to(source)
        if file.is_symlink() or any(part in PRIVATE_DIRECTORIES for part in relative.parts) or file.name.startswith(('smoke-', 'private-')):
            raise SystemExit('Publish directory contains test or private files; use the clean pre-test copy')
    for name in EXECUTABLES | {'bin/geoip.dat', 'bin/geosite.dat'}:
        if not (source / name).is_file(): raise SystemExit('Missing packaged input: ' + name)
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='drkozol-linux-') as directory:
        stage = Path(directory) / 'DrKozol'
        shutil.copytree(source, stage)
        shutil.copy2(ROOT / 'Linux/README-fa.md', stage / 'README-fa.md')
        licenses = stage / 'licenses'; licenses.mkdir(exist_ok=True)
        for original, name in ((ROOT / 'LICENSE', 'Android-GPL-3.0.txt'), (ROOT / 'Windows/LICENSE', 'Desktop-GPL-3.0.txt'),
                               (ROOT / 'Windows/v2rayN/ServiceLib/DrKozol/Flags/LICENSE.txt', 'Country-flags-MIT.txt'),
                               (ROOT / 'docs/licenses/amneziawg-go-MIT.txt', 'AmneziaWG-MIT.txt')):
            shutil.copy2(original, licenses / name)
        for notice in (ROOT / 'docs/licenses').glob('*.txt'):
            shutil.copy2(notice, licenses / notice.name)
        files = [(file.relative_to(stage).as_posix(), file.read_bytes(), 0o755 if file.relative_to(stage).as_posix() in EXECUTABLES else 0o644)
                 for file in sorted(stage.rglob('*')) if file.is_file()]
        (args.output / f'DrKozol-{args.version}-linux-x64.tar.gz').write_bytes(tar_bytes([('DrKozol/' + name, data, mode) for name, data, mode in files]))
        launcher = b'#!/bin/sh\nexport V2RAYN_LOCAL_APPLICATION_DATA_V2=1\nexec /opt/DrKozol/DrKozol "$@"\n'
        payload = [('opt/DrKozol/' + name, data, mode) for name, data, mode in files]
        payload += [('usr/bin/DrKozol', launcher, 0o755), ('usr/share/applications/DrKozol.desktop', (ROOT / 'Linux/DrKozol.desktop').read_bytes(), 0o644),
                    ('usr/share/icons/hicolor/scalable/apps/DrKozol.svg', (ROOT / 'Windows/v2rayN/v2rayN.Desktop/Assets/DrKozol.svg').read_bytes(), 0o644)]
        control = f'''Package: drkozol
Version: {args.version}
Architecture: amd64
Maintainer: DrKozol
Section: net
Priority: optional
Depends: ca-certificates, libx11-6, libice6, libsm6, libfontconfig1, libxrandr2, libxi6, libxcursor1, libstdc++6, zlib1g, libbrotli1, libgssapi-krb5-2, libcap2-bin, fonts-dejavu-core, libicu70 | libicu72 | libicu74 | libicu76 | libicu78, libssl3 | libssl3t64
Recommends: pkexec, gsettings-desktop-schemas
Homepage: https://github.com/SpecterLogic/Drkozol
Description: DrKozol subscription VPN client
 Native Linux desktop with Xray, AmneziaWG and optional TUN.
'''.encode()
        ar_file(args.output / f'DrKozol-{args.version}-linux-amd64.deb', [('debian-binary', b'2.0\n'), ('control.tar.gz', tar_bytes([('control', control, 0o644)])), ('data.tar.gz', tar_bytes(payload))])
    print('Clean Linux tar.gz and deb prepared')

if __name__ == '__main__': main()
