using System.Net.Http;
using ServiceLib.Handler.Builder;
using ServiceLib.Services;
using ServiceLib.Handler.SysProxy;
using Microsoft.Win32;
using System.Text.Json;
using ServiceLib.Helper;

namespace ServiceLib.DrKozol;

public sealed class DrKozolController
{
    public Config Config => AppManager.Instance.Config;
    public List<SubItem> Accounts { get; private set; } = [];
    public List<ProfileItem> Profiles { get; private set; } = [];
    public Dictionary<string, List<ProfileItem>> AccountProfiles { get; private set; } = [];
    public ConcurrentDictionary<string, int> Tcp { get; } = [];
    public ConcurrentDictionary<string, int> Real { get; } = [];
    public SubItem? Account => Accounts.FirstOrDefault(a => a.Id == Config.SubIndexId);
    public ProfileItem? SelectedProfile => AccountProfiles.Values.SelectMany(p => p).FirstOrDefault(p => p.IndexId == Config.IndexId);
    public bool Connected => _nativeReady && (CoreManager.Instance.IsRunning || _amnezia?.Running == true) && (_tun is null || _tun.Running);
    public bool UseTun => Config.TunModeItem.EnableTun;
    public string? LastConnectionFailure { get; private set; }
    public bool IsConnecting => _wantConnected && !Connected;
    public string HomeChoice { get; private set; } = "manual";
    public long MemoryBytes => Environment.WorkingSet;
    public bool SystemDarkTheme { get; private set; } = true;
    public bool LastProbeReal { get; private set; } = true;
    public bool LastProbeCancelled { get; private set; }
    public IEnumerable<ProfileItem> OrderProfiles(IEnumerable<ProfileItem> source, string? accountId) =>
        DesktopPreferences.OrderProfiles(source, LastProbeReal ? Real : Tcp,
            Accounts.FirstOrDefault(a => a.Id == accountId)?.SortOrder ?? Config.DrKozol.SortOrder);
    private volatile bool _nativeReady;
    private volatile bool _workBusy;
    private volatile bool _connectionBusy;
    public bool Busy => _workBusy || _connectionBusy;
    public bool IsTesting => _probeCancellation is not null;
    public long SessionBytes { get; private set; }
    public long Up { get; private set; }
    public long Down { get; private set; }
    public event Action? Changed;
    public event Action<string>? Notice;
    private readonly SemaphoreSlim _connection = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private SpeedtestService? _tests;
    private string? _connectedAccount;
    private long _lastRefresh;
    private ProxySnapshot? _proxy;
    private LinuxProxySnapshot? _linuxProxy;
    private AmneziaHost? _amnezia;
    private TunHost? _tun;
    private CancellationTokenSource? _probeCancellation;
    private Task? _monitorTask;
    private Task? _importTask;
    private Task? _probeTask;
    private Task? _qrTask;
    private Task? _selectionTask;
    private bool _closed;
    private bool _resumeAfterUnlock;
    private bool _wantConnected;
    private long _nextRecovery;
    private int _recoveryFailures;
    private Task? _sessionTask;
    private Task? _appearanceTask;
    private Task<string?>? _updateTask;
    private Task<bool>? _downloadTask;
    private string? _updateUrl;
    private string? _updateDigest;
    private Task<bool>? _preferencesTask;
    private Task<bool>? _providerTask;
    private Task? _groupTask;
    private Task? _dataTask;
    public bool SessionLocked { get; private set; }
    private bool _foreground = true;
    private bool _quietProbe;
    public bool Foreground {
        get => _foreground;
        set { _foreground = value; if (value && _quietProbe) CancelTests(); }
    }

    public Task<string?> CheckForUpdate() => _closed ? Task.FromResult<string?>(null)
        : _updateTask is { IsCompleted: false } ? _updateTask : (_updateTask = CheckForUpdateCore());

    private async Task<string?> CheckForUpdateCore()
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20), MaxResponseContentBufferSize = 2 * 1024 * 1024 };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("DrKozol/1.5.0");
            var json = await http.GetStringAsync("https://api.github.com/repos/SpecterLogic/Drkozol/releases/latest", _lifetime.Token);
            var installed = System.Reflection.Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(1, 4, 6);
            var release = System.Text.Json.Nodes.JsonNode.Parse(json)!;
            var url = FindDesktopUpdate(release, installed, OperatingSystem.IsWindows());
            _updateUrl = url;
            _updateDigest = (release["assets"] as System.Text.Json.Nodes.JsonArray)?.FirstOrDefault(a => a?["browser_download_url"]?.GetValue<string>() == url)?["digest"]?.GetValue<string>();
            Notice?.Invoke(url is null ? "upToDate" : "updateAvailable"); return url;
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { return null; }
        catch (Exception error) { Logging.SaveLog("Drkozol update check", new Exception(error.GetType().Name)); Notice?.Invoke("updateError"); return null; }
    }

    public static string? FindDesktopUpdate(System.Text.Json.Nodes.JsonNode release, Version installed, bool windows)
    {
        if (release["draft"]?.GetValue<bool>() == true || release["prerelease"]?.GetValue<bool>() == true
            || !Version.TryParse(release["tag_name"]?.GetValue<string>()?.TrimStart('v', 'V'), out var version)) return null;
        version = new Version(version.Major, version.Minor, Math.Max(0, version.Build), Math.Max(0, version.Revision));
        installed = new Version(installed.Major, installed.Minor, Math.Max(0, installed.Build), Math.Max(0, installed.Revision));
        if (version <= installed || release["assets"] is not System.Text.Json.Nodes.JsonArray assets) return null;
        foreach (var asset in assets)
        {
            var name = asset?["name"]?.GetValue<string>()?.ToLowerInvariant() ?? "";
            var compatible = windows ? name.Contains("windows-x64") && (name.EndsWith(".zip") || name.EndsWith(".exe") || name.EndsWith(".msi"))
                : name.Contains("linux") && (name.Contains("amd64") || name.Contains("x64")) && (name.EndsWith(".deb") || name.EndsWith(".tar.gz"));
            var url = asset?["browser_download_url"]?.GetValue<string>();
            if (compatible && name.StartsWith("drkozol-") && url?.StartsWith("https://github.com/SpecterLogic/Drkozol/releases/download/", StringComparison.Ordinal) == true) return url;
        }
        return null;
    }

    public Task<bool> DownloadUpdate(string url, string destination) => _closed ? Task.FromResult(false)
        : _downloadTask is { IsCompleted: false } ? _downloadTask : (_downloadTask = DownloadUpdateCore(url, destination));

    private async Task<bool> DownloadUpdateCore(string url, string destination)
    {
        if (url != _updateUrl || _updateDigest is not { Length: 71 } expected || !expected.StartsWith("sha256:", StringComparison.Ordinal))
        { Notice?.Invoke("updateError"); return false; }
        var temporary = destination + "." + Guid.NewGuid().ToString("N") + ".part";
        try
        {
            Notice?.Invoke("updateDownloading");
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token); deadline.CancelAfter(TimeSpan.FromMinutes(15));
            using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, deadline.Token); response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength > 400 * 1024 * 1024) throw new InvalidDataException();
            await using (var input = await response.Content.ReadAsStreamAsync(deadline.Token))
            await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
            {
                var buffer = new byte[65536]; long total = 0; int count;
                while ((count = await input.ReadAsync(buffer, deadline.Token)) != 0)
                {
                    total += count; if (total > 400 * 1024 * 1024) throw new InvalidDataException();
                    await output.WriteAsync(buffer.AsMemory(0, count), deadline.Token);
                }
            }
            await using (var stream = File.OpenRead(temporary))
            {
                var actual = Convert.ToHexString(await System.Security.Cryptography.SHA256.HashDataAsync(stream, deadline.Token));
                if (!actual.Equals(expected[7..], StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            }
            File.Move(temporary, destination, true); Notice?.Invoke("updateDownloaded"); return true;
        }
        catch (Exception error) { Logging.SaveLog("DrKozol update download", new Exception(error.GetType().Name)); Notice?.Invoke("updateError"); return false; }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    public Task<bool> SavePreferences(DesktopPreferences preferences) => _closed ? Task.FromResult(false)
        : _preferencesTask is { IsCompleted: false } ? _preferencesTask : (_preferencesTask = SavePreferencesCore(preferences));

    public async Task<List<RulesItem>> CustomRoutingRules()
    {
        var item = await SQLiteHelper.Instance.TableAsync<RoutingItem>().FirstOrDefaultAsync(r => r.Id == RoutingProfileLink.ProfileId);
        return item is null ? [] : JsonUtils.Deserialize<List<RulesItem>>(item.RuleSet) ?? [];
    }

    public Task<bool> SaveRoutingRules(string input) => _closed || Busy || Connected ? Task.FromResult(false)
        : _preferencesTask is { IsCompleted: false } ? Task.FromResult(false) : (_preferencesTask = SaveRoutingRulesCore(input));

    private async Task<bool> SaveRoutingRulesCore(string input)
    {
        try
        {
            var rules = await Task.Run(() => RoutingProfileLink.Parse(input), _lifetime.Token);
            await _connection.WaitAsync(_lifetime.Token);
            try
            {
                if (_closed || Connected) return false;
                await SQLiteHelper.Instance.ActivateRoutingProfileAsync(new RoutingItem { Id = RoutingProfileLink.ProfileId,
                    Remarks = "DrKozol", Enabled = true, RuleNum = rules.Count, RuleSet = JsonUtils.Serialize(rules), DomainStrategy = "AsIs" });
                Notice?.Invoke("saved"); Changed?.Invoke(); return true;
            }
            finally { _connection.Release(); }
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { return false; }
        catch (Exception error) { Logging.SaveLog("DrKozol routing import", new Exception(error.GetType().Name)); Notice?.Invoke("settingsError"); return false; }
    }

    private async Task<bool> SavePreferencesCore(DesktopPreferences preferences)
    {
        if (_closed) return false;
        if (!DesktopPreferences.ValidHttps(preferences.PingUrl) || preferences.UpdateMinutes is < 15 or > 43200
            || preferences.UserAgent.Contains('\r') || preferences.UserAgent.Contains('\n')
            || preferences.ApplicationMode is not ("all" or "include" or "exclude")
            || preferences.IpPreference is not ("auto" or "ipv4" or "ipv6")
            || !DesktopPreferences.ValidDns(preferences.TunnelDns)
            || preferences.ShareProxy && (preferences.SharingPort is < 1 or > 65535
                || string.IsNullOrWhiteSpace(preferences.ProxyUser) || string.IsNullOrWhiteSpace(preferences.ProxyPassword)))
        { Notice?.Invoke("settingsError"); return false; }
        await _connection.WaitAsync(_lifetime.Token);
        try
        {
            var previous = Config.DrKozol;
            var autoRun = Config.GuiItem.AutoRun;
            var pingUrl = Config.SpeedTestItem.SpeedPingTestUrl;
            Config.DrKozol = JsonUtils.DeepCopy(preferences);
            Config.GuiItem.AutoRun = preferences.AutoBoot;
            Config.SpeedTestItem.SpeedPingTestUrl = preferences.PingUrl;
            if (await ConfigHandler.SaveConfig(Config) != 0)
            {
                Config.DrKozol = previous; Config.GuiItem.AutoRun = autoRun; Config.SpeedTestItem.SpeedPingTestUrl = pingUrl;
                Notice?.Invoke("settingsError"); return false;
            }
            if (autoRun != preferences.AutoBoot) await AutoStartupHandler.UpdateTask(Config);
            Notice?.Invoke("saved"); Changed?.Invoke(); return true;
        }
        finally { _connection.Release(); }
    }

    public Task<bool> SaveProvider(SubItem account) => _closed ? Task.FromResult(false)
        : _providerTask is { IsCompleted: false } ? _providerTask : (_providerTask = SaveProviderCore(account));

    private async Task<bool> SaveProviderCore(SubItem account)
    {
        if (_closed || string.IsNullOrWhiteSpace(account.Remarks) || account.Remarks.Length > 128 || !Accounts.Any(a => a.Id == account.Id) || !DesktopPreferences.ValidDns(account.TunnelDns)
            || !string.IsNullOrWhiteSpace(account.ProbeUrl) && !DesktopPreferences.ValidHttps(account.ProbeUrl)
            || account.CustomUpdateInterval && account.AutoUpdateInterval is < 15 or > 43200)
        { Notice?.Invoke("settingsError"); return false; }
        try { await SQLiteHelper.Instance.ReplaceAsync(account); await Reload(); Notice?.Invoke("saved"); return true; }
        catch (Exception error) { Logging.SaveLog("Drkozol provider save", new Exception(error.GetType().Name)); Notice?.Invoke("settingsError"); return false; }
    }

    public Task CreateGroup(string name) => _closed || Busy ? Task.CompletedTask : (_groupTask = CreateGroupCore(name));

    private async Task CreateGroupCore(string name)
    {
        if (_closed || Busy || Connected || string.IsNullOrWhiteSpace(name) || name.Length > 128) return;
        _workBusy = true; Changed?.Invoke();
        try
        {
            var group = new SubItem { Id = Guid.NewGuid().ToString("N"), Remarks = name.Trim(), Url = "", LocalGroup = true };
            await SQLiteHelper.Instance.ReplaceAsync(group); Config.SubIndexId = group.Id;
            await ConfigHandler.SaveConfig(Config); await Reload();
        }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    public Task Backup(string path, bool restore) => _closed || Busy || Connected ? Task.CompletedTask
        : (_dataTask = BackupCore(path, restore));

    private async Task BackupCore(string path, bool restore)
    {
        _workBusy = true; Changed?.Invoke();
        try
        {
            if (restore)
            {
                var bundle = await Task.Run(async () => {
                    if (new FileInfo(path).Length > 32 * 1024 * 1024) throw new InvalidDataException();
                    return DesktopBackup.Parse(await File.ReadAllTextAsync(path, _lifetime.Token));
                }, _lifetime.Token);
                var incoming = bundle.Providers.Where(a => Accounts.All(current => current.Id != a.Id)).ToList();
                var profiles = bundle.Profiles.Where(p => incoming.Any(a => a.Id == p.Subid)).ToList();
                var existingIds = AccountProfiles.Values.SelectMany(p => p).Select(p => p.IndexId).ToHashSet();
                if (profiles.Any(p => existingIds.Contains(p.IndexId))) throw new InvalidDataException();
                await SQLiteHelper.Instance.MergeDesktopBackupAsync(incoming, profiles);
                await Reload();
            }
            else
            {
                var bundle = new DesktopBackup { Format = "DrKozol-desktop-1", Providers = Accounts.ToList(), Profiles = AccountProfiles.Values.SelectMany(p => p).ToList() };
                await Task.Run(async () => {
                    var json = JsonSerializer.Serialize(bundle, new JsonSerializerOptions { WriteIndented = true });
                    await File.WriteAllTextAsync(path, json, _lifetime.Token);
                    if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                }, _lifetime.Token);
            }
            Notice?.Invoke("saved");
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) { Logging.SaveLog("Drkozol local backup", new Exception(error.GetType().Name)); Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    public Task DeleteProvider(string id) => _closed || Busy || Connected ? Task.CompletedTask : (_dataTask = DeleteProviderCore(id));
    private async Task DeleteProviderCore(string id)
    {
        _workBusy = true; Changed?.Invoke();
        try { await ConfigHandler.DeleteSubItem(Config, id); await Reload(); await ConfigHandler.SaveConfig(Config); }
        catch (Exception error) { Logging.SaveLog("Drkozol provider removal", new Exception(error.GetType().Name)); Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    public async Task SessionLockChanged(bool locked)
    {
        if (_closed || locked == SessionLocked) return;
        SessionLocked = locked;
        if (!locked && _quietProbe) CancelTests();
        if (locked) Foreground = false;
        if (locked && Config.DrKozol.DisconnectOnLock && Connected)
        {
            _resumeAfterUnlock = true;
            _wantConnected = false;
            await _connection.WaitAsync(_lifetime.Token);
            try { await StopCore(); } finally { _connection.Release(); Changed?.Invoke(); }
        }
        else if (!locked)
        {
            var resume = _resumeAfterUnlock; _resumeAfterUnlock = false;
            if (resume && Config.DrKozol.ReconnectOnUnlock && !Connected) await Connect();
        }
    }

    public Task RefreshSubscriptions(string? accountId = null) => _closed || Busy ? Task.CompletedTask : (_importTask = RefreshSubscriptionsCore(accountId));

    private async Task RefreshSubscriptionsCore(string? accountId)
    {
        _workBusy = true; Changed?.Invoke();
        try
        {
            foreach (var account in Accounts.Where(a => a.Enabled && !a.LocalGroup && AccountUsage.ValidUrl(a.Url)
                && (accountId is null || a.Id == accountId)).ToArray()) await Fetch(account);
            await Reload(); Notice?.Invoke("updated");
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) { Logging.SaveLog("Drkozol subscription refresh", new Exception(error.GetType().Name)); Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    public Task ConnectBest() => _closed || Busy || _selectionTask?.IsCompleted == false ? Task.CompletedTask : (_selectionTask = ConnectBestCore());
    private async Task ConnectBestCore()
    {
        var eligible = Profiles.Where(p => !ServerPresentation.IsAccountInfo(p) && !p.ConfigType.IsGroupType()).ToList();
        var best = eligible.Where(p => Real.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => Real[p.IndexId]).FirstOrDefault();
        if (best is null && !Connected)
        {
            await Probe(true, eligible);
            if (LastProbeCancelled) return;
            best = eligible.Where(p => Real.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => Real[p.IndexId]).FirstOrDefault();
        }
        if (best is null) { Notice?.Invoke("testFirst"); return; }
        if (Connected && Config.IndexId == best.IndexId) { HomeChoice = "best"; Changed?.Invoke(); return; }
        await Connect(targetId: best.IndexId, choice: "best");
    }

    public Task ConnectBalancer() => ConnectBalancer(true);
    public Task ConnectBalancer(bool affectSystemProxy) => _closed || Busy || _selectionTask?.IsCompleted == false ? Task.CompletedTask : (_selectionTask = ConnectBalancerCore(affectSystemProxy));
    private async Task ConnectBalancerCore(bool affectSystemProxy)
    {
        var eligible = Profiles.Where(p => !ServerPresentation.IsAccountInfo(p) && !p.ConfigType.IsComplexType()
            && p.AmneziaConfig is null && Real.GetValueOrDefault(p.IndexId, 1) > 0)
            .OrderBy(p => Real.GetValueOrDefault(p.IndexId) > 0 ? Real[p.IndexId] : int.MaxValue).ToList();
        var healthy = eligible.Where(p => Real.GetValueOrDefault(p.IndexId) > 0).ToList();
        if (healthy.Count < 2 && !Connected)
        {
            await Probe(true, eligible);
            if (LastProbeCancelled) return;
            healthy = eligible.Where(p => Real.GetValueOrDefault(p.IndexId) > 0).ToList();
        }
        // Keep the default group small and measured: an intermittently failing slow peer
        // must not degrade every new application connection just because it was imported.
        eligible = healthy.Take(3).ToList();
        if (eligible.Count < 2 || Account is null) { Notice?.Invoke("balancerUnavailable"); return; }
        var profile = Profiles.FirstOrDefault(p => p.DrKozolGeneratedBalancer)
            ?? new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = Account.Id, Remarks = "Balancer", IsSub = false, DrKozolGeneratedBalancer = true, ConfigType = EConfigType.PolicyGroup, CoreType = ECoreType.Xray };
        var members = eligible.Select(p => p.IndexId).ToHashSet();
        if (Connected && Config.IndexId == profile.IndexId && profile.GetProtocolExtra().MultipleLoad == EMultipleLoad.RoundRobin
            && members.SetEquals((profile.GetProtocolExtra().ChildItems ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries)))
        { HomeChoice = "balancer"; Changed?.Invoke(); return; }
        profile.SetProtocolExtra(new ProtocolExtraItem { MultipleLoad = EMultipleLoad.RoundRobin,
            GroupType = EConfigType.PolicyGroup.ToString(), ChildItems = string.Join(",", eligible.Select(p => p.IndexId)) });
        await SQLiteHelper.Instance.ReplaceAsync(profile); await Reload();
        await Connect(affectSystemProxy, targetId: profile.IndexId, choice: "balancer");
    }

    public async Task Initialize()
    {
        if (OperatingSystem.IsWindows()) ProxySnapshot.Recover();
        await LinuxProxySnapshot.Recover();
        Config.GuiItem.EnableStatistics = true;
        Config.GuiItem.DisplayRealTimeSpeed = true;
        Config.DrKozol ??= new();
        SystemDarkTheme = await DesktopAppearance.ReadSystemDarkTheme();
        if (Config.GuiItem.AutoRun) Config.DrKozol.AutoBoot = true;
        Config.SpeedTestItem.SpeedPingTestUrl = Config.DrKozol.PingUrl;
        await ConfigHandler.InitBuiltinRouting(Config);
        await ConfigHandler.InitBuiltinDNS(Config);
        await ConfigHandler.InitBuiltinFullConfigTemplate(Config);
        await ProfileExManager.Instance.Init();
        await CoreManager.Instance.Init(Config, (_, _) => Task.CompletedTask);
        await CertPemManager.Instance.Init(Config);
        await StatisticsManager.Instance.Init(Config, speed =>
        {
            if (CoreManager.Instance.IsRunning) { Up = speed.ProxyUp; Down = speed.ProxyDown; SessionBytes += Math.Max(0, Up) + Math.Max(0, Down); }
            Changed?.Invoke();
            return Task.CompletedTask;
        });
        await Reload();
        _lastRefresh = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        _monitorTask = Monitor();
        if (OperatingSystem.IsWindows())
        {
            SystemEvents.SessionSwitch += OnSessionSwitch;
            SystemEvents.UserPreferenceChanged += OnSystemAppearanceChanged;
        }
        if (Config.DrKozol.UpdateOnLaunch && !Connected) await RefreshSubscriptions();
        else if (Config.DrKozol.PingOnLaunch && !Connected) await Probe(true, Profiles);
        if ((Config.DrKozol.AutoConnect || Config.DrKozol.AutoBoot && Environment.GetCommandLineArgs().Contains("--auto-start"))
            && !string.IsNullOrEmpty(Config.IndexId)) await Connect();
    }

    private void OnSessionSwitch(object sender, SessionSwitchEventArgs args)
    {
        if (!OperatingSystem.IsWindows()) return;
        if (args.Reason is not (SessionSwitchReason.SessionLock or SessionSwitchReason.SessionUnlock)) return;
        var previous = _sessionTask;
        _sessionTask = ApplySessionSwitch(previous, args.Reason == SessionSwitchReason.SessionLock);
    }

    private void OnSystemAppearanceChanged(object sender, UserPreferenceChangedEventArgs args)
    {
        if (_closed || Config.UiItem.CurrentTheme != "Auto" || _appearanceTask?.IsCompleted == false) return;
        _appearanceTask = RefreshSystemAppearance();
    }
    private async Task RefreshSystemAppearance()
    {
        SystemDarkTheme = await DesktopAppearance.ReadSystemDarkTheme();
        if (!_closed) Changed?.Invoke();
    }

    private async Task ApplySessionSwitch(Task? previous, bool locked)
    {
        try { if (previous is not null) await previous; await SessionLockChanged(locked); }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) { Logging.SaveLog("Drkozol session lifecycle", new Exception(error.GetType().Name)); }
    }

    public async Task Reload()
    {
        Accounts = await AppManager.Instance.SubItems();
        if (Accounts.All(a => a.Id != Config.SubIndexId)) Config.SubIndexId = Accounts.FirstOrDefault()?.Id ?? "";
        Profiles = await AppManager.Instance.ProfileItems(Config.SubIndexId);
        var allProfiles = new Dictionary<string, List<ProfileItem>>();
        foreach (var account in Accounts) allProfiles[account.Id] = await AppManager.Instance.ProfileItems(account.Id);
        AccountProfiles = allProfiles;
        if (!AccountProfiles.Values.SelectMany(p => p).Any(p => p.IndexId == Config.IndexId && !ServerPresentation.IsAccountInfo(p)))
        { Config.IndexId = ""; HomeChoice = "manual"; }
        if (SelectedProfile?.ConfigType == EConfigType.PolicyGroup) HomeChoice = "balancer";
        Changed?.Invoke();
    }

    public Task Import(string input) => _closed || Busy ? Task.CompletedTask : (_importTask = ImportCore(input));

    public Task ImportQrImage(string path) => _closed || Busy || Connected ? Task.CompletedTask : (_qrTask = ImportQrImageCore(path));

    private async Task ImportQrImageCore(string path)
    {
        _workBusy = true; Changed?.Invoke();
        try
        {
            var decoded = await Task.Run(async () =>
            {
                if (!File.Exists(path) || new FileInfo(path).Length > 10 * 1024 * 1024) throw new InvalidDataException();
                var bytes = await File.ReadAllBytesAsync(path, _lifetime.Token);
                return QRCodeUtils.ParseBarcode(bytes);
            }, _lifetime.Token);
            _lifetime.Token.ThrowIfCancellationRequested();
            if (string.IsNullOrWhiteSpace(decoded)) throw new InvalidDataException();
            _workBusy = false;
            if (AccountUsage.ValidUrl(decoded)) await Import(decoded); else await ImportConfigs(decoded);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception) { Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    private async Task ImportCore(string input)
    {
        if (input.Trim().StartsWith(RoutingProfileLink.Prefix, StringComparison.Ordinal)) { await SaveRoutingRules(input); return; }
        if (!AccountUsage.ValidUrl(input)) { Notice?.Invoke("url"); return; }
        if (Busy || Connected) return;
        Notice?.Invoke("clear");
        _workBusy = true; Changed?.Invoke();
        try
        {
            var url = input.Trim();
            var existing = Accounts.FirstOrDefault(a => a.Url == url);
            var account = existing ?? new SubItem { Id = Guid.NewGuid().ToString("N"), Url = url, Remarks = "DrKozol", Enabled = true };
            await Fetch(account);
            Config.SubIndexId = account.Id;
            await ConfigHandler.SaveConfig(Config);
            await Reload();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception) { Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    public Task ImportConfigs(string input) => _closed || Busy ? Task.CompletedTask : (_importTask = ImportConfigsCore(input));

    private async Task ImportConfigsCore(string input)
    {
        if (input.Trim().StartsWith(RoutingProfileLink.Prefix, StringComparison.Ordinal)) { await SaveRoutingRules(input); return; }
        if (Busy || Connected || Account is null || string.IsNullOrWhiteSpace(input)) return;
        _workBusy = true; Changed?.Invoke();
        try
        {
            if (AmneziaHost.Decode(input) is { } raw)
            {
                await AmneziaHost.Validate(raw);
                await SQLiteHelper.Instance.InsertAsync(new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = Account.Id, IsSub = false, ConfigType = EConfigType.Custom, Remarks = AmneziaHost.DisplayName(input.Trim()), AmneziaConfig = raw });
            }
            else
            {
                if (AccountUsage.ValidUrl(input) || await ConfigHandler.AddBatchServers(Config, input, Account.Id, false) <= 0) throw new InvalidDataException();
            }
            await Reload();
        }
        catch (Exception) { Notice?.Invoke("importError"); }
        finally { _workBusy = false; Changed?.Invoke(); }
    }

    private async Task Fetch(SubItem account, bool replaceProfiles = true)
    {
        using var client = new HttpClient(new HttpClientHandler { UseProxy = Connected, AllowAutoRedirect = false,
            Proxy = Connected ? new WebProxy($"socks5://127.0.0.1:{AppManager.Instance.GetLocalPort(EInboundProtocol.socks)}") : null })
            { Timeout = TimeSpan.FromSeconds(25), MaxResponseContentBufferSize = 5 * 1024 * 1024 };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(string.IsNullOrWhiteSpace(account.UserAgent) ? Config.DrKozol.UserAgent : account.UserAgent);
        if (Config.DrKozol.SendDeviceId) client.DefaultRequestHeaders.Add("x-hwid", Config.DrKozol.DeviceId);
        using var response = await client.GetAsync(account.Url, _lifetime.Token);
        response.EnsureSuccessStatusCode();
        AccountUsage.Capture(account, response.Headers);
        // Preserve the new expiry even when an expired subscription returns no profiles.
        await SQLiteHelper.Instance.ReplaceAsync(account);
        var content = await response.Content.ReadAsStringAsync(_lifetime.Token);
        if (AccountUsage.Locked(account) || !replaceProfiles) return;
        // Parse into a new group first; a malformed refresh must not erase a working group.
        var stagingId = Guid.NewGuid().ToString("N");
        try
        {
            var decoded = Utils.Base64Decode(content);
            if (string.IsNullOrWhiteSpace(decoded)) decoded = content;
            var awgProfiles = new List<ProfileItem>();
            var text = content.TrimStart().StartsWith("[Interface]") ? new[] { content } : decoded.Split('\n');
            foreach (var line in text)
                if (AmneziaHost.Decode(line.Trim()) is { } raw)
                {
                    await AmneziaHost.Validate(raw);
                    awgProfiles.Add(new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = stagingId, ConfigType = EConfigType.Custom, Remarks = AmneziaHost.DisplayName(line.Trim()), AmneziaConfig = raw });
                }
            var count = await ConfigHandler.AddBatchServers(Config, content, stagingId, true);
            if (awgProfiles.Count > 0) { await SQLiteHelper.Instance.InsertAllAsync(awgProfiles); count = Math.Max(0, count) + awgProfiles.Count; }
            if (count <= 0) throw new InvalidDataException();
            var staged = await AppManager.Instance.ProfileItems(stagingId);
            var stagingRows = staged.Select(p => JsonUtils.DeepCopy(p)).ToList();
            var old = await AppManager.Instance.ProfileItems(account.Id);
            var identities = old.Where(p => p.IsSub).GroupBy(DesktopPreferences.ProfileIdentity)
                .ToDictionary(g => g.Key, g => new Queue<ProfileItem>(g));
            foreach (var profile in staged)
            {
                profile.Subid = account.Id;
                if (identities.TryGetValue(DesktopPreferences.ProfileIdentity(profile), out var matches) && matches.TryDequeue(out var previous))
                    profile.IndexId = previous.IndexId;
            }
            staged.AddRange(old.Where(p => !p.IsSub));
            foreach (var balancer in staged.Where(p => p.DrKozolGeneratedBalancer))
                balancer.SetProtocolExtra(balancer.GetProtocolExtra() with {
                    ChildItems = string.Join(",", staged.Where(p => p.AmneziaConfig is null && !p.ConfigType.IsComplexType()
                        && !ServerPresentation.IsAccountInfo(p)).Select(p => p.IndexId)) });
            if (Connected && old.FirstOrDefault(p => p.IndexId == Config.IndexId) is { } active
                && staged.All(p => p.IndexId != active.IndexId)) staged.Add(active);
            await SQLiteHelper.Instance.ReplaceSubscriptionProfilesAsync(staged, [.. old, .. stagingRows]);
        }
        catch
        {
            foreach (var profile in await AppManager.Instance.ProfileItems(stagingId)) await SQLiteHelper.Instance.DeleteAsync(profile);
            throw;
        }
    }

    public async Task SelectAccount(string id)
    {
        if (Connected || Busy) return;
        Config.SubIndexId = id;
        await Reload(); await ConfigHandler.SaveConfig(Config);
    }

    public async Task SelectServer(string id)
    {
        if (_closed) return;
        await _connection.WaitAsync();
        try
        {
            if (_closed || Connected || Busy || !Profiles.Any(p => p.IndexId == id && !ServerPresentation.IsAccountInfo(p))) return;
            Config.IndexId = id;
            HomeChoice = "manual";
            await ConfigHandler.SaveConfig(Config);
            Changed?.Invoke();
        }
        finally { _connection.Release(); }
    }

    public async Task Connect(bool affectSystemProxy = true, string? targetId = null, string choice = "manual")
    {
        if (_closed) return;
        await _connection.WaitAsync();
        var ownsBusy = false;
        try
        {
            if (_closed) return;
            if (targetId is not null && !AccountProfiles.Values.SelectMany(p => p).Any(p => p.IndexId == targetId && !ServerPresentation.IsAccountInfo(p)))
            { if (!Connected) _wantConnected = false; return; }
            if (targetId is null && _wantConnected && !Connected)
            {
                _wantConnected = false; await StopCore(); return;
            }
            if (Connected)
            {
                ownsBusy = true; _connectionBusy = true; Changed?.Invoke(); await StopCore();
                if (targetId is null) { _wantConnected = false; return; }
                _connectionBusy = false;
            }
            if (targetId is not null) Config.IndexId = targetId;
            if (Busy) return;
            var profile = SelectedProfile;
            if (profile is null || ServerPresentation.IsAccountInfo(profile)) { _wantConnected = false; return; }
            var owner = Accounts.FirstOrDefault(a => a.Id == profile.Subid);
            if (owner is null || AccountUsage.Locked(owner)) { _wantConnected = false; return; }
            Config.SubIndexId = owner.Id;
            Profiles = AccountProfiles.GetValueOrDefault(owner.Id) ?? [];
            if (UseTun && !TunHost.HasTunPermission) { Notice?.Invoke("admin"); return; }
            ownsBusy = true; _connectionBusy = true; _wantConnected = true; LastConnectionFailure = null;
            HomeChoice = profile.ConfigType == EConfigType.PolicyGroup ? "balancer"
                : targetId is null && choice == "manual" ? HomeChoice : choice;
            Changed?.Invoke();
            await Task.Run(() => {
                var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,
                    AppManager.Instance.GetLocalPort(EInboundProtocol.socks));
                try { listener.Start(); } finally { listener.Stop(); }
            }, _lifetime.Token);
            var transportEndpoints = new List<System.Net.IPAddress>();
            if (profile.AmneziaConfig is { } raw)
            {
                if (UseTun) transportEndpoints.AddRange(await AmneziaHost.EndpointAddresses(raw, _lifetime.Token));
                _amnezia = new AmneziaHost();
                long prevUp = 0, prevDown = 0;
                _amnezia.Stats += (up, down) => { Up = Math.Max(0, up - prevUp); Down = Math.Max(0, down - prevDown); prevUp = up; prevDown = down; SessionBytes = up + down; Changed?.Invoke(); };
                await _amnezia.Start(raw, AppManager.Instance.GetLocalPort(EInboundProtocol.socks));
            }
            else
            {
                var nativeConfig = JsonUtils.DeepCopy(Config);
                nativeConfig.TunModeItem.EnableTun = false;
                nativeConfig.SpeedTestItem.SpeedPingTestUrl = string.IsNullOrWhiteSpace(owner.ProbeUrl) ? Config.DrKozol.PingUrl : owner.ProbeUrl;
                // Protocol engines stay private; authenticated LAN sharing has its own listener.
                nativeConfig.Inbound.First().AllowLANConn = false;
                nativeConfig.Inbound.First().SecondLocalPortEnabled = false;
                var runtimeProfile = UseTun ? await TunHost.ResolveEndpoint(profile, _lifetime.Token, Config.DrKozol.IpPreference) : profile;
                var context = await CoreConfigContextBuilder.BuildAll(nativeConfig, runtimeProfile);
                if (!context.Success) throw new InvalidDataException();
                if (UseTun) await TunHost.ResolveContextEndpoints(context.MainResult.Context, _lifetime.Token,
                    (node, token) => TunHost.ResolveEndpoint(node, token, Config.DrKozol.IpPreference));
                if (UseTun)
                {
                    if (context.PreSocksResult is { } pre)
                        await TunHost.ResolveContextEndpoints(pre.Context, _lifetime.Token,
                            (node, token) => TunHost.ResolveEndpoint(node, token, Config.DrKozol.IpPreference));
                    foreach (var node in context.MainResult.Context.AllProxiesMap.Values
                        .Concat(context.PreSocksResult?.Context.AllProxiesMap.Values.AsEnumerable() ?? []))
                        if (System.Net.IPAddress.TryParse(node.Address, out var endpoint)) transportEndpoints.Add(endpoint);
                }
                await CoreManager.Instance.LoadCore(context.MainResult.Context, context.PreSocksResult?.Context);
            }
            // Process.Start is not a ready signal. Do not route applications to a listener that
            // has not opened yet; the first request otherwise races native core initialization.
            await WaitForProxyListener();
            if (profile.DrKozolGeneratedBalancer)
            {
                // A SOCKS listener can open before the new balancer has a working TLS route.
                // Verify fresh connections before handing applications to the new engine.
                using var ready = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token); ready.CancelAfter(TimeSpan.FromSeconds(12));
                using var http = new HttpClient(new HttpClientHandler { UseProxy = true,
                    Proxy = new WebProxy($"socks5://127.0.0.1:{AppManager.Instance.GetLocalPort(EInboundProtocol.socks)}") }) { Timeout = TimeSpan.FromSeconds(3) };
                http.DefaultRequestHeaders.ConnectionClose = true;
                var url = string.IsNullOrWhiteSpace(owner.ProbeUrl) ? Config.DrKozol.PingUrl : owner.ProbeUrl;
                using var metrics = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(2), MaxResponseContentBufferSize = 2 * 1024 * 1024 };
                var members = (profile.GetProtocolExtra().ChildItems ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries).Length;
                while (true)
                {
                    ready.Token.ThrowIfCancellationRequested();
                    try
                    {
                        var report = await metrics.GetStringAsync($"http://127.0.0.1:{AppManager.Instance.StatePort}/debug/vars", ready.Token);
                        if (BalancerHealthComplete(report, members)) break;
                    }
                    catch (HttpRequestException) { }
                    await Task.Delay(200, ready.Token);
                }
                await WaitForUsableRoute(async token => {
                    if (!CoreManager.Instance.IsRunning) throw new IOException();
                    try { using var response = await http.GetAsync(url, token); return response.IsSuccessStatusCode; }
                    catch (OperationCanceledException) when (!token.IsCancellationRequested) { return false; }
                }, ready.Token);
            }
            if (UseTun || Config.DrKozol.ShareProxy)
            {
                _tun = new TunHost();
                await _tun.Start(AppManager.Instance.GetLocalPort(EInboundProtocol.socks), _lifetime.Token, Config.DrKozol,
                    string.IsNullOrWhiteSpace(Account?.TunnelDns) ? Config.DrKozol.TunnelDns : Account.TunnelDns, UseTun, transportEndpoints);
            }
            _nativeReady = true;
            _recoveryFailures = 0;
            if (!Connected) throw new IOException();
            _connectedAccount = profile.Subid; SessionBytes = 0;
            if (affectSystemProxy && !UseTun)
            {
                if (OperatingSystem.IsLinux()) _linuxProxy = await LinuxProxySnapshot.Apply(AppManager.Instance.GetLocalPort(EInboundProtocol.socks), _lifetime.Token);
                else if (OperatingSystem.IsWindows())
                {
                    _proxy = ProxySnapshot.Capture(AppManager.Instance.GetLocalPort(EInboundProtocol.socks));
                    Config.SystemProxyItem.SysProxyType = ESysProxyType.ForcedChange;
                    await SysProxyHandler.UpdateSysProxy(Config, false);
                }
            }
            await ConfigHandler.SaveConfig(Config);
        }
        catch (Exception error)
        {
            LastConnectionFailure = error.GetType().Name + (_tun?.StartupFailure is { } failure ? ": " + failure : "");
            await StopCore(); Notice?.Invoke("connectError");
            _nextRecovery = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + Math.Min(30, 2 << Math.Min(_recoveryFailures++, 4));
            Logging.SaveLog("Drkozol native connection setup", new Exception(error.GetType().Name));
        }
        finally { if (ownsBusy) _connectionBusy = false; _connection.Release(); Changed?.Invoke(); }
    }

    private async Task WaitForProxyListener()
    {
        var deadline = System.Diagnostics.Stopwatch.StartNew();
        using var startup = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        startup.CancelAfter(TimeSpan.FromSeconds(8));
        var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
        while (deadline.Elapsed < TimeSpan.FromSeconds(8))
        {
            startup.Token.ThrowIfCancellationRequested();
            if (!(CoreManager.Instance.IsRunning || _amnezia?.Running == true)) throw new IOException();
            using var socket = new System.Net.Sockets.TcpClient();
            try
            {
                await socket.ConnectAsync(System.Net.IPAddress.Loopback, port, startup.Token);
                return;
            }
            catch (System.Net.Sockets.SocketException) { await Task.Delay(100, startup.Token); }
        }
        throw new TimeoutException();
    }

    public static async Task WaitForUsableRoute(Func<CancellationToken, Task<bool>> probe, CancellationToken token)
    {
        var consecutive = 0;
        while (consecutive < 2)
        {
            token.ThrowIfCancellationRequested();
            try { consecutive = await probe(token) ? consecutive + 1 : 0; }
            catch (HttpRequestException error) { consecutive = 0; Logging.SaveLog("DrKozol balancer route readiness", new Exception(error.GetType().Name)); }
            if (consecutive < 2) await Task.Delay(250, token);
        }
    }

    public static bool BalancerHealthComplete(string report, int members)
    {
        using var document = JsonDocument.Parse(report);
        if (!document.RootElement.TryGetProperty("observatory", out var statuses) || statuses.ValueKind != JsonValueKind.Object) return false;
        var known = 0; var alive = 0;
        foreach (var entry in statuses.EnumerateObject())
        {
            if (!entry.Name.StartsWith("proxy", StringComparison.Ordinal) || !entry.Value.TryGetProperty("last_try_time", out var tried) || tried.GetInt64() <= 0) continue;
            known++;
            if (entry.Value.TryGetProperty("alive", out var usable) && usable.ValueKind == JsonValueKind.True) alive++;
        }
        return members > 0 && known >= members && alive > 0;
    }

    private async Task StopCore()
    {
        _nativeReady = false;
        try { CancelTests(); } catch (ObjectDisposedException) { }
        var tun = _tun; _tun = null;
        if (tun is not null) await Cleanup(() => tun.DisposeAsync().AsTask(), "tunnel");
        await Cleanup(() => CoreManager.Instance.CoreStop(), "protocol core");
        var amnezia = _amnezia; _amnezia = null;
        if (amnezia is not null) await Cleanup(() => amnezia.DisposeAsync().AsTask(), "AmneziaWG");
        if (OperatingSystem.IsWindows())
        {
            try { _proxy?.Restore(); }
            catch (Exception error) { Logging.SaveLog("Drkozol restore system proxy", new Exception(error.GetType().Name)); }
            finally { _proxy = null; }
        }
        var linuxProxy = _linuxProxy; _linuxProxy = null;
        if (linuxProxy is not null) await Cleanup(linuxProxy.Restore, "Linux proxy");
        _connectedAccount = null; Up = Down = 0;
    }

    private static async Task Cleanup(Func<Task> operation, string component)
    {
        try { await operation(); }
        catch (Exception error) { Logging.SaveLog("Drkozol cleanup " + component, new Exception(error.GetType().Name)); }
    }

    public async Task SetTun(bool enabled)
    {
        await _connection.WaitAsync();
        try
        {
            if (_closed || Busy || _connectedAccount is not null) return;
            Config.TunModeItem.EnableTun = enabled;
            await ConfigHandler.SaveConfig(Config); Changed?.Invoke();
        }
        finally { _connection.Release(); }
    }

    public Task Probe(bool real, IEnumerable<ProfileItem> profiles) => _closed || Busy ? Task.CompletedTask : (_probeTask = ProbeCore(real, profiles));

    private async Task ProbeCore(bool real, IEnumerable<ProfileItem> profiles, SubItem? provider = null)
    {
        if (Busy || Connected) return;
        var owner = provider ?? Account;
        if (owner is null) { Notice?.Invoke("probeError"); return; }
        if (AccountUsage.Locked(owner)) { Notice?.Invoke("probeLocked"); return; }
        LastProbeCancelled = false;
        _workBusy = true; Changed?.Invoke();
        var target = real ? Real : Tcp;
        LastProbeReal = real;
        _probeCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        Changed?.Invoke();
        var probeToken = _probeCancellation.Token;
        var list = profiles.Where(p => !ServerPresentation.IsAccountInfo(p)).ToList();
        foreach (var profile in list) target.TryRemove(profile.IndexId, out _);
        var testConfig = JsonUtils.DeepCopy(Config);
        testConfig.SpeedTestItem.SpeedPingTestUrl = Config.DrKozol.PingUrl;
        if (!string.IsNullOrWhiteSpace(owner.ProbeUrl)) testConfig.SpeedTestItem.SpeedPingTestUrl = owner.ProbeUrl;
        testConfig.TunModeItem.EnableTun = false;
        _tests = new SpeedtestService(testConfig, result =>
        {
            if (result.IndexId is { Length: > 0 } && int.TryParse(result.Delay, out var delay))
                { target[result.IndexId] = delay; Changed?.Invoke(); }
            return Task.CompletedTask;
        });
        try
        {
            await _tests.RunLoop(real ? ESpeedActionType.Realping : ESpeedActionType.Tcping, list.Where(p => p.AmneziaConfig is null).ToList(), probeToken);
            probeToken.ThrowIfCancellationRequested();
            foreach (var profile in list.Where(p => p.AmneziaConfig is not null))
            {
                var delay = -1;
                probeToken.ThrowIfCancellationRequested();
                if (real) try { delay = await AmneziaHost.RealDelay(profile.AmneziaConfig!, probeToken, testConfig.SpeedTestItem.SpeedPingTestUrl); } catch (OperationCanceledException) { throw; } catch (Exception) { }
                // AmneziaWG endpoints use UDP; a TCP test is not applicable.
                target[profile.IndexId] = delay; Changed?.Invoke();
            }
            // A completed test always presents its fresh results from fastest to slowest.
            var testedAccounts = list.Select(p => p.Subid).ToHashSet();
            foreach (var account in Accounts.Where(a => testedAccounts.Contains(a.Id)))
            {
                account.SortOrder = "ping";
                await SQLiteHelper.Instance.ReplaceAsync(account);
            }
        }
        catch (OperationCanceledException) { LastProbeCancelled = true; }
        catch (Exception) { Notice?.Invoke("probeError"); }
        finally { _workBusy = false; _tests = null; _probeCancellation?.Dispose(); _probeCancellation = null; Changed?.Invoke(); }
    }

    public void CancelTests()
    {
        try { _probeCancellation?.Cancel(); } catch (ObjectDisposedException) { }
        _tests?.ExitLoop();
    }

    public Task ProbeCurrent(bool real)
    {
        if (_closed || Busy) return Task.CompletedTask;
        var profile = SelectedProfile;
        if (profile is null) return Task.CompletedTask;
        // A user-requested offline diagnostic must not race automatic reconnection.
        // Testing never implicitly resumes a failed connection; the user can connect afterward.
        if (!Connected) _wantConnected = false;
        return _probeTask = Connected ? ProbeConnected(real, profile)
            : ProbeCore(real, [profile], Accounts.FirstOrDefault(a => a.Id == profile.Subid));
    }

    private async Task ProbeConnected(bool real, ProfileItem profile)
    {
        LastProbeReal = real;
        _workBusy = true; Changed?.Invoke();
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        cancellation.CancelAfter(TimeSpan.FromSeconds(15)); _probeCancellation = cancellation;
        Changed?.Invoke();
        var target = real ? Real : Tcp;
        try
        {
            var watch = Stopwatch.StartNew();
            if (real)
            {
                using var http = new HttpClient(new HttpClientHandler { UseProxy = true,
                    Proxy = new WebProxy($"socks5://127.0.0.1:{AppManager.Instance.GetLocalPort(EInboundProtocol.socks)}") });
                var url = Accounts.FirstOrDefault(a => a.Id == profile.Subid)?.ProbeUrl;
                using var response = await http.GetAsync(string.IsNullOrWhiteSpace(url) ? Config.DrKozol.PingUrl : url, cancellation.Token);
                response.EnsureSuccessStatusCode();
            }
            else
            {
                if (profile.AmneziaConfig is not null) { Notice?.Invoke("tcpNotApplicable"); return; }
                using var tcp = new TcpClient(); await tcp.ConnectAsync(profile.Address, profile.Port, cancellation.Token);
            }
            if (!_closed && _connectedAccount == profile.Subid && Config.IndexId == profile.IndexId)
                target[profile.IndexId] = Math.Max(1, (int)watch.ElapsedMilliseconds);
        }
        catch (OperationCanceledException) { if (!_lifetime.IsCancellationRequested && Connected) { target[profile.IndexId] = -1; Notice?.Invoke("probeError"); } }
        catch (Exception) { if (Connected) { target[profile.IndexId] = -1; Notice?.Invoke("probeError"); } }
        finally { if (ReferenceEquals(_probeCancellation, cancellation)) _probeCancellation = null; _workBusy = false; Changed?.Invoke(); }
    }

    public Task Best(IEnumerable<ProfileItem> profiles)
    {
        if (_closed || Busy || Connected) return Task.CompletedTask;
        var eligible = profiles.Where(p => !ServerPresentation.IsAccountInfo(p)).ToList();
        var best = eligible.Where(p => Real.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => Real[p.IndexId]).FirstOrDefault()
            ?? eligible.Where(p => Tcp.GetValueOrDefault(p.IndexId) > 0).OrderBy(p => Tcp[p.IndexId]).FirstOrDefault();
        if (best is null) { Notice?.Invoke("testFirst"); return Task.CompletedTask; }
        return _selectionTask = SelectServer(best.IndexId);
    }

    private async Task Monitor()
    {
        try
        {
            while (!_lifetime.IsCancellationRequested)
            {
                await Task.Delay(1000, _lifetime.Token);
                try
                {
                var active = Accounts.FirstOrDefault(a => a.Id == _connectedAccount);
                if (_connectedAccount is not null && (!Connected || active is null || AccountUsage.Locked(active)))
                {
                    if (active is null || AccountUsage.Locked(active)) _wantConnected = false;
                    await _connection.WaitAsync(_lifetime.Token);
                    try { await StopCore(); } finally { _connection.Release(); }
                }
                var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                if (OperatingSystem.IsLinux() && now % 5 == 0 && (Config.DrKozol.DisconnectOnLock || Config.DrKozol.ReconnectOnUnlock || Config.DrKozol.PingOnUpdate))
                    if (await DesktopSession.IsLocked(_lifetime.Token) is { } locked) await SessionLockChanged(locked);
                if (_wantConnected && !Connected && !Busy && !SessionLocked && now >= _nextRecovery)
                    await Connect(targetId: Config.IndexId, choice: HomeChoice);
                if (now - _lastRefresh >= 60 && Config.DrKozol.AutoUpdate && !Busy && (!_wantConnected || Connected))
                {
                    _lastRefresh = now;
                    var due = Accounts.Where(a => a.Enabled && !a.LocalGroup && AccountUsage.ValidUrl(a.Url)
                        && now - (a.CheckedAt ?? 0) >= Config.DrKozol.EffectiveInterval(a) * 60L).ToArray();
                    if (due.Length > 0)
                    {
                        _workBusy = true; Changed?.Invoke();
                        try
                        {
                            foreach (var account in due) await Fetch(account);
                            await Reload();
                            if (Config.DrKozol.NotifyUpdate) Notice?.Invoke("updated");
                        }
                        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
                        catch (Exception error) { Logging.SaveLog("Drkozol scheduled refresh", new Exception(error.GetType().Name)); }
                        finally { _workBusy = false; Changed?.Invoke(); }
                        // Never run a native batch across the user's active VPN or foreground work.
                        if (Config.DrKozol.PingOnUpdate && !_wantConnected && !Connected && SessionLocked && !Foreground
                            && await DesktopSession.QuietPowerAvailable(_lifetime.Token)
                            && !await Task.Run(DesktopSession.OtherVpnActive, _lifetime.Token))
                        {
                            _quietProbe = true;
                            try { await Probe(true, Profiles); } finally { _quietProbe = false; }
                        }
                    }
                }
                Changed?.Invoke();
                }
                catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { throw; }
                catch (Exception error) { Logging.SaveLog("Drkozol desktop monitor", new Exception(error.GetType().Name)); }
            }
        }
        catch (OperationCanceledException) { }
    }

    public async Task Close()
    {
        if (_closed) return;
        _closed = true;
        _wantConnected = false;
        if (OperatingSystem.IsWindows())
        {
            SystemEvents.SessionSwitch -= OnSessionSwitch;
            SystemEvents.UserPreferenceChanged -= OnSystemAppearanceChanged;
        }
        _lifetime.Cancel(); CancelTests();
        try { await Task.WhenAll(new Task?[] { _importTask, _probeTask, _qrTask, _selectionTask, _sessionTask, _appearanceTask,
            _updateTask, _downloadTask, _preferencesTask, _providerTask, _groupTask, _dataTask }.OfType<Task>()); }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) { Logging.SaveLog("Drkozol join desktop work", new Exception(error.GetType().Name)); }
        if (_monitorTask is not null) await Cleanup(() => _monitorTask, "monitor work");
        await _connection.WaitAsync();
        try { await StopCore(); StatisticsManager.Instance.Close(); await StatisticsManager.Instance.SaveTo(); await ConfigHandler.SaveConfig(Config); }
        finally { _connection.Release(); }
    }

    [SupportedOSPlatform("windows")]
    private sealed class ProxySnapshot
    {
        private readonly Dictionary<string, (object? Value, RegistryValueKind Kind)> _values = [];
        private const string Path = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        private static string LeasePath => Utils.GetConfigPath("DrKozol-proxy-lease.json");
        private sealed record Lease(Dictionary<string, string?> Values, Dictionary<string, RegistryValueKind> Kinds, int Owner, int Port);
        public static ProxySnapshot Capture(int port)
        {
            if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException();
            var result = new ProxySnapshot();
            using var key = Registry.CurrentUser.OpenSubKey(Path);
            foreach (var name in new[] { "ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL" })
                result._values[name] = (key?.GetValue(name), key?.GetValue(name) is null ? RegistryValueKind.String : key.GetValueKind(name));
            File.WriteAllText(LeasePath, JsonSerializer.Serialize(new Lease(result._values.ToDictionary(p => p.Key, p => p.Value.Value?.ToString()), result._values.ToDictionary(p => p.Key, p => p.Value.Kind), Environment.ProcessId, port)));
            return result;
        }
        public static void Recover()
        {
            if (!OperatingSystem.IsWindows()) return;
            if (!File.Exists(LeasePath)) return;
            try
            {
                var lease = JsonSerializer.Deserialize<Lease>(File.ReadAllText(LeasePath)); if (lease is null) return;
                try { if (!Process.GetProcessById(lease.Owner).HasExited) return; } catch (ArgumentException) { }
                using var key = Registry.CurrentUser.OpenSubKey(Path);
                // Respect changes made by the user or another proxy client after this app stopped.
                if (!(key?.GetValue("ProxyServer")?.ToString()?.Contains($"127.0.0.1:{lease.Port}") ?? false)) { File.Delete(LeasePath); return; }
                var snapshot = new ProxySnapshot();
                foreach (var entry in lease.Values)
                    snapshot._values[entry.Key] = (entry.Value is not null && lease.Kinds[entry.Key] == RegistryValueKind.DWord ? int.Parse(entry.Value) : entry.Value, lease.Kinds[entry.Key]);
                snapshot.Restore();
            }
            catch (Exception) { /* Leave the lease available for recovery if the registry is temporarily inaccessible. */ }
        }
        public void Restore()
        {
            if (!OperatingSystem.IsWindows()) return;
            using var key = Registry.CurrentUser.OpenSubKey(Path, true);
            if (key is null) return;
            foreach (var entry in _values)
                if (entry.Value.Value is null) key.DeleteValue(entry.Key, false); else key.SetValue(entry.Key, entry.Value.Value, entry.Value.Kind);
            RefreshInternetSettings(IntPtr.Zero, 39, IntPtr.Zero, 0);
            RefreshInternetSettings(IntPtr.Zero, 37, IntPtr.Zero, 0);
            if (File.Exists(LeasePath)) File.Delete(LeasePath);
        }
        [DllImport("wininet.dll", EntryPoint = "InternetSetOptionW")]
        private static extern bool RefreshInternetSettings(IntPtr handle, int option, IntPtr buffer, int length);
    }
}
