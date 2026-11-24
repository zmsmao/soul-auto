package com.soulbot.app.policy
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
