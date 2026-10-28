using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class RoutingProfileLinkTests
{
    [Test]
    public async Task RulesRoundTripPreservesActionsAndConditions()
    {
        var rules = RoutingProfileLink.FromFields("domain:direct.example\n192.0.2.0/24", "domain:proxy.example", "domain:blocked.example");
        var parsed = RoutingProfileLink.Parse(RoutingProfileLink.Export(rules));
        await parsed.Count.Should().BeEqualTo(4);
        await parsed.Select(r => r.OutboundTag).Distinct().Order().SequenceEqual(new[] { "block", "direct", "proxy" }).Should().BeTrue();
        await parsed.Any(r => r.Ip?.Contains("192.0.2.0/24") == true).Should().BeTrue();
        var advanced = RoutingProfileLink.Parse("""[{"domain":["domain:example.org"],"outboundTag":"block","port":"443","enabled":false}]""");
        await advanced.Single().Port.Should().BeEqualTo("443");
        await advanced.Single().Enabled.Should().BeFalse();
    }

    [Test]
    public async Task InvalidProfilesAreRejected()
    {
        foreach (var value in new[] { "", "[]", "{}", "null", "[null]", "drkozol://routing/add/!", """[{"outboundTag":"proxy"}]""", new string('x', 1_000_001) })
        {
            var rejected = false;
            try { RoutingProfileLink.Parse(value); }
            catch (Exception) { rejected = true; }
            await rejected.Should().BeTrue();
        }
    }
}
