package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType

/** Native AWG peers and panel account rows cannot become Xray balancer outbounds. */
internal fun balancerEligible(profile: ProfileItem): Boolean =
    profile.amneziaConfig == null && !profile.configType.isComplexType() &&
        !ProfileEligibility.isAccountInfo(profile) && !profile.server.isNullOrBlank()

/** Prefer a measured healthy fallback during observatory warm-up; keep failed peers for recovery. */
internal fun balancerFallbackRank(realDelay: Long?): Long = when {
    realDelay == null || realDelay == 0L -> Long.MAX_VALUE - 1
    realDelay < 0 -> Long.MAX_VALUE
    else -> realDelay
}
