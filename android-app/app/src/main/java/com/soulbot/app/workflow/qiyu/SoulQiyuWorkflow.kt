package com.soulbot.app.workflow.qiyu
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

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Owns the Qiyu encounter state, retries, draft ownership and send confirmation. */
internal class SoulQiyuWorkflow(private val host: SoulQiyuHost) {
    private val qiyuSeen = ConversationSeenTracker()
    private var pendingQiyu: PendingQiyu? = null

    fun hasPending(): Boolean = pendingQiyu != null
    fun clearPending() { pendingQiyu = null }
    fun handle(): Boolean = handleQiyu()
    fun resume(): Boolean = resumePendingQiyu()

    private fun handleQiyu(): Boolean {
        if (host.currentScreen() != SoulScreen.QIYU_POPUP) return false
        val chatBtn = host.findVisibleById(SoulAccessibilityIds.ID_QIYU_CHAT) ?: return false
        val signature = host.textOf(host.findVisibleById(SoulAccessibilityIds.ID_QIYU_SIGNATURE))
        val reason = host.textOf(host.findVisibleById(SoulAccessibilityIds.ID_QIYU_REASON))
        val session = pendingQiyu?.takeIf {
            it.contactName.isBlank() && it.signature == signature && it.reason == reason
        } ?: PendingQiyu(signature, reason)
        val remaining = session.retryAt - System.currentTimeMillis()
        if (remaining > 0L) { host.sleep(minOf(500L, remaining)); return false }
        pendingQiyu = session
        host.setNextStep("打开奇遇铃私聊")
        if (!host.tapNode(chatBtn)) {
            return deferQiyu(session, "open_click_failed", "奇遇铃私聊按钮尚未生效")
        }
        host.sleep(500)
        return resumePendingQiyu()
    }

    private fun deferQiyu(session: PendingQiyu, reason: String, status: String): Boolean {
        if (pendingQiyu !== session) return false
        session.failures++
        val delay = QiyuConversationPolicy.retryDelayMs(session.failures)
        session.retryAt = System.currentTimeMillis() + delay
        RuntimeLog.record(host.context,
            "qiyu pending; reason=$reason; failures=${session.failures}; retryMs=$delay; " +
                "contactId=${DiagnosticIdentity.contactId(host.context, session.contactName)}; prepared=${session.prepared != null}")
        host.setNextStep("$status；保留当前会话，稍后复核", delay)
        return false
    }

    /** A missing title is a loading/compatibility issue, not permission to abandon the encounter. */
    private fun resumePendingQiyu(): Boolean {
        val session = pendingQiyu ?: return false
        val remaining = session.retryAt - System.currentTimeMillis()
        if (remaining > 0L) {
            host.sleep(minOf(500L, remaining))
            return false
        }
        val deadline = System.currentTimeMillis() + QiyuConversationPolicy.LOAD_TIMEOUT_MS
        while (host.isRunning && host.isSoulForeground()) {
            val screen = host.currentScreen()
            if (screen == SoulScreen.GIFT || screen == SoulScreen.NO_SEND) {
                pendingQiyu = null
                RuntimeLog.record(host.context, "qiyu cancelled; reason=chat_not_allowed; screen=${screen.name}")
                if (screen == SoulScreen.GIFT) host.skipGift() else host.skipNoSend()
                return false
            }
            val name = if (host.isOnChat()) host.displayedConversationName().trim() else ""
            if (name.isNotBlank()) {
                if (session.contactName.isNotBlank() && !host.hasConfirmedConversationName(session.contactName)) {
                    pendingQiyu = null
                    RuntimeLog.record(host.context, "qiyu cancelled; reason=contact_changed")
                    return false
                }
                session.contactName = name
                break
            }
            val wait = deadline - System.currentTimeMillis()
            if (wait <= 0L) return deferQiyu(session, "contact_not_loaded", "奇遇铃联系人仍未读取到，未发送")
            host.setNextStep("等待奇遇铃聊天标题加载，确认联系人后再发送", wait)
            host.sleep(minOf(500L, wait))
        }
        if (!host.isRunning || !host.isOnChat() || !host.hasConfirmedConversationName(session.contactName)) return false
        val contactName = session.contactName

        session.prepared?.let { prepared ->
            return sendPreparedQiyu(session, prepared)
        }
        if (!host.convertIncomingVoiceMessages()) {
            return deferQiyu(session, "voice_unresolved", "奇遇铃语音尚未完成转写，未发送")
        }
        // Wait for outgoing history too: an empty first snapshot does not prove this is a new chat.
        var thread = host.readThread()
        var stableSince = System.currentTimeMillis()
        val loadDeadline = stableSince + QiyuConversationPolicy.LOAD_TIMEOUT_MS
        while (host.isRunning && host.isOnChat() && host.hasConfirmedConversationName(contactName) &&
            System.currentTimeMillis() < loadDeadline && System.currentTimeMillis() - stableSince < ConversationTiming.MESSAGE_SETTLE_MS) {
            host.setNextStep("确认奇遇铃聊天内容已加载", 500)
            host.sleep(500)
            val latest = host.readThread()
            if (latest != thread) stableSince = System.currentTimeMillis()
            thread = latest
        }
        if (!host.isRunning || !host.isOnChat() || !host.hasConfirmedConversationName(contactName)) {
            return deferQiyu(session, "chat_changed_during_load", "奇遇铃聊天页面变化，未发送")
        }
        if (host.pendingIncomingVoiceBubbles().isNotEmpty()) {
            return deferQiyu(session, "voice_arrived", "奇遇铃收到新语音，等待转写")
        }
        if (QiyuConversationPolicy.shouldWaitForHistory(thread, host.hasConversationHistory(contactName, ReplyScene.QIYU))) {
            return deferQiyu(session, "stored_history_not_visible", "奇遇铃已有本地记录，但页面内容尚未读到")
        }
        val route = OpeningConversationRoute.action(thread, host.hasConversationHistory(contactName, ReplyScene.QIYU))
        host.learnFromManualReplies(contactName, thread, ReplyScene.QIYU)
        val pendingCount = OpeningConversationRoute.pendingIncoming(thread).size
        val pendingEvents = host.unseenIncomingEvents(contactName, thread, qiyuSeen, pendingCount)
        val pending = pendingEvents.map { it.text }
        if (route == OpeningConversationAction.SKIP ||
            (route == OpeningConversationAction.REPLY && pending.isEmpty())
        ) {
            pendingQiyu = null
            RuntimeLog.record(host.context,
                "qiyu skipped; reason=already_answered; contactId=${DiagnosticIdentity.contactId(host.context, contactName)}; messages=${thread.size}")
            host.setNextStep("奇遇铃已有会话且没有新内容，不重复发送开场")
            host.gotoChatList()
            return false
        }
        val openingContext = OpeningContext(
            ReplyScene.QIYU,
            contactName,
            listOf(session.signature, host.readQiyuSignature()).filter(String::isNotBlank).joinToString("\n"),
            SoulMatchContent.zodiac(host.visibleTitleTexts()),
            session.reason,
        )
        val replying = route == OpeningConversationAction.REPLY
        val eventAnchor = host.currentIncomingMessageKeys(contactName).joinToString(",")
        val finalReply = if (replying) {
            host.generateValidatedChatReply(contactName, thread, pending, ReplyScene.QIYU)
        } else host.generateValidatedOpening("优先生成奇遇铃开场", openingContext)
        if (finalReply == null) {
            if (host.pendingIncomingVoiceBubbles().isNotEmpty() || host.readThread() != thread ||
                host.currentIncomingMessageKeys(contactName).joinToString(",") != eventAnchor
            ) {
                return deferQiyu(session, "incoming_changed_during_generation", "奇遇铃收到新消息，旧生成已取消，稍后按最新上下文重新准备")
            }
            return deferQiyu(session, "generation_failed", "奇遇铃回复生成失败，未发送")
        }
        if (!host.isRunning || !host.isOnChat() || !host.hasConfirmedConversationName(contactName) ||
            host.pendingIncomingVoiceBubbles().isNotEmpty() ||
            host.readThread() != thread ||
            host.currentIncomingMessageKeys(contactName).joinToString(",") != eventAnchor
        ) {
            return deferQiyu(session, "incoming_changed", "奇遇铃会话有变化，将按最新内容重新准备")
        }
        val sendText = host.splitReply(finalReply).single()
        val prepared = QiyuPreparedReply(
            sendText, thread, pending, openingContext.takeUnless { replying },
            host.outgoingOccurrenceCount(sendText), eventAnchor, pendingEvents.map { it.key },
        )
        session.prepared = prepared
        return sendPreparedQiyu(session, prepared)
    }

    private fun sendPreparedQiyu(session: PendingQiyu, prepared: QiyuPreparedReply): Boolean {
        val contactName = session.contactName
        if (!host.isRunning || !host.isOnChat() || !host.hasConfirmedConversationName(contactName)) return false
        val input = host.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX)
            ?: return deferQiyu(session, "input_not_loaded", "奇遇铃输入框尚未读到，保留原草稿")
        val inputText = if (input.isShowingHintText) "" else host.textOf(input)
        val recovery = QiyuConversationPolicy.sendRecovery(
            host.outgoingOccurrenceCount(prepared.text) > prepared.outgoingBefore,
            prepared.clickedSend,
            inputText == prepared.text,
            prepared.attempts,
        )
        if (recovery == QiyuSendRecovery.CONFIRMED) return finishQiyuSend(session, prepared)
        if (host.pendingIncomingVoiceBubbles().isNotEmpty() || host.readThread() != prepared.thread ||
            host.currentIncomingMessageKeys(contactName).joinToString(",") != prepared.eventAnchor
        ) {
            val refresh = QiyuConversationPolicy.draftRefresh(inputText, prepared.writtenText, prepared.clickedSend)
            val cleared = refresh == QiyuDraftRefresh.CLEAR_OWNED_UNSENT && clearOwnedQiyuDraft(session, prepared)
            if (!QiyuConversationPolicy.mayDiscardDraft(refresh, cleared)) {
                val reason = when (refresh) {
                    QiyuDraftRefresh.WAIT_CONFIRMATION -> "incoming_changed_send_unconfirmed"
                    QiyuDraftRefresh.KEEP_OTHER_INPUT -> "incoming_changed_input_edited"
                    else -> "owned_draft_clear_failed"
                }
                return deferQiyu(session, reason, "奇遇铃收到新内容，旧草稿尚不能安全撤回；保留原稿等待确认")
            }
            session.prepared = null
            return deferQiyu(session, "incoming_changed_before_send", "奇遇铃收到新内容，已撤回未发送草稿并重新准备")
        }
        if (recovery == QiyuSendRecovery.WAIT_CONFIRMATION) {
            return deferQiyu(session, "send_unconfirmed", "奇遇铃发送尚未确认，不重复点击，请检查当前输入框")
        }
        if (inputText.isNotEmpty() && inputText != prepared.text) {
            return deferQiyu(session, "input_edited", "输入框已有其他内容，不覆盖你的草稿")
        }
        prepared.attempts++
        host.setNextStep("立即发送奇遇铃私聊回复")
        val qiyuOpeningKey = prepared.opening?.let {
            ConversationIdentity.sendKey(
                host.memoryThreadId(contactName),
                ReplyScene.QIYU.name,
                listOf(ConversationIdentity.digest(
                    session.signature + "\u0000" + session.reason + "\u0000" + it.content,
                )),
                prepared.text,
            )
        }
        val sent = host.send(
            prepared.text,
            contactName,
            QiyuConversationPolicy.LOAD_TIMEOUT_MS,
            onInputWritten = { prepared.writtenText = it },
            onSendClick = { prepared.clickedSend = true },
            idempotencyKey = qiyuOpeningKey,
            sceneOverride = ReplyScene.QIYU,
            expectedEventAnchor = prepared.eventAnchor,
        )
        if (sent) return finishQiyuSend(session, prepared)
        return deferQiyu(session, "send_failed", "奇遇铃消息未确认发出，将检查同一条草稿")
    }

    /** Never uses paste or replaces a user's edited draft when withdrawing an obsolete reply. */
    private fun clearOwnedQiyuDraft(session: PendingQiyu, prepared: QiyuPreparedReply): Boolean {
        if (!host.isRunning || prepared.clickedSend || !host.isOnChat() || !host.hasConfirmedConversationName(session.contactName)) return false
        val input = host.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) ?: return false
        val text = if (input.isShowingHintText) "" else host.textOf(input)
        if (QiyuConversationPolicy.draftRefresh(text, prepared.writtenText, prepared.clickedSend) !=
            QiyuDraftRefresh.CLEAR_OWNED_UNSENT) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        val accepted = runCatching { input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        if (!accepted) return false
        val deadline = System.currentTimeMillis() + 1000L
        while (host.isRunning && System.currentTimeMillis() < deadline) {
            if (!host.isOnChat() || !host.hasConfirmedConversationName(session.contactName)) return false
            val current = host.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) ?: return false
            if (current.isShowingHintText || host.textOf(current).isEmpty()) return true
            host.sleep(100)
        }
        return false
    }

    private fun finishQiyuSend(session: PendingQiyu, prepared: QiyuPreparedReply): Boolean {
        pendingQiyu = null
        qiyuSeen.markSeen(host.memoryThreadId(session.contactName), prepared.pendingEventKeys)
        host.recordAutomatedInteraction(session.contactName, prepared.pending, prepared.text, prepared.opening, scene = ReplyScene.QIYU)
        host.recordHistory(session.contactName,
            prepared.opening?.let { "奇遇铃资料：${it.content}" } ?: prepared.pending.joinToString("\n"), prepared.text)
        RuntimeLog.record(host.context,
            "qiyu send confirmed; contactId=${DiagnosticIdentity.contactId(host.context, session.contactName)}; attempts=${prepared.attempts}")
        host.setNextStep("奇遇铃回复已确认发送，返回会话列表", 1200)
        host.sleep(1200)
        host.gotoChatList()
        return true
    }
}

private data class PendingQiyu(
    val signature: String,
    val reason: String,
    var contactName: String = "",
    var failures: Int = 0,
    var retryAt: Long = 0L,
    var prepared: QiyuPreparedReply? = null,
)

private data class QiyuPreparedReply(
    val text: String,
    val thread: List<Pair<String, String>>,
    val pending: List<String>,
    val opening: OpeningContext?,
    val outgoingBefore: Int,
    val eventAnchor: String,
    val pendingEventKeys: List<String>,
    var attempts: Int = 0,
    var clickedSend: Boolean = false,
    var writtenText: String? = null,
)
