package com.soulbot.app.automation
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

/** Operations the scheduler may request from the Android page agent. */
internal interface SoulAutomationHost {
    val isRunning: Boolean
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun sleep(ms: Long)
    fun currentScreen(): SoulScreen
    fun hasUnreadRedDot(): Boolean
    fun findVisibleById(id: String): AccessibilityNodeInfo?
    fun hasPendingQiyu(): Boolean
    fun clearPendingQiyu()
    fun handleQiyu(): Boolean
    fun resumePendingQiyu(): Boolean
    fun gotoChatList(): Boolean
    fun skipNoSend()
    fun skipGift()
    fun gotoModeIdleSurface(mode: AutomationMode): Boolean
    fun handleSoulMatchChat(handledMatches: MutableSet<String>, greetDb: GreetDatabase, seen: ConversationSeenTracker): Boolean
    fun handlePlanetChatIdle(handledMatches: MutableSet<String>, greetDb: GreetDatabase, seen: ConversationSeenTracker)
    fun isOnSquare(): Boolean
    fun gotoSquare(): Boolean
    fun browseSquareOnce(seenDm: MutableSet<String>, attemptedDmProfiles: MutableSet<String>, greetDb: GreetDatabase): Boolean
    fun scanUnread(): List<UnreadConversationCandidate>
    fun replyDelay(timeStr: String): Long
    fun waitUnlessQiyu(delayMs: Long): Boolean
    fun replyTo(name: String, seen: ConversationSeenTracker): Boolean
    fun scrollConversationToTop()
    fun hasGreeting(): Boolean
    fun openGreetingList(): Boolean
    fun scanGreetingUsers(handledKeys: Set<String>, greetDb: GreetDatabase): List<String>
    fun replyToGreeting(name: String, seen: ConversationSeenTracker, handledKeys: MutableSet<String>, greetDb: GreetDatabase): Boolean
    fun pressBack()
    fun gotoSoulPlanet(): Boolean
}