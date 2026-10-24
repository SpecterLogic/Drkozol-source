package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.CheckUpdateResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.net.URI

class UpdateDownloadSecurityTest {
    @Test fun onlyTheOwnedHttpsReleaseAndKnownRedirectHostsAreAccepted() {
        assertTrue(trustedUpdateUrl(URI("https://github.com/SpecterLogic/Drkozol/releases/download/v1.4.8/DrKozol-1.4.8.apk"), true))
        for (url in listOf("http://github.com/SpecterLogic/Drkozol/releases/download/v1/a.apk",
            "https://evil.example/SpecterLogic/Drkozol/releases/download/v1/a.apk",
            "https://github.com/Other/Drkozol/releases/download/v1/a.apk",
            "https://user:password@github.com/SpecterLogic/Drkozol/releases/download/v1/a.apk",
            "https://github.com:8443/SpecterLogic/Drkozol/releases/download/v1/a.apk")) {
            assertFalse(trustedUpdateUrl(URI(url), true))
        }
        assertTrue(trustedUpdateUrl(URI("https://release-assets.githubusercontent.com/github-production-release-asset/a"), false))
        assertFalse(trustedUpdateUrl(URI("https://arbitrary.githubusercontent.com/a"), false))
        assertFalse(trustedUpdateUrl(URI("http://objects.githubusercontent.com/a"), false))
    }

    @Test fun missingChangedOrAdditionalSigningKeysCannotInstall() {
        val original = byteArrayOf(1, 2, 3)
        assertTrue(sameSigningCertificates(listOf(original), listOf(original.copyOf())))
        assertFalse(sameSigningCertificates(emptyList(), emptyList()))
        assertFalse(sameSigningCertificates(listOf(original), emptyList()))
        assertFalse(sameSigningCertificates(listOf(original), listOf(byteArrayOf(4))))
        assertFalse(sameSigningCertificates(listOf(original), listOf(original, byteArrayOf(4))))
    }

    @Test fun incompleteOrUntrustedReleaseMetadataFailsBeforeAnyDownload() {
        val context = mock<Context>()
        val valid = CheckUpdateResult(true, "1.4.9", downloadUrl = "https://github.com/SpecterLogic/Drkozol/releases/download/v1.4.9/DrKozol-1.4.9.apk", sha256 = "0".repeat(64), downloadSize = 128)
        for (result in listOf(valid.copy(hasUpdate = false), valid.copy(latestVersion = ""),
            valid.copy(latestVersion = "../../escape"), valid.copy(sha256 = null), valid.copy(sha256 = "bad"),
            valid.copy(downloadSize = 0), valid.copy(downloadSize = MAX_APK_BYTES + 1),
            valid.copy(downloadUrl = "https://example.com/app.apk"))) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { UpdateCheckerManager.downloadUpdate(context, result) {} }
            }
        }
    }
}
