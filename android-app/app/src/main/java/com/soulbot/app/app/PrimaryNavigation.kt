package com.soulbot.app.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.soulbot.app.R

internal object PrimaryNavigation {
    fun attach(
        activity: AppCompatActivity,
        navigation: BottomNavigationView,
        selectedItemId: Int,
    ) {
        // Keep all primary pages on the same edge-to-edge layout policy.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        applyInboxHeaderInsets(activity)
        applyBottomNavigationInsets(activity)
        requestInsets(activity)

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

    fun requestInsets(activity: AppCompatActivity) {
        // Insets can change while another app is in the foreground. Explicitly
        // request a fresh dispatch whenever one of the primary pages resumes.
        ViewCompat.requestApplyInsets(activity.window.decorView)
    }

    private fun applyInboxHeaderInsets(activity: AppCompatActivity) {
        val header = activity.findViewById<View?>(R.id.inboxHeader) ?: return
        val initialLeft = header.paddingLeft
        val initialTop = header.paddingTop
        val initialRight = header.paddingRight
        val initialBottom = header.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            view.setPadding(
                initialLeft,
                initialTop + topInset,
                initialRight,
                initialBottom,
            )
            insets
        }
    }

    private fun applyBottomNavigationInsets(activity: AppCompatActivity) {
        val container = activity.findViewById<View?>(R.id.bottomNavigationContainer) ?: return
        val initialParams = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val initialStart = initialParams.marginStart
        val initialEnd = initialParams.marginEnd
        val initialBottom = initialParams.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                val targetStart = initialStart + safeInsets.left
                val targetEnd = initialEnd + safeInsets.right
                val targetBottom = initialBottom + safeInsets.bottom
                if (
                    params.marginStart != targetStart ||
                    params.marginEnd != targetEnd ||
                    params.bottomMargin != targetBottom
                ) {
                    params.setMarginStart(targetStart)
                    params.setMarginEnd(targetEnd)
                    params.bottomMargin = targetBottom
                    view.layoutParams = params
                }
            }
            insets
        }
    }
}
