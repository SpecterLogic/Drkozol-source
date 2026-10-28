using ServiceLib.Tests.CoreConfig;

namespace ServiceLib.Tests.DrKozol;

[NotInParallel]
public class DesktopIpPreferenceTests
{
    [Test]
    [Arguments("ipv4", false)]
    [Arguments("ipv6", true)]
    public async Task IpPreferenceReachesNativeOutboundWithoutRemovingDualStackFallback(string preference, bool preferV6)
    {
        var node = VLESSFmt.Resolve("vless://00000000-0000-0000-0000-000000000001@server.example:443?type=tcp&security=tls&encryption=none", out _)!;
        node.CoreType = ECoreType.Xray;
        var config = CoreConfigTestFactory.CreateConfig(); config.DrKozol.IpPreference = preference;
        CoreConfigTestFactory.BindAppManagerConfig(config);
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.Xray);
        var result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
        await result.Success.Should().BeTrue();
        var generated = JsonUtils.Deserialize<V2rayConfig>(result.Data!.ToString())!;
        var outbound = generated.outbounds.First(o => o.protocol == "vless");
        await outbound.streamSettings.sockopt!.domainStrategy.Should().BeEqualTo("UseIP");
        await outbound.streamSettings.sockopt.happyEyeballs!.prioritizeIPv6.Should().BeEqualTo(preferV6);
        await outbound.streamSettings.sockopt.happyEyeballs.interleave.Should().BeEqualTo(2);
        await outbound.settings.address.ToString().Should().BeEqualTo("server.example");
    }

    [Test]
    public async Task PreviousDesktopSettingsDefaultToAutomaticIpPreference()
    {
        var previous = JsonUtils.Deserialize<ServiceLib.DrKozol.DesktopPreferences>("""{"AutoUpdate":true,"UpdateMinutes":720}""")!;
        await previous.IpPreference.Should().BeEqualTo("auto");
        await previous.UpdateMinutes.Should().BeEqualTo(720);
    }
}
