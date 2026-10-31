package com.v2ray.ang.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** No Internet endpoint is contacted: both upstream proxies terminate requests locally. */
@RunWith(AndroidJUnit4::class)
class SelectedBalancerNetworkDeviceTest {
    private class Peer(private val delay:Long): AutoCloseable {
        private val server=ServerSocket(0)
        val port get()=server.localPort
        val requests=CopyOnWriteArrayList<String>()
        @Volatile private var open=true
        init { thread(isDaemon=true) {
            while(open) try {
                val socket=server.accept()
                thread(isDaemon=true) { socket.use {
                    try {
                        it.soTimeout=4000
                        val reader=it.getInputStream().bufferedReader()
                        var first=reader.readLine() ?: return@use
                        while(!reader.readLine().isNullOrEmpty()) { }
                        if(first.startsWith("CONNECT ")) {
                            it.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                            first=reader.readLine() ?: return@use
                            while(!reader.readLine().isNullOrEmpty()) { }
                        }
                        requests += first
                        Thread.sleep(delay)
                        it.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                    } catch(_:Exception) { }
                } }
            } catch(_:Exception) { if(open) throw IllegalStateException("Fixture listener failed") }
        } }
        override fun close() { open=false;server.close() }
    }

    @Test fun protectedRequestsStayOnHealthyPeerWhileOrdinaryTrafficBalancesAndRecovers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=Peer(5);val second=Peer(100)
        val port=ServerSocket(0).use { it.localPort }
        val tag=AppConfig.TAG_BALANCER
        val config=Gson().fromJson("""{
          "log":{"loglevel":"none"},"stats":{},
          "inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"http","tag":"test-in"}],
          "outbounds":[
            {"tag":"fixture-1","protocol":"http","settings":{"address":"127.0.0.1","port":${first.port}}},
            {"tag":"fixture-2","protocol":"http","settings":{"address":"127.0.0.1","port":${second.port}}}
          ],
          "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","network":"tcp,udp","balancerTag":"$tag"}],
            "balancers":[{"tag":"$tag","selector":["fixture-"],"fallbackTag":"fixture-2","strategy":{"type":"roundRobin"}}]}
        }""",V2rayConfig::class.java)
        protectSelectedBalancerSessions(config)
        config.observatory=V2rayConfig.ObservatoryObject(listOf("fixture-"),"http://probe.test/generate_204","1s",true)
        val controller=CoreNativeManager.newCoreController(object:libv2ray.CoreCallbackHandler {
            override fun startup():Long=0
            override fun shutdown():Long=0
            override fun onEmitStatus(l:Long,s:String?):Long=0
        })
        fun request(host:String,path:String) {
            val connection=URL("http://$host/$path").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",port))) as java.net.HttpURLConnection
            connection.connectTimeout=3000;connection.readTimeout=3000
            try { assertEquals(204,connection.responseCode) } finally { connection.disconnect() }
        }
        try {
            CoreNativeManager.initCoreEnv(context)
            controller.startLoop(Gson().toJson(config),0)
            Thread.sleep(3500)
            repeat(6) { request("chatgpt.com","protected-$it") }
            assertEquals(6,first.requests.count { it.contains("protected-") })
            assertEquals(0,second.requests.count { it.contains("protected-") })
            repeat(6) { request("ordinary.test","ordinary-$it") }
            assertTrue(first.requests.any { it.contains("ordinary-") })
            assertTrue(second.requests.any { it.contains("ordinary-") })
            first.close()
            Thread.sleep(4000)
            request("chatgpt.com","recovered")
            assertTrue(second.requests.any { it.contains("recovered") })
        } finally { controller.stopLoop();first.close();second.close() }
    }
}
