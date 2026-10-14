package com.v2ray.ang.dto.entities

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Optional provider text is data only; never interpret it as markup or commands. */
object SubscriptionMetadata {
    fun text(value: String?): String? {
        if (value == null || value.length > 8192) return null
        val decoded = if (value.startsWith("base64:", true)) {
            runCatching {
                val bytes = Base64.getDecoder().decode(value.substring(7).trim())
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() ?: return null
        } else value
        return decoded.filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim().take(2048)
    }
}
