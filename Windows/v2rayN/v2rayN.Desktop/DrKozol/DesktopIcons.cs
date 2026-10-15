using ShapePath = Avalonia.Controls.Shapes.Path;

namespace v2rayN.Desktop.DrKozol;

// Project-owned vector glyphs keep navigation sharp at every desktop scale.
internal static class DesktopIcons
{
    public static ShapePath Draw(string name, IBrush brush, double size = 18) => new()
    {
        Data = Geometry.Parse(name switch
        {
            "home" => "M3 11L12 3L21 11M5 10V21H10V15H14V21H19V10",
            "servers" => "M3 4H21V10H3ZM3 14H21V20H3ZM6 7H7M6 17H7M17 7H19M17 17H19",
            "settings" => "M10 2H14L15 6L18 7L21 6L23 10L20 13V16L22 19L19 22L16 20H13L10 22L7 19L8 16L6 13L2 12V8L6 7L8 4ZM12 8A5 5 0 1 0 12 18A5 5 0 1 0 12 8",
            "chevron" => "M8 4L16 12L8 20",
            "down" => "M4 8L12 16L20 8",
            "up" => "M4 16L12 8L20 16",
            "refresh" => "M20 10A8 8 0 1 0 20 16M20 3V10H13",
            "ping" => "M4 19A10 10 0 1 1 20 19M4 19H20M12 15L18 6M6 12L7 13M12 5V7M17 12L18 13",
            "plus" => "M12 4V20M4 12H20",
            "paste" => "M8 4H4V21H20V4H16M8 2H16V7H8Z",
            "qr" => "M2 2H9V9H2ZM15 2H22V9H15ZM2 15H9V22H2ZM15 15H18V18H15ZM20 15H22V22H15V20M5 5H6V6H5M18 5H19V6H18M5 18H6V19H5",
            "search" => "M10 3A7 7 0 1 0 10 17A7 7 0 1 0 10 3M15 15L22 22",
            "globe" => "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2M2 12H22M12 2C6 8 6 16 12 22C18 16 18 8 12 2",
            "shield" => "M12 2L21 6V12C21 18 17 21 12 23C7 21 3 18 3 12V6Z",
            "power" => "M12 2V11M6 5A9 9 0 1 0 18 5",
            "close" => "M5 5L19 19M19 5L5 19",
            "minimize" => "M4 12H20",
            "maximize" => "M4 4H20V20H4Z",
            "more" => "M3 12H5M11 12H13M19 12H21",
            "theme" => "M12 2A10 10 0 1 0 12 22H16A3 3 0 0 0 16 16H15A2 2 0 0 1 15 12H17A5 5 0 0 0 12 2M6 10H7M10 6H11M15 6H16",
            "wifi" => "M2 8Q12 0 22 8M6 12Q12 7 18 12M9 16Q12 13 15 16M11 20H13",
            _ => "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2M12 7V13M12 17H12.1"
        }),
        Stroke = brush, StrokeThickness = name == "power" ? 2.2 : 1.8,
        StrokeLineCap = PenLineCap.Round, StrokeJoin = PenLineJoin.Round,
        Stretch = Stretch.Uniform, Width = size, Height = size,
        HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center
    };
}
