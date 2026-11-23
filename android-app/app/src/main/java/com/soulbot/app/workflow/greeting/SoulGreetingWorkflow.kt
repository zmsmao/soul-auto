package com.soulbot.app.workflow.greeting

import com.soulbot.app.*

/** Owns new-greeting discovery and reply processing. */
internal class SoulGreetingWorkflow(private val host: SoulGreetingHost) {
    fun hasGreeting(): Boolean = host.findVisibleById(ID_GREETING_TITLE) != null

    fun openGreetingList(): Boolean {
        host.setNextStep("打开新招呼列表")
        for (i in 0 until 2) {
            if (host.currentScreen() == SoulScreen.GREETING_LIST) return true
            host.findById(ID_GREETING_TITLE)?.let { host.tapNode(it) }
            host.sleep(1500)
        }
        return host.currentScreen() == SoulScreen.GREETING_LIST
    }

    fun scanGreetingUsers(
        handledKeys: Set<String>,
        greetDb: GreetDatabase,
    ): List<String> {
        if (host.currentScreen() != SoulScreen.GREETING_LIST) return emptyList()
        val visibleNames = host.findByIds(ID_CONV_NAME)
            .filter(host::isVisibleOnScreen)
            .map(host::textOf)
        return GreetingSessionPolicy.pendingUsers(visibleNames, handledKeys)
            .filter { name ->
                greetDb.shouldGreet(
                    "新招呼:$name",
                    GreetingSessionPolicy.SYSTEM_GREETING_CONTEXT,
                )
            }
    }

    fun replyToGreeting(
        name: String,
        seen: ConversationSeenTracker,
        handledKeys: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean {
        if (host.currentScreen() != SoulScreen.GREETING_LIST) return false
        val greetingKey = GreetingSessionPolicy.userKey(name)
        if (greetingKey.isBlank() || !handledKeys.add(greetingKey)) return false
        val nameNode = host.findByIds(ID_CONV_NAME)
            .filter(::isVisibleOnScreen)
            .firstOrNull { host.textOf(it) == name } ?: return false
        host.setNextStep("打开 $name 的招呼")
        host.tapNode(nameNode)
        host.sleep(1500)
        if (!host.isOnChat()) {
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
            else { host.pressBack(); host.sleep(1200) }
            return false
        }
        if (!host.isExpectedConversation(name)) {
            host.setNextStep("招呼列表发生变化，取消错误会话", 800)
            host.pressBack()
            host.sleep(800)
            return false
        }
        val thread = host.waitForStableThread(name)
        if (!host.isRunning || !host.isOnChat() || !host.isExpectedConversation(name)) return false
        host.learnFromManualReplies(name, thread)
        val incoming = thread.filter { it.first == "in" }
        val systemGreetingName = "新招呼:$name"
        if (incoming.isEmpty()) {
            if (host.pendingIncomingVoiceBubbles().isNotEmpty()) {
                host.setNextStep("$name 的语音尚未转写，本轮暂不处理", 1200)
                host.pressBack()
                host.sleep(1200)
                return false
            }
            // Soul can show an app-generated preview under "new greetings" even
            // though the person never sent a chat message. Persist the decision so
            // an accessibility-service/worker restart cannot reopen the same row.
            greetDb.recordGreet(
                systemGreetingName,
                GreetingSessionPolicy.SYSTEM_GREETING_CONTEXT,
            )
            RuntimeLog.record(
                host.context,
                "greeting ignored; reason=system_only; contactId=${DiagnosticIdentity.contactId(host.context, name)}",
            )
            host.setNextStep("$name 只有系统招呼，没有真实消息，已忽略", 1200)
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
            else { host.pressBack(); host.sleep(1200) }
            return false
        }
        val pendingCount = OpeningConversationRoute.pendingIncoming(thread).size
        val newEvents = host.unseenIncomingEvents(name, thread, seen, pendingCount)
        val new = newEvents.map { it.text }
        if (incoming.isNotEmpty() && new.isEmpty()) {
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
            else { host.pressBack(); host.sleep(1200) }
            return false
        }
        val replyContext = new.last()
        if (!greetDb.shouldGreet(systemGreetingName, replyContext)) {
            host.setNextStep("$name 的这条招呼已处理，返回列表", 800)
            host.pressBack()
            host.sleep(800)
            return false
        }
        if (ReplyRhythmPolicy.shouldWaitForLowInformation(thread, new)) {
            seen.markSeenEvents(host.memoryThreadId(name), newEvents)
            greetDb.recordGreet(systemGreetingName, replyContext)
            host.setNextStep("$name 连续简短回应，本轮自然停顿")
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
            else { host.pressBack(); host.sleep(800) }
            return false
        }
        val greetingEventKey = host.currentIncomingMessageKeys(name).joinToString(",")
        val reply = host.generateValidatedChatReply(name, thread, new) ?: run {
            if (host.isExpectedConversation(name) &&
                host.currentIncomingMessageKeys(name).joinToString(",") != greetingEventKey
            ) {
                return host.replyToCurrentConversation(name, seen)
            }
            host.setNextStep("招呼回复生成失败，返回列表避免卡住", 1200)
            if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
            else { host.pressBack(); host.sleep(1200) }
            return false
        }
        if (host.currentScreen() == SoulScreen.QIYU_POPUP) {
            host.handleQiyu()
            return false
        }
        val sent = host.send(
            reply,
            expectedContact = name,
            expectedEventAnchor = greetingEventKey,
        )
        if (sent) {
            seen.markSeenEvents(host.memoryThreadId(name), newEvents)
            greetDb.recordGreet(systemGreetingName, replyContext)
            host.recordAutomatedInteraction(name, new, reply)
            host.recordHistory(name, replyContext, reply)
            host.setNextStep("已确认给 $name 发出消息，返回招呼列表", 800)
        } else if (host.isOnChat() && host.isExpectedConversation(name) &&
            host.currentIncomingMessageKeys(name).joinToString(",") != greetingEventKey
        ) {
            RuntimeLog.record(host.context, "greeting reply regenerated; reason=incoming_changed_before_send")
            return host.replyToCurrentConversation(name, seen)
        } else {
            host.setNextStep("给 $name 的消息未发出，本轮不再重复进入", 1500)
            host.sleep(1500)
        }
        if (host.currentScreen() == SoulScreen.QIYU_POPUP) host.handleQiyu()
        else { host.pressBack(); host.sleep(1200) }
        return sent
    }
}
