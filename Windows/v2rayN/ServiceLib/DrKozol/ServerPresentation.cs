namespace ServiceLib.DrKozol;

public static class ServerPresentation
{
    private static readonly ConcurrentDictionary<string, byte[]> Flags = new();
    private static readonly (string Code, string[] Names)[] Countries =
    [
        ("DE", ["germany", "german", "deutschland", "آلمان"]), ("SE", ["sweden", "swedish", "سوئد"]),
        ("US", ["united states", "usa", "america", "آمریکا"]), ("GB", ["united kingdom", "britain", "england", "انگلیس"]),
        ("NL", ["netherlands", "holland", "هلند"]), ("FR", ["france", "french", "فرانسه"]),
        ("CA", ["canada", "کانادا"]), ("TR", ["turkey", "türkiye", "ترکیه"]),
        ("FI", ["finland", "فنلاند"]), ("CH", ["switzerland", "سوئیس"]), ("IR", ["iran", "ایران"]),
        ("AE", ["uae", "emirates", "امارات"]), ("JP", ["japan", "ژاپن"]), ("SG", ["singapore", "سنگاپور"]),
        ("RU", ["russia", "روسیه"]), ("IT", ["italy", "ایتالیا"]), ("ES", ["spain", "اسپانیا"]),
        ("AU", ["australia", "استرالیا"]), ("IN", ["india", "هند"]), ("PL", ["poland", "لهستان"]),
    ];

    public static string? CountryCode(ProfileItem profile)
    {
        var runes = profile.Remarks.EnumerateRunes().ToArray();
        for (var i = 0; i + 1 < runes.Length; i++)
            if (runes[i].Value is >= 0x1F1E6 and <= 0x1F1FF && runes[i + 1].Value is >= 0x1F1E6 and <= 0x1F1FF)
                return string.Concat((char)('A' + runes[i].Value - 0x1F1E6), (char)('A' + runes[i + 1].Value - 0x1F1E6));
        var name = profile.Remarks.ToLowerInvariant();
        foreach (var country in Countries)
            if (country.Names.Any(n => Regex.IsMatch(name, $"(?<!\\p{{L}}){Regex.Escape(n)}(?!\\p{{L}})"))) return country.Code;
        return null;
    }

    public static string DisplayName(ProfileItem profile)
    {
        var builder = new StringBuilder();
        foreach (var rune in profile.Remarks.EnumerateRunes())
            if (rune.Value is < 0x1F1E6 or > 0x1F1FF) builder.Append(rune);
        return builder.ToString().Trim();
    }

    public static bool IsAccountInfo(ProfileItem profile)
    {
        if (profile.ConfigType != EConfigType.SOCKS || profile.AmneziaConfig is not null) return false;
        var local = profile.Port == 0 || IPAddress.TryParse(profile.Address, out var address)
            && (IPAddress.IsLoopback(address) || address.Equals(IPAddress.Any) || address.Equals(IPAddress.IPv6Any));
        var quota = profile.Remarks.Contains("GB", StringComparison.OrdinalIgnoreCase) || profile.Remarks.Contains("MB", StringComparison.OrdinalIgnoreCase);
        var days = profile.Remarks.Contains('⌛') || profile.Remarks.Contains('⏳') || profile.Remarks.Contains("روز")
            || profile.Remarks.Contains("days", StringComparison.OrdinalIgnoreCase);
        return local && quota && days;
    }

    public static string Protocol(ProfileItem profile) => profile.AmneziaConfig is not null ? "AmneziaWG"
        : profile.ConfigType == EConfigType.SOCKS ? "SOCKS5" : profile.ConfigType.ToString();
    public static string Transport(ProfileItem profile) => profile.AmneziaConfig is not null || profile.ConfigType is EConfigType.WireGuard or EConfigType.Hysteria2 ? "UDP"
        : string.IsNullOrWhiteSpace(profile.Network) || profile.Network.Equals("raw", StringComparison.OrdinalIgnoreCase) ? "TCP" : profile.Network.ToUpperInvariant();
    public static string Security(ProfileItem profile) => profile.StreamSecurity.ToUpperInvariant();
    public static string ProtocolColor(ProfileItem profile, bool dark = true) => (Protocol(profile), dark) switch
    {
        ("VLESS", true) => "#B8CE48", ("VMess", true) => "#B69CEC", ("Trojan", true) => "#EBA26C", ("Shadowsocks", true) => "#E57465",
        ("AmneziaWG" or "WireGuard", true) => "#36CDB8", ("SOCKS5", true) => "#49C8E4",
        ("VLESS", false) => "#607012", ("VMess", false) => "#694093", ("Trojan", false) => "#944D19", ("Shadowsocks", false) => "#A63C31",
        ("AmneziaWG" or "WireGuard", false) => "#0C8074", ("SOCKS5", false) => "#007C97", (_, true) => "#9AA59F", _ => "#526057",
    };
    public static byte[]? FlagPng(string? code)
    {
        if (code is null || code.Length != 2 || !code.All(char.IsAsciiLetter)) return null;
        return Flags.GetOrAdd(code.ToUpperInvariant(), key =>
        {
            using var stream = typeof(ServerPresentation).Assembly.GetManifestResourceStream($"ServiceLib.DrKozol.Flags.{key.ToLowerInvariant()}.png");
            if (stream is null) return [];
            using var output = new MemoryStream(); stream.CopyTo(output); return output.ToArray();
        }) is { Length: > 0 } bytes ? bytes : null;
    }
}
