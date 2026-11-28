package com.soulbot.app.policy

import com.soulbot.app.conversation.comparableText

object SquareBrowsePolicy {
    const val MAX_TRANSIENT_ATTEMPTS = 2

    /**
     * The square can keep the previous viewport partially visible after a short swipe.
     * Use the profile name as a session-level guard so that overlapping cards do not
     * open the same person's private-chat entry twice in one automation run.
     */
    fun profileKey(name: String): String = comparableText(name).take(120)

    fun viewportKey(text: String, cardTop: Int, bucketSizePx: Int): String {
        val safeBucket = bucketSizePx.coerceAtLeast(1)
        return "${comparableText(text).take(120)}@${cardTop / safeBucket}"
    }

    /**
     * Only labels spatially attached to the avatar are treated as status badges.
     * Ordinary post text that merely mentions streaming is not enough to skip a user.
     */
    fun liveActivitySkipReason(
        labelsNearAvatar: Iterable<String>,
        allCardLabels: Iterable<String>,
    ): String? {
        val nearby = labelsNearAvatar.map(::comparableText).filter(String::isNotBlank)
        val all = allCardLabels.map(::comparableText).filter(String::isNotBlank)

        val activeBadge = nearby.any { label ->
            listOf("活动中", "直播中", "正在直播", "主播直播中").any(label::contains)
        }
        if (activeBadge) return "ACTIVE_BADGE"

        val liveBadge = nearby.any { label ->
            listOf("直播间", "进入直播", "直播入口").any(label::contains)
        }
        if (liveBadge) return "LIVE_ENTRY_BADGE"

        val hotActivity = all.any { it.contains("正在热聊") }
        val joinEntry = all.any { it.contains("一键加入") }
        if (hotActivity && joinEntry) return "LIVE_ACTIVITY_ENTRY"

        return null
    }

    fun isSafeAvatarClickTarget(
        avatarWidth: Int,
        avatarHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean {
        if (avatarWidth <= 0 || avatarHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }
        val maxWidth = maxOf(72, (avatarWidth * 2.8f).toInt())
        val maxHeight = maxOf(72, (avatarHeight * 2.8f).toInt())
        return targetWidth <= maxWidth && targetHeight <= maxHeight
    }

    fun shouldRetryTransientFailure(attemptCount: Int): Boolean =
        attemptCount < MAX_TRANSIENT_ATTEMPTS
}
