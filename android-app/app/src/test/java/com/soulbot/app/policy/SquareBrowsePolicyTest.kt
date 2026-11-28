package com.soulbot.app.policy
import com.soulbot.app.*
import com.soulbot.app.accessibility.*
import com.soulbot.app.app.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareBrowsePolicyTest {
    @Test
    fun sameTextAtDifferentPositions_doesNotCollapseDifferentCards() {
        val first = SquareBrowsePolicy.viewportKey("今天下班啦", 120, 48)
        val second = SquareBrowsePolicy.viewportKey("今天下班啦", 520, 48)

        assertNotEquals(first, second)
    }

    @Test
    fun transientFailure_getsOneRetryBeforeSkippingCurrentViewport() {
        assertTrue(SquareBrowsePolicy.shouldRetryTransientFailure(0))
        assertTrue(SquareBrowsePolicy.shouldRetryTransientFailure(1))
        assertFalse(SquareBrowsePolicy.shouldRetryTransientFailure(2))
    }

    @Test
    fun activeBadgeBesideAvatar_skipsCardBeforeClick() {
        assertEquals(
            "ACTIVE_BADGE",
            SquareBrowsePolicy.liveActivitySkipReason(
                labelsNearAvatar = listOf("活动中"),
                allCardLabels = listOf("耳机一戴 谁都不爱"),
            ),
        )
    }

    @Test
    fun hotChatAndJoinEntry_skipsLiveActivityCard() {
        assertEquals(
            "LIVE_ACTIVITY_ENTRY",
            SquareBrowsePolicy.liveActivitySkipReason(
                labelsNearAvatar = emptyList(),
                allCardLabels = listOf("正在热聊", "一键加入"),
            ),
        )
    }

    @Test
    fun ordinaryPostMentioningLive_doesNotSkipCard() {
        assertEquals(
            null,
            SquareBrowsePolicy.liveActivitySkipReason(
                labelsNearAvatar = listOf("关注"),
                allCardLabels = listOf("昨晚看了一场直播，挺有意思"),
            ),
        )
    }

    @Test
    fun oversizedCardClickTarget_isRejected() {
        assertFalse(SquareBrowsePolicy.isSafeAvatarClickTarget(48, 48, 360, 640))
        assertTrue(SquareBrowsePolicy.isSafeAvatarClickTarget(48, 48, 64, 56))
    }

    @Test
    fun profileKey_ignoresDecorativeSpacingAndPunctuation() {
        assertEquals(
            SquareBrowsePolicy.profileKey(" 小 明。"),
            SquareBrowsePolicy.profileKey("小明"),
        )
    }
}
