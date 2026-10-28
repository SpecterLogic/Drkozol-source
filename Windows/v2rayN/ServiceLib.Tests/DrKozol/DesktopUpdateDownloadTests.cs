using ServiceLib.DrKozol;

namespace ServiceLib.Tests.DrKozol;

public class DesktopUpdateDownloadTests
{
    [Test]
    public async Task UnverifiedDownloadCannotOverwriteAnExistingFile()
    {
        var destination = Path.Combine(Path.GetTempPath(), "DrKozol-update-test-" + Guid.NewGuid().ToString("N"));
        await File.WriteAllTextAsync(destination, "existing download");
        try
        {
            var controller = new DrKozolController();
            await (await controller.DownloadUpdate("https://example.org/untrusted.zip", destination)).Should().BeFalse();
            await (await File.ReadAllTextAsync(destination)).Should().BeEqualTo("existing download");
            await Directory.GetFiles(Path.GetDirectoryName(destination)!, Path.GetFileName(destination) + ".*.part").Length.Should().BeEqualTo(0);
        }
        finally { File.Delete(destination); }
    }
}
