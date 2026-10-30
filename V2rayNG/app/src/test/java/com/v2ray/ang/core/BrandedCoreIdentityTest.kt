package com.v2ray.ang.core

import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrandedCoreIdentityTest {
    @Test fun brandedDistributionUsesItsBundledXrayProtocolInsteadOfPackageNameDetection() {
        assertTrue(Utils.isXray())
    }
}
