package com.soulbot.app.conversation

import com.soulbot.app.model.ModelClient
import com.soulbot.app.policy.QingshengSoulPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptBaselineTest {
    @Test
    fun freshAccountPersonaDoesNotInventDefaultPersonalFacts() {
        assertTrue(SelfProfile().promptContext().isEmpty())
        val prompt = ModelClient.buildSystemPrompt(SelfProfile(), historicalReplay = true)
        assertFalse(prompt.contains("性别：男"))
        assertTrue(prompt.contains("只使用本人已填写资料"))
    }

    @Test
    fun modularPersonaAssemblyReconstructsTheExactOriginalPrompt() {
        val profile = SelfProfile(
            age = "26",
            region = "广州",
            occupation = "软件研发",
        )
        val historical = ModelClient.buildSystemPrompt(profile, historicalReplay = true)
        val assembled = ModelClient.buildSystemPromptPrefix(historicalReplay = true) +
            profile.promptContext() + ModelClient.buildSystemPromptSuffix()

        assertTrue(historical == assembled)
    }

    @Test
    fun coreConversationPromptAndQingshengStrategyRemainPresent() {
        val base = ModelClient.buildSystemPrompt(SelfProfile(), historicalReplay = true)
        val strategy = QingshengSoulPrompt.guidance()

        assertTrue(base.startsWith("这是历史聊天的回复回放。"))
        assertTrue(base.contains("有话口不等于有问号"))
        assertTrue(base.contains("避免客服腔和万能套话"))
        assertTrue(base.contains(strategy))
        assertTrue(strategy.contains("千人千面的动态适配"))
        assertTrue(base.contains("不要求每轮推进"))
        assertTrue(strategy.contains("不要机械轮换"))
        assertTrue(strategy.contains("只使用账号资料中已确认且允许披露的信息"))
    }
}
