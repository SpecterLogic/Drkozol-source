package com.v2ray.ang.ui.main

/** Show only a country explicitly supplied by the profile name; unknown locations stay a globe. */
internal fun configurationFlag(remarks: String): String? {
    var index = 0
    while (index < remarks.length) {
        val first = Character.codePointAt(remarks, index)
        val nextIndex = index + Character.charCount(first)
        if (first in 0x1F1E6..0x1F1FF && nextIndex < remarks.length) {
            val second = Character.codePointAt(remarks, nextIndex)
            if (second in 0x1F1E6..0x1F1FF) return remarks.substring(index, nextIndex + Character.charCount(second))
        }
        index = nextIndex
    }
    return null
}

/** The flag has its own column; keep the remaining title, including mixed-script names. */
internal fun configurationName(remarks: String): String {
    val flag = configurationFlag(remarks) ?: return remarks
    return remarks.replace(flag, "").trim().ifBlank { remarks }
}
