package com.soulbot.app.app
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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DataBackupActivity : BaseSettingsActivity() {
    private lateinit var memoryDb: ConversationMemoryDatabase
    private lateinit var structuredMemoryDb: ContactMemoryDatabase
    private lateinit var learningDb: ChatLearningDatabase
    private lateinit var tvStats: TextView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var btnExport: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnExportContactMemory: MaterialButton
    private lateinit var btnImportContactMemory: MaterialButton
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) exportMemory(uri) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importMemory(uri) }

    private val exportContactMemoryLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) exportContactMemory(uri) }

    private val importContactMemoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importContactMemory(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_backup)
        setupToolbar("数据与备份")
        memoryDb = ConversationMemoryDatabase(applicationContext)
        structuredMemoryDb = ContactMemoryDatabase(applicationContext)
        learningDb = ChatLearningDatabase(applicationContext)
        HistoryDatabase(applicationContext).use { history ->
            memoryDb.seedLegacyHistory(history.getAll())
        }
        tvStats = findViewById(R.id.tvConversationMemoryStats)
        progress = findViewById(R.id.progressData)
        btnExport = findViewById(R.id.btnExportConversationMemory)
        btnImport = findViewById(R.id.btnImportConversationMemory)
        btnExportContactMemory = findViewById(R.id.btnExportContactMemory)
        btnImportContactMemory = findViewById(R.id.btnImportContactMemory)

        btnExportContactMemory.setOnClickListener {
            exportContactMemoryLauncher.launch("soulbot-contact-memory-${System.currentTimeMillis()}.json")
        }
        btnImportContactMemory.setOnClickListener {
            importContactMemoryLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<View>(R.id.rowHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnExport.setOnClickListener {
            exportLauncher.launch("soulbot-chat-memory-${System.currentTimeMillis()}.json")
        }
        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<MaterialButton>(R.id.btnClearConversationMemory).setOnClickListener {
            confirmClear(
                "清空完整聊天记忆？",
                "所有联系人的逐条聊天记录都会从本机删除。建议先导出备份；此操作不会删除 Soul 内的消息。",
            ) {
                memoryDb.clearAll()
                structuredMemoryDb.clearAll()
                learningDb.clearContactMemories()
                refreshStats()
                showMessage("聊天记录、旧版线索和长期记忆已清空")
            }
        }
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        if (::memoryDb.isInitialized) refreshStats()
    }

    override fun onDestroy() {
        if (::memoryDb.isInitialized || ::structuredMemoryDb.isInitialized || ::learningDb.isInitialized) {
            executor.execute {
                if (::memoryDb.isInitialized) memoryDb.close()
                if (::structuredMemoryDb.isInitialized) structuredMemoryDb.close()
                if (::learningDb.isInitialized) learningDb.close()
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshStats() {
        val useState = if (Prefs.getContactMemoryEnabled(this)) "回复时会参考" else "仅保存、不用于回复"
        val facts = structuredMemoryDb.globalStats()
        val extraction = if (Prefs.getContactMemoryExtractionEnabled(this)) "自动整理已开启" else "自动整理已关闭"
        tvStats.text = "${memoryDb.contactCount()} 位联系人\n${memoryDb.messageCount()} 条消息 · $useState\n" +
            "${facts.active} 条有效长期记忆 · ${facts.pending} 条待确认 · ${facts.conflicted} 条冲突\n$extraction"
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnExport.isEnabled = !busy
        btnImport.isEnabled = !busy
        btnExportContactMemory.isEnabled = !busy
        btnImportContactMemory.isEnabled = !busy
    }

    private fun exportMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
                output.bufferedWriter(Charsets.UTF_8).use(memoryDb::writeExport)
                memoryDb.messageCount()
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { showMessage("已导出 $it 条聊天记忆", Snackbar.LENGTH_LONG) }
                    .onFailure { showMessage("导出失败：${it.message ?: "未知错误"}", Snackbar.LENGTH_LONG) }
            }
        }
    }


    private fun exportContactMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
                output.bufferedWriter(Charsets.UTF_8).use(structuredMemoryDb::writeExport)
                structuredMemoryDb.globalStats().total
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { showMessage("已导出 $it 条长期记忆及其证据", Snackbar.LENGTH_LONG) }
                    .onFailure { showMessage("导出失败：${it.message ?: "未知错误"}", Snackbar.LENGTH_LONG) }
            }
        }
    }

    private fun importContactMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri) ?: error("无法读取导入文件")
                input.bufferedReader(Charsets.UTF_8).use(structuredMemoryDb::importFrom)
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { imported ->
                    refreshStats()
                    showMessage(
                        "长期记忆导入完成：新增 ${imported.added}，跳过重复 ${imported.skipped}，无效 ${imported.invalid}",
                        Snackbar.LENGTH_LONG,
                    )
                }.onFailure {
                    showMessage("长期记忆导入失败：${it.message ?: "文件格式错误"}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private fun importMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri) ?: error("无法读取导入文件")
                input.bufferedReader(Charsets.UTF_8).use(memoryDb::importFrom)
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { imported ->
                    refreshStats()
                    val invalid = if (imported.invalid > 0) "，无效 ${imported.invalid} 条" else ""
                    showMessage(
                        "导入完成：新增 ${imported.added} 条，跳过重复 ${imported.skipped} 条$invalid",
                        Snackbar.LENGTH_LONG,
                    )
                }.onFailure {
                    showMessage("导入失败：${it.message ?: "文件格式错误"}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }
}
