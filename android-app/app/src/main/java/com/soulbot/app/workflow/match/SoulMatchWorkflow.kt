package com.soulbot.app.workflow.match
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

import android.view.accessibility.AccessibilityNodeInfo

/** Owns Soul Match entry, conversation decisions and the planet idle loop. */
internal class SoulMatchWorkflow(private val host: SoulMatchHost) {

    fun handleSoulMatchChat(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ): Boolean = handleSoulMatchChatInternal(handledMatches, greetDb, seen)

    fun handlePlanetChatIdle(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ) = handlePlanetChatIdleInternal(handledMatches, greetDb, seen)

    private fun startSoulMatch(): Boolean {
        if (!host.isSoulPlanetSurface()) return false
        val startButton = host.findExactText("开始匹配").firstOrNull()
        if (startButton != null) {
            host.setNextStep("点击“开始匹配”")
            if (!host.tapNode(startButton)) return false
            host.sleep(800)
        } else {
            // The button disappears while matching. Re-entering this method in that
            // state should continue waiting instead of tapping arbitrary coordinates.
            host.setNextStep("等待正在进行的灵魂匹配")
        }

        val deadline = System.currentTimeMillis() + SoulBotService.SOUL_MATCH_TIMEOUT_MS
        while (host.isRunning && host.isSoulForeground() && System.currentTimeMillis() < deadline) {
            when (host.currentScreen()) {
                SoulScreen.SOUL_MATCH_CHAT -> return true
                SoulScreen.QIYU_POPUP -> {
                    host.handleQiyu()
                    return false
                }
                else -> Unit
            }
            if (host.hasUnreadRedDot()) {
                host.setNextStep("发现聊天消息，暂停匹配并优先回复")
                host.gotoChatList()
                return false
            }
            val failed = listOf("匹配失败", "今日匹配次数已用完", "今日次数已用完")
                .any { host.findExactText(it).isNotEmpty() }
            if (failed) {
                host.setNextStep("本次灵魂匹配未成功")
                return false
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            host.setNextStep("等待灵魂匹配完成", remaining)
            host.sleep(minOf(1000L, remaining))
        }
        if (!host.isRunning) return false
        host.setNextStep("灵魂匹配等待超时，本轮跳过")
        return false
    }

    private fun handleSoulMatchChatInternal(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ): Boolean {
        if (host.currentScreen() != SoulScreen.SOUL_MATCH_CHAT) return false

        var titleTexts = host.soulMatchProfileTexts()
        var gravityTags = SoulMatchContent.gravityTags(titleTexts)
        var zodiac = SoulMatchContent.zodiac(titleTexts)
        var contactName = SoulMatchContent.displayName(titleTexts)
        for (attempt in 0 until 10) {
            if (contactName.isNotBlank()) break
            host.setNextStep("读取匹配资料（引力签、昵称或星座）", (10L - attempt) * 500L)
            host.sleep(500)
            titleTexts = host.soulMatchProfileTexts()
            gravityTags = SoulMatchContent.gravityTags(titleTexts)
            zodiac = SoulMatchContent.zodiac(titleTexts)
            contactName = SoulMatchContent.displayName(titleTexts)
        }

        host.setNextStep("检查匹配聊天是否已有新消息", SoulBotService.MESSAGE_SETTLE_MS)
        if (!host.waitUnlessQiyu(SoulBotService.MESSAGE_SETTLE_MS)) return false
        if (!host.convertIncomingVoiceMessages()) {
            host.setNextStep("匹配聊天语音尚未转写，本轮不回复", 1200)
            host.sleep(1200)
            return false
        }
        val thread = host.readThread()
        if (!host.isExpectedConversation(contactName)) return false
        val eventAnchor = host.currentIncomingMessageKeys(contactName).joinToString(",")
        val openingBasis = SoulMatchContent.openingBasis(contactName, gravityTags, zodiac)
        if (contactName.isBlank()) {
            host.setNextStep("未确认匹配联系人，本轮不发送")
            host.gotoSoulPlanet()
            return false
        }
        val route = OpeningConversationRoute.action(thread, host.hasConversationHistory(contactName, ReplyScene.SOUL_MATCH))
        host.learnFromManualReplies(contactName, thread, ReplyScene.SOUL_MATCH)
        val pendingIncoming = OpeningConversationRoute.pendingIncoming(thread)
        val newIncomingEvents = host.unseenIncomingEvents(contactName, thread, seen, pendingIncoming.size)
        val newIncoming = newIncomingEvents.map { it.text }
        val replyingToMessage = newIncoming.isNotEmpty()
        if (replyingToMessage && ReplyRhythmPolicy.shouldWaitForLowInformation(thread, newIncoming)) {
            seen.markSeenEvents(host.memoryThreadId(contactName), newIncomingEvents)
            host.setNextStep("匹配聊天连续收到简短回应，本轮自然停顿")
            host.gotoSoulPlanet()
            return false
        }
        if (route == OpeningConversationAction.SKIP ||
            (route == OpeningConversationAction.REPLY && !replyingToMessage)
        ) {
            host.setNextStep("已有会话且没有待回复内容，不重复破冰")
            host.gotoSoulPlanet()
            return false
        }
        val openingContext = OpeningContext(ReplyScene.SOUL_MATCH, contactName, gravityTags, zodiac)

        val matchKey = SoulMatchContent.sessionKey(contactName, openingBasis)
        val persistentName = "灵魂匹配:$contactName"
        if (!replyingToMessage &&
            (matchKey in handledMatches || !greetDb.shouldGreet(persistentName, openingBasis))
        ) {
            RuntimeLog.record(
                host.context,
                "soul match skipped; reason=already_handled; contactId=${DiagnosticIdentity.contactId(host.context, contactName)}",
            )
            host.setNextStep("该灵魂匹配已处理，返回星球页", 1200)
            host.sleep(1200)
            host.gotoSoulPlanet()
            return false
        }

        val finalReply = if (replyingToMessage) {
            host.generateValidatedChatReply(contactName, thread, newIncoming, ReplyScene.SOUL_MATCH)
        } else {
            host.generateValidatedOpening(
                "根据 $contactName 的匹配资料生成开场白",
                openingContext,
            )
        }
        if (finalReply == null) {
            if (host.currentConversationHasChanged(contactName, thread) ||
                host.currentIncomingMessageKeys(contactName).joinToString(",") != eventAnchor
            ) {
                return host.replyToCurrentConversation(contactName, seen)
            }
            RuntimeLog.record(host.context, "soul-match reply generation exhausted")
            host.setNextStep("匹配回复生成失败，返回星球避免卡住", 2000)
            host.sleep(2000)
            host.gotoSoulPlanet()
            return false
        }

        val delay = host.thinkDelay()
        host.setNextStep("准备向 $contactName 发送匹配开场白", delay)
        if (!host.waitUnlessQiyu(delay)) return false
        if (!host.isRunning) return false
        if (!host.isOnChat()) {
            RuntimeLog.record(host.context, "soul match send cancelled; reason=chat_screen_changed")
            host.setNextStep("聊天界面已变化，重新检测", 1000)
            host.sleep(1000)
            return false
        }
        if (!host.isExpectedConversation(contactName) || host.pendingIncomingVoiceBubbles().isNotEmpty() ||
            incomingSignature(host.readThread()) != incomingSignature(thread) ||
            host.currentIncomingMessageKeys(contactName).joinToString(",") != eventAnchor
        ) {
            host.setNextStep("收到更新消息，重新按最新内容准备回复", SoulBotService.MESSAGE_SETTLE_MS)
            if (host.isOnChat() && host.isExpectedConversation(contactName)) {
                return host.replyToCurrentConversation(contactName, seen)
            }
            return false
        }
        val matchOpeningKey = if (replyingToMessage) null else {
            ConversationIdentity.sendKey(
                host.memoryThreadId(contactName),
                ReplyScene.SOUL_MATCH.name,
                listOf(ConversationIdentity.digest(matchKey)),
                finalReply,
            )
        }
        val sent = host.send(
            finalReply,
            expectedContact = contactName,
            idempotencyKey = matchOpeningKey,
            sceneOverride = ReplyScene.SOUL_MATCH,
            expectedEventAnchor = eventAnchor,
        )
        if (sent) {
            handledMatches += matchKey
            greetDb.recordGreet(persistentName, openingBasis)
            if (replyingToMessage) seen.markSeenEvents(host.memoryThreadId(contactName), newIncomingEvents)
            val contextMessages = if (replyingToMessage) newIncoming else listOf(openingBasis)
            val historyContext = if (replyingToMessage) {
                newIncoming.last()
            } else {
                "灵魂匹配资料：$openingBasis"
            }
            host.recordAutomatedInteraction(contactName, contextMessages, finalReply, openingContext.takeUnless { replyingToMessage }, scene = ReplyScene.SOUL_MATCH)
            host.recordHistory(contactName, historyContext, finalReply)
            host.setNextStep("灵魂匹配开场白已发送，返回星球页", 1200)
            host.sleep(1200)
            host.gotoSoulPlanet()
        } else if (host.isOnChat() && host.isExpectedConversation(contactName) &&
            host.currentIncomingMessageKeys(contactName).joinToString(",") != eventAnchor
        ) {
            RuntimeLog.record(host.context, "soul match reply regenerated; reason=incoming_changed_before_send")
            return host.replyToCurrentConversation(contactName, seen)
        }
        return sent
    }

    private fun handlePlanetChatIdleInternal(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ) {
        if (!host.isSoulPlanetSurface() && !host.gotoSoulPlanet()) return
        if (host.hasUnreadRedDot()) {
            host.setNextStep("发现新的聊天消息，优先处理")
            host.gotoChatList()
            return
        }

        host.setNextStep("没有新的聊天消息，立即开始下一次灵魂匹配")
        if (host.gotoSoulPlanet() && startSoulMatch()) {
            handleSoulMatchChat(handledMatches, greetDb, seen)
        } else if (host.isRunning) {
            if (host.hasUnreadRedDot()) {
                host.gotoChatList()
            } else if (host.isSoulPlanetSurface()) {
                host.setNextStep("本次匹配未完成，短暂检查后继续匹配", 1500)
                host.sleep(1500)
            } else {
                host.gotoSoulPlanet()
            }
        }
    }
    private fun incomingSignature(thread: List<Pair<String, String>>): String =
        thread.filter { it.first == "in" }.joinToString("\u0001") { it.second }

}
