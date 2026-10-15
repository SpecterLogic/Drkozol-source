#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out="$repo/artifacts/linux"
mkdir -p "$out/bin/xray"
python3 "$repo/tools/prepare_xray_compat.py"
(cd "$repo/AndroidLibXrayLite" && go test -modfile "$repo/artifacts/native/xray-compat.mod" github.com/xtls/xray-core/infra/conf -run '^TestDrKozol' -count=1 && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -modfile "$repo/artifacts/native/xray-compat.mod" -trimpath -ldflags '-s -w -checklinkname=0' -o "$out/bin/xray/xray" github.com/xtls/xray-core/main)
(cd "$repo/Windows/v2rayN/AwgHost" && go test -timeout 40s . && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags '-s -w -checklinkname=0' -o "$out/bin/drkozol-awg" .)
python3 "$repo/tools/bootstrap_linux_tun.py" "$out/bin/sing_box"
dotnet publish "$repo/Windows/v2rayN/v2rayN.Desktop/v2rayN.Desktop.csproj" -c Release -r linux-x64 --self-contained true -p:PublishSingleFile=false -o "$out"
cp "$repo"/V2rayNG/app/src/main/assets/*.dat "$out/bin/"
cp "$repo/Linux/install-tun.sh" "$out/install-tun.sh"
chmod 755 "$out/DrKozol" "$out/install-tun.sh"
