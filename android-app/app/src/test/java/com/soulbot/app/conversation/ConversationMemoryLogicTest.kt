package com.soulbot.app.conversation
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

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMemoryLogicTest {
    @Test
    fun `memory scope requires matching thread and scene and excludes legacy rows`() {
        val current = ConversationMemoryEntry("1", "小明", "in", "猫", 1, "chat", "thread-a", "CHAT")
        val otherScene = current.copy(eventKey = "2", scene = "QIYU")
        val otherThread = current.copy(eventKey = "3", threadId = "thread-b")
        val legacy = current.copy(eventKey = "4", threadId = "LEGACY_UNKNOWN", scene = "LEGACY_UNKNOWN")

        assertEquals(true, ConversationMemoryLogic.isInScope(current, "thread-a", ReplyScene.CHAT))
        assertEquals(false, ConversationMemoryLogic.isInScope(otherScene, "thread-a", ReplyScene.CHAT))
        assertEquals(false, ConversationMemoryLogic.isInScope(otherThread, "thread-a", ReplyScene.CHAT))
        assertEquals(false, ConversationMemoryLogic.isInScope(legacy, "thread-a", ReplyScene.CHAT))
    }

    @Test
    fun `first visible thread stores every message`() {
        val visible = listOf(
            "in" to "你好",
            "out" to "刚忙完",
            "in" to "吃饭了吗",
        )

        assertEquals(
            visible,
            ConversationMemoryLogic.messagesAfterOverlap(emptyList(), visible),
        )
    }

    @Test
    fun `only appends messages after the shared tail`() {
        val existing = listOf(
            "in" to "你好",
            "out" to "刚忙完",
            "in" to "吃饭了吗",
        )
        val visible = listOf(
            "out" to "刚忙完",
            "in" to "吃饭了吗",
            "out" to "还没，你呢",
        )

        assertEquals(
            listOf("out" to "还没，你呢"),
            ConversationMemoryLogic.messagesAfterOverlap(existing, visible),
        )
    }

    @Test
    fun `maximum overlap handles repeated messages without duplicating them`() {
        val existing = listOf(
            "in" to "嗯",
            "out" to "怎么啦",
            "in" to "嗯",
        )
        val visible = listOf(
            "in" to "嗯",
            "out" to "怎么啦",
            "in" to "嗯",
        )

        assertEquals(
            emptyList<Pair<String, String>>(),
            ConversationMemoryLogic.messagesAfterOverlap(existing, visible),
        )
    }

    @Test
    fun `relevant memory ranks the matching old topic first`() {
        val candidates = listOf(
            entry("最近一直下雨，没出门", 3),
            entry("我家那只猫半夜总跑酷", 2),
            entry("上次说的酸梅酒真的不好喝", 1),
        )

        val result = ConversationMemoryLogic.rankRelevant(
            "你家猫咪现在还会半夜跑酷吗",
            candidates,
            2,
        )

        assertEquals(listOf("我家那只猫半夜总跑酷"), result.map { it.content })
    }

    private fun entry(content: String, time: Long) = ConversationMemoryEntry(
        eventKey = time.toString(),
        contact = "小明",
        role = "in",
        content = content,
        observedAt = time,
        source = "chat",
    )
}
