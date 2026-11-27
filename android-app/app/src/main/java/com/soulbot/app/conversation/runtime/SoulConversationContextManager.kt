package com.soulbot.app.conversation.runtime

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.accessibility.*
import com.soulbot.app.data.*
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.logging.DiagnosticIdentity
import com.soulbot.app.conversation.*
import com.soulbot.app.model.ReplyPreferencePrompt
import com.soulbot.app.conversation.context.ContextFragment
import com.soulbot.app.conversation.context.PersonaContextProvider
import com.soulbot.app.conversation.context.ContactMemoryContextProvider
import com.soulbot.app.conversation.memory.ContactMemoryExtractionScheduler
import com.soulbot.app.queue.MessageDeliveryQueue
import com.soulbot.app.queue.MessageQueuePolicy

internal interface SoulConversationContextHost {
    val context: Context
    val learningDatabase: ChatLearningDatabase?
    val memoryDatabase: ConversationMemoryDatabase?
    val replyReplayDatabase: ReplyReplayDatabase?
    fun currentIncomingMessageKeys(contactName: String): List<String>
    fun openingFingerprint(reply: String, context: OpeningContext): OpeningFingerprint
    fun isOnChat(): Boolean
    fun isExpectedConversation(contactName: String): Boolean
    fun readThread(): List<Pair<String, String>>
    fun findVisibleById(id: String): AccessibilityNodeInfo?
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun rect(node: AccessibilityNodeInfo): Rect
    fun activeWindowRegion(): UiRegion
    fun screenHeight(): Int
}

internal class SoulConversationContextManager(private val host: SoulConversationContextHost) {
    private val deliveryQueue by lazy(LazyThreadSafetyMode.NONE) { MessageDeliveryQueue(host.context) }
    fun unseenIncomingEvents(
        contactName: String,
        thread: List<Pair<String, String>>,
        tracker: ConversationSeenTracker,
        pendingCount: Int,
        scene: ReplyScene = ReplyScene.CHAT,
    ): List<ConversationIncomingEvent> {
        val incomingTexts = thread.filter { it.first == "in" }.map { it.second }
        val eventKeys = host.currentIncomingMessageKeys(contactName)
        val events = MessageQueuePolicy.alignIncoming(incomingTexts, eventKeys)
        val threadId = memoryThreadId(contactName)
        deliveryQueue.observeSnapshot(
            threadId = threadId,
            contactId = DiagnosticIdentity.contactId(host.context, contactName),
            displayName = contactName,
            scene = scene.name,
            incoming = events,
            pendingCount = pendingCount,
        )
        val lastOutgoingIndex = thread.indexOfLast { it.first == "out" }
        if (lastOutgoingIndex >= 0) {
            val answeredIncomingCount = thread.take(lastOutgoingIndex).count { it.first == "in" }
            deliveryQueue.markManuallyHandledIfPending(
                threadId,
                events.take(answeredIncomingCount).map { it.key },
            )
        }
        // Reconcile the crash window where the send ledger was confirmed but queue ACK was not.
        val confirmedKeys = host.memoryDatabase?.confirmedMessageKeys(threadId).orEmpty()
        deliveryQueue.markConfirmedKeys(threadId, confirmedKeys)
        return deliveryQueue.claimPending(threadId, scene.name)
            .map { ConversationIncomingEvent(it.key, it.text) }
    }

    fun currentNativeSoulUserId(): String? {
        if (!host.isOnChat()) return null
        var node = host.findVisibleById(SoulAccessibilityIds.ID_PROFILE_NAME)
            ?: host.findByIds(SoulAccessibilityIds.ID_GENERIC_TITLE).filter(host::isVisibleOnScreen)
                .firstOrNull { host.rect(it).bottom <= host.activeWindowRegion().top + host.screenHeight() / 5 }
        repeat(3) {
            if (node != null) {
                SoulAccessibilityIdentity.userId(node)?.let { return it }
                node = runCatching { node?.parent }.getOrNull()
            }
        }
        return null
    }

    fun memoryThreadId(contactName: String): String =
        ConversationIdentity.threadId(
            currentNativeSoulUserId(),
            DiagnosticIdentity.contactId(host.context, contactName),
        )

    private fun scheduleContactMemoryExtraction(threadId: String, scene: ReplyScene) {
        if (!Prefs.getContactMemoryExtractionEnabled(host.context)) return
        val entries = host.memoryDatabase?.contactEntries(threadId, 120).orEmpty()
            .filter { it.scene == scene.name && it.role == "in" }
        if (entries.isNotEmpty()) {
            ContactMemoryExtractionScheduler.enqueue(
                host.context, threadId, scene.name, entries,
            )
        }
    }

    fun contextFragments(
        contactName: String,
        latestMessage: String,
        scene: ReplyScene = ReplyScene.CHAT,
        includeMemory: Boolean = true,
        styleQuery: String = latestMessage,
        excludeContextKey: String = "",
    ): List<ContextFragment> {
        val threadId = memoryThreadId(contactName)
        return PersonaContextProvider.fragments(
            context = host.context,
            profile = Prefs.getSelfProfile(host.context),
            learningDatabase = host.learningDatabase,
            replyReplayDatabase = host.replyReplayDatabase,
            query = styleQuery,
            scene = scene,
            excludeContextKey = excludeContextKey,
        ) + ContactMemoryContextProvider.fragments(
            learningDatabase = host.learningDatabase,
            memoryDatabase = host.memoryDatabase,
            threadId = threadId,
            scene = scene,
            latestMessage = latestMessage,
            includeMemory = includeMemory && Prefs.getContactMemoryEnabled(host.context),
        )
    }

    /** Compatibility renderer for non-chat call sites; chat generation uses ContextBuilder directly. */
    fun personalizationContext(
        contactName: String,
        latestMessage: String,
        scene: ReplyScene = ReplyScene.CHAT,
        includeMemory: Boolean = true,
        styleQuery: String = latestMessage,
        excludeContextKey: String = "",
    ): String = contextFragments(
        contactName, latestMessage, scene, includeMemory, styleQuery, excludeContextKey,
    ).sortedBy { it.order }.joinToString("") { it.content }

    fun learnFromManualReplies(
        contactName: String,
        thread: List<Pair<String, String>>,
        scene: ReplyScene = ReplyScene.CHAT,
    ) {
        val threadId = memoryThreadId(contactName)
        runCatching {
            host.memoryDatabase?.syncVisibleThread(contactName, thread, threadId, scene)
            host.memoryDatabase?.updateConversationState(threadId, scene, thread)
        }.onFailure { error ->
            RuntimeLog.record(
                host.context,
                "conversation memory sync failed; contactId=${DiagnosticIdentity.contactId(host.context, contactName)}",
                error,
            )
        }
        scheduleContactMemoryExtraction(threadId, scene)
        val db = host.learningDatabase ?: return
        val manualSamples = ManualStyleLearner.extract(thread, scene) { reply ->
            db.isAutomatedReply(threadId, reply)
        }
        if (Prefs.getStyleLearningEnabled(host.context)) {
            manualSamples.forEach { db.recordManualSample(it.theirMessage, it.myMessage, it.scene) }
        }
        if (Prefs.getContactMemoryEnabled(host.context)) {
            manualSamples.forEach { db.recordContactMemory(threadId, it.theirMessage) }
        }
    }

    fun recordAutomatedInteraction(
        contactName: String,
        incomingMessages: List<String>,
        reply: String,
        opening: OpeningContext? = null,
        scene: ReplyScene = opening?.scene ?: ReplyScene.CHAT,
    ) {
        val threadId = memoryThreadId(contactName)
        runCatching {
            host.memoryDatabase?.recordMessage(contactName, "out", reply, threadId, scene)
            if (host.isOnChat() && host.isExpectedConversation(contactName)) {
                val visibleThread = host.readThread()
                host.memoryDatabase?.syncVisibleThread(contactName, visibleThread, threadId, scene)
                host.memoryDatabase?.updateConversationState(threadId, scene, visibleThread)
            }
        }.onFailure { error ->
            RuntimeLog.record(
                host.context,
                "conversation memory write failed; contactId=${DiagnosticIdentity.contactId(host.context, contactName)}",
                error,
            )
        }
        if (opening == null) scheduleContactMemoryExtraction(threadId, scene)
        val db = host.learningDatabase ?: return
        if (opening != null) {
            runCatching {
                db.recordSentOpening(opening.scene, host.openingFingerprint(reply, opening))
            }.onFailure { error ->
                RuntimeLog.record(host.context, "sent opening pattern write failed", error)
            }
        }
        // Always mark automated text, even when learning is disabled, so it can
        // never be mistaken for a human-written sample after learning is enabled.
        db.recordAutomatedReply(threadId, reply)
        db.recordQualityEvent(contactName, reply, true, "sent")
        // A system match reason/profile is not an incoming chat message.
        if (opening == null && Prefs.getContactMemoryEnabled(host.context)) {
            incomingMessages.takeLast(3).forEach { db.recordContactMemory(threadId, it) }
        }
    }
}
