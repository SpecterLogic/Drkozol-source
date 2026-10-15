namespace ServiceLib.Models.Entities;

[Serializable]
public class SubItem
{
    public bool? ShowOnHome { get; set; }
    public bool CustomUpdateInterval { get; set; }
    public string? TunnelDns { get; set; }
    public bool LocalGroup { get; set; }
    public string? DisplayNameOverride { get; set; }
    public string? ProbeUrl { get; set; }
    public string? SortOrder { get; set; }
    public string? Announcement { get; set; }
    public long? UploadBytes { get; set; }
    public long? DownloadBytes { get; set; }
    public long? TotalBytes { get; set; }
    public long? ExpiresAt { get; set; }
    public long? CheckedAt { get; set; }
    public long? ServerTime { get; set; }
    public long? LatestTime { get; set; }

    [PrimaryKey]
    public string Id { get; set; }

    public string Remarks { get; set; }

    public string Url { get; set; }

    public string MoreUrl { get; set; }

    public bool Enabled { get; set; } = true;

    public string UserAgent { get; set; } = string.Empty;

    public string? RequestHeaders { get; set; }

    public int Sort { get; set; }

    public string? Filter { get; set; }

    public int AutoUpdateInterval { get; set; }

    public long UpdateTime { get; set; }

    public string? ConvertTarget { get; set; }

    public string? PrevProfile { get; set; }

    public string? NextProfile { get; set; }

    public int? PreSocksPort { get; set; }

    public string? Memo { get; set; }

    public ECoreType? CustomCoreType { get; set; }
}
