package com.v2ray.ang.core

/** Null is the preceding release's all-server format; empty is explicitly no members. */
internal fun selectedBalancerIds(selected: List<String>?, available: List<String>): List<String> =
    if (selected == null) available else selected.distinct().filter { it in available }
