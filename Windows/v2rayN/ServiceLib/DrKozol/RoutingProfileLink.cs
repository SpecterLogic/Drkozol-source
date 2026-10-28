using System.Text;
using System.Text.Json;

namespace ServiceLib.DrKozol;

public static class RoutingProfileLink
{
    public const string Prefix = "drkozol://routing/add/";
    public const string ProfileId = "drkozol-custom-routing";
    private static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    public static List<RulesItem> Parse(string input)
    {
        input = input.Trim();
        if (input.Length > 1_000_000) throw new InvalidDataException();
        if (input.StartsWith(Prefix, StringComparison.Ordinal))
        {
            var encoded = input[Prefix.Length..].Replace('-', '+').Replace('_', '/');
            input = Encoding.UTF8.GetString(Convert.FromBase64String(encoded.PadRight((encoded.Length + 3) / 4 * 4, '=')));
        }
        var rules = JsonSerializer.Deserialize<List<RulesItem>>(input, Options) ?? throw new InvalidDataException();
        if (rules.Count is < 1 or > 5000 || rules.Any(r => r is null || string.IsNullOrWhiteSpace(r.OutboundTag) || r.OutboundTag.Length > 128
            || !new[] { r.Ip, r.Domain, r.Process, r.Protocol }.Any(v => v?.Count > 0) && string.IsNullOrWhiteSpace(r.Port) && string.IsNullOrWhiteSpace(r.Network)
            || new[] { r.Ip, r.Domain, r.Process, r.Protocol }.SelectMany(v => v ?? []).Any(v => string.IsNullOrWhiteSpace(v) || v.Length > 1024)))
            throw new InvalidDataException();
        foreach (var rule in rules) rule.Id = Guid.NewGuid().ToString("N");
        return rules;
    }

    public static string Export(List<RulesItem> rules) => Prefix + Convert.ToBase64String(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(rules, Options)))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static List<RulesItem> FromFields(string direct, string proxy, string block)
    {
        var result = new List<RulesItem>();
        foreach (var (text, tag) in new[] { (block, "block"), (direct, "direct"), (proxy, "proxy") })
        {
            var lines = text.Split(['\r', '\n'], StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).Distinct().ToList();
            var ips = lines.Where(s => s.StartsWith("geoip:", StringComparison.Ordinal) || System.Net.IPAddress.TryParse(s.Split('/')[0], out _)).ToList();
            var domains = lines.Except(ips).ToList();
            if (ips.Count > 0) result.Add(new RulesItem { OutboundTag = tag, Ip = ips });
            if (domains.Count > 0) result.Add(new RulesItem { OutboundTag = tag, Domain = domains });
        }
        return result.Count == 0 ? [] : Parse(JsonSerializer.Serialize(result, Options));
    }
}
