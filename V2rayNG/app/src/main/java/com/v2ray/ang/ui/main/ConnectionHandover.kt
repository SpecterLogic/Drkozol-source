package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem

/** AWG owns another VpnService; a transport change must release the preceding tunnel first. */
internal fun requiresTransportHandover(previous: ProfileItem?, next: ProfileItem?): Boolean =
    previous != null && next != null && (previous.amneziaConfig != null) != (next.amneziaConfig != null)
