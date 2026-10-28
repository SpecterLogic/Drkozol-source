using v2rayN.Desktop.Common;
using v2rayN.Desktop.Manager;

namespace v2rayN.Desktop;

internal class Program
{
    public static EventWaitHandle ProgramStarted;
    private static ServiceLib.DrKozol.DesktopInstance? _linuxInstance;

    internal static void ListenForActivation(Action activate) => _linuxInstance?.Listen(activate);

    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't initialized
    // yet and stuff might break.
    [STAThread]
    public static void Main(string[] args)
    {
        try
        {
            if (OnStartup(args) == false) return;
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        }
        finally { _linuxInstance?.Dispose(); ProgramStarted?.Dispose(); }
    }

    private static bool OnStartup(string[]? Args)
    {
        if (OperatingSystem.IsLinux())
        {
            // Match InitApp's read-only-install fallback before choosing the shared lease identity.
            if (Environment.GetEnvironmentVariable(Global.LocalAppData) != "1" && !Utils.HasWritePermission())
                Environment.SetEnvironmentVariable(Global.LocalAppData, "1", EnvironmentVariableTarget.Process);
            var identity = Utils.GetMd5(Utils.StartupPath());
            _linuxInstance = new ServiceLib.DrKozol.DesktopInstance("DrKozol.Desktop." + identity);
            if (!_linuxInstance.IsPrimary)
            {
                _linuxInstance.ActivatePrimary().GetAwaiter().GetResult();
                return false;
            }
        }
        else if (Utils.IsWindows())
        {
            var exePathKey = Utils.GetMd5(Utils.GetExePath());
            var rebootas = (Args ?? []).Any(t => t == Global.RebootAs);
            ProgramStarted = new EventWaitHandle(false, EventResetMode.AutoReset, exePathKey, out var bCreatedNew);
            if (!rebootas && !bCreatedNew)
            {
                ProgramStarted.Set();
                return false;
            }
        }
        else
        {
            _ = new Mutex(true, "DrKozol.Desktop", out var bOnlyOneInstance);
            if (!bOnlyOneInstance)
            {
                return false;
            }
        }

        if (!AppManager.Instance.InitApp())
        {
            return false;
        }

        AppManager.Instance.WindowDialog = new WindowDialog();
        return true;
    }

    // Avalonia configuration, don't remove; also used by visual designer.
    public static AppBuilder BuildAvaloniaApp()
    {
        var builder = AppBuilder.Configure<App>()
           .UsePlatformDetect()
           //.WithInterFont()
           .WithFontByDefault()
#if DEBUG
           .WithDeveloperTools()
#endif
           .LogToTrace()
           .UseReactiveUI(_ => { });

        if (OperatingSystem.IsMacOS())
        {
            var showInDock = Design.IsDesignMode || AppManager.Instance.Config.UiItem.MacOSShowInDock;
            builder = builder.With(new MacOSPlatformOptions { ShowInDock = showInDock });
        }

        return builder;
    }
}
