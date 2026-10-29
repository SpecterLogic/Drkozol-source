using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using ServiceLib.Services;

namespace ServiceLib.DrKozol;

public sealed class AmneziaHost : IAsyncDisposable
{
    private Process? _process;
    private WindowsJobService? _job;
    public bool Running { get { try { return _process is not null && !_process.HasExited; } catch { return false; } } }
    public event Action<long, long>? Stats;
    public static string DisplayName(string input)
    {
        if (!input.StartsWith("awg://", StringComparison.OrdinalIgnoreCase) && !input.StartsWith("vpn://", StringComparison.OrdinalIgnoreCase)) return "AmneziaWG";
        var index = input.IndexOf('#');
        if (index < 0 || index == input.Length - 1) return "AmneziaWG";
        try { return Uri.UnescapeDataString(input[(index + 1)..]).Trim() is { Length: > 0 } name ? name : "AmneziaWG"; }
        catch (UriFormatException) { return "AmneziaWG"; }
    }
    public static string? Decode(string input)
    {
        try
        {
            var raw = input.Trim();
            if (raw.StartsWith("awg://", StringComparison.OrdinalIgnoreCase) || raw.StartsWith("vpn://", StringComparison.OrdinalIgnoreCase))
            {
                var encoded = raw.Split("://", 2)[1].Split('#')[0].Replace('-', '+').Replace('_', '/');
                encoded = encoded.PadRight((encoded.Length + 3) / 4 * 4, '=');
                raw = Encoding.UTF8.GetString(Convert.FromBase64String(encoded));
            }
            return raw.TrimStart().StartsWith("[Interface]", StringComparison.OrdinalIgnoreCase) ? raw : null;
        }
        catch { return null; }
    }
    public static async Task<IPAddress[]> EndpointAddresses(string raw, CancellationToken token)
    {
        var decoded = Decode(raw) ?? throw new InvalidDataException("Invalid AmneziaWG configuration");
        var addresses = new List<IPAddress>();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(8));
        foreach (var line in decoded.Split('\n'))
        {
            var parts = line.Split('=', 2, StringSplitOptions.TrimEntries);
            if (parts.Length != 2 || !parts[0].Equals("Endpoint", StringComparison.OrdinalIgnoreCase)) continue;
            var value = parts[1].Split('#', 2)[0].Trim();
            if (!Uri.TryCreate("udp://" + value, UriKind.Absolute, out var endpoint) || endpoint.Port is < 1 or > 65535)
                throw new InvalidDataException("Invalid AmneziaWG endpoint");
            if (IPAddress.TryParse(endpoint.DnsSafeHost, out var address)) addresses.Add(address);
            else addresses.AddRange(await Dns.GetHostAddressesAsync(endpoint.DnsSafeHost, deadline.Token));
        }
        return addresses.Distinct().ToArray();
    }

    public async Task Start(string raw, int port, bool check = false)
    {
        var ready = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _process = new Process { StartInfo = new ProcessStartInfo(Path.Combine(Utils.GetBaseDirectory(), "bin", Utils.GetExeName("drkozol-awg"))) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true } };
        _process.OutputDataReceived += (_, e) =>
        {
            if (e.Data is null) { ready.TrySetException(new IOException("AmneziaWG host exited")); return; }
            if (string.IsNullOrWhiteSpace(e.Data)) return;
            try
            {
                using var doc = JsonDocument.Parse(e.Data);
                var kind = doc.RootElement.GetProperty("kind").GetString();
                if (kind is "ready" or "checked") ready.TrySetResult();
                if (kind == "stats") Stats?.Invoke(doc.RootElement.GetProperty("up").GetInt64(), doc.RootElement.GetProperty("down").GetInt64());
            }
            catch { }
        };
        _process.ErrorDataReceived += (_, _) => { /* Native errors intentionally contain no credentials. */ };
        _process.EnableRaisingEvents = true;
        await NativeProcessLease.Prepare(_process.StartInfo);
        if (!_process.Start()) throw new IOException();
        if (OperatingSystem.IsWindows()) { _job = new WindowsJobService(); _job.AddProcess(_process.Handle); }
        _process.BeginOutputReadLine(); _process.BeginErrorReadLine();
        await _process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(new { config = raw, port, check }));
        await _process.StandardInput.FlushAsync();
        await ready.Task.WaitAsync(TimeSpan.FromSeconds(12));
    }
    public static async Task Validate(string raw)
    {
        await using var host = new AmneziaHost(); await host.Start(raw, 0, true);
    }
    public static async Task<int> RealDelay(string raw, CancellationToken token, string url = "https://www.gstatic.com/generate_204")
    {
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
        await using var host = new AmneziaHost(); await host.Start(raw, port);
        using var client = new HttpClient(new HttpClientHandler { Proxy = new WebProxy($"http://127.0.0.1:{port}"), UseProxy = true }) { Timeout = TimeSpan.FromSeconds(15) };
        var watch = Stopwatch.StartNew(); using var response = await client.GetAsync(url, token); response.EnsureSuccessStatusCode();
        return (int)watch.ElapsedMilliseconds;
    }
    public async ValueTask DisposeAsync()
    {
        if (_process is null) return;
        try
        {
            if (!_process.HasExited) { await _process.StandardInput.WriteLineAsync("stop"); await _process.StandardInput.FlushAsync(); await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
        }
        catch { try { if (!_process.HasExited) _process.Kill(true); } catch { } }
        if (OperatingSystem.IsWindows()) _job?.Dispose(); _process.Dispose(); _process = null;
    }
}
