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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfProfileTest {
    @Test
    fun promptContextIncludesConfiguredFactsAndVisibilityRules() {
        val prompt = SelfProfile(
            age = "26",
            region = "广州天河",
            zodiac = "双子座",
            occupation = "互联网研发",
            details = "喜欢徒步和做饭",
            height = "172-175cm",
            ageVisibility = ProfileVisibility.ASKED,
            regionVisibility = ProfileVisibility.PUBLIC,
            occupationVisibility = ProfileVisibility.ASKED,
            detailsVisibility = ProfileVisibility.PUBLIC,
            heightVisibility = ProfileVisibility.PRIVATE,
        ).promptContext()

        assertTrue(prompt.contains("年龄：26"))
        assertTrue(prompt.contains("现居地区：广州天河"))
        assertTrue(prompt.contains("职业或工作状态：互联网研发"))
        assertTrue(prompt.contains("喜欢徒步和做饭"))
        assertFalse(prompt.contains("身高：172-175cm"))
        assertTrue(prompt.contains("仅在对方自然问到时简短回答"))
        assertTrue(prompt.contains("寒暄式询问可以大方"))
        assertTrue(prompt.contains("不能猜测、补造"))
    }

    @Test
    fun emptyProfileAddsNoIdentityFacts() {
        val prompt = SelfProfile(
            gender = "",
            age = "",
            region = "",
            zodiac = "",
            occupation = "",
            details = "",
            height = "",
        ).promptContext()
        assertTrue(prompt.isEmpty())
    }

    @Test
    fun privateFieldsNeverEnterPromptContext() {
        val prompt = SelfProfile(
            age = "27",
            region = "广州",
            ageVisibility = ProfileVisibility.PRIVATE,
            regionVisibility = ProfileVisibility.PUBLIC,
        ).promptContext()

        assertFalse(prompt.contains("27"))
        assertTrue(prompt.contains("现居地区：广州"))
    }
}
