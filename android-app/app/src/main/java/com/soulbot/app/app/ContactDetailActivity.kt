package com.soulbot.app.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
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
    private lateinit var tvAvatar: TextView
    private lateinit var memoryEntriesLayout: LinearLayout
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
        tvAvatar = findViewById(R.id.tvContactAvatar)
        memoryEntriesLayout = findViewById(R.id.layoutContactMemoryEntries)
        tvAvatar.text = displayName.firstOrNull()?.toString() ?: "友"

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
        val memoryCount = learningDatabase.contactMemoryEntries(threadId, 300).size
        tvSummary.text =
            "${contact.messageCount} 条消息 · $memoryCount 条记忆线索\n最近互动：${timeFormat.format(Date(contact.lastActivityAt))}"
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
        if (sectionId == R.id.chipContactMemory) {
            renderMemoryTimeline()
            return
        }
        memoryEntriesLayout.visibility = View.GONE
        memoryEntriesLayout.removeAllViews()
        tvContent.visibility = View.VISIBLE
        tvContent.text = when (sectionId) {
            R.id.chipContactHistory -> renderHistory()
            R.id.chipContactState -> renderStates(memoryDatabase.conversationStates(threadId))
            else -> ""
        }
    }

    private fun renderMemoryTimeline() {
        val memories = learningDatabase.contactMemoryEntries(threadId, 100)
        memoryEntriesLayout.removeAllViews()
        if (memories.isEmpty()) {
            memoryEntriesLayout.visibility = View.GONE
            tvContent.visibility = View.VISIBLE
            tvContent.text = "还没有记录到独立记忆片段。\n\n随着双方有实际聊天内容，系统会在本地记录可供后续上下文参考的线索。"
            return
        }

        tvContent.visibility = View.GONE
        memoryEntriesLayout.visibility = View.VISIBLE
        memories.forEachIndexed { index, entry ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_contact_memory, memoryEntriesLayout, false)
            card.findViewById<TextView>(R.id.tvMemoryOrder).text = (index + 1).toString()
            card.findViewById<TextView>(R.id.tvMemoryTime).text =
                timeFormat.format(Date(entry.createdAt))
            card.findViewById<TextView>(R.id.tvMemoryContent).text = entry.content
            memoryEntriesLayout.addView(card)
        }
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
