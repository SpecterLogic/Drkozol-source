package com.v2ray.ang.handler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class RemoteRoutingSourceTest {
 @Test fun independentAndOneTimeImportsRemainDistinct() {
  assertEquals(RemoteRoutingSource("https://example.com/r.json",true,true),remoteRoutingSource("drkozol://autorouting/onadd/https://example.com/r.json"))
  assertEquals(RemoteRoutingSource("https://example.com/r.json",false,false),remoteRoutingSource("incy://routing/add/https://example.com/r.json"))
  assertNull(remoteRoutingSource("happ://routing/add/eyJOYW1lIjoiVGVzdCJ9"))
  assertNull(remoteRoutingSource("invalid"))
 }
 @Test fun unsafeAutomaticSourcesFailBeforeNetworkAccess() {
  listOf("http://example.com/a","https://user:pass@example.com/a","file:///data/a","AAAA").forEach { assertThrows(IllegalArgumentException::class.java) { remoteRoutingSource("drkozol://autorouting/add/"+it) } }
 }
 @Test fun previousStateReadsWithoutNewRefreshFields() {
  val previous=com.google.gson.Gson().fromJson("""{"profiles":[],"active":null}""",HappRoutingState::class.java)
  assertTrue(previous.profiles.isEmpty());assertTrue(previous.attempts.isEmpty())
 }
}
