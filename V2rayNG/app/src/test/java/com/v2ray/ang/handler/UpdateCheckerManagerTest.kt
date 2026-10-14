package com.v2ray.ang.handler

import com.v2ray.ang.dto.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerManagerTest {
    private fun release(vararg names: String) = GitHubRelease(
        tagName = "v1.4.5", body = "", assets = names.map { GitHubRelease.Asset(it, "https://example.invalid/$it") }
    )

    @Test fun prefersMatchingArchitectureOverUniversal() {
        val value = release("DrKozol-1.4.5.apk", "DrKozol-arm64-v8a.apk", "DrKozol-x86_64.apk")
        assertEquals("https://example.invalid/DrKozol-arm64-v8a.apk",
            UpdateCheckerManager.getDownloadUrl(value, "arm64-v8a", false))
    }

    @Test fun genericPublicReleaseWorksForEverySupportedArchitecture() {
        val value = release("DrKozol-source-1.4.5.zip", "DrKozol-1.4.5.apk")
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            assertEquals("https://example.invalid/DrKozol-1.4.5.apk",
                UpdateCheckerManager.getDownloadUrl(value, abi, false))
        }
    }

    @Test fun fdroidUsesOnlyItsOwnDistribution() {
        val value = release("DrKozol-1.4.5.apk", "DrKozol-universal-FDROID.APK")
        assertEquals("https://example.invalid/DrKozol-universal-FDROID.APK",
            UpdateCheckerManager.getDownloadUrl(value, "x86_64", true))
    }

    @Test(expected = IllegalStateException::class) fun fdroidDoesNotReceivePlaystoreApk() {
        UpdateCheckerManager.getDownloadUrl(release("DrKozol-1.4.5.apk"), "arm64-v8a", true)
    }

    @Test(expected = IllegalStateException::class) fun rejectsOtherArchitectureAndNonApkAssets() {
        UpdateCheckerManager.getDownloadUrl(release("DrKozol-arm64-v8a.apk", "DrKozol-x86_64.zip"), "x86_64", false)
    }

    @Test(expected = IllegalStateException::class) fun emptyReleaseHasNoDownload() {
        UpdateCheckerManager.getDownloadUrl(release(), "arm64-v8a", false)
    }

    @Test(expected = IllegalStateException::class) fun x86DoesNotReceiveAnX8664OnlyApk() {
        UpdateCheckerManager.getDownloadUrl(release("DrKozol-x86_64.apk"), "x86", false)
    }
}
