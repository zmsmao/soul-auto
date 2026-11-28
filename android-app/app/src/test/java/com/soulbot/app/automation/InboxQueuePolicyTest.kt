package com.soulbot.app.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxQueuePolicyTest {
    @Test
    fun `unread badge opens inbox for one initial viewport only`() {
        assertTrue(
            InboxQueuePolicy.shouldEnterInbox(
                hasUnreadRedDot = true,
                inboxPageScanned = false,
                hasPendingTask = false,
            ),
        )
        assertFalse(
            InboxQueuePolicy.shouldEnterInbox(
                hasUnreadRedDot = true,
                inboxPageScanned = true,
                hasPendingTask = false,
            ),
        )
    }

    @Test
    fun `queued contact reopens inbox even when global unread badge is absent`() {
        assertTrue(
            InboxQueuePolicy.shouldEnterInbox(
                hasUnreadRedDot = false,
                inboxPageScanned = true,
                hasPendingTask = true,
            ),
        )
    }

    @Test
    fun `later viewports are used for locating queued contacts but do not enqueue every row`() {
        assertTrue(InboxQueuePolicy.shouldEnqueueVisiblePage(inboxPageScanned = false))
        assertFalse(InboxQueuePolicy.shouldEnqueueVisiblePage(inboxPageScanned = true))
    }

    @Test
    fun `empty queue returns to idle even when unread badge remains visible`() {
        assertTrue(InboxQueuePolicy.shouldReturnToIdle(hasPendingTask = false))
        assertFalse(InboxQueuePolicy.shouldReturnToIdle(hasPendingTask = true))
    }
}
