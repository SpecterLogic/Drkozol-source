$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
python "$PSScriptRoot/prepare_xray_compat.py"
if ($LASTEXITCODE -ne 0) { throw 'Pinned Xray compatibility preparation failed' }
$compatibilityModFile = Join-Path $repoRoot 'artifacts/native/xray-compat.mod'
$nativeOutput = Join-Path $repoRoot 'artifacts/windows/bin'
New-Item -ItemType Directory -Force "$nativeOutput/Xray" | Out-Null
Push-Location "$repoRoot/AndroidLibXrayLite"
try {
    $env:GOOS = 'windows'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
    go test -modfile "$compatibilityModFile" github.com/xtls/xray-core/infra/conf -run '^TestDrKozol' -count=1
    if ($LASTEXITCODE -ne 0) { throw 'Xray protocol compatibility tests failed' }
    go build -modfile "$compatibilityModFile" -trimpath -ldflags '-s -w -checklinkname=0' -o "$nativeOutput/Xray/xray.exe" github.com/xtls/xray-core/main
    if ($LASTEXITCODE -ne 0) { throw 'Xray build failed' }
} finally { Pop-Location }
Push-Location "$repoRoot/Windows/v2rayN/AwgHost"
try {
    go test -timeout 40s .
    if ($LASTEXITCODE -ne 0) { throw 'AmneziaWG encrypted loopback tests failed' }
    go build -trimpath -ldflags '-s -w -checklinkname=0' -o "$nativeOutput/drkozol-awg.exe" .
    if ($LASTEXITCODE -ne 0) { throw 'AmneziaWG build failed' }
} finally { Pop-Location }
dotnet publish "$repoRoot/Windows/v2rayN/v2rayN/v2rayN.csproj" -c Release -r win-x64 --self-contained true -o "$repoRoot/artifacts/windows"
if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed' }
Copy-Item -Path "$repoRoot/V2rayNG/app/src/main/assets/*.dat" -Destination $nativeOutput -Force
python "$PSScriptRoot/bootstrap_windows_tun.py" "$nativeOutput/sing_box"
if ($LASTEXITCODE -ne 0) { throw 'Pinned TUN engine bootstrap failed' }
