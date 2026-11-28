package com.soulbot.app.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.soulbot.app.R

class SettingsHubActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_hub)
        setupToolbar("设置")
        setupPrimaryNavigation(R.id.nav_settings)

        findViewById<View>(R.id.rowTaskSettings).setOnClickListener { open(TaskSettingsActivity::class.java) }
        findViewById<View>(R.id.rowRuntimeEnvironment).setOnClickListener {
            open(RuntimeEnvironmentActivity::class.java)
        }
        findViewById<View>(R.id.rowModelSettings).setOnClickListener { open(ModelSettingsActivity::class.java) }
        findViewById<View>(R.id.rowProfileSettings).setOnClickListener { open(ProfileSettingsActivity::class.java) }
        findViewById<View>(R.id.rowDataBackup).setOnClickListener { open(DataBackupActivity::class.java) }
        findViewById<View>(R.id.rowDeviceDiagnostics).setOnClickListener { open(DeviceDiagnosticsActivity::class.java) }
        findViewById<View>(R.id.rowRuntimeLog).setOnClickListener { open(RuntimeLogActivity::class.java) }
        findViewById<View>(R.id.rowAppUpdate).setOnClickListener { open(AppUpdateActivity::class.java) }
    }

    private fun <T> open(activity: Class<T>) {
        startActivity(Intent(this, activity))
    }
}
