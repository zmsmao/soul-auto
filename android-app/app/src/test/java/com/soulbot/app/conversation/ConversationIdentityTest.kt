package com.soulbot.app.conversation
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdentityTest {
    @Test fun nativeMessageIdProducesStableKeyAcrossChangedNeighborContext() {
        val first = ConversationIdentity.messageKey("soul-user:123456", "m-7788", "in", "你好", "", 0)
        val second = ConversationIdentity.messageKey("soul-user:123456", "m-7788", "in", "你好", "上一条消息", 4)
        assertEquals(first, second)
    }

    @Test fun fallbackKeysDistinguishRepeatedMessagesAndDifferentContacts() {
        val first = ConversationIdentity.messageKey("fallback:abc", null, "in", "嗯", "你好", 0)
        val repeated = ConversationIdentity.messageKey("fallback:abc", null, "in", "嗯", "你好", 1)
        val otherContact = ConversationIdentity.messageKey("fallback:def", null, "in", "嗯", "你好", 0)
        assertNotEquals(first, repeated)
        assertNotEquals(first, otherContact)
    }

    @Test fun sendKeyIsStableForTheSameEventAndChangesForANewMessageId() {
        val first = ConversationIdentity.sendKey("soul-user:1", "CHAT", listOf("message-a"), "回复")
        val retry = ConversationIdentity.sendKey("soul-user:1", "CHAT", listOf("message-a"), "回复")
        val next = ConversationIdentity.sendKey("soul-user:1", "CHAT", listOf("message-b"), "回复")
        assertEquals(first, retry)
        assertNotEquals(first, next)
    }

    @Test fun replySendKeyIsNotCreatedWithoutAStableOrFallbackEventKey() {
        assertNull(ConversationIdentity.replySendKey("thread", "CHAT", emptyList(), "嗯"))
    }

    @Test fun nativeUserIdIsHashedBeforeBecomingAConversationScope() {
        val rawId = "soul-user-private-918273"
        val first = ConversationIdentity.threadId(rawId, "fallback")
        val second = ConversationIdentity.threadId(rawId, "other-fallback")
        assertEquals(first, second)
        assertTrue(first.startsWith("soul-user:"))
        org.junit.Assert.assertFalse(first.contains(rawId))
    }
}
