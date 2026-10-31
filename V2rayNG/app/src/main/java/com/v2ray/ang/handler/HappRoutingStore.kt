package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.Utils
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Owns provider-scoped imported profiles and immutable downloaded routing assets. */
object HappRoutingStore {
    private fun key(groupId: String) = "dr_happ_routing_$groupId"
    private fun legacyState(groupId: String) = MmkvManager.decodeSubscription(groupId)?.let {
        HappRoutingState(it.happRoutingProfiles.orEmpty(), it.activeHappRoutingProfile)
    } ?: HappRoutingState()
    fun state(groupId: String): HappRoutingState {
        val saved = MmkvManager.decodeSettingsString(key(groupId))
        return saved?.let { com.v2ray.ang.util.JsonUtil.fromJsonSafe(it, HappRoutingState::class.java) }
            ?: legacyState(groupId)
    }

    fun activate(groupId: String, name: String?): Boolean = MmkvManager.updateSettingsString(key(groupId)) { json ->
        val current = json?.let { com.v2ray.ang.util.JsonUtil.fromJsonSafe(it, HappRoutingState::class.java) } ?: legacyState(groupId)
        require(MmkvManager.decodeSubscription(groupId) != null && (name == null || current.profiles.any { it.name == name }))
        com.v2ray.ang.util.JsonUtil.toJson(current.copy(active = name))
    }
    fun importGroup(explicit: String = ""): String = sequenceOf(explicit,
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID).orEmpty(),
        MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)?.subscriptionId.orEmpty())
        .firstOrNull { it.isNotBlank() && MmkvManager.decodeSubscription(it) != null }.orEmpty()

    fun rules(guid: String, imported: HappRoutingProfile? = active(guid)) = imported?.let { profile -> profile.rules.map { rule ->
        fun convert(values: List<String>?, kind: String): List<String>? = values?.map { value ->
            val file = profile.assetFiles[kind]?.takeIf { it.matches(Regex("dr_happ_[a-f0-9]{32}\\.dat")) }
            if (file != null && value.startsWith("$kind:")) "ext:$file:${value.substringAfter(':')}" else value
        }
        rule.copy(domain = convert(rule.domain, "geosite"), ip = convert(rule.ip, "geoip"))
    } } ?: MmkvManager.decodeRoutingRulesets().orEmpty()

    fun fakeDns(guid: String, imported: HappRoutingProfile? = active(guid)): Boolean = imported?.fakeDns
        ?: MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)

    fun active(guid: String): HappRoutingProfile? = MmkvManager.decodeServerConfig(guid)?.subscriptionId
        ?.let(::state)?.let { provider ->
            provider.profiles.firstOrNull { it.name == provider.active }
        }

    fun install(context: Context, groupId: String, link: String): Boolean = install(context,groupId,link,::download)

    @JvmName("installWithFetcher")
    internal fun install(context: Context, groupId: String, link: String, fetch: (String,File)->Unit): Boolean {
        if (link.length > 1_000_000) return false
        val ownerId = importGroup(groupId)
        if (MmkvManager.decodeSubscription(ownerId) == null) return false
        val previous = state(ownerId)
        val downloaded = mutableListOf<File>()
        val attemptId = java.security.MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        var command: HappRoutingCommand? = null
        fun attempt(state: String) {
            check(MmkvManager.updateSettingsString(key(ownerId)) { json ->
                val current = json?.let { com.v2ray.ang.util.JsonUtil.fromJsonSafe(it, HappRoutingState::class.java) } ?: previous
                val entries = current.attempts + (attemptId to RoutingAttempt(link, command?.profile?.name, state, System.currentTimeMillis()))
                require(entries.size <= 40)
                com.v2ray.ang.util.JsonUtil.toJson(current.copy(attempts = entries))
            })
        }
        return try {
            attempt("downloading")
            val remote = remoteRoutingSource(link)
            val inline = if (remote != null) {
                val temporary = File.createTempFile("routing-profile-", ".json", context.cacheDir)
                try {
                    fetch(remote.url, temporary)
                    require(temporary.length() <= 1_000_000)
                    val json = temporary.readText(Charsets.UTF_8)
                    "happ://routing/" + (if (remote.activate) "onadd/" else "add/") + java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
                } finally { temporary.delete() }
            } else link
            val parsed = HappRoutingParser.parse(inline) ?: error("Invalid routing profile")
            command = parsed
            val profile = parsed.profile?.let { source ->
                val assets = mutableMapOf<String, String>()
                val directory = File(Utils.userAssetPath(context)).also { require(it.isDirectory) }
                val previousProfile = previous.profiles.firstOrNull {
                    it.name == source.name || (remote != null && it.sourceUrl == remote.url)
                }
                for ((kind, url) in listOf("geoip" to source.geoipUrl, "geosite" to source.geositeUrl)) {
                    if (url == null || source.rules.none { rule -> (if (kind == "geoip") rule.ip else rule.domain).orEmpty().any { it.startsWith("$kind:") } }) continue
                    val previousUrl = if (kind == "geoip") previousProfile?.geoipUrl else previousProfile?.geositeUrl
                    val cached = previousProfile?.assetFiles?.get(kind)?.takeIf { it.matches(Regex("dr_happ_[a-f0-9]{32}\\.dat")) }?.let { File(directory, it) }
                    if (previousUrl == url && cached?.isFile == true &&
                        System.currentTimeMillis() - cached.lastModified() < TimeUnit.DAYS.toMillis(7)) {
                        assets[kind] = cached.name
                        continue
                    }
                    val file = File(directory, "dr_happ_${java.util.UUID.randomUUID().toString().replace("-", "")}.dat")
                    downloaded += file
                    fetch(url, file)
                    assets[kind] = file.name
                }
                source.copy(assetFiles = assets, sourceUrl = remote?.url, autoRefresh = remote?.automatic ?: false, refreshedAt = System.currentTimeMillis())
            }
            if(profile!=null) com.v2ray.ang.core.CoreNativeManager.validateRoutingProfile(context,profile)
            check(MmkvManager.updateSettingsString(key(ownerId)) { json ->
                val current = json?.let { com.v2ray.ang.util.JsonUtil.fromJsonSafe(it, HappRoutingState::class.java) } ?: previous
                val replaced = current.profiles.filter {
                    it.name == profile?.name || (profile?.sourceUrl != null && it.sourceUrl == profile.sourceUrl)
                }
                val profiles = (current.profiles - replaced.toSet()).toMutableList()
                profile?.let { profiles += it }
                require(profiles.size <= 20 && MmkvManager.decodeSubscription(ownerId) != null)
                com.v2ray.ang.util.JsonUtil.toJson(current.copy(profiles = profiles,
                    active = if (parsed.off) null else if (parsed.activate || current.active == null || replaced.any { it.name == current.active }) profile?.name else current.active,
                    attempts = current.attempts - attemptId))
            })
            syncAutoUpdates(context)
            SettingsChangeManager.makeSetupGroupTab()
            true
        } catch (error: Exception) {
            downloaded.forEach { it.delete() }
            runCatching { attempt("failed") }
            syncAutoUpdates(context)
            com.v2ray.ang.util.LogUtil.e(AppConfig.TAG, "Routing profile import failed group=$groupId", IllegalStateException(error.javaClass.simpleName))
            false
        }
    }

    fun syncAutoUpdates(context: Context) {
        val request = androidx.work.PeriodicWorkRequestBuilder<RoutingRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        try {
            androidx.work.multiprocess.RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dr_routing_refresh", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (error: Exception) {
            com.v2ray.ang.util.LogUtil.e(AppConfig.TAG,"Routing refresh scheduling failed",IllegalStateException(error.javaClass.simpleName))
        }
    }

    fun refresh(context: Context, groupId: String): Boolean {
        val current = state(groupId)
        val links = current.profiles.mapNotNull { profile ->
            profile.sourceUrl?.takeIf { profile.autoRefresh }?.let {
                "drkozol://autorouting/" + (if (profile.name == current.active) "onadd/" else "add/") + it
            }
        } + current.attempts.values.map { it.link }
        return links.distinct().map { install(context, groupId, it) }.all { it }
    }

    private fun download(url: String, destination: File) {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(180, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .dns { name -> Dns.SYSTEM.lookup(name).also { addresses ->
                require(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
                    || it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc })
            } }.addNetworkInterceptor { chain ->
                require(chain.request().url.isHttps)
                chain.proceed(chain.request())
            }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
            val body = response.body ?: error("Missing routing asset")
            require(body.contentLength() <= 64L * 1024 * 1024)
            var size = 0L
            body.byteStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= 64L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            } }
            require(size > 0)
        }
    }
}
