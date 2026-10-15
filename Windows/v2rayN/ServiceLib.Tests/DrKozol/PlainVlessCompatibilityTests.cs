using ServiceLib.Tests.CoreConfig;

namespace ServiceLib.Tests.DrKozol;

[NotInParallel]
public class PlainVlessCompatibilityTests
{
    [Test]
    [Arguments("none", "none", true)]
    [Arguments("", "none", true)]
    [Arguments("tls", "none", false)]
    [Arguments("reality", "none", false)]
    [Arguments("none", "configured-encryption", false)]
    public async Task OnlyPlainVlessEnablesPerOutboundCompatibility(string security, string encryption, bool expected)
    {
        var node = VLESSFmt.Resolve("vless://00000000-0000-0000-0000-000000000001@server.example:443?type=tcp&headerType=http&host=example.org&path=%2Ftest&security=" + security + "&encryption=" + encryption, out _)!;
        node.CoreType = ECoreType.Xray;
        if (security == "reality") { node.PublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; node.Sni = "example.org"; node.Fingerprint = "chrome"; }
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.Xray); CoreConfigTestFactory.BindAppManagerConfig(config);
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.Xray);
        var result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
        await result.Success.Should().BeTrue();
        var generated = JsonUtils.Deserialize<V2rayConfig>(result.Data!.ToString())!;
        var outbound = generated.outbounds.First(o => o.protocol == "vless");
        await (outbound.drkozolAllowPlainVless == true).Should().BeEqualTo(expected);
        await outbound.settings.id.Should().BeEqualTo("00000000-0000-0000-0000-000000000001");
        await outbound.streamSettings.rawSettings!.header.type.Should().BeEqualTo("http");
        var request = JsonUtils.ParseJson(outbound.streamSettings.rawSettings.header.request!.ToString());
        await request["headers"]!["Host"]![0]!.GetValue<string>().Should().BeEqualTo("example.org");
        await request["path"]![0]!.GetValue<string>().Should().BeEqualTo("/test");
    }

    [Test]
    public async Task PreviousReleasedOutboundJsonRemainsReadableWithoutTheNewOption()
    {
        var previous = JsonUtils.Deserialize<Outbounds4Ray>("""{"tag":"proxy","protocol":"vless","settings":{"address":"server.example","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none"},"streamSettings":{"network":"raw"}}""")!;
        await (previous.drkozolAllowPlainVless is null).Should().BeTrue();
        await previous.settings.id.Should().BeEqualTo("00000000-0000-0000-0000-000000000001");
    }
}
