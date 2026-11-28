package com.soulbot.app.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTaskPolicyTest {
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
