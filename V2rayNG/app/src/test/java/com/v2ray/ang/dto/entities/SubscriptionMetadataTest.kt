package com.v2ray.ang.dto.entities

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class SubscriptionMetadataTest {
    @Test fun decodesProviderMessageAsPlainText() {
        val message = "⚠️ اختلال سراسری ⚠️"
        val encoded = Base64.getEncoder().encodeToString(message.toByteArray(Charsets.UTF_8))
        assertEquals(message, SubscriptionMetadata.text("base64:$encoded"))
        assertEquals("<b>notice</b>", SubscriptionMetadata.text("<b>notice</b>"))
    }
    @Test fun absentInvalidAndEmptyMetadata() {
        assertNull(SubscriptionMetadata.text(null))
        assertNull(SubscriptionMetadata.text("base64:invalid*"))
        assertNull(SubscriptionMetadata.text("base64:/w=="))
        assertNull(SubscriptionMetadata.text("x".repeat(8193)))
        assertEquals("", SubscriptionMetadata.text("base64:"))
        assertEquals("message", SubscriptionMetadata.text("\u0000message\u0001"))
        assertEquals(2048, SubscriptionMetadata.text("x".repeat(4096))?.length)
    }
    @Test fun precedingReleaseSubscriptionRetainsDefaults() {
        val old = Gson().fromJson("""{"remarks":"Existing","url":"https://example.com/sub/test","enabled":true,"usage":{"total":1000,"upload":1,"download":2}}""", SubscriptionItem::class.java)
        assertNull(old.announcement)
        assertEquals("Existing", old.remarks)
        assertEquals(997L, old.usage?.remaining)
        val restored = Gson().fromJson(Gson().toJson(old.copy(announcement = "Provider notice")), SubscriptionItem::class.java)
        assertEquals("Provider notice", restored.announcement)
        assertEquals(old.usage, restored.usage)
    }
}
