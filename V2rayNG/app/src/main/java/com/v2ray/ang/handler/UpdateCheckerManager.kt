package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease,
                sha256 = latestRelease.assets.firstOrNull { it.browserDownloadUrl == downloadUrl }?.digest?.removePrefix("sha256:"),
                downloadSize = latestRelease.assets.firstOrNull { it.browserDownloadUrl == downloadUrl }?.size ?: 0
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    /** The caller owns cancellation/progress; only a verified APK becomes installable. */
    suspend fun downloadUpdate(context: Context, result: CheckUpdateResult, progress: (Float) -> Unit): File =
        try {
            downloadUpdateVia(context, result, progress, 0)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            val port = withContext(Dispatchers.IO) { SettingsManager.getHttpPort() }
            if (port !in 1..65535) throw error
            downloadUpdateVia(context, result, progress, port)
        }

    private suspend fun downloadUpdateVia(context: Context, result: CheckUpdateResult, progress: (Float) -> Unit, proxyPort: Int): File = withContext(Dispatchers.IO) {
        require(result.hasUpdate && result.latestVersion?.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) == true)
        val expectedHash = result.sha256?.lowercase()
        require(expectedHash?.matches(Regex("[a-f0-9]{64}")) == true)
        require(result.downloadSize in 1..MAX_APK_BYTES)
        var uri = URI(requireNotNull(result.downloadUrl))
        require(trustedUpdateUrl(uri, initial = true))
        val directory = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        val partial = File.createTempFile("update-", ".part", directory)
        val destination = File(directory, "DrKozol-${result.latestVersion}.apk")
        val client = HttpUtil.buildOkHttpClient(30000, proxyPort,
            if (proxyPort == 0) null else SettingsManager.getSocksUsername(),
            if (proxyPort == 0) null else SettingsManager.getSocksPassword(), followRedirects = false)
        var connection: Response? = null
        try {
            var received = 0L
            val hash = MessageDigest.getInstance("SHA-256")
            for (redirect in 0..5) {
                currentCoroutineContext().ensureActive()
                require(trustedUpdateUrl(uri, initial = redirect == 0))
                val next = client.newCall(Request.Builder().url(uri.toString()).header("Accept-Encoding", "identity").build()).execute()
                connection = next
                val status = next.code
                if (status in listOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(requireNotNull(next.header("Location")))
                    next.close()
                    check(redirect < 5)
                    continue
                }
                if (status != 200) throw IOException("Update transfer HTTP $status")
                check(next.body.contentLength() <= MAX_APK_BYTES)
                next.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastProgress = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= result.downloadSize && received <= MAX_APK_BYTES)
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                            val percent = (received * 100 / result.downloadSize).toInt()
                            if (percent != lastProgress) {
                                lastProgress = percent
                                progress((received.toFloat() / result.downloadSize).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                break
            }
            currentCoroutineContext().ensureActive()
            check(received == result.downloadSize)
            check(hash.digest().joinToString("") { "%02x".format(it) } == expectedHash)
            val (installed, downloaded) = readUpdatePackages(context, partial)
            check(downloaded.packageName == context.packageName)
            check(downloaded.versionName == result.latestVersion)
            check(PackageInfoCompat.getLongVersionCode(downloaded) > PackageInfoCompat.getLongVersionCode(installed))
            check(sameSigningCertificates(packageCertificates(installed), packageCertificates(downloaded)))
            check(!destination.exists() || destination.delete())
            check(partial.renameTo(destination))
            destination
        } finally {
            connection?.close()
            if (partial.exists()) partial.delete()
        }
    }

    // Integer flags are required on API 24-32; remove the fallback at minSdk 33.
    @Suppress("DEPRECATION")
    private fun readUpdatePackages(context: Context, apk: File): Pair<PackageInfo, PackageInfo> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val typedFlags = PackageManager.PackageInfoFlags.of(flags.toLong())
            manager.getPackageInfo(context.packageName, typedFlags) to
                requireNotNull(manager.getPackageArchiveInfo(apk.absolutePath, typedFlags))
        } else manager.getPackageInfo(context.packageName, flags) to
            requireNotNull(manager.getPackageArchiveInfo(apk.absolutePath, flags))
    }

    // Legacy certificate access is needed only for API 24-27; remove at minSdk 28.
    @Suppress("DEPRECATION")
    private fun packageCertificates(info: PackageInfo): List<ByteArray> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { it.toByteArray() }

    internal fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].toInt() else 0
            val num2 = if (i < v2.size) v2[i].toInt() else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    internal fun getDownloadUrl(
        release: GitHubRelease,
        abi: String,
        isFdroid: Boolean = BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)
    ): String {
        val compatible = release.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.name.contains("fdroid", ignoreCase = true) == isFdroid
        }
        val genericName = "DrKozol-${release.tagName.removePrefix("v")}.apk"
        val asset = compatible.firstOrNull {
            it.name.contains(abi, ignoreCase = true) &&
                !(abi == "x86" && it.name.contains("x86_64", ignoreCase = true))
        }
            ?: compatible.firstOrNull {
                it.name.contains("universal", ignoreCase = true) || it.name.equals(genericName, ignoreCase = true)
            }
        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}

internal const val MAX_APK_BYTES = 250L * 1024 * 1024

internal fun trustedUpdateUrl(uri: URI, initial: Boolean): Boolean {
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
    val host = uri.host?.lowercase() ?: return false
    return if (initial) host == "github.com" && uri.path.startsWith("/SpecterLogic/Drkozol/releases/download/")
    else host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}

internal fun sameSigningCertificates(installed: List<ByteArray>, downloaded: List<ByteArray>): Boolean {
    fun hashes(values: List<ByteArray>) = values.map { MessageDigest.getInstance("SHA-256").digest(it).toList() }.toSet()
    return installed.isNotEmpty() && downloaded.isNotEmpty() && hashes(installed) == hashes(downloaded)
}
