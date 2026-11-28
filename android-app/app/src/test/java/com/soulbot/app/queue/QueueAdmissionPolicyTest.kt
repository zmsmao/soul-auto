package com.soulbot.app.queue

import com.soulbot.app.conversation.ConversationIncomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class QueueAdmissionPolicyTest {
    private fun event(key: String, text: String) = ConversationIncomingEvent(key, text)

    @Test
    fun `only trailing unanswered inbound messages are admitted`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(
                event("old-1", "上周聊过的历史"),
                event("old-2", "我已经回答过了"),
                event("new-1", "你周末有空吗"),
                event("new-2", "想约你喝咖啡"),
            ),
            pendingCount = 2,
        )

        assertEquals(listOf("new-1", "new-2"), result.accepted.map { it.key })
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `no pending tail means no queue admission`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(event("old-1", "历史消息")),
            pendingCount = 0,
        )

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `official notification conversations are rejected before event persistence`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "Soul 官方通知",
            incoming = listOf(event("notice-1", "官方通知：活动开始")),
            pendingCount = 1,
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(1, result.rejected[QueueAdmissionRejection.PLATFORM_NOTICE])
    }

    @Test
    fun `system generated in chat rows are filtered but later real messages survive`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(
                event("system-1", "你们已成为好友，开始聊天吧"),
                event("human-1", "你好，刚刚看到你的动态"),
                event("system-2", "对方撤回了一条消息"),
                event("human-2", "周末想不想出去走走"),
            ),
            pendingCount = 4,
        )

        assertEquals(listOf("human-1", "human-2"), result.accepted.map { it.key })
        assertEquals(2, result.rejected[QueueAdmissionRejection.SYSTEM_GENERATED_CONTENT])
    }

    @Test
    fun `blank keys blank content and duplicate event keys are not admitted`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(
                event("", "正文"),
                event("blank", "   "),
                event("same", "第一条"),
                event("same", "重复投递"),
            ),
            pendingCount = 4,
        )

        assertEquals(listOf("same"), result.accepted.map { it.key })
        assertEquals(1, result.rejected[QueueAdmissionRejection.EMPTY_EVENT_KEY])
        assertEquals(1, result.rejected[QueueAdmissionRejection.EMPTY_CONTENT])
        assertEquals(1, result.rejected[QueueAdmissionRejection.DUPLICATE_EVENT_KEY])
    }

    @Test
    fun `human messages mentioning friendship notices are retained unless exact system template`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(
                event("human-1", "我通过了你的好友申请，现在可以开始聊天了，哈哈"),
                event("human-2", "你们已经成为好友的朋友也在这个群里"),
                event("human-3", "系统消息这个词我只是拿来举例"),
                event("system-1", "你们已经成为好友，可以开始聊天了！"),
            ),
            pendingCount = 4,
        )

        assertEquals(
            listOf("human-1", "human-2", "human-3"),
            result.accepted.map { it.key },
        )
        assertEquals(1, result.rejected[QueueAdmissionRejection.SYSTEM_GENERATED_CONTENT])
    }

    @Test
    fun `media and shared post messages remain actionable content`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "小林",
            incoming = listOf(
                event("image", "[图片]"),
                event("emoji", "[表情]"),
                event("post", "[分享动态]今天去了海边"),
            ),
            pendingCount = 3,
        )

        assertEquals(listOf("image", "emoji", "post"), result.accepted.map { it.key })
    }

    @Test
    fun `ordinary people mentioning official topics are not blocked`() {
        val result = QueueAdmissionPolicy.filter(
            displayName = "官方摄影社的阿杰",
            incoming = listOf(event("human", "我刚看到官方通知，不过周末还是有空")),
            pendingCount = 1,
        )

        assertEquals(listOf("human"), result.accepted.map { it.key })
        assertFalse(result.isEmpty)
    }
}
