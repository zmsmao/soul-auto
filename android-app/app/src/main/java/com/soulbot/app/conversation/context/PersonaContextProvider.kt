package com.soulbot.app.conversation.context

import android.content.Context
import com.soulbot.app.conversation.ReplyScene
import com.soulbot.app.conversation.SelfProfile
import com.soulbot.app.data.ChatLearningDatabase
import com.soulbot.app.data.ReplyReplayDatabase
import com.soulbot.app.data.Prefs
import com.soulbot.app.model.ReplyPreferencePrompt
import com.soulbot.app.model.ReplyReplayLogic

/** User identity and writing style are deliberately separate from contact memories. */
internal object PersonaContextProvider {
    fun fragments(
        context: Context,
        profile: SelfProfile,
        learningDatabase: ChatLearningDatabase?,
        replyReplayDatabase: ReplyReplayDatabase?,
        query: String,
        scene: ReplyScene,
        excludeContextKey: String,
    ): List<ContextFragment> {
        val result = mutableListOf<ContextFragment>()
        val identity = profile.promptContext()
        if (identity.isNotEmpty()) {
            result += ContextFragment(
                id = "persona-identity",
                content = identity,
                priority = 100,
                order = 0,
                required = true,
            )
        }
        val db = learningDatabase ?: return result
        if (!Prefs.getStyleLearningEnabled(context)) return result
        val styleProfile = db.styleProfile(scene)
        val preferences = runCatching {
            replyReplayDatabase?.relevantExamples(query, scene, 3, excludeContextKey)
        }.getOrNull().orEmpty()
        val examples = if (preferences.isEmpty()) db.relevantStyleSamples(query, 3, scene) else emptyList()
        val dislikedHints = db.dislikedStyleHints(scene)

        if (dislikedHints.isNotEmpty()) {
            result += ContextFragment(
                id = "persona-disliked-style",
                content = "\n用户对这个场景明确不喜欢的表达类型：" +
                    dislikedHints.joinToString("、") + "。这些是表达偏好，不是对方的个人信息。",
                priority = 72,
                order = 20,
            )
        }
        if (styleProfile.sampleCount > 0) {
            result += ContextFragment(
                id = "persona-style-profile",
                content = "\n\n这是用户本人聊天风格档案，只模仿表达习惯，不要声称自己是某个角色，也不要照抄事实或整句。\n" +
                    styleProfile.promptLine() +
                    "不要刻意重复口头词、收尾或固定句式；这些统计不是字数或提问配额。",
                priority = 70,
                order = 30,
            )
        }
        if (examples.isNotEmpty()) {
            result += ContextFragment(
                id = "persona-style-examples",
                content = buildString {
                    append("\n下面是用户本人过去的人工回复样本。只参考长度、措辞和节奏，不要照抄其中的人名、事实或句子。\n")
                    examples.forEach { sample ->
                        append("\n对方：").append(sample.theirMessage)
                        append("\n我：").append(sample.myMessage)
                    }
                },
                priority = 42,
                order = 40,
            )
        }
        val preferencePrompt = ReplyPreferencePrompt.build(preferences)
        if (preferencePrompt.isNotEmpty()) {
            result += ContextFragment(
                id = "persona-reviewed-preferences",
                content = preferencePrompt,
                priority = 48,
                order = 50,
            )
        }
        return result
    }
}
