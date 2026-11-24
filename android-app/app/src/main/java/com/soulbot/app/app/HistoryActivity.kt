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

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

class HistoryActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setupToolbar("互动记录")

        val items = HistoryDatabase(this).use { database ->
            database.getAll()
        }
        val texts = items.map { it.display() }
        val listView = findViewById<ListView>(R.id.listHistory)
        listView.adapter = ArrayAdapter(this, R.layout.item_history, R.id.tvItem, texts)
        findViewById<android.widget.TextView>(R.id.tvEmptyHistory).visibility =
            if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun HistoryItem.display(): String =
        "【${name}】${HistoryDatabase.formatTime(time)}\n" +
            "对方：${theirMsg}\n" +
            "我：${myMsg}"
}
