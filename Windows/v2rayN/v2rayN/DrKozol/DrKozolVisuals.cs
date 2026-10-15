using System.Collections.Concurrent;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using ServiceLib.DrKozol;

namespace v2rayN.DrKozol;

// WPF owns frozen image sources and vector controls; protocol and country data stay shared.
internal static class DrKozolVisuals
{
    private static readonly ConcurrentDictionary<string, BitmapSource> Flags = new();
    private static readonly Dictionary<string, string> Paths = new()
    {
        ["home"] = "M12,3 L3,10 L3,12 L6,12 L6,21 L10,21 L10,15 L14,15 L14,21 L18,21 L18,12 L21,12 L21,10 Z",
        ["servers"] = "M3,4 L21,4 L21,10 L3,10 Z M3,14 L21,14 L21,20 L3,20 Z M6,7 L7,7 M6,17 L7,17 M16,7 L18,7 M16,17 L18,17",
        ["settings"] = "M9,3 L15,3 L16,6 L19,7 L21,11 L19,14 L19,17 L15,20 L12,19 L9,20 L5,17 L5,14 L3,11 L5,7 L8,6 Z M12,8 A4,4 0 1 0 12,16 A4,4 0 1 0 12,8",
        ["chevron"] = "M9,6 L15,12 L9,18",
        ["collapse"] = "M6,15 L12,9 L18,15",
        ["down"] = "M6,9 L12,15 L18,9",
        ["refresh"] = "M19,8 A8,8 0 1 0 20,15 M19,3 L19,9 L13,9",
        ["ping"] = "M4,18 A9,9 0 0 1 20,18 M12,15 L17,8 M8,17 L16,17",
        ["more"] = "M5,12 L5.1,12 M12,12 L12.1,12 M19,12 L19.1,12",
        ["plus"] = "M12,5 L12,19 M5,12 L19,12",
        ["paste"] = "M8,5 L5,5 L5,21 L19,21 L19,5 L16,5 M8,3 L16,3 L16,7 L8,7 Z",
        ["qr"] = "M3,3 L9,3 L9,9 L3,9 Z M15,3 L21,3 L21,9 L15,9 Z M3,15 L9,15 L9,21 L3,21 Z M14,14 L18,14 L18,18 L21,18 L21,21 L14,21 Z M6,6 L6.1,6 M18,6 L18.1,6 M6,18 L6.1,18 M21,12 L21,14 M11,11 L13,11 L13,14",
        ["globe"] = "M12,3 A9,9 0 1 0 12,21 A9,9 0 1 0 12,3 M3,12 L21,12 M5,7 L19,7 M5,17 L19,17 M12,3 C5,7 5,17 12,21 C19,17 19,7 12,3",
        ["search"] = "M10,3 A7,7 0 1 0 10,17 A7,7 0 1 0 10,3 M15,15 L21,21",
        ["language"] = "M12,3 A9,9 0 1 0 12,21 A9,9 0 1 0 12,3 M3,12 L21,12 M12,3 C5,7 5,17 12,21 C19,17 19,7 12,3",
        ["palette"] = "M12,3 C6,3 3,7 3,12 C3,18 7,21 12,21 L15,21 C18,21 18,17 16,16 C14,15 16,13 19,13 C23,13 21,3 12,3 Z M7,8 L7.1,8 M12,6 L12.1,6 M17,8 L17.1,8 M7,13 L7.1,13",
        ["connection"] = "M3,7 C8,2 16,2 21,7 M6,11 C10,8 14,8 18,11 M9,15 C11,13 13,13 15,15 M12,19 L12.1,19",
        ["lock"] = "M7,10 L7,7 A5,5 0 0 1 17,7 L17,10 M5,10 L19,10 L19,21 L5,21 Z M12,14 L12,17",
        ["info"] = "M12,3 A9,9 0 1 0 12,21 A9,9 0 1 0 12,3 M12,10 L12,17 M12,7 L12.1,7",
        ["shield"] = "M12,2 L21,6 V12 C21,18 17,21 12,23 C7,21 3,18 3,12 V6 Z",
        ["power"] = "M12,2 L12,11 M6,5 A9,9 0 1 0 18,5",
        ["minimize"] = "M5,12 L19,12",
        ["maximize"] = "M5,5 L19,5 L19,19 L5,19 Z",
        ["restore"] = "M8,5 L19,5 L19,16 M5,8 L16,8 L16,19 L5,19 Z",
        ["close"] = "M6,6 L18,18 M18,6 L6,18"
    };

    internal static UIElement Icon(string name, Brush brush, double size = 16)
    {
        return new System.Windows.Shapes.Path
        {
            Data = Geometry.Parse(Paths.GetValueOrDefault(name, Paths["globe"])),
            Stroke = brush, StrokeThickness = name == "more" ? 3 : 1.8,
            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round, Fill = Brushes.Transparent,
            Width = size, Height = size, Stretch = Stretch.Uniform,
            IsHitTestVisible = false, SnapsToDevicePixels = true
        };
    }

    internal static Task PrepareFlags(CancellationToken cancellation) => Task.Run(() =>
    {
        for (var a = 'A'; a <= 'Z'; a++)
            for (var b = 'A'; b <= 'Z'; b++)
            {
                cancellation.ThrowIfCancellationRequested();
                var code = $"{a}{b}";
                if (Flags.ContainsKey(code) || ServerPresentation.FlagPng(code) is not { Length: > 0 } bytes) continue;
                using var stream = new MemoryStream(bytes);
                var bitmap = new BitmapImage();
                bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze();
                Flags.TryAdd(code, bitmap);
            }
    }, cancellation);

    internal static UIElement Flag(ProfileItem profile, Brush muted)
    {
        var country = ServerPresentation.CountryCode(profile);
        return country is not null && Flags.TryGetValue(country, out var image)
            ? new Image { Source = image, Width = 21, Height = 16, Stretch = Stretch.Uniform, IsHitTestVisible = false }
            : Icon("globe", muted, 15);
    }
}
