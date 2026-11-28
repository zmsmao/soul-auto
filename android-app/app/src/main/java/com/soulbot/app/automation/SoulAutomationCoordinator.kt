package com.soulbot.app.automation

import com.soulbot.app.accessibility.SoulAccessibilityIds
import com.soulbot.app.accessibility.SoulAccessibilityTiming
import com.soulbot.app.accessibility.SoulScreen
import com.soulbot.app.conversation.ConversationSeenTracker
import com.soulbot.app.data.AutomationMode
import com.soulbot.app.data.GreetDatabase
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.policy.GreetingSessionPolicy
import com.soulbot.app.queue.ContactTask
import com.soulbot.app.queue.ContactTaskOutcome
import com.soulbot.app.queue.ContactTaskQueue
import com.soulbot.app.queue.ContactTaskState
import com.soulbot.app.queue.ContactTaskPolicy
import com.soulbot.app.queue.MessageDeliveryQueue
import com.soulbot.app.queue.MessageQueueState
import com.soulbot.app.workflow.privatechat.PrivateChatTaskResult
import android.content.Context

/**
 * Owns task scheduling and page-priority decisions.
 * Ordinary private chats are scheduled by contact; legacy event tracking remains
 * available to match/greeting workflows only.
 */
internal class SoulAutomationCoordinator(
    private val context: Context,
    private val automationMode: AutomationMode,
    private val squareIntervalSeconds: Long,
    private val greetDb: GreetDatabase,
    private val host: SoulAutomationHost,
) {
    fun run() {
        val legacyDeliveryQueue = MessageDeliveryQueue(context)
        legacyDeliveryQueue.recoverProcessingAtWorkerStart()

        val contactQueue = ContactTaskQueue(context)
        contactQueue.recoverProcessingAtWorkerStart()

        val seen = ConversationSeenTracker { threadId, keys, state ->
            when {
                state == "SENT" -> legacyDeliveryQueue.updateEvents(threadId, keys, MessageQueueState.SENT)
                state == "SUPPRESSED_POLICY" -> legacyDeliveryQueue.updateEvents(threadId, keys, MessageQueueState.SUPPRESSED_POLICY)
                state.startsWith("NO_REPLY_REQUIRED:") -> legacyDeliveryQueue.updateEvents(
                    threadId, keys, MessageQueueState.NO_REPLY_REQUIRED, state.substringAfter(':'),
                )
                state.startsWith("SEND_UNCERTAIN:") -> legacyDeliveryQueue.markSendUncertain(threadId, keys, state.substringAfter(':'))
                state.startsWith("RETRY_WAIT:") -> legacyDeliveryQueue.retryEvents(threadId, keys, state.substringAfter(':'))
            }
        }

        val seenDm = mutableSetOf<String>()
        val attemptedDmProfiles = mutableSetOf<String>()
        val handledSoulMatches = mutableSetOf<String>()
        val handledGreetingUsers = mutableSetOf<String>()

        var lastDm = 0L
        var lastScreenName = ""
        var stuckCount = 0
        var unreadTopSearchComplete = false
        var nextUnreadSweepAt = 0L
        var inboxPositionInitialized = false
        var lastUnreadRedDot = false
        var greetingRecheckAt = 0L
        var missingHeadKey = ""
        var wrappedForMissingHead = false

        fun applyTaskResult(task: ContactTask, result: PrivateChatTaskResult) {
            when (result) {
                PrivateChatTaskResult.COMPLETED_SENT ->
                    contactQueue.complete(task.contactKey, ContactTaskOutcome.SENT)
                PrivateChatTaskResult.COMPLETED_NO_REPLY ->
                    contactQueue.complete(task.contactKey, ContactTaskOutcome.NO_REPLY)
                PrivateChatTaskResult.RETRY ->
                    contactQueue.retry(task.contactKey, "private_chat_processing_failed")
                PrivateChatTaskResult.VERIFY_SEND ->
                    contactQueue.markSendUncertain(task.contactKey, "send_result_requires_confirmation")
            }
            RuntimeLog.record(
                context,
                "contact task state changed; contactId=" + task.contactKey + "; result=" + result,
            )
        }

        fun processQueueHead(head: ContactTask, alreadyClaimed: Boolean = false) {
            val task = if (alreadyClaimed) head else contactQueue.claimHead()
            if (task == null) {
                val refreshed = contactQueue.peekHead()
                when (refreshed?.state) {
                    ContactTaskState.VERIFY_SEND ->
                        host.setNextStep(refreshed.displayName + " 发送结果待确认，队列暂停", 10_000)
                    ContactTaskState.RETRY_WAIT ->
                        host.setNextStep(refreshed.displayName + " 等待重试", 3_000)
                    ContactTaskState.PROCESSING ->
                        host.setNextStep(refreshed.displayName + " 正在处理中", 1_000)
                    else -> host.setNextStep("等待联系人任务")
                }
                host.sleep(1_000)
                return
            }
            host.setNextStep("处理私聊联系人 " + task.displayName)
            val result = host.replyTo(task.contactKey, task.displayName, seen)
            applyTaskResult(task, result)
        }

        fun handleIdleWork() {
            if (automationMode.handlesSoulMatch) {
                host.handlePlanetChatIdle(handledSoulMatches, greetDb, seen)
                return
            }

            val remainMs = squareIntervalSeconds * 1000L - (System.currentTimeMillis() - lastDm)
            if (remainMs > 0L) {
                if (!host.isOnSquare() && !host.gotoSquare()) return
                host.setNextStep("停留同城广场，等待下一次私聊", remainMs)
                host.sleep(minOf(3000L, remainMs))
                return
            }

            host.setNextStep("进入同城广场并私聊")
            var sent = false
            if (host.gotoSquare()) {
                sent = host.browseSquareOnce(seenDm, attemptedDmProfiles, greetDb)
                if (host.currentScreen() == SoulScreen.QIYU_POPUP) {
                    host.handleQiyu()
                    return
                }
                if (sent) lastDm = System.currentTimeMillis()
            }
            if (!sent && host.isRunning) {
                if (host.hasUnreadRedDot()) {
                    host.gotoChatList()
                    return
                }
                if (!host.isOnSquare()) host.gotoSquare()
                RuntimeLog.record(context, "square round ended; sent=false; cooldown=false")
                host.setNextStep("继续寻找可私聊用户", SoulAccessibilityTiming.SQUARE_RETRY_DELAY_MS)
                host.sleep(SoulAccessibilityTiming.SQUARE_RETRY_DELAY_MS)
            }
        }

        mainLoop@ while (host.isRunning) {
            try {
                legacyDeliveryQueue.recoverExpiredLeases()
                contactQueue.recoverExpiredLeases()
                val screen = host.currentScreen()
                val hasUnreadRedDot = host.hasUnreadRedDot()
                if (!hasUnreadRedDot) {
                    unreadTopSearchComplete = false
                    nextUnreadSweepAt = 0L
                }
                if (hasUnreadRedDot != lastUnreadRedDot) {
                    inboxPositionInitialized = false
                }
                lastUnreadRedDot = hasUnreadRedDot
                if (screen.name == lastScreenName) {
                    stuckCount++
                } else {
                    lastScreenName = screen.name
                    stuckCount = 0
                    RuntimeLog.record(
                        context,
                        "screen=" + screen.name +
                            " pager=" + (host.findVisibleById(SoulAccessibilityIds.ID_SQUARE_PAGER) != null) +
                            " avatar=" + (host.findVisibleById(SoulAccessibilityIds.ID_SQUARE_AVATAR) != null) +
                            " name=" + (host.findVisibleById(SoulAccessibilityIds.ID_CONV_NAME) != null) +
                            " input=" + (host.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) != null),
                    )
                }
                if (stuckCount > 8 && !host.hasPendingQiyu()) {
                    RuntimeLog.record(
                        context,
                        "screen state stalled; screen=" + screen.name + "; consecutiveChecks=" + stuckCount,
                    )
                    host.setNextStep("重新检测当前页面", 5000)
                    host.sleep(5000)
                    stuckCount = 0
                    continue
                }

                if (screen == SoulScreen.QIYU_POPUP) {
                    host.handleQiyu()
                    continue
                }

                if (host.hasPendingQiyu()) {
                    if (screen == SoulScreen.CHAT || screen == SoulScreen.SOUL_MATCH_CHAT || screen == SoulScreen.OTHER) {
                        host.resumePendingQiyu()
                        continue
                    }
                    if (screen != SoulScreen.OUTSIDE) {
                        RuntimeLog.record(context, "qiyu cancelled; reason=left_chat; screen=" + screen.name)
                        host.clearPendingQiyu()
                    }
                }

                // Crash recovery: if the worker restarted while the queue head's chat was
                // already open, resume that exact contact before the normal red-dot navigation.
                val queuedHead = contactQueue.peekHead()
                if (screen == SoulScreen.CHAT && queuedHead != null) {
                    val visibleName = host.displayedConversationName()
                    if (ContactTaskPolicy.shouldResumeCurrentChat(
                            queuedHead.state,
                            sameContact = visibleName == queuedHead.displayName,
                        )
                    ) {
                        processQueueHead(queuedHead)
                    } else if (visibleName != queuedHead.displayName) {
                        host.setNextStep("检测到待处理联系人，返回列表恢复队列")
                        host.gotoChatList()
                    } else {
                        host.setNextStep(queuedHead.displayName + " 等待发送状态恢复", 1500)
                        host.sleep(1500)
                    }
                    continue
                }

                if (screen != SoulScreen.LIST &&
                    screen != SoulScreen.GREETING_LIST &&
                    host.hasUnreadRedDot() &&
                    !unreadTopSearchComplete
                ) {
                    host.setNextStep("发现聊天消息，优先返回会话列表")
                    host.gotoChatList()
                    continue
                }

                when (screen) {
                    SoulScreen.NO_SEND -> {
                        host.skipNoSend()
                        host.gotoModeIdleSurface(automationMode)
                    }
                    SoulScreen.GIFT -> {
                        host.skipGift()
                        host.gotoModeIdleSurface(automationMode)
                    }
                    SoulScreen.QIYU_POPUP -> host.handleQiyu()
                    SoulScreen.SOUL_MATCH_CHAT -> {
                        if (automationMode.handlesSoulMatch) {
                            host.handleSoulMatchChat(handledSoulMatches, greetDb, seen)
                        } else {
                            host.gotoSquare()
                        }
                    }
                    SoulScreen.SOUL_PLANET -> {
                        if (automationMode.handlesSoulMatch) {
                            host.handlePlanetChatIdle(handledSoulMatches, greetDb, seen)
                        } else {
                            host.gotoSquare()
                        }
                    }
                    SoulScreen.LIST -> {
                        if (!inboxPositionInitialized && contactQueue.peekHead() == null) {
                            host.setNextStep("定位私聊列表顶部")
                            host.scrollConversationToTop()
                            inboxPositionInitialized = true
                            continue@mainLoop
                        }
                        if (host.hasUnreadRedDot() && unreadTopSearchComplete &&
                            System.currentTimeMillis() >= nextUnreadSweepAt
                        ) {
                            unreadTopSearchComplete = false
                        }
                        // Scan one viewport only. The list provides identity + unread state;
                        // actual chat content is fetched only after opening the contact.
                        val pageRows = host.scanConversationPage()
                        pageRows.forEach { row ->
                            if (row.hasUnread) {
                                contactQueue.enqueueUnread(row.contactKey, row.name)
                            } else {
                                contactQueue.markUnreadCleared(row.contactKey)
                            }
                        }

                        val head = contactQueue.peekHead()
                        if (head != null) {
                            when (head.state) {
                                ContactTaskState.VERIFY_SEND -> {
                                    host.setNextStep(head.displayName + " 发送结果待确认，等待人工确认", 10_000)
                                    host.sleep(2_000)
                                }
                                ContactTaskState.PROCESSING -> {
                                    host.setNextStep(head.displayName + " 任务正在处理", 1_000)
                                    host.sleep(1_000)
                                }
                                ContactTaskState.RETRY_WAIT -> {
                                    val remaining = (head.nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L)
                                    if (remaining > 0L) {
                                        host.setNextStep(head.displayName + " 处理失败，等待重试", remaining)
                                        host.sleep(minOf(3_000L, remaining))
                                    } else if (pageRows.none { it.contactKey == head.contactKey }) {
                                        if (head.contactKey != missingHeadKey) {
                                            missingHeadKey = head.contactKey
                                            wrappedForMissingHead = false
                                        }
                                        if (host.scrollConversationPageDown()) {
                                            continue@mainLoop
                                        }
                                        if (!wrappedForMissingHead) {
                                            wrappedForMissingHead = true
                                            host.scrollConversationToTop()
                                            continue@mainLoop
                                        }
                                        contactQueue.retry(head.contactKey, "contact_not_found_in_inbox")
                                    } else {
                                        missingHeadKey = ""
                                        wrappedForMissingHead = false
                                        processQueueHead(head)
                                    }
                                }
                                ContactTaskState.PENDING -> {
                                    // Tasks normally belong to the current page. Legacy migrated
                                    // tasks may be elsewhere; page forward rather than scanning the
                                    // whole inbox again for every contact.
                                    if (pageRows.none { it.contactKey == head.contactKey }) {
                                        if (head.contactKey != missingHeadKey) {
                                            missingHeadKey = head.contactKey
                                            wrappedForMissingHead = false
                                        }
                                        if (host.scrollConversationPageDown()) {
                                            continue@mainLoop
                                        }
                                        if (!wrappedForMissingHead) {
                                            wrappedForMissingHead = true
                                            host.scrollConversationToTop()
                                            continue@mainLoop
                                        }
                                        contactQueue.retry(head.contactKey, "contact_not_found_in_inbox")
                                    } else {
                                        missingHeadKey = ""
                                        wrappedForMissingHead = false
                                        processQueueHead(head)
                                    }
                                }
                                ContactTaskState.DONE -> Unit
                            }
                            continue@mainLoop
                        }

                        if (host.hasUnreadRedDot() && !unreadTopSearchComplete) {
                            if (host.scrollConversationPageDown()) {
                                continue@mainLoop
                            }
                            host.setNextStep("已检查完当前私聊列表，回到顶部等待下一轮")
                            host.scrollConversationToTop()
                            unreadTopSearchComplete = true
                            nextUnreadSweepAt = System.currentTimeMillis() + 30_000L
                            host.sleep(800)
                            continue@mainLoop
                        }

                        if (host.hasGreeting() && System.currentTimeMillis() >= greetingRecheckAt) {
                            host.setNextStep("私聊未读已处理，开始处理新招呼")
                            host.openGreetingList()
                        } else {
                            handleIdleWork()
                        }
                    }
                    SoulScreen.GREETING_LIST -> {
                        val users = host.scanGreetingUsers(handledGreetingUsers, greetDb)
                        if (users.isNotEmpty()) {
                            host.setNextStep("回复招呼 " + users[0])
                            host.replyToGreeting(users[0], seen, handledGreetingUsers, greetDb)
                        } else {
                            greetingRecheckAt = System.currentTimeMillis() +
                                GreetingSessionPolicy.EMPTY_LIST_RECHECK_MS
                            host.setNextStep(
                                "本轮新招呼已检查，稍后再检查",
                                GreetingSessionPolicy.EMPTY_LIST_RECHECK_MS,
                            )
                            host.pressBack()
                            host.sleep(1200)
                        }
                    }
                    SoulScreen.OUTSIDE -> {
                        host.setNextStep("检查 Soul 是否已打开", 1500)
                        host.sleep(1500)
                    }
                    SoulScreen.SQUARE -> {
                        if (automationMode.handlesSquareDm) handleIdleWork() else host.gotoSoulPlanet()
                    }
                    SoulScreen.LIVE_ROOM -> {
                        host.setNextStep("检测到直播页面，立即退出并恢复浏览", 1000)
                        host.pressBack()
                        host.sleep(700)
                        if (host.currentScreen() == SoulScreen.LIVE_ROOM) {
                            host.gotoModeIdleSurface(automationMode)
                        }
                    }
                    SoulScreen.PROFILE -> host.gotoModeIdleSurface(automationMode)
                    SoulScreen.CHAT -> {
                        if (contactQueue.peekHead() != null) {
                            host.gotoChatList()
                        } else {
                            host.gotoModeIdleSurface(automationMode)
                        }
                    }
                    else -> host.gotoModeIdleSurface(automationMode)
                }
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                val detail = e.javaClass.simpleName + "：" + e.message.orEmpty()
                RuntimeLog.record(context, "page flow error: " + detail.take(48), e)
                host.setNextStep("页面读取异常：" + detail.take(36) + "，重新检测", 2000)
                host.sleep(2000)
            }
        }
    }
}
