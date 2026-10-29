using ServiceLib.DrKozol;
using ServiceLib.Tests.CoreConfig;
using System.Text.Json.Nodes;

namespace ServiceLib.Tests.DrKozol;

[NotInParallel]
public class Desktop146CompatibilityTests
{
    [Test]
    public async Task BackupsRejectForeignFormatsAndOrphanedProfiles()
    {
        var valid = new DesktopBackup { Format = "DrKozol-desktop-1", Providers = [new SubItem { Id = "provider", LocalGroup = true }],
            Profiles = [new ProfileItem { IndexId = "profile", Subid = "provider", Remarks = "server" }] };
        var json = System.Text.Json.JsonSerializer.Serialize(valid);
        await DesktopBackup.Parse(json).Profiles.Single().Remarks.Should().BeEqualTo("server");
        foreach (var invalid in new[] { "{}", json.Replace("DrKozol-desktop-1", "other"), json.Replace("\"Subid\":\"provider\"", "\"Subid\":\"missing\"") })
        {
            var rejected = false;
            try { DesktopBackup.Parse(invalid); } catch (System.IO.InvalidDataException) { rejected = true; }
            await rejected.Should().BeTrue();
        }
    }

    [Test]
    public async Task PreviousConfigKeepsSelectionAndAcquiresSafeDefaults()
    {
        var config = JsonUtils.Deserialize<Config>("""{"IndexId":"chosen","SubIndexId":"provider"}""")!;
        await config.IndexId.Should().BeEqualTo("chosen");
        await config.DrKozol.UpdateMinutes.Should().BeEqualTo(720);
        await config.DrKozol.AutoConnect.Should().BeFalse();
        var account = new SubItem { AutoUpdateInterval = 120 };
        await (account.ShowOnHome != false).Should().BeTrue();
        await config.DrKozol.EffectiveInterval(account).Should().BeEqualTo(720);
        account.CustomUpdateInterval = true;
        await config.DrKozol.EffectiveInterval(account).Should().BeEqualTo(120);
        account.AutoUpdateInterval = 999999;
        await config.DrKozol.EffectiveInterval(account).Should().BeEqualTo(43200);
    }

    [Test]
    public async Task RefreshIdentitySurvivesStagingButNotCredentialOrEndpointChanges()
    {
        var profile = new ProfileItem { IndexId = "stable", Subid = "subscription", Address = "192.0.2.1", Port = 443, Password = "test" };
        var staged = JsonUtils.DeepCopy(profile); staged.IndexId = "new"; staged.Subid = "staging";
        await DesktopPreferences.ProfileIdentity(profile).Should().BeEqualTo(DesktopPreferences.ProfileIdentity(staged));
        staged.Password = "changed";
        await (DesktopPreferences.ProfileIdentity(profile) != DesktopPreferences.ProfileIdentity(staged)).Should().BeTrue();
    }

    [Test]
    public async Task TransportEndpointRoutesCannotLoopWhenProcessLookupFails()
    {
        var endpoints = new[] { "192.0.2.10", "2001:db8::10", "fd00::10", "192.0.2.10", "127.0.0.1", "::1", "0.0.0.0", "::", "224.0.0.1", "ff02::1" }
            .Select(System.Net.IPAddress.Parse).ToArray();
        var document = JsonNode.Parse(TunHost.Configuration(10808, protocolEndpoints: endpoints))!;
        var exclusions = document["inbounds"]![0]!["route_exclude_address"]!.AsArray().Select(n => n!.GetValue<string>()).ToArray();
        await exclusions.Length.Should().BeEqualTo(3);
        await exclusions.Contains("192.0.2.10/32").Should().BeTrue();
        await exclusions.Contains("2001:db8::10/128").Should().BeTrue();
        await exclusions.Contains("fd00::10/128").Should().BeTrue();
        await document["inbounds"]![0]!["strict_route"]!.GetValue<bool>().Should().BeTrue();
        await document["route"]!["rules"]![0]!["action"]!.GetValue<string>().Should().BeEqualTo("hijack-dns");
        var output = Environment.GetEnvironmentVariable("DRKOZOL_CONFIG_VALIDATION_OUTPUT");
        if (output is not null)
        {
            Directory.CreateDirectory(output);
            await File.WriteAllTextAsync(Path.Combine(output, "transport-exclusions.json"), document.ToJsonString());
        }
    }

    [Test]
    public async Task AmneziaTransportExclusionsSupportBothAddressFamilies()
    {
        var addresses = await AmneziaHost.EndpointAddresses("[Interface]\n[Peer]\nEndpoint = 192.0.2.10:51820\n[Peer]\nEndpoint = [2001:db8::10]:51820 # peer", CancellationToken.None);
        await addresses.Length.Should().BeEqualTo(2);
        await addresses.Any(a => a.ToString() == "2001:db8::10").Should().BeTrue();
    }

    [Test]
    public async Task TunnelRoutesBothAddressFamiliesAndHonorsLanAndDns()
    {
        var document = JsonNode.Parse(TunHost.Configuration(10808, new() { RouteLan = true }, "9.9.9.9"))!;
        await document["inbounds"]![0]!["address"]!.AsArray().Count.Should().BeEqualTo(2);
        await document["inbounds"]![0]!["dns_mode"]!.GetValue<string>().Should().BeEqualTo("hijack");
        await document["inbounds"]![0]!["dns_address"]![0]!.GetValue<string>().Should().BeEqualTo("172.29.255.2");
        await document["route"]!["rules"]![0]!["action"]!.GetValue<string>().Should().BeEqualTo("hijack-dns");
        await document["route"]!["rules"]!.AsArray().Count.Should().BeEqualTo(2);
        await document["dns"]!["servers"]![0]!["server"]!.GetValue<string>().Should().BeEqualTo("9.9.9.9");
        var normal = JsonNode.Parse(TunHost.Configuration(10808))!;
        await normal["route"]!["rules"]!.AsArray().Count.Should().BeEqualTo(3);
        await normal["inbounds"]![0]!["strict_route"]!.GetValue<bool>().Should().BeTrue();
    }

    [Test]
    public async Task SharedProxyRequiresAuthenticationAndUsesASeparatePort()
    {
        var preferences = new DesktopPreferences { ShareProxy = true, ProxyUser = "test-user", ProxyPassword = "test-password",
            ApplicationMode = "include", Applications = "browser.exe,player" };
        var json = TunHost.Configuration(10808, preferences, "https://dns.example/dns-query");
        var document = JsonNode.Parse(json)!;
        await document["inbounds"]!.AsArray().Count.Should().BeEqualTo(2);
        await document["inbounds"]![1]!["users"]![0]!["username"]!.GetValue<string>().Should().BeEqualTo("test-user");
        await document["route"]!["final"]!.GetValue<string>().Should().BeEqualTo("direct");
        await document["dns"]!["servers"]![0]!["domain_resolver"]!.GetValue<string>().Should().BeEqualTo("remote");
        var noTun = JsonNode.Parse(TunHost.Configuration(10808, preferences, enableTun: false))!;
        await noTun["inbounds"]!.AsArray().Count.Should().BeEqualTo(1);
        var invalid = false;
        try { TunHost.Configuration(10808, new() { ShareProxy = true }); } catch (ArgumentException) { invalid = true; }
        await invalid.Should().BeTrue();
        if (Environment.GetEnvironmentVariable("DRKOZOL_CONFIG_VALIDATION_OUTPUT") is { } directory)
        {
            Directory.CreateDirectory(directory);
            await File.WriteAllTextAsync(Path.Combine(directory, "shared-app-routing.json"), json);
            await File.WriteAllTextAsync(Path.Combine(directory, "default-tun.json"), TunHost.Configuration(10808));
            await File.WriteAllTextAsync(Path.Combine(directory, "sharing-without-tun.json"), TunHost.Configuration(10808, preferences, enableTun: false));
            await File.WriteAllTextAsync(Path.Combine(directory, "custom-dns.json"), TunHost.Configuration(10808, dns: "9.9.9.9"));
        }
    }

    [Test]
    public async Task SortingKeepsFailuresAndUnknownDelaysAfterHealthyServers()
    {
        var profiles = new[] { new ProfileItem { IndexId = "unknown", Remarks = "Zulu" },
            new ProfileItem { IndexId = "failed", Remarks = "Bravo" }, new ProfileItem { IndexId = "healthy", Remarks = "Alpha" } };
        var delays = new Dictionary<string, int> { ["failed"] = -1, ["healthy"] = 23 };
        await DesktopPreferences.OrderProfiles(profiles, delays, "ping").First().IndexId.Should().BeEqualTo("healthy");
        await DesktopPreferences.OrderProfiles(profiles, delays, "default").First().IndexId.Should().BeEqualTo("unknown");
        await DesktopPreferences.OrderProfiles(profiles, delays, "name").First().IndexId.Should().BeEqualTo("healthy");
    }

    [Test]
    public async Task SubscriptionReplacementRetainsReusedIdsAndRemovesStagingRows()
    {
        var database = ServiceLib.Helper.SQLiteHelper.Instance;
        database.CreateTable<ProfileItem>();
        var previous = new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = "previous-fixture", Remarks = "Previous" };
        var staging = new ProfileItem { IndexId = Guid.NewGuid().ToString("N"), Subid = "staging-fixture", Remarks = "Refreshed" };
        try
        {
            await database.InsertAsync(previous); await database.InsertAsync(staging);
            var refreshed = JsonUtils.DeepCopy(staging); refreshed.IndexId = previous.IndexId; refreshed.Subid = previous.Subid;
            await database.ReplaceSubscriptionProfilesAsync([refreshed], [previous, staging]);
            var selected = await database.TableAsync<ProfileItem>().Where(p => p.IndexId == previous.IndexId).FirstOrDefaultAsync();
            await selected.Remarks.Should().BeEqualTo("Refreshed");
            var leftover = await database.TableAsync<ProfileItem>().Where(p => p.IndexId == staging.IndexId).FirstOrDefaultAsync();
            await (leftover is null).Should().BeTrue();
        }
        finally { await database.DeleteAsync(previous); await database.DeleteAsync(staging); }
    }

    [Test]
    public async Task RoundRobinIncludesHealthChecksFallbackAndKeepalive()
    {
        var config = CoreConfigTestFactory.CreateConfig(); CoreConfigTestFactory.BindAppManagerConfig(config);
        var one = CoreConfigTestFactory.CreateVmessNode(ECoreType.Xray, "one");
        var two = CoreConfigTestFactory.CreateVmessNode(ECoreType.Xray, "two");
        var group = CoreConfigTestFactory.CreatePolicyGroupNode(ECoreType.Xray, "group", "Balancer", [one.IndexId, two.IndexId]);
        group.SetProtocolExtra(group.GetProtocolExtra() with { MultipleLoad = EMultipleLoad.RoundRobin });
        var context = CoreConfigTestFactory.CreateContext(config, group, ECoreType.Xray);
        context.AllProxiesMap[one.IndexId] = one; context.AllProxiesMap[two.IndexId] = two;
        var result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
        await result.Success.Should().BeTrue();
        var generated = JsonUtils.Deserialize<V2rayConfig>(result.Data!.ToString())!;
        var balancer = generated.routing.balancers!.First();
        await balancer.strategy!.type.Should().BeEqualTo("roundRobin");
        await generated.outbounds.Any(o => o.tag == balancer.fallbackTag).Should().BeTrue();
        await generated.observatory!.probeInterval.Should().BeEqualTo("15s");
        await generated.outbounds.First(o => o.protocol == "vmess").streamSettings.sockopt!.tcpUserTimeout.Should().BeEqualTo(30000);
    }

    [Test]
    public async Task BalancerHealthChecksUseTheProviderRuntimeProbeUrl()
    {
        var global = CoreConfigTestFactory.CreateConfig(); CoreConfigTestFactory.BindAppManagerConfig(global);
        var config = JsonUtils.DeepCopy(global);
        config.SpeedTestItem.SpeedPingTestUrl = "https://provider.example/health";
        foreach (var strategy in new[] { EMultipleLoad.RoundRobin, EMultipleLoad.LeastPing, EMultipleLoad.LeastLoad, EMultipleLoad.Fallback })
        {
            var one = CoreConfigTestFactory.CreateVmessNode(ECoreType.Xray, "one");
            var two = CoreConfigTestFactory.CreateVmessNode(ECoreType.Xray, "two");
            var group = CoreConfigTestFactory.CreatePolicyGroupNode(ECoreType.Xray, "group", "Balancer", [one.IndexId, two.IndexId]);
            group.SetProtocolExtra(group.GetProtocolExtra() with { MultipleLoad = strategy });
            var context = CoreConfigTestFactory.CreateContext(config, group, ECoreType.Xray);
            context.AllProxiesMap[one.IndexId] = one; context.AllProxiesMap[two.IndexId] = two;
            var result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
            await result.Success.Should().BeTrue();
            var generated = JsonUtils.Deserialize<V2rayConfig>(result.Data!.ToString())!;
            var url = generated.observatory?.probeUrl ?? generated.burstObservatory?.pingConfig?.destination;
            await url.Should().BeEqualTo(config.SpeedTestItem.SpeedPingTestUrl);
            await (global.SpeedTestItem.SpeedPingTestUrl != url).Should().BeTrue();
        }
    }

    [Test]
    public async Task DesktopUpdateIgnoresAndroidAssetsAndSelectsTheOwnedPlatformDownload()
    {
        var release = JsonNode.Parse("""
            {"tag_name":"v1.4.7","assets":[
            {"name":"DrKozol-1.4.7.apk","browser_download_url":"https://github.com/SpecterLogic/Drkozol/releases/download/v1.4.7/DrKozol-1.4.7.apk"},
            {"name":"DrKozol-1.4.7-windows-x64.zip","browser_download_url":"https://github.com/SpecterLogic/Drkozol/releases/download/v1.4.7/windows.zip"},
            {"name":"DrKozol-1.4.7-linux-amd64.deb","browser_download_url":"https://github.com/SpecterLogic/Drkozol/releases/download/v1.4.7/linux.deb"}]}
            """)!;
        await DrKozolController.FindDesktopUpdate(release, new Version(1, 4, 6), true)!.EndsWith("windows.zip").Should().BeTrue();
        await DrKozolController.FindDesktopUpdate(release, new Version(1, 4, 6), false)!.EndsWith("linux.deb").Should().BeTrue();
        await (DrKozolController.FindDesktopUpdate(release, new Version(1, 4, 7, 0), true) is null).Should().BeTrue();
        release["prerelease"] = true;
        await (DrKozolController.FindDesktopUpdate(release, new Version(1, 4, 6), true) is null).Should().BeTrue();
        release["prerelease"] = false;
        release["assets"]![1]!["browser_download_url"] = "https://example.org/unowned.zip";
        await (DrKozolController.FindDesktopUpdate(release, new Version(1, 4, 6), true) is null).Should().BeTrue();
    }

    [Test]
    public async Task AFailedDnsMemberDoesNotPreventHealthyBalancerMembersFromBootstrapping()
    {
        var config = CoreConfigTestFactory.CreateConfig();
        var group = CoreConfigTestFactory.CreatePolicyGroupNode(ECoreType.Xray, "group", "Balancer", ["one", "two"]);
        var context = CoreConfigTestFactory.CreateContext(config, group, ECoreType.Xray);
        var healthy = new ProfileItem { IndexId = "one", Address = "healthy.example" };
        var failed = new ProfileItem { IndexId = "two", Address = "failed.example" };
        context.AllProxiesMap["one"] = healthy; context.AllProxiesMap["two"] = failed;
        await TunHost.ResolveContextEndpoints(context, CancellationToken.None, (profile, _) =>
            profile.IndexId == "two" ? Task.FromException<ProfileItem>(new SocketException())
                : Task.FromResult(TunHost.WithResolvedEndpoint(profile, IPAddress.Parse("192.0.2.1"))));
        await context.AllProxiesMap["one"].Address.Should().BeEqualTo("192.0.2.1");
        await context.AllProxiesMap["two"].Address.Should().BeEqualTo("failed.example");
        await healthy.Address.Should().BeEqualTo("healthy.example");
        using var cancelled = new CancellationTokenSource(); cancelled.Cancel();
        var stopped = false;
        try { await TunHost.ResolveContextEndpoints(context, cancelled.Token); } catch (OperationCanceledException) { stopped = true; }
        await stopped.Should().BeTrue();
    }
}
