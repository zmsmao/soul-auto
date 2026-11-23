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
    fun unseenIncomingEvents(
        contactName: String,
        thread: List<Pair<String, String>>,
        tracker: ConversationSeenTracker,
        pendingCount: Int,
    ): List<ConversationIncomingEvent> {
        if (pendingCount <= 0) return emptyList()
        val incomingTexts = thread.filter { it.first == "in" }.map { it.second }
        val eventKeys = host.currentIncomingMessageKeys(contactName)
        val alignedCount = minOf(incomingTexts.size, eventKeys.size)
        if (alignedCount == 0) return emptyList()
        val events = incomingTexts.takeLast(alignedCount)
            .zip(eventKeys.takeLast(alignedCount))
            .map { (messageText, eventKey) -> ConversationIncomingEvent(eventKey, messageText) }
            .takeLast(pendingCount)
        val threadId = memoryThreadId(contactName)
        val confirmedKeys = host.memoryDatabase?.confirmedMessageKeys(threadId).orEmpty()
        return tracker.unseenEvents(threadId, events, confirmedKeys)
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

    fun personalizationContext(
        contactName: String,
        latestMessage: String,
        scene: ReplyScene = ReplyScene.CHAT,
        includeMemory: Boolean = true,
        styleQuery: String = latestMessage,
        excludeContextKey: String = "",
    ): String {
        return buildString {
            val db = host.learningDatabase
            if (db != null && Prefs.getStyleLearningEnabled(host.context)) {
                val profile = db.styleProfile(scene)
                val preferences = runCatching {
                    host.replyReplayDatabase?.relevantExamples(styleQuery, scene, 3, excludeContextKey)
                }.onFailure { error ->
                    RuntimeLog.record(host.context, "reply preference lookup failed", error)
                }.getOrNull().orEmpty()
                // Prefer reviewed multi-turn corrections. Legacy pairs remain a fallback.
                val examples = if (preferences.isEmpty()) db.relevantStyleSamples(styleQuery, 3, scene) else emptyList()
                val dislikedHints = db.dislikedStyleHints(scene)
                if (dislikedHints.isNotEmpty()) {
                    append("\n用户对这个场景明确不喜欢的表达类型：").append(dislikedHints.joinToString("、"))
                    append("。这些是表达偏好，不是对方的个人信息。")
                }
                if (profile.sampleCount > 0) {
                    append("\n\n这是用户本人聊天风格档案，只模仿表达习惯，不要声称自己是某个角色，也不要照抄事实或整句。\n")
                    append(profile.promptLine())
                    // Frequent words and endings may be names or topic fragments, not style rules.
                    append("不要刻意重复口头词、收尾或固定句式；这些统计不是字数或提问配额。")
                }
                if (examples.isNotEmpty()) {
                    append("\n下面是用户本人过去的人工回复样本。只参考长度、措辞和节奏，不要照抄其中的人名、事实或句子。\n")
                    for (sample in examples) {
                        append("\n对方：").append(sample.theirMessage)
                        append("\n我：").append(sample.myMessage)
                    }
                }
                append(ReplyPreferencePrompt.build(preferences))
            }
            if (includeMemory && db != null &&
                Prefs.getContactMemoryEnabled(host.context) &&
                contactName.isNotBlank()
            ) {
                val memories = db.contactMemories(contactName, 5)
                if (memories.isNotEmpty()) {
                    append("\n\n对方过去提到过这些内容，相关时再自然参考，不相关就忽略，也不要说自己在读取记忆：")
                    memories.forEach { append("\n- ").append(it) }
                }
            }
            if (includeMemory && Prefs.getContactMemoryEnabled(host.context) && contactName.isNotBlank()) {
                val olderMessages = runCatching {
                    host.memoryDatabase?.relevantOlderMessages(memoryThreadId(contactName), scene, latestMessage, 6)
                }.getOrNull().orEmpty().filterNot { message ->
                    message.role == "out" && ReplyNaturalness.isInternalDraft(message.content)
                }
                if (olderMessages.isNotEmpty()) {
                    append("\n\n下面是与当前话题相关的更早聊天片段。只在确实相关时自然接上，")
                    append("不要复述整段，不要声称自己在读取数据库，也不要把推测当事实：")
                    olderMessages.forEach { message ->
                        append("\n")
                        append(if (message.role == "in") "对方曾说：" else "我曾说：")
                        append(message.content)
                    }
                }
            }
        }
    }

    fun learnFromManualReplies(
        contactName: String,
        thread: List<Pair<String, String>>,
        scene: ReplyScene = ReplyScene.CHAT,
    ) {
        runCatching {
            val threadId = memoryThreadId(contactName)
            host.memoryDatabase?.syncVisibleThread(contactName, thread, threadId, scene)
            host.memoryDatabase?.updateConversationState(threadId, scene, thread)
        }.onFailure { error ->
            RuntimeLog.record(
                host.context,
                "conversation memory sync failed; contactId=${DiagnosticIdentity.contactId(host.context, contactName)}",
                error,
            )
        }
        val db = host.learningDatabase ?: return
        val manualSamples = ManualStyleLearner.extract(thread, scene) { reply ->
            db.isAutomatedReply(contactName, reply)
        }
        if (Prefs.getStyleLearningEnabled(host.context)) {
            manualSamples.forEach { db.recordManualSample(it.theirMessage, it.myMessage, it.scene) }
        }
        if (Prefs.getContactMemoryEnabled(host.context)) {
            manualSamples.forEach { db.recordContactMemory(contactName, it.theirMessage) }
        }
    }

    fun recordAutomatedInteraction(
        contactName: String,
        incomingMessages: List<String>,
        reply: String,
        opening: OpeningContext? = null,
        scene: ReplyScene = opening?.scene ?: ReplyScene.CHAT,
    ) {
        runCatching {
            val threadId = memoryThreadId(contactName)
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
        db.recordAutomatedReply(contactName, reply)
        db.recordQualityEvent(contactName, reply, true, "sent")
        // A system match reason/profile is not an incoming chat message.
        if (opening == null && Prefs.getContactMemoryEnabled(host.context)) {
            incomingMessages.takeLast(3).forEach { db.recordContactMemory(contactName, it) }
        }
    }
}
