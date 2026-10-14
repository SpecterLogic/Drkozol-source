package com.v2ray.ang.enums

import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Enum defining different notification channels.
 * Each channel has a unique channelId, notificationId, and display name.
 */
enum class NotificationChannelType(
    val channelId: String,
    @StringRes val channelNameRes: Int,
    val notificationId: Int
) {
    AMNEZIA("amnezia_connection", R.string.notification_channel_service, 14),
    SUBSCRIPTION_RESULT("subscription_result", R.string.notification_channel_subscription_updates, 15),
    CONNECTION_AUTOMATION("connection_automation", R.string.notification_channel_service, 16),
    SUBSCRIPTION_UPDATE(
        channelId = "subscription_update_channel",
        channelNameRes = R.string.notification_channel_subscription_updates,
        notificationId = 13
    ),
    CORE_TEST(
        channelId = "core_test_channel",
        channelNameRes = R.string.notification_channel_connection_checks,
        notificationId = 12
    )
}
