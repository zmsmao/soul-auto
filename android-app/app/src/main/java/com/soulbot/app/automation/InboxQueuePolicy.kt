package com.soulbot.app.automation

/** Queue-driven inbox navigation; the global unread badge never authorizes a full-list sweep. */
internal object InboxQueuePolicy {
    /** Enter the inbox only for a queued contact or one initial page check per unread-badge cycle. */
    fun shouldEnterInbox(
        hasUnreadRedDot: Boolean,
        inboxPageScanned: Boolean,
        hasPendingTask: Boolean,
    ): Boolean = hasPendingTask || (hasUnreadRedDot && !inboxPageScanned)

    /** Discover unread contacts from the first visible page only, never from later pages. */
    fun shouldEnqueueVisiblePage(inboxPageScanned: Boolean): Boolean = !inboxPageScanned

    /** An empty FIFO queue means inbox work is over, even if Soul keeps showing its global badge. */
    fun shouldReturnToIdle(hasPendingTask: Boolean): Boolean = !hasPendingTask
}
