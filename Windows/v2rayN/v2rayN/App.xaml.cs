using v2rayN.Manager;
using v2rayN.Views;

namespace v2rayN;

/// <summary>
/// Interaction logic for App.xaml
/// </summary>
public partial class App
{
    public static EventWaitHandle ProgramStarted;
    private RegisteredWaitHandle? _activationWait;

    public App()
    {
        DispatcherUnhandledException += App_DispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;
        TaskScheduler.UnobservedTaskException += TaskScheduler_UnobservedTaskException;
    }

    /// <summary>
    /// Open only one process
    /// </summary>
    /// <param name="e"></param>
    protected override void OnStartup(StartupEventArgs e)
    {
        // The elevated replacement must not open the same settings/native-engine lease
        // until the ordinary instance has finished its asynchronous shutdown.
        if (e.Args.Contains(Global.RebootAs) && e.Args.FirstOrDefault(a => a.StartsWith("--wait-for-exit=")) is { } handoff
            && int.TryParse(handoff["--wait-for-exit=".Length..], out var ownerId) && ownerId != Environment.ProcessId)
        {
            try
            {
                using var owner = Process.GetProcessById(ownerId);
                if (!owner.WaitForExit(20000)) { Shutdown(); return; }
            }
            catch (ArgumentException) { } // The previous instance already exited.
        }
        var exePathKey = Utils.GetMd5(Utils.GetExePath());

        var rebootas = e.Args.Any(t => t == Global.RebootAs);
        ProgramStarted = new EventWaitHandle(false, EventResetMode.AutoReset, exePathKey, out var bCreatedNew);
        if (!rebootas && !bCreatedNew)
        {
            ProgramStarted.Set();
            Environment.Exit(0);
            return;
        }

        if (!AppManager.Instance.InitApp())
        {
            UI.Show($"Loading GUI configuration file is abnormal,please restart the application{Environment.NewLine}加载GUI配置文件异常,请重启应用");
            Environment.Exit(0);
            return;
        }

        AppManager.Instance.WindowDialog = new WindowDialog();

        AppManager.Instance.InitComponents();

        RxAppBuilder.CreateReactiveUIBuilder()
            .WithWpf()
            .BuildApp();

        base.OnStartup(e);

        if (e.Args.Contains("--smoke-test"))
        {
            _ = DrKozol.SmokeTests.RunAndExit();
            return;
        }

        var mainWindow = new DrKozol.DrKozolWindow();
        MainWindow = mainWindow;
        ShutdownMode = ShutdownMode.OnMainWindowClose;
        _activationWait = ThreadPool.RegisterWaitForSingleObject(ProgramStarted, (_, _) =>
            Dispatcher.BeginInvoke(() =>
            {
                if (MainWindow is not { } window) return;
                window.Show();
                if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
                window.Activate();
            }), null, Timeout.Infinite, false);
        mainWindow.Show();
    }

    private void App_DispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Logging.SaveLog("App_DispatcherUnhandledException", e.Exception);
        e.Handled = true;
    }

    private void CurrentDomain_UnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject != null)
        {
            Logging.SaveLog("CurrentDomain_UnhandledException", (Exception)e.ExceptionObject);
        }
    }

    private void TaskScheduler_UnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        Logging.SaveLog("TaskScheduler_UnobservedTaskException", e.Exception);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Logging.SaveLog("OnExit");
        _activationWait?.Unregister(null);
        base.OnExit(e);
        ProgramStarted?.Dispose();
    }
}
