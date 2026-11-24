package com.soulbot.app.app

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.soulbot.app.accessibility.UiPoint
import com.soulbot.app.accessibility.UiRegion
import kotlin.math.abs

internal enum class FloatingDockSide { LEFT, RIGHT }

internal interface FloatingOverlayMotionHost {
    val windowManager: WindowManager
    val handler: Handler
    val floatView: LinearLayout?
    val windowParams: WindowManager.LayoutParams?
    val expandedView: LinearLayout?
    val collapsedView: LinearLayout?
    fun dp(value: Int): Int
    fun safeOverlayRegion(): UiRegion
    fun recordUiEvent(event: String, error: Throwable? = null)
}

internal class FloatingOverlayMotionController(private val host: FloatingOverlayMotionHost) {
    private var isCollapsed = false
    private var isDockedToEdge = true
    private val motionState = FloatingMotionState()
    val dragInProgress: Boolean get() = motionState.dragging
    private var dragLayoutErrorReported = false
    private var dockSide = FloatingDockSide.RIGHT
    private var repositionListener: View.OnLayoutChangeListener? = null
    private var repositionFallback: Runnable? = null
    private var dragFramePending = false
    private val dragFrame = Runnable {
        dragFramePending = false
        if (dragInProgress) applyPendingDragPosition()
    }
    private val autoCollapseRunnable = Runnable {
        if (!dragInProgress && !isCollapsed && isDockedToEdge) setCollapsed(true)
    }

    fun attachDragAndClick(view: View, onClick: (() -> Unit)? = null) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var gestureRegion: UiRegion? = null
        var gestureWidth = 0
        var gestureHeight = 0
        var gestureStartedAt = 0L
        var maxEventDelayMs = 0L
        val movementThreshold = host.dp(6)

        if (onClick != null) {
            view.setOnClickListener {
                onClick()
                if (!isCollapsed) scheduleAutoCollapse()
            }
        } else {
            view.setOnClickListener(null)
        }
        view.setOnTouchListener { touchedView, event ->
            val root = host.floatView ?: return@setOnTouchListener false
            val lp = host.windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoCollapse()
                    val layoutPending = repositionListener != null || repositionFallback != null || root.isLayoutRequested
                    cancelReposition()
                    root.removeCallbacks(dragFrame)
                    dragFramePending = false
                    // A tap/drag must always win over a pending collapse animation.
                    // Otherwise the old animation end callback can hide the panel
                    // again after the user has already touched it.
                    root.animate().cancel()
                    root.alpha = 1f
                    motionState.beginGesture(layoutPending)
                    dragLayoutErrorReported = false
                    gestureRegion = host.safeOverlayRegion()
                    gestureWidth = root.width
                    gestureHeight = root.height
                    gestureStartedAt = SystemClock.uptimeMillis()
                    maxEventDelayMs = (gestureStartedAt - event.eventTime).coerceAtLeast(0)
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragInProgress) return@setOnTouchListener true
                    maxEventDelayMs = maxOf(maxEventDelayMs, SystemClock.uptimeMillis() - event.eventTime)
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) >= movementThreshold || abs(dy) >= movementThreshold)) {
                        moved = true
                    }
                    if (moved) {
                        val safeRegion = gestureRegion ?: return@setOnTouchListener true
                        val x = (initialX + dx.toInt()).coerceIn(
                            safeRegion.left - gestureWidth.coerceAtLeast(host.dp(48)) + host.dp(48),
                            safeRegion.right - host.dp(48),
                        )
                        val y = (initialY + dy.toInt()).coerceIn(
                            safeRegion.top,
                            (safeRegion.bottom - gestureHeight).coerceAtLeast(safeRegion.top),
                        )
                        motionState.queuePosition(UiPoint(x, y))
                        if (!dragFramePending) {
                            dragFramePending = true
                            root.postOnAnimation(dragFrame)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    root.removeCallbacks(dragFrame)
                    dragFramePending = false
                    applyPendingDragPosition()
                    motionState.endGesture()
                    if (moved) {
                        finishDrag()
                    } else if (onClick != null) {
                        touchedView.performClick()
                    } else {
                        scheduleAutoCollapse()
                    }
                    if (motionState.takeDeferredReposition()) repositionAfterLayout()
                    if (moved || maxEventDelayMs >= 100) {
                        host.recordUiEvent("floating gesture completed; moved=$moved; " +
                            "durationMs=${SystemClock.uptimeMillis() - gestureStartedAt}; maxInputDelayMs=$maxEventDelayMs")
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    root.removeCallbacks(dragFrame)
                    dragFramePending = false
                    applyPendingDragPosition()
                    motionState.endGesture()
                    if (moved) {
                        finishDrag()
                    }
                    cancelAutoCollapse()
                    if (motionState.takeDeferredReposition()) repositionAfterLayout()
                    // A system gesture or a temporary window-layout change may cancel
                    // touch delivery. Keep the panel expanded so the user's drag does
                    // not look like it got stuck and disappeared.
                    true
                }

                else -> false
            }
        }
    }

    private fun finishDrag() {
        val root = host.floatView ?: return
        val lp = host.windowParams ?: return
        if (root.width == 0) return

        val safeRegion = host.safeOverlayRegion()
        val edgeSlop = host.dp(8)
        val leftGap = lp.x - safeRegion.left
        val rightGap = safeRegion.right - (lp.x + root.width)

        when {
            leftGap <= edgeSlop -> {
                dockSide = FloatingDockSide.LEFT
                isDockedToEdge = true
                snapToEdge()
                scheduleAutoCollapse()
            }

            rightGap <= edgeSlop -> {
                dockSide = FloatingDockSide.RIGHT
                isDockedToEdge = true
                snapToEdge()
                scheduleAutoCollapse()
            }

            else -> {
                isDockedToEdge = false
                cancelAutoCollapse()
                if (isCollapsed) {
                    // A collapsed handle dragged away from the edge becomes a
                    // normal panel at the exact place where the user left it.
                    setCollapsed(false)
                } else {
                    clampFreePosition()
                }
            }
        }
    }

    private fun clampFreePosition() {
        val root = host.floatView ?: return
        val lp = host.windowParams ?: return
        if (root.width == 0) return
        val safeRegion = host.safeOverlayRegion()
        val horizontalPadding = host.dp(8)
        lp.x = lp.x.coerceIn(
            safeRegion.left + horizontalPadding,
            (safeRegion.right - root.width - horizontalPadding)
                .coerceAtLeast(safeRegion.left + horizontalPadding),
        )
        lp.y = lp.y.coerceIn(
            safeRegion.top,
            (safeRegion.bottom - root.height).coerceAtLeast(safeRegion.top),
        )
        try {
            host.windowManager.updateViewLayout(root, lp)
        } catch (error: Exception) {
            host.recordUiEvent("floating free-position clamp failed", error)
        }
    }

    private fun snapToEdge() {
        val root = host.floatView ?: return
        val lp = host.windowParams ?: return
        if (root.width == 0) return
        val safeRegion = host.safeOverlayRegion()
        lp.x = when {
            isCollapsed && dockSide == FloatingDockSide.LEFT -> safeRegion.left - host.dp(8)
            isCollapsed -> safeRegion.right - root.width + host.dp(8)
            dockSide == FloatingDockSide.LEFT -> safeRegion.left + host.dp(8)
            else -> safeRegion.right - root.width - host.dp(8)
        }
        lp.y = lp.y.coerceIn(
            safeRegion.top,
            (safeRegion.bottom - root.height).coerceAtLeast(safeRegion.top),
        )
        try {
            host.windowManager.updateViewLayout(root, lp)
        } catch (error: Exception) {
            host.recordUiEvent("floating edge snap failed", error)
        }
    }

    private fun scheduleAutoCollapse() {
        host.handler.removeCallbacks(autoCollapseRunnable)
        if (!dragInProgress && !isCollapsed && isDockedToEdge) {
            host.handler.postDelayed(autoCollapseRunnable, 5000)
        }
    }

    private fun cancelAutoCollapse() {
        host.handler.removeCallbacks(autoCollapseRunnable)
    }

    fun setCollapsed(collapsed: Boolean) {
        if (isCollapsed == collapsed) return
        val root = host.floatView ?: return
        cancelAutoCollapse()
        host.recordUiEvent(
            "floating panel state changed; collapsed=$collapsed; docked=$isDockedToEdge; side=${dockSide.name}",
        )
        // Do not animate the layout transition. Window overlays can receive a
        // touch while an animation is running; the old end callback could then
        // apply stale coordinates and make the collapsed handle untappable.
        root.animate().cancel()
        root.alpha = 1f
        isCollapsed = collapsed
        host.expandedView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        host.collapsedView?.visibility = if (collapsed) View.VISIBLE else View.GONE
        repositionAfterLayout()
        if (!collapsed) scheduleAutoCollapse()
    }

    /** Recalculate the overlay position only after WRAP_CONTENT has been remeasured. */
    private fun repositionAfterLayout() {
        val root = host.floatView ?: return
        cancelReposition()
        val generation = motionState.invalidateLayout()
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                applyReposition(view, generation, measured = true)
            }
        }
        repositionListener = listener
        root.addOnLayoutChangeListener(listener)
        // The listener and fallback belong to one request; either path removes both.
        val fallback = Runnable { applyReposition(root, generation) }
        repositionFallback = fallback
        root.requestLayout()
        root.postDelayed(fallback, 48)
    }

    private fun applyReposition(view: View, generation: Long, measured: Boolean = false) {
        if (host.floatView !== view || !motionState.canApplyLayout(generation)) return
        // A fallback must not use the old collapsed width while a measure is still pending.
        // Keep the layout listener alive; the actual layout pass owns the final position.
        if (!measured && view.isLayoutRequested) return
        cancelReposition()
        if (view.width <= 0 || view.height <= 0) return
        if (isDockedToEdge) snapToEdge() else clampFreePosition()
    }

    private fun cancelReposition() {
        motionState.invalidateLayout()
        host.floatView?.let { root ->
            repositionListener?.let(root::removeOnLayoutChangeListener)
            repositionFallback?.let(root::removeCallbacks)
        }
        repositionListener = null
        repositionFallback = null
    }

    private fun applyPendingDragPosition() {
        val point = motionState.takePosition() ?: return
        val root = host.floatView ?: return
        val lp = host.windowParams ?: return
        if (point.x == lp.x && point.y == lp.y) return
        lp.x = point.x
        lp.y = point.y
        try {
            host.windowManager.updateViewLayout(root, lp)
        } catch (error: RuntimeException) {
            if (!dragLayoutErrorReported) {
                dragLayoutErrorReported = true
                host.recordUiEvent("floating window drag layout update failed", error)
            }
        }
    }

    fun onInitialLayoutReady() {
        val root = host.floatView ?: return
        val lp = host.windowParams ?: return
        root.post {
            if (host.floatView !== root || dragInProgress) return@post
            val currentSafeRegion = host.safeOverlayRegion()
            lp.y = (currentSafeRegion.centerY - root.height / 2)
                .coerceAtLeast(currentSafeRegion.top)
            dockSide = FloatingDockSide.RIGHT
            isDockedToEdge = true
            snapToEdge()
            scheduleAutoCollapse()
        }
    }

    fun onConfigurationChanged() {
        cancelReposition()
        if (dragInProgress) motionState.deferReposition()
        host.floatView?.post {
            if (!dragInProgress) {
                repositionAfterLayout()
            }
        }
    }

    fun onDestroy() {
        cancelAutoCollapse()
        cancelReposition()
        host.floatView?.removeCallbacks(dragFrame)
        dragFramePending = false
        motionState.endGesture()
    }

}
