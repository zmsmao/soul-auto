package com.soulbot.app.conversation

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadConversationPolicyTest {
    private val now = LocalDateTime.of(2026, 9, 12, 12, 0)

    @Test
    fun `keeps unread contacts in discovered list order regardless of timestamps`() {
        val rows = listOf(
            UnreadConversationCandidate("最新", "刚刚", 0),
            UnreadConversationCandidate("较早", "2小时前", 1),
            UnreadConversationCandidate("最早", "昨天 08:30", 2),
            UnreadConversationCandidate("中间", "35分钟前", 3),
        )

        assertEquals(
            listOf("最新", "较早", "最早", "中间"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }

    @Test
    fun `overlapping pages retain one contact and stable global display order`() {
        val pageOne = listOf(
            UnreadConversationCandidate("小明", "10分钟前", 0),
            UnreadConversationCandidate("小红", "8分钟前", 1),
        )
        val pageTwo = listOf(
            UnreadConversationCandidate("小红", "8分钟前", 0),
            UnreadConversationCandidate("小张", "5分钟前", 1),
        )
        val merged = UnreadConversationPolicy.mergeVisiblePages(listOf(pageOne, pageTwo))

        assertEquals(listOf("小明", "小红", "小张"), merged.map { it.name })
        assertEquals(listOf(0, 1, 2), merged.map { it.displayOrder })
    }

    @Test
    fun `message preview priority does not change FIFO contact order`() {
        val rows = listOf(
            UnreadConversationCandidate("普通旧消息", "昨天 08:30", 2, "今天吃饭了吗"),
            UnreadConversationCandidate("联系方式", "刚刚", 0, "方便加个微信吗"),
            UnreadConversationCandidate("情绪信号", "10分钟前", 1, "你怎么不理我"),
        )

        assertEquals(
            listOf("普通旧消息", "联系方式", "情绪信号"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }

    @Test
    fun `same priority and timestamp preserve top to bottom display order`() {
        val rows = listOf(
            UnreadConversationCandidate("顶部", "刚刚", 0, "方便加个微信吗"),
            UnreadConversationCandidate("底部", "刚刚", 1, "哪天见面？"),
        )

        assertEquals(
            listOf("顶部", "底部"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }

    @Test
    fun `priority score recognises contact meeting photo and emotional signals`() {
        assertEquals(100, UnreadConversationPolicy.priorityScore("方便加个微信吗"))
        assertEquals(100, UnreadConversationPolicy.priorityScore("哪天见面？"))
        assertEquals(100, UnreadConversationPolicy.priorityScore("可以发张照片吗"))
        assertEquals(100, UnreadConversationPolicy.priorityScore("你怎么不理我"))
        assertEquals(0, UnreadConversationPolicy.priorityScore("我今天下班去吃饭"))
    }

    @Test
    fun `parses clock weekday and concrete date labels`() {
        assertEquals(90L, UnreadConversationPolicy.ageMinutes("10:30", now))
        assertEquals(4L * 24L * 60L + 12L * 60L, UnreadConversationPolicy.ageMinutes("周二", now))
        assertEquals(4L * 24L * 60L + 12L * 60L, UnreadConversationPolicy.ageMinutes("9月8日", now))
    }

    @Test
    fun `future clock is treated as previous day`() {
        assertEquals(13L * 60L, UnreadConversationPolicy.ageMinutes("23:00", now))
    }

    @Test
    fun `unknown labels sort after recognized times`() {
        val rows = listOf(
            UnreadConversationCandidate("未知", "置顶", 2),
            UnreadConversationCandidate("最新", "刚刚", 0),
            UnreadConversationCandidate("最早", "1小时前", 1),
        )

        assertEquals(
            listOf("最新", "最早", "未知"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }
}
