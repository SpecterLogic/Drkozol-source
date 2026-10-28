using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class PresentationTests
{
    [Test]
    [Arguments("Forest")]
    [Arguments("Maroon")]
    [Arguments("Purple")]
    [Arguments("Aurora")]
    [Arguments("Sandstone")]
    [Arguments("Graphite")]
    public async Task Palette_RetainsModeAndBlackTitleBar(string palette)
    {
        var dark = DesktopAppearance.For("Dark", palette); var light = DesktopAppearance.For("Light", palette);
        await dark.IsDark.Should().BeTrue(); await light.IsDark.Should().BeFalse();
        await dark.TitleBar.Should().BeEqualTo("#050505"); await light.TitleBar.Should().BeEqualTo("#050505");
        await (dark.Background != light.Background).Should().BeTrue();
    }

    [Test]
    public async Task FreshPaletteUsesOceanAndUnknownPaletteRetainsForestFallback()
    {
        await DesktopAppearance.For("Dark", null).Should().BeEqualTo(DesktopAppearance.For("Dark", "Ocean"));
        await DesktopAppearance.For("Light", "unknown").Should().BeEqualTo(DesktopAppearance.For("Light", "Forest"));
    }

    [Test]
    public async Task Flags_FollowConfigAndUnknownCountryIsNotGuessed()
    {
        var profile = new ProfileItem { Remarks = "CDN🇩🇪" };
        await ServerPresentation.CountryCode(profile).Should().BeEqualTo("DE"); await ServerPresentation.DisplayName(profile).Should().BeEqualTo("CDN");
        profile.Remarks = "Sweden"; await ServerPresentation.CountryCode(profile).Should().BeEqualTo("SE");
        profile.Remarks = "CDN"; await (ServerPresentation.CountryCode(profile) is null).Should().BeTrue();
        profile.Remarks = "Canadamex"; await (ServerPresentation.CountryCode(profile) is null).Should().BeTrue();
        await (ServerPresentation.FlagPng("DE")?.Length > 0).Should().BeTrue();
        await (ServerPresentation.FlagPng("../secret") is null).Should().BeTrue();
    }

    [Test]
    public async Task ProtocolLabels_FollowActualTransportAndSecurity()
    {
        var profile = new ProfileItem { ConfigType = EConfigType.VLESS, Network = "xhttp", StreamSecurity = "reality" };
        await ServerPresentation.Protocol(profile).Should().BeEqualTo("VLESS"); await ServerPresentation.Transport(profile).Should().BeEqualTo("XHTTP");
        await ServerPresentation.Security(profile).Should().BeEqualTo("REALITY");
        profile.ConfigType = EConfigType.SOCKS; profile.Network = ""; await ServerPresentation.Protocol(profile).Should().BeEqualTo("SOCKS5");
        await ServerPresentation.Transport(profile).Should().BeEqualTo("TCP");
        profile.ConfigType = EConfigType.VLESS; profile.Network = "raw";
        await ServerPresentation.Transport(profile).Should().BeEqualTo("TCP");
        profile.ConfigType = EConfigType.WireGuard;
        await ServerPresentation.Transport(profile).Should().BeEqualTo("UDP");
        profile.ConfigType = EConfigType.Custom; profile.AmneziaConfig = "[Interface]";
        await ServerPresentation.Transport(profile).Should().BeEqualTo("UDP");
    }

    [Test]
    public async Task AccountInfoRows_CannotReplaceRealServerAndLegitimateLocalSocksIsRetained()
    {
        var profile = new ProfileItem { ConfigType = EConfigType.SOCKS, Address = "127.0.0.1", Port = 1080, Remarks = "📊10GB|⌛29روز" };
        await ServerPresentation.IsAccountInfo(profile).Should().BeTrue();
        profile.Remarks = "Local SOCKS 10GB"; await ServerPresentation.IsAccountInfo(profile).Should().BeFalse();
        profile.Remarks = "29 days development proxy"; await ServerPresentation.IsAccountInfo(profile).Should().BeFalse();
        profile.Remarks = "📊10GB|⌛29روز"; profile.Address = "203.0.113.10";
        await ServerPresentation.IsAccountInfo(profile).Should().BeFalse();
        profile.Address = "127.0.0.1"; profile.ConfigType = EConfigType.VLESS;
        await ServerPresentation.IsAccountInfo(profile).Should().BeFalse();
    }

    [Test]
    public async Task AmneziaNamesRetainTheImportedTitleAndCountryFlag()
    {
        var name = AmneziaHost.DisplayName("vpn://encoded#%F0%9F%87%A9%F0%9F%87%AA%20My%20server");
        await name.Should().BeEqualTo("🇩🇪 My server");
        await ServerPresentation.CountryCode(new ProfileItem { Remarks = name }).Should().BeEqualTo("DE");
        await AmneziaHost.DisplayName("awg://encoded#").Should().BeEqualTo("AmneziaWG");
        await AmneziaHost.DisplayName("[Interface]").Should().BeEqualTo("AmneziaWG");
    }
}
