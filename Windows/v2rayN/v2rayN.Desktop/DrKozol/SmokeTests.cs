using System.Net;
using System.Net.Http;
using System.Text.Json;
using ServiceLib.DrKozol;
using ServiceLib.Helper;
using System.Diagnostics;
using Avalonia.VisualTree;

namespace v2rayN.Desktop.DrKozol;

internal static class SmokeTests
{
    private static string OutputPath(string name) => Path.Combine(Environment.GetEnvironmentVariable("DRKOZOL_SMOKE_OUTPUT") ?? AppContext.BaseDirectory, name);
    public static async Task Run(DrKozolWindow window)
    {
        var checks = new List<string>(); var success = false; var client = window.TestClient; var stage = "initialization";
        void Check(bool condition, string label) { if (!condition) throw new InvalidOperationException(label); checks.Add(label); }
        try
        {
            await window.InitializeForSmoke();
            Check(!client.Connected, "Linux startup does not connect automatically");
            var palettes = new List<string>();
            foreach (var mode in new[] { "Dark", "Light" }) foreach (var color in new[] { "Forest", "Maroon", "Purple" })
            {
                var palette = DesktopAppearance.For(mode, color);
                Check(palette.IsDark == (mode == "Dark") && Color.TryParse(palette.Background, out _) && Color.TryParse(palette.Accent, out _), $"{color} {mode} palette is valid"); palettes.Add(palette.Accent + palette.Background);
            }
            Check(palettes.Distinct().Count() == 6, "Six distinct desktop color appearances");
            var mockAccount = new SubItem { Id = Guid.NewGuid().ToString("N"), Remarks = "DrKozol Preview", Url = "https://panel.example/sub/preview", Enabled = true, Announcement = "پیام آزمایشی از پنل اشتراک", AutoUpdateInterval = 720,
                UploadBytes = 10000, DownloadBytes = 386000, TotalBytes = 10L * 1024 * 1024 * 1024, ExpiresAt = DateTimeOffset.UtcNow.AddDays(29).ToUnixTimeSeconds(), CheckedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() };
            await SQLiteHelper.Instance.InsertAsync(mockAccount);
            var fixtures = new List<ProfileItem>();
            foreach (var (name, type, transport, security) in new[] {
                ("CDN 🇩🇪", EConfigType.VLESS, "xhttp", "tls"), ("Sweden 🇸🇪", EConfigType.VLESS, "tcp", "reality"),
                ("United States 🇺🇸", EConfigType.VMess, "ws", "tls"), ("Tokyo 🇯🇵", EConfigType.Trojan, "tcp", "tls"),
                ("All Internet 🇬🇧", EConfigType.Shadowsocks, "tcp", ""), ("Unknown server", EConfigType.SOCKS, "tcp", "") })
                fixtures.Add(new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = mockAccount.Id, Remarks = name, ConfigType = type, Address = "server.example", Port = 443, Network = transport, StreamSecurity = security, Password = "preview-not-a-credential" });
            await SQLiteHelper.Instance.InsertAllAsync(fixtures);
            client.Config.SubIndexId = mockAccount.Id; await client.Reload();
            Check(ServerPresentation.CountryCode(fixtures[0]) == "DE" && ServerPresentation.CountryCode(fixtures[1]) == "SE" && ServerPresentation.CountryCode(fixtures[5]) is null, "Country follows configuration flags without invented geolocation");
            Check(ServerPresentation.DisplayName(fixtures[0]).Trim() == "CDN" && ServerPresentation.Transport(fixtures[0]).Equals("XHTTP", StringComparison.OrdinalIgnoreCase), "Server labels use decoded profile metadata");
            Check(ServerPresentation.FlagPng("DE") is { Length: > 100 } && ServerPresentation.FlagPng("SE") is { Length: > 100 }, "Country flag bitmap assets are available");
            var infoRow = new ProfileItem { ConfigType = EConfigType.SOCKS, Address = "127.0.0.1", Port = 1080, Remarks = "10 GB | 29 days" };
            Check(ServerPresentation.IsAccountInfo(infoRow) && !ServerPresentation.IsAccountInfo(new ProfileItem { ConfigType = EConfigType.SOCKS, Address = "127.0.0.1", Port = 1080, Remarks = "Local proxy" }), "Quota information rows are distinguished from genuine local SOCKS servers");
            client.Config.UiItem.CurrentLanguage = "en";
            foreach (var (width, height) in new[] { (1000, 700), (1920, 1080) })
                foreach (var mode in new[] { "Dark", "Light" })
                    foreach (var color in new[] { "Forest", "Maroon", "Purple" })
                        foreach (var tab in new[] { 0, 1, 2 })
                        {
                            await window.RenderForSmoke(tab, mode, color); await SaveRender(window, width, height, $"smoke-{width}-tab-{tab}-{color}-{mode}.png");
                        }
            Check(true, "Actual Avalonia home servers settings renders at 1000x700 and 1920x1080 in all six themes");
            client.Config.UiItem.CurrentLanguage = "fa";
            foreach (var tab in new[] { 0, 1, 2 }) { await window.RenderForSmoke(tab, "Dark", "Forest"); await SaveRender(window, 1000, 700, $"smoke-fa-tab-{tab}.png"); }
            Check(true, "Actual Avalonia Persian home servers settings renders");
            window.RenderThemeGalleryForSmoke();
            await SaveRender(window, 1000, 700, "smoke-theme-gallery.png");
            Check(window.GetVisualDescendants().OfType<Avalonia.Controls.Shapes.Ellipse>().Count() >= 63,
                "Theme gallery renders twenty-one day/night choices with palette previews");
            var themeLabels = window.GetVisualDescendants().OfType<TextBlock>().Select(t => t.Text).ToList();
            Check(themeLabels.Contains("تم‌های روشن") && themeLabels.Contains("تم‌های تیره"), "Linux theme gallery separates light and dark categories");
            await window.RenderForSmoke(0, "Dark", "Ocean");
            var backupFile = Utils.GetTempPath("DrKozol-smoke-backup.json");
            try {
                await client.Backup(backupFile, false);
                Check(File.Exists(backupFile), "Local subscription backup is written");
                await client.DeleteProvider(mockAccount.Id);
                Check(client.Accounts.All(a => a.Id != mockAccount.Id), "Provider removal deletes the selected provider");
                await client.Backup(backupFile, true);
                Check(client.Accounts.Any(a => a.Id == mockAccount.Id)
                    && fixtures.All(p => client.AccountProfiles.Values.SelectMany(rows => rows).Any(row => row.IndexId == p.IndexId)),
                    "Backup restores provider and stable profile identities");
            } finally { if (File.Exists(backupFile)) File.Delete(backupFile); }
            var privateUrl = Environment.GetEnvironmentVariable("DRKOZOL_TEST_URL");
            if (!string.IsNullOrWhiteSpace(privateUrl))
            {
                await client.Import(privateUrl);
                Check(client.Account?.Url == privateUrl.Trim() && client.Profiles.Count > 0 && client.Profiles.All(p => p.Subid == client.Account.Id), "Live Linux subscription import");
                Check(!string.IsNullOrWhiteSpace(client.Account!.Announcement), "Live panel announcement received");
                var candidates = client.Profiles.Where(p => p.ConfigType == EConfigType.VLESS && p.Network == "xhttp").ToList();
                Check(candidates.Count > 0, "Live subscription contains imported XHTTP candidates");
                await client.Probe(false, candidates); Check(candidates.Any(p => client.Tcp.GetValueOrDefault(p.IndexId) > 0), "Live TCP ping through Linux native client");
                await client.Probe(true, candidates); Check(candidates.Any(p => client.Real.GetValueOrDefault(p.IndexId) > 0), "Live Linux XHTTP TLS Real Delay");
                var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
                using var http = new HttpClient(new HttpClientHandler { Proxy = new WebProxy($"socks5://127.0.0.1:{port}"), UseProxy = true }) { Timeout = TimeSpan.FromSeconds(25) };
                stage = "proxy connection verification";
                await client.SetTun(false);
                var verified = false;
                foreach (var candidate in candidates.Where(p => client.Real.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => client.Real[p.IndexId]).Take(3))
                {
                    await client.SelectServer(candidate.IndexId); await client.Connect(false);
                    if (client.Connected)
                    {
                        try { using var response = await http.GetAsync("https://www.gstatic.com/generate_204"); verified = response.IsSuccessStatusCode; }
                        catch (HttpRequestException) { }
                        catch (TaskCanceledException) { }
                    }
                    if (verified) break;
                    checks.Add("A candidate did not complete the subsequent HTTPS check; testing the next reachable server");
                    if (client.Connected) await client.Connect(false);
                }
                Check(verified && client.Connected, "Linux real HTTPS request through a verified XHTTP TLS connection");
                var selected = client.Config.IndexId;
                stage = "connected probes";
                await client.ProbeCurrent(true); Check(client.Connected && client.Config.IndexId == selected && client.Real.GetValueOrDefault(selected) > 0, "Current-connection Real Delay keeps Linux native tunnel running");
                await client.ProbeCurrent(false); Check(client.Connected && client.Config.IndexId == selected, "Current-connection TCP ping preserves server identity");
                if (Environment.GetEnvironmentVariable("DRKOZOL_TEST_GNOME") == "1")
                {
                    stage = "GNOME system proxy apply and restore";
                    var keys = new List<(string Schema, string Key)> { ("org.gnome.system.proxy", "mode"), ("org.gnome.system.proxy", "ignore-hosts") };
                    foreach (var protocol in new[] { "http", "https", "ftp", "socks" })
                    {
                        keys.Add(("org.gnome.system.proxy." + protocol, "host")); keys.Add(("org.gnome.system.proxy." + protocol, "port"));
                    }
                    var before = new List<string>(); foreach (var key in keys) before.Add(await GSettings(key.Schema, key.Key));
                    await client.Connect(false); await client.Connect();
                    Check(client.Connected && await GSettings("org.gnome.system.proxy", "mode") == "'manual'"
                        && await GSettings("org.gnome.system.proxy.http", "host") == "'127.0.0.1'"
                        && await GSettings("org.gnome.system.proxy.http", "port") == port.ToString(), "GNOME System Proxy applies to the actual desktop settings");
                    await client.Connect();
                    var after = new List<string>(); foreach (var key in keys) after.Add(await GSettings(key.Schema, key.Key));
                    Check(!client.Connected && before.SequenceEqual(after), "GNOME disconnect restores every changed proxy setting");
                    await client.Connect(false); Check(client.Connected, "Connection resumes after GNOME proxy restoration");
                }
                stage = "same-server best selection";
                var activeId = client.Config.IndexId;
                foreach (var key in client.Real.Keys) if (client.Real[key] > 0) client.Real[key] = Math.Max(2, client.Real[key]);
                client.Real[activeId] = 1;
                using (var unchangedHttp = new HttpClient(new HttpClientHandler { UseProxy = true,
                    Proxy = new WebProxy($"socks5://127.0.0.1:{port}") }) { Timeout = TimeSpan.FromSeconds(25) })
                {
                    using (var before = await unchangedHttp.GetAsync("https://www.gstatic.com/generate_204"))
                        Check(before.IsSuccessStatusCode, "Active server accepts HTTPS before repeated best selection");
                    var unchangedPids = Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().ToArray();
                    await client.ConnectBest();
                    Check(client.Connected && client.Config.IndexId == activeId && client.HomeChoice == "best"
                        && Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().SequenceEqual(unchangedPids),
                        "Selecting the current best server preserves its running engine");
                    using var after = await unchangedHttp.GetAsync("https://www.gstatic.com/generate_204");
                    Check(after.IsSuccessStatusCode, "Existing HTTP connection continues after selecting the current best server");
                }
                stage = "session lock and unlock recovery";
                client.Config.DrKozol.DisconnectOnLock = true; client.Config.DrKozol.ReconnectOnUnlock = true;
                await client.SessionLockChanged(true); Check(!client.Connected, "Session lock stops a configured connection");
                await client.SessionLockChanged(false); Check(client.Connected, "Session unlock restores the previous connection");
                checks.Add($"Balancer preflight: {client.Real.Count(p => p.Value > 0)} successful real-delay results; {client.Profiles.Count(p => !ServerPresentation.IsAccountInfo(p) && !p.ConfigType.IsComplexType() && p.AmneziaConfig is null && !client.Real.ContainsKey(p.IndexId))} untested eligible profiles");
                // Keep native diagnostics aggregate-only: core messages may contain server secrets.
                var nativeSignals = new System.Collections.Concurrent.ConcurrentDictionary<string, int>();
                client.Config.CoreBasicItem.LogEnabled = false;
                client.Config.CoreBasicItem.Loglevel = "info";
                await CoreManager.Instance.Init(client.Config, (_, message) =>
                {
                    foreach (var signal in new[] { "is alive:", "is dead:", "connection refused", "network is unreachable", "no such host",
                        "i/o timeout", "connection reset", "EOF", "failed to lookup", "failed to find", "fallback to", "no outbound matches" })
                        if (message.Contains(signal, StringComparison.OrdinalIgnoreCase)) nativeSignals.AddOrUpdate(signal, 1, (_, count) => count + 1);
                    return Task.CompletedTask;
                });
                stage = "round-robin balancer";
                await client.ConnectBalancer(false);
                Check(client.Connected && client.HomeChoice == "balancer" && client.SelectedProfile?.ConfigType == EConfigType.PolicyGroup,
                    "Balancer starts a real multi-server policy group");
                var memberIds = (client.SelectedProfile!.GetProtocolExtra().ChildItems ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries);
                checks.Add($"Balancer membership: {memberIds.Length} total; {memberIds.Count(id => client.Real.GetValueOrDefault(id) > 0)} tested healthy");
                // A replaced native core owns new TCP connections. Do not reuse the old
                // single-server HTTP pool after deliberately disconnecting its SOCKS stream.
                using var balancedHttp = new HttpClient(new HttpClientHandler { UseProxy = true,
                    Proxy = new WebProxy($"socks5://127.0.0.1:{port}") }) { Timeout = TimeSpan.FromSeconds(25) };
                try
                {
                    for (var request = 0; request < 3; request++)
                    {
                        using var balanced = await balancedHttp.GetAsync("https://www.gstatic.com/generate_204");
                        Check(balanced.IsSuccessStatusCode, $"Balancer HTTPS request {request + 1}");
                    }
                }
                catch (HttpRequestException failure)
                {
                    checks.Add("Balancer request error category: " + failure.HttpRequestError);
                    // This second request diagnoses startup timing; it never turns a failed
                    // first request into a passing integration check.
                    await Task.Delay(TimeSpan.FromSeconds(6));
                    try
                    {
                        using var warmed = await balancedHttp.GetAsync("https://www.gstatic.com/generate_204");
                        checks.Add("Balancer diagnostic after health probes: HTTP " + (int)warmed.StatusCode);
                    }
                    catch (Exception error) { checks.Add("Balancer diagnostic after health probes: " + error.GetType().Name); }
                    checks.Add("Native signal counts: " + JsonSerializer.Serialize(nativeSignals));
                    throw;
                }
                var balancerPids = Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().ToArray();
                await client.ConnectBalancer(false);
                Check(client.Connected && Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().SequenceEqual(balancerPids),
                    "Selecting the same balancer preserves its running engine");
                using (var continued = await balancedHttp.GetAsync("https://www.gstatic.com/generate_204"))
                    Check(continued.IsSuccessStatusCode, "Existing HTTP connection continues after selecting the same balancer");
                if (Environment.GetEnvironmentVariable("DRKOZOL_TEST_TUN") == "1")
                {
                    stage = "TUN adapter startup";
                    await client.Connect(false); await client.SetTun(true); await client.Connect(false); Check(client.Connected && client.UseTun, "Linux TUN starts with configured network capability");
                    stage = "TUN DNS and HTTPS";
                    using var direct = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(25) };
                    using var response = await direct.GetAsync("https://www.gstatic.com/generate_204"); Check(response.IsSuccessStatusCode, "Linux HTTPS request through TUN with system proxy disabled");
                }
                await client.Connect(false); Check(!client.Connected, "Linux disconnect stops native engines");
            }
            success = true;
        }
        catch (Exception error) { checks.Add("FAILED: " + error.GetType().Name + (error.InnerException is { } inner ? " / " + inner.GetType().Name : "") + " Stage=" + stage + (error is InvalidOperationException ? " " + error.Message : "")); }
        finally
        {
            if (!success && client.LastConnectionFailure is { } reason) checks.Add("Native startup: " + reason);
            Console.WriteLine(JsonSerializer.Serialize(new { success, checks }));
            await client.Close();
            await File.WriteAllTextAsync(OutputPath("smoke-result.json"), JsonSerializer.Serialize(new { success, checks }, new JsonSerializerOptions { WriteIndented = true }));
            (Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.Shutdown(success ? 0 : 1);
        }
    }
    private static async Task<string> GSettings(string schema, string key)
    {
        var start = new ProcessStartInfo("gsettings") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        start.ArgumentList.Add("get"); start.ArgumentList.Add(schema); start.ArgumentList.Add(key);
        using var process = Process.Start(start) ?? throw new IOException();
        var output = process.StandardOutput.ReadToEndAsync(); var error = process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(8)); await error;
        if (process.ExitCode != 0) throw new IOException("GNOME settings query failed");
        return (await output).Trim();
    }
    private static async Task SaveRender(Window window, int width, int height, string name)
    {
        window.Width = width; window.Height = height;
        // X11 resize notifications arrive on a later native event turn. Capturing before
        // ClientSize settles produced clipped small images and blank large images.
        for (var turn = 0; turn < 100 && (Math.Abs(window.ClientSize.Width - width) > 1 || Math.Abs(window.ClientSize.Height - height) > 1); turn++) await Task.Delay(20);
        if (Math.Abs(window.ClientSize.Width - width) > 1 || Math.Abs(window.ClientSize.Height - height) > 1) throw new InvalidOperationException("Native Linux window resize did not settle");
        await Dispatcher.UIThread.InvokeAsync(() => { }, DispatcherPriority.Loaded);
        window.Measure(new Size(width, height)); window.Arrange(new Rect(0, 0, width, height)); window.UpdateLayout();
        using var bitmap = new RenderTargetBitmap(new PixelSize(width, height), new Vector(96, 96)); bitmap.Render(window);
        await Task.Run(() => { using var file = File.Create(OutputPath(name)); bitmap.Save(file, PngBitmapEncoderOptions.Default); });
    }
}
