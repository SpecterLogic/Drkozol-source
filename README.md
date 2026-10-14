# Drkozol Android corresponding source

Copyright © 2026 Drkozol.

Android application source for release 1.4.5. APK downloads and feedback:
https://github.com/SpecterLogic/Drkozol

This repository is a clean source snapshot without previous development history.
The Android application is distributed under GPLv3; see LICENSE. Individual
components retain their respective copyrights and licenses. Drkozol branding and
original artwork are reserved; this does not restrict rights granted by the software licenses.

The source release contains the same snapshot with verified native compiler inputs
and public routing data. Download DrKozol-source-1.4.5.zip from this repository's
Releases to build without any private bootstrap service. Signing keys, credentials,
subscription accounts and local settings are excluded.

## Build from the source release archive

Install JDK 21, Android SDK platform android-37.0, build-tools 37.0.0, and NDK
30.0.16248370. Set ANDROID_HOME and JAVA_HOME. In V2rayNG run:

```sh
chmod +x gradlew
./gradlew :app:testPlaystoreDebugUnitTest :app:assemblePlaystoreRelease
```

The archive includes the native inputs used for the released APK. It does not
include the release signing key; rebuilding produces an unsigned release.

## Rebuild native components

All pinned wrapper and HEV source trees are included. SOURCE-MODULES.json records
their upstream revisions. Go modules are pinned in go.mod/go.sum. Install Go
1.27.2 and set ANDROID_NDK_HOME to the NDK above. To restore the wrapper's upstream
revision metadata without replacing its provided source files:

```sh
git -C AndroidLibXrayLite init
git -C AndroidLibXrayLite fetch --depth=1 https://github.com/2dust/AndroidLibXrayLite 1f3e2c5df1c248ed42630cee2bf747643d303ecc
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

The unchanged official AmneziaWG libwg-go.so provenance and its exact upstream
source commit are documented in V2rayNG/awg/PROVENANCE.md. The Android Java wrapper
source, its changes and license are included. Other component licenses are retained
in their source trees and Android legal notices. Personal identities and private
development links are not part of this source distribution.
