package com.v2ray.ang.ui
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class LinkPromptParsingTest {
 @Test fun commandsRequireConsentAndUnknownInputNeverCrashes() {
  assertEquals("connect",parseLinkPrompt("drkozol://connect").command)
  assertTrue(parseLinkPrompt("drkozol://toggle").visible)
  assertFalse(parseLinkPrompt("drkozol://toggle").completed)
  listOf("bad URI %", "other://connect", "drkozol://connect?unexpected=1", "drkozol://crypt1/bad").forEach { assertTrue(parseLinkPrompt(it).error) }
 }
 @Test fun promptNeverExposesDecodedUrl() {
  val link=com.v2ray.ang.handler.SubscriptionLinkCodec.encode("https://example.com/private-token","Provider")
  val state=parseLinkPrompt(link)
  assertEquals("Provider",state.name);assertFalse(state.toString().contains("private-token"));assertFalse(state.error)
 }
}
