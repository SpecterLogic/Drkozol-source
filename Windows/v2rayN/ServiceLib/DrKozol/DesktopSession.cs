namespace ServiceLib.DrKozol;

internal static class DesktopSession
{
    [StructLayout(LayoutKind.Sequential)]
    private struct PowerStatus
    {
        public byte AcLineStatus, BatteryFlag, BatteryPercent, SystemStatus;
        public uint BatteryLife, FullBatteryLife;
    }
    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out PowerStatus status);

    public static Task<bool> QuietPowerAvailable(CancellationToken token) => Task.Run(() => {
        if (OperatingSystem.IsWindows()) return GetSystemPowerStatus(out var status) && status.AcLineStatus == 1;
        if (!OperatingSystem.IsLinux()) return false;
        try
        {
            var entries = Directory.GetDirectories("/sys/class/power_supply");
            var battery = false;
            foreach (var entry in entries)
            {
                var type = File.ReadAllText(Path.Combine(entry, "type")).Trim();
                if (type == "Battery") battery = true;
                else if (File.Exists(Path.Combine(entry, "online")) && File.ReadAllText(Path.Combine(entry, "online")).Trim() == "1") return true;
            }
            return !battery;
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }, token);

    public static bool OtherVpnActive()
    {
        try { return System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces().Any(adapter =>
        adapter.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up
        && (adapter.NetworkInterfaceType is System.Net.NetworkInformation.NetworkInterfaceType.Tunnel or System.Net.NetworkInformation.NetworkInterfaceType.Ppp
            || new[] { "wireguard", "vpn", "tailscale", "warp", "amnezia", "tap", "tun" }
                .Any(word => (adapter.Name + " " + adapter.Description).Contains(word, StringComparison.OrdinalIgnoreCase)))); }
        catch (System.Net.NetworkInformation.NetworkInformationException) { return true; }
    }

    // logind reports the session's lock state across desktops without running the GUI as root.
    public static async Task<bool?> IsLocked(CancellationToken token)
    {
        var session = Environment.GetEnvironmentVariable("XDG_SESSION_ID");
        if (string.IsNullOrWhiteSpace(session)) return null;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(2));
        using var process = new Process { StartInfo = new ProcessStartInfo("loginctl") {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true } };
        foreach (var item in new[] { "show-session", session, "--property=LockedHint", "--value" }) process.StartInfo.ArgumentList.Add(item);
        try
        {
            if (!process.Start()) return null;
            var output = process.StandardOutput.ReadToEndAsync(deadline.Token);
            var error = process.StandardError.ReadToEndAsync(deadline.Token);
            await process.WaitForExitAsync(deadline.Token);
            await error;
            return process.ExitCode == 0 ? (await output).Trim() switch { "yes" => true, "no" => false, _ => null } : null;
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested) { return null; }
        catch (System.ComponentModel.Win32Exception) { return null; }
        finally { try { if (process.Id > 0 && !process.HasExited) process.Kill(true); } catch (InvalidOperationException) { } }
    }
}
