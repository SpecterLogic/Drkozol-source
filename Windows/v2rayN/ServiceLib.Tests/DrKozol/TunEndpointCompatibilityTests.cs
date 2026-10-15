using System.Net;
using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class TunEndpointCompatibilityTests
{
    [Test]
    public async Task RuntimeEndpointCopyPreservesTheStoredProfileAndExplicitTlsIdentity()
    {
        var profile = new ProfileItem { Address = "server.example", Sni = "certificate.example", StreamSecurity = "tls", Password = "test-id", IndexId = "stable-id" };
        var copy = TunHost.WithResolvedEndpoint(profile, IPAddress.Parse("192.0.2.1"));
        await copy.Address.Should().BeEqualTo("192.0.2.1");
        await copy.Sni.Should().BeEqualTo("certificate.example");
        await copy.Password.Should().BeEqualTo(profile.Password);
        await copy.IndexId.Should().BeEqualTo(profile.IndexId);
        await profile.Address.Should().BeEqualTo("server.example");
    }

    [Test]
    [Arguments("", "server.example")]
    [Arguments("http.example", "http.example")]
    public async Task ImplicitTlsIdentitySurvivesEndpointResolution(string host, string expected)
    {
        var profile = new ProfileItem { Address = "server.example", StreamSecurity = "tls", Network = "ws" };
        profile.SetTransportExtra(new() { Host = host });
        var copy = TunHost.WithResolvedEndpoint(profile, IPAddress.Parse("192.0.2.1"));
        await copy.Sni.Should().BeEqualTo(expected);
        await profile.Sni.Should().BeNull();
        await copy.GetTransportExtra().Host.Should().BeEqualTo(host);
    }

    [Test]
    public async Task PlainVlessDoesNotAcquireTlsDuringBootstrap()
    {
        var profile = new ProfileItem { Address = "server.example", StreamSecurity = "none", ConfigType = EConfigType.VLESS };
        var copy = TunHost.WithResolvedEndpoint(profile, IPAddress.Parse("192.0.2.1"));
        await copy.StreamSecurity.Should().BeEqualTo("none");
        await copy.Sni.Should().BeNull();
    }

    [Test]
    public async Task LiteralAddressDoesNotNeedNetworkResolution()
    {
        var profile = new ProfileItem { Address = "192.0.2.1" };
        using var canceled = new CancellationTokenSource(); canceled.Cancel();
        await ReferenceEquals(await TunHost.ResolveEndpoint(profile, canceled.Token), profile).Should().BeTrue();
    }
}
