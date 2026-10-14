package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem

/** The internal ungrouped storage bucket is not an empty user-created group. */
internal object SubscriptionVisibility {
    fun shouldDisplay(id: String, item: SubscriptionItem, serverCount: Int): Boolean =
        id != AppConfig.DEFAULT_SUBSCRIPTION_ID || !item.url.isNullOrBlank() || serverCount > 0

    fun displayRemarks(id: String, item: SubscriptionItem, localTitle: String): String =
        if (id == AppConfig.DEFAULT_SUBSCRIPTION_ID && item.url.isNullOrBlank() && (item.remarks == "Default" || item.remarks.isNullOrBlank())) localTitle
        else item.remarks.orEmpty()
}
