package com.soulbot.app.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.*
import com.soulbot.app.app.SoulBotService
import com.soulbot.app.diagnostics.*
import com.soulbot.app.policy.*
import kotlin.math.abs

internal interface SoulVoiceMessageHost {
    val context: Context
    val isRunning: Boolean
    fun isOnChat(): Boolean
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun findExactText(text: String): List<AccessibilityNodeInfo>
    fun descendantsById(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo>
    fun rect(node: AccessibilityNodeInfo): Rect
    fun contains(outer: Rect, inner: Rect): Boolean
    fun textOf(node: AccessibilityNodeInfo?): String
    fun messageRole(
        item: AccessibilityNodeInfo,
        avatars: List<AccessibilityNodeInfo>,
        readReceipts: List<AccessibilityNodeInfo>,
    ): String
    fun activeWindowRegion(): UiRegion
    fun dp(value: Int): Int
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun sleep(ms: Long)
    fun longPressNode(node: AccessibilityNodeInfo): Boolean
    fun tap(x: Float, y: Float): Boolean
}

internal class SoulVoiceMessageController(private val host: SoulVoiceMessageHost) {
    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)

    fun transcriptForVoice(
        bubble: AccessibilityNodeInfo,
        item: AccessibilityNodeInfo? = null,
    ): String {
        // Prefer the actual message subtree. Some Soul releases render the
        // transcript as a sibling below the item, so fall back to geometric
        // ownership among all currently visible voice bubbles.
        item?.let { messageItem ->
            host.descendantsById(messageItem, SoulBotService.ID_AUDIO_CONTENT)
                .asSequence()
                .filter(host::isVisibleOnScreen)
                .map(host::textOf)
                .firstOrNull(String::isNotBlank)
                ?.let { return it }
        }

        val voiceBounds = host.rect(bubble)
        val voices = host.findByIds(SoulBotService.ID_VOICE_BUBBLE)
            .filter(host::isVisibleOnScreen)
            .map { host.rect(it).toUiRegion() }
        val transcripts = host.findByIds(SoulBotService.ID_AUDIO_CONTENT)
            .filter(host::isVisibleOnScreen)
            .map { TranscriptCandidate(host.textOf(it), host.rect(it).toUiRegion()) }
        return VoiceTranscriptPolicy.transcriptFor(
            targetVoice = voiceBounds.toUiRegion(),
            visibleVoices = voices,
            transcripts = transcripts,
            maxHorizontalDistance = host.activeWindowRegion().width / 2,
            maxVerticalDistance = maxOf(host.dp(180), voiceBounds.height() * 4),
        )
    }

    fun voiceTranscriptFrom(item: AccessibilityNodeInfo): String {
        val bubble = host.descendantsById(item, SoulBotService.ID_VOICE_BUBBLE).firstOrNull()
            ?: host.findByIds(SoulBotService.ID_VOICE_BUBBLE).firstOrNull { host.contains(host.rect(item), host.rect(it)) }
            ?: return ""
        return transcriptForVoice(bubble, item)
    }

    fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> {
        if (!host.isOnChat()) return emptyList()
        val items = host.findByIds(SoulBotService.ID_CHAT_ITEM)
        val avatars = host.findByIds(SoulBotService.ID_CHAT_AVATAR)
        val messageDots = host.findByIds(SoulBotService.ID_MESSAGE_READ)
        return host.findByIds(SoulBotService.ID_VOICE_BUBBLE).filter { bubble ->
            val bubbleBounds = host.rect(bubble)
            val item = items.firstOrNull { host.contains(host.rect(it), bubbleBounds) }
            val hasUnreadDot = item != null && messageDots.any { dot ->
                val dotBounds = host.rect(dot)
                host.contains(host.rect(item), dotBounds) &&
                    dotBounds.centerX() > bubbleBounds.right &&
                    dotBounds.centerY() > bubbleBounds.centerY()
            }
            item != null &&
                host.messageRole(item, avatars, messageDots) == "in" &&
                hasUnreadDot &&
                transcriptForVoice(bubble, item).isBlank()
        }
    }

    fun voiceActionNear(voiceBounds: Rect): AccessibilityNodeInfo? =
        host.findExactText("转文字")
            .filter { action ->
                val bounds = host.rect(action)
                kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) < maxOf(host.dp(180), voiceBounds.height() * 3) &&
                    kotlin.math.abs(bounds.centerX() - voiceBounds.centerX()) < host.activeWindowRegion().width / 2
            }
            .minByOrNull { action ->
                val bounds = host.rect(action)
                kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) * 4 +
                    kotlin.math.abs(bounds.centerX() - voiceBounds.centerX())
            }

    fun convertIncomingVoiceMessages(): Boolean {
        val initialPending = pendingIncomingVoiceBubbles().size
        if (initialPending > 0) {
            RuntimeLog.record(host.context, "voice transcription batch started; pending=$initialPending")
        }
        repeat(8) { index ->
            val bubble = pendingIncomingVoiceBubbles().lastOrNull() ?: return true
            if (!host.isRunning || !host.isOnChat()) return false
            val voiceBounds = host.rect(bubble)

            val directButton = voiceActionNear(voiceBounds)
            if (directButton != null) {
                RuntimeLog.record(host.context, "voice transcription action; path=direct; index=${index + 1}")
                host.setNextStep("点击未读语音旁的转文字")
                host.tapNode(directButton)
                host.sleep(700)
            } else {
                host.setNextStep("长按第 ${index + 1} 条未读语音")
                if (!host.longPressNode(bubble)) return false
                host.sleep(700)

                // Prefer the semantic menu item when this Soul version exposes it.
                // Keep a coordinate fallback only for the self-drawn menu variant.
                val menuButton = voiceActionNear(voiceBounds)
                if (menuButton != null) {
                    RuntimeLog.record(host.context, "voice transcription action; path=semantic_menu; index=${index + 1}")
                    DeviceCompatibilityStore.recordVoiceMenuOffset(
                        host.context,
                        voiceBounds.toUiRegion(),
                        host.rect(menuButton).toUiRegion(),
                        host.activeWindowRegion(),
                    )
                    host.setNextStep("点击语音菜单中的转文字")
                    host.tapNode(menuButton)
                } else {
                    val activeArea = host.activeWindowRegion()
                    val menuPoint = DeviceCompatibilityStore.learnedVoiceMenuPoint(
                        host.context,
                        voiceBounds.toUiRegion(),
                        activeArea,
                        edgeInset = host.dp(8),
                    ) ?: AdaptiveUiPolicy.voiceMenuFallback(
                        voiceBounds.toUiRegion(),
                        activeArea,
                        offsetPx = host.dp(56),
                        edgeInset = host.dp(8),
                    )
                    RuntimeLog.record(
                        host.context,
                        "voice transcription action; path=coordinate_fallback; learned=${DeviceCompatibilityStore.hasLearnedVoiceMenuOffset(host.context)}; index=${index + 1}",
                    )
                    host.setNextStep("点击语音菜单中的转文字")
                    host.tap(menuPoint.x.toFloat(), menuPoint.y.toFloat())
                }
                host.sleep(700)
            }

            val startedAt = System.currentTimeMillis()
            var lastTranscript = ""
            var stableSince = System.currentTimeMillis()
            while (host.isRunning && host.isOnChat() && System.currentTimeMillis() - startedAt < 30_000L) {
                val currentBubble = host.findByIds(SoulBotService.ID_VOICE_BUBBLE)
                    .filter(host::isVisibleOnScreen)
                    .minByOrNull { candidate ->
                        val bounds = host.rect(candidate)
                        kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) * 4 +
                            kotlin.math.abs(bounds.centerX() - voiceBounds.centerX())
                    }
                    ?: bubble
                val transcript = transcriptForVoice(currentBubble)
                if (transcript != lastTranscript) {
                    lastTranscript = transcript
                    stableSince = System.currentTimeMillis()
                }
                val stableFor = System.currentTimeMillis() - stableSince
                val elapsed = System.currentTimeMillis() - startedAt
                if (lastTranscript.isNotBlank() &&
                    elapsed >= SoulBotService.VOICE_TRANSCRIPT_START_GRACE_MS &&
                    stableFor >= SoulBotService.VOICE_TRANSCRIPT_STABLE_MS
                ) {
                    break
                }
                val remaining = (SoulBotService.VOICE_TRANSCRIPT_STABLE_MS - stableFor).coerceAtLeast(500L)
                host.setNextStep("等待语音转文字完成", remaining)
                host.sleep(500)
            }
            if (lastTranscript.isBlank()) {
                RuntimeLog.record(
                    host.context,
                    "voice transcription failed; reason=no_text; index=${index + 1}; durationMs=${System.currentTimeMillis() - startedAt}",
                )
                host.setNextStep("语音转文字失败，稍后重试", 5000)
                host.sleep(5000)
                return false
            }
            RuntimeLog.record(
                host.context,
                "voice transcription completed; index=${index + 1}; chars=${lastTranscript.length}; durationMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        val complete = pendingIncomingVoiceBubbles().isEmpty()
        if (initialPending > 0) {
            RuntimeLog.record(host.context, "voice transcription batch ended; complete=$complete")
        }
        return complete
    }
}
