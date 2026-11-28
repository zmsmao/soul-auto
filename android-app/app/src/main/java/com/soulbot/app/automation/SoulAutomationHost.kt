package com.soulbot.app.automation

import com.soulbot.app.accessibility.SoulScreen
import com.soulbot.app.conversation.ConversationSeenTracker
import com.soulbot.app.data.AutomationMode
import com.soulbot.app.data.GreetDatabase
import com.soulbot.app.conversation.UnreadConversationCandidate
import com.soulbot.app.conversation.ConversationListEntry
import com.soulbot.app.workflow.privatechat.PrivateChatTaskResult
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
    fun scanConversationPage(): List<ConversationListEntry>
    fun scrollConversationPageDown(): Boolean
    fun displayedConversationName(): String
    fun scanUnread(): List<UnreadConversationCandidate>
    fun replyDelay(timeStr: String): Long
    fun waitUnlessQiyu(delayMs: Long): Boolean
    fun replyTo(contactKey: String, name: String, seen: ConversationSeenTracker): PrivateChatTaskResult
    fun scrollConversationToTop()
    fun hasGreeting(): Boolean
    fun openGreetingList(): Boolean
    fun scanGreetingUsers(handledKeys: Set<String>, greetDb: GreetDatabase): List<String>
    fun replyToGreeting(name: String, seen: ConversationSeenTracker, handledKeys: MutableSet<String>, greetDb: GreetDatabase): Boolean
    fun pressBack()
    fun gotoSoulPlanet(): Boolean
}