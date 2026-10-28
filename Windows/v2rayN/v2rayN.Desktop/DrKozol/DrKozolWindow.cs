using Avalonia.Automation;
using System.Diagnostics;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Presenters;
using Avalonia.Controls.Templates;
using Avalonia.Data;
using Avalonia.Layout;
using Avalonia.Input.Platform;
using Avalonia.Platform.Storage;
using ServiceLib.DrKozol;

namespace v2rayN.Desktop.DrKozol;

public sealed class DrKozolWindow : Window
{
    internal bool CanActivate => !_closing;
    private readonly DrKozolController _client = new();
    private readonly Grid _shell = new();
    private readonly ScrollViewer _scroll = new() { HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private readonly Dictionary<string, Bitmap> _flags = [];
    private readonly Dictionary<string, TextBlock> _delays = [];
    private readonly Dictionary<string, (TextBlock Label, ProgressBar Bar)> _quotas = [];
    private readonly HashSet<string> _expanded = [];
    private readonly CancellationTokenSource _lifetime = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly double[] _offsets = new double[3];
    private Task? _flagTask;
    private Task? _permissionTask;
    private bool _ready, _closing, _adding, _compactSidebar;
    private DateTimeOffset _noticeUntil = DateTimeOffset.MaxValue;
    private bool _themePage;
    private int _tab;
    private string _search = "", _category = "All", _input = "", _notice = "", _lastStructure = "";
    private StackPanel _content = new();
    private TextBlock? _state, _speed, _status, _activeDelay;
    private TextBox? _url;
    private bool Farsi => _client.Config.UiItem.CurrentLanguage == "fa";
    private DesktopPalette Palette => DesktopAppearance.For(_client.Config.UiItem.CurrentTheme == "Auto" ? (ActualThemeVariant == ThemeVariant.Dark ? "Dark" : "Light") : _client.Config.UiItem.CurrentTheme, _client.Config.UiItem.ColorPrimaryName);
    private IBrush Bg => Brush(Palette.Background);
    private IBrush Panel => Brush(Palette.Panel);
    private IBrush Ink => Brush(Palette.Text);
    private IBrush Muted => Brush(Palette.Muted);
    private IBrush Accent => Brush(Palette.Accent);
    private IBrush Line => Brush(Palette.Border);
    private static IBrush Brush(string color) => new SolidColorBrush(Color.Parse(color));
    private static readonly FontFamily DesktopFont = new("DejaVu Sans");
    private IBrush Tint { get { var color = Color.Parse(Palette.Accent); return new SolidColorBrush(Color.FromArgb(30, color.R, color.G, color.B)); } }
    private string T(string fa, string en) => Farsi ? fa : en;

    internal DrKozolController TestClient => _client;
    internal async Task InitializeForSmoke()
    {
        await Task.Run(AppManager.Instance.InitComponents);
        await _client.Initialize(); await PrepareFlags(); _ready = true; Build();
    }
    internal async Task RenderForSmoke(int tab, string mode, string color)
    {
        _tab = tab; _themePage = false; _client.Config.UiItem.CurrentTheme = mode; _client.Config.UiItem.ColorPrimaryName = color;
        foreach (var account in _client.Accounts) _expanded.Add(account.Id);
        await PrepareFlags(); Build();
    }

    internal void RenderThemeGalleryForSmoke() { _tab = 2; _themePage = true; Build(); }

    public DrKozolWindow(bool smoke = false)
    {
        Activated += (_, _) => _client.Foreground = true;
        Deactivated += (_, _) => _client.Foreground = false;
        ActualThemeVariantChanged += (_, _) => { if (_ready && _client.Config.UiItem.CurrentTheme == "Auto") Dispatcher.UIThread.Post(Build); };
        KeyDown += (_, e) => { if (e.Key != Avalonia.Input.Key.Escape) return; if (_themePage) { _themePage = false; Build(); e.Handled = true; } else if (_tab != 0) { _tab = 0; Build(); e.Handled = true; } };
        Title = "DrKozol"; Width = 1060; Height = 760; MinWidth = 760; MinHeight = 540;
        WindowDecorations = Avalonia.Controls.WindowDecorations.None;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = DesktopFont; FontSize = 13;
        try { Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://DrKozol/Assets/v2rayN.ico"))); } catch { }
        Content = _shell;
        _client.Changed += OnClientChanged;
        _client.Notice += OnNotice;
        _timer.Tick += (_, _) => Refresh();
        Opened += async (_, _) =>
        {
            if (smoke) { await SmokeTests.Run(this); return; }
            try
            {
                await Task.Run(AppManager.Instance.InitComponents);
                await _client.Initialize();
                await PrepareFlags();
                _ready = true; Build(); _timer.Start();
            }
            catch (Exception)
            {
                _ready = true; _notice = "startup"; Build();
            }
        };
        Closing += OnClosing;
    }

    private void OnClientChanged() => Dispatcher.UIThread.Post(async () =>
    {
        if (!_ready || _closing) return;
        await PrepareFlags(); Refresh();
    });
    private void OnNotice(string code) => Dispatcher.UIThread.Post(() =>
    {
        if (_closing) return;
        _notice = code;
        _noticeUntil = code is "saved" or "updated" or "upToDate" or "updateAvailable" ? DateTimeOffset.UtcNow.AddSeconds(5) : DateTimeOffset.MaxValue;
        if (_status is not null) { _status.Text = NoticeText(); _status.IsVisible = _status.Text.Length > 0; }
    });
    private string NoticeText() => _notice switch
    {
        "clear" => "",
        "settingsError" => T("تنظیمات ذخیره نشد؛ آدرس‌ها، پورت و مشخصات پراکسی را بررسی کنید", "Settings could not be saved. Check URLs, port and proxy credentials."),
        "upToDate" => T("نسخهٔ جدیدتری برای این دستگاه موجود نیست", "No newer version is available for this device"),
        "updateAvailable" => T("نسخهٔ جدید آمادهٔ دریافت است", "A new version is available"),
        "updateError" => T("بررسی نسخهٔ جدید انجام نشد", "Could not check for updates"),
        "updateDownloading" => T("در حال دانلود نسخهٔ جدید…", "Downloading update…"),
        "updateDownloaded" => T("نسخهٔ جدید دانلود شد و صحت فایل تأیید شد", "Update downloaded and verified"),
        "saved" => T("ذخیره شد", "Saved"),
        "updated" => T("اشتراک‌ها به‌روز شدند", "Subscriptions updated"),
        "balancerUnavailable" => T("حداقل دو سرور سازگار نیاز است", "At least two compatible servers are required"),
        "admin" => T("برای TUN دسترسی شبکه لازم است. در تنظیمات «فعال‌کردن دسترسی TUN» را بزنید.", "TUN needs network permission. Use Enable TUN permission in Settings."),
        "url" => T("لینک اشتراک معتبر با HTTPS وارد کنید.", "Enter a valid HTTPS subscription URL."),
        "connectError" => T("اتصال برقرار نشد. سرور دیگری انتخاب کنید یا اشتراک را به‌روزرسانی کنید.", "Connection failed. Select another server or refresh your subscription."),
        "importError" => T("دریافت اطلاعات انجام نشد. لینک یا تصویر QR را بررسی کنید.", "Import failed. Check your link or QR image."),
        "expired" => T("جهت تمدید اقدام نمایید", "Please renew your subscription"),
        "probeError" => T("آزمون اتصال پاسخ نداد. دوباره تست کنید یا سرور دیگری انتخاب کنید.", "The connection test did not respond. Retry or choose another server."),
        "testFirst" => T("ابتدا Real Delay یا TCP Ping را اجرا کنید.", "Run Real Delay or TCP Ping first."),
        "tcpNotApplicable" => T("این کانفیگ از UDP استفاده می‌کند؛ برای آن Real Delay را اجرا کنید.", "This configuration uses UDP. Use Real Delay for this server."),
        "startup" => T("راه‌اندازی کامل نشد. برنامه را ببندید و دوباره باز کنید.", "Startup did not complete. Close and reopen the app."),
        "operation" => T("عملیات کامل نشد. دوباره امتحان کنید.", "The operation did not complete. Try again."),
        "tunPermission" => T("دسترسی TUN فعال شد؛ اکنون می‌توانید حالت TUN را انتخاب کنید.", "TUN permission is enabled. You can now select TUN mode."),
        _ => ""
    };
    private async Task PrepareFlags()
    {
        if (_flagTask is { IsCompleted: false }) { await _flagTask; return; }
        var codes = _client.AccountProfiles.Values.SelectMany(x => x).Select(ServerPresentation.CountryCode)
            .Where(x => x is not null && !_flags.ContainsKey(x)).Distinct().ToArray();
        if (codes.Length == 0) return;
        _flagTask = LoadFlags(codes!); await _flagTask;
    }
    private async Task LoadFlags(string?[] codes)
    {
        var created = await Task.Run(() =>
        {
            var items = new Dictionary<string, Bitmap>();
            foreach (var code in codes)
            {
                if (code is null || _lifetime.IsCancellationRequested) break;
                if (ServerPresentation.FlagPng(code) is not { } bytes) continue;
                try { using var stream = new MemoryStream(bytes); items[code] = new Bitmap(stream); } catch { }
            }
            return items;
        });
        foreach (var item in created)
            if (_closing) item.Value.Dispose(); else _flags[item.Key] = item.Value;
    }
    private async void OnClosing(object? sender, WindowClosingEventArgs e)
    {
        if (_closing) return;
        e.Cancel = true; _closing = true; _timer.Stop(); _lifetime.Cancel();
        _client.Changed -= OnClientChanged; _client.Notice -= OnNotice;
        try { await _client.Close(); if (_flagTask is not null) await _flagTask; if (_permissionTask is not null) await _permissionTask; }
        catch (OperationCanceledException) { }
        finally
        {
            foreach (var flag in _flags.Values) flag.Dispose();
            _lifetime.Dispose(); Close();
        }
    }

    private void Refresh()
    {
        if (!_ready || _closing) return;
        if (DateTimeOffset.UtcNow >= _noticeUntil) { _noticeUntil = DateTimeOffset.MaxValue; _notice = ""; if (_status is not null) _status.Text = ""; }
        var signature = $"{_client.Config.SubIndexId}|{_client.Config.IndexId}|{_client.Busy}|{_client.IsTesting}|{_client.HomeChoice}|{_client.Connected}|{_client.UseTun}|{Farsi}|{_category}|{Palette.Accent}|{Palette.IsDark}|" +
            string.Join(';', _client.Accounts.Select(a => $"{a.Id}:{a.Remarks}:{a.CheckedAt}:{a.Announcement}:{AccountUsage.Locked(a)}:{_client.AccountProfiles.GetValueOrDefault(a.Id)?.Count}"));
        if (signature != _lastStructure) { _lastStructure = signature; Build(); }
        Metrics();
    }
    private void Metrics()
    {
        if (_state is not null) _state.Text = _client.IsConnecting ? T("در حال اتصال…", "Connecting…") : _client.IsTesting ? T("در حال پینگ…", "Testing connection…") : _client.Connected ? T("متصل", "Connected") : _client.Busy ? T("در حال انجام عملیات…", "Working…") : T("برای اتصال کلیک کنید", "Tap to connect");
        if (_speed is not null) _speed.Text = _client.Connected ? $"↓ {AccountUsage.Bytes(_client.Down)}/s     ↑ {AccountUsage.Bytes(_client.Up)}/s     ·     {AccountUsage.Bytes(_client.SessionBytes)}" : "";
        if (_speed is not null && _client.Config.DrKozol.MemoryMonitor) _speed.Text += "  ·  " + T("حافظه: ", "Memory: ") + AccountUsage.Bytes(_client.MemoryBytes);
        foreach (var account in _client.Accounts)
            if (_quotas.TryGetValue(account.Id, out var quota)) { quota.Label.Text = Quota(account); quota.Bar.Value = UsedPercent(account); }
        foreach (var profile in _client.AccountProfiles.Values.SelectMany(x => x))
            if (_delays.TryGetValue(profile.IndexId, out var label))
            {
                label.Text = Delay(profile); label.Foreground = DelayColor(profile);
            }
        if (_activeDelay is not null && _client.SelectedProfile is { } active) { _activeDelay.Text = Delay(active); _activeDelay.Foreground = DelayColor(active); }
    }
    private string Quota(SubItem a)
    {
        if (AccountUsage.Locked(a)) return T("جهت تمدید اقدام نمایید", "Please renew your subscription");
        var days = AccountUsage.Days(a)?.ToString() ?? (a.ExpiresAt == 0 ? "∞" : "—");
        long? used = a.UploadBytes is { } up && a.DownloadBytes is { } down ? (long)Math.Min(long.MaxValue, (decimal)up + down) : null;
        return $"{days} {T("روز باقی‌مانده", "days left")}   |   {AccountUsage.Bytes(used)} / {(a.TotalBytes == 0 ? "∞" : AccountUsage.Bytes(a.TotalBytes))}   ·   {T("باقی‌مانده", "remaining")} {AccountUsage.Bytes(AccountUsage.Remaining(a))}";
    }
    private static double UsedPercent(SubItem a) => a.TotalBytes is > 0 && AccountUsage.Remaining(a) is { } left ? Math.Clamp(100d * (1d - (double)left / a.TotalBytes.Value), 0, 100) : 0;
    private IEnumerable<ProfileItem> Filtered(string accountId) => _client.OrderProfiles(
        (_client.AccountProfiles.GetValueOrDefault(accountId) ?? []).Where(p =>
            (_tab != 1 || _category == "All" || ServerPresentation.Protocol(p).Equals(_category, StringComparison.OrdinalIgnoreCase))
            && (_tab != 1 || string.IsNullOrWhiteSpace(_search) || p.Remarks.Contains(_search, StringComparison.OrdinalIgnoreCase))), accountId);

    private string Delay(ProfileItem p)
    {
        if (ServerPresentation.IsAccountInfo(p)) return "n/a";
        string Value(IReadOnlyDictionary<string, int> values) => values.TryGetValue(p.IndexId, out var ms) ? ms > 0 ? $"{ms} ms" : "n/a" : "—";
        return $"● {Value(_client.Real)}  ·  TCP {Value(_client.Tcp)}";
    }
    private IBrush DelayColor(ProfileItem p) => _client.Real.TryGetValue(p.IndexId, out var delay)
        ? Brush(delay <= 0 ? "#E76770" : delay < 150 ? "#44CE85" : delay < 500 ? "#E0BD51" : "#E76770") : Muted;

    private void Build()
    {
        _input = _url?.Text ?? _input; _url = null;
        _offsets[_tab] = _scroll.Offset.Y;
        _scroll.Content = null; _shell.Children.Clear(); _shell.RowDefinitions.Clear(); _shell.ColumnDefinitions.Clear();
        _delays.Clear(); _quotas.Clear(); _state = null; _speed = null; _activeDelay = null;
        Background = Bg; RequestedThemeVariant = _client.Config.UiItem.CurrentTheme == "Auto" ? ThemeVariant.Default : Palette.IsDark ? ThemeVariant.Dark : ThemeVariant.Light;
        _shell.RowDefinitions.Add(new RowDefinition(38, GridUnitType.Pixel));
        _shell.RowDefinitions.Add(new RowDefinition(1, GridUnitType.Star));
        _shell.ColumnDefinitions.Add(new ColumnDefinition(_compactSidebar ? 62 : 196, GridUnitType.Pixel));
        _shell.ColumnDefinitions.Add(new ColumnDefinition(1, GridUnitType.Star));
        var top = TitleBar(); Grid.SetColumnSpan(top, 2); _shell.Children.Add(top);
        var sidebar = Sidebar(); Grid.SetRow(sidebar, 1); _shell.Children.Add(sidebar);
        _content = new StackPanel { Margin = new Thickness(20, 10, 20, 18), Spacing = 8 };
        _scroll.Content = _content; Grid.SetColumn(_scroll, 1); Grid.SetRow(_scroll, 1); _shell.Children.Add(_scroll);
        if (!_ready) _content.Children.Add(Label(T("در حال راه‌اندازی…", "Starting…"), 18));
        else if (_client.Accounts.Count == 0 && _tab != 2 && !_client.Config.DrKozol.OnboardingCompleted) Onboarding();
        else if (_tab == 2) { if (_themePage) ThemeGallery(); else Settings(); }
        else
        {
            if (_tab == 0) Home(); else Servers();
            if (_adding) AddForm();
            if (_tab == 0) _content.Children.Add(Section(T("اشتراک فعلی", "CURRENT SUBSCRIPTION")));
            if (_tab == 1) foreach (var account in _client.Accounts) _content.Children.Add(Provider(account));
        }
        _status = Label(NoticeText(), 12); _status.Foreground = Brush("#E76770"); _status.IsVisible = _status.Text?.Length > 0; _content.Children.Add(_status);
        Metrics(); Dispatcher.UIThread.Post(() => _scroll.Offset = new Vector(0, _offsets[_tab]), DispatcherPriority.Loaded);
    }
    private Control TitleBar()
    {
        var grid = new Grid { Background = Brush("#050505"), ColumnDefinitions = new ColumnDefinitions("*,40,40,40") };
        var title = Row(Label("DrKozol", 12, true, Accent));
        title.Margin = new Thickness(10, 0, 0, 0); title.VerticalAlignment = VerticalAlignment.Center;
        grid.PointerPressed += (_, e) =>
        {
            for (var node = e.Source as Control; node is not null && node != grid; node = node.Parent as Control)
                if (node is Button) return;
            if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
            if (e.ClickCount == 2) ToggleMaximize(); else BeginMoveDrag(e);
        };
        grid.Children.Add(title);
        foreach (var (name, col, action) in new (string, int, Action)[] { ("minimize", 1, () => WindowState = WindowState.Minimized), ("maximize", 2, ToggleMaximize), ("close", 3, Close) })
        {
            var button = IconButton(name, name switch { "close" => T("بستن", "Close"), "minimize" => T("کوچک‌کردن", "Minimize"), _ => T("بزرگ‌کردن", "Maximize") }, () => { action(); return Task.CompletedTask; });
            button.Background = Brushes.Transparent; button.BorderThickness = new Thickness(0); button.Foreground = Brush("#909090"); button.Width = 40; button.Height = 38; button.CornerRadius = new CornerRadius(0);
            button.Content = DesktopIcons.Draw(name, Brush("#909090"), 12); Grid.SetColumn(button, col); grid.Children.Add(button);
        }
        return grid;
    }
    private void ToggleMaximize() => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private Control Sidebar()
    {
        var grid = new Grid { Background = Brush(Palette.Sidebar), RowDefinitions = new RowDefinitions("72,*,30") };
        var brand = new Grid { ColumnDefinitions = new ColumnDefinitions("42,*,28"), Margin = new Thickness(18, 16, 10, 12) };
        var logo = new Border { Width = 34, Height = 34, Background = Accent, CornerRadius = new CornerRadius(8), Child = new TextBlock { Text = "DK", FontWeight = FontWeight.Bold, FontSize = 13, Foreground = Brush(Palette.Background), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center } };
        brand.Children.Add(logo);
        if (!_compactSidebar)
        {
            var word = Label("DrKozol", 18, true); word.VerticalAlignment = VerticalAlignment.Center; Grid.SetColumn(word, 1); brand.Children.Add(word);
            var collapse = IconButton("chevron", T("جمع‌کردن نوار کناری", "Collapse sidebar"), () => { _compactSidebar = true; Build(); return Task.CompletedTask; });
            collapse.Content = DesktopIcons.Draw("chevron", Muted, 12); collapse.RenderTransform = new ScaleTransform(-1, 1); collapse.Width = 26; collapse.Height = 28; Grid.SetColumn(collapse, 2); brand.Children.Add(collapse);
        }
        else logo.PointerPressed += (_, _) => { _compactSidebar = false; Build(); };
        grid.Children.Add(brand);
        var nav = new StackPanel { Spacing = 4, Margin = new Thickness(8, 16, 8, 0) }; Grid.SetRow(nav, 1); grid.Children.Add(nav);
        var names = new[] { T("خانه", "Home"), T("سرورها", "Servers"), T("تنظیمات", "Settings") };
        var icons = new[] { "home", "servers", "settings" };
        for (var i = 0; i < 3; i++)
        {
            var index = i; var selected = i == _tab; var color = selected ? Accent : Muted;
            var button = ActionButton("", () => { _offsets[_tab] = _scroll.Offset.Y; _tab = index; _scroll.Offset = new Vector(0, _offsets[index]); Build(); return Task.CompletedTask; });
            button.Content = _compactSidebar ? DesktopIcons.Draw(icons[i], color) : Row(DesktopIcons.Draw(icons[i], color), Label(names[i], 14, selected, color));
            button.Height = 42; button.Background = selected ? Tint : Brushes.Transparent; button.BorderThickness = new Thickness(0); button.Padding = new Thickness(12, 8); button.HorizontalContentAlignment = HorizontalAlignment.Left;
            AutomationProperties.SetName(button, names[i]); nav.Children.Add(button);
        }

        return grid;
    }

    private StackPanel ManualGroupForm()
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(Label(T("گروه شخصی (اختیاری)", "Personal group (optional)"), 12, false, Muted));
        var name = new TextBox { Foreground = Ink, Background = Panel, MinHeight = 32 }; panel.Children.Add(name);
        panel.Children.Add(ActionButton(T("ساخت گروه", "Create group"), async () => { await _client.CreateGroup(name.Text ?? ""); _adding = true; Build(); }, !_client.Busy && !_client.Connected));
        return panel;
    }
    private void Home()
    {
        var modes = Row(ModeButton("TUN", true), ModeButton("System Proxy", false)); modes.HorizontalAlignment = HorizontalAlignment.Center; modes.Margin = new Thickness(0, 20, 0, 0); _content.Children.Add(modes);
        var power = ActionButton("", () => { if (_client.IsTesting) { _client.CancelTests(); return Task.CompletedTask; } return _client.Connect(); }, _client.IsTesting || !_client.Busy && (_client.Connected || _client.Account is { } a && !AccountUsage.Locked(a)));
        power.Width = 132; power.Height = 132; power.CornerRadius = new CornerRadius(66); power.Background = Panel;
        power.BorderBrush = _client.Connected ? Accent : Line; power.HorizontalAlignment = HorizontalAlignment.Center;
        power.Content = DesktopIcons.Draw(_client.Connected ? "shield" : "power", _client.Connected ? Accent : Muted, 42); power.Margin = new Thickness(0, 54, 0, 20);
        if (_client.Connected) power.Content = new Border { Child = DesktopIcons.Draw("shield", Accent, 42), Width = 128, Height = 128,
            CornerRadius = new CornerRadius(64), Background = Panel,
            BoxShadow = new BoxShadows(new BoxShadow { IsInset = true, Blur = 14, OffsetY = 5, Color = Colors.Black }) };
        AutomationProperties.SetName(power, _client.IsTesting ? T("توقف تست", "Stop tests") : _client.Connected ? T("قطع اتصال", "Disconnect") : T("اتصال", "Connect")); _content.Children.Add(PowerRing(power));
        var choices = Row(HomeAction(T("اتصال به بهترین", "Connect to best"), _client.ConnectBest, _client.HomeChoice == "best"),
            HomeAction(T("بالانسر", "Balancer"), _client.ConnectBalancer, _client.HomeChoice == "balancer"));
        choices.HorizontalAlignment = HorizontalAlignment.Center; _content.Children.Add(choices);
        _state = Label("", 14, false, Muted); _state.HorizontalAlignment = HorizontalAlignment.Center; _content.Children.Add(_state);
        _speed = Label("", 11, false, Muted); _speed.HorizontalAlignment = HorizontalAlignment.Center; _speed.MinHeight = 16; _speed.Margin = new Thickness(0, 0, 0, 6); _content.Children.Add(_speed);
        if (_client.Connected)
        {
            var current = Row(HomeAction(T("پینگ اتصال فعلی", "Ping current connection"), () => _client.ProbeCurrent(true), _client.IsTesting));
            current.HorizontalAlignment = HorizontalAlignment.Center; _content.Children.Add(current);
            _activeDelay = Label("", 11, false, Muted); _activeDelay.HorizontalAlignment = HorizontalAlignment.Center; _content.Children.Add(_activeDelay);
        }
        if (_client.Connected && _client.AccountProfiles.Values.SelectMany(p => p).FirstOrDefault(p => p.IndexId == _client.Config.IndexId) is { } active)
            _content.Children.Add(Card(ServerRow(active)));
        if (!string.IsNullOrWhiteSpace(_client.Account?.Announcement))
        {
            var message = Label(_client.Account.Announcement, 11, false, Muted); message.FlowDirection = Farsi ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            _content.Children.Add(new Border { Child = message, Padding = new Thickness(10, 7), Background = Tint, BorderBrush = Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(7) });
        }
        _content.Children.Add(AddStrip());
        if (_client.IsTesting) _content.Children.Add(ActionButton(T("لغو آزمون اتصال", "Cancel connection tests"), () => { _client.CancelTests(); return Task.CompletedTask; }));
    }
    private Button ModeButton(string label, bool tun)
    {
        var button = ActionButton(label, () => _client.SetTun(tun), !_client.Busy && !_client.Connected);
        button.Height = 23; button.MinHeight = 23; button.FontSize = 11; button.Padding = new Thickness(12, 1); button.CornerRadius = new CornerRadius(5);
        button.Foreground = _client.UseTun == tun ? Accent : Muted; button.BorderBrush = _client.UseTun == tun ? Accent : Muted;
        button.Background = _client.UseTun == tun ? Tint : Brushes.Transparent; return button;
    }
    private void Servers()
    {
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var titles = new StackPanel { Spacing = 1 }; titles.Children.Add(Label(T("سرورها", "Servers"), 23, true));
        titles.Children.Add(Label($"{_client.AccountProfiles.Values.Sum(x => x.Count)} {T("سرور", "servers")} · {_client.Accounts.Count} {T("اشتراک", "subscriptions")}", 11, false, Muted)); header.Children.Add(titles);
        var actions = Row(IconButton("up", T("بستن همه", "Collapse all"), () => { _expanded.Clear(); Build(); return Task.CompletedTask; }), PingMenu(Filtered(_client.Config.SubIndexId)), IconButton("plus", T("افزودن", "Add"), () => { _adding = !_adding; Build(); return Task.CompletedTask; }));
        Grid.SetColumn(actions, 1); header.Children.Add(actions); _content.Children.Add(header);
        var search = new TextBox { FontFamily = DesktopFont, Text = _search, PlaceholderText = T("جست‌وجوی سرورها…", "Search servers…"), Height = 43, CornerRadius = new CornerRadius(13), Background = Panel, Foreground = Ink, BorderBrush = Line, Padding = new Thickness(14, 8), MaxLength = 200 };
        AutomationProperties.SetName(search, T("جست‌وجوی سرورها", "Search servers"));
        search.TextChanged += (_, _) => { _search = search.Text ?? ""; RebuildProviderList(); }; _content.Children.Add(search);
        var categories = _client.AccountProfiles.Values.SelectMany(x => x).Select(ServerPresentation.Protocol).Distinct().Order().Prepend("All");
        var chips = new WrapPanel { Orientation = Orientation.Horizontal };
        foreach (var category in categories)
        {
            var button = ActionButton(category == "All" ? T("همه", "All") : category, () => { _category = category; Build(); return Task.CompletedTask; });
            button.Background = category == _category ? Accent : Panel; button.Foreground = category == _category ? Bg : Muted; button.Height = 28; button.FontSize = 11; button.Padding = new Thickness(12, 3); button.Margin = new Thickness(0, 3, 6, 5); chips.Children.Add(button);
        }
        _content.Children.Add(chips);
        if (_client.IsTesting) _content.Children.Add(ActionButton(T("لغو آزمون اتصال", "Cancel connection tests"), () => { _client.CancelTests(); return Task.CompletedTask; }));
    }
    private void RebuildProviderList()
    {
        var existing = _content.Children.OfType<Border>().Where(x => x.Tag is string id && _client.Accounts.Any(a => a.Id == id)).ToArray();
        foreach (var border in existing) _content.Children.Remove(border);
        _delays.Clear(); _quotas.Clear();
        var statusIndex = _status is null ? _content.Children.Count : _content.Children.IndexOf(_status);
        if (statusIndex < 0) statusIndex = _content.Children.Count;
        foreach (var account in _client.Accounts.Where(a => _tab == 1 || a.ShowOnHome != false)) _content.Children.Insert(statusIndex++, Provider(account));
        Metrics();
    }
    private Control AddStrip()
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*,42"), ColumnSpacing = 8 };
        var add = ActionButton("", () => { _adding = !_adding; Build(); return Task.CompletedTask; }); add.Content = Row(DesktopIcons.Draw("plus", Accent, 17), Label(T("افزودن", "Add"), 13, true, Accent)); add.Background = Tint;
        var paste = ActionButton("", async () => { var value = await (Clipboard?.TryGetTextAsync() ?? Task.FromResult<string?>(null)) ?? ""; if (AccountUsage.ValidUrl(value)) await _client.Import(value); else await _client.ImportConfigs(value); }, !_client.Busy && !_client.Connected); paste.Content = Row(DesktopIcons.Draw("paste", Muted, 15), Label(T("چسباندن", "Paste"), 13, false, Muted));
        var qr = IconButton("qr", T("واردکردن QR از تصویر", "Import QR image"), ImportQr, !_client.Busy && !_client.Connected); qr.Height = 41;
        add.Height = 41; paste.Height = 41; add.HorizontalContentAlignment = HorizontalAlignment.Center; paste.HorizontalContentAlignment = HorizontalAlignment.Center;
        grid.Children.Add(add); Grid.SetColumn(paste, 1); grid.Children.Add(paste); Grid.SetColumn(qr, 2); grid.Children.Add(qr); return grid;
    }
    private async Task ImportQr()
    {
        var selected = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions { Title = T("انتخاب تصویر QR", "Choose a QR image"), AllowMultiple = false, FileTypeFilter = [new FilePickerFileType("QR image") { Patterns = ["*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp"] }] });
        if (selected.FirstOrDefault()?.TryGetLocalPath() is { } path) await _client.ImportQrImage(path);
    }
    private void Onboarding()
    {
        var panel = new StackPanel { MaxWidth = 520, Spacing = 18, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(10, 80, 10, 10) };
        panel.Children.Add(Label("DrKozol", 38, true, Accent)); panel.Children.Add(Label(T("لینک اشتراک خود را وارد کنید", "Enter your subscription link"), 22, true));
        panel.Children.Add(Label(T("لینک را از پنل یا پشتیبانی دریافت کنید. سرورها، حجم و روزهای باقی‌مانده خودکار نمایش داده می‌شوند.", "Get the link from your panel or provider. Servers, remaining quota and days appear automatically."), 13, false, Muted));
        panel.Children.Add(UrlBox()); panel.Children.Add(ActionButton(T("اسکن QR از تصویر", "Scan QR image"), ImportQr, !_client.Busy)); panel.Children.Add(ActionButton(T("ادامه", "Continue"), () => _client.Import(_url?.Text ?? ""), !_client.Busy));
        panel.Children.Add(ActionButton(T("ادامه بدون لینک ساب", "Continue without subscription"), async () => {
            var settings = JsonUtils.DeepCopy(_client.Config.DrKozol); settings.OnboardingCompleted = true;
            if (await _client.SavePreferences(settings)) Build();
        }));
        panel.Children.Add(ManualGroupForm());
        panel.Children.Add(Row(ActionButton("فارسی", () => SetLanguage("fa")), ActionButton("English", () => SetLanguage("en")))); _content.Children.Add(panel);
    }
    private void AddForm()
    {
        var body = new StackPanel { Spacing = 10 }; body.Children.Add(Label(T("افزودن اشتراک یا کانفیگ", "Add a subscription or configuration"), 15, true)); body.Children.Add(UrlBox());
        body.Children.Add(Row(ActionButton(T("دریافت اشتراک", "Load subscription"), () => _client.Import(_url?.Text ?? ""), !_client.Busy && !_client.Connected), ActionButton(T("واردکردن کانفیگ", "Import configuration"), () => _client.ImportConfigs(_url?.Text ?? ""), !_client.Busy && !_client.Connected)));
        body.Children.Add(ManualGroupForm());
        _content.Children.Add(Card(body));
    }
    private TextBox UrlBox()
    {
        _url = new TextBox { FontFamily = DesktopFont, Text = _input, PlaceholderText = "https://…", AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 60, MaxHeight = 140, MaxLength = 65536, Background = Panel, Foreground = Ink, BorderBrush = Accent, Padding = new Thickness(12), CornerRadius = new CornerRadius(8), FlowDirection = FlowDirection.LeftToRight };
        AutomationProperties.SetName(_url, T("لینک اشتراک یا کانفیگ", "Subscription link or configuration")); return _url;
    }

    private Border Provider(SubItem account)
    {
        var body = new StackPanel { Spacing = 6 }; var expanded = _expanded.Contains(account.Id);
        var head = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var name = ActionButton("", async () => { if (!_expanded.Add(account.Id)) _expanded.Remove(account.Id); Build(); }, !_client.Busy && (!_client.Connected || account.Id == _client.Config.SubIndexId));
        var nameBody = new StackPanel { Spacing = 0 }; nameBody.Children.Add(Label(account.Remarks, 12, true, Muted)); nameBody.Children.Add(Label($"{account.AutoUpdateInterval / 60} h", 9, false, Muted));
        name.Content = Row(DesktopIcons.Draw(expanded ? "down" : "chevron", Muted, 10), nameBody); name.Background = Brushes.Transparent; name.BorderThickness = new Thickness(0); name.Padding = new Thickness(3, 0); name.HorizontalContentAlignment = HorizontalAlignment.Left; head.Children.Add(name);
        var stamp = account.CheckedAt is > 0 and < 253402300799 ? DateTimeOffset.FromUnixTimeSeconds(account.CheckedAt.Value).LocalDateTime.ToString("dd.MM.yy, HH:mm") : "—";
        var count = _client.AccountProfiles.GetValueOrDefault(account.Id)?.Count ?? 0;
        var tools = Row(Label(stamp, 9, false, Muted), Label(count.ToString(), 10, true, Accent), IconButton("refresh", T("به‌روزرسانی اشتراک", "Refresh subscription"), () => _client.RefreshSubscriptions(account.Id), !_client.Busy && !account.LocalGroup), PingMenu(Filtered(account.Id), account.Id), IconButton("more", T("گزینه‌های اشتراک", "Provider actions"), () => OpenProviderMenu(account)));
        Grid.SetColumn(tools, 1); head.Children.Add(tools); body.Children.Add(head);
        var quota = Label(Quota(account), 10, false, Muted); quota.FlowDirection = FlowDirection.LeftToRight; body.Children.Add(quota);
        var progress = new ProgressBar { Minimum = 0, Maximum = 100, Value = UsedPercent(account), Height = 3, Foreground = Accent, Background = Tint }; body.Children.Add(progress); _quotas[account.Id] = (quota, progress);
        if (_tab == 1 && !string.IsNullOrWhiteSpace(account.Announcement)) body.Children.Add(Label(account.Announcement, 11, false, Muted));
        if (expanded)
            foreach (var profile in Filtered(account.Id)) body.Children.Add(ServerRow(profile));
        var border = Card(body, 14); border.Tag = account.Id; return border;
    }
    private Button PingMenu(IEnumerable<ProfileItem> profiles, string? accountId = null)
    {
        var button = IconButton("ping", T("آزمون اتصال", "Connection tests"), () => Task.CompletedTask, !_client.Busy && !_client.Connected);
        var menu = new ContextMenu();
        foreach (var (label, action) in new (string, Func<Task>)[] { ("Real Delay", () => _client.Probe(true, profiles)), ("TCP Ping", () => _client.Probe(false, profiles)), (T("انتخاب بهترین سرور", "Select best server"), () => _client.Best(profiles)) })
        {
            var item = new MenuItem { Header = label }; item.Click += async (_, _) => await Run(async () =>
            {
                if (accountId is not null && accountId != _client.Config.SubIndexId) await _client.SelectAccount(accountId);
                await action();
            }); menu.Items.Add(item);
        }
        button.Click += (_, _) => menu.Open(button); return button;
    }

    private Task OpenProviderMenu(SubItem account)
    {
        var menu = new ContextMenu();
        var edit = new MenuItem { Header = T("تنظیمات اشتراک", "Provider settings") }; edit.Click += async (_, _) => await EditProvider(account); menu.Items.Add(edit);
        if (!account.LocalGroup) {
            var copy = new MenuItem { Header = T("کپی ساب لینک", "Copy subscription URL") }; copy.Click += async (_, _) => { if (Clipboard is not null) await Clipboard.SetTextAsync(account.Url); }; menu.Items.Add(copy);
            var qr = new MenuItem { Header = T("نمایش QR", "Show QR") }; qr.Click += async (_, _) => {
                var bytes = await Task.Run(() => QRCodeUtils.GenQRCode(account.Url)); if (bytes is null) return;
                using var bitmap = new Bitmap(new System.IO.MemoryStream(bytes));
                await new Window { Title = "DrKozol", Width = 360, Height = 400, Background = Panel,
                    Content = new Image { Source = bitmap, Margin = new Thickness(20), Stretch = Stretch.Uniform } }.ShowDialog(this);
            }; menu.Items.Add(qr);
        }
        var remove = new MenuItem { Header = T("حذف اشتراک", "Delete provider"), IsEnabled = !_client.Busy && !_client.Connected };
        remove.Click += async (_, _) => {
            var dialog = new Window { Title = "DrKozol", Width = 400, Height = 180, Background = Panel };
            dialog.Content = new StackPanel { Margin = new Thickness(20), Spacing = 12, Children = {
                Label(T("اشتراک و سرورهای آن حذف شوند؟", "Delete this provider and its servers?")),
                ActionButton(T("حذف", "Delete"), async () => { await _client.DeleteProvider(account.Id); dialog.Close(); }),
                ActionButton(T("انصراف", "Cancel"), () => { dialog.Close(); return Task.CompletedTask; })
            } }; await dialog.ShowDialog(this);
        }; menu.Items.Add(remove);
        menu.Open(this); return Task.CompletedTask;
    }
    private async Task EditProvider(SubItem original)
    {
        var account = JsonUtils.DeepCopy(original);
        var dialog = new Window { Title = T("تنظیمات اشتراک", "Provider settings"), Width = 500, Height = 600, Background = Panel };
        var body = new StackPanel { Margin = new Thickness(20), Spacing = 12 };
        var name = new TextBox { Text = account.Remarks, Foreground = Ink, Background = Panel }; body.Children.Add(name);
        var visible = new CheckBox { Content = T("نمایش در Home", "Show on Home"), IsChecked = account.ShowOnHome != false, Foreground = Ink }; body.Children.Add(visible);
        var custom = new CheckBox { Content = T("فاصلهٔ آپدیت اختصاصی", "Custom update interval"), IsChecked = account.CustomUpdateInterval, Foreground = Ink }; body.Children.Add(custom);
        var interval = new TextBox { Text = (account.AutoUpdateInterval > 0 ? account.AutoUpdateInterval : _client.Config.DrKozol.UpdateMinutes).ToString(), Foreground = Ink, Background = Panel }; body.Children.Add(Label(T("فاصلهٔ آپدیت به دقیقه", "Update interval in minutes"))); body.Children.Add(interval);
        var dns = new TextBox { Text = account.TunnelDns ?? "", Foreground = Ink, Background = Panel }; body.Children.Add(Label("DNS")); body.Children.Add(dns);
        var ping = new TextBox { Text = account.ProbeUrl ?? "", Foreground = Ink, Background = Panel }; body.Children.Add(Label(T("آدرس آزمون (خالی = عمومی)", "Test URL (empty = global)"))); body.Children.Add(ping);
        var order = new ComboBox { ItemsSource = new[] { "ping", "name", "default" }, SelectedItem = account.SortOrder ?? "ping", Foreground = Ink, Background = Panel }; body.Children.Add(order);
        body.Children.Add(ActionButton(T("ذخیره", "Save"), async () => {
            if (!int.TryParse(interval.Text, out var minutes)) return;
            account.Remarks = name.Text ?? ""; account.DisplayNameOverride = name.Text; account.ShowOnHome = visible.IsChecked == true;
            account.CustomUpdateInterval = custom.IsChecked == true; account.AutoUpdateInterval = minutes; account.TunnelDns = dns.Text; account.ProbeUrl = ping.Text; account.SortOrder = order.SelectedItem as string;
            if (await _client.SaveProvider(account)) { dialog.Close(); Build(); }
        }));
        dialog.Content = new ScrollViewer { Content = body }; await dialog.ShowDialog(this);
    }
    private Control ServerRow(ProfileItem profile)
    {
        var selected = profile.IndexId == _client.Config.IndexId;
        var accountInfo = ServerPresentation.IsAccountInfo(profile);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("38,*,116"), ColumnSpacing = 8, FlowDirection = FlowDirection.LeftToRight };
        Control flag = DesktopIcons.Draw("globe", Muted, 15);
        if (ServerPresentation.CountryCode(profile) is { } code && _flags.TryGetValue(code, out var bitmap)) flag = new Image { Source = bitmap, Width = 23, Height = 17, Stretch = Stretch.Uniform };
        var flagBox = new Border { Child = flag, Width = 30, Height = 30, Background = Bg, CornerRadius = new CornerRadius(9), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }; grid.Children.Add(flagBox);
        var name = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        var title = Label(ServerPresentation.DisplayName(profile), 13); title.FlowDirection = FlowDirection.LeftToRight;
        title.TextAlignment = TextAlignment.Left; title.TextWrapping = TextWrapping.NoWrap; title.TextTrimming = TextTrimming.CharacterEllipsis; name.Children.Add(title);
        var details = Row(Label(ServerPresentation.Protocol(profile), 9, false, Brush(ServerPresentation.ProtocolColor(profile, Palette.IsDark))), Label("· " + ServerPresentation.Transport(profile), 9, false, Muted));
        details.FlowDirection = FlowDirection.LeftToRight;
        var security = ServerPresentation.Security(profile); if (!string.IsNullOrWhiteSpace(security)) details.Children.Add(Label("· " + security, 9, false, Brush("#38A46A")));
        foreach (var label in details.Children.OfType<TextBlock>()) { label.TextWrapping = TextWrapping.NoWrap; label.FlowDirection = FlowDirection.LeftToRight; }
        name.Children.Add(details); Grid.SetColumn(name, 1); grid.Children.Add(name);
        var latency = Label(Delay(profile), 9, false, DelayColor(profile)); latency.VerticalAlignment = VerticalAlignment.Center;
        latency.FlowDirection = FlowDirection.LeftToRight; latency.TextAlignment = TextAlignment.Right; latency.TextWrapping = TextWrapping.NoWrap;
        latency.TextTrimming = TextTrimming.CharacterEllipsis; _delays[profile.IndexId] = latency; Grid.SetColumn(latency, 2); grid.Children.Add(latency);
        var row = ActionButton("", async () => { await _client.SelectAccount(profile.Subid); await _client.SelectServer(profile.IndexId); }, !accountInfo && !_client.Busy && !_client.Connected);
        row.Content = grid; row.HorizontalContentAlignment = HorizontalAlignment.Stretch; row.Height = 46; row.Padding = new Thickness(7, 4); row.CornerRadius = new CornerRadius(13); row.Background = Brushes.Transparent; row.BorderThickness = new Thickness(0);
        AutomationProperties.SetName(row, profile.Remarks + (selected ? T("، انتخاب‌شده", ", selected") : ""));
        if (accountInfo) ToolTip.SetTip(row, T("اطلاعات حجم و اعتبار اشتراک", "Subscription quota and expiry"));
        var container = new Grid { ColumnDefinitions = new ColumnDefinitions("*,28"), FlowDirection = FlowDirection.LeftToRight }; container.Children.Add(row);
        var allowed = !accountInfo && !_client.Busy && (!_client.Connected || selected);
        var more = IconButton("more", T("گزینه‌های سرور", "Server actions"), () => Task.CompletedTask, allowed); more.Background = Brushes.Transparent; more.BorderThickness = new Thickness(0); more.Width = 25;
        var menu = new ContextMenu();
        foreach (var (label, real) in new[] { ("Real Delay", true), ("TCP Ping", false) })
        {
            var item = new MenuItem { Header = label, IsEnabled = allowed };
            item.Click += async (_, _) => await Run(async () =>
            {
                if (_client.Connected) await _client.ProbeCurrent(real);
                else { await _client.SelectAccount(profile.Subid); await _client.Probe(real, [profile]); }
            }); menu.Items.Add(item);
        }
        more.Click += (_, _) => menu.Open(more); Grid.SetColumn(more, 1); container.Children.Add(more);
        return new Border { Child = container, CornerRadius = new CornerRadius(13), Background = selected ? Tint : Brushes.Transparent, BorderBrush = selected ? Accent : Brushes.Transparent, BorderThickness = new Thickness(1) };
    }
    private void Settings()
    {
        _content.Children.Add(Label(T("تنظیمات", "Settings"), 23, true)); _content.Children.Add(Label("DrKozol", 11, false, Muted));
        _content.Children.Add(Section(T("ظاهر برنامه", "APPEARANCE")));
        var appearance = new StackPanel { Spacing = 12 };
        appearance.Children.Add(ActionButton(T("انتخاب تم", "Choose theme"), () => { _themePage = true; Build(); return Task.CompletedTask; }));
        appearance.Children.Add(Separator());
        appearance.Children.Add(SettingRow("globe", T("زبان", "Language"), Farsi ? "فارسی" : "English", Row(ActionButton("فارسی", () => SetLanguage("fa")), ActionButton("English", () => SetLanguage("en")))));
        _content.Children.Add(Card(appearance));
        _content.Children.Add(Section(T("اتصال", "CONNECTION")));
        var connection = new StackPanel { Spacing = 12 }; connection.Children.Add(SettingRow("wifi", T("حالت اتصال", "Connection mode"), T("TUN تمام برنامه‌ها را از تونل عبور می‌دهد", "TUN routes applications through the tunnel"), Row(ModeButton("TUN", true), ModeButton("System Proxy", false))));
        connection.Children.Add(Label(T("System Proxy تنظیم GNOME را به کار می‌گیرد. در محیط‌های دسکتاپ دیگر از TUN یا پراکسی دستی برنامه‌ها استفاده کنید.", "System Proxy uses GNOME settings. On other desktops, use TUN or configure application proxies manually."), 12, false, Muted));
        if (OperatingSystem.IsLinux())
        {
            connection.Children.Add(Label(T("برای ساخت آداپتور مجازی، فقط موتور TUN به دسترسی شبکه نیاز دارد. برنامه با حساب عادی شما اجرا می‌شود.", "Only the TUN engine needs network permission to create a virtual adapter. The app runs as your normal user."), 12, false, Muted));
            connection.Children.Add(ActionButton(T("فعال‌کردن دسترسی TUN", "Enable TUN permission"), EnableTunPermission, !_client.Busy && !_client.Connected && _permissionTask is not { IsCompleted: false }));
        }
        _content.Children.Add(Card(connection));
        _content.Children.Add(ClientPreferencesCard());
        _content.Children.Add(Card(new StackPanel { Spacing = 8, Children = {
            ActionButton(T("پشتیبان‌گیری", "Back up subscriptions"), () => LocalBackup(false), !_client.Busy && !_client.Connected),
            ActionButton(T("بازیابی پشتیبان", "Restore backup"), () => LocalBackup(true), !_client.Busy && !_client.Connected),
            ActionButton(T("گزارش‌های برنامه", "Application logs"), () => { Process.Start(new ProcessStartInfo(Utils.GetLogPath()) { UseShellExecute = true }); return Task.CompletedTask; })
        } }));
        _content.Children.Add(Section(T("برنامه", "APPLICATION")));
        var version = GetType().Assembly.GetName().Version;
        _content.Children.Add(Card(SettingRow("info", T("دربارهٔ DrKozol", "About DrKozol"),
            T("DrKozol، کلاینت اشتراک شما برای اتصال امن.", "DrKozol is your subscription client for secure connections."),
            Label(version is null ? "" : $"v{version.Major}.{version.Minor}.{version.Build}"))));
    }


    private async Task LocalBackup(bool restore)
    {
        if (restore)
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions { AllowMultiple = false,
                FileTypeFilter = [new FilePickerFileType("DrKozol backup") { Patterns = ["*.json"] }] });
            if (files.FirstOrDefault()?.TryGetLocalPath() is { } path) await _client.Backup(path, true);
        }
        else
        {
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions { SuggestedFileName = "DrKozol-backup.json",
                FileTypeChoices = [new FilePickerFileType("DrKozol backup") { Patterns = ["*.json"] }] });
            if (file?.TryGetLocalPath() is { } path) await _client.Backup(path, false);
        }
    }

    private void ThemeGallery()
    {
        _content.Children.Add(ActionButton(T("بازگشت به تنظیمات", "Back to Settings"), () => { _themePage = false; Build(); return Task.CompletedTask; }));
        _content.Children.Add(HomeAction(T("خودکار • مطابق سیستم", "Automatic · system appearance"), async () => {
            _client.Config.UiItem.CurrentTheme = "Auto"; await ConfigHandler.SaveConfig(_client.Config); Build();
        }, _client.Config.UiItem.CurrentTheme == "Auto"));
        foreach (var dark in new[] { false, true })
        {
        _content.Children.Add(Section(dark ? T("تم‌های تیره", "Dark themes") : T("تم‌های روشن", "Light themes")));
        var gallery = new WrapPanel { Orientation = Orientation.Horizontal };
        foreach (var color in DesktopAppearance.Palettes)
        {
            if (color == "OLED" && !dark) continue;
            var palette = DesktopAppearance.For(dark ? "Dark" : "Light", color);
            var body = new StackPanel { Spacing = 8 };
            var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            foreach (var sample in new[] { palette.Accent, palette.Panel, palette.Background })
                dots.Children.Add(new Avalonia.Controls.Shapes.Ellipse { Width = 18, Height = 18, Fill = Brush(sample), Stroke = Line, StrokeThickness = 1 });
            body.Children.Add(dots); body.Children.Add(Label(color + (dark ? T(" تاریک", " Dark") : T(" روشن", " Light")), 13));
            var button = ActionButton("", async () => { _client.Config.UiItem.ColorPrimaryName = color; await SetTheme(dark ? "Dark" : "Light"); });
            button.Content = body; button.Width = 190; button.Height = 86; button.Margin = new Thickness(4);
            button.Background = Brush(palette.Panel); button.BorderBrush = string.Equals(_client.Config.UiItem.ColorPrimaryName ?? "Ocean", color, StringComparison.OrdinalIgnoreCase) && Palette.IsDark == dark ? Accent : Line;
            AutomationProperties.SetName(button, color + (dark ? " Dark" : " Light")); gallery.Children.Add(button);
        }
        _content.Children.Add(gallery);
        }
    }
    private Control PowerRing(Button button)
    {
        var wrapper = new Grid { Width = 156, Height = 156, Margin = button.Margin, HorizontalAlignment = HorizontalAlignment.Center };
        button.Margin = new Thickness(0); wrapper.Children.Add(button);
        var ring = new Avalonia.Controls.Shapes.Ellipse { Width = 152, Height = 152, Stroke = _client.Connected ? Accent : Line,
            StrokeThickness = 3, IsHitTestVisible = false, RenderTransformOrigin = new RelativePoint(.5, .5, RelativeUnit.Relative) };
        if (_client.IsTesting || _client.IsConnecting)
        {
            ring.Stroke = Accent; ring.StrokeDashArray = new Avalonia.Collections.AvaloniaList<double> { 28, 15 };
            var rotation = new RotateTransform(); ring.RenderTransform = rotation;
            var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
            timer.Tick += (_, _) => rotation.Angle = (rotation.Angle + 360d / 35) % 360;
            ring.AttachedToVisualTree += (_, _) => timer.Start();
            ring.DetachedFromVisualTree += (_, _) => timer.Stop();
        }
        wrapper.Children.Add(ring); return wrapper;
    }

    private Button HomeAction(string label, Func<Task> action, bool selected)
    {
        var button = ActionButton(label, action, !_client.Busy);
        button.Background = Brushes.Transparent; button.BorderThickness = new Thickness(0); button.Padding = new Thickness(0);
        button.Content = new Border { Child = Label(label, 12, false, Accent), CornerRadius = new CornerRadius(24),
            Background = Panel, Padding = new Thickness(18, 12), BorderBrush = Line, BorderThickness = new Thickness(1),
            BoxShadow = selected ? new BoxShadows(new BoxShadow { IsInset = true, Blur = 10, OffsetX = 2, OffsetY = 3, Color = Colors.Black }) : default };
        AutomationProperties.SetHelpText(button, selected ? T("انتخاب شده", "Selected") : "");
        return button;
    }
    private Border ClientPreferencesCard()
    {
        var body = new StackPanel { Spacing = 8 };
        var draft = JsonUtils.DeepCopy(_client.Config.DrKozol);
        var ipPreference = new ComboBox { ItemsSource = new[] { "auto", "ipv4", "ipv6" }, SelectedItem = draft.IpPreference, Foreground = Ink, Background = Panel };
        body.Children.Add(SettingRow("wifi", T("نوع IP ترجیحی", "Preferred IP family"), T("با امکان استفاده از خانوادهٔ دیگر", "Retains fallback to the other address family"), ipPreference));
        foreach (var option in DesktopPreferences.Options)
        {
            var toggle = new CheckBox { Content = T(option.Farsi, option.English), IsChecked = option.Read(_client.Config.DrKozol), Foreground = Ink, MinHeight = 36 };
            toggle.Click += (_, _) => option.Write(draft, toggle.IsChecked == true);
            body.Children.Add(toggle);
        }
        var interval = new TextBox { Text = _client.Config.DrKozol.UpdateMinutes.ToString(), Foreground = Ink, Background = Panel };
        body.Children.Add(SettingRow("wifi", T("فاصلهٔ آپدیت (دقیقه)", "Update interval (minutes)"), "", interval));
        var ping = new TextBox { Text = _client.Config.DrKozol.PingUrl, Foreground = Ink, Background = Panel, MinWidth = 220 };
        body.Children.Add(SettingRow("ping", T("آدرس آزمون اتصال", "Connection test URL"), "", ping));

        TextBox Field(string label, string value) {
            var field = new TextBox { Text = value, Foreground = Ink, Background = Panel, MinWidth = 180 };
            body.Children.Add(SettingRow("wifi", label, "", field)); return field;
        }
        var agent = Field("User-Agent", _client.Config.DrKozol.UserAgent);
        var dns = Field(T("DNS تونل", "Tunnel DNS"), _client.Config.DrKozol.TunnelDns);
        var username = Field(T("نام کاربری پراکسی", "Proxy username"), _client.Config.DrKozol.ProxyUser);
        var sharingPort = Field(T("پورت اشتراک پراکسی", "Shared proxy port"), _client.Config.DrKozol.SharingPort.ToString());
        var applications = Field(T("نام فایل برنامه‌ها (با کاما جدا شود)", "Application filenames (comma separated)"), _client.Config.DrKozol.Applications);
        var applicationMode = new ComboBox { ItemsSource = new[] { "all", "include", "exclude" }, SelectedItem = _client.Config.DrKozol.ApplicationMode, Foreground = Ink, Background = Panel };
        body.Children.Add(SettingRow("wifi", T("مسیریابی برنامه‌ها در TUN", "Application routing in TUN"), T("همه، فقط انتخاب‌شده، یا به‌جز انتخاب‌شده", "All, only selected, or except selected"), applicationMode));
        var password = Field(T("رمز پراکسی", "Proxy password"), _client.Config.DrKozol.ProxyPassword); password.PasswordChar = '●';
        body.Children.Add(ActionButton(T("ذخیره", "Save"), async () => {
            if (!int.TryParse(interval.Text, out var minutes)) return;
            var next = JsonUtils.DeepCopy(draft); next.UpdateMinutes = minutes; next.PingUrl = ping.Text ?? "";
            next.IpPreference = ipPreference.SelectedItem as string ?? "auto";
            if (!int.TryParse(sharingPort.Text, out var port)) return;
            next.UserAgent = agent.Text ?? ""; next.TunnelDns = dns.Text ?? "";
            next.ProxyUser = username.Text ?? ""; next.ProxyPassword = password.Text ?? ""; next.SharingPort = port;
            next.ApplicationMode = applicationMode.SelectedItem as string ?? "all"; next.Applications = applications.Text ?? "";
            await _client.SavePreferences(next);
        }));
        body.Children.Add(ActionButton(T("بازخورد", "Feedback"), () => { Process.Start(new ProcessStartInfo("https://github.com/SpecterLogic/Drkozol/issues") { UseShellExecute = true }); return Task.CompletedTask; }));
        body.Children.Add(ActionButton(T("بررسی نسخهٔ جدید", "Check for updates"), async () => {
            if (await _client.CheckForUpdate() is not { } url) return;
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions { SuggestedFileName = Path.GetFileName(new Uri(url).AbsolutePath), ShowOverwritePrompt = true });
            if (file?.TryGetLocalPath() is { } path) await _client.DownloadUpdate(url, path);
        }));
        return Card(body);
    }
    private Task EnableTunPermission()
    {
        if (_permissionTask is { IsCompleted: false }) return _permissionTask;
        _permissionTask = EnableTunPermissionCore(); Build(); return _permissionTask;
    }
    private async Task EnableTunPermissionCore()
    {
        var installer = Path.Combine(AppContext.BaseDirectory, "install-tun.sh");
        using var child = await Task.Run(() =>
        {
            if (!File.Exists(installer)) throw new FileNotFoundException();
            var start = new System.Diagnostics.ProcessStartInfo("pkexec") { UseShellExecute = false, WorkingDirectory = AppContext.BaseDirectory };
            start.ArgumentList.Add("/bin/sh"); start.ArgumentList.Add(installer); start.ArgumentList.Add(AppContext.BaseDirectory);
            return System.Diagnostics.Process.Start(start) ?? throw new IOException();
        }, _lifetime.Token);
        try { await child.WaitForExitAsync(_lifetime.Token); }
        catch (OperationCanceledException) { try { if (!child.HasExited) child.Kill(true); } catch { } throw; }
        _notice = child.ExitCode == 0 ? "tunPermission" : "admin"; Build();
    }
    private async Task SetTheme(string mode) { _client.Config.UiItem.CurrentTheme = mode; await ConfigHandler.SaveConfig(_client.Config); Build(); }
    private async Task SetLanguage(string code) { _client.Config.UiItem.CurrentLanguage = code; await ConfigHandler.SaveConfig(_client.Config); Build(); }
    private Control SettingRow(string icon, string name, string subtitle, Control action)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("38,*,Auto"), ColumnSpacing = 10 };
        grid.Children.Add(new Border { Child = DesktopIcons.Draw(icon, Accent, 18), Width = 34, Height = 34, Background = Tint, CornerRadius = new CornerRadius(8) });
        var text = new StackPanel { Spacing = 2 }; text.Children.Add(Label(name, 14)); text.Children.Add(Label(subtitle, 11, false, Muted)); Grid.SetColumn(text, 1); grid.Children.Add(text); Grid.SetColumn(action, 2); action.VerticalAlignment = VerticalAlignment.Center; grid.Children.Add(action); return grid;
    }
    private Control Separator() => new Border { Height = 1, Background = Line, Margin = new Thickness(0, 2) };
    private TextBlock Section(string text) => new() { Text = text, FontSize = 11, Foreground = Muted, Margin = new Thickness(0, 2, 0, 0) };
    private TextBlock Label(string? text, double size = 13, bool bold = false, IBrush? color = null) => new() { FontFamily = DesktopFont, Text = text ?? "", FontSize = size, FontWeight = bold ? FontWeight.SemiBold : FontWeight.Normal, Foreground = color ?? Ink, TextWrapping = TextWrapping.Wrap };
    private static StackPanel Row(params Control[] children)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in children) { child.VerticalAlignment = VerticalAlignment.Center; row.Children.Add(child); } return row;
    }
    private Border Card(Control child, double radius = 14) => new() { Child = child, Background = Panel, BorderBrush = Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(radius), Padding = new Thickness(14, 12) };
    private Button IconButton(string icon, string description, Func<Task> action, bool enabled = true)
    {
        var button = ActionButton("", action, enabled); button.Content = DesktopIcons.Draw(icon, Muted, 16); button.Width = 30; button.Height = 30; button.MinHeight = 28; button.Padding = new Thickness(6); button.CornerRadius = new CornerRadius(15);
        AutomationProperties.SetName(button, description); ToolTip.SetTip(button, description); return button;
    }
    private Button ActionButton(string text, Func<Task> action, bool enabled = true)
    {
        var button = new Button { FontFamily = DesktopFont, Content = text, Foreground = Ink, Background = Panel, BorderBrush = Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(9), Padding = new Thickness(12, 7), MinHeight = 28, IsEnabled = enabled, FontSize = 12, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Center, Opacity = enabled ? 1 : .5 };
        // Semi's default content alignment is replaced for this shell. Template bindings
        // allow navigation, action strips and server grids to fill their assigned columns.
        button.Template = new FuncControlTemplate<Button>((control, scope) =>
        {
            var presenter = new ContentPresenter { Name = "PART_ContentPresenter", VerticalAlignment = VerticalAlignment.Center };
            presenter.Bind(ContentPresenter.ContentProperty, new Binding(nameof(Button.Content)) { Source = control });
            presenter.Bind(ContentPresenter.HorizontalAlignmentProperty, new Binding(nameof(Button.HorizontalContentAlignment)) { Source = control });
            presenter.Bind(ContentPresenter.PaddingProperty, new Binding(nameof(Button.Padding)) { Source = control });
            scope.Register("PART_ContentPresenter", presenter);
            var border = new Border { Child = presenter };
            border.Bind(Border.BackgroundProperty, new Binding(nameof(Button.Background)) { Source = control });
            border.Bind(Border.BorderBrushProperty, new Binding(nameof(Button.BorderBrush)) { Source = control });
            border.Bind(Border.BorderThicknessProperty, new Binding(nameof(Button.BorderThickness)) { Source = control });
            border.Bind(Border.CornerRadiusProperty, new Binding(nameof(Button.CornerRadius)) { Source = control });
            var pressed = control.GetObservable(Button.IsPressedProperty).Subscribe(value =>
                border.BoxShadow = value ? new BoxShadows(new BoxShadow { IsInset = true, Blur = 10, OffsetY = 3, Color = Colors.Black }) : default);
            border.DetachedFromVisualTree += (_, _) => pressed.Dispose();
            return border;
        });
        button.PointerEntered += (_, _) => { if (button.IsEnabled) button.Opacity = .82; };
        button.PointerExited += (_, _) => button.Opacity = button.IsEnabled ? 1 : .5;
        button.Click += async (_, _) => await Run(action); return button;
    }
    private async Task Run(Func<Task> action)
    {
        try { await action(); }
        catch (OperationCanceledException) when (_closing) { }
        catch (Exception) { _notice = "operation"; if (_status is not null) { _status.Text = NoticeText(); _status.IsVisible = true; } }
    }
}
