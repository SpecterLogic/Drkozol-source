package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/proxy"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"
)

func keyPair(t *testing.T) ([]byte, []byte) {
	t.Helper()
	secret := make([]byte, 32)
	rand.Read(secret)
	public, e := curve25519.X25519(secret, curve25519.Basepoint)
	if e != nil {
		t.Fatal(e)
	}
	return secret, public
}

const obfuscation = "jc=2\njmin=5\njmax=20\ns1=8\ns2=9\ns3=4\ns4=6\nh1=12341\nh2=12342\nh3=12343\nh4=12344\n"

func TestEncryptedAmneziaHTTPAndSOCKS(t *testing.T) {
	serverSecret, serverPublic := keyPair(t)
	clientSecret, clientPublic := keyPair(t)
	reserve, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	port := reserve.LocalAddr().(*net.UDPAddr).Port
	reserve.Close()
	serverTun, serverNet, e := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.88.0.1")}, nil, 1420)
	if e != nil {
		t.Fatal(e)
	}
	server := device.NewDevice(serverTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer server.Close()
	if e = server.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\n%sreplace_peers=true\npublic_key=%s\nallowed_ip=10.88.0.2/32\n", hex.EncodeToString(serverSecret), port, obfuscation, hex.EncodeToString(clientPublic))); e != nil {
		t.Fatal(e)
	}
	if e = server.Up(); e != nil {
		t.Fatal(e)
	}
	listener, e := serverNet.ListenTCPAddrPort(netip.MustParseAddrPort("10.88.0.1:8181"))
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "native-encrypted-AWG") })}
	go httpServer.Serve(listener)
	defer httpServer.Close()
	raw := fmt.Sprintf("[Interface]\nPrivateKey=%s\nAddress=10.88.0.2/32\n%s[Peer]\nPublicKey=%s\nAllowedIPs=10.88.0.1/32\nEndpoint=127.0.0.1:%d\n", base64.StdEncoding.EncodeToString(clientSecret), obfuscation, base64.StdEncoding.EncodeToString(serverPublic), port)
	cfg, e := parse(raw)
	if e != nil {
		t.Fatal(e)
	}
	clientTun, clientNet, e := netstack.CreateNetTUN(cfg.ips, cfg.dns, cfg.mtu)
	if e != nil {
		t.Fatal(e)
	}
	client := device.NewDevice(clientTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer client.Close()
	if e = client.IpcSet(cfg.ipc); e != nil {
		t.Fatal(e)
	}
	if e = client.Up(); e != nil {
		t.Fatal(e)
	}
	proxyListener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer proxyListener.Close()
	go func() {
		for {
			c, e := proxyListener.Accept()
			if e != nil {
				return
			}
			go serve(c, clientNet)
		}
	}()
	proxyURL, _ := url.Parse("http://" + proxyListener.Addr().String())
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
	defer transport.CloseIdleConnections()
	httpClient := &http.Client{Transport: transport, Timeout: 12 * time.Second}
	response, e := httpClient.Get("http://10.88.0.1:8181/")
	if e != nil {
		t.Fatal(e)
	}
	body, e := io.ReadAll(response.Body)
	response.Body.Close()
	if e != nil || string(body) != "native-encrypted-AWG" {
		t.Fatal("encrypted HTTP failed")
	}
	dialer, e := proxy.SOCKS5("tcp", proxyListener.Addr().String(), nil, &net.Dialer{Timeout: 5 * time.Second})
	if e != nil {
		t.Fatal(e)
	}
	socket, e := dialer.Dial("tcp", "10.88.0.1:8181")
	if e != nil {
		t.Fatal(e)
	}
	defer socket.Close()
	socket.SetDeadline(time.Now().Add(5 * time.Second))
	fmt.Fprint(socket, "GET / HTTP/1.1\r\nHost: 10.88.0.1\r\nConnection: close\r\n\r\n")
	data, e := io.ReadAll(socket)
	if e != nil || !strings.Contains(string(data), "native-encrypted-AWG") {
		t.Fatal("encrypted SOCKS failed")
	}
}
func TestRejectUnsafeAndIncompleteConfiguration(t *testing.T) {
	for _, raw := range []string{"", "[Interface]\nAddress=invalid", "[Interface]\nPostUp=cmd.exe", "[Peer]\nPrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="} {
		if _, e := parse(raw); e == nil {
			t.Fatalf("accepted invalid config")
		}
	}
}
