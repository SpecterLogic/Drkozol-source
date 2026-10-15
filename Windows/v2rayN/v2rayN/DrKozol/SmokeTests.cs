using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows;
using System.Windows.Controls;
using ServiceLib.DrKozol;
using ServiceLib.Helper;

namespace v2rayN.DrKozol;

public static class SmokeTests
{
    public static async Task RunAndExit()
    {
        var checks = new List<string>(); var client = new DrKozolController();
        var success = false;
        var stage = "validation";
        try
        {
            void Check(bool condition, string label) { if (!condition) throw new InvalidOperationException(label); checks.Add(label); }
            Check(AccountUsage.ValidUrl("https://panel.example:2096/sub/test") && !AccountUsage.ValidUrl("http://panel.example") && !AccountUsage.ValidUrl("") && !AccountUsage.ValidUrl("https://user:pass@panel.example"), "HTTPS subscription validation");
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            var account = new SubItem(); using var message = new HttpResponseMessage();
            message.Headers.TryAddWithoutValidation("subscription-userinfo", $"upload=10; download=90; total=100; expire={now + 86401}");
            message.Headers.Date = DateTimeOffset.UtcNow;
            AccountUsage.Capture(account, message.Headers);
            Check(AccountUsage.Remaining(account) == 0 && AccountUsage.Locked(account), "Exhausted quota locks");
            account.TotalBytes = 1000;
            Check(!AccountUsage.Locked(account) && AccountUsage.Remaining(account) == 900 && AccountUsage.Days(account) is 1 or 2, "Renewed quota and days");
            account.ExpiresAt = now - 1;
            Check(AccountUsage.Locked(account), "Expired account locks");
            using var noInfo = new HttpResponseMessage(); AccountUsage.Capture(account, noInfo.Headers);
            Check(AccountUsage.Locked(account), "Missing headers cannot erase expiry");
            var providerMessage = "پیام آزمایشی پنل";
            using var metadata = new HttpResponseMessage();
            metadata.Headers.TryAddWithoutValidation("Announce", "base64:" + Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(providerMessage)));
            metadata.Headers.TryAddWithoutValidation("Profile-Title", "base64:" + Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("Provider")));
            metadata.Headers.TryAddWithoutValidation("Profile-Update-Interval", "12");
            AccountUsage.Capture(account, metadata.Headers);
            Check(account.Announcement == providerMessage && account.Remarks == "Provider" && account.AutoUpdateInterval == 720, "Panel announcement title and interval decode");
            AccountUsage.Capture(account, noInfo.Headers);
            Check(account.Announcement == providerMessage && AccountUsage.ProviderText("base64:invalid*") is null && AccountUsage.ProviderText("base64:/w==") is null
                && AccountUsage.ProviderText("base64:") == "" && new SubItem().Announcement is null, "Missing malformed cleared and legacy provider metadata");
            var qr = QRCodeUtils.GenQRCode("https://panel.example/sub/test");
            Check(QRCodeUtils.ParseBarcode(qr) == "https://panel.example/sub/test" && QRCodeUtils.ParseBarcode(new byte[] { 1, 2, 3 }) is null
                && QRCodeUtils.ParseBarcode(new byte[10 * 1024 * 1024 + 1]) is null, "QR image decode rejects malformed and oversized data");
            var window = new DrKozolWindow { Width = 1000, Height = 740, Left = -20000, Top = -20000, WindowStartupLocation = WindowStartupLocation.Manual, ShowActivated = false, ShowInTaskbar = false };
            client = window.TestClient;
            client.Config.UiItem.CurrentLanguage = "fa";
            await window.InitializeForSmoke();
            // Keep integration tests away from a user's existing local proxy listener.
            client.Config.Inbound.First(i => i.Protocol == nameof(EInboundProtocol.socks)).LocalPort = Utils.GetFreePort(11808);
            window.Show();
            await Application.Current.Dispatcher.InvokeAsync(() => { }, System.Windows.Threading.DispatcherPriority.ContextIdle);
            window.Measure(new Size(1000, 740)); window.Arrange(new Rect(0, 0, 1000, 740)); window.UpdateLayout();
            var bitmap = new RenderTargetBitmap(1000, 740, 96, 96, PixelFormats.Pbgra32); bitmap.Render(window);
            var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(bitmap));
            using (var file = File.Create(Utils.GetBaseDirectory("smoke-onboarding.png"))) png.Save(file);
            var pixels = new byte[1000 * 740 * 4]; bitmap.CopyPixels(pixels, 1000 * 4, 0);
            Check(pixels.Distinct().Count() > 20, "WPF Persian onboarding render");
            Check(window.WindowStyle == WindowStyle.None && VisualChildren(window).OfType<Border>().Any(b => b.Height == 36 && b.Background is SolidColorBrush brush && brush.Color == Colors.Black), "Custom black desktop titlebar has 36 pixel height");
            Check(!client.Connected, "Startup does not connect automatically");
            using (var tunConfig = JsonDocument.Parse(TunHost.Configuration(10808)))
            {
                Check(tunConfig.RootElement.GetProperty("inbounds")[0].GetProperty("auto_route").GetBoolean()
                    && tunConfig.RootElement.GetProperty("route").GetProperty("auto_detect_interface").GetBoolean()
                    && tunConfig.RootElement.GetProperty("outbounds")[0].GetProperty("server_port").GetInt32() == 10808,
                    "TUN routes to local protocol engine and avoids routing loops");
            }
            var invalidPort = false; try { TunHost.Configuration(0); } catch (ArgumentOutOfRangeException) { invalidPort = true; }
            Check(invalidPort, "TUN rejects invalid listener port");
            var tunPath = Utils.GetConfigPath("smoke-tun.json");
            try
            {
                await File.WriteAllTextAsync(tunPath, TunHost.Configuration(10808));
                var start = new ProcessStartInfo(Path.Combine(Utils.GetBaseDirectory(), "bin", "sing_box", "sing-box.exe"))
                    { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
                start.ArgumentList.Add("check"); start.ArgumentList.Add("-c"); start.ArgumentList.Add(tunPath);
                using var native = Process.Start(start)!;
                var output = native.StandardOutput.ReadToEndAsync(); var error = native.StandardError.ReadToEndAsync();
                await native.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15)); await Task.WhenAll(output, error);
                Check(native.ExitCode == 0, "Pinned native TUN engine accepts generated configuration");
            }
            finally { File.Delete(tunPath); }
            await client.SetTun(true); Check(client.UseTun, "TUN mode selection persists in existing config");
            await client.SetTun(false); Check(!client.UseTun, "System proxy mode remains selectable");
            await client.ImportQrImage("");
            Check(!client.Busy && !client.Connected, "Invalid QR file releases loading state without connection");
            var previewAccount = new SubItem { Id = Guid.NewGuid().ToString("N"), Remarks = "DrKozol Preview", Url = "https://panel.example/sub/preview",
                Enabled = true, UploadBytes = 0, DownloadBytes = 1024, TotalBytes = 1024 * 1024, ExpiresAt = now + 864000 };
            await SQLiteHelper.Instance.InsertAsync(previewAccount);
            var longTitle = "سرور Germany 01 / very long subscription configuration title 🇩🇪";
            var previewProfiles = new[] {
                new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = previewAccount.Id, Remarks = longTitle, ConfigType = EConfigType.VLESS,
                    Address = "server.example", Port = 443, Network = "tcp", StreamSecurity = "reality" },
                new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = previewAccount.Id, Remarks = "📊10GB|⌛29روز", ConfigType = EConfigType.SOCKS,
                    Address = "127.0.0.1", Port = 1080 } };
            await SQLiteHelper.Instance.InsertAllAsync(previewProfiles);
            client.Config.SubIndexId = previewAccount.Id; await client.Reload();
            foreach (var language in new[] { "fa", "en" })
            {
                client.Config.UiItem.CurrentLanguage = language;
                foreach (var dark in new[] { true, false })
                    foreach (var color in new[] { "Forest", "Maroon", "Purple" })
                    {
                        window.RenderThemeForSmoke(color, dark);
                        foreach (var tab in new[] { 0, 1, 2 })
                        {
                            window.Width = 760; window.Height = 620;
                            window.RenderForSmoke(tab, dark); window.UpdateLayout();
                            var render = new RenderTargetBitmap(760, 620, 96, 96, PixelFormats.Pbgra32); render.Render(window);
                            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(render));
                            using var image = File.Create(Utils.GetBaseDirectory($"smoke-{language}-{color}-{dark}-tab-{tab}.png")); encoder.Save(image);
                            if (tab == 1)
                            {
                                var serverButton = VisualChildren(window).OfType<Button>().First(b => System.Windows.Automation.AutomationProperties.GetName(b).StartsWith(longTitle));
                                var serverGrid = (Grid)serverButton.Content;
                                var title = (FrameworkElement)serverGrid.Children[1]; var latency = (FrameworkElement)serverGrid.Children[2];
                                var titleRight = title.TranslatePoint(new Point(title.ActualWidth, 0), serverGrid).X;
                                var latencyLeft = latency.TranslatePoint(new Point(0, 0), serverGrid).X;
                                Check(title.ActualWidth > 0 && latency.ActualWidth > 0 && titleRight <= latencyLeft + 1,
                                    $"Long mixed-script server title and latency do not overlap at minimum width in {language} {color} {dark}");
                            }
                            if (tab == 2)
                            {
                                var labels = VisualChildren(window).OfType<TextBlock>().Select(t => t.Text).ToArray();
                                Check(labels.Any(t => t.Contains("DrKozol")) && !labels.Any(t => t.Contains("v2rayNG", StringComparison.OrdinalIgnoreCase) || t.Contains("v2rayN", StringComparison.OrdinalIgnoreCase)),
                                    $"About information identifies DrKozol in {language} {color} {dark}");
                            }
                        }
                    }
            }
            Check(!client.Connected, "UI preview fixtures never establish a connection");
            var responseFile = Environment.GetEnvironmentVariable("DRKOZOL_TEST_RESPONSE_FILE");
            if (!string.IsNullOrEmpty(responseFile))
            {
                stage = "offline import of real server configurations";
                var responseContent = await File.ReadAllTextAsync(responseFile);
                var decoded = Utils.Base64Decode(responseContent); if (string.IsNullOrWhiteSpace(decoded)) decoded = responseContent;
                foreach (var line in decoded.Split('\n').Where(line => !string.IsNullOrWhiteSpace(line))) await client.ImportConfigs(line.Trim());
                var plainTcp = client.Profiles.Where(p => p.ConfigType == EConfigType.VLESS && ServerPresentation.Transport(p) == "TCP"
                    && (p.StreamSecurity.IsNullOrEmpty() || p.StreamSecurity == "none")).ToList();
                var awgProfiles = client.Profiles.Where(p => p.AmneziaConfig is not null).ToList();
                Check(plainTcp.Count == 2 && awgProfiles.Count == 2, "Real response imports both plain VLESS TCP and both AmneziaWG profiles");
                stage = "VLESS TCP real delay";
                await client.Probe(false, plainTcp); await client.Probe(true, plainTcp);
                foreach (var candidate in plainTcp.Select((profile, index) => (profile, index)))
                    checks.Add($"VLESS TCP case {candidate.index + 1}: TCP={client.Tcp.GetValueOrDefault(candidate.profile.IndexId)} Real={client.Real.GetValueOrDefault(candidate.profile.IndexId)}");
                var reachable = plainTcp.Where(p => client.Real.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => client.Real[p.IndexId]).FirstOrDefault();
                Check(reachable is not null, "Plain VLESS TCP Real Delay succeeds through generated native configuration");
                await client.SelectServer(reachable!.IndexId); await client.Connect(false); Check(client.Connected, "Plain VLESS TCP client connection starts");
                var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
                using var http = new HttpClient(new HttpClientHandler { UseProxy = true, Proxy = new WebProxy($"socks5://127.0.0.1:{port}") }) { Timeout = TimeSpan.FromSeconds(25) };
                stage = "VLESS TCP HTTPS";
                using (var response = await http.GetAsync("https://www.gstatic.com/generate_204")) Check(response.IsSuccessStatusCode, "HTTPS through plain VLESS TCP client configuration");
                var beforePids = Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().ToArray();
                await client.ProbeCurrent(true);
                Check(client.Connected && client.Real.GetValueOrDefault(reachable.IndexId) > 0
                    && Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().SequenceEqual(beforePids), "Plain VLESS current Real Delay preserves the existing engine process");
                await client.Connect(false);
                if (Environment.GetEnvironmentVariable("DRKOZOL_TEST_TUN") == "1")
                {
                    Check(TunHost.IsAdministrator, "Windows TUN validation requires Administrator");
                    stage = "Windows TUN adapter startup";
                    await client.SetTun(true); await client.Connect(false); Check(client.Connected && client.UseTun, "Windows TUN starts with verified VLESS TCP connection");
                    stage = "Windows TUN DNS resolution";
                    using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(20));
                    Check((await Dns.GetHostAddressesAsync("www.gstatic.com", cancellation.Token)).Length > 0, "Windows DNS resolves through TUN");
                    using var direct = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(25) };
                    stage = "Windows TUN direct HTTPS";
                    for (var attempt = 0; attempt < 3; attempt++)
                    {
                        using var response = await direct.GetAsync("https://www.gstatic.com/generate_204");
                        Check(response.IsSuccessStatusCode, $"HTTPS request {attempt + 1} through Windows TUN with system proxy disabled");
                    }
                    await client.Connect(false); await client.SetTun(false);
                }
                if (Environment.GetEnvironmentVariable("DRKOZOL_SKIP_AWG_TEST") != "1")
                {
                    stage = "AmneziaWG real delay";
                    await client.Probe(true, awgProfiles);
                    foreach (var candidate in awgProfiles.Select((profile, index) => (profile, index))) checks.Add($"AmneziaWG case {candidate.index + 1}: Real={client.Real.GetValueOrDefault(candidate.profile.IndexId)}");
                    Check(awgProfiles.Any(p => client.Real.GetValueOrDefault(p.IndexId) > 0), "Real AmneziaWG HTTPS delay through desktop client");
                }
            }
            var backupFile = Utils.GetTempPath("DrKozol-smoke-backup.json");
            try {
                await client.Backup(backupFile, false);
                Check(File.Exists(backupFile), "Local subscription backup is written");
                await client.DeleteProvider(previewAccount.Id);
                Check(client.Accounts.All(a => a.Id != previewAccount.Id), "Provider removal deletes the selected provider");
                await client.Backup(backupFile, true);
                Check(client.Accounts.Any(a => a.Id == previewAccount.Id)
                    && previewProfiles.All(p => client.AccountProfiles.Values.SelectMany(rows => rows).Any(row => row.IndexId == p.IndexId)),
                    "Backup restores provider and stable profile identities");
            } finally { if (File.Exists(backupFile)) File.Delete(backupFile); }
            var privateUrl = Environment.GetEnvironmentVariable("DRKOZOL_TEST_URL");
            if (!string.IsNullOrEmpty(privateUrl))
            {
                await client.Import(privateUrl);
                Check(client.Profiles.Count > 0 && client.Account?.Url == privateUrl.Trim()
                    && client.Profiles.All(p => p.Subid == client.Account.Id && previewProfiles.All(fixture => fixture.IndexId != p.IndexId)), "Real subscription import replaces preview identities");
                Check(AccountUsage.Remaining(client.Account!) is > 0 && AccountUsage.Days(client.Account!) is > 0, "Real subscription metadata");
                Check(!string.IsNullOrWhiteSpace(client.Account!.Announcement), "Real panel announcement received");
                window.Width = 1060; window.Height = 760;
                foreach (var dark in new[] { true, false })
                    foreach (var tab in new[] { 0, 1, 2 })
                    {
                        window.RenderForSmoke(tab, dark);
                        window.Measure(new Size(1060, 760)); window.Arrange(new Rect(0, 0, 1060, 760)); window.UpdateLayout();
                        var preview = new RenderTargetBitmap(1060, 760, 96, 96, PixelFormats.Pbgra32); preview.Render(window);
                        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(preview));
                        using var image = File.Create(Utils.GetBaseDirectory($"smoke-tab-{tab}-{(dark ? "dark" : "light")}.png")); encoder.Save(image);
                    }
                Check(true, "WPF home servers settings Persian dark and light renders");
                if (client.Profiles.FirstOrDefault(ServerPresentation.IsAccountInfo) is { } infoProfile)
                {
                    window.RenderForSmoke(1, true); window.UpdateLayout();
                    Check(VisualChildren(window).OfType<Button>().Any(b => System.Windows.Automation.AutomationProperties.GetName(b) == infoProfile.Remarks && !b.IsEnabled), "Account metadata row remains visible and cannot be selected as a VPN server");
                }
                var previousColor = client.Config.UiItem.ColorPrimaryName;
                foreach (var color in new[] { "Forest", "Maroon", "Purple" })
                    foreach (var dark in new[] { true, false })
                    {
                        window.RenderThemeForSmoke(color, dark);
                        window.Measure(new Size(1060, 760)); window.Arrange(new Rect(0, 0, 1060, 760)); window.UpdateLayout();
                        var themeBitmap = new RenderTargetBitmap(1060, 760, 96, 96, PixelFormats.Pbgra32); themeBitmap.Render(window);
                        var themePng = new PngBitmapEncoder(); themePng.Frames.Add(BitmapFrame.Create(themeBitmap));
                        using var themeFile = File.Create(Utils.GetBaseDirectory($"smoke-theme-{color.ToLowerInvariant()}-{(dark ? "dark" : "light")}.png")); themePng.Save(themeFile);
                        Check(VisualChildren(window).OfType<System.Windows.Shapes.Ellipse>().Count() >= 45, $"WPF {color} {(dark ? "dark" : "light")} gallery has fifteen palette previews");
                        Check(!VisualChildren(window).OfType<TextBlock>().Any(t => t.Text.Contains("ThemeChoice", StringComparison.Ordinal)), $"WPF {color} {(dark ? "dark" : "light")} selected theme uses readable label");

                    }
                client.Config.UiItem.ColorPrimaryName = previousColor;
                var flagProfile = new ProfileItem { Remarks = "Sweden 🇸🇪" };
                Check(ServerPresentation.CountryCode(flagProfile) == "SE" && DrKozolVisuals.Flag(flagProfile, Brushes.Gray) is Image { Source: not null }
                    && DrKozolVisuals.Flag(new ProfileItem { Remarks = "CDN" }, Brushes.Gray) is System.Windows.Shapes.Path, "Configuration country flag renders and unknown country uses globe");
                var candidates = client.Profiles.Where(p => p.ConfigType == EConfigType.VLESS && p.Network == "xhttp").ToList();
                var beforeSelection = client.Config.IndexId;
                await client.SelectServer(""); await client.SelectServer("missing-guid");
                Check(client.Config.IndexId == beforeSelection, "Invalid and empty selection cannot change server identity");
                await client.SelectServer(candidates.First().IndexId);
                Check(client.Config.IndexId == candidates.First().IndexId, "Server row selects stable profile identity");
                await client.Probe(false, candidates); Check(candidates.Any(p => client.Tcp.GetValueOrDefault(p.IndexId) > 0), "Real server TCP ping");
                await client.Probe(true, candidates); Check(candidates.Any(p => client.Real.GetValueOrDefault(p.IndexId) > 0), "Real XHTTP/TLS delay");
                await client.Best(candidates);
                var profile = candidates.First(p => p.IndexId == client.Config.IndexId);
                Check(client.Real.GetValueOrDefault(profile.IndexId) == candidates.Select(p => client.Real.GetValueOrDefault(p.IndexId)).Where(delay => delay > 0).Min(), "Best server chooses lowest real delay");
                await client.Connect(false); Check(client.Connected, "Native Xray starts without modifying system proxy");
                var connectedGuid = client.Config.IndexId;
                var runningPids = Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().ToArray();
                await client.ProbeCurrent(true);
                Check(client.Connected && client.Config.IndexId == connectedGuid && client.Real.GetValueOrDefault(connectedGuid) > 0
                    && Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().SequenceEqual(runningPids), "Connected real delay preserves live engine process and selected GUID");
                await client.ProbeCurrent(false);
                Check(client.Connected && client.Config.IndexId == connectedGuid && client.Tcp.GetValueOrDefault(connectedGuid) > 0, "Connected TCP ping preserves active connection");
                await client.SelectServer("missing-guid");
                Check(client.Config.IndexId == profile.IndexId, "Connected server cannot change outside connection lifecycle");
                var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
                using var http = new HttpClient(new HttpClientHandler { Proxy = new WebProxy($"socks5://127.0.0.1:{port}"), UseProxy = true }) { Timeout = TimeSpan.FromSeconds(20) };
                for (var request = 0; request < 3; request++)
                {
                    using var response = await http.GetAsync("https://www.gstatic.com/generate_204");
                    Check(response.IsSuccessStatusCode, $"Real HTTPS request {request + 1} through XHTTP/TLS without reconnect");
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
                using var recoveredHttp = new HttpClient(new HttpClientHandler { UseProxy = true,
                    Proxy = new WebProxy($"socks5://127.0.0.1:{port}") }) { Timeout = TimeSpan.FromSeconds(25) };
                using (var recovered = await recoveredHttp.GetAsync("https://www.gstatic.com/generate_204"))
                    Check(recovered.IsSuccessStatusCode, "HTTPS resumes after session unlock");
                checks.Add($"Balancer preflight: {client.Real.Count(p => p.Value > 0)} successful real-delay results; {client.Profiles.Count(p => !ServerPresentation.IsAccountInfo(p) && !p.ConfigType.IsComplexType() && p.AmneziaConfig is null && !client.Real.ContainsKey(p.IndexId))} untested eligible profiles");
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
                for (var request = 0; request < 3; request++)
                {
                    using var balanced = await balancedHttp.GetAsync("https://www.gstatic.com/generate_204");
                    Check(balanced.IsSuccessStatusCode, $"Balancer HTTPS request {request + 1}");
                }
                var balancerPids = Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().ToArray();
                await client.ConnectBalancer(false);
                Check(client.Connected && Process.GetProcessesByName("xray").Select(p => { using (p) return p.Id; }).Order().SequenceEqual(balancerPids),
                    "Selecting the same balancer preserves its running engine");
                using (var continued = await balancedHttp.GetAsync("https://www.gstatic.com/generate_204"))
                    Check(continued.IsSuccessStatusCode, "Existing HTTP connection continues after selecting the same balancer");
                await client.Connect(false, profile.IndexId);
                if (Environment.GetEnvironmentVariable("DRKOZOL_TEST_TUN") == "1")
                {
                    Check(TunHost.IsAdministrator, "TUN integration requires elevated test process");
                    await client.Connect(false); await client.SetTun(true); await client.Connect(false);
                    Check(client.Connected && client.UseTun, "Native TUN adapter starts with XHTTP TLS engine");
                    await client.SetTun(false); Check(client.UseTun, "Connection mode cannot change during an active tunnel");
                    stage = "TUN DNS resolution";
                    using var dnsCancellation = new CancellationTokenSource(TimeSpan.FromSeconds(20));
                    var addresses = await Dns.GetHostAddressesAsync("www.gstatic.com", dnsCancellation.Token);
                    Check(addresses.Length > 0, "Host DNS resolves while native TUN is active");
                    using var directHttp = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(25) };
                    for (var request = 0; request < 3; request++)
                    {
                        stage = $"TUN HTTPS request {request + 1}";
                        using var response = await directHttp.GetAsync("https://www.gstatic.com/generate_204");
                        Check(response.IsSuccessStatusCode, $"HTTPS request {request + 1} through TUN with system proxy disabled");
                    }
                    stage = "expiry cleanup";
                }
                client.Account!.ExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 1;
                await Task.Delay(2200); Check(!client.Connected, "Active connection stops on account expiry");
                await client.Close(); await client.SelectServer(candidates.First().IndexId);
                Check(client.Config.IndexId == profile.IndexId, "Closed controller rejects pending server selection");
            }
            success = true;
        }
        catch (Exception error)
        {
            var details = "";
            for (Exception? cause = error; cause is not null; cause = cause.InnerException)
                if (cause is SocketException socket) details += $" SocketError={socket.SocketErrorCode} NativeError={socket.NativeErrorCode}";
                else if (cause is HttpRequestException request) details += $" HttpRequestError={request.HttpRequestError}";
            checks.Add("FAILED: " + error.GetType().Name + (error.InnerException is { } inner ? " / " + inner.GetType().Name : "")
                + $" Stage={stage}" + details + (error is InvalidOperationException ? " " + error.Message : ""));
        }
        finally
        {
            if (!success && client.LastConnectionFailure is { } reason) checks.Add("Native startup: " + reason);
            await client.Close();
            await File.WriteAllTextAsync(Utils.GetBaseDirectory("smoke-result.json"), JsonSerializer.Serialize(new { success, checks }, new JsonSerializerOptions { WriteIndented = true }));
            Application.Current.Shutdown(success ? 0 : 1);
        }
    }

    private static IEnumerable<DependencyObject> VisualChildren(DependencyObject parent)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index); yield return child;
            foreach (var descendant in VisualChildren(child)) yield return descendant;
        }
    }
}
