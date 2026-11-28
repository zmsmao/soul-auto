package com.soulbot.app.accessibility

import com.soulbot.app.logging.RuntimeLog
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt
import java.util.ArrayDeque
import com.soulbot.app.policy.SquareBrowsePolicy

/** Low-level Soul accessibility tree and gesture operations. No chat/business policy lives here. */
internal class SoulAccessibilityUi(
    private val service: AccessibilityService,
    private val pause: (Long) -> Unit,
) {
    fun root(): AccessibilityNodeInfo? =
        runCatching { service.rootInActiveWindow }.getOrNull()
    fun isSoulForeground(): Boolean = runCatching {
        root()?.packageName?.toString() == SoulAccessibilityIds.SOUL_PACKAGE
    }.getOrDefault(false)
    fun screenWidth(): Int = service.resources.displayMetrics.widthPixels
    fun screenHeight(): Int = service.resources.displayMetrics.heightPixels
    fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
    fun activeWindowRegion(): UiRegion {
        val rootBounds = root()?.let(::rect)?.toUiRegion()
        return AdaptiveUiPolicy.effectiveWindow(screenWidth(), screenHeight(), rootBounds)
    }
    fun visibleContainerRegion(id: String): UiRegion? =
        findByIds(id)
            .asSequence()
            .filter(::isVisibleOnScreen)
            .map { rect(it).toUiRegion() }
            .filter(UiRegion::isValid)
            .maxByOrNull { it.width.toLong() * it.height }
            ?.intersect(activeWindowRegion())
    fun swipeVertically(
        containerId: String,
        startRatio: Float,
        endRatio: Float,
        duration: Long = 300,
    ) {
        val area = visibleContainerRegion(containerId) ?: activeWindowRegion()
        val line = AdaptiveUiPolicy.verticalGesture(
            area = area,
            startRatio = startRatio,
            endRatio = endRatio,
            edgeInset = dp(8),
        )
        swipe(
            line.start.x.toFloat(),
            line.start.y.toFloat(),
            line.end.x.toFloat(),
            line.end.y.toFloat(),
            duration,
        )
    }
    fun findById(id: String): AccessibilityNodeInfo? = runCatching {
        root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    }.getOrNull()
    fun findByIds(id: String): List<AccessibilityNodeInfo> = runCatching {
        root()?.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
    }.getOrDefault(emptyList())
    fun findExactText(text: String): List<AccessibilityNodeInfo> = runCatching {
        root()?.findAccessibilityNodeInfosByText(text)
            ?.filter { textOf(it) == text && isVisibleOnScreen(it) }
            ?: emptyList()
    }.getOrDefault(emptyList())
    fun findVisibleById(id: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        return runCatching {
            r.findAccessibilityNodeInfosByViewId(id).firstOrNull(::isVisibleOnScreen)
        }.getOrNull()
    }
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return false
        val bounds = rect(node).toUiRegion()
        return bounds.isValid && bounds.intersect(activeWindowRegion()) != null
    }
    fun rect(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        return r
    }
    fun textOf(node: AccessibilityNodeInfo?): String =
        runCatching { node?.text?.toString()?.trim() ?: "" }.getOrDefault("")

    fun contentDescriptionOf(node: AccessibilityNodeInfo?): String =
        runCatching { node?.contentDescription?.toString()?.trim() ?: "" }.getOrDefault("")

    /** Bounded breadth-first traversal for one card; avoids walking the whole Soul window. */
    fun descendants(node: AccessibilityNodeInfo, maxDepth: Int = 9, maxNodes: Int = 180): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.addLast(node to 0)
        while (pending.isNotEmpty() && result.size < maxNodes) {
            val (current, depth) = pending.removeFirst()
            result += current
            if (depth >= maxDepth) continue
            val childCount = runCatching { current.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                if (result.size + pending.size >= maxNodes) break
                val child = runCatching { current.getChild(index) }.getOrNull() ?: continue
                pending.addLast(child to depth + 1)
            }
        }
        return result
    }

    fun visibleLabels(): List<String> {
        val window = root() ?: return emptyList()
        return descendants(window, maxDepth = 12, maxNodes = 320)
            .asSequence()
            .filter(::isVisibleOnScreen)
            .flatMap { sequenceOf(textOf(it), contentDescriptionOf(it)) }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    /**
     * A square avatar may bubble a generic node click to its whole card. Only click
     * the avatar or its nearest clickable ancestor when that target is tightly bounded.
     * There is intentionally no coordinate fallback for this high-risk action.
     */
    fun tapSquareAvatarSafely(card: AccessibilityNodeInfo, avatar: AccessibilityNodeInfo): Boolean {
        if (!isVisibleOnScreen(card) || !isVisibleOnScreen(avatar)) return false
        val cardBounds = rect(card)
        val avatarBounds = rect(avatar)
        var current: AccessibilityNodeInfo? = avatar
        repeat(5) {
            val candidate = current ?: return false
            val clickable = runCatching { candidate.isClickable }.getOrDefault(false)
            if (clickable) {
                val targetBounds = rect(candidate)
                val insideCard = cardBounds.contains(targetBounds)
                val overlapsAvatar = Rect.intersects(targetBounds, avatarBounds)
                val tightTarget = SquareBrowsePolicy.isSafeAvatarClickTarget(
                    avatarBounds.width(),
                    avatarBounds.height(),
                    targetBounds.width(),
                    targetBounds.height(),
                )
                if (!insideCard || !overlapsAvatar || !tightTarget) return false
                return runCatching {
                    candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            }
            current = runCatching { candidate.parent }.getOrNull()
        }
        return false
    }
    fun tap(x: Float, y: Float): Boolean {
        val point = AdaptiveUiPolicy.clampPoint(
            UiPoint(x.roundToInt(), y.roundToInt()),
            activeWindowRegion(),
            dp(2),
        )
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // Prefer semantic accessibility clicks. Some Soul child views are not clickable,
        // so walk up a few parents before falling back to a coordinate gesture.
        var clickable: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = clickable ?: return@repeat
            val clicked = runCatching {
                candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                return true
            }
            clickable = runCatching { candidate.parent }.getOrNull()
        }
        val r = rect(node)
        if (r.width() <= 0 || r.height() <= 0) return false
        return tap(r.exactCenterX().toFloat(), r.exactCenterY().toFloat())
    }
    fun longPressNode(node: AccessibilityNodeInfo): Boolean {
        val clicked = runCatching {
            node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)
        if (clicked) {
            return true
        }
        val bounds = rect(node)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply {
            moveTo(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1200))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val area = activeWindowRegion()
        val start = AdaptiveUiPolicy.clampPoint(UiPoint(x1.roundToInt(), y1.roundToInt()), area, dp(2))
        val end = AdaptiveUiPolicy.clampPoint(UiPoint(x2.roundToInt(), y2.roundToInt()), area, dp(2))
        val path = Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        service.dispatchGesture(gesture, null, null)
    }
    fun pressBack() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
    fun waitForInputText(expected: String, timeoutMs: Long = 1000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (textOf(findById(SoulAccessibilityIds.ID_INPUT_BOX)) == expected) return true
            pause(100)
        }
        return textOf(findById(SoulAccessibilityIds.ID_INPUT_BOX)) == expected
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        if (waitForInputText(text)) {
            return true
        }

        // ACTION_SET_TEXT is unreliable in some Soul releases. Use the public
        // accessibility paste action as a fallback and restore the user's clipboard.
        val input = findById(SoulAccessibilityIds.ID_INPUT_BOX) ?: return false
        tapNode(input)
        runCatching { input.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val currentText = textOf(input)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
        }
        runCatching { input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }

        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = runCatching { clipboard.primaryClip }.getOrNull()
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("SoulBot reply", text))
            pause(100)
            runCatching {
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false) && waitForInputText(text)
        } catch (e: Exception) {
            RuntimeLog.record(service.applicationContext, "message text injection failed", e)
            false
        } finally {
            try {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {}
        }
    }


    fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    // ===== 界面识别 =====

    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)
}