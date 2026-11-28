package com.soulbot.app.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.soulbot.app.R
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.data.ConversationMemoryDatabase
import com.soulbot.app.queue.ContactTaskQueue
import com.soulbot.app.queue.ContactTaskState
import com.soulbot.app.queue.ContactTaskOutcome
import com.soulbot.app.queue.ContactTaskSummary
import com.soulbot.app.queue.InboxConversationSummary
import com.soulbot.app.queue.InboxFilter
import com.soulbot.app.queue.MessageDeliveryQueue
import com.soulbot.app.queue.MessageQueueState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvRuntimeTitle: TextView
    private lateinit var tvRuntimeDetail: TextView
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSettings: TextView
    private lateinit var tvReadinessSummary: TextView
    private lateinit var tvReadinessDetails: TextView
    private lateinit var btnReadinessDetails: TextView
    private lateinit var btnReadinessToggle: TextView
    private var readinessExpanded = true
    private lateinit var etSearch: EditText
    private lateinit var queueList: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvTabOpen: TextView
    private lateinit var tvTabSnoozed: TextView
    private lateinit var tvTabDone: TextView
    private lateinit var lineOpen: View
    private lateinit var lineSnoozed: View
    private lateinit var lineDone: View
    private lateinit var contactTaskQueue: ContactTaskQueue

    private var selectedFilter = InboxFilter.OPEN
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDashboard()
            refreshHandler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        contactTaskQueue = ContactTaskQueue(applicationContext)

        tvRuntimeTitle = findViewById(R.id.tvRuntimeTitle)
        tvRuntimeDetail = findViewById(R.id.tvRuntimeDetail)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSettings = findViewById(R.id.btnSettings)
        tvReadinessSummary = findViewById(R.id.tvReadinessSummary)
        tvReadinessDetails = findViewById(R.id.tvReadinessDetails)
        btnReadinessDetails = findViewById(R.id.btnReadinessDetails)
        btnReadinessToggle = findViewById(R.id.btnReadinessToggle)
        btnReadinessToggle.setOnClickListener { toggleReadinessDetails() }
        etSearch = findViewById(R.id.etInboxSearch)
        queueList = findViewById(R.id.queueList)
        tvEmpty = findViewById(R.id.tvInboxEmpty)
        tvTabOpen = findViewById(R.id.tvTabOpen)
        tvTabSnoozed = findViewById(R.id.tvTabSnoozed)
        tvTabDone = findViewById(R.id.tvTabDone)
        lineOpen = findViewById(R.id.lineOpen)
        lineSnoozed = findViewById(R.id.lineSnoozed)
        lineDone = findViewById(R.id.lineDone)

        PrimaryNavigation.attach(
            this,
            findViewById<BottomNavigationView>(R.id.bottomNavigation),
            R.id.nav_home,
        )

        findViewById<View>(R.id.btnCompose).setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("cn.soulapp.android")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Snackbar.make(queueList, "未检测到 Soul 客户端", Snackbar.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.tabOpen).setOnClickListener { selectFilter(InboxFilter.OPEN) }
        findViewById<View>(R.id.tabSnoozed).setOnClickListener { selectFilter(InboxFilter.SNOOZED) }
        findViewById<View>(R.id.tabDone).setOnClickListener { selectFilter(InboxFilter.DONE) }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsHubActivity::class.java))
        }
        tvRuntimeDetail.setOnClickListener { openRuntimeEnvironment() }
        btnReadinessDetails.setOnClickListener { openRuntimeEnvironment() }
        btnPrimary.setOnClickListener {
            val readiness = RuntimeReadinessProvider.snapshot(this)
            if (!readiness.canOpenFloatingController) {
                openRuntimeEnvironment()
                return@setOnClickListener
            }
            openFloatingController()
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderInbox()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateTabs()
    }

    override fun onResume() {
        super.onResume()
        PrimaryNavigation.requestInsets(this)
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable)
        contactTaskQueue.close()
        super.onDestroy()
    }

    private fun refreshDashboard() {
        val readiness = RuntimeReadinessProvider.snapshot(this)
        val serviceAvailable = readiness.accessibilityConnected
        val running = readiness.automationRunning
        val rawStatus = if (serviceAvailable) SoulBotService.statusText else "等待服务连接"
        tvRuntimeTitle.text = when {
            running -> "运行中"
            !readiness.accessibilityEnabled -> "未开启无障碍"
            !serviceAvailable -> "等待服务"
            else -> "已停止"
        }
        tvRuntimeTitle.setTextColor(color(if (running) R.color.inbox_status_fg else R.color.inbox_secondary))
        tvRuntimeDetail.text = when {
            running -> FloatingStatusFormatter.detail(
                true, serviceAvailable, rawStatus, SoulBotService.statusDeadline,
                System.currentTimeMillis(),
            )
            !readiness.soulInstalled -> "未检测到 Soul 客户端 · 点击查看运行准备"
            !readiness.accessibilityEnabled -> "无障碍服务未开启 · 点击查看运行准备"
            !serviceAvailable -> "无障碍服务尚未连接 · 点击查看运行准备"
            !readiness.overlayGranted -> "悬浮窗权限未开启 · 点击查看运行准备"
            readiness.runnableModelCount == 0 -> "没有可用模型通道 · 点击查看运行准备"
            rawStatus.isNotBlank() && rawStatus != "用户手动停止" -> rawStatus
            else -> "自动任务尚未运行 · 未处理的联系人任务会保留"
        }
        tvReadinessSummary.text = "运行准备 · " + readiness.checklistCompletedCount + " / 4 项完成"
        tvReadinessDetails.text = listOf(
            if (readiness.soulInstalled) "Soul 已安装" else "Soul 未安装",
            if (readiness.accessibilityEnabled && readiness.accessibilityConnected) "无障碍已连接"
                else if (readiness.accessibilityEnabled) "无障碍等待连接" else "无障碍未开启",
            if (readiness.overlayGranted) "悬浮窗已授权" else "悬浮窗未授权",
            if (readiness.runnableModelCount > 0) "模型可用 " + readiness.runnableModelCount + " 个"
                else "模型未就绪",
        ).joinToString("  ·  ")
        btnPrimary.text = if (readiness.canOpenFloatingController) "打开控制" else "运行准备"
        renderInbox()
    }

    private fun toggleReadinessDetails() {
        readinessExpanded = !readinessExpanded
        tvReadinessDetails.visibility = if (readinessExpanded) View.VISIBLE else View.GONE
        btnReadinessToggle.text = if (readinessExpanded) "收起" else "展开"
        btnReadinessToggle.contentDescription =
            if (readinessExpanded) "收起运行准备详情" else "展开运行准备详情"
    }

    private fun openRuntimeEnvironment() {
        startActivity(Intent(this, RuntimeEnvironmentActivity::class.java))
    }

    private fun openFloatingController() {
        val intent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        RuntimeLog.record(applicationContext, "floating control launch requested from inbox")
        Snackbar.make(btnPrimary, "悬浮控制已打开", Snackbar.LENGTH_SHORT).show()
    }

    private fun selectFilter(filter: InboxFilter) {
        selectedFilter = filter
        updateTabs()
        renderInbox()
    }

    private fun updateTabs() {
        if (!::contactTaskQueue.isInitialized) return
        val counts = contactTaskQueue.counts()
        tvTabOpen.text = "Open  " + (counts[InboxFilter.OPEN] ?: 0)
        tvTabSnoozed.text = "Snoozed  " + (counts[InboxFilter.SNOOZED] ?: 0)
        tvTabDone.text = "Done  " + (counts[InboxFilter.DONE] ?: 0)
        val active = color(R.color.inbox_text)
        val inactive = color(R.color.inbox_secondary)
        tvTabOpen.setTextColor(if (selectedFilter == InboxFilter.OPEN) active else inactive)
        tvTabSnoozed.setTextColor(if (selectedFilter == InboxFilter.SNOOZED) active else inactive)
        tvTabDone.setTextColor(if (selectedFilter == InboxFilter.DONE) active else inactive)
        lineOpen.visibility = if (selectedFilter == InboxFilter.OPEN) View.VISIBLE else View.INVISIBLE
        lineSnoozed.visibility = if (selectedFilter == InboxFilter.SNOOZED) View.VISIBLE else View.INVISIBLE
        lineDone.visibility = if (selectedFilter == InboxFilter.DONE) View.VISIBLE else View.INVISIBLE
    }

    private fun renderInbox() {
        if (!::queueList.isInitialized || !::contactTaskQueue.isInitialized) return
        queueList.removeAllViews()
        val rows = runCatching {
            contactTaskQueue.summaries(selectedFilter, etSearch.text?.toString().orEmpty())
                .map(::toInboxConversationSummary)
        }.getOrDefault(emptyList())
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when {
            etSearch.text?.isNotBlank() == true -> "没有找到匹配的会话"
            selectedFilter == InboxFilter.OPEN -> "没有待处理联系人\n发现私聊未读后会按顺序加入队列"
            selectedFilter == InboxFilter.SNOOZED -> "联系人队列没有暂缓任务"
            else -> "已处理的会话会显示在这里"
        }
        rows.forEachIndexed { index, row ->
            queueList.addView(createConversationRow(row))
            if (index < rows.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(color(R.color.inbox_line))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
                    ).apply { leftMargin = dp(62) }
                }
                queueList.addView(divider)
            }
        }
        updateTabs()
    }

    private fun createConversationRow(item: InboxConversationSummary): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(15))
            minimumHeight = dp(100)
            isClickable = true
            isFocusable = true
            setOnClickListener { showConversationDetails(item) }
        }
        val avatar = TextView(this).apply {
            text = item.displayName.trim().firstOrNull()?.toString() ?: "S"
            gravity = Gravity.CENTER
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColor(item.displayName))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        root.addView(avatar)

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = item.displayName.ifBlank { "未知联系人" }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.inbox_text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val time = TextView(this).apply {
            text = formatTime(item.updatedAt)
            textSize = 11f
            setTextColor(color(R.color.inbox_secondary))
        }
        heading.addView(name)
        heading.addView(time)
        detail.addView(heading)

        val folder = TextView(this).apply {
            text = "▰  Soul · Private Chat"
            textSize = 11f
            setTextColor(color(R.color.inbox_secondary))
            setPadding(0, dp(4), 0, 0)
        }
        detail.addView(folder)

        val preview = TextView(this).apply {
            text = item.preview
            textSize = 13f
            setTextColor(color(R.color.inbox_secondary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, dp(7))
        }
        detail.addView(preview)

        val tag = TextView(this).apply {
            text = rowTag(item)
            textSize = 10f
            setTextColor(color(if (item.state == MessageQueueState.SUPPRESSED_POLICY) {
                R.color.inbox_chip_purple_text
            } else {
                R.color.inbox_chip_blue_text
            }))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(if (item.state == MessageQueueState.SUPPRESSED_POLICY) {
                    R.color.inbox_chip_purple
                } else {
                    R.color.inbox_chip_blue
                }))
            }
        }
        detail.addView(tag)
        root.addView(detail)

        if (item.openCount > 0 && selectedFilter == InboxFilter.OPEN) {
            val badge = TextView(this).apply {
                text = item.openCount.coerceAtMost(99).toString()
                gravity = Gravity.CENTER
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.inbox_badge_fg))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.inbox_badge_bg))
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(8)
                }
            }
            root.addView(badge)
        }
        return root
    }

    private fun toInboxConversationSummary(task: ContactTaskSummary): InboxConversationSummary {
        val state = when (task.state) {
            ContactTaskState.PENDING -> MessageQueueState.PENDING
            ContactTaskState.PROCESSING -> MessageQueueState.PROCESSING
            ContactTaskState.RETRY_WAIT -> MessageQueueState.RETRY_WAIT
            ContactTaskState.VERIFY_SEND -> MessageQueueState.SEND_UNCERTAIN
            ContactTaskState.DONE -> when (task.outcome) {
                ContactTaskOutcome.SENT -> MessageQueueState.SENT
                ContactTaskOutcome.NO_REPLY -> MessageQueueState.NO_REPLY_REQUIRED
                ContactTaskOutcome.MANUAL -> MessageQueueState.HANDLED_MANUALLY
                null -> MessageQueueState.HANDLED_MANUALLY
            }
        }
        val subtitle = when (task.state) {
            ContactTaskState.PENDING -> "私聊联系人 · 等待按序处理"
            ContactTaskState.PROCESSING -> "私聊联系人 · 正在处理"
            ContactTaskState.RETRY_WAIT -> "私聊联系人 · 等待重试"
            ContactTaskState.VERIFY_SEND -> "私聊联系人 · 发送结果待确认"
            ContactTaskState.DONE -> when (task.outcome) {
                ContactTaskOutcome.SENT -> "本轮已成功回复"
                ContactTaskOutcome.NO_REPLY -> "AI 判断本轮无需回复"
                ContactTaskOutcome.MANUAL -> "已人工标记处理"
                null -> "本轮已处理"
            }
        }
        return InboxConversationSummary(
            threadId = task.contactKey,
            displayName = task.displayName,
            preview = subtitle,
            updatedAt = task.updatedAt,
            openCount = if (task.state == ContactTaskState.DONE) 0 else 1,
            state = state,
        )
    }
    private fun showConversationDetails(item: InboxConversationSummary) {
        val events = deliveryQueue.eventsForThread(item.threadId)
        if (events.isEmpty()) {
            Snackbar.make(queueList, "该会话暂无已采集的消息事件", Snackbar.LENGTH_SHORT).show()
            return
        }
        val details = events.take(24).joinToString("\n\n") { event ->
            buildString {
                append("[")
                append(queueStateLabel(event.state))
                append("] ")
                append(formatTime(event.observedAt))
                append("\n")
                append(event.text)
                if (event.attemptCount > 0) {
                    append("\n重试次数：")
                    append(event.attemptCount)
                }
                if (event.lastError.isNotBlank()) {
                    append("\n记录：")
                    append(event.lastError)
                }
            }
        }
        val uncertain = events.filter { it.state == MessageQueueState.SEND_UNCERTAIN }
            .map { it.key }
        val retryable = events.filter {
            it.state == MessageQueueState.RETRY_WAIT || it.state == MessageQueueState.DEAD_LETTER
        }.map { it.key }
        val manuallyResolvable = events.filter {
            it.state == MessageQueueState.PENDING ||
                it.state == MessageQueueState.RETRY_WAIT ||
                it.state == MessageQueueState.SEND_UNCERTAIN ||
                it.state == MessageQueueState.DEAD_LETTER
        }.map { it.key }
        val snoozed = events.filter { it.state == MessageQueueState.SUPPRESSED_POLICY }.map { it.key }

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.displayName)
            .setMessage(details)
            .setCancelable(true)
        if (selectedFilter == InboxFilter.SNOOZED && snoozed.isNotEmpty()) {
            dialog.setPositiveButton("恢复到 Open") { _, _ ->
                deliveryQueue.unsnooze(item.threadId, snoozed)
                renderInbox()
            }
            dialog.setNegativeButton("关闭", null)
        } else if (uncertain.isNotEmpty()) {
            dialog.setPositiveButton("已确认发送") { _, _ ->
                deliveryQueue.updateEvents(item.threadId, uncertain, MessageQueueState.SENT)
                renderInbox()
            }
            dialog.setNegativeButton("确认未发送后重试") { _, _ ->
                deliveryQueue.retryNow(item.threadId, uncertain)
                renderInbox()
            }
        } else if (retryable.isNotEmpty()) {
            dialog.setPositiveButton("关闭", null)
            dialog.setNegativeButton("立即重试") { _, _ ->
                deliveryQueue.retryNow(item.threadId, retryable)
                renderInbox()
            }
        } else {
            dialog.setPositiveButton("关闭", null)
        }
        if (manuallyResolvable.isNotEmpty()) {
            dialog.setNeutralButton("标记人工已处理") { _, _ ->
                deliveryQueue.resolveAsManual(item.threadId, manuallyResolvable)
                renderInbox()
            }
        }
        dialog.show()
    }

    private fun rowTag(item: InboxConversationSummary): String = when (item.state) {
        MessageQueueState.PENDING -> "待回复 · ${item.openCount} 条"
        MessageQueueState.PROCESSING -> "正在处理"
        MessageQueueState.RETRY_WAIT -> "等待重试"
        MessageQueueState.SEND_UNCERTAIN -> "发送待确认"
        MessageQueueState.DEAD_LETTER -> "需要检查"
        MessageQueueState.NO_REPLY_REQUIRED -> "无需回复 · 已归档"
        MessageQueueState.SUPPRESSED_POLICY -> "暂缓 · 回复节奏"
        MessageQueueState.HANDLED_MANUALLY -> "手动处理"
        MessageQueueState.BASELINE -> "历史记录"
        MessageQueueState.SENT -> "已发送"
    }

    private fun queueStateLabel(state: MessageQueueState): String =
        when (state) {
            MessageQueueState.PENDING -> "待回复"
            MessageQueueState.PROCESSING -> "正在处理"
            MessageQueueState.SENT -> "已发送"
            MessageQueueState.RETRY_WAIT -> "等待重试"
            MessageQueueState.SUPPRESSED_POLICY -> "暂缓"
            MessageQueueState.HANDLED_MANUALLY -> "手动处理"
            MessageQueueState.BASELINE -> "历史记录"
            MessageQueueState.SEND_UNCERTAIN -> "发送待确认"
            MessageQueueState.DEAD_LETTER -> "需要检查"
            MessageQueueState.NO_REPLY_REQUIRED -> "无需回复"
        }

    private fun avatarColor(name: String): Int {
        val palette = intArrayOf(
            Color.rgb(36, 177, 111), Color.rgb(79, 145, 220),
            Color.rgb(160, 91, 194), Color.rgb(228, 119, 83),
            Color.rgb(42, 160, 169), Color.rgb(107, 128, 190),
        )
        return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
    }

    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
        val pattern = if (today == day) "HH:mm" else "MM/dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }

    private fun color(id: Int): Int = getColor(id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
