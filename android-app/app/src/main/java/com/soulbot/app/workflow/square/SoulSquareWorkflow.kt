package com.soulbot.app.workflow.square
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

/** Owns the Square browse/inspect/open-chat loop; Android access stays behind SoulSquareHost. */
internal class SoulSquareWorkflow(private val host: SoulSquareHost) {
    private data class SquarePostCandidate(
        val text: String,
        val avatar: AccessibilityNodeInfo,
        val viewportKey: String,
    )

    fun browseOnce(
        seenDm: MutableSet<String>,
        attemptedDmProfiles: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean = browseSquareOnce(seenDm, attemptedDmProfiles, greetDb)

    private fun readPosts(expectedChannel: String): List<SquarePostCandidate> {
        if (!host.isOnSquare()) return emptyList()
        if (host.selectedSquareChannel() != expectedChannel) return emptyList()
        val cards = host.findByIds(SoulBotService.ID_SQUARE_CARD)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val result = mutableListOf<SquarePostCandidate>()
        for (card in cards) {
            val cb = host.rect(card)
            // ViewPager keeps neighbouring channels in the accessibility tree and
            // their cards can share screen coordinates. Bind the text and avatar
            // through this card's own subtree, never through global geometry.
            val text = host.descendantsById(card, SoulBotService.ID_SQUARE_TEXT)
                .asSequence()
                .filter(host::isVisibleOnScreen)
                .map(host::textOf)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            val avatar = host.descendantsById(card, SoulBotService.ID_SQUARE_AVATAR)
                .firstOrNull(host::isVisibleOnScreen)
            if (text.isNotBlank() && avatar != null) {
                result += SquarePostCandidate(
                    text = text,
                    avatar = avatar,
                    viewportKey = SquareBrowsePolicy.viewportKey(text, cb.top, host.dp(48)),
                )
            }
        }
        return result.distinctBy { it.viewportKey }
    }

    private fun squarePostSignature(posts: List<SquarePostCandidate>): String =
        posts.joinToString("|") { it.viewportKey }

    private fun waitForStableSquarePosts(expectedChannel: String): List<SquarePostCandidate> {
        val deadline = System.currentTimeMillis() + SoulBotService.SQUARE_POST_LOAD_TIMEOUT_MS
        var latest = emptyList<SquarePostCandidate>()
        var lastSignature = ""
        var stableSince = System.currentTimeMillis()
        while (host.isRunning && host.isOnSquare() && host.selectedSquareChannel() == expectedChannel) {
            val current = readPosts(expectedChannel)
            val signature = squarePostSignature(current)
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = System.currentTimeMillis()
            }
            latest = current
            val stableFor = System.currentTimeMillis() - stableSince
            if (latest.isNotEmpty() && stableFor >= SoulBotService.SQUARE_POST_STABLE_MS) return latest

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return latest
            val action = if (latest.isEmpty()) {
                "等待同城动态加载"
            } else {
                "确认本屏 ${latest.size} 条动态已加载"
            }
            host.setNextStep(action, minOf(remaining, SoulBotService.SQUARE_POST_STABLE_MS))
            host.sleep(minOf(300L, remaining))
        }
        return latest
    }

    private fun getProfileName(): String =
        if (host.isOnProfile()) host.textOf(host.findById(SoulBotService.ID_PROFILE_NAME)) else ""

    private fun openProfile(avatar: AccessibilityNodeInfo): Boolean {
        val bounds = host.rect(avatar)
        host.setNextStep("打开同城用户主页")
        for (attempt in 0 until 2) {
            if (attempt == 0) {
                host.tapNode(avatar)
            } else {
                RuntimeLog.record(host.context, "square profile open retry; attempt=2")
                host.tap(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())
            }
            for (wait in 0 until 8) {
                host.sleep(250)
                if (host.isOnProfile()) return true
                if (!host.isSoulForeground()) return false
            }
            if (!host.isOnSquare()) return false
        }
        RuntimeLog.record(host.context, "square profile open failed; attempts=2")
        return false
    }

    private fun browseSquareOnce(
        seenDm: MutableSet<String>,
        attemptedDmProfiles: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean {
        if (!host.isOnSquare()) return false
        val channel = host.ensureLocalSquareChannel() ?: run {
            host.setStatusText("未找到同城频道")
            RuntimeLog.record(host.context, "square browse aborted; reason=local_channel_missing")
            return false
        }
        RuntimeLog.record(host.context, "square browse started; maxViewports=$SoulBotService.SQUARE_MAX_VIEWPORTS")
        host.setNextStep("浏览同城·$channel")
        val handledInViewport = mutableSetOf<String>()
        val transientFailures = mutableMapOf<String, Int>()
        var viewportCount = 0
        var lastScrolledFrom = ""
        while (viewportCount < SoulBotService.SQUARE_MAX_VIEWPORTS) {
            if (host.currentScreen() == SoulScreen.QIYU_POPUP || host.hasUnreadRedDot()) return false
            if (host.selectedSquareChannel() != channel) {
                RuntimeLog.record(host.context, "square browse stopped; reason=channel_changed")
                return false
            }
            val posts = waitForStableSquarePosts(channel)
            RuntimeLog.record(
                host.context,
                "square viewport scanned; viewport=${viewportCount + 1}; posts=${posts.size}; handled=${handledInViewport.size}",
            )
            val candidate = posts.firstOrNull { post ->
                post.viewportKey !in handledInViewport &&
                    SquareBrowsePolicy.shouldRetryTransientFailure(
                        transientFailures[post.viewportKey] ?: 0,
                    )
            }

            if (candidate == null) {
                // Re-read once after the list has settled. Only advance when every
                // currently visible candidate is handled or has exhausted retries.
                val confirmedPosts = waitForStableSquarePosts(channel)
                val remaining = confirmedPosts.firstOrNull { post ->
                    post.viewportKey !in handledInViewport &&
                        SquareBrowsePolicy.shouldRetryTransientFailure(
                            transientFailures[post.viewportKey] ?: 0,
                        )
                }
                if (remaining != null) continue

                val signature = squarePostSignature(confirmedPosts)
                val prepareDelay = if (signature == lastScrolledFrom) 3000L else SoulBotService.SQUARE_SCROLL_PREPARE_MS
                host.setNextStep("本屏动态已检查，${prepareDelay / 1000} 秒后下滑", prepareDelay)
                host.sleep(prepareDelay)
                if (host.currentScreen() == SoulScreen.QIYU_POPUP || host.hasUnreadRedDot()) return false
                if (!host.isOnSquare() || host.selectedSquareChannel() != channel) return false

                lastScrolledFrom = signature
                // Shorter and slower than the old half-screen fling. The overlap
                // keeps cards near both edges visible on the next pass.
                host.swipeVertically(SoulBotService.ID_SQUARE_PAGER, 0.76f, 0.50f, 650)
                host.setNextStep("等待下方动态加载", SoulBotService.SQUARE_SCROLL_SETTLE_MS)
                host.sleep(SoulBotService.SQUARE_SCROLL_SETTLE_MS)
                viewportCount++
                handledInViewport.clear()
                transientFailures.clear()
                continue
            }

            val text = candidate.text
            val avatar = candidate.avatar
            val viewportKey = candidate.viewportKey
            if (!host.isVisibleOnScreen(avatar)) {
                transientFailures[viewportKey] = (transientFailures[viewportKey] ?: 0) + 1
                continue
            }

            host.setNextStep("检查当前同城动态")
            if (!openProfile(avatar)) {
                val attempts = (transientFailures[viewportKey] ?: 0) + 1
                transientFailures[viewportKey] = attempts
                if (!host.backToSquare()) return false
                if (SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                    host.setNextStep("头像打开失败，重新扫描本屏", 1200)
                    host.sleep(1200)
                } else {
                    handledInViewport += viewportKey
                }
                continue
            }

            val name = getProfileName()
            if (name.isBlank()) {
                val attempts = (transientFailures[viewportKey] ?: 0) + 1
                transientFailures[viewportKey] = attempts
                if (!host.backToSquare()) return false
                if (!SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                    handledInViewport += viewportKey
                }
                continue
            }

            val profileKey = SquareBrowsePolicy.profileKey(name)
            if (profileKey.isBlank() || profileKey in attemptedDmProfiles) {
                handledInViewport += viewportKey
                if (!host.backToSquare()) return false
                continue
            }
            // Reserve before opening private chat. The reservation survives the
            // overlapping area of the next short swipe, even after a modal detour.
            attemptedDmProfiles += profileKey

            val postKey = "$name\u0000$text"
            if (postKey in seenDm ||
                !greetDb.shouldGreet(
                    name,
                    text,
                    Prefs.getGreetIntervalHours(host.context),
                )
            ) {
                seenDm += postKey
                handledInViewport += viewportKey
                if (!host.backToSquare()) return false
                continue
            }

            val secret = host.findById(SoulBotService.ID_CHAT_SECRET)
            if (secret == null) {
                handledInViewport += viewportKey
                if (!host.backToSquare()) return false
                continue
            }
            host.setNextStep("打开 $name 的私聊")
            host.tapNode(secret)
            host.sleep(2500)
            when {
                host.isOnNoSend() -> {
                    seenDm += postKey
                    handledInViewport += viewportKey
                    host.skipNoSend()
                    if (!host.backToSquare()) return false
                }
                host.isOnGift() -> {
                    seenDm += postKey
                    handledInViewport += viewportKey
                    host.skipGift()
                    if (!host.backToSquare()) return false
                }
                host.isOnChat() -> {
                    val contactId = DiagnosticIdentity.contactId(host.context, name)
                    RuntimeLog.record(host.context, "square private chat opened; contactId=$contactId")
                    val openingContext = OpeningContext(ReplyScene.SQUARE, name, text)
                    val initialIncoming = incomingSignature(host.readThread())
                    val initialEventAnchor = host.currentIncomingMessageKeys(name).joinToString(",")
                    val reply = host.generateValidatedOpening(
                        "生成给 $name 的广场开场白",
                        openingContext,
                    )
                    val canSend = host.isRunning && host.isOnChat() && host.isExpectedConversation(name) &&
                        host.pendingIncomingVoiceBubbles().isEmpty() &&
                        incomingSignature(host.readThread()) == initialIncoming &&
                        host.currentIncomingMessageKeys(name).joinToString(",") == initialEventAnchor
                    if (!canSend && host.isRunning && host.isOnChat() && host.isExpectedConversation(name)) {
                        val currentThreadChanged = host.pendingIncomingVoiceBubbles().isNotEmpty() ||
                            incomingSignature(host.readThread()) != initialIncoming ||
                            host.currentIncomingMessageKeys(name).joinToString(",") != initialEventAnchor
                        if (currentThreadChanged) {
                            host.setNextStep("对方已发来新消息，取消广场开场并直接回复")
                            val replied = host.replyToCurrentConversation(name, ConversationSeenTracker())
                            if (replied) {
                                seenDm += postKey
                                greetDb.recordGreet(name, text)
                            }
                            host.backToSquare()
                            return replied
                        }
                        if (host.hasUnreadRedDot()) {
                            RuntimeLog.record(host.context, "square opening interrupted; reason=new_unread_inbox")
                            host.setNextStep("发现新的私聊未读，停止广场主动任务并优先处理")
                            host.returnToList()
                            return false
                        }
                    }
                    val squareSendKey = reply?.let {
                        ConversationIdentity.sendKey(
                            host.memoryThreadId(name),
                            ReplyScene.SQUARE.name,
                            listOf(ConversationIdentity.digest(postKey)),
                            it,
                        )
                    }
                    val sent = reply != null && canSend && host.send(
                        reply,
                        expectedContact = name,
                        idempotencyKey = squareSendKey,
                        sceneOverride = ReplyScene.SQUARE,
                        expectedEventAnchor = initialEventAnchor,
                    )
                    if (!sent && reply != null && host.isOnChat() && host.isExpectedConversation(name) &&
                        host.currentIncomingMessageKeys(name).joinToString(",") != initialEventAnchor
                    ) {
                        host.setNextStep("广场开场发送前收到新消息，改为回复最新内容")
                        val replied = host.replyToCurrentConversation(name, ConversationSeenTracker())
                        if (replied) {
                            seenDm += postKey
                            greetDb.recordGreet(name, text)
                        }
                        host.backToSquare()
                        return replied
                    }
                    if (sent) {
                        seenDm += postKey
                        greetDb.recordGreet(name, text)
                        host.recordAutomatedInteraction(name, listOf(text), reply, openingContext, scene = ReplyScene.SQUARE)
                        host.recordHistory(name, text, reply)
                    }
                    RuntimeLog.record(
                        host.context,
                        "square private chat ended; contactId=$contactId; sent=$sent",
                    )
                    host.backToSquare()
                    return sent
                }
                else -> {
                    val attempts = (transientFailures[viewportKey] ?: 0) + 1
                    transientFailures[viewportKey] = attempts
                    if (!host.backToSquare()) return false
                    if (!SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                        handledInViewport += viewportKey
                    }
                }
            }
        }
        RuntimeLog.record(host.context, "square browse ended; sent=false; scannedViewports=$viewportCount")
        return false
    }
    private fun incomingSignature(thread: List<Pair<String, String>>): String =
        thread.filter { it.first == "in" }.joinToString("\u0001") { it.second }

}
