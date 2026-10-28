# DrKozol 1.5.0 corresponding source

Android, Windows and Linux 1.5.0 corresponding source. Downloads and feedback:
https://github.com/SpecterLogic/Drkozol

The applications and components retain their respective software licenses and
copyright notices. See LICENSE, Windows/LICENSE, docs/licenses and the notices
inside individual components. Copyright Ã‚Â© 2026 Drkozol applies to original branding
and artwork without restricting rights granted by those software licenses.

## Build from the source release archive

Download DrKozol-desktop-source-1.5.0.zip from the v1.5.0-desktop release for Windows and Linux.
The original v1.5.0 release archive remains available for the original Android and Windows binaries. The archive
includes the verified Android native compiler inputs and routing data used in the
release. Signing keys, subscriptions and local settings are excluded.

### Android

Install JDK 21, Android SDK platform android-37.0, build-tools 37.0.0 and NDK
30.0.16248370. Set ANDROID_HOME and JAVA_HOME, then:

```sh
cd V2rayNG
chmod +x gradlew
./gradlew :app:testPlaystoreDebugUnitTest :app:assemblePlaystoreRelease
```

This produces an unsigned APK. Both Play Store and F-Droid build variants are
available. The Gradle files define the supported architectures and SDK versions.

### Windows x64

Install .NET SDK 10.0.401, Go 1.27.2 and Python 3. Add them to PATH. From the root:

```powershell
./tools/build_windows.ps1
dotnet run --project Windows/v2rayN/ServiceLib.Tests/ServiceLib.Tests.csproj -c Release
```

The application and protocol engines are generated in artifacts/windows. Keep the
bin directory beside DrKozol.exe. TUN creation needs administrator rights.

### Linux x64

Install the same .NET SDK, Go and Python versions and the desktop dependencies
listed in Linux/README-fa.md. From the root:

```sh
sh tools/build_linux.sh
python3 tools/package_linux.py artifacts/linux
```

The build produces artifacts/linux and installable packages in artifacts/packages.
The graphical app runs as an ordinary user. Linux/install-tun.sh grants the pinned
TUN engine the network capability needed to create a virtual adapter.

## Native sources

SOURCE-MODULES.json records the wrapper and HEV source revisions. Go modules are
pinned in go.mod/go.sum. The archive also includes the patched Xray source and the
AmneziaWG Go source. tools/prepare_xray_compat.py checks the upstream source hash
before applying the compatibility patch; tools/test_xray_compat.py validates it.
For an Android native rebuild, restore the wrapper's upstream revision metadata
without replacing the provided source:

```sh
git -C AndroidLibXrayLite init
git -C AndroidLibXrayLite fetch --depth=1 https://github.com/2dust/AndroidLibXrayLite 76a6e2172210613fcb4aa32b6de55bcd9452ce43
git -C AndroidLibXrayLite reset --mixed FETCH_HEAD
python3 tools/test_xray_compat.py
python3 tools/build_android_xray.py
python3 tools/build_android_awg_probe.py
NDK_HOME="$ANDROID_NDK_HOME" bash compile-hevtun.sh
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp "libs/$abi/libhev-socks5-tunnel.so" "V2rayNG/app/libs/$abi/"
  cp "libs/$abi/libhevsockstun.so" "V2rayNG/app/libs/$abi/"
done
```

The unchanged official Android libwg-go.so provenance and exact source revision
are documented in V2rayNG/awg/PROVENANCE.md. Its wrapper sources and notices are
included. Native TUN engine versions, upstream downloads and integrity checks are
specified in tools/bootstrap_windows_tun.py and tools/bootstrap_linux_tun.py.
