package com.tytv.app.ui

import com.tytv.app.model.Channel

/**
 * Channel-surfing order: exactly the home Live TV section's order —
 * grouped by (provider, group) in first-appearance order, number-sorted
 * within each group. Surfing walks this list, so up/down moves through
 * channels the way they appear on the home screen. Pure, so unit tests
 * can pin the order.
 */
object SurfOrder {
    fun liveOrder(channels: List<Channel>): List<Channel> {
        return channels.groupBy { it.provider to it.group }.values.flatMap { group ->
            group.sortedWith(
                compareBy<Channel> { it.number == null }.thenBy { it.number }
            )
        }
    }
}
