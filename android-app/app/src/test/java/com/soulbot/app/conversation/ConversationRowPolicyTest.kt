package com.soulbot.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRowPolicyTest {
    @Test
    fun `explicit Soul system notification names are excluded`() {
        val names = listOf(
            "官方通知",
            "系统通知",
            "通知中心",
            "平台通知",
            "Soul官方通知",
            "Soul系统通知",
            "Soul助手",
        )
        names.forEach { name ->
            assertTrue("$name should be classified as a platform notice",
                ConversationRowPolicy.isPlatformNotice(name))
        }
    }

    @Test
    fun `ordinary people and non-notice official themed names are retained`() {
        val names = listOf(
            "小林",
            "官方摄影社的阿杰",
            "Soul伙伴",
            "官方风格研究员",
            "通知我一下",
        )
        names.forEach { name ->
            assertFalse("$name should not be filtered",
                ConversationRowPolicy.isPlatformNotice(name))
        }
    }

    @Test
    fun `normalization handles spacing and punctuation around known notice labels`() {
        assertTrue(ConversationRowPolicy.isPlatformNotice("Soul 官方通知"))
        assertTrue(ConversationRowPolicy.isPlatformNotice("系统通知！"))
    }
}
