"""Build the existing userspace AWG proxy as an APK-packaged probe for every ABI."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    ndk = os.environ.get('ANDROID_NDK_HOME')
    if not ndk:
        raise SystemExit('ANDROID_NDK_HOME must point to the pinned Android NDK')
    prebuilt = Path(ndk) / 'toolchains/llvm/prebuilt'
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    compilers = prebuilt / host / 'bin'
    for abi, arch, compiler in (
        ('arm64-v8a', 'arm64', 'aarch64-linux-android24-clang'),
        ('armeabi-v7a', 'arm', 'armv7a-linux-androideabi24-clang'),
        ('x86_64', 'amd64', 'x86_64-linux-android24-clang'),
        ('x86', '386', 'i686-linux-android24-clang'),
    ):
        output = ROOT / 'V2rayNG/app/libs' / abi / 'libdrkozol-awg-probe.so'
        output.parent.mkdir(parents=True, exist_ok=True)
        environment = dict(os.environ, GOOS='android', GOARCH=arch, GOARM='7', CGO_ENABLED='1',
                           CC=str(compilers / (compiler + ('.cmd' if os.name == 'nt' else ''))))
        subprocess.run(['go', 'build', '-buildmode=pie', '-trimpath', '-ldflags=-s -w -checklinkname=0', '-o', str(output), '.'],
                       cwd=ROOT / 'Windows/v2rayN/AwgHost', env=environment, check=True)
        print('APK-packaged userspace AWG probe built:', abi)


if __name__ == '__main__':
    main()
