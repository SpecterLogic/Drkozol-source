namespace ServiceLib.DrKozol;

public sealed record DesktopPalette(string Background, string Panel, string TitleBar, string Sidebar,
    string Accent, string Text, string Muted, string Border, bool IsDark);

public static class DesktopAppearance
{
    public static Task<bool> ReadSystemDarkTheme() => Task.Run(() => {
        if (!OperatingSystem.IsWindows()) return true;
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("AppsUseLightTheme") is not int value || value == 0;
    });
    public static readonly string[] Palettes = ["Ocean", "Forest", "Maroon", "Purple", "Sunset", "OLED", "Japan", "Anime", "Aurora", "Sandstone", "Graphite"];
    public static DesktopPalette For(string? themeMode, string? colorName)
    {
        var dark = !string.Equals(themeMode, "Light", StringComparison.OrdinalIgnoreCase);
        var color = colorName?.ToLowerInvariant();
        return (color, dark) switch
        {
            ("aurora", true) => new("#071C1B", "#102D2B", "#050505", "#092220", "#88E5C8", "#E5FCF4", "#A0C5B9", "#34594E", true),
            ("aurora", false) => new("#F0FAF5", "#FFFFFF", "#050505", "#E1F2EA", "#00664D", "#10352C", "#486D60", "#B5D4C7", false),
            ("sandstone", true) => new("#211B14", "#30291E", "#050505", "#231E16", "#E8C585", "#FCF2DC", "#C3B49B", "#5A4D37", true),
            ("sandstone", false) => new("#FAF5EB", "#FFFFFF", "#050505", "#F0E7D5", "#785313", "#332A1D", "#72624B", "#D8CAB0", false),
            ("graphite", true) => new("#13171C", "#20262E", "#050505", "#171D25", "#AFC9ED", "#F0F4FA", "#ADB9CA", "#3D4A5B", true),
            ("graphite", false) => new("#F2F5F9", "#FFFFFF", "#050505", "#E6ECF4", "#355A87", "#202B38", "#596A7D", "#C1CEDD", false),
            (null or "ocean", true) => new("#061923", "#102936", "#050505", "#0B202B", "#70D8EE", "#E4F8FC", "#92B6C2", "#274651", true),
            (null or "ocean", false) => new("#EFFAFF", "#FFFFFF", "#050505", "#E0F0F7", "#006780", "#082C37", "#496C79", "#B8D2DC", false),
            ("sunset", true) => new("#25130D", "#382017", "#050505", "#21120C", "#FFBC8C", "#FFF0E6", "#C8A99A", "#63412D", true),
            ("sunset", false) => new("#FFF4E8", "#FFFFFF", "#050505", "#F7E6D4", "#99501B", "#382015", "#7B6252", "#DFC8B3", false),
            ("oled", _) => new("#000000", "#101010", "#000000", "#080808", "#B9F3CB", "#F3F3F3", "#ACB9B0", "#29392F", true),
            ("japan", true) => new("#080808", "#1B1012", "#050505", "#12090B", "#FF7C86", "#FFF0F0", "#D0A8AD", "#51272D", true),
            ("japan", false) => new("#FFF4F0", "#FFFFFF", "#050505", "#F7E7E4", "#A43F58", "#38222A", "#7F606A", "#DEC6CC", false),
            ("anime", true) => new("#100C21", "#201735", "#050505", "#140F27", "#9BCBFF", "#F1EDFF", "#B2A7CE", "#433858", true),
            ("anime", false) => new("#EFF6FF", "#FFFCFF", "#050505", "#EEE8F4", "#85518F", "#322447", "#756085", "#D3C6DC", false),
            ("maroon", true) => new("#1C0E14", "#2C1721", "#050505", "#211219", "#E5A0B5", "#FFF1F6", "#B5A0A8", "#48313B", true),
            ("maroon", false) => new("#FFF5F8", "#FFFFFF", "#050505", "#F6E8EE", "#8F2D4C", "#311821", "#806571", "#E5CCD5", false),
            ("purple", true) => new("#15101E", "#241A32", "#050505", "#1B1526", "#C5ADFF", "#F7F0FF", "#A99BB9", "#3C304D", true),
            ("purple", false) => new("#F9F6FF", "#FFFFFF", "#050505", "#EEE7F8", "#6F45B5", "#281B3D", "#796B8C", "#DED3EE", false),
            (_, false) => new("#F4F7F1", "#FFFFFF", "#050505", "#E9EEE7", "#98742F", "#0A1F17", "#68756D", "#D4DBD0", false),
            _ => new("#090C0A", "#111512", "#050505", "#141815", "#C9A45A", "#F3F5EF", "#88958C", "#252E28", true),
        };
    }
}
