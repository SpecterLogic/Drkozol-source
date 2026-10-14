package com.v2ray.ang.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns a short-lived userspace probe; it never creates or replaces Android's VPN. */
internal object AmneziaProbe {
    // Profiles can reuse the same local and remote peer keys with different endpoints.
    // Concurrent handshakes replace the remote peer's endpoint/session and corrupt results.
    private val peerProbeMutex = Mutex()
    internal suspend fun <T> withPeerProbeLease(action: suspend () -> T): T = peerProbeMutex.withLock { action() }

    suspend fun realDelay(context: Context, configuration: String, url: String): Long =
        withPeerProbeLease { probe(context, configuration, url) }

    private suspend fun probe(context: Context, configuration: String, url: String): Long = withContext(Dispatchers.IO) {
        val executable = File(context.applicationInfo.nativeLibraryDir, "libdrkozol-awg-probe.so")
        check(executable.isFile) { "Packaged AWG probe is missing" }
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).apply {
                write(Gson().toJson(mapOf("config" to configuration, "port" to port)))
                newLine(); flush()
            }
            withTimeout(12_000) {
                val buffer = StringBuilder()
                var ready = false
                while (!ready) {
                    currentCoroutineContext().ensureActive()
                    val available = process.inputStream.available()
                    if (available > 0) {
                        val bytes = ByteArray(available.coerceAtMost(8192))
                        val count = process.inputStream.read(bytes)
                        if (count > 0) buffer.append(String(bytes, 0, count, Charsets.UTF_8))
                        check(buffer.length <= 65536) { "Invalid probe output" }
                        while ('\n' in buffer) {
                            val end = buffer.indexOf("\n")
                            val line = buffer.substring(0, end)
                            buffer.delete(0, end + 1)
                            ready = runCatching { JsonParser.parseString(line).asJsonObject.get("kind")?.asString == "ready" }.getOrDefault(false)
                            if (ready) break
                        }
                    }
                    if (!ready) {
                        try { process.exitValue(); throw IOException("AWG probe exited") }
                        catch (_: IllegalThreadStateException) { }
                        delay(20)
                    }
                }
            }
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .callTimeout(30, TimeUnit.SECONDS).build()
            val started = SystemClock.elapsedRealtime()
            try {
                suspendCancellableCoroutine { continuation ->
                    val call = client.newCall(Request.Builder().url(url).build())
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, failure: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("AWG HTTPS probe failed"))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                if (continuation.isActive) {
                                    if (it.isSuccessful) continuation.resume((SystemClock.elapsedRealtime() - started).coerceAtLeast(1))
                                    else continuation.resumeWithException(IOException("AWG HTTPS probe rejected"))
                                }
                            }
                        }
                    })
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush(); process.outputStream.close() }
                fun exited(): Boolean = try { process.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                withTimeoutOrNull(1500) { while (!exited()) delay(20) }
                if (!exited()) {
                    process.destroy()
                    // Android 24/25 lack forceful destruction. Remove fallback when minSdk reaches 26.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly()
                    withTimeoutOrNull(1500) { while (!exited()) delay(20) }
                }
                runCatching { process.inputStream.close() }
            }
        }
    }
}
