package com.v2ray.ang.ui.main

import java.util.UUID

/** Main-thread request ownership; a late reply must not complete a newer test. */
internal class MainTestRequests {
    data class Bulk(val id: String, val groupId: String, val kind: ProbeKind = ProbeKind.REAL)

    private data class Current(val id: String, val guid: String)
    private var current: Current? = null
    var bulk: Bulk? = null
        private set
    val isTesting: Boolean get() = current != null || bulk != null

    fun beginCurrent(guid: String = ""): String = UUID.randomUUID().toString().also { current = Current(it, guid) }

    fun completeCurrent(id: String, connectedGuid: String? = null): Boolean {
        val request = current ?: return false
        if (id != request.id || (connectedGuid != null && request.guid.isNotEmpty() && request.guid != connectedGuid)) return false
        current = null
        return true
    }

    fun invalidateCurrent() {
        current = null
    }

    fun beginBulk(groupId: String, kind: ProbeKind = ProbeKind.REAL): Bulk = Bulk(UUID.randomUUID().toString(), groupId, kind).also { bulk = it }

    fun completeBulk(id: String): Bulk? {
        val request = bulk?.takeIf { it.id == id } ?: return null
        bulk = null
        return request
    }

    fun cancelBulk() {
        bulk = null
    }
}
