package com.soulbot.app.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.soulbot.app.R

class RuntimeEnvironmentActivity : BaseSettingsActivity() {
    private lateinit var tvOverallStatus: TextView
    private lateinit var tvOverallDetail: TextView
    private lateinit var tvSoulStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var btnSoulAction: MaterialButton
    private lateinit var btnAccessibilityAction: MaterialButton
    private lateinit var btnOverlayAction: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runtime_environment)
        setupToolbar("权限与运行环境")

        tvOverallStatus = findViewById(R.id.tvOverallStatus)
        tvOverallDetail = findViewById(R.id.tvOverallDetail)
        tvSoulStatus = findViewById(R.id.tvSoulStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnSoulAction = findViewById(R.id.btnSoulAction)
        btnAccessibilityAction = findViewById(R.id.btnAccessibilityAction)
        btnOverlayAction = findViewById(R.id.btnOverlayAction)

        btnSoulAction.setOnClickListener { openSoul() }
        btnAccessibilityAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlayAction.setOnClickListener { openOverlaySettings() }
        findViewById<MaterialButton>(R.id.btnModelAction).setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnDiagnosticsAction).setOnClickListener {
            startActivity(Intent(this, DeviceDiagnosticsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val readiness = RuntimeReadinessProvider.snapshot(this)
        val blockers = readiness.automationStartBlockers()
        val complete = readiness.checklistCompletedCount

        tvOverallStatus.text = when {
            readiness.automationRunning -> "自动化正在运行"
            blockers.isNotEmpty() -> "运行准备尚未完成 · " + complete + " / 4"
            !readiness.overlayGranted -> "自动化条件满足 · 悬浮控制器待授权"
            else -> "自动化启动条件已满足"
        }
        tvOverallDetail.text = when {
            readiness.automationRunning -> "当前任务正在运行。此页面只检查状态，不会更改任务或恢复设置。"
            blockers.isNotEmpty() -> blockers.joinToString("；")
            !readiness.overlayGranted -> "启用悬浮窗权限后，才能打开悬浮控制器并手动启动自动化。"
            else -> "可以打开悬浮控制器并手动启动自动化。"
        }

        setStatus(tvSoulStatus, if (readiness.soulInstalled) "已安装" else "未检测到客户端", readiness.soulInstalled)
        setStatus(
            tvAccessibilityStatus,
            when {
                readiness.accessibilityEnabled && readiness.accessibilityConnected -> "已开启并连接"
                readiness.accessibilityEnabled -> "系统已开启 · 等待服务连接"
                else -> "未开启"
            },
            readiness.accessibilityEnabled && readiness.accessibilityConnected,
        )
        setStatus(tvOverlayStatus, if (readiness.overlayGranted) "已授权" else "未授权", readiness.overlayGranted)
        setStatus(tvModelStatus, readiness.modelSummary, readiness.runnableModelCount > 0)

        btnSoulAction.text = if (readiness.soulInstalled) "打开 Soul" else "检查安装"
        btnAccessibilityAction.text = if (readiness.accessibilityEnabled) "查看系统设置" else "前往授权"
        btnOverlayAction.text = if (readiness.overlayGranted) "已授权" else "前往授权"
        btnOverlayAction.isEnabled = !readiness.overlayGranted
    }

    private fun setStatus(view: TextView, value: String, good: Boolean) {
        view.text = value
        view.setTextColor(getColor(if (good) R.color.state_success else R.color.state_warning))
    }

    private fun openSoul() {
        val launchIntent = packageManager.getLaunchIntentForPackage(RuntimeReadinessProvider.SOUL_PACKAGE)
        if (launchIntent == null) {
            showMessage("未检测到 Soul 客户端，请先安装后返回此页面。")
        } else {
            startActivity(launchIntent)
        }
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            showMessage("悬浮窗权限已授权")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName),
            ),
        )
    }
}
