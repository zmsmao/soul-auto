package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRegressionCorpusTest {
    @Test
    fun corpusContainsNineAnnotatedAndDeidentifiedEvaluationCases() {
        assertEquals(9, ChatRegressionCorpus.cases.size)
        assertEquals(9, ChatRegressionCorpus.cases.map { it.id }.distinct().size)

        ChatRegressionCorpus.cases.forEach { case ->
            assertTrue(case.title, case.messages.isNotEmpty())
            assertTrue(case.title, case.observedFailure.isNotBlank())
            assertTrue(case.title, case.failureType.isNotBlank())
            assertTrue(case.title, case.expectedBehavior.isNotBlank())
            assertTrue(case.title, case.idealReply.isNotBlank())
            case.messages.forEach { message ->
                assertTrue("invalid role: " + case.title, message.role == "in" || message.role == "out")
                assertTrue("context missing: " + case.title, message.context.isNotBlank())
                assertTrue("intent missing: " + case.title, message.intent.isNotBlank())
                assertTrue("text missing: " + case.title, message.text.isNotBlank())
            }

            val replay = case.asReplayCase()
            assertNull(case.title + ": " + ReplyReplayLogic.validationError(replay),
                ReplyReplayLogic.validationError(replay))
            assertEquals("Evaluation cases must never become style references", "",
                ReplyReplayLogic.learningReply(replay))
        }
    }

    @Test
    fun criticismAboutAiOrOverlyFormalToneSwitchesTheReplyPolicy() {
        val feedbackMessages = listOf(
            "你这个问题是 AI 生成的吗？",
            "很官方",
            "太书面化",
            "一股 AI 味",
            "你说话好豆包",
            "你说话怎么这么官方？",
        )

        feedbackMessages.forEach { feedback ->
            val guidance = ConversationReplyContext.responseGuidance(
                listOf("out" to "你喜欢主动冲锋还是偏稳着运营？", "in" to feedback),
                listOf(feedback),
            )
            assertTrue(feedback, guidance.contains("简短"))
            assertTrue(feedback, guidance.contains("回到"))
            assertTrue(feedback, guidance.contains("不要解释模型"))
        }

        val directQuestionGuidance = ConversationReplyContext.responseGuidance(
            listOf("out" to "你喜欢哪种游戏？", "in" to "你这个问题是 AI 生成的吗？"),
            listOf("你这个问题是 AI 生成的吗？"),
        )
        assertFalse(directQuestionGuidance.contains("必须如实回答"))
        assertTrue(directQuestionGuidance.contains("一句简短自然的轻松反问"))

        val ordinaryTopics = listOf("官方活动明天开始", "我去看机器人比赛", "这个视频是 AI 生成的吗？")
        ordinaryTopics.forEach { topic ->
            val guidance = ConversationReplyContext.responseGuidance(
                listOf("in" to topic), listOf(topic),
            )
            assertFalse(topic, guidance.contains("对方在反馈我的表达像 AI"))
        }

        val toneQuestion = "你说话怎么这么官方？"
        val toneGuidance = ConversationReplyContext.responseGuidance(
            listOf("out" to "你平时玩什么游戏？", "in" to toneQuestion),
            listOf(toneQuestion),
        )
        assertTrue(toneGuidance.contains("对方在反馈我的表达太官方、太书面或太机械"))
        assertFalse(toneGuidance.contains("必须如实回答"))
    }

    @Test
    fun repeatedTopicAndBanterLoopsAreExplicitlyDiscouraged() {
        val guidance = ConversationReplyContext.responseGuidance(
            listOf(
                "out" to "那我直接转正，被点名还挺有面子。",
                "in" to "哈哈，当然啦。",
                "out" to "我先坐稳这个位置，别说我是临时工。",
                "in" to "哈哈，那你放心吧。",
            ),
            listOf("哈哈，那你放心吧。"),
        )
        assertTrue(guidance.contains("同一个细节换词复述"))
        assertTrue(guidance.contains("一个玩笑接住一两次即可"))
    }

    @Test
    fun basePromptSeparatesSharedDynamicsFromSignaturesAndAvoidsInventedPersonality() {
        val prompt = ModelClient.buildSystemPrompt(historicalReplay = true)
        assertTrue(prompt.contains("[分享动态]"))
        assertTrue(prompt.contains("不等同于个人签名"))
        assertTrue(prompt.contains("来源不清"))
        assertTrue(prompt.contains("未经确认的性格标签"))
        assertFalse(prompt.contains("慢热"))
        assertTrue(prompt.contains("本人已明确提供"))
    }
}
