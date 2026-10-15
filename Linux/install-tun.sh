#!/bin/sh
set -eu
# Called only by the TUN permission button using pkexec; the GUI remains the desktop user.
base=$(CDPATH= cd -- "${1:-$(dirname -- "$0")}" && pwd)
binary="$base/bin/sing_box/sing-box"
[ -f "$binary" ] && [ -x "$binary" ] || { printf '%s\n' 'TUN engine missing. Extract the complete package.' >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || { printf '%s\n' 'Use the TUN permission button or sudo to run this helper.' >&2; exit 1; }
command -v setcap >/dev/null || { printf '%s\n' 'Install libcap2-bin and retry.' >&2; exit 1; }
setcap cap_net_admin+ep "$binary"
printf '%s\n' 'TUN permission installed. The GUI does not need root.'
