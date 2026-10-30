package com.v2ray.ang.ui.compose

/** Stable setting IDs preserve explicit choices; fresh installations use ocean. */
enum class DrKozolPalette(val settingId: String) {
    FOREST("forest"), MAROON("maroon"), PURPLE("purple"), OCEAN("ocean"), SUNSET("sunset"),
    OLED("oled"), JAPAN("japan"), ANIME("anime"), AURORA("aurora"), SANDSTONE("sandstone"), GRAPHITE("graphite"), KYOTO("kyoto"), BAIKAL("baikal"), JADE("jade"), PORCELAIN("porcelain");

    fun colors(dark: Boolean): DrKozolColors = when (this) {
        KYOTO -> if (dark) DrKozolColors(0xFF11131E, 0xFF070911, 0xFF211C28, 0xFFFFB3BB, 0xFF3C0818, 0xFFFFF0F3, 0xFFCAB5C0)
            else DrKozolColors(0xFFFFF6F0, 0xFFFFFCF8, 0xFFFFFFFF, 0xFF8D3852, 0xFFFFFFFF, 0xFF30202A, 0xFF755261)
        BAIKAL -> if (dark) DrKozolColors(0xFF081624, 0xFF030B14, 0xFF132A3A, 0xFF9ADAF7, 0xFF002E44, 0xFFECF8FF, 0xFFA7BDCD)
            else DrKozolColors(0xFFF0F7FD, 0xFFFBFDFF, 0xFFFFFFFF, 0xFF275E88, 0xFFFFFFFF, 0xFF152B3B, 0xFF506879)
        JADE -> if (dark) DrKozolColors(0xFF0B1C1A, 0xFF040E0D, 0xFF172F2B, 0xFFA7DEC9, 0xFF06382B, 0xFFEBFCF3, 0xFFB0C7BC)
            else DrKozolColors(0xFFF3F7EF, 0xFFFCFDF8, 0xFFFFFFFF, 0xFF29684D, 0xFFFFFFFF, 0xFF18352B, 0xFF526D60)
        PORCELAIN -> if (dark) DrKozolColors(0xFF111A2D, 0xFF070D1A, 0xFF202D43, 0xFFB9CEFF, 0xFF172F56, 0xFFF4F6FF, 0xFFB8C2D5)
            else DrKozolColors(0xFFF9F6EE, 0xFFFFFDF8, 0xFFFFFFFF, 0xFF34538B, 0xFFFFFFFF, 0xFF212D43, 0xFF5B677C)
        AURORA -> if (dark) DrKozolColors(0xFF071C1B, 0xFF030E12, 0xFF102D2B, 0xFF88E5C8, 0xFF00382B, 0xFFE5FCF4, 0xFFA0C5B9)
            else DrKozolColors(0xFFF0FAF5, 0xFFF8FCFF, 0xFFFFFFFF, 0xFF00664D, 0xFFFFFFFF, 0xFF10352C, 0xFF486D60)
        SANDSTONE -> if (dark) DrKozolColors(0xFF211B14, 0xFF120F0B, 0xFF30291E, 0xFFE8C585, 0xFF3D2B05, 0xFFFCF2DC, 0xFFC3B49B)
            else DrKozolColors(0xFFFAF5EB, 0xFFFFFCF5, 0xFFFFFFFF, 0xFF785313, 0xFFFFFFFF, 0xFF332A1D, 0xFF72624B)
        GRAPHITE -> if (dark) DrKozolColors(0xFF13171C, 0xFF080B0F, 0xFF20262E, 0xFFAFC9ED, 0xFF172C48, 0xFFF0F4FA, 0xFFADB9CA)
            else DrKozolColors(0xFFF2F5F9, 0xFFFAFCFF, 0xFFFFFFFF, 0xFF355A87, 0xFFFFFFFF, 0xFF202B38, 0xFF596A7D)
        FOREST -> if (dark) DrKozolColors(0xFF0A1F17, 0xFF08130E, 0xFF10271D, 0xFFC9A45A, 0xFF0A1F17, 0xFFEDF3EF, 0xFF86A397)
            else DrKozolColors(0xFFF1F5EC, 0xFFFFFCF4, 0xFFF9FBF5, 0xFF80621F, 0xFFFFFFFF, 0xFF0A1F17, 0xFF577266)
        MAROON -> if (dark) DrKozolColors(0xFF1C0E14, 0xFF12090D, 0xFF2C1721, 0xFFE5A0B5, 0xFF321220, 0xFFFFEDF2, 0xFFC09DA9)
            else DrKozolColors(0xFFFFF5F8, 0xFFFFFBFC, 0xFFFFFFFF, 0xFF8F2D4C, 0xFFFFFFFF, 0xFF321220, 0xFF77515F)
        PURPLE -> if (dark) DrKozolColors(0xFF15101E, 0xFF0D0914, 0xFF241A32, 0xFFC5ADFF, 0xFF281B41, 0xFFF3EDFF, 0xFFADA1C3)
            else DrKozolColors(0xFFF9F6FF, 0xFFFFFCFF, 0xFFFFFFFF, 0xFF6F45B5, 0xFFFFFFFF, 0xFF281B41, 0xFF6C5C83)
        OCEAN -> if (dark) DrKozolColors(0xFF061923, 0xFF030D15, 0xFF102936, 0xFF70D8EE, 0xFF002D38, 0xFFE4F8FC, 0xFF92B6C2)
            else DrKozolColors(0xFFEFFAFF, 0xFFF8FDFF, 0xFFFFFFFF, 0xFF006780, 0xFFFFFFFF, 0xFF082C37, 0xFF496C79)
        SUNSET -> if (dark) DrKozolColors(0xFF25130D, 0xFF130B08, 0xFF382017, 0xFFFFBC8C, 0xFF482000, 0xFFFFF0E6, 0xFFC8A99A)
            else DrKozolColors(0xFFFFF4E8, 0xFFFFFCF8, 0xFFFFFFFF, 0xFF99501B, 0xFFFFFFFF, 0xFF382015, 0xFF7B6252)
        OLED -> DrKozolColors(0xFF000000, 0xFF000000, 0xFF101010, 0xFFB9F3CB, 0xFF082619, 0xFFF3F3F3, 0xFFACB9B0)
        JAPAN -> if (dark) DrKozolColors(0xFF080808, 0xFF000000, 0xFF1B1012, 0xFFFF7C86, 0xFF38000A, 0xFFFFF0F0, 0xFFD0A8AD)
            else DrKozolColors(0xFFFFF4F0, 0xFFFFFCF7, 0xFFFFFFFF, 0xFFA43F58, 0xFFFFFFFF, 0xFF38222A, 0xFF7F606A)
        // Your Name sky/pink by day; Solo Leveling shadow violet, ice blue and crimson by night.
        ANIME -> if (dark) DrKozolColors(0xFF100C21, 0xFF050812, 0xFF201735, 0xFF9BCBFF, 0xFF142442, 0xFFF1EDFF, 0xFFB2A7CE)
            else DrKozolColors(0xFFEFF6FF, 0xFFFFEFF7, 0xFFFFFCFF, 0xFF85518F, 0xFFFFFFFF, 0xFF322447, 0xFF756085)
    }

    companion object {
        fun fromSetting(value: String?): DrKozolPalette = if (value == null) OCEAN else entries.firstOrNull { it.settingId == value } ?: FOREST
        fun isValidSetting(value: String): Boolean = entries.any { it.settingId == value }
    }
}

data class DrKozolColors(
    val background: Long, val backgroundEnd: Long, val surface: Long,
    val accent: Long, val onAccent: Long, val ink: Long, val muted: Long,
)
