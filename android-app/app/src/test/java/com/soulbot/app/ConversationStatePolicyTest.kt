package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStatePolicyTest {
    @Test fun sameVisibleSnapshotDoesNotAdvanceTopicCounterTwice() {
        val thread = listOf("in" to "我家猫最近总在半夜跑酷", "out" to "它是不是晚上精力比较旺", "in" to "对，白天一直睡觉")
        val first = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, thread, now = 100L)
        val repeated = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, thread, first, now = 200L)
        assertEquals(1, first.topicTurnCount)
        assertEquals(first.topicTurnCount, repeated.topicTurnCount)
        assertEquals(first.lastProgressAt, repeated.lastProgressAt)
    }

    @Test fun topicContinuationIncreasesCounterButLowInformationDoesNot() {
        val firstThread = listOf("in" to "我家猫最近总在半夜跑酷")
        val first = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, firstThread, now = 100L)
        val continuingThread = firstThread + listOf("out" to "它是不是精力太旺了", "in" to "对，半夜一直跑来跑去")
        val continuing = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, continuingThread, first, now = 200L)
        val lowInfoThread = continuingThread + listOf("out" to "哈哈", "in" to "嗯")
        val lowInfo = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, lowInfoThread, continuing, now = 250L)
        val changedThread = lowInfoThread + listOf("out" to "那换个话题", "in" to "我明天要去爬山")
        val changed = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, changedThread, lowInfo, now = 300L)
        assertTrue(continuing.topicTurnCount > first.topicTurnCount)
        assertEquals(continuing.topicTurnCount, lowInfo.topicTurnCount)
        assertEquals(1, changed.topicTurnCount)
        assertEquals(300L, changed.lastProgressAt)
    }

    @Test fun tracksLastQuestionBanterAndLowInformationCount() {
        val thread = listOf("in" to "我今天有点累", "out" to "今天工作很忙吗？", "in" to "嗯",
            "out" to "哈哈，今天先早点休息", "in" to "哦哦")
        val state = ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, thread, now = 100L)
        assertEquals("今天工作很忙吗？", state.lastQuestion)
        assertTrue(state.lastBanter.contains("哈哈"))
        assertEquals(2, state.lowInformationCount)
    }

    @Test fun stateIsIsolatedByScene() {
        val thread = listOf("in" to "我喜欢爬山")
        assertEquals("CHAT", ConversationStatePolicy.evolve("thread-1", ReplyScene.CHAT, thread).scene)
        assertEquals("QIYU", ConversationStatePolicy.evolve("thread-1", ReplyScene.QIYU, thread).scene)
    }
}

class SendAttemptPolicyTest {
    @Test fun preparedMayRetryButClickedMustBeReconciled() {
        assertEquals(SendAttemptDisposition.READY, SendAttemptPolicy.disposition("PREPARED", 0, 0))
        assertEquals(SendAttemptDisposition.UNCERTAIN, SendAttemptPolicy.disposition("CLICKED", 0, 0))
        assertEquals(SendAttemptDisposition.ALREADY_CONFIRMED, SendAttemptPolicy.disposition("CLICKED", 1, 0))
    }

    @Test fun confirmedAttemptIsNeverSentAgain() {
        assertEquals(SendAttemptDisposition.ALREADY_CONFIRMED, SendAttemptPolicy.disposition("CONFIRMED", 1, 0))
    }
}
