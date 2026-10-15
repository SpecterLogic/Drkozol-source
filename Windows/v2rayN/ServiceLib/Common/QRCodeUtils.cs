using QRCoder;
using QRCoder.Exceptions;
using SkiaSharp;
using ZXing.SkiaSharp;

namespace ServiceLib.Common;

public class QRCodeUtils
{
    public static byte[]? GenQRCode(string? url)
    {
        if (url.IsNullOrEmpty())
        {
            return null;
        }
        using QRCodeGenerator qrGenerator = new();
        DataTooLongException? lastDtle = null;

        var levels = new[]
        {
            QRCodeGenerator.ECCLevel.H,
            QRCodeGenerator.ECCLevel.Q,
            QRCodeGenerator.ECCLevel.M,
            QRCodeGenerator.ECCLevel.L
        };
        foreach (var level in levels)
        {
            try
            {
                using var qrCodeData = qrGenerator.CreateQrCode(url, level);
                using PngByteQRCode qrCode = new(qrCodeData);
                return qrCode.GetGraphic(20);
            }
            catch (DataTooLongException ex)
            {
                lastDtle = ex;
                continue;
            }
            catch
            {
                throw;
            }
        }

        if (lastDtle != null)
        {
            throw lastDtle;
        }

        return null;
    }

    public static string? ParseBarcode(string? fileName)
    {
        if (fileName == null || !File.Exists(fileName))
        {
            return null;
        }

        try
        {
            var image = SKImage.FromEncodedData(fileName);
            var bitmap = SKBitmap.FromImage(image);

            return ReaderBarcode(bitmap);
        }
        catch
        {
            // ignored
        }

        return null;
    }

    public static string? ParseBarcode(byte[]? bytes)
    {
        try
        {
            if (bytes is null || bytes.Length > 10 * 1024 * 1024) return null;
            using var stream = new SKMemoryStream(bytes);
            using var codec = SKCodec.Create(stream);
            if (codec is null || codec.Info.Width <= 0 || codec.Info.Height <= 0
                || (long)codec.Info.Width * codec.Info.Height > 16_000_000) return null;
            using var bitmap = SKBitmap.Decode(codec);
            if (bitmap is null) return null;
            return ReaderBarcode(bitmap);
        }
        catch
        {
            // ignored
        }

        return null;
    }

    private static string? ReaderBarcode(SKBitmap? bitmap)
    {
        var reader = new BarcodeReader();
        var result = reader.Decode(bitmap);

        if (result != null && result.Text.IsNotEmpty())
        {
            return result.Text;
        }

        //FlipBitmap
        var result2 = reader.Decode(FlipBitmap(bitmap));
        return result2?.Text;
    }

    private static SKBitmap FlipBitmap(SKBitmap bmp)
    {
        // Create a bitmap (to return)
        var flipped = new SKBitmap(bmp.Width, bmp.Height, bmp.Info.ColorType, bmp.Info.AlphaType);

        // Create a canvas to draw into the bitmap
        using var canvas = new SKCanvas(flipped);

        // Set a transform matrix which moves the bitmap to the right,
        // and then "scales" it by -1, which just flips the pixels
        // horizontally
        canvas.Translate(bmp.Width, 0);
        canvas.Scale(-1, 1);
        canvas.DrawBitmap(bmp, 0, 0);
        return flipped;
    }
}
