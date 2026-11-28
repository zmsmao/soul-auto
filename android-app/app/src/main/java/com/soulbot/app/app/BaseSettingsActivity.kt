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

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar

abstract class BaseSettingsActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        PrimaryNavigation.requestInsets(this)
    }

    protected fun setupToolbar(title: String) {
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            this.title = title
            setNavigationOnClickListener { finish() }
        }
        applyToolbarInsets()
    }

    protected fun setupPrimaryNavigation(selectedItemId: Int) {
        PrimaryNavigation.attach(
            this,
            findViewById(com.soulbot.app.R.id.bottomNavigation),
            selectedItemId,
        )
    }

    protected fun showMessage(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(findViewById(android.R.id.content), message, duration).show()
    }

    protected fun confirmClear(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ -> action() }
            .show()
    }

    protected fun View.visible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}

fun AppCompatActivity.applyToolbarInsets() {
    val toolbar = findViewById<View>(R.id.toolbar)
    val initialHeight = toolbar.layoutParams.height
    val initialTopPadding = toolbar.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        view.setPadding(view.paddingLeft, initialTopPadding + top, view.paddingRight, view.paddingBottom)
        view.layoutParams = view.layoutParams.apply { height = initialHeight + top }
        insets
    }
    ViewCompat.requestApplyInsets(toolbar)
}
