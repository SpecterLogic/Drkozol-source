using System.IO.Pipes;

namespace ServiceLib.DrKozol;

/// <summary>Owns the GUI process lease and same-user activation IPC, independently of native engine leases.</summary>
public sealed class DesktopInstance : IDisposable
{
    private readonly Mutex _mutex;
    private readonly string _pipeName;
    private readonly CancellationTokenSource _lifetime = new();
    private Task? _listener;
    private bool _disposed;
    public bool IsPrimary { get; }

    public DesktopInstance(string name)
    {
        _pipeName = name;
        _mutex = new Mutex(true, name, out var created);
        if (created) IsPrimary = true;
        else
        {
            try { IsPrimary = _mutex.WaitOne(0); }
            catch (AbandonedMutexException) { IsPrimary = true; }
        }
    }

    public void Listen(Action activate)
    {
        if (!IsPrimary || _disposed || _listener is not null) throw new InvalidOperationException();
        _listener = ListenCore(activate);
    }

    private async Task ListenCore(Action activate)
    {
        while (!_lifetime.IsCancellationRequested)
        {
            try
            {
                using var server = new NamedPipeServerStream(_pipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(_lifetime.Token).ConfigureAwait(false);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
                timeout.CancelAfter(TimeSpan.FromSeconds(2));
                var message = new byte[1];
                if (await server.ReadAsync(message, timeout.Token).ConfigureAwait(false) == 1 && message[0] == 1 && !_lifetime.IsCancellationRequested)
                    activate();
            }
            catch (OperationCanceledException) { }
            catch (IOException error)
            {
                Logging.SaveLog("DrKozol desktop activation listener", new IOException(error.GetType().Name));
                try { await Task.Delay(100, _lifetime.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { }
            }
        }
    }

    public async Task<bool> ActivatePrimary()
    {
        if (IsPrimary || _disposed) return false;
        try
        {
            using var client = new NamedPipeClientStream(".", _pipeName, PipeDirection.Out, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            await client.ConnectAsync(timeout.Token).ConfigureAwait(false);
            await client.WriteAsync(new byte[] { 1 }, timeout.Token).ConfigureAwait(false);
            await client.FlushAsync(timeout.Token).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException) { return false; }
        catch (IOException error)
        {
            Logging.SaveLog("DrKozol desktop activation request", new IOException(error.GetType().Name));
            return false;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _lifetime.Cancel();
        _listener?.GetAwaiter().GetResult();
        _lifetime.Dispose();
        if (IsPrimary) _mutex.ReleaseMutex();
        _mutex.Dispose();
    }
}
