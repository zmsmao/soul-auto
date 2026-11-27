package com.soulbot.app.queue

import com.soulbot.app.conversation.ConversationIncomingEvent

/** Pure queue rules shared by persistence code and regression tests. */
internal object MessageQueuePolicy {
    fun alignIncoming(
        incomingTexts: List<String>,
        eventKeys: List<String>,
    ): List<ConversationIncomingEvent> {
        val count = minOf(incomingTexts.size, eventKeys.size)
        if (count <= 0) return emptyList()
        return incomingTexts.takeLast(count)
            .zip(eventKeys.takeLast(count))
            .map { (text, key) -> ConversationIncomingEvent(key, text) }
    }

    fun initialState(index: Int, pendingStart: Int, initialized: Boolean): MessageQueueState =
        when {
            index >= pendingStart -> MessageQueueState.PENDING
            initialized -> MessageQueueState.HANDLED_MANUALLY
            else -> MessageQueueState.BASELINE
        }

    fun isReadyHead(state: String?, nextAttemptAt: Long, now: Long): Boolean =
        state in setOf(MessageQueueState.PENDING.name, MessageQueueState.RETRY_WAIT.name) &&
            nextAttemptAt <= now

    fun retryDelay(attempt: Int): Long = when (attempt) {
        1 -> 3_000L
        2 -> 10_000L
        3 -> 30_000L
        4 -> 120_000L
        else -> 300_000L
    }
}
