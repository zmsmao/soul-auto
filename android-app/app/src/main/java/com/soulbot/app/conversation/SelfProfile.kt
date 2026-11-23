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

enum class ProfileVisibility(val label: String) {
    PUBLIC("主动公开"),
    ASKED("被问时回答"),
    PRIVATE("不公开");

    companion object {
        fun fromStored(value: String?): ProfileVisibility =
            values().firstOrNull { it.name == value } ?: ASKED
    }
}

data class SelfProfile(
    val gender: String = "男",
    val age: String = "",
    val region: String = "",
    val zodiac: String = "",
    val occupation: String = "",
    val details: String = "",
    val height: String = "",
    val genderVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val ageVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val regionVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val zodiacVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val occupationVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val detailsVisibility: ProfileVisibility = ProfileVisibility.ASKED,
    val heightVisibility: ProfileVisibility = ProfileVisibility.ASKED,
) {
    fun promptContext(): String {
        data class Fact(val label: String, val value: String, val visibility: ProfileVisibility)

        val facts = listOf(
            Fact("性别", gender, genderVisibility),
            Fact("年龄", age, ageVisibility),
            Fact("现居地区", region, regionVisibility),
            Fact("星座", zodiac, zodiacVisibility),
            Fact("身高", height, heightVisibility),
            Fact("职业或工作状态", occupation, occupationVisibility),
            Fact("兴趣、生活习惯与其他资料", details, detailsVisibility),
        ).mapNotNull { fact ->
            fact.value.trim().takeIf(String::isNotEmpty)?.let { fact.copy(value = it.take(300)) }
        }
        val publicFacts = facts.filter { it.visibility == ProfileVisibility.PUBLIC }
        val askedFacts = facts.filter { it.visibility == ProfileVisibility.ASKED }
        if (publicFacts.isEmpty() && askedFacts.isEmpty()) return ""

        return buildString {
            append("\n\n【账号人设卡：仅包含已确认资料】\n")
            if (publicFacts.isNotEmpty()) {
                append("可在合适语境自然提及，不必刻意展示：\n")
                append(publicFacts.joinToString("\n") { "- ${it.label}：${it.value}" })
                append("\n")
            }
            if (askedFacts.isNotEmpty()) {
                append("仅在对方自然问到时简短回答，不主动查户口式介绍：\n")
                append(askedFacts.joinToString("\n") { "- ${it.label}：${it.value}" })
                append("\n")
            }
            append("寒暄式询问可以大方、简短地回答已配置且允许披露的资料，再视语境自然问回对方；不要把普通问题当成冒犯。")
            append("未配置或标为不公开的资料不能猜测、补造或从其他联系人记录中借用；不要用固定的“暂时不说”挡住整段聊天，可以轻松转向相近话题。")
        }
    }
}
