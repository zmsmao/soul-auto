package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSeenTrackerTest {

    @Test
    fun `same display name in different active thread scopes remains unseen`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("session:one", listOf("你好"))

        assertEquals(listOf("你好"), tracker.unseen("session:two", listOf("你好")))
    }

    @Test
    fun `same text in different conversations remains unseen`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertEquals(listOf("你好"), tracker.unseen("小红", listOf("你好")))
    }

    @Test
    fun `sent conversation messages are filtered`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertTrue(tracker.unseen("小明", listOf("你好")).isEmpty())
        assertEquals(listOf("在吗"), tracker.unseen("小明", listOf("你好", "在吗")))
    }

    @Test
    fun `same text sent again is still a new occurrence`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertEquals(listOf("你好"), tracker.unseen("小明", listOf("你好", "你好")))
    }

    @Test
    fun `durable confirmation filters earlier events after tracker restart`() {
        val restarted = ConversationSeenTracker()
        val events = listOf(
            ConversationIncomingEvent("m1", "你好"),
            ConversationIncomingEvent("m2", "在吗"),
        )

        assertEquals(
            listOf(events.last()),
            restarted.unseenEvents("thread", events, setOf("m1")),
        )
    }

    @Test
    fun `distinct event keys preserve repeated identical message text`() {
        val tracker = ConversationSeenTracker()
        val events = listOf(
            ConversationIncomingEvent("m1", "嗯"),
            ConversationIncomingEvent("m2", "嗯"),
        )

        assertEquals(events, tracker.unseenEvents("thread", events, emptySet()))
        tracker.markSeenEvents("thread", listOf(events.first()))
        assertEquals(listOf(events.last()), tracker.unseenEvents("thread", events, emptySet()))
    }

    @Test
    fun `scene independent durable keys work across tracker scopes`() {
        val tracker = ConversationSeenTracker()
        val events = listOf(ConversationIncomingEvent("m1", "你好"))
        assertTrue(tracker.unseenEvents("thread", events, setOf("m1")).isEmpty())
    }

    @Test
    fun `scrolled visible window keeps suffix overlap`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("第一条", "第二条"))

        assertEquals(
            listOf("第三条"),
            tracker.unseen("小明", listOf("第二条", "第三条")),
        )
    }
}
