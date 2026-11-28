package com.soulbot.app.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageQueuePolicyTest {
    @Test
    fun `aligns all twenty messages without an eight item cap`() {
        val messages = (1..20).map { "message-$it" }
        val keys = (1..20).map { "event-$it" }

        val aligned = MessageQueuePolicy.alignIncoming(messages, keys)

        assertEquals(20, aligned.size)
        assertEquals("message-1", aligned.first().text)
        assertEquals("event-20", aligned.last().key)
    }

    @Test
    fun `repeated identical text remains separate when event keys differ`() {
        val aligned = MessageQueuePolicy.alignIncoming(
            listOf("嗯", "嗯", "嗯"),
            listOf("key-1", "key-2", "key-3"),
        )

        assertEquals(3, aligned.size)
        assertEquals(setOf("key-1", "key-2", "key-3"), aligned.map { it.key }.toSet())
    }

    @Test
    fun `future retry at queue head blocks later events`() {
        val now = 1_000_000L
        assertTrue(MessageQueuePolicy.isReadyHead("PENDING", 0L, now))
        assertTrue(MessageQueuePolicy.isReadyHead("RETRY_WAIT", now, now))
        org.junit.Assert.assertFalse(MessageQueuePolicy.isReadyHead("RETRY_WAIT", now + 1L, now))
        org.junit.Assert.assertFalse(MessageQueuePolicy.isReadyHead("SEND_UNCERTAIN", 0L, now))
        org.junit.Assert.assertFalse(MessageQueuePolicy.isReadyHead("DEAD_LETTER", 0L, now))
        org.junit.Assert.assertFalse(MessageQueuePolicy.isReadyHead("PROCESSING", 0L, now))
    }

    @Test
    fun `terminal no reply decisions are never claimable again`() {
        val now = 1_000_000L
        assertFalse(MessageQueuePolicy.isReadyHead(MessageQueueState.NO_REPLY_REQUIRED.name, 0L, now))
        assertFalse(MessageQueuePolicy.isReadyHead(MessageQueueState.SENT.name, 0L, now))
        assertFalse(MessageQueuePolicy.isReadyHead(MessageQueueState.SUPPRESSED_POLICY.name, 0L, now))
    }

    @Test
    fun `retry delay backs off and remains capped`() {
        assertEquals(3_000L, MessageQueuePolicy.retryDelay(1))
        assertEquals(10_000L, MessageQueuePolicy.retryDelay(2))
        assertEquals(30_000L, MessageQueuePolicy.retryDelay(3))
        assertEquals(120_000L, MessageQueuePolicy.retryDelay(4))
        assertEquals(300_000L, MessageQueuePolicy.retryDelay(9))
    }

    @Test
    fun `empty identity alignment does not invent events`() {
        assertTrue(MessageQueuePolicy.alignIncoming(listOf("hi"), emptyList()).isEmpty())
    }
}
