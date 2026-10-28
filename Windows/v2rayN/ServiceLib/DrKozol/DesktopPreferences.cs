namespace ServiceLib.DrKozol;

// Defaults also apply when opening a configuration written by earlier desktop versions.
public sealed class DesktopPreferences
{
    public bool AutoConnect { get; set; }
    public bool AutoBoot { get; set; }
    public bool AutoUpdate { get; set; } = true;
    public int UpdateMinutes { get; set; } = 720;
    public bool NotifyUpdate { get; set; }
    public bool UpdateOnLaunch { get; set; }
    public bool PingOnLaunch { get; set; }
    public bool PingOnUpdate { get; set; } = true;
    public bool DisconnectOnLock { get; set; }
    public bool ReconnectOnUnlock { get; set; }
    public bool MemoryMonitor { get; set; }
    public bool AllowLan { get; set; } = true;
    public bool RouteLan { get; set; }
    public bool SendDeviceId { get; set; }
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public bool ShareProxy { get; set; }
    public int SharingPort { get; set; } = 10818;
    public string ProxyUser { get; set; } = "";
    public string ProxyPassword { get; set; } = "";
    public string ApplicationMode { get; set; } = "all";
    public string Applications { get; set; } = "";
    public string TunnelDns { get; set; } = "";
    public string PingUrl { get; set; } = "https://www.gstatic.com/generate_204";
    public string UserAgent { get; set; } = "DrKozol/1.5.0";
    public string SortOrder { get; set; } = "ping";

    public static readonly DesktopOption[] Options = [
        new("اتصال خودکار", "Connect on launch", p => p.AutoConnect, (p, v) => p.AutoConnect = v),
        new("اجرا هنگام روشن‌شدن دستگاه", "Start with the device", p => p.AutoBoot, (p, v) => p.AutoBoot = v),
        new("آپدیت خودکار ساب", "Automatic subscription updates", p => p.AutoUpdate, (p, v) => p.AutoUpdate = v),
        new("اعلان به‌روزرسانی", "Notify after updating", p => p.NotifyUpdate, (p, v) => p.NotifyUpdate = v),
        new("آپدیت هنگام ورود", "Update on launch", p => p.UpdateOnLaunch, (p, v) => p.UpdateOnLaunch = v),
        new("تست هنگام ورود", "Test on launch", p => p.PingOnLaunch, (p, v) => p.PingOnLaunch = v),
        new("تست پس از آپدیت", "Test after updating", p => p.PingOnUpdate, (p, v) => p.PingOnUpdate = v),
        new("قطع هنگام قفل دستگاه", "Disconnect on lock", p => p.DisconnectOnLock, (p, v) => p.DisconnectOnLock = v),
        new("اتصال مجدد پس از بازشدن قفل", "Reconnect on unlock", p => p.ReconnectOnUnlock, (p, v) => p.ReconnectOnUnlock = v),
        new("نمایش مصرف حافظه", "Show memory usage", p => p.MemoryMonitor, (p, v) => p.MemoryMonitor = v),
        new("دسترسی شبکهٔ محلی", "Allow local network", p => p.AllowLan, (p, v) => p.AllowLan = v),
        new("عبور شبکهٔ محلی از تونل", "Route local network through tunnel", p => p.RouteLan, (p, v) => p.RouteLan = v),
        new("ارسال شناسهٔ دستگاه به پنل", "Send device ID to provider", p => p.SendDeviceId, (p, v) => p.SendDeviceId = v),
        new("اشتراک پراکسی با شبکهٔ محلی", "Share proxy with local network", p => p.ShareProxy, (p, v) => p.ShareProxy = v),
    ];

    public int EffectiveInterval(SubItem account) => Math.Clamp(
        account.CustomUpdateInterval && account.AutoUpdateInterval > 0
            ? account.AutoUpdateInterval : UpdateMinutes, 15, 43200);

    public static bool ValidHttps(string? value) => Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttps && string.IsNullOrEmpty(uri.UserInfo);

    public static bool ValidDns(string? value) => string.IsNullOrWhiteSpace(value)
        || System.Net.IPAddress.TryParse(value.Trim(), out _) || ValidHttps(value);

    public static string ProfileIdentity(ProfileItem profile)
    {
        var copy = JsonUtils.DeepCopy(profile);
        copy.IndexId = ""; copy.Subid = "";
        return JsonUtils.Serialize(copy);
    }

    public static IEnumerable<ProfileItem> OrderProfiles(IEnumerable<ProfileItem> source,
        IReadOnlyDictionary<string, int> delays, string? order) => order switch {
        "default" => source,
        "name" => source.OrderBy(p => p.Remarks, StringComparer.OrdinalIgnoreCase),
        _ => source.OrderBy(p => delays.TryGetValue(p.IndexId, out var delay) && delay > 0 ? delay : int.MaxValue),
    };
}

public sealed record DesktopOption(string Farsi, string English, Func<DesktopPreferences, bool> Read,
    Action<DesktopPreferences, bool> Write);
