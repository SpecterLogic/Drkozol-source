using System.Text.Json;

namespace ServiceLib.DrKozol;

public sealed class DesktopBackup
{
    public string Format { get; set; } = "";
    public List<SubItem> Providers { get; set; } = [];
    public List<ProfileItem> Profiles { get; set; } = [];

    public static DesktopBackup Parse(string json)
    {
        var bundle = JsonSerializer.Deserialize<DesktopBackup>(json) ?? throw new InvalidDataException();
        if (bundle.Format != "DrKozol-desktop-1" || bundle.Providers is null || bundle.Profiles is null
            || bundle.Providers.Count > 1000 || bundle.Profiles.Count > 100000
            || bundle.Providers.Any(p => string.IsNullOrWhiteSpace(p.Id) || !p.LocalGroup && !AccountUsage.ValidUrl(p.Url))
            || bundle.Profiles.Any(p => string.IsNullOrWhiteSpace(p.IndexId))
            || bundle.Providers.Select(p => p.Id).Distinct().Count() != bundle.Providers.Count
            || bundle.Profiles.Select(p => p.IndexId).Distinct().Count() != bundle.Profiles.Count
            || bundle.Profiles.Any(p => !bundle.Providers.Any(a => a.Id == p.Subid))) throw new InvalidDataException();
        return bundle;
    }
}
