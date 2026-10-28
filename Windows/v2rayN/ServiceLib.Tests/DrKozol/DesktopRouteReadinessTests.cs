using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class DesktopRouteReadinessTests
{
    [Test]
    public async Task TransientFailureAndIsolatedSuccessDoNotMarkTheRouteReady()
    {
        var attempts = 0;
        await DrKozolController.WaitForUsableRoute(_ => {
            attempts++;
            if (attempts == 1) throw new System.Net.Http.HttpRequestException();
            return Task.FromResult(attempts != 3);
        }, CancellationToken.None);
        await attempts.Should().BeEqualTo(5);
    }

    [Test]
    public async Task UnavailableRoutesHonorTheStartupDeadline()
    {
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        var cancelled = false;
        try { await DrKozolController.WaitForUsableRoute(_ => Task.FromResult(false), cancellation.Token); }
        catch (OperationCanceledException) { cancelled = true; }
        await cancelled.Should().BeTrue();
    }
}
