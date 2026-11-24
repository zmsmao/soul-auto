package com.soulbot.app.conversation.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextBuilderTest {
    @Test
    fun keepsFragmentOrderAndAllContentWhenBudgetIsSufficient() {
        val built = ConversationContextBuilder.build(
            baseSystemPrompt = "BASE",
            fragments = listOf(
                ContextFragment("later", "C", priority = 1, order = 30, required = true),
                ContextFragment("middle", "B", priority = 20, order = 20, required = false),
                ContextFragment("earlier", "A", priority = 1, order = 10, required = true),
            ),
            history = listOf("in" to "你好", "out" to "我在"),
            focusIncoming = listOf("你好"),
            maxInputTokens = 1000,
            reservedOutputTokens = 0,
        )

        assertEquals("BASEABC", built.systemPrompt)
        assertEquals(listOf("user" to "你好", "assistant" to "我在"), built.messages)
        assertTrue(built.droppedFragmentIds.isEmpty())
        assertEquals(0, built.droppedHistoryMessages)
        assertEquals(0, built.overflowTokens)
    }

    @Test
    fun highPriorityOptionalContextWinsWhenBudgetIsTight() {
        val high = "重".repeat(20)
        val low = "次".repeat(20)
        val built = ConversationContextBuilder.buildSystemPrompt(
            baseSystemPrompt = "",
            fragments = listOf(
                ContextFragment("low-priority", low, priority = 10, order = 1),
                ContextFragment("high-priority", high, priority = 90, order = 2),
            ),
            maxInputTokens = 30,
            reservedOutputTokens = 0,
        )

        assertTrue(built.systemPrompt.contains(high))
        assertFalse(built.systemPrompt.contains(low))
        assertEquals(listOf("low-priority"), built.droppedFragmentIds)
    }

    @Test
    fun requiredInstructionsAndRecentHistorySurviveAnOverBudgetRequest() {
        val history = (1..10).map { "in" to "第${it}条消息" }
        val built = ConversationContextBuilder.build(
            baseSystemPrompt = "核心规则",
            fragments = listOf(
                ContextFragment("required-focus", "\n回应最新消息", 100, 1, required = true),
                ContextFragment("optional-memory", "\n很长的旧记忆".repeat(20), 10, 2),
            ),
            history = history,
            focusIncoming = listOf("第10条消息"),
            maxInputTokens = 4,
            reservedOutputTokens = 0,
        )

        assertTrue(built.systemPrompt.contains("核心规则"))
        assertTrue(built.systemPrompt.contains("回应最新消息"))
        assertFalse(built.systemPrompt.contains("很长的旧记忆"))
        assertEquals(6, built.messages.size)
        assertEquals("第10条消息", built.messages.last().second)
        assertTrue(built.overflowTokens > 0)
        assertEquals(listOf("optional-memory"), built.droppedFragmentIds)
    }

    @Test
    fun historicalMessagesAndContextFragmentsCompeteForTheSameTokenBudget() {
        val base = "CORE"
        val olderMessage = "更早的聊天"
        val recent = (1..6).map { "in" to "最近消息$it" }
        val requiredCost = ContextTokenEstimator.estimate(base) +
            recent.sumOf { ContextTokenEstimator.estimate(it.second) }
        val budget = requiredCost + ContextTokenEstimator.estimate(olderMessage)

        val built = ConversationContextBuilder.build(
            baseSystemPrompt = base,
            fragments = listOf(
                ContextFragment(
                    id = "lower-priority-memory",
                    content = "联系人记忆线索".repeat(3),
                    priority = 65,
                    order = 60,
                ),
            ),
            history = listOf("in" to olderMessage) + recent,
            focusIncoming = emptyList(),
            maxInputTokens = budget,
            reservedOutputTokens = 0,
        )

        assertEquals(7, built.messages.size)
        assertEquals(olderMessage, built.messages.first().second)
        assertEquals(listOf("lower-priority-memory"), built.droppedFragmentIds)
    }

    @Test
    fun tokenEstimateDoesNotUnderweightChineseRelativeToShortLatinText() {
        assertTrue(
            ContextTokenEstimator.estimate("广州周末见面") >
                ContextTokenEstimator.estimate("Hi"),
        )
    }
}
