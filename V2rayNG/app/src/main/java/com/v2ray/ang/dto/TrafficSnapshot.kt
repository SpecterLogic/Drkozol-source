package com.v2ray.ang.dto

import java.io.Serializable

data class TrafficSnapshot(
    val uploadPerSecond: Long = 0,
    val downloadPerSecond: Long = 0,
    val sessionBytes: Long = 0,
) : Serializable
