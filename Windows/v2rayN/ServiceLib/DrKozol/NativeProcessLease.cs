namespace ServiceLib.DrKozol;

internal static class NativeProcessLease
{
    private static readonly SemaphoreSlim ScriptGate = new(1, 1);
    // A user-owned supervisor can signal a capability-enabled child without running the GUI
    // as root. It also tears it down if the owning GUI dies, including an abrupt crash.
    private const string Supervisor = """
        #!/bin/sh
        owner="$1"
        shift
        child=''
        watcher=''
        cleanup() {
          trap - EXIT HUP INT TERM
          [ -z "$watcher" ] || kill "$watcher" 2>/dev/null
          [ -z "$child" ] || { kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; }
        }
        trap 'cleanup; exit 0' HUP INT TERM
        trap cleanup EXIT
        # POSIX background jobs receive /dev/null on fd 0 before their redirections.
        # Preserve the original pipe separately so the AWG host receives its JSON input.
        exec 3<&0
        "$@" <&3 3<&- &
        child=$!
        exec 3<&-
        (
          while kill -0 "$owner" 2>/dev/null; do sleep 1; done
          kill -TERM "$child" 2>/dev/null
        ) &
        watcher=$!
        wait "$child"
        result=$?
        child=''
        exit "$result"
        """;

    public static async Task Prepare(ProcessStartInfo start, CancellationToken token = default)
    {
        if (!OperatingSystem.IsLinux()) return;
        var script = Utils.GetConfigPath("drkozol-watch-core-v2.sh");
        await ScriptGate.WaitAsync(token);
        try { if (!File.Exists(script)) await File.WriteAllTextAsync(script, Supervisor, token); }
        finally { ScriptGate.Release(); }
        var executable = start.FileName;
        var arguments = start.ArgumentList.ToArray();
        start.FileName = "/bin/sh"; start.ArgumentList.Clear();
        start.ArgumentList.Add(script); start.ArgumentList.Add(Environment.ProcessId.ToString());
        start.ArgumentList.Add(executable); foreach (var argument in arguments) start.ArgumentList.Add(argument);
    }

    public static void RequestStop(Process process)
    {
        if (OperatingSystem.IsLinux()) SendSignal(process.Id, 15);
        else process.Kill(true);
    }

    [DllImport("libc", EntryPoint = "kill")]
    private static extern int SendSignal(int process, int signal);
}
