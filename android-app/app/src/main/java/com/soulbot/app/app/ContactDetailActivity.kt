package com.soulbot.app.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.soulbot.app.R
import com.soulbot.app.conversation.ConversationState
import com.soulbot.app.conversation.memory.ContactMemoryExtractionScheduler
import com.soulbot.app.data.ChatLearningDatabase
import com.soulbot.app.data.ContactMemoryDatabase
import com.soulbot.app.data.ContactMemoryEvidence
import com.soulbot.app.data.ContactMemoryFact
import com.soulbot.app.data.ContactMemoryStatus
import com.soulbot.app.data.ConversationMemoryDatabase
import com.soulbot.app.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactDetailActivity : BaseSettingsActivity() {
    private lateinit var memoryDatabase: ConversationMemoryDatabase
    private lateinit var learningDatabase: ChatLearningDatabase
    private lateinit var structuredMemoryDatabase: ContactMemoryDatabase
    private lateinit var threadId: String
    private lateinit var displayName: String
    private lateinit var tvSummary: TextView
    private lateinit var tvIdentity: TextView
    private lateinit var tvContent: TextView
    private lateinit var tvAvatar: TextView
    private lateinit var tvMemorySummary: TextView
    private lateinit var tvLegacyHeading: TextView
    private lateinit var memoryEntriesLayout: LinearLayout
    private lateinit var legacyEntriesLayout: LinearLayout
    private val timeFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
    private var selectedSection = R.id.chipContactMemory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_detail)
        threadId = intent.getStringExtra(EXTRA_THREAD_ID).orEmpty()
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { "联系人" }
        setupToolbar(displayName)

        memoryDatabase = ConversationMemoryDatabase(applicationContext)
        learningDatabase = ChatLearningDatabase(applicationContext)
        structuredMemoryDatabase = ContactMemoryDatabase(applicationContext)
        tvSummary = findViewById(R.id.tvContactDetailSummary)
        tvIdentity = findViewById(R.id.tvContactIdentityType)
        tvContent = findViewById(R.id.tvContactSectionContent)
        tvAvatar = findViewById(R.id.tvContactAvatar)
        tvMemorySummary = findViewById(R.id.tvContactMemorySummary)
        tvLegacyHeading = findViewById(R.id.tvContactLegacyHeading)
        memoryEntriesLayout = findViewById(R.id.layoutContactMemoryEntries)
        legacyEntriesLayout = findViewById(R.id.layoutContactLegacyEntries)
        tvAvatar.text = displayName.firstOrNull()?.toString() ?: "友"

        if (threadId.isBlank()) {
            tvContent.visibility = View.VISIBLE
            tvContent.text = "联系人身份无效，请返回联系人列表重新打开。"
            return
        }

        findViewById<MaterialSwitch>(R.id.switchContactMemoryExtraction).apply {
            isChecked = Prefs.getContactMemoryExtractionEnabled(this@ContactDetailActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setContactMemoryExtractionEnabled(this@ContactDetailActivity, checked)
                if (checked) {
                    scheduleExistingMessages()
                    showMessage("已开启后台记忆整理；整理结果暂不参与自动回复")
                } else {
                    structuredMemoryDatabase.clearPendingSources()
                    showMessage("已暂停整理，并清除尚未处理的本地队列")
                }
            }
        }

        findViewById<Chip>(R.id.chipContactMemory).setOnClickListener {
            selectSection(R.id.chipContactMemory)
        }
        findViewById<Chip>(R.id.chipContactHistory).setOnClickListener {
            selectSection(R.id.chipContactHistory)
        }
        findViewById<Chip>(R.id.chipContactState).setOnClickListener {
            selectSection(R.id.chipContactState)
        }
        renderSummary()
        selectSection(R.id.chipContactMemory)
    }

    override fun onResume() {
        super.onResume()
        if (::structuredMemoryDatabase.isInitialized && threadId.isNotBlank()) {
            renderSummary()
            if (selectedSection == R.id.chipContactMemory) renderMemoryTimeline()
        }
    }

    override fun onDestroy() {
        if (::memoryDatabase.isInitialized) memoryDatabase.close()
        if (::learningDatabase.isInitialized) learningDatabase.close()
        if (::structuredMemoryDatabase.isInitialized) structuredMemoryDatabase.close()
        super.onDestroy()
    }

    private fun renderSummary() {
        val contact = memoryDatabase.contacts(limit = 500).firstOrNull { it.threadId == threadId }
        if (contact == null) {
            tvSummary.text = "还没有同步到聊天记录"
            tvIdentity.text = "联系人身份暂不可用"
            return
        }
        tvIdentity.text = if (threadId.startsWith("soul-user:")) {
            "稳定身份 · 同名联系人独立保存"
        } else {
            "本机临时身份 · 昵称变更后可能形成新记录"
        }
        val stats = structuredMemoryDatabase.statsForThread(threadId)
        val legacyCount = learningDatabase.contactMemoryEntries(threadId, 300).size
        tvSummary.text = "${contact.messageCount} 条消息 · ${stats.active} 条有效记忆 · ${stats.pending + stats.conflicted} 条待处理"
        if (legacyCount > 0) tvSummary.append(" · ${legacyCount} 条旧版线索")
    }

    private fun selectSection(sectionId: Int) {
        selectedSection = sectionId
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

        memoryEntriesLayout.removeAllViews()
        memoryEntriesLayout.visibility = View.GONE
        legacyEntriesLayout.removeAllViews()
        legacyEntriesLayout.visibility = View.GONE
        tvLegacyHeading.visibility = View.GONE
        tvMemorySummary.visibility = View.GONE
        tvContent.visibility = View.VISIBLE
        tvContent.text = when (sectionId) {
            R.id.chipContactHistory -> renderHistory()
            R.id.chipContactState -> renderStates(memoryDatabase.conversationStates(threadId))
            else -> ""
        }
    }

    private fun renderMemoryTimeline() {
        val facts = structuredMemoryDatabase.factsForThread(threadId, includeInactive = true, limit = 300)
        val legacy = learningDatabase.contactMemoryEntries(threadId, 100)
        memoryEntriesLayout.removeAllViews()
        legacyEntriesLayout.removeAllViews()
        tvContent.visibility = if (facts.isEmpty() && legacy.isEmpty()) View.VISIBLE else View.GONE
        tvContent.text = "还没有整理出长期记忆。开启自动整理后，系统会从新的聊天内容中提取有原文依据的信息。"
        tvMemorySummary.visibility = View.VISIBLE
        val stats = structuredMemoryDatabase.statsForThread(threadId)
        tvMemorySummary.text = "${stats.active} 条有效 · ${stats.pending} 条待确认 · ${stats.conflicted} 条冲突"
        memoryEntriesLayout.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE

        facts.forEachIndexed { index, fact ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_contact_memory, memoryEntriesLayout, false)
            card.findViewById<TextView>(R.id.tvMemoryOrder).text = (index + 1).toString()
            card.findViewById<TextView>(R.id.tvMemoryCategory).text = fact.category.label
            card.findViewById<TextView>(R.id.tvMemoryStatus).apply {
                text = fact.status.label
                setTextColor(statusColor(fact.status))
            }
            card.findViewById<TextView>(R.id.tvMemoryTime).text =
                timeFormat.format(Date(if (fact.lastConfirmedAt > 0) fact.lastConfirmedAt else fact.createdAt))
            card.findViewById<TextView>(R.id.tvMemoryContent).text = fact.content
            card.findViewById<TextView>(R.id.tvMemoryEvidence).text =
                "${fact.evidenceCount} 条原文证据 · ${evidenceLabel(fact.evidenceLevel)} · 点击管理"
            card.setOnClickListener { showFactActions(fact) }
            memoryEntriesLayout.addView(card)
        }

        tvLegacyHeading.visibility = if (legacy.isEmpty()) View.GONE else View.VISIBLE
        legacyEntriesLayout.visibility = if (legacy.isEmpty()) View.GONE else View.VISIBLE
        legacy.forEachIndexed { index, entry ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_contact_memory, legacyEntriesLayout, false)
            card.findViewById<TextView>(R.id.tvMemoryOrder).text = (index + 1).toString()
            card.findViewById<TextView>(R.id.tvMemoryCategory).text = "旧版聊天线索"
            card.findViewById<TextView>(R.id.tvMemoryStatus).text = "未经核实"
            card.findViewById<TextView>(R.id.tvMemoryTime).text = timeFormat.format(Date(entry.createdAt))
            card.findViewById<TextView>(R.id.tvMemoryContent).text = entry.content
            card.findViewById<TextView>(R.id.tvMemoryEvidence).text = "旧版记录 · 没有独立证据关联 · 不参与新记忆整理"
            card.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("旧版聊天线索")
                    .setMessage(entry.content + "\n\n该记录来自旧版记忆表，没有消息级证据关联。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
            legacyEntriesLayout.addView(card)
        }
    }

    private fun showFactActions(fact: ContactMemoryFact) {
        val actions = when (fact.status) {
            ContactMemoryStatus.ACTIVE -> arrayOf("查看原始证据", "编辑 / 纠正", "标记为错误", "删除")
            ContactMemoryStatus.CONFLICTED -> arrayOf("查看原始证据", "确认这条记忆", "编辑 / 纠正", "标记为错误", "删除")
            ContactMemoryStatus.PENDING -> arrayOf("查看原始证据", "确认这条记忆", "编辑 / 纠正", "标记为错误", "删除")
            else -> arrayOf("查看原始证据", "重新确认", "编辑 / 纠正", "删除")
        }
        AlertDialog.Builder(this)
            .setTitle(fact.content)
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "查看原始证据" -> showEvidence(fact)
                    "确认这条记忆", "重新确认" -> {
                        if (structuredMemoryDatabase.confirmFact(fact.id)) {
                            showMessage("已确认；同一事实的其他冲突版本已标记为被替代")
                            renderSummary()
                            renderMemoryTimeline()
                        }
                    }
                    "编辑 / 纠正" -> editFact(fact)
                    "标记为错误" -> confirmReject(fact)
                    "删除" -> confirmClear("删除这条记忆？", "记忆及其证据关联会从本机删除。") {
                        structuredMemoryDatabase.deleteFact(fact.id)
                        renderSummary()
                        renderMemoryTimeline()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEvidence(fact: ContactMemoryFact) {
        val evidence = structuredMemoryDatabase.evidenceForFact(fact.id)
        val message = if (evidence.isEmpty()) {
            "没有可用的原始证据。"
        } else {
            evidence.joinToString("\n\n") { item ->
                val scene = sceneLabel(item.scene)
                "[${timeFormat.format(Date(item.observedAt))} · $scene]\n“${item.sourceQuote}”"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("原始证据 · ${evidence.size} 条")
            .setMessage(message)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun editFact(fact: ContactMemoryFact) {
        val editor = EditText(this).apply {
            setText(fact.content)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
            setPadding(28, 22, 28, 22)
        }
        AlertDialog.Builder(this)
            .setTitle("纠正记忆")
            .setMessage("保存后会标记为人工确认。原始证据仍会保留，方便追溯。")
            .setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                if (structuredMemoryDatabase.updateFactContent(fact.id, editor.text.toString())) {
                    showMessage("已保存人工纠正")
                    renderSummary()
                    renderMemoryTimeline()
                } else {
                    showMessage("内容不能为空")
                }
            }
            .show()
    }

    private fun confirmReject(fact: ContactMemoryFact) {
        AlertDialog.Builder(this)
            .setTitle("标记为错误")
            .setMessage("该记忆将被排除，不会作为有效事实使用。原始证据保留，便于审查。")
            .setNegativeButton("取消", null)
            .setPositiveButton("标记错误") { _, _ ->
                structuredMemoryDatabase.rejectFact(fact.id)
                renderSummary()
                renderMemoryTimeline()
                showMessage("已排除这条记忆")
            }
            .show()
    }

    private fun scheduleExistingMessages() {
        val entries = memoryDatabase.contactEntries(threadId, 120)
            .filter { it.role == "in" }
        entries.groupBy { it.scene }.forEach { (scene, messages) ->
            ContactMemoryExtractionScheduler.enqueue(
                applicationContext, threadId, scene, messages,
            )
        }
    }

    private fun statusColor(status: ContactMemoryStatus): Int {
        val colorRes = when (status) {
            ContactMemoryStatus.ACTIVE -> R.color.apple_blue
            ContactMemoryStatus.PENDING -> R.color.state_warning
            ContactMemoryStatus.CONFLICTED -> R.color.state_danger
            else -> R.color.apple_secondary
        }
        return androidx.core.content.ContextCompat.getColor(this, colorRes)
    }

    private fun evidenceLabel(value: String): String = when (value) {
        "USER_CONFIRMED" -> "人工确认"
        "REPEATED" -> "多次提及"
        "DIRECT" -> "直接表达"
        "UNCERTAIN" -> "信息不确定"
        else -> "来源已记录"
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
