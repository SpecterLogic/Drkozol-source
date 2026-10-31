package com.v2ray.ang.core
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class SelectedSessionRoutingTest {
 private fun config()=com.google.gson.Gson().fromJson("""{"log":{},"inbounds":[],"outbounds":[],"routing":{"rules":[],"balancers":[]}}""",V2rayConfig::class.java)
 @Test fun selectedMembersAndUserBlockRulesAreRetained() {
  val config=config()
  val block=V2rayConfig.RoutingBean.RulesBean(domain=listOf("domain:blocked.example"),outboundTag="block")
  config.routing.rules.add(block)
  config.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(network="tcp,udp",balancerTag=AppConfig.TAG_BALANCER))
  val main=V2rayConfig.RoutingBean.BalancerBean(AppConfig.TAG_BALANCER,listOf("chosen-members-"),"chosen-members-1",V2rayConfig.RoutingBean.StrategyObject("roundRobin"))
  config.routing.balancers=listOf(main)
  protectSelectedBalancerSessions(config)
  assertSame(block,config.routing.rules.first())
  val session=config.routing.balancers!!.last()
  assertEquals(main.selector,session.selector);assertEquals(main.fallbackTag,session.fallbackTag)
  assertEquals("leastPing",session.strategy!!.type)
  assertEquals("roundRobin",config.routing.balancers!!.first().strategy!!.type)
  assertTrue(config.routing.rules[1].domain!!.contains("domain:chatgpt.com"))
  assertNotNull(config.observatory)
  protectSelectedBalancerSessions(config);assertEquals(2,config.routing.balancers!!.size)
 }
 @Test fun ordinaryProfilesAreNotChanged() {
  val config=config();protectSelectedBalancerSessions(config)
  assertTrue(config.routing.rules.isEmpty());assertNull(config.observatory)
 }
}
