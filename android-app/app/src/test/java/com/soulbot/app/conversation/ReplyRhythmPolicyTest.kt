package com.soulbot.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRhythmPolicyTest {
    @Test
    fun repeatedLowInformationAcknowledgementsCanPause() {
        val thread = listOf(
            "in" to "我今天有点累",
            "out" to "今天工作很忙吗？",
            "in" to "嗯嗯",
            "out" to "那今天早点休息吧",
            "in" to "对呀",
        )
        assertTrue(ReplyRhythmPolicy.shouldWaitForLowInformation(thread, listOf("对呀")))
    }

    @Test
    fun singleAcknowledgementAndUnansweredQuestionAreNotPaused() {
        val single = listOf("in" to "你好", "out" to "你好呀", "in" to "嗯")
        assertFalse(ReplyRhythmPolicy.shouldWaitForLowInformation(single, listOf("嗯")))

        val answeringQuestion = listOf(
            "in" to "我喜欢看展",
            "out" to "你最近看什么展？",
            "in" to "嗯嗯",
        )
        assertFalse(ReplyRhythmPolicy.shouldWaitForLowInformation(answeringQuestion, listOf("嗯嗯")))
    }

    @Test
    fun importantSignalIsNeverSuppressedAsFiller() {
        val thread = listOf("in" to "我有点难过", "out" to "怎么了？", "in" to "你怎么不理我")
        assertFalse(
            ReplyRhythmPolicy.shouldWaitForLowInformation(thread, listOf("你怎么不理我")),
        )
    }

    @Test
    fun directAiQuestionsAndDoubaoJokesUseNaturalDeflectionInsteadOfLiteralAnswering() {
        for (message in listOf("你是AI吗？", "你是不是机器人", "说话好豆包")) {
            val guidance = ReplyRhythmPolicy.guidance(listOf("in" to message), listOf(message))
            assertTrue(guidance.contains("轻松反问"))
            assertTrue(guidance.contains("不输出模型名称"))
            assertFalse(guidance.contains("先回答对方实际问的问题"))
            assertFalse(guidance.contains("必须如实回答"))
        }
    }

    @Test
    fun guidanceSuggestsVariationAfterConsecutiveStatementReplies() {
        val thread = listOf(
            "in" to "我周末喜欢看展",
            "out" to "看展挺有意思的",
            "in" to "我比较喜欢摄影展",
            "out" to "摄影展能看到不少有意思的表达",
        )
        val guidance = ReplyRhythmPolicy.guidance(thread, listOf("最近看了一个很喜欢的展"))
        assertTrue(guidance.contains("换一个新切面"))
        assertTrue(guidance.contains("不强制提问"))
    }
}
