package com.soulbot.app.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTaskPolicyTest {
    @Test
    fun `only a cleared done contact can reenter the queue`() {
        assertFalse(ContactTaskPolicy.shouldEnqueueAgain(ContactTaskState.PENDING, true))
        assertFalse(ContactTaskPolicy.shouldEnqueueAgain(ContactTaskState.DONE, false))
        assertTrue(ContactTaskPolicy.shouldEnqueueAgain(ContactTaskState.DONE, true))
    }

    @Test
    fun `only pending or due retry at the FIFO head can be claimed`() {
        val now = 100_000L
        assertTrue(ContactTaskPolicy.canClaim(ContactTaskState.PENDING, 0L, now))
        assertTrue(ContactTaskPolicy.canClaim(ContactTaskState.RETRY_WAIT, now, now))
        assertFalse(ContactTaskPolicy.canClaim(ContactTaskState.RETRY_WAIT, now + 1L, now))
        assertFalse(ContactTaskPolicy.canClaim(ContactTaskState.PROCESSING, 0L, now))
        assertFalse(ContactTaskPolicy.canClaim(ContactTaskState.VERIFY_SEND, 0L, now))
        assertFalse(ContactTaskPolicy.canClaim(ContactTaskState.DONE, 0L, now))
    }

    @Test
    fun `chat recovery requires matching identity and no send verification hold`() {
        assertTrue(ContactTaskPolicy.shouldResumeCurrentChat(ContactTaskState.PENDING, true))
        assertTrue(ContactTaskPolicy.shouldResumeCurrentChat(ContactTaskState.PROCESSING, true))
        assertFalse(ContactTaskPolicy.shouldResumeCurrentChat(ContactTaskState.VERIFY_SEND, true))
        assertFalse(ContactTaskPolicy.shouldResumeCurrentChat(ContactTaskState.PENDING, false))
    }

    @Test
    fun `retry delay grows and stays capped`() {
        assertEquals(3_000L, ContactTaskPolicy.retryDelay(1))
        assertEquals(10_000L, ContactTaskPolicy.retryDelay(2))
        assertEquals(30_000L, ContactTaskPolicy.retryDelay(3))
        assertEquals(120_000L, ContactTaskPolicy.retryDelay(4))
        assertEquals(300_000L, ContactTaskPolicy.retryDelay(5))
        assertEquals(300_000L, ContactTaskPolicy.retryDelay(20))
    }

    @Test
    fun `only done is terminal and send verification remains blocking`() {
        assertTrue(ContactTaskPolicy.isTerminal(ContactTaskState.DONE))
        assertFalse(ContactTaskPolicy.isTerminal(ContactTaskState.PENDING))
        assertFalse(ContactTaskPolicy.isTerminal(ContactTaskState.PROCESSING))
        assertFalse(ContactTaskPolicy.isTerminal(ContactTaskState.RETRY_WAIT))
        assertFalse(ContactTaskPolicy.isTerminal(ContactTaskState.VERIFY_SEND))
    }
}
