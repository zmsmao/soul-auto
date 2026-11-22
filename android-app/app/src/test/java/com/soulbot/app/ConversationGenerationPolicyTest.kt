package com.soulbot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationGenerationPolicyTest {
    @Test fun unchangedConversationAndMessageKeysKeepGenerationAlive() {
        assertFalse(ConversationGenerationPolicy.shouldCancel("m1,m2", "m1,m2", false, true))
    }

    @Test fun newlyArrivedMessageCancelsTheOldGeneration() {
        assertTrue(ConversationGenerationPolicy.shouldCancel("m1", "m1,m2", false, true))
    }

    @Test fun unresolvedVoiceMessageCancelsGeneration() {
        assertTrue(ConversationGenerationPolicy.shouldCancel("m1", "m1", true, true))
    }

    @Test fun conversationSwitchCancelsGenerationEvenWhenKeysMatch() {
        assertTrue(ConversationGenerationPolicy.shouldCancel("m1", "m1", false, false))
    }
}
