package com.soulbot.app.app
import com.soulbot.app.accessibility.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.conversation.runtime.SoulConversationContextHost
import com.soulbot.app.conversation.runtime.SoulConversationContextManager
import com.soulbot.app.conversation.runtime.SoulReplyGenerationEngine
import com.soulbot.app.conversation.runtime.SoulReplyGenerationHost
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.model.*
import com.soulbot.app.policy.*

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random
import com.soulbot.app.workflow.SoulMatchContent
import com.soulbot.app.workflow.square.SoulSquareTiming
import com.soulbot.app.workflow.greeting.SoulGreetingHost
import com.soulbot.app.workflow.greeting.SoulGreetingWorkflow
import com.soulbot.app.workflow.match.SoulMatchHost
import com.soulbot.app.workflow.match.SoulMatchWorkflow
import com.soulbot.app.workflow.privatechat.SoulPrivateChatHost
import com.soulbot.app.workflow.privatechat.SoulPrivateChatWorkflow
import com.soulbot.app.workflow.qiyu.SoulQiyuHost
import com.soulbot.app.workflow.qiyu.SoulQiyuWorkflow
import com.soulbot.app.workflow.square.SoulSquareHost
import com.soulbot.app.workflow.square.SoulSquareWorkflow

class SoulBotService : AccessibilityService() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: SoulBotService? = null
        @Volatile var statusText: String = "已停止"
        @Volatile var statusDeadline: Long = 0  // 下次动作的绝对时间戳（毫秒），0=无倒计时

        const val SOUL_PACKAGE = SoulAccessibilityIds.SOUL_PACKAGE
        const val DM_INTERVAL = SoulSquareTiming.DM_INTERVAL_SECONDS
        const val CONTEXT_LEN = ReplyGenerationConfig.CONTEXT_LEN
        const val MAX_SCROLLS_TO_TOP = SoulNavigationController.MAX_SCROLLS_TO_TOP
        const val SQUARE_RETRY_DELAY_MS = SoulAccessibilityTiming.SQUARE_RETRY_DELAY_MS
        const val SQUARE_POST_LOAD_TIMEOUT_MS = SoulSquareTiming.SQUARE_POST_LOAD_TIMEOUT_MS
        const val SQUARE_POST_STABLE_MS = SoulSquareTiming.SQUARE_POST_STABLE_MS
        const val SQUARE_SCROLL_PREPARE_MS = SoulSquareTiming.SQUARE_SCROLL_PREPARE_MS
        const val SQUARE_SCROLL_SETTLE_MS = SoulSquareTiming.SQUARE_SCROLL_SETTLE_MS
        const val SQUARE_MAX_VIEWPORTS = SoulSquareTiming.SQUARE_MAX_VIEWPORTS
        const val MESSAGE_SETTLE_MS = ConversationTiming.MESSAGE_SETTLE_MS
        const val MESSAGE_LOAD_TIMEOUT_MS = ConversationTiming.MESSAGE_LOAD_TIMEOUT_MS
        const val VOICE_TRANSCRIPT_STABLE_MS = SoulVoiceMessageController.VOICE_TRANSCRIPT_STABLE_MS
        const val VOICE_TRANSCRIPT_START_GRACE_MS = SoulVoiceMessageController.VOICE_TRANSCRIPT_START_GRACE_MS

        // Soul 控件 id
        const val ID_INPUT_BOX = SoulAccessibilityIds.ID_INPUT_BOX
        const val ID_CONTENT_TEXT = SoulAccessibilityIds.ID_CONTENT_TEXT
        const val ID_SEND_BUTTON = SoulAccessibilityIds.ID_SEND_BUTTON
        const val ID_CHAT_ITEM = SoulAccessibilityIds.ID_CHAT_ITEM
        const val ID_CHAT_AVATAR = SoulAccessibilityIds.ID_CHAT_AVATAR
        const val ID_MESSAGE_READ = SoulAccessibilityIds.ID_MESSAGE_READ
        const val ID_VOICE_BUBBLE = SoulAccessibilityIds.ID_VOICE_BUBBLE
        const val ID_AUDIO_CONTENT = SoulAccessibilityIds.ID_AUDIO_CONTENT
        const val ID_EXPRESSION = SoulAccessibilityIds.ID_EXPRESSION
        const val ID_SHARED_POST_TEXT = SoulAccessibilityIds.ID_SHARED_POST_TEXT
        const val ID_CONV_LIST = SoulAccessibilityIds.ID_CONV_LIST
        const val ID_CONV_ITEM = SoulAccessibilityIds.ID_CONV_ITEM
        const val ID_CONV_NAME = SoulAccessibilityIds.ID_CONV_NAME
        const val ID_CONV_TIME = SoulAccessibilityIds.ID_CONV_TIME
        const val ID_UNREAD_BADGE = SoulAccessibilityIds.ID_UNREAD_BADGE
        const val ID_SQUARE_TAB = SoulAccessibilityIds.ID_SQUARE_TAB
        const val ID_MSG_TAB = SoulAccessibilityIds.ID_MSG_TAB
        const val ID_MSG_RED_DOT = SoulAccessibilityIds.ID_MSG_RED_DOT
        const val ID_SQUARE_CARD = SoulAccessibilityIds.ID_SQUARE_CARD
        const val ID_SQUARE_TEXT = SoulAccessibilityIds.ID_SQUARE_TEXT
        const val ID_SQUARE_AVATAR = SoulAccessibilityIds.ID_SQUARE_AVATAR
        const val ID_SQUARE_PAGER = SoulAccessibilityIds.ID_SQUARE_PAGER
        const val ID_SQUARE_CHANNEL_TAB = SoulAccessibilityIds.ID_SQUARE_CHANNEL_TAB
        const val ID_GIFT_CLOSE = SoulAccessibilityIds.ID_GIFT_CLOSE
        const val ID_NO_SEND_CLOSE = SoulAccessibilityIds.ID_NO_SEND_CLOSE
        const val ID_CHAT_SECRET = SoulAccessibilityIds.ID_CHAT_SECRET
        const val ID_PROFILE_NAME = SoulAccessibilityIds.ID_PROFILE_NAME
        const val ID_QIYU_CHAT = SoulAccessibilityIds.ID_QIYU_CHAT
        const val ID_QIYU_SIGNATURE = SoulAccessibilityIds.ID_QIYU_SIGNATURE
        const val ID_QIYU_REASON = SoulAccessibilityIds.ID_QIYU_REASON
        const val ID_QIYU_CLOSE = SoulAccessibilityIds.ID_QIYU_CLOSE
        const val ID_GREETING_TITLE = SoulAccessibilityIds.ID_GREETING_TITLE
        const val ID_GREETING_TIPS = SoulAccessibilityIds.ID_GREETING_TIPS
        const val ID_PLANET_TAB = SoulAccessibilityIds.ID_PLANET_TAB
        const val ID_GENERIC_TITLE = SoulAccessibilityIds.ID_GENERIC_TITLE
        const val ID_SOUL_MATCH_PERCENT = SoulAccessibilityIds.ID_SOUL_MATCH_PERCENT
        const val SOUL_MATCH_TIMEOUT_MS = SoulMatchWorkflow.SOUL_MATCH_TIMEOUT_MS
    }


    private val serviceComponentFactory by lazy(LazyThreadSafetyMode.NONE) {
        SoulServiceComponentFactory(this)
    }
    private val messageSender get() = serviceComponentFactory.messageSender
    private val voiceMessageController get() = serviceComponentFactory.voiceMessageController
    private val conversationListScanner get() = serviceComponentFactory.conversationListScanner
    private val replyGenerationEngine get() = serviceComponentFactory.replyGenerationEngine
    private val conversationContextManager get() = serviceComponentFactory.conversationContextManager
    private val compatibilitySnapshotCollector get() = serviceComponentFactory.compatibilitySnapshotCollector

    private val workflowHostFactory by lazy(LazyThreadSafetyMode.NONE) { SoulWorkflowHostFactory(this) }
    private val automationHost: SoulAutomationHost get() = workflowHostFactory.automationHost
    private val soulMatchWorkflow: SoulMatchWorkflow get() = workflowHostFactory.soulMatchWorkflow
    private val privateChatWorkflow: SoulPrivateChatWorkflow get() = workflowHostFactory.privateChatWorkflow
    private val greetingWorkflow: SoulGreetingWorkflow get() = workflowHostFactory.greetingWorkflow
    private val squareWorkflow: SoulSquareWorkflow get() = workflowHostFactory.squareWorkflow
    private val qiyuWorkflow: SoulQiyuWorkflow get() = workflowHostFactory.qiyuWorkflow

    private val pageUi: SoulAccessibilityUi by lazy(LazyThreadSafetyMode.NONE) { SoulAccessibilityUi(this, ::sleep) }

    private val screenDetector: SoulScreenDetector by lazy(LazyThreadSafetyMode.NONE) { SoulScreenDetector(pageUi, ::displayedConversationName) }

    private val messageReader: SoulMessageReader by lazy(LazyThreadSafetyMode.NONE) { SoulMessageReader(pageUi, ::isOnChat, ::isExpectedConversation, ::memoryThreadId, ::normalizeReply, ::voiceTranscriptFrom) }

    private val navigation: SoulNavigationController by lazy(LazyThreadSafetyMode.NONE) { SoulNavigationController(applicationContext, pageUi, ::isSoulForeground, ::currentScreen, ::isSoulPlanetSurface, { action -> setNextStep(action) }, ::sleep, ::conversationListSnapshot) }

    internal fun handleSoulMatchChat(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ): Boolean = soulMatchWorkflow.handleSoulMatchChat(handledMatches, greetDb, seen)

    internal fun handlePlanetChatIdle(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ) = soulMatchWorkflow.handlePlanetChatIdle(handledMatches, greetDb, seen)

    internal val random = Random.Default
    private var worker: Thread? = null
    internal var historyDb: HistoryDatabase? = null
    internal var learningDb: ChatLearningDatabase? = null
    internal var replyReplayDb: ReplyReplayDatabase? = null
    internal var conversationMemoryDb: ConversationMemoryDatabase? = null
    @Volatile internal var activeGenerationCancellation: ModelCallCancellation? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    internal var lastDiagnosticsSignature = ""
    private var lastDiagnosticsLogAt = 0L
    internal var lastDiagnosticVisited = 0
    internal var lastDiagnosticTruncated = false
    internal val diagnosticsSampler = CoalescingDiagnosticSampler(
        sample = { captureCompatibilitySnapshot() },
        onComplete = { stats, error ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastDiagnosticsLogAt == 0L || now - lastDiagnosticsLogAt >= 60_000L) {
                lastDiagnosticsLogAt = now
                RuntimeLog.info(
                    applicationContext, "DIAGNOSTICS", "background compatibility sample",
                    mapOf(
                        "durationMs" to stats.durationMs, "samples" to stats.completed,
                        "coalescedEvents" to stats.dropped, "visited" to lastDiagnosticVisited,
                        "truncated" to lastDiagnosticTruncated, "failureType" to error?.javaClass?.simpleName.orEmpty(),
                    ),
                )
            }
        },
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RuntimeLog.record(applicationContext, "accessibility service connected")
        if (Prefs.getTaskShouldRun(applicationContext)) {
            start()
        } else {
            statusText = Prefs.getLastStopReason(applicationContext)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != SOUL_PACKAGE) return
        // Keep the shared UI thread free for floating-window input. Never retain event/root objects here.
        diagnosticsSampler.request(intervalMs = if (running) 5_000L else 3_000L)
    }

    override fun onInterrupt() {
        RuntimeLog.record(applicationContext, "accessibility service interrupted")
    }

    override fun onDestroy() {
        diagnosticsSampler.close()
        activeGenerationCancellation?.cancel()
        RuntimeLog.record(applicationContext, "accessibility service destroyed; shouldRun=${Prefs.getTaskShouldRun(applicationContext)}")
        restartHandler.removeCallbacksAndMessages(null)
        running = false
        worker?.interrupt()
        instance = null
        super.onDestroy()
    }

    fun start() {
        Prefs.setTaskShouldRun(applicationContext, true)
        if (running || worker?.isAlive == true) {
            RuntimeLog.record(
                applicationContext,
                "task start ignored; running=$running; workerAlive=${worker?.isAlive == true}",
            )
            return
        }
        restartHandler.removeCallbacksAndMessages(null)
        running = true
        RuntimeLog.record(applicationContext, "task start requested; launching worker")
        worker = Thread { runLoop() }.apply { start() }
    }

    fun stop() {
        RuntimeLog.record(
            applicationContext,
            "task stop requested by user; running=$running; workerAlive=${worker?.isAlive == true}",
        )
        Prefs.setTaskShouldRun(applicationContext, false)
        Prefs.setLastStopReason(applicationContext, "用户手动停止")
        running = false
        statusText = "用户手动停止"
        statusDeadline = 0
        activeGenerationCancellation?.cancel()
        worker?.interrupt()
        // runLoop() clears the reference in finally. Keeping it until then prevents
        // a second worker from starting while the interrupted one is still unwinding.
    }

    // ===== 基础辅助 =====

    internal fun root(): AccessibilityNodeInfo? = pageUi.root()

    internal fun isSoulForeground(): Boolean = pageUi.isSoulForeground()

    internal fun screenWidth(): Int = pageUi.screenWidth()

    internal fun screenHeight(): Int = pageUi.screenHeight()

    internal fun dp(value: Int): Int = pageUi.dp(value)

    internal fun activeWindowRegion(): UiRegion = pageUi.activeWindowRegion()

    private fun visibleContainerRegion(id: String): UiRegion? = pageUi.visibleContainerRegion(id)

    internal fun swipeVertically(containerId: String, startRatio: Float, endRatio: Float, duration: Long = 300) = pageUi.swipeVertically(containerId, startRatio, endRatio, duration)

    private fun conversationListSnapshot(): String = conversationListScanner.snapshot()

    internal fun scrollConversationToTop() = navigation.scrollConversationToTop()

    internal fun findById(id: String): AccessibilityNodeInfo? = pageUi.findById(id)

    internal fun findByIds(id: String): List<AccessibilityNodeInfo> = pageUi.findByIds(id)

    internal fun findExactText(text: String): List<AccessibilityNodeInfo> = pageUi.findExactText(text)

    internal fun findVisibleById(id: String): AccessibilityNodeInfo? = pageUi.findVisibleById(id)

    internal fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean = pageUi.isVisibleOnScreen(node)

    internal fun rect(node: AccessibilityNodeInfo): Rect = pageUi.rect(node)

    internal fun textOf(node: AccessibilityNodeInfo?): String = pageUi.textOf(node)

    internal fun tap(x: Float, y: Float): Boolean = pageUi.tap(x, y)

    internal fun tapNode(node: AccessibilityNodeInfo): Boolean = pageUi.tapNode(node)

    internal fun longPressNode(node: AccessibilityNodeInfo): Boolean = pageUi.longPressNode(node)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) = pageUi.swipe(x1, y1, x2, y2, duration)

    internal fun pressBack() = pageUi.pressBack()

    internal fun setNextStep(action: String, delayMs: Long = 0L) {
        if (statusText != action) {
            RuntimeLog.record(
                applicationContext,
                "workflow state; stage=${diagnosticStage(action)}; stateId=${action.hashCode().toUInt().toString(16)}; delayMs=$delayMs",
            )
        }
        statusText = action
        statusDeadline = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
    }

    private fun diagnosticStage(action: String): String = when {
        action.contains("奇遇铃") -> "QIYU"
        action.contains("语音") || action.contains("转文字") -> "VOICE"
        action.contains("模型") || action.contains("生成") || action.contains("通道") -> "MODEL"
        action.contains("广场") || action.contains("同城") || action.contains("私聊") -> "SQUARE"
        action.contains("匹配") || action.contains("星球") -> "SOUL_MATCH"
        action.contains("消息") || action.contains("回复") || action.contains("发送") ||
            action.contains("输入") || action.contains("会话") || action.contains("招呼") -> "MESSAGE"
        action.contains("打开") || action.contains("返回") || action.contains("页面") ||
            action.contains("检测") -> "NAVIGATION"
        else -> "AUTOMATION"
    }

    internal fun sleep(ms: Long) {
        // Short UI waits also get a real countdown. A longer deadline that was
        // explicitly reported by the workflow (for example the next square visit)
        // always takes precedence over these internal waits.
        val now = System.currentTimeMillis()
        val ownsDeadline = running && statusDeadline <= now
        if (ownsDeadline) statusDeadline = now + ms
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StoppedException()
        } finally {
            if (ownsDeadline) statusDeadline = 0
        }
    }

    private fun waitForInputText(expected: String, timeoutMs: Long = 1000): Boolean =
        pageUi.waitForInputText(expected, timeoutMs)

    internal fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        pageUi.setText(node, text)


    internal fun contains(outer: Rect, inner: Rect): Boolean = pageUi.contains(outer, inner)

    internal fun visibleTitleTexts(): List<String> = screenDetector.visibleTitleTexts()

    private fun visibleTextsContaining(query: String): List<String> = screenDetector.visibleTextsContaining(query)

    internal fun soulMatchProfileTexts(): List<String> = screenDetector.soulMatchProfileTexts()

    private fun isSoulMatchChatSurface(): Boolean = screenDetector.isSoulMatchChatSurface()

    internal fun isSoulPlanetSurface(): Boolean = screenDetector.isSoulPlanetSurface()

    internal fun currentScreen(): SoulScreen = screenDetector.currentScreen()

    private fun captureCompatibilitySnapshot() = compatibilitySnapshotCollector.capture()

    internal fun isOnChat(): Boolean {
        val screen = currentScreen()
        return screen == SoulScreen.CHAT || screen == SoulScreen.SOUL_MATCH_CHAT
    }
    internal fun isOnList() = currentScreen() == SoulScreen.LIST
    internal fun isOnSquare() = currentScreen() == SoulScreen.SQUARE
    internal fun isOnProfile() = currentScreen() == SoulScreen.PROFILE
    internal fun isOnGift() = currentScreen() == SoulScreen.GIFT
    internal fun isOnNoSend() = currentScreen() == SoulScreen.NO_SEND

    internal fun selectedSquareChannel(): String = navigation.selectedSquareChannel()

    internal fun ensureLocalSquareChannel(): String? = navigation.ensureLocalSquareChannel()

    internal fun returnToList(): Boolean = navigation.returnToList()

    internal fun gotoSquare(): Boolean = navigation.gotoSquare()

    internal fun gotoChatList(): Boolean = navigation.gotoChatList()

    internal fun gotoSoulPlanet(): Boolean = navigation.gotoSoulPlanet()

    internal fun gotoModeIdleSurface(mode: AutomationMode): Boolean = navigation.gotoModeIdleSurface(mode)

    internal fun backToSquare(): Boolean = navigation.backToSquare()

    internal fun thinkDelay(): Long {
        val min = Prefs.getThinkMin(applicationContext).coerceAtLeast(0)
        val max = Prefs.getThinkMax(applicationContext).coerceAtLeast(min + 1)
        return random.nextLong(min.toLong(), max.toLong()) * 1000L
    }

    internal fun waitUnlessQiyu(delayMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + delayMs
        while (running && System.currentTimeMillis() < deadline) {
            if (currentScreen() == SoulScreen.QIYU_POPUP) {
                handleQiyu()
                return false
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            sleep(minOf(250L, remaining))
        }
        return running
    }

    internal fun replyDelay(timeStr: String): Long {
        val minutesAgo = UnreadConversationPolicy.ageMinutes(timeStr)
        if (minutesAgo >= 1) return 0L   // 消息已发超过 1 分钟，立即回
        return thinkDelay()
    }

    // ===== 拆分回复 =====

    internal fun splitReply(text: String): List<String> {
        // Model formatting must not turn one thought into three incomplete bubbles.
        val singleBubble = text.trim().replace(Regex("""\s*\n+\s*"""), " ")
        return listOf(singleBubble)
    }

    // ===== 发送（仅聊天界面） =====

    internal fun outgoingOccurrenceCount(text: String): Int {
        val target = normalizeReply(text)
        if (target.isEmpty()) return 0
        return readThread()
            .asSequence()
            .filter { it.first == "out" }
            .map { normalizeReply(it.second) }
            .count { it == target }
    }

    /** Total visible outgoing count reconciles an uncertain click even when the retry text differs. */
    internal fun outgoingMessageCount(): Int = readThread().count { it.first == "out" }

    internal fun visibleMessageOccurrenceCount(text: String): Int {
        val target = normalizeReply(text)
        if (target.isEmpty()) return 0
        return findByIds(ID_CONTENT_TEXT)
            .asSequence()
            .filter(::isVisibleOnScreen)
            .map { normalizeReply(textOf(it)) }
            .count { it == target }
    }

    internal fun send(
        text: String,
        expectedContact: String? = null,
        inputTimeoutMs: Long? = null,
        onInputWritten: (String) -> Unit = {},
        onSendClick: () -> Unit = {},
        idempotencyKey: String? = null,
        sceneOverride: ReplyScene? = null,
        expectedEventAnchor: String? = null,
    ): Boolean = messageSender.send(
        text = text,
        expectedContact = expectedContact,
        inputTimeoutMs = inputTimeoutMs,
        onInputWritten = onInputWritten,
        onSendClick = onSendClick,
        idempotencyKey = idempotencyKey,
        sceneOverride = sceneOverride,
        expectedEventAnchor = expectedEventAnchor,
    )

    // ===== 读聊天消息（仅聊天界面） =====

    internal fun messageRole(item: AccessibilityNodeInfo, avatars: List<AccessibilityNodeInfo>, readReceipts: List<AccessibilityNodeInfo>): String = messageReader.messageRole(item, avatars, readReceipts)

    internal fun descendantsById(
        node: AccessibilityNodeInfo,
        id: String,
    ): List<AccessibilityNodeInfo> = runCatching {
        node.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)

    private fun transcriptForVoice(
        bubble: AccessibilityNodeInfo,
        item: AccessibilityNodeInfo? = null,
    ): String = voiceMessageController.transcriptForVoice(bubble, item)

    private fun voiceTranscriptFrom(item: AccessibilityNodeInfo): String =
        voiceMessageController.voiceTranscriptFrom(item)

    internal fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> =
        voiceMessageController.pendingIncomingVoiceBubbles()

    internal fun convertIncomingVoiceMessages(): Boolean =
        voiceMessageController.convertIncomingVoiceMessages()

    internal fun readThread(): List<Pair<String, String>> = messageReader.readThread()

    internal fun currentIncomingMessageKeys(contactName: String): List<String> = messageReader.currentIncomingMessageKeys(contactName)

    internal fun unseenIncomingEvents(
        contactName: String,
        thread: List<Pair<String, String>>,
        tracker: ConversationSeenTracker,
        pendingCount: Int,
    ): List<ConversationIncomingEvent> =
        conversationContextManager.unseenIncomingEvents(contactName, thread, tracker, pendingCount)

    private fun currentNativeSoulUserId(): String? = conversationContextManager.currentNativeSoulUserId()

    private fun nonTextMessageFrom(item: AccessibilityNodeInfo): String = messageReader.nonTextMessageFrom(item)

    private fun incomingSignature(thread: List<Pair<String, String>>): String =
        thread.filter { it.first == "in" }.joinToString("\u0001") { it.second }

    internal fun waitForStableThread(name: String): List<Pair<String, String>> {
        if (!isOnChat()) return emptyList()
        if (!convertIncomingVoiceMessages()) {
            // Never answer using older text while an unread voice message is still
            // unresolved. Keeping the unread marker lets the next list pass retry it.
            return emptyList()
        }

        var latest = emptyList<Pair<String, String>>()
        var lastSignature = ""
        var stableSince = System.currentTimeMillis()
        val loadDeadline = System.currentTimeMillis() + MESSAGE_LOAD_TIMEOUT_MS
        while (running && isOnChat()) {
            latest = readThread()
            val signature = incomingSignature(latest)
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = System.currentTimeMillis()
            }
            if (signature.isEmpty()) {
                val remaining = loadDeadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    setNextStep("未读取到 $name 的未读内容，返回列表重新检查")
                    return latest
                }
                setNextStep("等待 $name 的消息加载", remaining)
                sleep(minOf(500L, remaining))
                continue
            }

            val remaining = MESSAGE_SETTLE_MS - (System.currentTimeMillis() - stableSince)
            if (remaining <= 0L) return latest
            setNextStep("等待 $name 发完消息", remaining)
            sleep(minOf(500L, remaining))
        }
        return latest
    }

    // ===== 扫未读（仅会话列表） =====

    internal fun scanUnread(): List<UnreadConversationCandidate> = conversationListScanner.scanUnread()

    internal fun findConversation(name: String): AccessibilityNodeInfo? =
        conversationListScanner.findConversation(name)

    internal fun normalizeReply(text: String): String =
        text.trim().replace(Regex("""[\s，。！？!?、~～]+"""), "")

    internal fun generateValidatedChatReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
        scene: ReplyScene = ReplyScene.CHAT,
    ): String? = replyGenerationEngine.generateValidatedChatReply(contactName, thread, focusIncoming, scene)

    internal fun generateValidatedOpening(
        action: String,
        context: OpeningContext,
    ): String? = replyGenerationEngine.generateValidatedOpening(action, context)

    internal fun memoryThreadId(contactName: String): String =
        conversationContextManager.memoryThreadId(contactName)

    internal fun personalizationContext(
        contactName: String,
        latestMessage: String,
        scene: ReplyScene = ReplyScene.CHAT,
        includeMemory: Boolean = true,
        styleQuery: String = latestMessage,
        excludeContextKey: String = "",
    ): String = conversationContextManager.personalizationContext(
        contactName, latestMessage, scene, includeMemory, styleQuery, excludeContextKey,
    )

    internal fun learnFromManualReplies(
        contactName: String,
        thread: List<Pair<String, String>>,
        scene: ReplyScene = ReplyScene.CHAT,
    ) = conversationContextManager.learnFromManualReplies(contactName, thread, scene)

    internal fun recordAutomatedInteraction(
        contactName: String,
        incomingMessages: List<String>,
        reply: String,
        opening: OpeningContext? = null,
        scene: ReplyScene = opening?.scene ?: ReplyScene.CHAT,
    ) = conversationContextManager.recordAutomatedInteraction(
        contactName, incomingMessages, reply, opening, scene,
    )

    internal fun hasPendingQiyu(): Boolean = qiyuWorkflow.hasPending()

    internal fun clearPendingQiyu() = qiyuWorkflow.clearPending()

    internal fun handleQiyu(): Boolean = qiyuWorkflow.handle()

    internal fun resumePendingQiyu(): Boolean = qiyuWorkflow.resume()

    internal fun readQiyuSignature(): String {
        return SoulMatchContent.gravityTags(visibleTitleTexts())
    }

    // ===== 回复会话（仅会话列表起点） =====

    internal fun replyTo(name: String, seen: ConversationSeenTracker): Boolean =
        privateChatWorkflow.replyTo(name, seen)

    internal fun displayedConversationName(): String {
        val primary = findByIds(ID_PROFILE_NAME).filter(::isVisibleOnScreen).map(::textOf)
        // Both ids already occur in supported Soul versions. Restrict the generic
        // title fallback to the header; profile tags and message-card titles are not identities.
        val area = activeWindowRegion()
        val headerBottom = area.top + screenHeight() / 5
        val headerTitles = findByIds(ID_GENERIC_TITLE)
            .filter(::isVisibleOnScreen)
            .filter { rect(it).bottom <= headerBottom }
            .map(::textOf)
        return QiyuConversationPolicy.conversationName(primary, headerTitles)
    }

    internal fun hasConfirmedConversationName(name: String): Boolean {
        val displayed = displayedConversationName()
        return QiyuConversationPolicy.sameContact(name, displayed)
    }

    internal fun isExpectedConversation(name: String): Boolean {
        val displayed = displayedConversationName()
        if (displayed.isBlank()) return true
        return comparableText(displayed) == comparableText(name)
    }

    internal fun currentConversationHasChanged(name: String, thread: List<Pair<String, String>>): Boolean =
        running && isOnChat() && isExpectedConversation(name) &&
            (pendingIncomingVoiceBubbles().isNotEmpty() || incomingSignature(readThread()) != incomingSignature(thread))

    internal fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean =
        privateChatWorkflow.replyToCurrentConversation(name, seen)

    // ===== 收到的新招呼 =====

    internal fun hasGreeting(): Boolean = greetingWorkflow.hasGreeting()

    /** Hidden tab-bar nodes remain in Soul's accessibility tree; only a visible dot is unread. */
    internal fun hasUnreadRedDot(): Boolean = findVisibleById(ID_MSG_RED_DOT) != null

    internal fun openGreetingList(): Boolean = greetingWorkflow.openGreetingList()

    internal fun scanGreetingUsers(
        handledKeys: Set<String>,
        greetDb: GreetDatabase,
    ): List<String> = greetingWorkflow.scanGreetingUsers(handledKeys, greetDb)

    internal fun replyToGreeting(
        name: String,
        seen: ConversationSeenTracker,
        handledKeys: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean = greetingWorkflow.replyToGreeting(name, seen, handledKeys, greetDb)

    // ===== 广场 =====

    internal fun skipGift() {
        if (isOnGift()) {
            setNextStep("关闭礼物弹窗")
            findById(ID_GIFT_CLOSE)?.let { tapNode(it) }
            sleep(1500)
        }
    }

    internal fun skipNoSend() {
        if (isOnNoSend()) {
            setNextStep("点击“先不聊了”")
            findById(ID_NO_SEND_CLOSE)?.let { tapNode(it) }
            sleep(1200)
        }
    }

    internal fun browseSquareOnce(
        seenDm: MutableSet<String>,
        attemptedDmProfiles: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean = squareWorkflow.browseOnce(seenDm, attemptedDmProfiles, greetDb)

    // ===== 主循环 =====

    private fun runLoop() {
        var failure: Throwable? = null
        try {
            runLoopInternal()
        } catch (_: StoppedException) {
            RuntimeLog.record(applicationContext, "worker interrupted for stop")
        } catch (t: Throwable) {
            failure = t
            RuntimeLog.record(applicationContext, "worker crashed", t)
        } finally {
            runCatching { conversationMemoryDb?.close() }
            conversationMemoryDb = null
            runCatching { learningDb?.close() }
            learningDb = null
            runCatching { replyReplayDb?.close() }
            replyReplayDb = null
            runCatching { historyDb?.close() }
            historyDb = null
            // A destroyed service can overlap briefly with a newly connected one.
            // The old worker must never overwrite the new instance's global state.
            val ownsGlobalState = instance === this
            if (ownsGlobalState) {
                running = false
                statusDeadline = 0
            }
            if (worker === Thread.currentThread()) worker = null

            val shouldRestart = Prefs.getTaskShouldRun(applicationContext) && ownsGlobalState
            RuntimeLog.record(
                applicationContext,
                "worker finished; failure=${failure != null}; ownsState=$ownsGlobalState; restart=$shouldRestart",
            )
            if (shouldRestart) {
                val reason = failure?.message?.takeIf(String::isNotBlank)
                    ?.let { "任务异常：${it.take(36)}" }
                    ?: "任务线程意外退出"
                Prefs.setLastStopReason(applicationContext, reason)
                RuntimeLog.record(
                    applicationContext,
                    "worker ended unexpectedly; scheduling restart; hadFailure=${failure != null}",
                )
                setNextStep("$reason，自动恢复", 3000)
                restartHandler.postDelayed({
                    if (instance === this &&
                        Prefs.getTaskShouldRun(applicationContext) &&
                        !running
                    ) {
                        RuntimeLog.record(applicationContext, "worker automatic restart executing")
                        start()
                    } else {
                        RuntimeLog.record(applicationContext, "worker automatic restart cancelled")
                    }
                }, 3000)
            } else if (ownsGlobalState &&
                statusText != "未配置 API Key" &&
                statusText != "模型配置不完整" &&
                statusText != "用户手动停止"
            ) {
                statusText = Prefs.getLastStopReason(applicationContext)
            }
        }
    }

    private fun initializeSessionDatabases() {
        historyDb = HistoryDatabase(applicationContext)
        conversationMemoryDb = ConversationMemoryDatabase(applicationContext).also { memory ->
            memory.seedLegacyHistory(historyDb?.getAll().orEmpty())
        }
        learningDb = ChatLearningDatabase(applicationContext).also { db ->
            // Existing interaction history contains only replies sent by the bot.
            // Seed it once so older bot messages are not learned as human style.
            db.seedAutomatedReplies(historyDb?.getAll().orEmpty())
        }
        replyReplayDb = ReplyReplayDatabase(applicationContext)
    }

    private fun runLoopInternal() {
        SoulAutomationWorker(
            context = applicationContext,
            automationHost = automationHost,
            updateStatus = { text, deadline ->
                statusText = text
                statusDeadline = deadline
            },
            setNextStep = ::setNextStep,
            initializeSessionDatabases = ::initializeSessionDatabases,
        ).run()
    }
}
