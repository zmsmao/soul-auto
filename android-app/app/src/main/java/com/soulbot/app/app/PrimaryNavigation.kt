package com.soulbot.app.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.soulbot.app.R

internal object PrimaryNavigation {
    fun attach(
        activity: AppCompatActivity,
        navigation: BottomNavigationView,
        selectedItemId: Int,
    ) {
        navigation.selectedItemId = selectedItemId
        navigation.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_contacts -> ContactsActivity::class.java
                R.id.nav_learning -> ReplyLearningActivity::class.java
                R.id.nav_settings -> SettingsHubActivity::class.java
                else -> null
            }
            if (target != null && activity::class.java != target) {
                activity.startActivity(
                    Intent(activity, target).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }
            true
        }
    }
}
