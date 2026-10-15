using System.Net.Http.Headers;

namespace ServiceLib.DrKozol;

public static class AccountUsage
{
    public static void Capture(SubItem account, HttpResponseHeaders headers)
    {
        string? Header(string name) => headers.TryGetValues(name, out var items) ? items.FirstOrDefault() : null;
        if (ProviderText(Header("announce") ?? Header("profile-announce")) is { } announcement) account.Announcement = announcement;
        if (!string.IsNullOrWhiteSpace(account.DisplayNameOverride)) account.Remarks = account.DisplayNameOverride;
        else if (ProviderText(Header("profile-title")) is { Length: > 0 } title) account.Remarks = title;
        if (!account.CustomUpdateInterval && int.TryParse(Header("profile-update-interval"), out var hours) && hours is >= 1 and <= 720) account.AutoUpdateInterval = hours * 60;
        if (headers.TryGetValues("subscription-userinfo", out var values))
            foreach (var item in string.Join(";", values).Split(';'))
            {
                var pair = item.Trim().Split('=', 2);
                if (pair.Length != 2 || !long.TryParse(pair[1], out var number) || number < 0) continue;
                switch (pair[0].ToLowerInvariant())
                {
                    case "upload": account.UploadBytes = number; break;
                    case "download": account.DownloadBytes = number; break;
                    case "total": account.TotalBytes = number; break;
                    case "expire": account.ExpiresAt = number; break;
                }
            }
        account.CheckedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (headers.Date is { } serverDate) account.ServerTime = serverDate.ToUnixTimeSeconds();
        account.LatestTime = Math.Max(account.LatestTime ?? 0, Now(account));
    }

    public static string? ProviderText(string? value)
    {
        if (value is null || value.Length > 8192) return null;
        if (value.StartsWith("base64:", StringComparison.OrdinalIgnoreCase))
        {
            try { value = new System.Text.UTF8Encoding(false, true).GetString(Convert.FromBase64String(value[7..].Trim())); }
            catch (FormatException) { return null; }
            catch (System.Text.DecoderFallbackException) { return null; }
        }
        return new string(value.Where(c => !char.IsControl(c) || c is '\n' or '\t').Take(2048).ToArray()).Trim();
    }

    public static long Now(SubItem account)
    {
        var local = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var anchored = account.ServerTime is { } server && account.CheckedAt is { } checkedAt
            ? server + Math.Max(0, local - checkedAt) : local;
        return Math.Max(Math.Max(local, anchored), account.LatestTime ?? 0);
    }

    public static long? Remaining(SubItem account) => account.TotalBytes is > 0
        && account.UploadBytes is { } up && account.DownloadBytes is { } down
        ? (long)Math.Max(0m, (decimal)account.TotalBytes.Value - up - down) : null;

    public static bool Locked(SubItem account) => (account.ExpiresAt is > 0 && Now(account) >= account.ExpiresAt)
        || Remaining(account) == 0;

    public static long? Days(SubItem account) => account.ExpiresAt is > 0
        ? Math.Max(0, (long)Math.Ceiling((account.ExpiresAt.Value - Now(account)) / 86400d)) : null;

    public static string Bytes(long? bytes) => bytes is null ? "—" : bytes >= 1073741824
        ? $"{bytes / 1073741824d:0.00} GB" : $"{bytes / 1048576d:0.00} MB";

    public static bool ValidUrl(string text) => Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && !string.IsNullOrWhiteSpace(uri.Host) && string.IsNullOrEmpty(uri.UserInfo);
}
