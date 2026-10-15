# Desktop source provenance — DrKozol 1.4.6

DrKozol's Windows WPF and Linux Avalonia clients are derived from GPL-3.0 v2rayN. Both use the DrKozol subscription/controller code in `v2rayN/ServiceLib/DrKozol`; each platform owns its window, images and desktop integration.
- Repository: https://github.com/2dust/v2rayN
- Revision: `1883178b5f1e222b6c6db2782e7a3d989b7cc18f`
- Branding, compact desktop layouts, black custom titlebar, account guard, onboarding, connected-server ping and separate delay results: DrKozol changes. The DK wordmark replaces the earlier shield artwork.
- Layout references: user-supplied Incy screenshots, https://github.com/INCY-DEV/incy-platforms and https://incy.cc/. Incy executables and application assets are not bundled.
- Theme state retains the existing `UIItem.CurrentTheme` and `UIItem.ColorPrimaryName` fields; Ocean is the default; additional color palettes provide Light and Dark choices, with an OLED black option. Country presentation reads configuration remarks, not a guessed server location.
- Xray: built from the version pinned by `AndroidLibXrayLite/go.mod`, same core source as Android.
- AmneziaWG: `github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260814`, MIT license.
- AmneziaWG host: userspace netstack, local HTTP/SOCKS proxy, IPv4/IPv6 support; the protocol host does not install a driver. Optional TUN is owned separately by the sing-box bridge.
- Third-party .NET packages: versions pinned in `v2rayN/Directory.Packages.props`.
- TUN bridge: official sing-box 1.14.2 Windows amd64 release, GPL-3.0; source https://github.com/SagerNet/sing-box/tree/v1.14.2.
  Archive: https://github.com/SagerNet/sing-box/releases/download/v1.14.2/sing-box-1.14.2-windows-amd64.zip.
  SHA-256: `c2d8bfff918755808781dfdeeb8581b6c91eb3a243d9a7b55483cfc0c0684d32`.
  Bootstrapped by `tools/bootstrap_windows_tun.py`; bundled native notices are retained.
  sing-tun revision `ddaa4ca25e3b` embeds official Wintun 0.14.1; signed driver license is included in the Windows package.
  TUN is an optional front end to the existing local SOCKS engine, including Xray XHTTP; administrator rights are required.

Linux TUN bridge uses the official sing-box 1.14.2 Linux amd64 release, GPL-3.0, from the same source tag:

- Archive: https://github.com/SagerNet/sing-box/releases/download/v1.14.2/sing-box-1.14.2-linux-amd64.tar.gz.
- SHA-256: `a684484d7477d1437282ee411f4d131d0340aaad60a7868841ebd5d87dd8a0c6`.
- Download/verification: `../tools/bootstrap_linux_tun.py`; build entry: `../tools/build_linux.sh`.
- Linux Xray uses the same `AndroidLibXrayLite/go.mod` pin as Android and Windows. The AmneziaWG host is built from the same `AwgHost` module for `linux/amd64`.
- `../Linux/install-tun.sh` applies `cap_net_admin+ep` only to the bundled `bin/sing_box/sing-box` executable. The permission helper runs with authorization; the GUI remains the normal desktop user. Linux System Proxy reads and restores GNOME settings.
- Native engine process ownership, routing teardown, subscription quota and expiry guards are shared. No separate Incy tunnel binary is used.

Country flags use [lipis/flag-icons v7.3.2](https://github.com/lipis/flag-icons/tree/v7.3.2), MIT, copyright Panayiotis Lipiridis. The 4:3 country SVGs were converted to small PNG assets and embedded in `v2rayN/ServiceLib/DrKozol/Flags`. The original [license](v2rayN/ServiceLib/DrKozol/Flags/LICENSE.txt) is retained. WPF and Avalonia load these assets for country codes read from configuration names; unknown countries use their own vector globe icons.

DrKozol 1.4.6 rebuilds Android and desktop Xray with the same explicit plaintext-VLESS compatibility patch from `tools/prepare_xray_compat.py`. The opt-in is per outbound; certificate verification is retained. Android also packages a standalone AmneziaWG real-delay probe built from the desktop userspace host for all four ABIs. HEV and the official Android libwg-go tunnel libraries retain their preceding verified inputs. The base bootstrap manifest remains pinned to 1.1.0; the build scripts replace its Xray AAR and add the probe before assembling 1.4.6. The release native-input archive includes the final AAR, helpers, hashes and provenance. Desktop sing-box archives are verified independently by their platform bootstrap scripts.

Release downloads and feedback are maintained at https://github.com/SpecterLogic/Drkozol. Corresponding source is maintained at https://github.com/SpecterLogic/Drkozol-source.

The upstream source and all retained copyright and license notices remain applicable.

Version 1.4.6 adds shared desktop preferences, per-provider settings, scheduled refresh, round-robin health checks, session recovery, authenticated LAN proxy sharing and application routing. Generated tunnel configurations include IPv6 routes and an explicit DNS resolver. Local subscription backups retain profile and group identities.
