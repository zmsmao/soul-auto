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

/**
 * Tracks handled incoming messages per conversation.
 *
 * Message text alone is not globally unique: different people often send the same greeting.
 * Keep the conversation key as the first dimension so one user's message never suppresses
 * another user's reply.
 */
internal data class ConversationIncomingEvent(
    val key: String,
    val text: String,
)

internal class ConversationSeenTracker {
    private val handledByConversation = mutableMapOf<String, List<String>>()

    fun unseen(conversation: String, incoming: List<String>): List<String> {
        val handled = handledByConversation[conversation] ?: return incoming
        val maxOverlap = minOf(handled.size, incoming.size)
        val overlap = (maxOverlap downTo 1).firstOrNull { size ->
            handled.takeLast(size) == incoming.take(size)
        } ?: 0
        return incoming.drop(overlap)
    }

    fun unseenEvents(
        conversation: String,
        incoming: List<ConversationIncomingEvent>,
        durablyConfirmedKeys: Set<String>,
    ): List<ConversationIncomingEvent> {
        if (incoming.isEmpty()) return emptyList()
        val unseenKeys = unseen(conversation, incoming.map { it.key }).toHashSet()
        return incoming.filter { it.key in unseenKeys && it.key !in durablyConfirmedKeys }
    }

    fun markSeenEvents(conversation: String, incoming: List<ConversationIncomingEvent>) {
        markSeen(conversation, incoming.map { it.key })
    }

    fun markSeen(conversation: String, incoming: List<String>) {
        handledByConversation[conversation] = incoming.toList()
    }
}
