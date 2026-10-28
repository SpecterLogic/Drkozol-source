using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class DesktopInstanceTests
{
    [Test]
    public async Task ExistingInstanceReceivesActivationAndReleasesItsLeaseOnExit()
    {
        var name = "DrKozol.Test." + Guid.NewGuid().ToString("N");
        using var stop = new ManualResetEventSlim();
        var ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var activated = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        Exception? failure = null;
        var owner = new Thread(() =>
        {
            try
            {
                using var primary = new DesktopInstance(name);
                if (!primary.IsPrimary) throw new InvalidOperationException("First instance must own the lease");
                primary.Listen(() => activated.TrySetResult(true));
                ready.TrySetResult(true);
                stop.Wait(TimeSpan.FromSeconds(15));
            }
            catch (Exception error) { failure = error; ready.TrySetException(error); }
        }) { IsBackground = true };
        owner.Start();
        try
        {
            await ready.Task.WaitAsync(TimeSpan.FromSeconds(8));
            using var second = new DesktopInstance(name);
            await second.IsPrimary.Should().BeFalse();
            await (await second.ActivatePrimary()).Should().BeTrue();
            await (await activated.Task.WaitAsync(TimeSpan.FromSeconds(8))).Should().BeTrue();
        }
        finally { stop.Set(); owner.Join(TimeSpan.FromSeconds(8)); }
        await owner.IsAlive.Should().BeFalse();
        if (failure is not null) throw failure;
        using var reopened = new DesktopInstance(name);
        if (!reopened.IsPrimary) throw new InvalidOperationException("Lease was not released");
    }

    [Test]
    public void PrimaryCanCloseBeforeTheListenerStarts()
    {
        var name = "DrKozol.Test." + Guid.NewGuid().ToString("N");
        using (var first = new DesktopInstance(name))
            if (!first.IsPrimary) throw new InvalidOperationException();
        using var reopened = new DesktopInstance(name);
        if (!reopened.IsPrimary) throw new InvalidOperationException();
        reopened.Dispose();
        reopened.Dispose();
    }
}
