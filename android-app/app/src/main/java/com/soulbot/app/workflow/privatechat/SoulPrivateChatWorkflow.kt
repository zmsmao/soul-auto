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



/** Owns ordinary private-chat entry, reservation and reply processing. */
internal class SoulPrivateChatWorkflow(private val host: SoulPrivateChatHost) {
    @Volatile private var activeReplyName: String? = null

    fun replyTo(name: String, seen: ConversationSeenTracker): Boolean {
        val contactId = DiagnosticIdentity.contactId(host.context, name)
        if (name.isBlank()) {
            RuntimeLog.record(host.context, "conversation reply skipped; reason=blank_name")
            return false
        }
        if (!reserveReply(name)) {
            RuntimeLog.record(host.context, "conversation reply skipped; reason=reply_busy; contactId=$contactId")
            return false
        }
        RuntimeLog.record(host.context, "conversation reply started; contactId=$contactId")
        return try {
            replyToReserved(name, seen).also { sent ->
                RuntimeLog.record(
                    host.context,
                    "conversation reply ended; contactId=$contactId; sent=$sent",
                )
            }
        } catch (stopped: StoppedException) {
            throw stopped
        } catch (error: Throwable) {
            RuntimeLog.record(
                host.context,
                "conversation reply crashed; contactId=${DiagnosticIdentity.contactId(host.context, name)}",
                error,
            )
            host.setNextStep("$name 的会话发生变化，返回列表重试", 1200)
            if (host.isRunning && host.currentScreen() != SoulScreen.QIYU_POPUP) {
                runCatching { host.gotoChatList() }
            }
            false
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

    private fun replyToReserved(name: String, seen: ConversationSeenTracker): Boolean {
        if (!host.isOnList() || !openConversationForReply(name)) return false
        return replyToCurrentConversation(name, seen)
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

            val reply = host.generateValidatedChatReply(name, thread, new)
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
