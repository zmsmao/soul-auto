package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
