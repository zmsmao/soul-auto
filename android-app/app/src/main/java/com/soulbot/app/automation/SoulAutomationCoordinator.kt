package com.soulbot.app.automation

import com.soulbot.app.accessibility.SoulAccessibilityIds
import com.soulbot.app.accessibility.SoulAccessibilityTiming
import com.soulbot.app.accessibility.SoulScreen
import com.soulbot.app.conversation.ConversationSeenTracker
import com.soulbot.app.data.AutomationMode
import com.soulbot.app.data.GreetDatabase
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.policy.GreetingSessionPolicy
import com.soulbot.app.queue.MessageDeliveryQueue
import com.soulbot.app.queue.MessageQueueState
import android.content.Context

/**
 * Owns task scheduling and page-priority decisions. It deliberately delegates
 * page interactions and scene-specific workflows to SoulAutomationHost.
 * The branch order below mirrors the previous SoulBotService loop.
 */
internal class SoulAutomationCoordinator(
    private val context: Context,
    private val automationMode: AutomationMode,
    private val squareIntervalSeconds: Long,
    private val greetDb: GreetDatabase,
    private val host: SoulAutomationHost,
) {
    fun run() {
        val deliveryQueue = MessageDeliveryQueue(context)
        deliveryQueue.recoverProcessingAtWorkerStart()
        val seen = ConversationSeenTracker { threadId, keys, state ->
            when {
                state == "SENT" -> deliveryQueue.updateEvents(threadId, keys, MessageQueueState.SENT)
                state == "SUPPRESSED_POLICY" -> deliveryQueue.updateEvents(threadId, keys, MessageQueueState.SUPPRESSED_POLICY)
                state.startsWith("SEND_UNCERTAIN:") -> deliveryQueue.markSendUncertain(threadId, keys, state.substringAfter(':'))
                state.startsWith("RETRY_WAIT:") -> deliveryQueue.retryEvents(threadId, keys, state.substringAfter(':'))
            }
        }
        val seenDm = mutableSetOf<String>()
        val attemptedDmProfiles = mutableSetOf<String>()


        var lastDm = 0L
        var lastScreenName = ""
        var stuckCount = 0
        var unreadTopSearchComplete = false
        val handledSoulMatches = mutableSetOf<String>()
        val handledGreetingUsers = mutableSetOf<String>()
        var greetingRecheckAt = 0L

        fun handleIdleWork() {
            if (automationMode.handlesSoulMatch) {
                host.handlePlanetChatIdle(
                    handledSoulMatches,
                    greetDb,
                    seen,
                )
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
                deliveryQueue.recoverExpiredLeases()
                val screen = host.currentScreen()
                if (!host.hasUnreadRedDot()) unreadTopSearchComplete = false
                if (screen.name == lastScreenName) stuckCount++ else {
                    lastScreenName = screen.name
                    stuckCount = 0
                    RuntimeLog.record(
                        context,
                        "screen=${screen.name} pager=${host.findVisibleById(SoulAccessibilityIds.ID_SQUARE_PAGER) != null} avatar=${host.findVisibleById(SoulAccessibilityIds.ID_SQUARE_AVATAR) != null} name=${host.findVisibleById(SoulAccessibilityIds.ID_CONV_NAME) != null} input=${host.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) != null}"
                    )
                }
                if (stuckCount > 8 && !host.hasPendingQiyu()) {
                    RuntimeLog.record(
                        context,
                        "screen state stalled; screen=${screen.name}; consecutiveChecks=$stuckCount",
                    )
                    host.setNextStep("重新检测当前页面", 5000)
                    host.sleep(5000)
                    stuckCount = 0
                    continue
                }

                // 奇遇铃高于匹配、广场和普通聊天，在两个工作模式中都立即处理。
                if (screen == SoulScreen.QIYU_POPUP) {
                    host.handleQiyu()
                    continue
                }

                // A transient title/read/send failure must not fall through to the
                // ordinary CHAT -> idle navigation and silently abandon an encounter.
                if (host.hasPendingQiyu()) {
                    if (screen == SoulScreen.CHAT || screen == SoulScreen.SOUL_MATCH_CHAT || screen == SoulScreen.OTHER) {
                        host.resumePendingQiyu()
                        continue
                    }
                    if (screen != SoulScreen.OUTSIDE) {
                        RuntimeLog.record(context, "qiyu cancelled; reason=left_chat; screen=${screen.name}")
                        host.clearPendingQiyu()
                    }
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

                // 两个模式都先处理招呼和未读聊天；空闲时才执行各自的主动找人方式。
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
                            host.handlePlanetChatIdle(
                                handledSoulMatches,
                                greetDb,
                                seen,
                            )
                        } else {
                            host.gotoSquare()
                        }
                    }
                    SoulScreen.LIST -> {
                        // Private unread messages outrank greetings and all idle outbound work.
                        // The unread list itself is ordered by high-intent preview, then message age.
                        val unread = if (host.hasUnreadRedDot()) host.scanUnread() else emptyList()
                        if (unread.isNotEmpty()) {
                            unreadTopSearchComplete = false
                            val nextUnread = unread.first()
                            val name = nextUnread.name
                            val timeStr = nextUnread.timeText
                            val delay = host.replyDelay(timeStr)
                            if (delay > 0) {
                                host.setNextStep("回复 $name", delay)
                                if (!host.waitUnlessQiyu(delay)) continue@mainLoop
                            }
                            host.setNextStep(
                                if (unread.size > 1) {
                                    "共 ${unread.size} 条未读，优先回复 $name"
                                } else {
                                    "回复 $name"
                                },
                            )
                            host.replyTo(name, seen)
                        } else if (host.hasUnreadRedDot() && !unreadTopSearchComplete) {
                            // Scan from the top once before accepting that the private inbox is empty.
                            // This prevents a greeting task from hiding unread rows above the current viewport.
                            host.setNextStep("回到会话顶部，优先检查私聊未读")
                            host.scrollConversationToTop()
                            unreadTopSearchComplete = true
                            host.setNextStep("重新检查未读消息", 800)
                            host.sleep(800)
                        } else if (host.hasGreeting() && System.currentTimeMillis() >= greetingRecheckAt) {
                            // Only handle greetings after the private inbox has been scanned and has no unread row.
                            host.setNextStep("私聊未读已处理，开始处理新招呼")
                            host.openGreetingList()
                        } else {
                            if (!host.hasUnreadRedDot()) unreadTopSearchComplete = false
                            // With no private unread rows (or a stale, fully scanned dot),
                            // normal greeting and idle work can proceed.
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
                    SoulScreen.PROFILE -> {
                        host.gotoModeIdleSurface(automationMode)
                    }
                    SoulScreen.CHAT -> host.gotoModeIdleSurface(automationMode)
                    else -> host.gotoModeIdleSurface(automationMode)
                }
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}：${e.message.orEmpty()}".take(48)
                RuntimeLog.record(context, "page flow error: $detail", e)
                host.setNextStep("页面读取异常：$detail，重新检测", 2000)
                host.sleep(2000)
            }
        }
    }
}