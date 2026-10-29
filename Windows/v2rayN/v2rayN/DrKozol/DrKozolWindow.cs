using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using ServiceLib.DrKozol;

namespace v2rayN.DrKozol;

public sealed partial class DrKozolWindow : Window
{
    private readonly Grid _shell = new();
    private readonly StackPanel _content = new() { Margin = new Thickness(20, 10, 20, 12) };
    private readonly StackPanel _providers = new();
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(2, 8, 2, 2), FontSize = 12 };
    private readonly HashSet<string> _expanded = [];
    private readonly Dictionary<string, (TextBlock Real, TextBlock Tcp, System.Windows.Shapes.Ellipse Dot)> _delays = [];
    private readonly Dictionary<string, (TextBlock Days, TextBlock Bytes, ProgressBar Bar)> _quotas = [];
    private readonly DrKozolController _client = new();
    private readonly CancellationTokenSource _visualLifetime = new();
    private Task? _flagTask;
    private DateTimeOffset _noticeUntil = DateTimeOffset.MaxValue;
    private bool _themePage;
    private int _tab;
    private bool _adding;
    private bool _sidebarCollapsed;
    private bool _ready;
    private bool _closing;
    private bool _closePending;
    private bool _elevating;
    private bool _dark = true;
    private string _search = "";
    private string _input = "";
    private string _category = "All";
    private string _lastStructure = "";
    private TextBox? _url;
    private TextBlock? _speed;
    private TextBlock? _state;
    private TextBlock? _probeResult;
    private ScrollViewer? _scroll;
    private readonly Dictionary<int, double> _scrollOffsets = [];
    private bool Farsi => _client.Config.UiItem.CurrentLanguage == "fa";
    private DesktopPalette Palette => DesktopAppearance.For((_client.Config.UiItem.CurrentTheme == "Auto" ? _client.SystemDarkTheme : _dark) ? "Dark" : "Light", _client.Config.UiItem.ColorPrimaryName);
    private static SolidColorBrush Brush(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    private SolidColorBrush Accent => Brush(Palette.Accent);
    private SolidColorBrush Ink => Brush(Palette.Text);
    private SolidColorBrush Muted => Brush(Palette.Muted);
    private SolidColorBrush Panel => Brush(Palette.Panel);
    private SolidColorBrush Stroke => Brush(Palette.Border);
    private SolidColorBrush AccentTint => new(Color.FromArgb(_dark ? (byte)25 : (byte)22, Accent.Color.R, Accent.Color.G, Accent.Color.B));
    private string Version => GetType().Assembly.GetName().Version is { } version ? $"v{version.Major}.{version.Minor}.{version.Build}" : "";
    private string T(string fa, string en) => Farsi ? fa : en;
    private bool Idle => !_client.Busy && !_client.Connected;

    internal DrKozolController TestClient => _client;
    internal async Task InitializeForSmoke()
    {
        await _client.Initialize();
        await (_flagTask = DrKozolVisuals.PrepareFlags(_visualLifetime.Token));
        _ready = true; Build();
    }
    internal void RenderForSmoke(int tab, bool dark)
    {
        _tab = tab; _themePage = false; _dark = dark; _client.Config.UiItem.CurrentTheme = dark ? "Dark" : "Light";
        if (_client.Account is { } account) _expanded.Add(account.Id);
        Build();
    }
    internal void RenderThemeForSmoke(string color, bool dark)
    {
        _client.Config.UiItem.ColorPrimaryName = color; _dark = dark; _tab = 2; _themePage = true;
        _client.Config.UiItem.CurrentTheme = dark ? "Dark" : "Light"; Build();
    }

    public DrKozolWindow()
    {
        _dark = _client.Config.UiItem.CurrentTheme != "Light";
        Activated += (_, _) => _client.Foreground = true;
        Deactivated += (_, _) => _client.Foreground = false;
        PreviewKeyDown += (_, e) => { if (e.Key != Key.Escape) return; if (_themePage) { _themePage = false; Build(); e.Handled = true; } else if (_tab != 0) { ChangeTab(0); e.Handled = true; } };
        Title = "DrKozol"; Width = 1000; Height = 740; MinWidth = 760; MinHeight = 560;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        WindowStyle = WindowStyle.None; ResizeMode = ResizeMode.CanResize;
        FontFamily = new FontFamily("Segoe UI"); FontSize = 13;
        UseLayoutRounding = true; SnapsToDevicePixels = true;
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            CaptionHeight = 36, ResizeBorderThickness = new Thickness(6), GlassFrameThickness = new Thickness(0),
            CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false
        });
        try { Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Resources/v2rayN.ico")); } catch (Exception) { }
        Content = _shell;
        _client.Changed += () => Dispatcher.BeginInvoke(Refresh);
        _client.Notice += code => Dispatcher.BeginInvoke(() => {
            if (code == "admin") { _ = RestartElevated(); return; }
            _noticeUntil = code is "saved" or "updated" or "upToDate" or "updateAvailable" ? DateTimeOffset.UtcNow.AddSeconds(5) : DateTimeOffset.MaxValue;
            _status.Text = code switch
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
            "admin" => T("برای TUN، از تنظیمات برنامه را با دسترسی Administrator اجرا کنید.", "TUN requires Administrator. Restart as administrator in Settings."),
            "url" => T("لینک ساب معتبر با https وارد کنید.", "Enter a valid HTTPS subscription URL."),
            "importError" => T("دریافت ساب ناموفق بود. لینک و اینترنت را بررسی کنید.", "Subscription could not be loaded. Check the URL and network."),
            "connectError" => T("اتصال برقرار نشد. سرور دیگری را امتحان کنید.", "Connection failed. Try another server."),
            "testFirst" => T("ابتدا تست تأخیر را اجرا کنید.", "Run a delay test first."),
            "probeLocked" => T("اشتراک این سرور غیرفعال یا تمام شده است؛ ابتدا آن را به‌روزرسانی کنید.", "This server's subscription is disabled or exhausted. Refresh it before testing."),
            _ => T("تست کامل نشد. دوباره امتحان کنید.", "The test could not be completed. Try again.")
        }; });
        Loaded += async (_, _) =>
        {
            if (_ready) return;
            try
            {
                await _client.Initialize();
                await (_flagTask = DrKozolVisuals.PrepareFlags(_visualLifetime.Token));
                if (_closing) return;
                _ready = true; Build();
            }
            catch (OperationCanceledException) when (_visualLifetime.IsCancellationRequested) { }
            catch (Exception) { _status.Text = T("راه‌اندازی ناموفق بود. پوشهٔ برنامه باید قابل نوشتن باشد.", "Startup failed. Check that the application folder is writable."); }
        };
        Closing += async (_, e) =>
        {
            if (_closing) return;
            e.Cancel = true;
            if (_closePending) return;
            _closePending = true; IsEnabled = false; _visualLifetime.Cancel();
            try { await _client.Close(); }
            catch (Exception error) { Logging.SaveLog("DrKozol window shutdown", new Exception(error.GetType().Name)); }
            finally
            {
                _closing = true;
                // Close may complete synchronously when diagnostics already stopped the client.
                // Defer the final close until this Closing notification has returned to WPF.
                if (!Dispatcher.HasShutdownStarted)
                    try { await Dispatcher.InvokeAsync(() => { if (IsVisible) Close(); }, System.Windows.Threading.DispatcherPriority.Background); }
                    catch (TaskCanceledException) when (Dispatcher.HasShutdownStarted) { }
            }
        };
        StateChanged += (_, _) => BuildTitleBar();
        Build();
    }

    private Button Button(string text, Func<Task> action, bool enabled = true, double height = 36)
    {
        var button = new Button
        {
            Style = new Style(typeof(Button)), Content = text, MinHeight = height,
            Margin = new Thickness(2), Padding = new Thickness(12, 6, 12, 6),
            Background = Panel, Foreground = Ink, BorderBrush = Stroke,
            BorderThickness = new Thickness(1), FontSize = 12,
            IsEnabled = enabled, Cursor = Cursors.Hand,
            HorizontalContentAlignment = HorizontalAlignment.Center,
            FocusVisualStyle = FocusStyle()
        };
        var template = new ControlTemplate(typeof(Button));
        var border = new FrameworkElementFactory(typeof(Border)); border.Name = "surface";
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(9));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
        border.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty));
        border.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Control.BorderThicknessProperty));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(ContentPresenter.HorizontalAlignmentProperty, new TemplateBindingExtension(Control.HorizontalContentAlignmentProperty));
        presenter.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
        presenter.SetValue(ContentPresenter.MarginProperty, new TemplateBindingExtension(Control.PaddingProperty));
        border.AppendChild(presenter); template.VisualTree = border;
        var hover = new Trigger { Property = IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(Border.BorderBrushProperty, Accent, "surface"));
        var disabled = new Trigger { Property = IsEnabledProperty, Value = false };
        var pressed = new Trigger { Property = ButtonBase.IsPressedProperty, Value = true };
        pressed.Setters.Add(new Setter(Border.BackgroundProperty, AccentTint, "surface"));
        pressed.Setters.Add(new Setter(Border.BorderBrushProperty, Accent, "surface"));
        disabled.Setters.Add(new Setter(OpacityProperty, .38));
        template.Triggers.Add(hover); template.Triggers.Add(pressed); template.Triggers.Add(disabled); button.Template = template;
        AutomationProperties.SetName(button, text);
        button.Click += async (_, _) =>
        {
            try { await action(); }
            catch (Exception) { _status.Text = T("عملیات کامل نشد. دوباره امتحان کنید.", "The operation could not be completed. Try again."); }
        };
        return button;
    }

    private Style FocusStyle()
    {
        var style = new Style(typeof(Control)); var template = new ControlTemplate(typeof(Control));
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.BorderBrushProperty, Accent); border.SetValue(Border.BorderThicknessProperty, new Thickness(2));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(9)); border.SetValue(Border.MarginProperty, new Thickness(-2));
        template.VisualTree = border; style.Setters.Add(new Setter(Control.TemplateProperty, template)); return style;
    }

    private Button IconButton(string name, string accessible, Func<Task> action, bool enabled = true, double size = 30)
    {
        var button = Button("", action, enabled, size); button.Width = size; button.Height = size;
        button.Padding = new Thickness(6); button.Content = DrKozolVisuals.Icon(name, Muted, 14);
        button.ToolTip = accessible; AutomationProperties.SetName(button, accessible); return button;
    }
    private TextBlock Label(string text, double size = 13, Brush? color = null) => new()
    {
        Text = text, Foreground = color ?? Ink, FontSize = size, TextWrapping = TextWrapping.Wrap,
        FlowDirection = Farsi ? FlowDirection.RightToLeft : FlowDirection.LeftToRight,
        VerticalAlignment = VerticalAlignment.Center
    };
    private UIElement Inline(string icon, string text, Brush? color = null)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        row.Children.Add(DrKozolVisuals.Icon(icon, color ?? Muted, 15));
        var label = Label(text, 13, color); label.Margin = new Thickness(10, 0, 0, 0); row.Children.Add(label); return row;
    }
    private StackPanel Row(params UIElement[] children)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in children) row.Children.Add(child); return row;
    }
    private Border Card(UIElement body, Thickness? padding = null) => new()
    {
        Child = body, CornerRadius = new CornerRadius(16), Background = Panel, BorderBrush = Stroke,
        BorderThickness = new Thickness(1), Padding = padding ?? new Thickness(15, 10, 15, 10), Margin = new Thickness(0, 6, 0, 8)
    };

    private readonly Border _titleBar = new();
    private void BuildTitleBar()
    {
        _titleBar.Child = null; _titleBar.Background = Brushes.Black; _titleBar.Height = 36;
        var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var brand = Row(Label("DrKozol", 12, Accent));
        brand.Margin = new Thickness(12, 0, 0, 0); grid.Children.Add(brand);
        var controls = Row(
            IconButton("minimize", T("کوچک کردن", "Minimize"), () => { WindowState = WindowState.Minimized; return Task.CompletedTask; }, size: 44),
            IconButton(WindowState == WindowState.Maximized ? "restore" : "maximize", T("بزرگ کردن یا بازگردانی", "Maximize or restore"), () => { WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized; return Task.CompletedTask; }, size: 44),
            IconButton("close", T("بستن برنامه", "Close application"), () => { Close(); return Task.CompletedTask; }, size: 44));
        foreach (Button control in controls.Children)
        {
            control.Background = Brushes.Black; control.BorderThickness = new Thickness(0);
            control.Height = 34; control.MinHeight = 34; control.Margin = new Thickness(0);
            WindowChrome.SetIsHitTestVisibleInChrome(control, true);
        }
        Grid.SetColumn(controls, 1); grid.Children.Add(controls); _titleBar.Child = grid;
    }

    private void Build()
    {
        if (_url is not null) _input = _url.Text;
        if (_scroll is not null) { _scrollOffsets[_tab] = _scroll.VerticalOffset; _scroll.Content = null; }
        _url = null; _speed = null; _state = null; _probeResult = null; _delays.Clear(); _quotas.Clear();
        Background = Brush(Palette.Background); Foreground = Ink; _status.Foreground = Accent;
        InstallScrollStyle();
        _shell.Children.Clear(); _shell.RowDefinitions.Clear(); _shell.ColumnDefinitions.Clear();
        _shell.RowDefinitions.Add(new RowDefinition { Height = new GridLength(36) });
        _shell.RowDefinitions.Add(new RowDefinition());
        _shell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(_sidebarCollapsed ? 68 : 196) });
        _shell.ColumnDefinitions.Add(new ColumnDefinition());
        BuildTitleBar(); Grid.SetColumnSpan(_titleBar, 2); _shell.Children.Add(_titleBar);
        var sidebar = BuildSidebar(); Grid.SetRow(sidebar, 1); _shell.Children.Add(sidebar);
        _scroll = new ScrollViewer { Content = _content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, CanContentScroll = false };
        Grid.SetRow(_scroll, 1); Grid.SetColumn(_scroll, 1); _shell.Children.Add(_scroll);
        _content.Children.Clear(); _content.FlowDirection = FlowDirection.LeftToRight;
        if (!_ready) { _content.Children.Add(Label(T("در حال آماده‌سازی…", "Starting…"), 14, Muted)); _content.Children.Add(_status); return; }
        // Appearance and connection settings remain available before adding a subscription.
        var onboard = !_client.Config.DrKozol.OnboardingCompleted && _tab != 2 && (_client.Account is null || (_client.Profiles.Count == 0 && !AccountUsage.Locked(_client.Account)));
        if (onboard) BuildOnboarding();
        else if (_tab == 2) { if (_themePage) BuildThemeGallery(); else BuildSettings(); }
        else
        {
            if (_tab == 0) BuildHome(); else BuildServersHeader();
            if (_adding) BuildImport();
            var heading = Label(_tab == 0 ? T("اشتراک فعلی", "CURRENT SUBSCRIPTION") : T("اشتراک‌ها", "SUBSCRIPTIONS"), 10, Muted);
            heading.Margin = new Thickness(3, 7, 0, 0); _content.Children.Add(heading);
            _content.Children.Add(_providers); BuildProviders();
            if (_tab == 1) _content.Children.Add(ActionStrip());
        }
        _content.Children.Add(_status); RefreshMetrics();
        var captured = _scroll;
        Dispatcher.BeginInvoke(() => { if (_scroll == captured) captured.ScrollToVerticalOffset(_scrollOffsets.GetValueOrDefault(_tab)); }, DispatcherPriority.Loaded);
    }

    private DockPanel BuildSidebar()
    {
        var sidebar = new DockPanel { Background = Brush(Palette.Sidebar), LastChildFill = true };

        var nav = new StackPanel { Margin = new Thickness(6, 24, 6, 0) };
        var brand = new Grid { Margin = new Thickness(10, 0, 8, 22) };
        brand.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(34) });
        brand.ColumnDefinitions.Add(new ColumnDefinition()); brand.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var monogram = new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(8), Background = Accent,
            Child = new TextBlock { Text = "DK", Foreground = Brush(Palette.Background), FontSize = 14, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center } };
        brand.Children.Add(monogram);
        if (!_sidebarCollapsed)
        {
            var wordmark = Label("DrKozol", 19); wordmark.FontWeight = FontWeights.SemiBold; wordmark.Margin = new Thickness(10, 0, 0, 0);
            Grid.SetColumn(wordmark, 1); brand.Children.Add(wordmark);
            var collapse = IconButton("chevron", T("جمع کردن منو", "Collapse sidebar"), () => { _sidebarCollapsed = true; Build(); return Task.CompletedTask; }, size: 27);
            collapse.RenderTransform = new RotateTransform(180, 13.5, 13.5); collapse.BorderThickness = new Thickness(0);
            Grid.SetColumn(collapse, 2); brand.Children.Add(collapse);
        }
        else
        {
            var expand = Button("", () => { _sidebarCollapsed = false; Build(); return Task.CompletedTask; });
            brand.Children.Remove(monogram); expand.Content = monogram; expand.Padding = new Thickness(0); expand.Margin = new Thickness(0); expand.BorderThickness = new Thickness(0);
            AutomationProperties.SetName(expand, T("باز کردن منو", "Expand sidebar")); brand.Children.Add(expand);
        }
        nav.Children.Add(brand);
        foreach (var (fa, en, icon, index) in new[] { ("خانه", "Home", "home", 0), ("سرورها", "Servers", "servers", 1), ("تنظیمات", "Settings", "settings", 2) })
        {
            var button = Button("", () => { ChangeTab(index); return Task.CompletedTask; }, height: 42);
            button.Content = _sidebarCollapsed ? DrKozolVisuals.Icon(icon, _tab == index ? Accent : Muted, 16) : Inline(icon, T(fa, en), _tab == index ? Accent : Muted);
            button.Background = _tab == index ? AccentTint : Brush(Palette.Sidebar);
            button.BorderThickness = _tab == index ? new Thickness(3, 0, 0, 0) : new Thickness(0); button.BorderBrush = Accent;
            button.HorizontalContentAlignment = _sidebarCollapsed ? HorizontalAlignment.Center : HorizontalAlignment.Left;
            button.Margin = new Thickness(0, 2, 0, 2); button.Padding = new Thickness(14, 9, 10, 9);
            button.ToolTip = T(fa, en); AutomationProperties.SetName(button, T(fa, en)); nav.Children.Add(button);
        }
        sidebar.Children.Add(nav); return sidebar;
    }
    private void ChangeTab(int tab)
    {
        if (_scroll is not null) _scrollOffsets[_tab] = _scroll.VerticalOffset;
        _tab = tab; _themePage = false; _adding = false; _scroll = null; Build();
    }

    private void BuildOnboarding()
    {
        var body = new StackPanel { MaxWidth = 500, Margin = new Thickness(12, 30, 12, 24) };
        var brand = Label("DrKozol", 32, Accent); brand.FontWeight = FontWeights.Bold; body.Children.Add(brand);
        var title = Label(T("به DrKozol خوش آمدید", "Welcome to DrKozol"), 22); title.Margin = new Thickness(0, 20, 0, 8); body.Children.Add(title);
        var help = Label(T("لینک اشتراک خود را وارد کنید تا سرورها و اطلاعات حساب دریافت شوند.", "Enter your subscription link to load your servers and account details."), 13, Muted); help.Margin = new Thickness(0, 0, 0, 18); body.Children.Add(help);
        body.Children.Add(UrlBox());
        body.Children.Add(Button(T("اسکن QR از تصویر", "Scan QR image"), ImportQrImage));
        body.Children.Add(Button(T("ادامه بدون لینک ساب", "Continue without subscription"), async () => {
            var settings = JsonUtils.DeepCopy(_client.Config.DrKozol); settings.OnboardingCompleted = true;
            if (await _client.SavePreferences(settings)) Build();
        }));
        body.Children.Add(ManualGroupForm());
        var load = Button(T("ورود و دریافت سرورها", "Load subscription"), () => _client.Import(_url?.Text ?? ""), !_client.Busy, 42); load.Background = AccentTint; load.Foreground = Accent; body.Children.Add(load);
        body.Children.Add(Row(Button("فارسی", () => SetLanguage("fa")), Button("English", () => SetLanguage("en"))));
        _content.Children.Add(Card(body));
    }


    private async Task ImportQrImage()
    {
        var picker = new Microsoft.Win32.OpenFileDialog { Title = T("انتخاب تصویر QR", "Choose a QR image"), Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp", CheckFileExists = true };
        if (picker.ShowDialog(this) == true) await _client.ImportQrImage(picker.FileName);
    }
    private StackPanel ManualGroupForm()
    {
        var panel = new StackPanel { Margin = new Thickness(0, 12, 0, 8) };
        panel.Children.Add(Label(T("گروه شخصی (اختیاری)", "Personal group (optional)"), 12, Muted));
        var name = new TextBox { Foreground = Ink, Background = Panel, MinHeight = 32, Margin = new Thickness(0, 6, 0, 6) }; panel.Children.Add(name);
        panel.Children.Add(Button(T("ساخت گروه", "Create group"), async () => { await _client.CreateGroup(name.Text); _adding = true; Build(); }, Idle));
        return panel;
    }
    private void BuildHome()
    {
        var modes = Row(ModeButton("TUN", true), ModeButton("System Proxy", false));
        modes.HorizontalAlignment = HorizontalAlignment.Center; modes.Margin = new Thickness(0, 18, 0, 0); _content.Children.Add(modes);
        var connect = Button("", () => { if (_client.IsTesting) { _client.CancelTests(); return Task.CompletedTask; } return _client.Connect(); }, _client.IsTesting || _client.Connected || !_client.Busy && _client.Account is { } a && !AccountUsage.Locked(a), 130);
        connect.Content = DrKozolVisuals.Icon(_client.Connected ? "shield" : "power", _client.Connected ? Accent : Muted, 39);
        connect.Width = 130; connect.Height = 130; connect.Padding = new Thickness(0); connect.HorizontalAlignment = HorizontalAlignment.Center;
        connect.Margin = new Thickness(0, 63, 0, 28); connect.Background = Panel;
        if (_client.Connected) connect.Background = AccentTint;
        var template = new ControlTemplate(typeof(Button)); var border = new FrameworkElementFactory(typeof(Border)); border.Name = "power";
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(65));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(BackgroundProperty));
        border.SetValue(Border.BorderBrushProperty, _client.Connected ? Accent : Stroke); border.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        var content = new FrameworkElementFactory(typeof(ContentPresenter)); content.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center); content.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(content); template.VisualTree = border;
        var hover = new Trigger { Property = IsMouseOverProperty, Value = true }; hover.Setters.Add(new Setter(Border.BorderBrushProperty, Accent, "power")); template.Triggers.Add(hover);
        var disabled = new Trigger { Property = IsEnabledProperty, Value = false }; disabled.Setters.Add(new Setter(OpacityProperty, .38)); template.Triggers.Add(disabled); connect.Template = template;
        AutomationProperties.SetName(connect, _client.IsTesting ? T("توقف تست", "Stop tests") : _client.Connected ? T("قطع اتصال", "Disconnect") : T("اتصال", "Connect"));
        _content.Children.Add(PowerRing(connect));
        var choices = Row(HomeAction(T("اتصال به بهترین", "Connect to best"), _client.ConnectBest, _client.HomeChoice == "best"),
            HomeAction(T("بالانسر", "Balancer"), _client.ConnectBalancer, _client.HomeChoice == "balancer"));
        choices.HorizontalAlignment = HorizontalAlignment.Center; _content.Children.Add(choices);
        _state = Label("", 15, Muted); _state.HorizontalAlignment = HorizontalAlignment.Center; _state.Margin = new Thickness(0, 6, 0, 0); _content.Children.Add(_state);
        _speed = Label("", 11, Muted); _speed.HorizontalAlignment = HorizontalAlignment.Center; _speed.Margin = new Thickness(0, 8, 0, 10); _content.Children.Add(_speed);
        if (_client.SelectedProfile is { } testProfile && !ServerPresentation.IsAccountInfo(testProfile))
        {
            var current = HomeAction(_client.Connected ? T("پینگ کانفیگ متصل", "Ping connected server") : T("تست کانفیگ انتخاب‌شده", "Test selected server"), () => _client.ProbeCurrent(true), _client.IsTesting);
            current.Tag = "home-probe";
            current.HorizontalAlignment = HorizontalAlignment.Center;
            current.Margin = new Thickness(0, 0, 0, 8); _content.Children.Add(current);
            _probeResult = Label("", 12, Muted);
            _probeResult.HorizontalAlignment = HorizontalAlignment.Center;
            _content.Children.Add(_probeResult);
        }
        if (_client.IsTesting)
        {
            var cancel = Button(T("لغو تست", "Cancel test"), () => { _client.CancelTests(); return Task.CompletedTask; }, height: 28);
            cancel.HorizontalAlignment = HorizontalAlignment.Center; cancel.Foreground = Accent; cancel.Margin = new Thickness(0, 0, 0, 8); _content.Children.Add(cancel);
        }
        if (_client.Connected && _client.AccountProfiles.Values.SelectMany(p => p).FirstOrDefault(p => p.IndexId == _client.Config.IndexId) is { } active
            && _client.Accounts.FirstOrDefault(a => a.Id == active.Subid) is { } account) _content.Children.Add(Card(ServerRow(account, active)));
        if (!string.IsNullOrWhiteSpace(_client.Account?.Announcement))
        {
            var announcement = Label(_client.Account.Announcement, 11, Muted);
            announcement.FlowDirection = Farsi ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            _content.Children.Add(new Border { Child = announcement, Background = AccentTint, BorderBrush = Stroke, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 7, 12, 7), Margin = new Thickness(0, 2, 0, 5) });
        }
        _content.Children.Add(ActionStrip());
    }
    private Button ModeButton(string text, bool tun)
    {
        var button = Button(text, async () => {
            var previous = _client.UseTun;
            await _client.SetTun(tun);
            if (tun && !TunHost.IsAdministrator && !await RestartElevated()) await _client.SetTun(previous);
        }, Idle, 21);
        button.Padding = new Thickness(11, 2, 11, 2); button.FontSize = 11; button.MinHeight = 21;
        button.Foreground = _client.UseTun == tun ? Accent : Muted; button.BorderBrush = _client.UseTun == tun ? Accent : Muted;
        button.Background = _client.UseTun == tun ? AccentTint : Brushes.Transparent; return button;
    }

    private void BuildServersHeader()
    {
        var heading = new Grid(); heading.ColumnDefinitions.Add(new ColumnDefinition()); heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var labels = new StackPanel(); var title = Label(T("سرورها", "Servers"), 23); title.FontWeight = FontWeights.Bold; labels.Children.Add(title);
        labels.Children.Add(Label($"{_client.AccountProfiles.Values.Sum(p => p.Count)} {T("سرور", "servers")} · {_client.Accounts.Count} {T("اشتراک", "subscriptions")}", 11, Muted)); heading.Children.Add(labels);
        var controls = Row(IconButton("collapse", T("بستن همه", "Collapse all"), () => { _expanded.Clear(); BuildProviders(); return Task.CompletedTask; }),
            IconButton("ping", T("تست سرورها", "Test servers"), () => { OpenTestsMenu(); return Task.CompletedTask; }),
            IconButton("plus", T("افزودن اشتراک", "Add subscription"), () => { _adding = !_adding; Build(); return Task.CompletedTask; }, Idle, 36));
        Grid.SetColumn(controls, 1); heading.Children.Add(controls); _content.Children.Add(heading);
        var searchGrid = new Grid { Margin = new Thickness(0, 15, 0, 7) }; searchGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(38) }); searchGrid.ColumnDefinitions.Add(new ColumnDefinition());
        var searchIcon = DrKozolVisuals.Icon("search", Muted, 15); var host = new Border { Child = searchIcon, VerticalAlignment = VerticalAlignment.Center }; searchGrid.Children.Add(host);
        var search = new TextBox { Style = new Style(typeof(TextBox)), Text = _search, Background = Brushes.Transparent, Foreground = Ink, BorderThickness = new Thickness(0), Padding = new Thickness(0, 11, 10, 11), MinHeight = 40, FontSize = 13 };
        search.TextChanged += (_, _) => { _search = search.Text; BuildProviders(); };
        AutomationProperties.SetName(search, T("جستجوی سرورها", "Search servers"));
        var placeholder = Label(T("جستجوی سرورها…", "Search servers…"), 13, Muted); placeholder.IsHitTestVisible = false; placeholder.Visibility = _search.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        search.TextChanged += (_, _) => placeholder.Visibility = search.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        Grid.SetColumn(search, 1); Grid.SetColumn(placeholder, 1); searchGrid.Children.Add(search); searchGrid.Children.Add(placeholder);
        _content.Children.Add(new Border { Child = searchGrid, CornerRadius = new CornerRadius(12), Background = Panel, BorderBrush = Stroke, BorderThickness = new Thickness(1), Margin = new Thickness(0, 12, 0, 8) });
        searchGrid.Margin = new Thickness(0);
        var filters = new WrapPanel();
        var types = new[] { "All" }.Concat(_client.AccountProfiles.Values.SelectMany(p => p).Select(ServerPresentation.Protocol).Distinct()).ToList();
        foreach (var type in types)
        {
            var chip = Button(type == "All" ? T("همه", "All") : type, () => { _category = type; Build(); return Task.CompletedTask; }, height: 27);
            chip.Padding = new Thickness(12, 4, 12, 4); chip.FontSize = 11; chip.Margin = new Thickness(0, 0, 6, 4);
            if (_category == type) { chip.Foreground = Brush(Palette.Background); chip.Background = Accent; chip.BorderBrush = Accent; }
            filters.Children.Add(chip);
        }
        _content.Children.Add(filters);
    }
    private Grid ActionStrip()
    {
        var add = Button("", () => { _adding = !_adding; Build(); return Task.CompletedTask; }, Idle, 40);
        add.Content = Inline("plus", T("افزودن", "Add"), Accent); add.Background = AccentTint; add.BorderThickness = new Thickness(0); AutomationProperties.SetName(add, T("افزودن اشتراک یا کانفیگ", "Add subscription or config"));
        var paste = Button("", async () =>
        {
            var text = Clipboard.ContainsText() ? Clipboard.GetText() : "";
            if (AccountUsage.ValidUrl(text)) await _client.Import(text); else await _client.ImportConfigs(text);
        }, Idle, 40); paste.Content = Inline("paste", T("چسباندن", "Paste")); paste.BorderThickness = new Thickness(0); AutomationProperties.SetName(paste, T("چسباندن", "Paste"));
        var qr = IconButton("qr", T("واردکردن QR از تصویر", "Import QR from an image"), async () =>
        {
            var picker = new Microsoft.Win32.OpenFileDialog { Title = T("انتخاب تصویر QR", "Choose a QR image"), Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp", CheckFileExists = true };
            if (picker.ShowDialog(this) == true) await _client.ImportQrImage(picker.FileName);
        }, Idle, 40); qr.BorderThickness = new Thickness(0);
        var grid = new Grid { Margin = new Thickness(-2, 0, -2, 2) }; grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var buttons = new[] { add, paste, qr };
        for (var i = 0; i < buttons.Length; i++) { buttons[i].HorizontalAlignment = HorizontalAlignment.Stretch; buttons[i].Margin = new Thickness(2, 2, i < 2 ? 6 : 2, 2); Grid.SetColumn(buttons[i], i); grid.Children.Add(buttons[i]); }
        return grid;
    }
    private void BuildImport()
    {
        var body = new StackPanel(); body.Children.Add(Label(T("افزودن اشتراک یا کانفیگ", "Add subscription or configuration"), 16)); body.Children.Add(UrlBox());
        body.Children.Add(Row(Button(T("دریافت ساب", "Load subscription"), () => _client.Import(_url?.Text ?? ""), Idle), Button(T("واردکردن کانفیگ", "Import config"), () => _client.ImportConfigs(_url?.Text ?? ""), Idle)));
        _content.Children.Add(Card(body));
    }
    private List<ProfileItem> Filtered(SubItem account) => _client.OrderProfiles(
        _client.AccountProfiles.GetValueOrDefault(account.Id, []).Where(p =>
            (_tab != 1 || _category == "All" || ServerPresentation.Protocol(p) == _category)
            && (_tab != 1 || string.IsNullOrWhiteSpace(_search) || p.Remarks.Contains(_search, StringComparison.OrdinalIgnoreCase))), account.Id).ToList();

    private void BuildProviders()
    {
        _providers.Children.Clear(); _delays.Clear(); _quotas.Clear();
        foreach (var account in _client.Accounts.Where(a => _tab == 1 || a.ShowOnHome != false))
        {
            var profiles = Filtered(account);
            if (_tab == 1 && (_category != "All" || _search.Length > 0) && profiles.Count == 0) continue;
            var body = new StackPanel(); var heading = new Grid(); heading.ColumnDefinitions.Add(new ColumnDefinition()); heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var titleButton = Button("", () => { if (!_expanded.Add(account.Id)) _expanded.Remove(account.Id); BuildProviders(); return Task.CompletedTask; }, height: 30);
            titleButton.Margin = new Thickness(-6, 0, 0, 0); titleButton.Padding = new Thickness(6, 0, 6, 0); titleButton.BorderThickness = new Thickness(0); titleButton.Background = Brushes.Transparent; titleButton.HorizontalContentAlignment = HorizontalAlignment.Left;
            var title = new StackPanel(); var name = Label(account.Remarks, 13, Muted); name.FontWeight = FontWeights.SemiBold; title.Children.Add(name);
            title.Children.Add(Label(account.AutoUpdateInterval > 0 ? $"{account.AutoUpdateInterval / 60} {T("ساعت", "h")}" : "—", 9, Muted));
            var titleRow = Row(DrKozolVisuals.Icon(_expanded.Contains(account.Id) ? "down" : "chevron", Muted, 11), title); title.Margin = new Thickness(8, 0, 0, 0); titleButton.Content = titleRow;
            AutomationProperties.SetName(titleButton, account.Remarks + (_expanded.Contains(account.Id) ? T("، باز", ", expanded") : T("، بسته", ", collapsed"))); heading.Children.Add(titleButton);
            var controls = Row();
            if (account.CheckedAt is > 0 and < 253402300799)
            {
                var updated = Label(DateTimeOffset.FromUnixTimeSeconds(account.CheckedAt.Value).ToLocalTime().ToString("dd.MM.yy, HH:mm", CultureInfo.InvariantCulture), 9, Muted); updated.FlowDirection = FlowDirection.LeftToRight; updated.Margin = new Thickness(0, 0, 8, 0); controls.Children.Add(updated);
            }
            var count = Label(_client.AccountProfiles.GetValueOrDefault(account.Id, []).Count.ToString(), 10, Accent);
            controls.Children.Add(new Border { Child = count, Background = AccentTint, CornerRadius = new CornerRadius(4), Padding = new Thickness(4, 1, 4, 1), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 4, 0) });
            controls.Children.Add(IconButton("refresh", T("به‌روزرسانی اشتراک", "Refresh subscription"), () => _client.RefreshSubscriptions(account.Id), !_client.Busy && !account.LocalGroup, 29));
            controls.Children.Add(IconButton("ping", T("تست پینگ اشتراک", "Test subscription latency"), () => { OpenAccountTests(account); return Task.CompletedTask; }, size: 29));
            controls.Children.Add(IconButton("more", T("گزینه‌های اشتراک", "Subscription actions"), () => { OpenProviderMenu(account); return Task.CompletedTask; }, size: 29));
            Grid.SetColumn(controls, 1); heading.Children.Add(controls); body.Children.Add(heading);
            var quota = new Grid { Margin = new Thickness(0, 10, 0, 8) };
            quota.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); quota.ColumnDefinitions.Add(new ColumnDefinition()); quota.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var days = Label("", 10, Muted); days.Margin = new Thickness(0, 0, 10, 0); quota.Children.Add(days);
            var progress = new ProgressBar { Height = 3, Maximum = 1, Background = Brush(Palette.Background), Foreground = Accent, VerticalAlignment = VerticalAlignment.Center, BorderThickness = new Thickness(0), Margin = new Thickness(4, 0, 12, 0), Style = new Style(typeof(ProgressBar)) };
            Grid.SetColumn(progress, 1); quota.Children.Add(progress); var bytes = Label("", 9, Muted); bytes.FlowDirection = FlowDirection.LeftToRight; Grid.SetColumn(bytes, 2); quota.Children.Add(bytes);
            _quotas[account.Id] = (days, bytes, progress); body.Children.Add(quota);
            if (_tab == 1 && !string.IsNullOrWhiteSpace(account.Announcement))
            {
                var announcement = Label(account.Announcement, 11, Muted); announcement.FlowDirection = Farsi ? FlowDirection.RightToLeft : FlowDirection.LeftToRight; announcement.Margin = new Thickness(0, 5, 0, 10); body.Children.Add(announcement);
            }
            if (_expanded.Contains(account.Id))
                foreach (var profile in profiles) body.Children.Add(ServerRow(account, profile));
            _providers.Children.Add(Card(body, new Thickness(15, 10, 15, 10)));
        }
        if (_providers.Children.Count == 0) _providers.Children.Add(Label(T("سروری با این جستجو پیدا نشد.", "No servers match your search."), 13, Muted));
        RefreshMetrics();
    }

    private Grid ServerRow(SubItem account, ProfileItem profile)
    {
        var info = ServerPresentation.IsAccountInfo(profile);
        var selected = !info && profile.IndexId == _client.Config.IndexId;
        var grid = new Grid { Height = 48, Margin = new Thickness(-4, 0, -4, 0), FlowDirection = FlowDirection.LeftToRight };
        grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(29) });
        var select = Button("", async () => { if (account.Id != _client.Config.SubIndexId) await _client.SelectAccount(account.Id); await _client.SelectServer(profile.IndexId); }, Idle && !info, 46);
        if (info) select.Opacity = .65;
        select.Margin = new Thickness(0); select.Padding = new Thickness(8, 3, 5, 3); select.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        select.Background = selected ? AccentTint : Brushes.Transparent; select.BorderBrush = selected ? Accent : Brushes.Transparent;
        var row = new Grid { FlowDirection = FlowDirection.LeftToRight }; row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) }); row.ColumnDefinitions.Add(new ColumnDefinition()); row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(110) });
        row.Children.Add(new Border { Child = DrKozolVisuals.Flag(profile, Muted), Width = 30, Height = 30, Background = Brush(Palette.Background), CornerRadius = new CornerRadius(8), HorizontalAlignment = HorizontalAlignment.Left });
        var title = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var name = Label(ServerPresentation.DisplayName(profile), 14); name.FlowDirection = FlowDirection.LeftToRight; name.TextAlignment = TextAlignment.Left; name.TextWrapping = TextWrapping.NoWrap; name.TextTrimming = TextTrimming.CharacterEllipsis; title.Children.Add(name);
        var detail = Label("", 10, Muted); detail.TextWrapping = TextWrapping.NoWrap;
        detail.FlowDirection = FlowDirection.LeftToRight; detail.TextAlignment = TextAlignment.Left; detail.TextTrimming = TextTrimming.CharacterEllipsis;
        detail.Inlines.Add(new Run(ServerPresentation.Protocol(profile)) { Foreground = Brush(ServerPresentation.ProtocolColor(profile, _dark)) });
        var transport = ServerPresentation.Transport(profile); if (transport.Length > 0) detail.Inlines.Add(new Run(" · " + transport));
        var security = ServerPresentation.Security(profile); if (security.Length > 0) detail.Inlines.Add(new Run(" · " + security) { Foreground = _dark ? Brush("#3CD582") : Brush("#147647") });
        title.Children.Add(detail); Grid.SetColumn(title, 1); row.Children.Add(title);
        var metric = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 3, 0), FlowDirection = FlowDirection.LeftToRight };
        var real = Label("", 10, Muted); var dot = new System.Windows.Shapes.Ellipse { Width = 7, Height = 7, Fill = Muted, Margin = new Thickness(0, 0, 4, 0), VerticalAlignment = VerticalAlignment.Center };
        real.FlowDirection = FlowDirection.LeftToRight; real.TextWrapping = TextWrapping.NoWrap;
        metric.Children.Add(Row(dot, real)); var tcp = Label("", 8, Muted); tcp.Margin = new Thickness(11, 1, 0, 0); metric.Children.Add(tcp);
        tcp.FlowDirection = FlowDirection.LeftToRight; tcp.TextWrapping = TextWrapping.NoWrap; tcp.TextTrimming = TextTrimming.CharacterEllipsis;
        if (info) real.Text = "n/a"; else _delays[profile.IndexId] = (real, tcp, dot);
        Grid.SetColumn(metric, 2); row.Children.Add(metric); select.Content = row;
        select.ToolTip = profile.Remarks; AutomationProperties.SetName(select, profile.Remarks + (selected ? T("، انتخاب‌شده", ", selected") : "")); grid.Children.Add(select);
        var more = IconButton("more", T("گزینه‌های سرور", "Server actions"), () => { OpenServerTests(profile, account); return Task.CompletedTask; }, !_client.Busy && !info, 25);
        more.Background = Brushes.Transparent; more.BorderThickness = new Thickness(0); Grid.SetColumn(more, 1); grid.Children.Add(more); return grid;
    }

    private void OpenTestsMenu() { if (_client.Account is { } account) OpenAccountTests(account); }
    private void OpenAccountTests(SubItem account)
    {
        var menu = Menu();
        AddMenu(menu, "Real Delay", async () => { if (account.Id != _client.Config.SubIndexId) await _client.SelectAccount(account.Id); await _client.Probe(true, Filtered(account)); }, Idle);
        AddMenu(menu, "TCP Ping", async () => { if (account.Id != _client.Config.SubIndexId) await _client.SelectAccount(account.Id); await _client.Probe(false, Filtered(account)); }, Idle);
        AddMenu(menu, T("بهترین سرور", "Select best server"), async () => { if (account.Id != _client.Config.SubIndexId) await _client.SelectAccount(account.Id); await _client.Best(Filtered(account)); }, Idle);
        if (_client.Connected && account.Id == _client.Config.SubIndexId) AddMenu(menu, T("پینگ کانفیگ متصل", "Ping connected server"), () => _client.ProbeCurrent(true), !_client.Busy);
        if (_client.IsTesting) AddMenu(menu, T("لغو تست", "Cancel tests"), () => { _client.CancelTests(); return Task.CompletedTask; });
        ShowMenu(menu);
    }

    private void OpenProviderMenu(SubItem account)
    {
        var menu = Menu();
        AddMenu(menu, T("تنظیمات اشتراک", "Provider settings"), () => { EditProvider(account); return Task.CompletedTask; });
        if (!account.LocalGroup) {
            AddMenu(menu, T("کپی ساب لینک", "Copy subscription URL"), () => { Clipboard.SetText(account.Url); return Task.CompletedTask; });
            AddMenu(menu, T("نمایش QR", "Show QR"), async () => {
                var bytes = await Task.Run(() => QRCodeUtils.GenQRCode(account.Url)); if (bytes is null) return;
                var bitmap = new BitmapImage(); using var input = new System.IO.MemoryStream(bytes);
                bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = input; bitmap.EndInit(); bitmap.Freeze();
                new Window { Owner = this, Title = "DrKozol", Width = 360, Height = 400, Background = Panel,
                    Content = new Image { Source = bitmap, Margin = new Thickness(20), Stretch = Stretch.Uniform } }.ShowDialog();
            });
        }
        AddMenu(menu, T("حذف اشتراک", "Delete provider"), async () => {
            if (!_client.Busy && !_client.Connected && MessageBox.Show(this, T("اشتراک و سرورهای آن حذف شوند؟", "Delete this provider and its servers?"), "DrKozol", MessageBoxButton.YesNo) == MessageBoxResult.Yes)
                await _client.DeleteProvider(account.Id);
        });
        ShowMenu(menu);
    }
    private void EditProvider(SubItem original)
    {
        var account = JsonUtils.DeepCopy(original);
        var dialog = new Window { Owner = this, Title = T("تنظیمات اشتراک", "Provider settings"), Width = 500, Height = 600, Background = Panel, Foreground = Ink };
        var body = new StackPanel { Margin = new Thickness(20) };
        var name = new TextBox { Text = account.Remarks, Foreground = Ink, Background = Panel, Margin = new Thickness(0, 10, 0, 10) }; body.Children.Add(name);
        var visible = new CheckBox { Content = T("نمایش در Home", "Show on Home"), IsChecked = account.ShowOnHome != false, Foreground = Ink, Margin = new Thickness(0, 8, 0, 8) }; body.Children.Add(visible);
        var custom = new CheckBox { Content = T("فاصلهٔ آپدیت اختصاصی", "Custom update interval"), IsChecked = account.CustomUpdateInterval, Foreground = Ink }; body.Children.Add(custom);
        var interval = new TextBox { Text = (account.AutoUpdateInterval > 0 ? account.AutoUpdateInterval : _client.Config.DrKozol.UpdateMinutes).ToString(), Foreground = Ink, Background = Panel }; body.Children.Add(Label(T("فاصلهٔ آپدیت به دقیقه", "Update interval in minutes"))); body.Children.Add(interval);
        var dns = new TextBox { Text = account.TunnelDns ?? "", Foreground = Ink, Background = Panel }; body.Children.Add(Label("DNS")); body.Children.Add(dns);
        var ping = new TextBox { Text = account.ProbeUrl ?? "", Foreground = Ink, Background = Panel }; body.Children.Add(Label(T("آدرس آزمون (خالی = عمومی)", "Test URL (empty = global)"))); body.Children.Add(ping);
        var order = Picker(); order.ItemsSource = new[] { "ping", "name", "default" }; order.SelectedItem = account.SortOrder ?? "ping"; body.Children.Add(order);
        body.Children.Add(Button(T("ذخیره", "Save"), async () => {
            if (!int.TryParse(interval.Text, out var minutes)) return;
            account.Remarks = name.Text; account.DisplayNameOverride = name.Text; account.ShowOnHome = visible.IsChecked == true;
            account.CustomUpdateInterval = custom.IsChecked == true; account.AutoUpdateInterval = minutes; account.TunnelDns = dns.Text; account.ProbeUrl = ping.Text; account.SortOrder = order.SelectedItem as string;
            if (await _client.SaveProvider(account)) { dialog.Close(); Build(); }
        }));
        dialog.Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; dialog.ShowDialog();
    }
    private void OpenServerTests(ProfileItem profile, SubItem account)
    {
        var menu = Menu();
        async Task Probe(bool real)
        {
            if (_client.Connected) { await _client.ProbeCurrent(real); return; }
            if (account.Id != _client.Config.SubIndexId) await _client.SelectAccount(account.Id);
            await _client.Probe(real, [profile]);
        }
        var allowed = !_client.Busy && (!_client.Connected || profile.IndexId == _client.Config.IndexId);
        AddMenu(menu, "Real Delay", () => Probe(true), allowed); AddMenu(menu, "TCP Ping", () => Probe(false), allowed); ShowMenu(menu);
    }
    private ContextMenu Menu() => new() { Background = Panel, Foreground = Ink, BorderBrush = Stroke, Placement = PlacementMode.MousePoint };
    private void AddMenu(ContextMenu menu, string text, Func<Task> action, bool enabled = true)
    {
        var item = new MenuItem { Header = text, IsEnabled = enabled, Background = Panel, Foreground = Ink, Padding = new Thickness(10, 6, 10, 6) };
        item.Click += async (_, _) => { try { await action(); } catch (Exception) { _status.Text = T("تست کامل نشد.", "The test could not be completed."); } };
        menu.Items.Add(item);
    }
    private static void ShowMenu(ContextMenu menu) { menu.IsOpen = true; }

    private void Refresh()
    {
        if (!_ready || _closing) return;
        if (DateTimeOffset.UtcNow >= _noticeUntil) { _noticeUntil = DateTimeOffset.MaxValue; _status.Text = ""; }
        var signature = $"{_client.Config.SubIndexId}|{_client.AccountProfiles.Values.Sum(p => p.Count)}|{_client.Config.IndexId}|{_client.Busy}|{_client.IsTesting}|{_client.HomeChoice}|{_client.Connected}|{_client.UseTun}|{Farsi}|{_client.Config.UiItem.ColorPrimaryName}|{string.Join('|', _client.Accounts.Select(a => $"{a.Id}:{a.Announcement}:{a.Remarks}:{a.CheckedAt}:{AccountUsage.Locked(a)}"))}";
        if (signature != _lastStructure) { _lastStructure = signature; Build(); }
        RefreshMetrics();
    }
    private void RefreshMetrics()
    {
        if (_state is not null) _state.Text = _client.IsConnecting ? T("در حال اتصال…", "Connecting…") : _client.IsTesting ? T("در حال تست تأخیر…", "Testing latency…") : _client.Connected ? T("متصل", "Connected") : _client.Busy ? T("در حال انجام عملیات…", "Working…") : T("برای اتصال کلیک کنید", "Tap to connect");
        if (_speed is not null) _speed.Text = _client.Connected ? $"↓ {AccountUsage.Bytes(_client.Down)}/s     ↑ {AccountUsage.Bytes(_client.Up)}/s   ·   {T("مصرف", "Session")} {AccountUsage.Bytes(_client.SessionBytes)}" : "";
        if (_speed is not null && _client.Config.DrKozol.MemoryMonitor) _speed.Text += "  ·  " + T("حافظه: ", "Memory: ") + AccountUsage.Bytes(_client.MemoryBytes);
        if (_probeResult is not null && _client.SelectedProfile is { } tested)
            _probeResult.Text = _client.IsTesting ? T("در حال تست…", "Testing…")
                : !_client.Real.TryGetValue(tested.IndexId, out var latency) ? ""
                : latency > 0 ? $"Real Delay: {latency} ms" : T("سرور به تست پاسخ نداد", "Server did not respond to the test");
        foreach (var account in _client.Accounts)
            if (_quotas.TryGetValue(account.Id, out var quota))
            {
                var locked = AccountUsage.Locked(account);
                quota.Days.Text = locked ? T("جهت تمدید اقدام نمایید", "Please renew your subscription") : $"{AccountUsage.Days(account)?.ToString() ?? (account.ExpiresAt == 0 ? "∞" : "—")} {T("روز باقی‌مانده", "days left")}";
                quota.Days.Foreground = locked ? Brush("#E06D76") : Muted;
                decimal? used = account.UploadBytes is { } up && account.DownloadBytes is { } down ? Math.Min(long.MaxValue, (decimal)up + down) : null;
                quota.Bytes.Text = $"{AccountUsage.Bytes(used is { } u ? (long)u : null)} / {(account.TotalBytes == 0 ? "∞" : AccountUsage.Bytes(account.TotalBytes))}";
                quota.Bar.Value = account.TotalBytes is > 0 && used is { } usage ? (double)Math.Clamp(usage / account.TotalBytes.Value, 0, 1) : 0;
                quota.Bar.ToolTip = T("حجم باقی‌مانده: ", "Remaining data: ") + AccountUsage.Bytes(AccountUsage.Remaining(account));
            }
        foreach (var pair in _delays)
        {
            var real = _client.Real.GetValueOrDefault(pair.Key); var tcp = _client.Tcp.GetValueOrDefault(pair.Key);
            pair.Value.Real.Text = _client.Real.ContainsKey(pair.Key) ? real > 0 ? $"{real} ms" : "n/a" : "—";
            pair.Value.Dot.Fill = real > 0 ? Brush(real < 110 ? "#43D787" : real < 500 ? "#E4C74D" : "#EC6068") : Muted;
            pair.Value.Real.ToolTip = "Real Delay";
            pair.Value.Tcp.Text = _client.Tcp.ContainsKey(pair.Key) ? $"TCP {(tcp > 0 ? tcp + " ms" : "n/a")}" : "";
        }
    }

    private sealed record ThemeChoice(string Name, string Color, bool Dark)
    {
        public override string ToString() => Name;
    }
    private ComboBox Picker(double width = 190)
    {
        var picker = new ComboBox
        {
            Style = new Style(typeof(ComboBox)), Background = Panel, Foreground = Ink,
            BorderBrush = Stroke, MinWidth = width, MinHeight = 32,
            VerticalAlignment = VerticalAlignment.Center, FocusVisualStyle = FocusStyle()
        };
        var template = new ControlTemplate(typeof(ComboBox)); var root = new FrameworkElementFactory(typeof(Grid));
        var toggle = new FrameworkElementFactory(typeof(ToggleButton)); toggle.SetValue(FocusableProperty, false);
        toggle.SetValue(StyleProperty, new Style(typeof(ToggleButton))); toggle.SetValue(WidthProperty, double.NaN);
        toggle.SetValue(HeightProperty, double.NaN); toggle.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Stretch);
        toggle.SetValue(VerticalAlignmentProperty, VerticalAlignment.Stretch);
        toggle.SetBinding(ToggleButton.IsCheckedProperty, new Binding(nameof(ComboBox.IsDropDownOpen)) { RelativeSource = RelativeSource.TemplatedParent, Mode = BindingMode.TwoWay });
        var toggleTemplate = new ControlTemplate(typeof(ToggleButton)); var surface = new FrameworkElementFactory(typeof(Border));
        surface.SetValue(Border.BackgroundProperty, Panel); surface.SetValue(Border.BorderBrushProperty, Stroke);
        surface.SetValue(Border.BorderThicknessProperty, new Thickness(1)); surface.SetValue(Border.CornerRadiusProperty, new CornerRadius(8));
        toggleTemplate.VisualTree = surface; toggle.SetValue(Control.TemplateProperty, toggleTemplate); root.AppendChild(toggle);
        var content = new FrameworkElementFactory(typeof(ContentPresenter)); content.SetValue(IsHitTestVisibleProperty, false);
        content.SetValue(FrameworkElement.MarginProperty, new Thickness(10, 4, 29, 4)); content.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        content.SetBinding(ContentPresenter.ContentProperty, new Binding(nameof(ComboBox.SelectionBoxItem)) { RelativeSource = RelativeSource.TemplatedParent });
        content.SetBinding(ContentPresenter.ContentTemplateProperty, new Binding(nameof(ComboBox.SelectionBoxItemTemplate)) { RelativeSource = RelativeSource.TemplatedParent }); root.AppendChild(content);
        var arrow = new FrameworkElementFactory(typeof(System.Windows.Shapes.Path));
        arrow.SetValue(System.Windows.Shapes.Path.DataProperty, Geometry.Parse("M0,0 L4,4 L8,0")); arrow.SetValue(System.Windows.Shapes.Path.StrokeProperty, Muted);
        arrow.SetValue(System.Windows.Shapes.Path.StrokeThicknessProperty, 1.4); arrow.SetValue(WidthProperty, 8d); arrow.SetValue(HeightProperty, 4d);
        arrow.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Right); arrow.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        arrow.SetValue(MarginProperty, new Thickness(0, 0, 11, 0)); arrow.SetValue(IsHitTestVisibleProperty, false); root.AppendChild(arrow);
        var popup = new FrameworkElementFactory(typeof(Popup)); popup.Name = "PART_Popup";
        popup.SetValue(Popup.PlacementProperty, PlacementMode.Bottom); popup.SetValue(Popup.AllowsTransparencyProperty, true);
        popup.SetValue(Popup.FocusableProperty, false); popup.SetBinding(Popup.IsOpenProperty, new Binding(nameof(ComboBox.IsDropDownOpen)) { RelativeSource = RelativeSource.TemplatedParent });
        var dropdown = new FrameworkElementFactory(typeof(Border)); dropdown.SetValue(Border.BackgroundProperty, Panel);
        dropdown.SetValue(Border.BorderBrushProperty, Stroke); dropdown.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        dropdown.SetValue(Border.CornerRadiusProperty, new CornerRadius(8)); dropdown.SetValue(Border.PaddingProperty, new Thickness(4));
        dropdown.SetBinding(MinWidthProperty, new Binding(nameof(ActualWidth)) { RelativeSource = RelativeSource.TemplatedParent });
        var scroll = new FrameworkElementFactory(typeof(ScrollViewer)); scroll.SetValue(MaxHeightProperty, 320d);
        scroll.SetValue(ScrollViewer.VerticalScrollBarVisibilityProperty, ScrollBarVisibility.Auto);
        scroll.AppendChild(new FrameworkElementFactory(typeof(ItemsPresenter))); dropdown.AppendChild(scroll); popup.AppendChild(dropdown); root.AppendChild(popup);
        template.VisualTree = root; picker.Template = template;
        var items = new Style(typeof(ComboBoxItem)); items.Setters.Add(new Setter(Control.ForegroundProperty, Ink));
        items.Setters.Add(new Setter(Control.BackgroundProperty, Panel)); items.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(8, 7, 8, 7)));
        var highlighted = new Trigger { Property = ComboBoxItem.IsHighlightedProperty, Value = true }; highlighted.Setters.Add(new Setter(Control.BackgroundProperty, AccentTint));
        highlighted.Setters.Add(new Setter(Control.ForegroundProperty, Accent)); items.Triggers.Add(highlighted); picker.ItemContainerStyle = items;
        return picker;
    }
    private void BuildSettings()
    {
        var title = Label(T("تنظیمات", "Settings"), 23); title.FontWeight = FontWeights.Bold; _content.Children.Add(title);
        _content.Children.Add(Label("DrKozol", 11, Muted)); Section(T("ظاهر برنامه", "APPEARANCE"));
        var appearance = new StackPanel();
        appearance.Children.Add(Button(T("انتخاب تم", "Choose theme"), () => { _themePage = true; Build(); return Task.CompletedTask; }));
        var language = Picker(); language.ItemsSource = new[] { "فارسی", "English" }; language.SelectedIndex = Farsi ? 0 : 1;
        language.SelectionChanged += async (_, _) => await SetLanguage(language.SelectedIndex == 0 ? "fa" : "en"); AutomationProperties.SetName(language, T("زبان", "Language"));
        appearance.Children.Add(SettingRow("language", T("زبان", "Language"), T("زبان رابط کاربری", "Interface language"), language));
        appearance.Children.Add(SettingRow("info", T("آیکون برنامه", "App icon"), T("نشان نوشتاری DrKozol", "DrKozol wordmark"), Label("DK", 20, Accent)));
        _content.Children.Add(Card(appearance));
        Section(T("اتصال", "CONNECTION")); var connection = new StackPanel();
        connection.Children.Add(SettingRow("connection", T("حالت اتصال", "Connection mode"), T("تونل همهٔ برنامه‌ها یا پراکسی سیستم", "Virtual tunnel or system proxy"), Row(ModeButton("TUN", true), ModeButton("System Proxy", false))));
        var note = Label(T("TUN به دسترسی Administrator نیاز دارد. حالت System Proxy برای برنامه‌های سازگار با پراکسی سیستم است.", "TUN requires Administrator. System Proxy serves applications that use the system proxy."), 12, Muted); note.Margin = new Thickness(0, 10, 0, 10); connection.Children.Add(note);
        if (!TunHost.IsAdministrator)
        {
            var admin = Button(T("اجرای مجدد با دسترسی Administrator", "Restart as administrator"), RestartAsAdministrator, !_client.Busy); admin.HorizontalAlignment = HorizontalAlignment.Left; admin.Foreground = Accent; connection.Children.Add(admin);
        }
        _content.Children.Add(Card(connection));
        _content.Children.Add(Button(T("مسیریابی سایت‌ها و IP", "Site and IP routing"), EditRouting, !_client.Busy && !_client.Connected));
        _content.Children.Add(ClientPreferencesCard());
        var data = new StackPanel();
        data.Children.Add(Button(T("پشتیبان‌گیری", "Back up subscriptions"), () => LocalBackup(false), !_client.Busy && !_client.Connected));
        data.Children.Add(Button(T("بازیابی پشتیبان", "Restore backup"), () => LocalBackup(true), !_client.Busy && !_client.Connected));
        data.Children.Add(Button(T("گزارش‌های برنامه", "Application logs"), () => { Process.Start(new ProcessStartInfo(Utils.GetLogPath()) { UseShellExecute = true }); return Task.CompletedTask; }));
        _content.Children.Add(Card(data));
        Section(T("برنامه", "APPLICATION")); var about = new StackPanel();
        about.Children.Add(SettingRow("info", T("دربارهٔ DrKozol", "About DrKozol"), T("DrKozol، کلاینت اشتراک شما برای اتصال امن.", "DrKozol is your subscription client for secure connections."), Label(Version, 12, Muted)));
        var panel = Label(T("حجم، انقضا و پیام‌ها از پنل اشتراک شما دریافت می‌شوند.", "Quota, expiry and announcements come from your subscription panel."), 12, Muted); panel.Margin = new Thickness(0, 12, 0, 5); about.Children.Add(panel); _content.Children.Add(Card(about));
    }


    private async Task EditRouting()
    {
        var rules = await _client.CustomRoutingRules();
        var advanced = rules.Where(r => !r.Enabled || r.OutboundTag is not ("direct" or "proxy" or "block") ||
            r.Process?.Count > 0 || r.Protocol?.Count > 0 || !string.IsNullOrWhiteSpace(r.Port) || !string.IsNullOrWhiteSpace(r.Network) ||
            r.Domain?.Count > 0 && r.Ip?.Count > 0).ToList();
        var dialog = new Window { Owner = this, Title = T("مسیریابی", "Routing"), Width = 540, Height = 650, Background = Panel, Foreground = Ink };
        var body = new StackPanel { Margin = new Thickness(20) };
        body.Children.Add(Label(T("هر دامنه، IP یا CIDR در یک خط. تغییرات با اتصال بعدی اعمال می‌شوند.", "One domain, IP or CIDR per line. Changes apply on the next connection."), 12, Muted));
        if (advanced.Count > 0) body.Children.Add(Label(T("قواعد پیشرفتهٔ واردشده حفظ می‌شوند.", "Imported advanced rules are retained."), 12, Muted));
        var fields = new Dictionary<string, TextBox>();
        foreach (var (tag, label) in new[] { ("direct", T("مستقیم", "Direct")), ("proxy", T("پروکسی", "Proxy")), ("block", T("مسدود", "Block")) })
        {
            body.Children.Add(Label(label));
            var field = new TextBox { Text = string.Join(Environment.NewLine, rules.Except(advanced).Where(r => r.OutboundTag == tag).SelectMany(r => (r.Domain ?? []).Concat(r.Ip ?? []))),
                AcceptsReturn = true, MinHeight = 80, MaxHeight = 160, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Background = Panel, Foreground = Ink, Margin = new Thickness(0, 6, 0, 12) };
            AutomationProperties.SetName(field, label); fields[tag] = field; body.Children.Add(field);
        }
        var error = Label("", 12, Accent); body.Children.Add(error);
        async Task Import(string input)
        {
            if (await _client.SaveRoutingRules(input)) dialog.Close();
            else error.Text = T("قواعد معتبر نیستند یا ذخیره نشدند.", "Rules are invalid or could not be saved.");
        }
        body.Children.Add(Button(T("ذخیره", "Save"), async () => {
            try { await Import(RoutingProfileLink.Export(advanced.Concat(RoutingProfileLink.FromFields(fields["direct"].Text, fields["proxy"].Text, fields["block"].Text)).ToList())); }
            catch (Exception) { error.Text = T("حداقل یک قانون معتبر وارد کنید.", "Enter at least one valid rule."); }
        }));
        body.Children.Add(Button(T("ورود لینک از کلیپ‌بورد", "Import link from clipboard"), () => Import(Clipboard.GetText())));
        body.Children.Add(Button(T("ورود از تصویر QR", "Import QR image"), async () => {
            var picker = new Microsoft.Win32.OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp", CheckFileExists = true };
            if (picker.ShowDialog(dialog) != true) return;
            var text = await Task.Run(() => new FileInfo(picker.FileName).Length <= 10 * 1024 * 1024 ? QRCodeUtils.ParseBarcode(File.ReadAllBytes(picker.FileName)) : null);
            if (text is not null) await Import(text);
            else error.Text = T("QR معتبر پیدا نشد.", "No valid QR found.");
        }));
        body.Children.Add(Button(T("کپی لینک پروفایل", "Copy profile link"), () => { Clipboard.SetText(RoutingProfileLink.Export(rules)); return Task.CompletedTask; }, rules.Count > 0));
        body.Children.Add(Button(T("نمایش QR پروفایل", "Show profile QR"), async () => {
            var image = await Task.Run(() => QRCodeUtils.GenQRCode(RoutingProfileLink.Export(rules)));
            var bitmap = new BitmapImage(); using var stream = new MemoryStream(image);
            bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze();
            new Window { Owner = dialog, Title = "DrKozol QR", Width = 360, Height = 400, Background = Panel,
                Content = new Image { Source = bitmap, Margin = new Thickness(20), Stretch = Stretch.Uniform } }.ShowDialog();
        }, rules.Count > 0));
        dialog.Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; dialog.ShowDialog();
    }

    private async Task LocalBackup(bool restore)
    {
        if (restore)
        {
            var picker = new Microsoft.Win32.OpenFileDialog { Filter = "DrKozol backup|*.json", CheckFileExists = true };
            if (picker.ShowDialog(this) == true) await _client.Backup(picker.FileName, true);
        }
        else
        {
            var picker = new Microsoft.Win32.SaveFileDialog { Filter = "DrKozol backup|*.json", FileName = "DrKozol-backup.json" };
            if (picker.ShowDialog(this) == true) await _client.Backup(picker.FileName, false);
        }
    }

    private void BuildThemeGallery()
    {
        _content.Children.Add(Button(T("بازگشت به تنظیمات", "Back to Settings"), () => { _themePage = false; Build(); return Task.CompletedTask; }));
        _content.Children.Add(HomeAction(T("خودکار • مطابق سیستم", "Automatic · system appearance"), async () => {
            _client.Config.UiItem.CurrentTheme = "Auto"; await ConfigHandler.SaveConfig(_client.Config); Build();
        }, _client.Config.UiItem.CurrentTheme == "Auto"));
        foreach (var dark in new[] { false, true })
        {
        Section(dark ? T("تم‌های تیره", "Dark themes") : T("تم‌های روشن", "Light themes"));
        var gallery = new WrapPanel();
        foreach (var color in DesktopAppearance.Palettes)
        {
            if (color == "OLED" && !dark) continue;
            var palette = DesktopAppearance.For(dark ? "Dark" : "Light", color);
            var selected = _client.Config.UiItem.CurrentTheme != "Auto" && Palette.IsDark == dark
                && string.Equals(_client.Config.UiItem.ColorPrimaryName ?? "Ocean", color, StringComparison.OrdinalIgnoreCase);
            var button = HomeAction(color + (dark ? T(" • تاریک", " · Dark") : T(" • روشن", " · Light")), () => SetTheme(color, dark), selected);
            var preview = new StackPanel();
            var dots = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(4) };
            foreach (var sample in new[] { palette.Accent, palette.Panel, palette.Background })
                dots.Children.Add(new System.Windows.Shapes.Ellipse { Width = 18, Height = 18, Fill = Brush(sample), Stroke = Stroke, StrokeThickness = 1, Margin = new Thickness(3) });
            preview.Children.Add(dots); preview.Children.Add(Label(color + (dark ? T(" تاریک", " Dark") : T(" روشن", " Light")), 13));
            button.Content = preview; button.Width = 190; button.Height = 86; gallery.Children.Add(button);
        }
        _content.Children.Add(gallery);
        }
    }
    private Button HomeAction(string label, Func<Task> action, bool selected)
    {
        var button = Button(label, action, !_client.Busy, 44);
        button.Foreground = Accent; button.Margin = new Thickness(4);
        button.Background = selected ? AccentTint : Panel;
        button.FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal;
        button.BorderBrush = selected ? Accent : Stroke;
        AutomationProperties.SetHelpText(button, selected ? T("انتخاب شده", "Selected") : "");
        return button;
    }
    private Border ClientPreferencesCard()
    {
        var body = new StackPanel();
        var draft = JsonUtils.DeepCopy(_client.Config.DrKozol);
        var ipPreference = Picker(); ipPreference.ItemsSource = new[] { "auto", "ipv4", "ipv6" }; ipPreference.SelectedItem = draft.IpPreference;
        body.Children.Add(SettingRow("connection", T("نوع IP ترجیحی", "Preferred IP family"), T("با امکان استفاده از خانوادهٔ دیگر", "Retains fallback to the other address family"), ipPreference));
        foreach (var option in DesktopPreferences.Options)
        {
            var toggle = new CheckBox { Content = T(option.Farsi, option.English), IsChecked = option.Read(_client.Config.DrKozol),
                Foreground = Ink, Margin = new Thickness(0, 7, 0, 7), MinHeight = 32 };
            toggle.Click += (_, _) => option.Write(draft, toggle.IsChecked == true);
            body.Children.Add(toggle);
        }
        var interval = new TextBox { Text = _client.Config.DrKozol.UpdateMinutes.ToString(), Foreground = Ink, Background = Panel, Margin = new Thickness(0, 6, 0, 6) };
        body.Children.Add(SettingRow("connection", T("فاصلهٔ آپدیت (دقیقه)", "Update interval (minutes)"), "", interval));
        var ping = new TextBox { Text = _client.Config.DrKozol.PingUrl, Foreground = Ink, Background = Panel, MinWidth = 220 };
        body.Children.Add(SettingRow("ping", T("آدرس آزمون اتصال", "Connection test URL"), "", ping));

        TextBox Field(string label, string value) {
            var field = new TextBox { Text = value, Foreground = Ink, Background = Panel, MinWidth = 180 };
            body.Children.Add(SettingRow("connection", label, "", field)); return field;
        }
        var agent = Field("User-Agent", _client.Config.DrKozol.UserAgent);
        var dns = Field(T("DNS تونل", "Tunnel DNS"), _client.Config.DrKozol.TunnelDns);
        var username = Field(T("نام کاربری پراکسی", "Proxy username"), _client.Config.DrKozol.ProxyUser);
        var sharingPort = Field(T("پورت اشتراک پراکسی", "Shared proxy port"), _client.Config.DrKozol.SharingPort.ToString());
        var applications = Field(T("نام فایل برنامه‌ها (با کاما جدا شود)", "Application filenames (comma separated)"), _client.Config.DrKozol.Applications);
        var applicationMode = new ComboBox { ItemsSource = new[] { "all", "include", "exclude" }, SelectedItem = _client.Config.DrKozol.ApplicationMode, Foreground = Ink, Background = Panel };
        body.Children.Add(SettingRow("connection", T("مسیریابی برنامه‌ها در TUN", "Application routing in TUN"), T("همه، فقط انتخاب‌شده، یا به‌جز انتخاب‌شده", "All, only selected, or except selected"), applicationMode));
        var password = new PasswordBox { Password = _client.Config.DrKozol.ProxyPassword, Foreground = Ink, Background = Panel, MinWidth = 180 };
        body.Children.Add(SettingRow("connection", T("رمز پراکسی", "Proxy password"), "", password));
        body.Children.Add(Button(T("ذخیره", "Save"), async () => {
            if (!int.TryParse(interval.Text, out var minutes)) return;
            var next = JsonUtils.DeepCopy(draft); next.UpdateMinutes = minutes; next.PingUrl = ping.Text;
            next.IpPreference = ipPreference.SelectedItem as string ?? "auto";
            if (!int.TryParse(sharingPort.Text, out var port)) return;
            next.UserAgent = agent.Text ?? ""; next.TunnelDns = dns.Text ?? "";
            next.ProxyUser = username.Text ?? ""; next.ProxyPassword = password.Password; next.SharingPort = port;
            next.ApplicationMode = applicationMode.SelectedItem as string ?? "all"; next.Applications = applications.Text ?? "";
            await _client.SavePreferences(next);
        }));
        body.Children.Add(Button(T("بازخورد", "Feedback"), () => { Process.Start(new ProcessStartInfo("https://github.com/SpecterLogic/Drkozol/issues") { UseShellExecute = true }); return Task.CompletedTask; }));
        body.Children.Add(Button(T("بررسی نسخهٔ جدید", "Check for updates"), async () => {
            if (await _client.CheckForUpdate() is not { } url) return;
            var picker = new Microsoft.Win32.SaveFileDialog { FileName = Path.GetFileName(new Uri(url).AbsolutePath), Filter = "DrKozol download|*.zip;*.exe;*.msi", OverwritePrompt = true };
            if (picker.ShowDialog(this) == true) await _client.DownloadUpdate(url, picker.FileName);
        }));
        return Card(body);
    }
    private void Section(string text)
    {
        var label = Label(text, 11, Muted); label.Margin = new Thickness(3, 10, 0, 0); _content.Children.Add(label);
    }
    private Grid SettingRow(string icon, string title, string subtitle, UIElement control)
    {
        var row = new Grid { MinHeight = 72, Margin = new Thickness(0, 0, 0, 1) }; row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(48) }); row.ColumnDefinitions.Add(new ColumnDefinition()); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(new Border { Child = DrKozolVisuals.Icon(icon, Accent, 17), Width = 36, Height = 36, CornerRadius = new CornerRadius(9), Background = AccentTint, HorizontalAlignment = HorizontalAlignment.Left });
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 14, 0) }; text.Children.Add(Label(title, 14)); text.Children.Add(Label(subtitle, 11, Muted)); Grid.SetColumn(text, 1); row.Children.Add(text);
        control.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center); Grid.SetColumn(control, 2); row.Children.Add(control); return row;
    }
    private async Task SetTheme(string color, bool dark)
    {
        _dark = dark; _client.Config.UiItem.CurrentTheme = dark ? "Dark" : "Light";
        _client.Config.UiItem.ColorPrimaryName = color; await ConfigHandler.SaveConfig(_client.Config); Build();
    }
    private async Task SetLanguage(string code) { _client.Config.UiItem.CurrentLanguage = code; await ConfigHandler.SaveConfig(_client.Config); Build(); }
    private TextBox UrlBox()
    {
        _url = new TextBox { Style = new Style(typeof(TextBox)), Background = Brush(Palette.Background), Foreground = Ink, BorderBrush = Stroke,
            Text = _input, Margin = new Thickness(0, 12, 0, 8), Padding = new Thickness(12), FlowDirection = FlowDirection.LeftToRight,
            MinHeight = 65, MaxHeight = 140, MaxLength = 65536, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        AutomationProperties.SetName(_url, T("لینک ساب", "Subscription URL")); return _url;
    }
    private async Task RestartAsAdministrator()
        => await RestartElevated();

    private async Task<bool> RestartElevated()
    {
        if (_client.Busy || _elevating || TunHost.IsAdministrator) return false;
        _elevating = true;
        try
        {
            var start = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = true, Verb = "runas", WorkingDirectory = Utils.GetBaseDirectory(), WindowStyle = ProcessWindowStyle.Normal };
            start.ArgumentList.Add(Global.RebootAs);
            start.ArgumentList.Add("--wait-for-exit=" + Environment.ProcessId);
            if (Process.Start(start) is null) throw new IOException();
            IsEnabled = false;
            await _client.Close();
            _closing = true; Close();
            return true;
        }
        catch (Exception error)
        {
            Logging.SaveLog("DrKozol elevated restart", new Exception(error.GetType().Name));
            _status.Text = T("اجرای مجدد تأیید نشد؛ برنامه همچنان قابل استفاده است.", "Restart was not approved; the app is still available.");
            return false;
        }
        finally { _elevating = false; }
    }
}
