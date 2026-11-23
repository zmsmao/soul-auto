package com.soulbot.app.app

import android.os.Bundle
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.soulbot.app.R
import com.soulbot.app.conversation.ConversationState
import com.soulbot.app.data.ChatLearningDatabase
import com.soulbot.app.data.ConversationContactSummary
import com.soulbot.app.data.ConversationMemoryDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactDetailActivity : BaseSettingsActivity() {
    private lateinit var memoryDatabase: ConversationMemoryDatabase
    private lateinit var learningDatabase: ChatLearningDatabase
    private lateinit var threadId: String
    private lateinit var displayName: String
    private lateinit var tvSummary: TextView
    private lateinit var tvIdentity: TextView
    private lateinit var tvLastMessage: TextView
    private lateinit var tvContent: TextView
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_detail)
        threadId = intent.getStringExtra(EXTRA_THREAD_ID).orEmpty()
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { "联系人" }
        setupToolbar(displayName)

        memoryDatabase = ConversationMemoryDatabase(applicationContext)
        learningDatabase = ChatLearningDatabase(applicationContext)
        tvSummary = findViewById(R.id.tvContactDetailSummary)
        tvIdentity = findViewById(R.id.tvContactIdentityType)
        tvLastMessage = findViewById(R.id.tvContactLastMessage)
        tvContent = findViewById(R.id.tvContactSectionContent)

        if (threadId.isBlank()) {
            tvContent.text = "联系人身份无效，请返回联系人列表重新打开。"
            return
        }
        renderSummary()
        findViewById<Chip>(R.id.chipContactMemory).setOnClickListener {
            selectSection(R.id.chipContactMemory)
        }
        findViewById<Chip>(R.id.chipContactHistory).setOnClickListener {
            selectSection(R.id.chipContactHistory)
        }
        findViewById<Chip>(R.id.chipContactState).setOnClickListener {
            selectSection(R.id.chipContactState)
        }
        selectSection(R.id.chipContactMemory)
    }

    override fun onDestroy() {
        if (::memoryDatabase.isInitialized) memoryDatabase.close()
        if (::learningDatabase.isInitialized) learningDatabase.close()
        super.onDestroy()
    }

    private fun renderSummary() {
        val contact = memoryDatabase.contacts(limit = 500).firstOrNull { it.threadId == threadId }
        if (contact == null) {
            tvSummary.text = "暂时没有可展示的聊天记录。"
            tvLastMessage.text = ""
            return
        }
        tvIdentity.text = if (threadId.startsWith("soul-user:")) {
            "已识别 Soul 用户 ID · 同名联系人也会独立隔离"
        } else {
            "本机临时身份 · 当前根据昵称哈希区分，改名后可能形成新记录"
        }
        tvSummary.text =
            "${contact.messageCount} 条消息\n最近互动：${timeFormat.format(Date(contact.lastActivityAt))}"
        val speaker = if (contact.lastRole == "out") "我" else "对方"
        tvLastMessage.text = "最近一条（$speaker）：${contact.lastMessage}"
    }

    private fun selectSection(sectionId: Int) {
        listOf(
            R.id.chipContactMemory,
            R.id.chipContactHistory,
            R.id.chipContactState,
        ).forEach { id ->
            findViewById<Chip>(id).isChecked = id == sectionId
        }
        tvContent.text = when (sectionId) {
            R.id.chipContactHistory -> renderHistory()
            R.id.chipContactState -> renderStates(memoryDatabase.conversationStates(threadId))
            else -> renderMemories()
        }
    }

    private fun renderMemories(): String {
        val memories = learningDatabase.contactMemories(threadId, 20)
        if (memories.isEmpty()) return "暂无独立记忆片段。\n\n后续会从该联系人的真实互动中积累线索。"
        return memories.mapIndexed { index, value -> "${index + 1}. $value" }.joinToString("\n\n")
    }

    private fun renderHistory(): String {
        val entries = memoryDatabase.contactEntries(threadId, 100).asReversed()
        if (entries.isEmpty()) return "暂无聊天历史。"
        return entries.take(60).joinToString("\n\n") { entry ->
            val speaker = if (entry.role == "out") "我" else "对方"
            val time = timeFormat.format(Date(entry.observedAt))
            "[$time] $speaker：${entry.content}"
        }
    }

    private fun renderStates(states: List<ConversationState>): String {
        if (states.isEmpty()) return "暂无持久化会话状态。\n\n当前话题、最近提问和互动节奏会随着后续聊天逐步形成。"
        return states.joinToString("\n\n") { state ->
            buildString {
                append("场景：").append(sceneLabel(state.scene))
                if (state.currentTopic.isNotBlank()) append("\n当前话题：").append(state.currentTopic)
                append("\n有效话题轮次：").append(state.topicTurnCount)
                if (state.lastQuestion.isNotBlank()) append("\n最近提问：").append(state.lastQuestion)
                if (state.lastBanter.isNotBlank()) append("\n最近玩笑：").append(state.lastBanter)
                append("\n连续低信息回应：").append(state.lowInformationCount)
                append("\n状态更新时间：").append(timeFormat.format(Date(state.updatedAt)))
            }
        }
    }

    private fun sceneLabel(scene: String): String = when (scene) {
        "SOUL_MATCH" -> "灵魂匹配"
        "QIYU" -> "奇遇铃"
        "SQUARE" -> "广场私聊"
        else -> "私聊"
    }

    companion object {
        const val EXTRA_THREAD_ID = "contact_thread_id"
        const val EXTRA_DISPLAY_NAME = "contact_display_name"
    }
}
