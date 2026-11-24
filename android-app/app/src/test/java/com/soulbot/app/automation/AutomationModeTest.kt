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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationModeTest {
    @Test
    fun planetChatModeDoesNotRunSquareTasks() {
        val mode = AutomationMode.PLANET_CHAT

        assertTrue(mode.handlesChatReplies)
        assertTrue(mode.handlesSoulMatch)
        assertFalse(mode.handlesSquareDm)
        assertTrue(mode.idleSurface == IdleSurface.SOUL_PLANET)
    }

    @Test
    fun squareDmModeStillRepliesToChatsButDoesNotMatch() {
        val mode = AutomationMode.SQUARE_DM

        assertTrue(mode.handlesSquareDm)
        assertTrue(mode.handlesChatReplies)
        assertFalse(mode.handlesSoulMatch)
        assertTrue(mode.idleSurface == IdleSurface.LOCAL_SQUARE)
    }
}
