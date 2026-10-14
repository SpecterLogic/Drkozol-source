"""Prepare the pinned Xray source with an explicit per-outbound plain VLESS option.

The upstream security checks remain the default. Only a VLESS outbound carrying
drkozolAllowPlainVless=true may retain a user-supplied security=none configuration.
TLS certificate validation and all other protocols are unchanged.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA256 = '5a976cc578d1160bfadfac8e91b8ca22dff13b7b58e3560044939633f874cb3c'
WIREGUARD_SOURCE_SHA256 = '0d9f7d528ac8a9ebf59de128bcad5e2064ccc9f4a57b183e9bea008d18d2c48f'
WIREGUARD_CLOSE = '''func (tun *netTun) Close() error {
	tun.stack.RemoveNIC(1)
	tun.stack.Close()
	tun.ep.RemoveNotify(tun.notifyHandle)
	tun.ep.Close()

	if tun.events != nil {
		close(tun.events)
	}

	// we don't close incomingPacket, because WriteNotify may be mid-send on it (DNS lookup) and would panic.
	if tun.closed != nil {
		close(tun.closed)
	}

	return nil
}
'''
WIREGUARD_CLOSE_REPLACEMENT = '''func (tun *netTun) Close() error {
	// Xray teardown and WireGuard's failed TUN reader can both close this device.
	// Serialize the complete cleanup, including both channels, exactly once.
	tun.closeOnce.Do(func() {
		tun.stack.RemoveNIC(1)
		tun.stack.Close()
		tun.ep.RemoveNotify(tun.notifyHandle)
		tun.ep.Close()
		if tun.events != nil { close(tun.events) }
		// WriteNotify may still select this channel; never close incomingPacket.
		if tun.closed != nil { close(tun.closed) }
	})
	return nil
}
'''
WIREGUARD_TEST = r'''package wireguard

import (
    "errors"
    "net/netip"
    "os"
    "sync"
    "testing"
    "time"
)

func TestDrKozolConcurrentNetTunClose(t *testing.T) {
    for iteration := 0; iteration < 20; iteration++ {
        dev, _, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.23.45.1")}, nil, 1280, false)
        if err != nil { t.Fatal(err) }
        var callers sync.WaitGroup
        for i := 0; i < 8; i++ {
            callers.Add(1)
            go func() { defer callers.Done(); if err := dev.Close(); err != nil { t.Error(err) } }()
        }
        done := make(chan struct{})
        go func() { callers.Wait(); close(done) }()
        select { case <-done: case <-time.After(5*time.Second): t.Fatal("concurrent cleanup deadlocked") }
        if err := dev.Close(); err != nil { t.Fatal(err) }
        for range dev.Events() {} // EventUp is drained and the channel must be closed.
    }
}

func TestDrKozolNetTunCloseUnblocksReader(t *testing.T) {
    dev, _, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.23.45.1")}, nil, 1280, false)
    if err != nil { t.Fatal(err) }
    result := make(chan error, 1)
    go func() {
        _, err := dev.Read([][]byte{make([]byte, 1500)}, []int{0}, 0)
        result <- err
        // The WireGuard reader starts Device.Close after a read error.
        dev.Close()
    }()
    if err := dev.Close(); err != nil { t.Fatal(err) }
    select {
    case err := <-result: if !errors.Is(err, os.ErrClosed) { t.Fatalf("closed reader: %v", err) }
    case <-time.After(5*time.Second): t.Fatal("closed reader stayed blocked")
    }
    if err := dev.Close(); err != nil { t.Fatal(err) }
}
'''
FIELD = '\tTargetStrategy string           `json:"targetStrategy"`\n'
FIELD_REPLACEMENT = FIELD + '\tDrKozolAllowPlainVless bool `json:"drkozolAllowPlainVless,omitempty"`\n'
CHECK = '''\tif err := validateOutboundTransportSecurity(rawConfig, senderSettings); err != nil {
\t\treturn nil, err
\t}
'''
CHECK_REPLACEMENT = '''\t// DrKozol: preserve an explicitly unencrypted imported VLESS profile. This
\t// option does not bypass TLS validation or affect Trojan/other protocols.
\t// Remove this extension when upstream provides a supported per-outbound opt-in.
\t_, plainVless := rawConfig.(*VLessOutboundConfig)
\tif !(plainVless && c.DrKozolAllowPlainVless) {
\t\tif err := validateOutboundTransportSecurity(rawConfig, senderSettings); err != nil {
\t\t\treturn nil, err
\t\t}
\t}
'''
TEST = r'''package conf

import (
    "encoding/json"
    "testing"
)

func TestDrKozolPlainVlessRequiresExplicitOptIn(t *testing.T) {
    for _, allow := range []bool{false, true} {
        config := `{"protocol":"vless","settings":{"address":"8.8.8.8","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}`
        var outbound OutboundDetourConfig
        if err := json.Unmarshal([]byte(config), &outbound); err != nil { t.Fatal(err) }
        outbound.DrKozolAllowPlainVless = allow
        _, err := outbound.Build()
        if allow && err != nil { t.Fatalf("explicit plain VLESS profile rejected: %v", err) }
        if !allow && err == nil { t.Fatal("plain VLESS default security check bypassed") }
    }
}

func TestDrKozolOptInDoesNotDisableTLSCertificateValidation(t *testing.T) {
    config := `{"protocol":"vless","drkozolAllowPlainVless":true,"settings":{"address":"8.8.8.8","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none"},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"allowInsecure":true}}}`
    var outbound OutboundDetourConfig
    if err := json.Unmarshal([]byte(config), &outbound); err != nil { t.Fatal(err) }
    if _, err := outbound.Build(); err == nil { t.Fatal("TLS certificate validation bypassed") }
}

func TestDrKozolOptInDoesNotAffectPlainTrojan(t *testing.T) {
    config := `{"protocol":"trojan","drkozolAllowPlainVless":true,"settings":{"servers":[{"address":"8.8.8.8","port":443,"password":"fixture"}]},"streamSettings":{"network":"tcp","security":"none"}}`
    var outbound OutboundDetourConfig
    if err := json.Unmarshal([]byte(config), &outbound); err != nil { t.Fatal(err) }
    if _, err := outbound.Build(); err == nil { t.Fatal("Trojan security check bypassed") }
}
'''


def prepare():
    metadata = subprocess.run(['go', 'mod', 'download', '-json', 'github.com/xtls/xray-core'], cwd=ROOT / 'AndroidLibXrayLite',
                              capture_output=True, text=True, check=True)
    module = json.loads(metadata.stdout)
    source = Path(module['Dir'])
    original = (source / 'infra/conf/xray.go').read_bytes()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise SystemExit('Pinned Xray security source changed; review compatibility patch before rebuilding')
    wireguard = (source / 'proxy/wireguard/netstack.go').read_bytes()
    if hashlib.sha256(wireguard).hexdigest() != WIREGUARD_SOURCE_SHA256:
        raise SystemExit('Pinned WireGuard netstack source changed; review close patch before rebuilding')
    patch_digest = hashlib.sha256((FIELD_REPLACEMENT + CHECK_REPLACEMENT + TEST + WIREGUARD_CLOSE_REPLACEMENT + WIREGUARD_TEST).encode()).hexdigest()
    target = ROOT / 'artifacts/native' / ('xray-compat-' + patch_digest[:12])
    marker = target / '.drkozol-compat.json'
    if not marker.exists():
        if target.exists():
            raise SystemExit('Incomplete compatibility source directory; use a fresh output directory')
        shutil.copytree(source, target)
        target.chmod(target.stat().st_mode | stat.S_IWUSR)
        conf = target / 'infra/conf'
        conf.chmod(conf.stat().st_mode | stat.S_IWUSR)
        path = conf / 'xray.go'
        path.chmod(path.stat().st_mode | stat.S_IWUSR)
        text = original.decode()
        if text.count(FIELD) != 1 or text.count(CHECK) != 1:
            raise SystemExit('Xray patch context mismatch')
        path.write_text(text.replace(FIELD, FIELD_REPLACEMENT).replace(CHECK, CHECK_REPLACEMENT), encoding='utf-8')
        test_path = conf / 'drkozol_compat_test.go'
        test_path.write_text(TEST, encoding='utf-8')
        wgdir = target / 'proxy/wireguard'
        wgdir.chmod(wgdir.stat().st_mode | stat.S_IWUSR)
        wgpath = wgdir / 'netstack.go'
        wgpath.chmod(wgpath.stat().st_mode | stat.S_IWUSR)
        wgtext = wireguard.decode()
        if wgtext.count(WIREGUARD_CLOSE) != 1:
            raise SystemExit('WireGuard cleanup patch context mismatch')
        wgtext = wgtext.replace('\t"syscall"\n', '\t"syscall"\n\t"sync"\n', 1)
        wgtext = wgtext.replace('type netTun struct {\n', 'type netTun struct {\n\tcloseOnce sync.Once\n', 1)
        wgpath.write_text(wgtext.replace(WIREGUARD_CLOSE, WIREGUARD_CLOSE_REPLACEMENT), encoding='utf-8')
        wgtest = wgdir / 'drkozol_close_test.go'
        wgtest.write_text(WIREGUARD_TEST, encoding='utf-8')
        subprocess.run(['gofmt', '-w', str(path), str(test_path), str(wgpath), str(wgtest)], check=True)
        marker.write_text(json.dumps({'module': module['Path'], 'version': module['Version'], 'upstream_file_sha256': SOURCE_SHA256,
                                     'wireguard_upstream_file_sha256': WIREGUARD_SOURCE_SHA256,
                                     'compatibility_sha256': patch_digest}, indent=2), encoding='utf-8')
    modfile = ROOT / 'artifacts/native/xray-compat.mod'
    modfile.write_bytes((ROOT / 'AndroidLibXrayLite/go.mod').read_bytes())
    modfile.with_suffix('.sum').write_bytes((ROOT / 'AndroidLibXrayLite/go.sum').read_bytes())
    subprocess.run(['go', 'mod', 'edit', '-modfile', str(modfile), '-replace', 'github.com/xtls/xray-core=' + str(target)],
                   cwd=ROOT / 'AndroidLibXrayLite', check=True)
    print('Pinned Xray per-outbound VLESS compatibility prepared')
    return modfile, target


if __name__ == '__main__':
    prepare()
