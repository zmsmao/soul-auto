package com.soulbot.app.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import com.soulbot.app.R
import com.soulbot.app.data.ConversationContactSummary
import com.soulbot.app.data.ConversationMemoryDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactsActivity : BaseSettingsActivity() {
    private lateinit var database: ConversationMemoryDatabase
    private lateinit var adapter: ContactAdapter
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var searchInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        setupToolbar("联系人")
        setupPrimaryNavigation(R.id.nav_contacts)

        database = ConversationMemoryDatabase(applicationContext)
        emptyView = findViewById(R.id.tvEmptyContacts)
        countView = findViewById(R.id.tvContactsCount)
        searchInput = findViewById(R.id.etContactSearch)
        val list = findViewById<ListView>(R.id.listContacts)
        adapter = ContactAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, ContactDetailActivity::class.java)
                    .putExtra(ContactDetailActivity.EXTRA_THREAD_ID, item.threadId)
                    .putExtra(ContactDetailActivity.EXTRA_DISPLAY_NAME, item.displayName),
            )
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                renderContacts()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderContacts()
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized) renderContacts()
    }

    override fun onDestroy() {
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }

    private fun renderContacts() {
        val contacts = database.contacts(searchInput.text?.toString().orEmpty())
        adapter.clear()
        adapter.addAll(contacts)
        countView.text = if (contacts.isEmpty()) "最近互动" else "最近互动 · ${contacts.size} 位联系人"
        emptyView.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class ContactAdapter : ArrayAdapter<ConversationContactSummary>(
        this@ContactsActivity,
        R.layout.item_contact,
        mutableListOf(),
    ) {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_contact, parent, false)
            val item = getItem(position) ?: return row
            row.findViewById<TextView>(R.id.tvContactAvatar).text =
                item.displayName.firstOrNull()?.toString() ?: "友"
            row.findViewById<TextView>(R.id.tvContactName).text = item.displayName
            val speaker = if (item.lastRole == "out") "我：" else "对方："
            row.findViewById<TextView>(R.id.tvContactPreview).text =
                speaker + item.lastMessage.replace('\n', ' ').take(70)
            val identityLabel = if (item.threadId.startsWith("soul-user:")) "Soul ID" else "本机临时 ID"
            row.findViewById<TextView>(R.id.tvContactMeta).text =
                "${item.messageCount} 条消息 · ${timeFormat.format(Date(item.lastActivityAt))} · $identityLabel"
            return row
        }
    }
}
