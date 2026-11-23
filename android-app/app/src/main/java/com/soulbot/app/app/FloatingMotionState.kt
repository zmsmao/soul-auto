package com.soulbot.app.app
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

/** Latest-only motion and a generation fence for delayed window-layout callbacks. */
internal class FloatingMotionState {
    var dragging: Boolean = false
        private set
    private var layoutGeneration = 0L
    private var pendingPosition: UiPoint? = null
    private var deferredReposition = false

    fun beginGesture(layoutPending: Boolean = false) {
        layoutGeneration++
        pendingPosition = null
        deferredReposition = deferredReposition || layoutPending
        dragging = true
    }

    fun queuePosition(position: UiPoint) {
        if (dragging) pendingPosition = position
    }

    fun takePosition(): UiPoint? = pendingPosition.also { pendingPosition = null }

    fun endGesture() {
        dragging = false
        pendingPosition = null
    }

    fun deferReposition() { deferredReposition = true }

    fun takeDeferredReposition(): Boolean = deferredReposition.also { deferredReposition = false }

    fun invalidateLayout(): Long = ++layoutGeneration

    fun canApplyLayout(generation: Long): Boolean = !dragging && generation == layoutGeneration
}
