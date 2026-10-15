namespace ServiceLib.DrKozol;

// GNOME settings belong to the desktop user. Capture/restore each changed key rather than
// clearing somebody else's proxy on disconnect. TUN remains available on other desktops.
internal sealed class LinuxProxySnapshot
{
    private sealed record Value(string Schema, string Key, string Before, string Applied);
    private sealed record Lease(int Owner, List<Value> Values);
    private readonly List<Value> _values;
    private LinuxProxySnapshot(List<Value> values) => _values = values;
    private static string LeasePath => Utils.GetConfigPath("DrKozol-gnome-proxy-lease.json");

    public static async Task<LinuxProxySnapshot> Apply(int port, CancellationToken token)
    {
        var values = new List<Value>();
        foreach (var protocol in new[] { "http", "https", "ftp", "socks" })
        {
            var schema = "org.gnome.system.proxy." + protocol;
            values.Add(new(schema, "host", await Command(["get", schema, "host"], token), "'127.0.0.1'"));
            values.Add(new(schema, "port", await Command(["get", schema, "port"], token), port.ToString()));
        }
        values.Add(new("org.gnome.system.proxy", "ignore-hosts", await Command(["get", "org.gnome.system.proxy", "ignore-hosts"], token), "['localhost', '127.0.0.0/8', '::1']"));
        values.Add(new("org.gnome.system.proxy", "mode", await Command(["get", "org.gnome.system.proxy", "mode"], token), "'manual'"));
        var snapshot = new LinuxProxySnapshot(values);
        await File.WriteAllTextAsync(LeasePath, JsonSerializer.Serialize(new Lease(Environment.ProcessId, values)), token);
        if (OperatingSystem.IsLinux()) File.SetUnixFileMode(LeasePath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        try { foreach (var value in values) await Command(["set", value.Schema, value.Key, value.Applied], token); }
        catch { await snapshot.Restore(); throw; }
        return snapshot;
    }

    public async Task Restore()
    {
        var complete = true;
        foreach (var value in _values.AsEnumerable().Reverse())
            try
            {
                if (await Command(["get", value.Schema, value.Key], CancellationToken.None) == value.Applied)
                    await Command(["set", value.Schema, value.Key, value.Before], CancellationToken.None);
            }
            catch (Exception) { complete = false; }
        if (complete && File.Exists(LeasePath)) File.Delete(LeasePath);
    }

    public static async Task Recover()
    {
        if (!OperatingSystem.IsLinux() || !File.Exists(LeasePath)) return;
        try
        {
            var lease = JsonSerializer.Deserialize<Lease>(await File.ReadAllTextAsync(LeasePath));
            if (lease is null) return;
            try { if (!Process.GetProcessById(lease.Owner).HasExited) return; } catch (ArgumentException) { }
            await new LinuxProxySnapshot(lease.Values).Restore();
        }
        catch (Exception) { /* Keep the user-owned lease if a desktop session is unavailable. */ }
    }

    private static async Task<string> Command(string[] args, CancellationToken token)
    {
        var start = new ProcessStartInfo("gsettings") { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start) ?? throw new IOException();
        var output = process.StandardOutput.ReadToEndAsync(token); var error = process.StandardError.ReadToEndAsync(token);
        try { await process.WaitForExitAsync(token).WaitAsync(TimeSpan.FromSeconds(8), token); }
        catch { if (!process.HasExited) process.Kill(true); throw; }
        await error;
        if (process.ExitCode != 0) throw new PlatformNotSupportedException("GNOME proxy settings unavailable");
        return (await output).Trim();
    }
}
