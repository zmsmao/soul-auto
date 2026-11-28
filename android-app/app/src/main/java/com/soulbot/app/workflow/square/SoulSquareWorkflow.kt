package com.soulbot.app.workflow.square

import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.logging.DiagnosticIdentity
import com.soulbot.app.accessibility.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/** Owns the Square browse/inspect/open-chat loop; Android access stays behind SoulSquareHost. */
internal class SoulSquareWorkflow(private val host: SoulSquareHost) {
    private data class SquarePostCandidate(
        val text: String,
        val card: AccessibilityNodeInfo,
        val avatar: AccessibilityNodeInfo,
        val viewportKey: String,
        val skipReason: String?,
    )

    fun browseOnce(
        seenDm: MutableSet<String>,
        attemptedDmProfiles: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean = browseSquareOnce(seenDm, attemptedDmProfiles, greetDb)

    private fun readPosts(expectedChannel: String): List<SquarePostCandidate> {
        if (!host.isOnSquare()) return emptyList()
        if (host.selectedSquareChannel() != expectedChannel) return emptyList()
        val cards = host.findByIds(SoulAccessibilityIds.ID_SQUARE_CARD)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val result = mutableListOf<SquarePostCandidate>()
        for (card in cards) {
            val cb = host.rect(card)
            // ViewPager keeps neighbouring channels in the accessibility tree and
            // their cards can share screen coordinates. Bind the text and avatar
            // through this card's own subtree, never through global geometry.
            val text = host.descendantsById(card, SoulAccessibilityIds.ID_SQUARE_TEXT)
                .asSequence()
                .filter(host::isVisibleOnScreen)
                .map(host::textOf)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            val avatar = host.descendantsById(card, SoulAccessibilityIds.ID_SQUARE_AVATAR)
                .firstOrNull(host::isVisibleOnScreen)
            if (text.isNotBlank() && avatar != null) {
                val avatarBounds = host.rect(avatar)
                val nearbyRegion = Rect(
                    avatarBounds.left - host.dp(12),
                    avatarBounds.top - host.dp(8),
                    avatarBounds.right + host.dp(24),
                    avatarBounds.bottom + host.dp(40),
                )
                val cardNodes = host.descendants(card).filter { node ->
                    runCatching { node.isVisibleToUser }.getOrDefault(false)
                }
                fun labelsOf(node: AccessibilityNodeInfo): List<String> =
                    listOf(host.textOf(node), host.contentDescriptionOf(node)).filter(String::isNotBlank)
                val allLabels = cardNodes.flatMap(::labelsOf)
                val nearbyLabels = cardNodes
                    .filter { node ->
                        val bounds = host.rect(node)
                        val compactLabel = bounds.width() <= maxOf(host.dp(100), avatarBounds.width() * 3) &&
                            bounds.height() <= maxOf(host.dp(60), avatarBounds.height() * 2)
                        compactLabel && bounds.width() > 0 && bounds.height() > 0 &&
                            Rect.intersects(bounds, nearbyRegion)
                    }
                    .flatMap(::labelsOf)
                val skipReason = SquareBrowsePolicy.liveActivitySkipReason(nearbyLabels, allLabels)
                result += SquarePostCandidate(
                    text = text,
                    card = card,
                    avatar = avatar,
                    viewportKey = SquareBrowsePolicy.viewportKey(text, cb.top, host.dp(48)),
                    skipReason = skipReason,
                )
            }
        }
        return result.distinctBy { it.viewportKey }
    }

    private fun squarePostSignature(posts: List<SquarePostCandidate>): String =
        posts.joinToString("|") { it.viewportKey }

    private fun waitForStableSquarePosts(expectedChannel: String): List<SquarePostCandidate> {
        val deadline = System.currentTimeMillis() + SoulSquareTiming.SQUARE_POST_LOAD_TIMEOUT_MS
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
            if (latest.isNotEmpty() && stableFor >= SoulSquareTiming.SQUARE_POST_STABLE_MS) return latest

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return latest
            val action = if (latest.isEmpty()) {
                "等待同城动态加载"
            } else {
                "确认本屏 ${latest.size} 条动态已加载"
            }
            host.setNextStep(action, minOf(remaining, SoulSquareTiming.SQUARE_POST_STABLE_MS))
            host.sleep(minOf(300L, remaining))
        }
        return latest
    }

    private fun getProfileName(): String =
        if (host.isOnProfile()) host.textOf(host.findById(SoulAccessibilityIds.ID_PROFILE_NAME)) else ""

    private enum class ProfileOpenResult {
        OPENED,
        UNSAFE_TARGET,
        LIVE_ROOM,
        OTHER_SCREEN,
        TIMEOUT,
    }

    private fun openProfile(card: AccessibilityNodeInfo, avatar: AccessibilityNodeInfo): ProfileOpenResult {
        host.setNextStep("安全打开同城用户主页")
        if (!host.tapSquareAvatarSafely(card, avatar)) {
            RuntimeLog.record(host.context, "square profile click rejected; reason=unsafe_avatar_target")
            return ProfileOpenResult.UNSAFE_TARGET
        }
        repeat(8) {
            host.sleep(250)
            if (host.isOnProfile()) return ProfileOpenResult.OPENED
            if (host.isLikelyLiveRoom()) return ProfileOpenResult.LIVE_ROOM
            if (!host.isSoulForeground()) return ProfileOpenResult.OTHER_SCREEN
            if (!host.isOnSquare()) return ProfileOpenResult.OTHER_SCREEN
        }
        return ProfileOpenResult.TIMEOUT
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
        RuntimeLog.record(host.context, "square browse started; maxViewports=$SoulSquareTiming.SQUARE_MAX_VIEWPORTS")
        host.setNextStep("浏览同城·$channel")
        val handledInViewport = mutableSetOf<String>()
        val transientFailures = mutableMapOf<String, Int>()
        var viewportCount = 0
        var lastScrolledFrom = ""
        while (viewportCount < SoulSquareTiming.SQUARE_MAX_VIEWPORTS) {
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
            posts.filter { it.skipReason != null && it.viewportKey !in handledInViewport }
                .forEach { liveCard ->
                    handledInViewport += liveCard.viewportKey
                    RuntimeLog.record(
                        host.context,
                        "square card skipped; reason=${liveCard.skipReason}; key=${liveCard.viewportKey.hashCode().toUInt().toString(16)}",
                    )
                }
            val candidate = posts.firstOrNull { post ->
                post.skipReason == null &&
                    post.viewportKey !in handledInViewport &&
                    SquareBrowsePolicy.shouldRetryTransientFailure(
                        transientFailures[post.viewportKey] ?: 0,
                    )
            }

            if (candidate == null) {
                // Re-read once after the list has settled. Only advance when every
                // currently visible candidate is handled or has exhausted retries.
                val confirmedPosts = waitForStableSquarePosts(channel)
                confirmedPosts.filter { it.skipReason != null && it.viewportKey !in handledInViewport }
                    .forEach { liveCard ->
                        handledInViewport += liveCard.viewportKey
                        RuntimeLog.record(host.context, "square card skipped; reason=${liveCard.skipReason}")
                    }
                val remaining = confirmedPosts.firstOrNull { post ->
                    post.skipReason == null &&
                        post.viewportKey !in handledInViewport &&
                        SquareBrowsePolicy.shouldRetryTransientFailure(
                            transientFailures[post.viewportKey] ?: 0,
                        )
                }
                if (remaining != null) continue

                val signature = squarePostSignature(confirmedPosts)
                val prepareDelay = if (signature == lastScrolledFrom) 3000L else SoulSquareTiming.SQUARE_SCROLL_PREPARE_MS
                host.setNextStep("本屏动态已检查，${prepareDelay / 1000} 秒后下滑", prepareDelay)
                host.sleep(prepareDelay)
                if (host.currentScreen() == SoulScreen.QIYU_POPUP || host.hasUnreadRedDot()) return false
                if (!host.isOnSquare() || host.selectedSquareChannel() != channel) return false

                lastScrolledFrom = signature
                // Shorter and slower than the old half-screen fling. The overlap
                // keeps cards near both edges visible on the next pass.
                host.swipeVertically(SoulAccessibilityIds.ID_SQUARE_PAGER, 0.76f, 0.50f, 650)
                host.setNextStep("等待下方动态加载", SoulSquareTiming.SQUARE_SCROLL_SETTLE_MS)
                host.sleep(SoulSquareTiming.SQUARE_SCROLL_SETTLE_MS)
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
            when (openProfile(candidate.card, avatar)) {
                ProfileOpenResult.UNSAFE_TARGET -> {
                    handledInViewport += viewportKey
                    RuntimeLog.record(host.context, "square card skipped; reason=unsafe_click_target")
                    continue
                }
                ProfileOpenResult.LIVE_ROOM -> {
                    RuntimeLog.record(host.context, "square live room intercepted; action=back_and_skip")
                    if (!host.backToSquare()) return false
                    handledInViewport += viewportKey
                    continue
                }
                ProfileOpenResult.OTHER_SCREEN -> {
                    RuntimeLog.record(host.context, "square profile navigation changed unexpectedly; action=recover")
                    if (!host.backToSquare()) return false
                    handledInViewport += viewportKey
                    continue
                }
                ProfileOpenResult.TIMEOUT -> {
                    // A safe tap that does not reach a confirmed profile is never repeated:
                    // the destination may be a live layer that the current Soul build hides.
                    if (!host.isOnSquare() && !host.backToSquare()) return false
                    handledInViewport += viewportKey
                    RuntimeLog.record(host.context, "square card skipped; reason=profile_destination_unconfirmed")
                    continue
                }
                ProfileOpenResult.OPENED -> Unit
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

            val secret = host.findById(SoulAccessibilityIds.ID_CHAT_SECRET)
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
