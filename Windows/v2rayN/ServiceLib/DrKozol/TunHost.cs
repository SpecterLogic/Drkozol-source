using System.Net.NetworkInformation;
using System.Net;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text.Json;
using System.Text.Json.Nodes;
using ServiceLib.Services;

namespace ServiceLib.DrKozol;

// Owns only the TUN adapter and routing bridge; Xray/AmneziaHost retain protocol ownership.
public sealed class TunHost : IAsyncDisposable
{
    private Process? _process;
    private WindowsJobService? _job;
    private string? _configPath;
    public string? StartupFailure { get; private set; }
    public bool Running { get { try { return _process is not null && !_process.HasExited; } catch { return false; } } }
    public static bool IsAdministrator => OperatingSystem.IsWindows()
        ? new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)
        : OperatingSystem.IsLinux() && GetEffectiveUserId() == 0;
    public static bool HasTunPermission => IsAdministrator || OperatingSystem.IsLinux() && HasNetworkCapability();

    private static bool HasNetworkCapability()
    {
        var bytes = new byte[24];
        var executable = Path.Combine(Utils.GetBaseDirectory(), "bin", "sing_box", "sing-box");
        var length = GetXattr(executable, "security.capability", bytes, (nuint)bytes.Length);
        return length >= 12 && (BitConverter.ToUInt32(bytes, 0) & 1) != 0
            && (BitConverter.ToUInt32(bytes, 4) & (1u << 12)) != 0;
    }
    [DllImport("libc", EntryPoint = "geteuid")]
    private static extern uint GetEffectiveUserId();
    [DllImport("libc", EntryPoint = "getxattr")]
    private static extern nint GetXattr(string path, string name, byte[] value, nuint size);

    // Windows strict_route blocks physical-interface DNS. Resolve the protocol endpoint
    // before enabling TUN so DoH through that endpoint cannot wait on its own DNS tunnel.
    // This is a connection-only copy: subscription names, addresses and credentials stay intact.
    public static ProfileItem WithResolvedEndpoint(ProfileItem profile, IPAddress address)
    {
        var copy = JsonUtils.DeepCopy(profile);
        copy.Address = address.ToString();
        if (copy.StreamSecurity == Global.StreamSecurity && copy.Sni.IsNullOrEmpty())
        {
            var transport = profile.GetTransportExtra();
            var host = profile.GetNetwork() == nameof(ETransport.grpc) ? transport.GrpcAuthority : transport.Host;
            copy.Sni = Utils.String2List(host)?.FirstOrDefault() ?? profile.Address;
        }
        return copy;
    }

    public static async Task<ProfileItem> ResolveEndpoint(ProfileItem profile, CancellationToken token)
    {
        if (profile.IsComplex() || IPAddress.TryParse(profile.Address, out _)) return profile;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(8));
        var addresses = await Dns.GetHostAddressesAsync(profile.Address, deadline.Token);
        var address = addresses.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork) ?? addresses.FirstOrDefault() ?? throw new SocketException((int)SocketError.HostNotFound);
        return WithResolvedEndpoint(profile, address);
    }

    public static async Task ResolveContextEndpoints(CoreConfigContext context, CancellationToken token,
        Func<ProfileItem, CancellationToken, Task<ProfileItem>>? resolver = null)
    {
        resolver ??= ResolveEndpoint;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(8));
        var resolved = new ConcurrentDictionary<string, ProfileItem>();
        await Parallel.ForEachAsync(context.AllProxiesMap.ToArray(), new ParallelOptions {
            MaxDegreeOfParallelism = 8, CancellationToken = token }, async (entry, _) => {
            try { resolved[entry.Key] = await resolver(entry.Value, deadline.Token); }
            catch (Exception error) when (context.Node.ConfigType == EConfigType.PolicyGroup && !token.IsCancellationRequested
                && error is SocketException or OperationCanceledException)
            {
                // A failed member must not delay every healthy member. Observatory can mark
                // the unchanged hostname unavailable; TLS identity and stored profiles stay intact.
                resolved[entry.Key] = entry.Value;
            }
        });
        foreach (var entry in resolved) context.AllProxiesMap[entry.Key] = entry.Value;
    }

    public static string Configuration(int port, DesktopPreferences? preferences = null, string? dns = null, bool enableTun = true)
    {
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        // sing-box 1.14 typed DNS and route actions. Native process bypass prevents tunnelling
        // the protocol engines back into their own SOCKS listener; DNS for applications uses DoH.
        var configuration = $$$"""
        {
          "log": {"disabled": true},
          "dns": {"servers": [{"type":"https","tag":"remote","server":"1.1.1.1","detour":"proxy","tls":{"enabled":true,"server_name":"cloudflare-dns.com"}}],"final":"remote"},
          "inbounds": [{"type":"tun","tag":"tun","interface_name":"DrKozol","address":["172.29.255.1/30","fdfe:dcba:9876::1/126"],"mtu":1400,"auto_route":true,"strict_route":true,"stack":"mixed"}],
          "outbounds": [{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":{{{port}}},"version":"5"},{"type":"direct","tag":"direct"}],
          "route": {"auto_detect_interface":true,"default_domain_resolver":"remote","rules":[
            {"process_name":["xray.exe","drkozol-awg.exe","sing-box.exe","xray","drkozol-awg","sing-box"],"action":"route","outbound":"direct"},
            {"port":53,"action":"hijack-dns"},
            {"ip_is_private":true,"action":"route","outbound":"direct"}
          ],"final":"proxy"}
        }
        """;
        var document = JsonNode.Parse(configuration)!;
        var inbounds = (JsonArray)document["inbounds"]!;
        if (!enableTun) inbounds.Clear();
        if (preferences is { ShareProxy: true })
        {
            if (preferences.SharingPort == port || preferences.SharingPort is < 1 or > 65535
                || string.IsNullOrWhiteSpace(preferences.ProxyUser) || string.IsNullOrWhiteSpace(preferences.ProxyPassword))
                throw new ArgumentException("Proxy sharing requires a distinct port and authentication");
            inbounds.Add(new JsonObject { ["type"] = "mixed", ["tag"] = "shared", ["listen"] = "0.0.0.0",
                ["listen_port"] = preferences.SharingPort, ["users"] = new JsonArray(new JsonObject {
                    ["username"] = preferences.ProxyUser, ["password"] = preferences.ProxyPassword }) });
        }
        if (preferences is { ApplicationMode: "include" or "exclude" })
        {
            var names = preferences.Applications.Split([',', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            if (names.Length == 0) throw new ArgumentException("Choose applications before enabling application routing");
            var rules = (JsonArray)document["route"]!["rules"]!;
            rules.Add(new JsonObject { ["process_name"] = new JsonArray(names.Select(n => (JsonNode?)JsonValue.Create(n)).ToArray()),
                ["action"] = "route", ["outbound"] = preferences.ApplicationMode == "include" ? "proxy" : "direct" });
            if (preferences.ApplicationMode == "include") document["route"]!["final"] = "direct";
        }
        if (preferences is { RouteLan: true } or { AllowLan: false })
            ((JsonArray)document["route"]!["rules"]!).RemoveAt(2);
        if (!string.IsNullOrWhiteSpace(dns))
        {
            var servers = (JsonArray)document["dns"]!["servers"]!;
            if (IPAddress.TryParse(dns.Trim(), out var address))
                servers.Insert(0, new JsonObject { ["type"] = "udp", ["tag"] = "custom", ["server"] = address.ToString(), ["detour"] = "proxy" });
            else if (DesktopPreferences.ValidHttps(dns))
            {
                var uri = new Uri(dns);
                servers.Insert(0, new JsonObject { ["type"] = "https", ["tag"] = "custom", ["server"] = uri.Host,
                    ["server_port"] = uri.Port, ["path"] = uri.AbsolutePath, ["detour"] = "proxy",
                    ["domain_resolver"] = "remote", ["tls"] = new JsonObject { ["enabled"] = true, ["server_name"] = uri.Host } });
            }
            else throw new ArgumentException("Invalid tunnel DNS");
            document["dns"]!["final"] = "custom";
            document["route"]!["default_domain_resolver"] = "custom";
        }
        return document.ToJsonString();
    }

    public async Task Start(int port, CancellationToken token, DesktopPreferences? preferences = null, string? dns = null, bool enableTun = true)
    {
        if (enableTun && !HasTunPermission) throw new UnauthorizedAccessException();
        var executable = Path.Combine(Utils.GetBaseDirectory(), "bin", "sing_box", Utils.GetExeName("sing-box"));
        if (!File.Exists(executable)) throw new FileNotFoundException();
        _configPath = Utils.GetConfigPath("drkozol-tun.json");
        await File.WriteAllTextAsync(_configPath, Configuration(port, preferences, dns, enableTun), token);
        _process = new Process { StartInfo = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(executable)!, RedirectStandardError = true, RedirectStandardOutput = true } };
        _process.StartInfo.ArgumentList.Add("run"); _process.StartInfo.ArgumentList.Add("-c"); _process.StartInfo.ArgumentList.Add(_configPath);
        await NativeProcessLease.Prepare(_process.StartInfo, token);
        _process.OutputDataReceived += (_, _) => { };
        _process.ErrorDataReceived += (_, line) => {
            if (line.Data is not { } text) return;
            foreach (var reason in new[] { "permission denied", "operation not permitted", "not supported", "invalid argument", "file exists", "device or resource busy", "network is unreachable", "no such file", "missing default interface" })
                if (text.Contains(reason, StringComparison.OrdinalIgnoreCase)) StartupFailure = reason;
        };
        if (!_process.Start()) throw new IOException();
        if (OperatingSystem.IsWindows()) { _job = new WindowsJobService(); _job.AddProcess(_process.Handle); }
        _process.BeginOutputReadLine(); _process.BeginErrorReadLine();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token); deadline.CancelAfter(TimeSpan.FromSeconds(12));
        while (true)
        {
            deadline.Token.ThrowIfCancellationRequested();
            if (!Running) { await _process.WaitForExitAsync(token); StartupFailure ??= "native exit " + _process.ExitCode; throw new IOException(); }
            var ready = !enableTun || await Task.Run(() => NetworkInterface.GetAllNetworkInterfaces().Any(n => n.Name == "DrKozol"
                && n.OperationalStatus == OperationalStatus.Up
                && n.GetIPProperties().UnicastAddresses.Any(a => a.Address.ToString() == "172.29.255.1")), deadline.Token);
            if (ready && preferences is { ShareProxy: true })
            {
                using var client = new TcpClient();
                try { await client.ConnectAsync(IPAddress.Loopback, preferences.SharingPort, deadline.Token); }
                catch (SocketException) { ready = false; }
            }
            if (ready) { await Task.Delay(300, deadline.Token); if (!Running) throw new IOException(); return; }
            await Task.Delay(100, deadline.Token);
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_process is not null)
        {
            try { if (!_process.HasExited) { NativeProcessLease.RequestStop(_process); await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); } }
            catch (TimeoutException) { if (!_process.HasExited) _process.Kill(true); }
            finally { if (OperatingSystem.IsWindows()) _job?.Dispose(); _process.Dispose(); _process = null; }
        }
        if (_configPath is not null) { try { File.Delete(_configPath); } catch (IOException) { } _configPath = null; }
    }
}
