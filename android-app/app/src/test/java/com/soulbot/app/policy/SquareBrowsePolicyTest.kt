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
    fun profileKey_ignoresDecorativeSpacingAndPunctuation() {
        assertEquals(
            SquareBrowsePolicy.profileKey(" 小 明。"),
            SquareBrowsePolicy.profileKey("小明"),
        )
    }
}
