// SPDX-License-Identifier: GPL-3.0-or-later
// DrKozol userspace AmneziaWG proxy. No machine-wide routes or drivers are installed.
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type request struct {
	Config string `json:"config"`
	Port   int    `json:"port"`
	Check  bool   `json:"check"`
}
type config struct {
	ipc      string
	ips, dns []netip.Addr
	mtu      int
}

func parse(text string) (config, error) {
	c := config{mtu: 1420}
	var out strings.Builder
	section := ""
	peer := false
	private := false
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(line)
			if section == "[peer]" {
				if !peer {
					out.WriteString("replace_peers=true\n")
				}
				out.WriteString("[peer]\n")
				peer = true
			} else if section != "[interface]" || peer {
				return c, errors.New("section")
			}
			continue
		}
		pair := strings.SplitN(line, "=", 2)
		if len(pair) != 2 {
			return c, errors.New("attribute")
		}
		k := strings.ToLower(strings.TrimSpace(pair[0]))
		v := strings.TrimSpace(pair[1])
		target := k
		peerAttribute := k == "publickey" || k == "presharedkey" || k == "endpoint" || k == "persistentkeepalive" || k == "allowedips"
		if (section == "[peer]") != peerAttribute {
			return c, errors.New("attribute section")
		}
		switch k {
		case "address":
			for _, item := range strings.Split(v, ",") {
				p, e := netip.ParsePrefix(strings.TrimSpace(item))
				if e != nil {
					return c, e
				}
				c.ips = append(c.ips, p.Addr())
			}
			continue
		case "dns":
			for _, item := range strings.Split(v, ",") {
				a, e := netip.ParseAddr(strings.TrimSpace(item))
				if e != nil {
					return c, e
				}
				c.dns = append(c.dns, a)
			}
			continue
		case "mtu":
			n, e := strconv.Atoi(v)
			if e != nil || n < 576 || n > 65535 {
				return c, errors.New("mtu")
			}
			c.mtu = n
			continue
		case "privatekey", "publickey", "presharedkey":
			raw, e := base64.StdEncoding.DecodeString(v)
			if e != nil || len(raw) != 32 {
				return c, errors.New("key")
			}
			v = hex.EncodeToString(raw)
			if k == "privatekey" {
				target = "private_key"
				private = true
			} else if k == "publickey" {
				target = "public_key"
			} else {
				target = "preshared_key"
			}
		case "allowedips":
			out.WriteString("replace_allowed_ips=true\n")
			for _, item := range strings.Split(v, ",") {
				p, e := netip.ParsePrefix(strings.TrimSpace(item))
				if e != nil {
					return c, e
				}
				fmt.Fprintf(&out, "allowed_ip=%s\n", p.Masked())
			}
			continue
		case "endpoint":
			endpoint, e := net.ResolveUDPAddr("udp", v)
			if e != nil {
				return c, e
			}
			v = endpoint.String()
		case "persistentkeepalive":
			target = "persistent_keepalive_interval"
		case "listenport":
			target = "listen_port"
		case "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5":
		case "headerprotectionkey":
			target = "header_protection_key"
			raw, e := base64.StdEncoding.DecodeString(v)
			if e != nil || len(raw) != 32 {
				return c, errors.New("header key")
			}
			v = hex.EncodeToString(raw)
		case "contentpaddingaddition":
			target = "content_padding_addition"
		case "rekeyaftertime":
			target = "rekey_after_time"
		case "rekeytimeout":
			target = "rekey_timeout"
		case "rejectaftertime":
			target = "reject_after_time"
		case "keepalivetimeout":
			target = "keepalive_timeout"
		case "maxhandshakeattempts":
			target = "max_handshake_attempts"
		case "randomtrailers":
			target = "random_trailers"
			if strings.EqualFold(v, "on") {
				v = "1"
			}
			if strings.EqualFold(v, "off") {
				v = "0"
			}
		case "disablecookies":
			target = "disable_cookies"
			if strings.EqualFold(v, "on") {
				v = "1"
			}
			if strings.EqualFold(v, "off") {
				v = "0"
			}
		default:
			return c, errors.New("unsupported attribute")
		}
		if section == "" {
			return c, errors.New("missing section")
		}
		fmt.Fprintf(&out, "%s=%s\n", target, v)
	}
	if scanner.Err() != nil {
		return c, scanner.Err()
	}
	if !private || !peer || len(c.ips) == 0 {
		return c, errors.New("incomplete")
	}
	if len(c.dns) == 0 {
		c.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	chunks := strings.Split(out.String(), "[peer]\n")
	var ordered strings.Builder
	ordered.WriteString(chunks[0])
	for _, chunk := range chunks[1:] {
		public := ""
		var rest strings.Builder
		for _, line := range strings.Split(chunk, "\n") {
			if strings.HasPrefix(line, "public_key=") {
				public = line
			} else if line != "" {
				rest.WriteString(line + "\n")
			}
		}
		if public == "" {
			return c, errors.New("missing peer key")
		}
		ordered.WriteString(public + "\n" + rest.String())
	}
	c.ipc = ordered.String()
	return c, nil
}

func relay(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(a, b); done <- struct{}{} }()
	go func() { io.Copy(b, a); done <- struct{}{} }()
	<-done
}
func serve(client net.Conn, tnet *netstack.Net) {
	defer client.Close()
	client.SetDeadline(time.Now().Add(20 * time.Second))
	reader := bufio.NewReader(client)
	first, e := reader.Peek(1)
	if e != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if first[0] == 5 {
		header := make([]byte, 2)
		if _, e = io.ReadFull(reader, header); e != nil {
			return
		}
		methods := make([]byte, int(header[1]))
		if _, e = io.ReadFull(reader, methods); e != nil {
			return
		}
		noauth := false
		for _, m := range methods {
			if m == 0 {
				noauth = true
			}
		}
		if !noauth {
			client.Write([]byte{5, 255})
			return
		}
		client.Write([]byte{5, 0})
		head := make([]byte, 4)
		if _, e = io.ReadFull(reader, head); e != nil || head[0] != 5 || head[1] != 1 {
			return
		}
		host := ""
		switch head[3] {
		case 1:
			raw := make([]byte, 4)
			if _, e = io.ReadFull(reader, raw); e != nil {
				return
			}
			host = net.IP(raw).String()
		case 4:
			raw := make([]byte, 16)
			if _, e = io.ReadFull(reader, raw); e != nil {
				return
			}
			host = net.IP(raw).String()
		case 3:
			n, e := reader.ReadByte()
			if e != nil {
				return
			}
			raw := make([]byte, int(n))
			if _, e = io.ReadFull(reader, raw); e != nil {
				return
			}
			host = string(raw)
		default:
			return
		}
		raw := make([]byte, 2)
		if _, e = io.ReadFull(reader, raw); e != nil {
			return
		}
		remote, e := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(raw)))))
		if e != nil {
			client.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
			return
		}
		client.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		client.SetDeadline(time.Time{})
		if reader.Buffered() > 0 {
			data, _ := reader.Peek(reader.Buffered())
			remote.Write(data)
			reader.Discard(len(data))
		}
		relay(client, remote)
		return
	}
	req, e := http.ReadRequest(reader)
	if e != nil {
		return
	}
	if req.Method == "CONNECT" {
		remote, e := tnet.DialContext(ctx, "tcp", req.Host)
		if e != nil {
			io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n")
		client.SetDeadline(time.Time{})
		if reader.Buffered() > 0 {
			data, _ := reader.Peek(reader.Buffered())
			remote.Write(data)
			reader.Discard(len(data))
		}
		relay(client, remote)
		return
	}
	if req.URL.Scheme != "http" || req.URL.Host == "" {
		return
	}
	req.RequestURI = ""
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authorization")
	req.Close = true
	client.SetDeadline(time.Time{})
	transport := &http.Transport{DialContext: tnet.DialContext, DisableKeepAlives: true, ResponseHeaderTimeout: 15 * time.Second}
	defer transport.CloseIdleConnections()
	response, e := transport.RoundTrip(req)
	if e != nil {
		io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer response.Body.Close()
	response.Close = true
	response.Write(client)
}

func run() error {
	input := bufio.NewScanner(os.Stdin)
	input.Buffer(make([]byte, 4096), 5*1024*1024)
	if !input.Scan() {
		return errors.New("input")
	}
	var req request
	if e := json.Unmarshal(input.Bytes(), &req); e != nil {
		return e
	}
	cfg, e := parse(req.Config)
	if e != nil {
		return e
	}
	tun, tnet, e := netstack.CreateNetTUN(cfg.ips, cfg.dns, cfg.mtu)
	if e != nil {
		return e
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	if e = dev.IpcSet(cfg.ipc); e != nil {
		return e
	}
	if req.Check {
		fmt.Println(`{"kind":"checked"}`)
		return nil
	}
	if req.Port < 1 || req.Port > 65535 {
		return errors.New("port")
	}
	if e = dev.Up(); e != nil {
		return e
	}
	listener, e := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(req.Port)))
	if e != nil {
		return e
	}
	defer listener.Close()
	stop := make(chan struct{})
	var once sync.Once
	go func() {
		for input.Scan() {
			if input.Text() == "stop" {
				break
			}
		}
		once.Do(func() { close(stop) })
		listener.Close()
	}()
	fmt.Println(`{"kind":"ready"}`)
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				dump, e := dev.IpcGet()
				if e != nil {
					continue
				}
				var rx, tx uint64
				for _, line := range strings.Split(dump, "\n") {
					pair := strings.SplitN(line, "=", 2)
					if len(pair) != 2 {
						continue
					}
					n, _ := strconv.ParseUint(pair[1], 10, 64)
					if pair[0] == "rx_bytes" {
						rx += n
					}
					if pair[0] == "tx_bytes" {
						tx += n
					}
				}
				fmt.Printf("{\"kind\":\"stats\",\"up\":%d,\"down\":%d}\n", tx, rx)
			}
		}
	}()
	for {
		client, e := listener.Accept()
		if e != nil {
			select {
			case <-stop:
				return nil
			default:
				return e
			}
		}
		go serve(client, tnet)
	}
}
func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, "AmneziaWG setup failed")
		os.Exit(1)
	}
}
