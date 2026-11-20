package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelClientResponseTest {
    @Test
    fun reasoningOnlyResponseDoesNotBecomeAUserFacingReply() {
        // The provider's reasoning_content field is intentionally not passed to
        // selectUserFacingContent, so an empty visible response remains empty.
        assertNull(ModelClient.selectUserFacingContent(null, emptyList(), null))
    }

    @Test
    fun visibleTextWinsAndReasoningBlocksAreIgnored() {
        val blocks = listOf(
            ModelContentBlock("thinking", "用户问做什么工作的。资料：互联网行业。"),
            ModelContentBlock("reasoning", "直接回答，不编造具体岗位。"),
            ModelContentBlock("text", "互联网行业，主要做软件研发。"),
        )
        assertEquals(
            "互联网行业，主要做软件研发。",
            ModelClient.selectUserFacingContent(null, blocks, null),
        )
    }

    @Test
    fun reasoningOnlyBlocksAreRejected() {
        val blocks = listOf(
            ModelContentBlock("thinking", "内部分析"),
            ModelContentBlock("reasoning", "建议回复：互联网行业"),
        )
        assertNull(ModelClient.selectUserFacingContent(null, blocks, null))
    }

    @Test
    fun stringContentHasHiddenReasoningMarkupRemoved() {
        assertEquals(
            "互联网行业。",
            ModelClient.selectUserFacingContent(
                "<think>内部分析</think>互联网行业。",
                emptyList(),
                null,
            ),
        )
        assertNull(
            ModelClient.selectUserFacingContent(
                "<analysis>只有内部分析</analysis>",
                emptyList(),
                null,
            ),
        )
    }

    @Test
    fun legacyCompletionTextRemainsSupported() {
        assertEquals(
            "互联网行业。",
            ModelClient.selectUserFacingContent(null, emptyList(), "互联网行业。"),
        )
    }
}
