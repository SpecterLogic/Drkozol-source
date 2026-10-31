package com.v2ray.ang.handler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class SubscriptionLinkCodecTest {
 @Test fun officialIncyVectorDecodes() {
  val vector="incy://crypt1/AAECAwQFBgcICQoLNyIQL3rDwRZqnyoD8pGKSLXP6o8NdSXQVSSALNbbUyIr__tWGFUexdIfKvvmDnuDGbmBvuppfNef6aKNZUwOm4c-Sg"
  assertEquals("https://sub.example.com/test-vector",SubscriptionLinkCodec.decode(vector)?.url)
 }
 @Test fun randomizedRoundTripAndPreservedShareFormat() {
  val url="https://example.com/sub?q=1&x=2"
  val first=SubscriptionLinkCodec.encode(url,"نام تست")
  assertNotEquals(first,SubscriptionLinkCodec.encode(url,"نام تست"))
  assertEquals(SubscriptionLinkCodec.Decoded(url,"نام تست"),SubscriptionLinkCodec.decode(first))
  val provider=com.v2ray.ang.dto.entities.SubscriptionItem(url=url,obfuscatedShare=true)
  assertTrue(SubscriptionLinkCodec.share(provider).startsWith("drkozol://crypt1/"))
  assertFalse(com.google.gson.Gson().fromJson("""{"url":"https://example.com"}""",com.v2ray.ang.dto.entities.SubscriptionItem::class.java).obfuscatedShare)
 }
 @Test fun malformedTamperedAndUnsafeUrlsAreRejected() {
  listOf("", "drkozol://crypt1/AA", "drkozol://crypt1/!!!", "incy://crypt1/").forEach { assertNull(SubscriptionLinkCodec.decode(it)) }
  val good=SubscriptionLinkCodec.encode("https://example.com/sub")
  assertNull(SubscriptionLinkCodec.decode(good.dropLast(4)+"AAAA"))
  listOf("file:///tmp/a","http://example.com","https://user:pass@example.com").forEach { assertThrows(IllegalArgumentException::class.java) { SubscriptionLinkCodec.encode(it) } }
 }
}
