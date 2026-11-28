package com.soulbot.app.workflow.privatechat

import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.logging.DiagnosticIdentity

import com.soulbot.app.model.runtime.ModelRouter
import com.soulbot.app.accessibility.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*



/** Result of one contact-level private-chat task. */
internal enum class PrivateChatTaskResult {
    COMPLETED_SENT,
    COMPLETED_NO_REPLY,
    RETRY,
    VERIFY_SEND,
}

/** Owns ordinary private-chat entry, reservation and reply processing. */
internal class SoulPrivateChatWorkflow(private val host: SoulPrivateChatHost) {
    @Volatile private var activeReplyName: String? = null

    fun replyTo(name: String, seen: ConversationSeenTracker): PrivateChatTaskResult {
        val contactId = DiagnosticIdentity.contactId(host.context, name)
        if (name.isBlank()) return PrivateChatTaskResult.RETRY
        if (!reserveReply(name)) return PrivateChatTaskResult.RETRY
        RuntimeLog.record(host.context, "contact task started; contactId=$contactId")
        return try {
            val result = when {
                host.isOnChat() && host.isExpectedConversation(name) ->
                    processContactTaskInCurrentChat(name)
                host.isOnList() && openConversationForReply(name) ->
                    processContactTaskInCurrentChat(name)
                else -> PrivateChatTaskResult.RETRY
            }
            RuntimeLog.record(host.context, "contact task ended; contactId=$contactId; result=$result")
            result
        } catch (stopped: StoppedException) {
            throw stopped
        } catch (error: Throwable) {
            RuntimeLog.record(host.context, "contact task crashed; contactId=$contactId", error)
            host.setNextStep("$name 的会话发生异常，保留队列等待重试", 1200)
            if (host.isRunning && host.currentScreen() != SoulScreen.QIYU_POPUP) {
                runCatching { host.gotoChatList() }
            }
            PrivateChatTaskResult.RETRY
        } finally {
            releaseReply(name)
        }
    }

    @Synchronized
    private fun reserveReply(name: String): Boolean {
        if (activeReplyName != null) return false
        activeReplyName = name
        return true
    }

    @Synchronized
    private fun releaseReply(name: String) {
        if (activeReplyName == name) activeReplyName = null
    }

    private fun openConversationForReply(name: String): Boolean {
        val contactId = DiagnosticIdentity.contactId(host.context, name)
        repeat(2) { attempt ->
            if (!host.isRunning || !host.isOnList()) return false
            val item = host.findConversation(name) ?: return false
            host.setNextStep(if (attempt == 0) "打开 $name 的会话" else "重新定位 $name 的会话")
            if (!host.tapNode(item)) {
                host.sleep(500)
                return@repeat
            }
            host.sleep(1500)
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) return false
            if (host.isOnChat() && host.isExpectedConversation(name)) {
                RuntimeLog.record(
                    host.context,
                    "conversation opened; contactId=$contactId; attempt=${attempt + 1}",
                )
                return true
            }

            host.setNextStep("会话列表刚刚更新，重新定位 $name", 800)
            if (host.isOnChat()) host.gotoChatList()
            host.sleep(800)
        }
        RuntimeLog.record(host.context, "conversation open failed; contactId=$contactId; attempts=2")
        return false
    }

    /**
     * The queue selected the contact; the live transcript is the only source of reply context.
     * No message events or message bodies are written to the contact task queue.
     */
    private fun processContactTaskInCurrentChat(name: String): PrivateChatTaskResult {
        val contactId = DiagnosticIdentity.contactId(host.context, name)
        if (!host.isRunning || !host.isOnChat() || !host.isExpectedConversation(name)) {
            return PrivateChatTaskResult.RETRY
        }
        if (host.hasUncertainSendAttempts(name)) {
            host.setNextStep("$name 存在发送结果待确认，暂停自动重发")
            host.gotoChatList()
            return PrivateChatTaskResult.VERIFY_SEND
        }

        replyCycle@ while (host.isRunning && host.isOnChat()) {
            val thread = host.waitForStableThread(name)
            if (!host.isRunning || !host.isOnChat() || !host.isExpectedConversation(name)) {
                host.gotoChatList()
                return PrivateChatTaskResult.RETRY
            }
            host.learnFromManualReplies(name, thread)
            if (thread.isEmpty()) {
                host.setNextStep("$name 的对话暂时无法读取，保留任务重试", 1500)
                host.gotoChatList()
                return PrivateChatTaskResult.RETRY
            }

            val lastOutgoingIndex = thread.indexOfLast { it.first == "out" }
            val focusIncoming = thread.drop(lastOutgoingIndex + 1)
                .filter { it.first == "in" }
                .map { it.second.trim() }
                .filter(String::isNotBlank)

            if (focusIncoming.isEmpty()) {
                RuntimeLog.record(host.context, "contact task completed without reply; reason=no_unanswered_tail; contactId=$contactId")
                host.setNextStep("$name 当前没有待回复内容，完成本轮处理", 800)
                host.gotoChatList()
                return PrivateChatTaskResult.COMPLETED_NO_REPLY
            }

            if (ReplyRhythmPolicy.shouldWaitForLowInformation(thread, focusIncoming)) {
                RuntimeLog.record(host.context, "contact task completed without reply; reason=low_information_rhythm; contactId=$contactId")
                host.setNextStep("$name 连续简短回应，本轮自然停顿")
                host.gotoChatList()
                return PrivateChatTaskResult.COMPLETED_NO_REPLY
            }

            // This anchor is used only by the existing send idempotency guard; it is not queued.
            val eventAnchor = host.currentIncomingMessageKeys(name).joinToString(",")
            val generationResult = host.generateValidatedChatReply(name, thread, focusIncoming)
            when (generationResult) {
                is ChatReplyGenerationResult.NoReplyRequired -> {
                    if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                        host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                    ) {
                        RuntimeLog.record(host.context, "contact no-reply decision discarded; reason=incoming_changed")
                        continue@replyCycle
                    }
                    RuntimeLog.record(
                        host.context,
                        "contact task completed without reply; reason=" + generationResult.reason + "; contactId=" + contactId,
                    )
                    host.setNextStep("$name 本轮无需回复，已完成处理", 800)
                    host.gotoChatList()
                    return PrivateChatTaskResult.COMPLETED_NO_REPLY
                }
                null -> {
                    if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                        host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                    ) {
                        host.setNextStep("收到新消息，重新读取最新对话", ConversationTiming.MESSAGE_SETTLE_MS)
                        continue@replyCycle
                    }
                    val reason = ModelRouter.lastError.ifBlank { "模型生成失败" }.take(36)
                    RuntimeLog.record(host.context, "contact task retry; reason=generation_failed; detail=$reason")
                    host.setNextStep("$name 回复生成失败，保留任务重试：$reason", 2000)
                    host.gotoChatList()
                    return PrivateChatTaskResult.RETRY
                }
                is ChatReplyGenerationResult.ReadyToSend -> {
                    val reply = generationResult.text
                    if (reply.isBlank()) {
                        host.gotoChatList()
                        return PrivateChatTaskResult.RETRY
                    }

                    val normalized = host.normalizeReply(reply)
                    val repeated = thread.filter { it.first == "out" }
                        .map { host.normalizeReply(it.second) }
                        .any { it.isNotEmpty() && it == normalized }
                    if (repeated) {
                        RuntimeLog.record(host.context, "contact task completed without reply; reason=duplicate_output")
                        host.setNextStep("回复内容与历史重复，本轮不再发送")
                        host.gotoChatList()
                        return PrivateChatTaskResult.COMPLETED_NO_REPLY
                    }

                    if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                        host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                    ) {
                        RuntimeLog.record(host.context, "contact reply regenerated; reason=incoming_changed")
                        host.setNextStep("收到更新消息，重新准备回复", ConversationTiming.MESSAGE_SETTLE_MS)
                        host.sleep(500)
                        continue@replyCycle
                    }
                    if (!host.isExpectedConversation(name)) {
                        host.gotoChatList()
                        return PrivateChatTaskResult.RETRY
                    }

                    val sent = host.send(reply, expectedContact = name, expectedEventAnchor = eventAnchor)
                    if (sent) {
                        RuntimeLog.record(host.context, "contact reply confirmed; contactId=$contactId")
                        host.recordAutomatedInteraction(name, focusIncoming, reply)
                        host.recordHistory(name, focusIncoming.last(), reply)
                        host.gotoChatList()
                        return PrivateChatTaskResult.COMPLETED_SENT
                    }

                    if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                        host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                    ) {
                        RuntimeLog.record(host.context, "contact reply regenerated; reason=incoming_changed_before_send")
                        host.setNextStep("发送前收到新消息，使用最新上下文重新准备回复", ConversationTiming.MESSAGE_SETTLE_MS)
                        continue@replyCycle
                    }

                    val result = if (host.hasUncertainSendAttempts(name)) {
                        PrivateChatTaskResult.VERIFY_SEND
                    } else {
                        PrivateChatTaskResult.RETRY
                    }
                    host.setNextStep(
                        if (result == PrivateChatTaskResult.VERIFY_SEND) "$name 发送结果不明确，等待确认"
                        else "$name 发送操作失败，保留任务重试",
                    )
                    host.gotoChatList()
                    return result
                }
            }
        }
        return PrivateChatTaskResult.RETRY
    }

    /** Continue in place: messages received in an open chat may no longer have a list badge. */
    fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean {
        if (!host.isRunning || !host.isOnChat() || !host.isExpectedConversation(name)) return false
        replyCycle@ while (host.isRunning && host.isOnChat()) {
            // 等对方连续一段时间不再发新消息，并先完成所有可见语音的转写。
            val thread = host.waitForStableThread(name)
            if (!host.isRunning || !host.isOnChat() || !host.isExpectedConversation(name)) return false
            host.learnFromManualReplies(name, thread)
            val incoming = thread.filter { it.first == "in" }
            RuntimeLog.record(
                host.context,
                "conversation loaded; contactId=${DiagnosticIdentity.contactId(host.context, name)}; messages=${thread.size}; incoming=${incoming.size}; pendingVoice=${host.pendingIncomingVoiceBubbles().size}",
            )
            if (incoming.isEmpty()) {
                RuntimeLog.record(host.context, "conversation reply aborted; reason=no_readable_incoming")
                host.setNextStep("未读内容不可读取，返回会话列表", 1200)
                host.sleep(1200)
                host.gotoChatList()
                return false
            }
            val pendingCount = OpeningConversationRoute.pendingIncoming(thread).size
            val newEvents = host.unseenIncomingEvents(name, thread, seen, pendingCount)
            val new = newEvents.map { it.text }
            if (new.isEmpty()) {
                RuntimeLog.record(host.context, "conversation reply skipped; reason=no_unseen_message")
                host.gotoChatList()
                return false
            }
            if (ReplyRhythmPolicy.shouldWaitForLowInformation(thread, new)) {
                seen.markSuppressedEvents(host.memoryThreadId(name), newEvents)
                RuntimeLog.record(
                    host.context,
                    "conversation reply paused; reason=repeated_low_information; contactId=${DiagnosticIdentity.contactId(host.context, name)}",
                )
                host.setNextStep("$name 连续简短回应，本轮自然停顿")
                host.gotoChatList()
                return false
            }
            val eventAnchor = host.currentIncomingMessageKeys(name).joinToString(",")

            val generationResult = host.generateValidatedChatReply(name, thread, new)
            val reply = when (generationResult) {
                is ChatReplyGenerationResult.ReadyToSend -> generationResult.text
                is ChatReplyGenerationResult.NoReplyRequired -> {
                    if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                        host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                    ) {
                        RuntimeLog.record(
                            host.context,
                            "no-reply decision discarded; reason=incoming_changed_after_review",
                        )
                        continue@replyCycle
                    }
                    seen.markNoReplyRequiredEvents(
                        host.memoryThreadId(name),
                        newEvents,
                        generationResult.reason,
                    )
                    RuntimeLog.record(
                        host.context,
                        "conversation reply intentionally skipped; reason=${generationResult.reason}; contactId=${DiagnosticIdentity.contactId(host.context, name)}",
                    )
                    host.setNextStep("$name 本轮无需回复，已归档", 800)
                    host.gotoChatList()
                    return false
                }
                null -> null
            }
            if (reply.isNullOrBlank()) {
                if (!host.isExpectedConversation(name)) return false
                val refreshedAfterCancel = host.readThread()
                if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                    host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                ) {
                    RuntimeLog.record(host.context, "conversation reply regenerated; reason=generation_cancelled_for_new_message")
                    host.setNextStep("收到新消息，改用最新上下文重新准备回复", ConversationTiming.MESSAGE_SETTLE_MS)
                    continue@replyCycle
                }
                seen.retryEvents(host.memoryThreadId(name), newEvents, "generation_failed")
                RuntimeLog.record(host.context, "conversation reply aborted; reason=generation_failed")
                val reason = ModelRouter.lastError.ifBlank { "连续生成内容不合格" }.take(36)
                host.setNextStep("本轮回复失败：$reason，返回列表避免卡住", 2000)
                host.sleep(2000)
                host.gotoChatList()
                return false
            }
            run {
                val normalized = host.normalizeReply(reply)
                val repeated = thread.filter { it.first == "out" }
                    .map { host.normalizeReply(it.second) }
                    .any { it.isNotEmpty() && it == normalized }
                if (repeated) {
                    seen.markSuppressedEvents(host.memoryThreadId(name), newEvents)
                    RuntimeLog.record(host.context, "conversation reply aborted; reason=duplicate_output")
                    host.setNextStep("回复内容与历史重复，本轮不发送并返回列表", 1500)
                    host.sleep(1500)
                    host.gotoChatList()
                    return false
                }
            }

            // 模型生成期间对方可能又发了文字或语音。此时废弃旧回复，重新等待并生成。
            if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
            ) {
                RuntimeLog.record(host.context, "conversation reply regenerated; reason=incoming_changed")
                host.setNextStep("收到更新消息，重新准备回复", ConversationTiming.MESSAGE_SETTLE_MS)
                host.sleep(500)
                continue@replyCycle
            }
            if (!host.isExpectedConversation(name)) {
                seen.retryEvents(host.memoryThreadId(name), newEvents, "conversation_changed")
                RuntimeLog.record(host.context, "conversation reply cancelled; reason=conversation_changed")
                host.setNextStep("会话已切换，取消本次发送并重新检查列表", 800)
                host.gotoChatList()
                return false
            }

            val sent = host.send(
                reply,
                expectedContact = name,
                expectedEventAnchor = eventAnchor,
            )
            if (sent) {
                RuntimeLog.record(host.context, "conversation reply confirmed; unseenMessages=${new.size}")
                seen.markSeenEvents(host.memoryThreadId(name), newEvents)
                host.recordAutomatedInteraction(name, new, reply)
                host.recordHistory(name, incoming.last().second, reply)
                host.gotoChatList()
                return true
            } else if (host.isRunning && host.isOnChat()) {
                if (host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                    host.currentIncomingMessageKeys(name).joinToString(",") != eventAnchor
                ) {
                    RuntimeLog.record(host.context, "conversation reply regenerated; reason=incoming_changed_before_send")
                    host.setNextStep("发送前收到新消息，使用最新上下文重新准备回复", ConversationTiming.MESSAGE_SETTLE_MS)
                    continue@replyCycle
                }
                seen.markUncertainEvents(host.memoryThreadId(name), newEvents, "send_not_confirmed")
                RuntimeLog.record(host.context, "conversation reply send failed; chatStillVisible=true")
                host.setNextStep("回复未完成，保留当前会话")
            } else if (!host.isRunning || !host.isOnChat()) {
                seen.retryEvents(host.memoryThreadId(name), newEvents, "chat_closed_before_send")
            }
            return false
        }
        return false
    }
}
