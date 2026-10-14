package com.v2ray.ang.handler

/** This represents a tasks-process probe, never connection/native-core running state. */
data class BackgroundProbeLease(val id: String, val pid: Int, val deadlineMillis: Long) {
    fun active(now: Long, ownerExists: Boolean): Boolean = !id.isNullOrBlank() && pid > 0 && now < deadlineMillis && ownerExists
}
